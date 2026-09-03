package sc6103.client;

import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import sc6103.common.Header;
import sc6103.common.Replies;

/**
 * Monitor mode: README sec. 7.3 and docs/console-script.md sec. 5.
 *
 * <p>After a successful MONITOR registration the client blocks for the interval, printing
 * each seat update as it arrives and accepting no user input. The brief permits exactly
 * this - "the client simply waits for the updates from the server during the monitor
 * interval... you do not have to use multiple threads at a client" - and taking that
 * permission is what keeps the client single-threaded (D11).
 *
 * <h2>This class exists to make loss measurable, not to prevent it</h2>
 *
 * Callbacks are fire-and-forget: no acknowledgement, no retry, no history (D10). That is
 * <em>maybe</em> semantics in deck 3's taxonomy, and the report says so. Nothing here
 * recovers a lost callback. What it does instead is account for every one that should
 * have arrived, so that loss appears in the output as a measured quantity rather than as
 * silence a marker would read as a bug.
 *
 * <p>Two independent signals carry that:
 *
 * <ul>
 *   <li><b>{@code callbackSeq} gaps</b> catch losses <em>between</em> updates that did
 *       arrive - seeing 1, 2, 4 proves 3 was lost.</li>
 *   <li><b>MONITOR_END's final count</b> catches losses at the <em>tail</em>, which gaps
 *       cannot see at all. Without it, a run of losses after the last delivered callback
 *       is indistinguishable from a quiet server (D20).</li>
 * </ul>
 *
 * <p>And a third, which is about the other machine rather than this one: a repeated
 * {@code eventRequestId} means one client operation was executed twice. That is the
 * direct observable signature of at-least-once re-execution, and it is what experiment E8
 * reads off this display.
 */
public final class MonitorSession implements RequestReply.AsyncHandler {

    private final int flightId;
    private final int intervalMs;
    private final int baselineSeats;
    private final TraceLog trace;
    private final PrintStream out;

    /** Highest {@code callbackSeq} seen, for gap detection. */
    private int highestSeq;

    private int received;

    /**
     * Which {@code callbackSeq} first reported each {@code eventRequestId}.
     *
     * <p>Insertion-ordered so the end-of-session summary lists repeats in the order they
     * were observed, which is the order a demo audience watched them appear.
     */
    private final Map<Integer, Integer> firstSeqByEvent = new LinkedHashMap<>();

    private int repeatedEvents;

    /** Server's final count, or -1 if MONITOR_END never arrived. */
    private int finalSeq = -1;

    public MonitorSession(int flightId, int intervalMs, int baselineSeats,
                          TraceLog trace, PrintStream out) {
        this.flightId = flightId;
        this.intervalMs = intervalMs;
        this.baselineSeats = baselineSeats;
        this.trace = trace;
        this.out = out;
    }

    /**
     * Blocks for the interval, printing updates, then prints the reconciliation summary.
     *
     * <p><b>The deadline is computed here, from the client's own monotonic clock, and it
     * is authoritative.</b> The server's {@code expiresInMs} is display only: it was
     * computed before the reply travelled over a lossy link, and MONITOR_END may never
     * arrive at all. A client that ended on the server's word rather than its own would
     * hang exactly when the network is worst (D20, README sec. 7.3).
     */
    public void run(RequestReply engine) throws IOException {
        out.printf("Monitoring flight %d for %ds. %d seats at registration.%n",
                flightId, intervalMs / 1000, baselineSeats);
        out.println("Input is blocked until the interval ends.");
        out.println();

        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(intervalMs);

        final RequestReply.AsyncHandler previous = null; // monitor mode owns the socket
        engine.setAsyncHandler(this);
        try {
            final boolean endedByServer = engine.pumpUntil(deadline, () -> finalSeq >= 0);
            printSummary(endedByServer);
        } finally {
            // Leaving the handler installed would send stray callbacks from an expired
            // registration into a session that has already printed its summary.
            engine.setAsyncHandler(previous);
        }
    }

    @Override
    public void onCallback(Header header, Replies.Callback body) {
        if (body.flightId != flightId) {
            // A callback for a different registration this client also holds. Out of scope
            // for this session's arithmetic; counting it would corrupt the gap report.
            trace.ignore("other-monitor", "callback for flight " + body.flightId);
            return;
        }

        received++;
        trace.callback(body.flightId, body.callbackSeq, body.seatsAvailable, body.eventRequestId);
        out.printf("    %d seats%n", body.seatsAvailable);

        // Gap detection. Reported, never hidden: README D10 makes callback loss documented
        // behaviour, and an update that appears as nothing at all reads as a defect to
        // whoever is marking.
        if (body.callbackSeq > highestSeq + 1) {
            for (int missing = highestSeq + 1; missing < body.callbackSeq; missing++) {
                out.printf("           ^ gap: callback seq=%d was not delivered%n", missing);
            }
        }
        if (body.callbackSeq > highestSeq) {
            highestSeq = body.callbackSeq;
        }

        // A repeated eventRequestId is the visible fingerprint of at-least-once
        // re-execution: two callbacks, one user action. Named explicitly because the
        // seat count alone cannot distinguish it from two separate reservations.
        final Integer firstSeq = firstSeqByEvent.putIfAbsent(body.eventRequestId, body.callbackSeq);
        if (firstSeq != null) {
            repeatedEvents++;
            out.printf("           ^ same event as seq=%d - server executed req%d twice%n",
                    firstSeq, body.eventRequestId);
        }
    }

    @Override
    public void onMonitorEnd(Header header, Replies.MonitorEnd body) {
        if (body.flightId != flightId) {
            trace.ignore("other-monitor", "MONITOR_END for flight " + body.flightId);
            return;
        }
        trace.monitorEnd(body.flightId, body.callbackSeq);
        finalSeq = body.callbackSeq;
    }

    /**
     * Reconciles what arrived against what the server says it sent.
     *
     * @param endedByServer true if MONITOR_END ended the wait rather than the local timer
     */
    private void printSummary(boolean endedByServer) {
        out.println();
        if (finalSeq >= 0) {
            final int lost = Math.max(0, finalSeq - received);
            out.printf("Monitor ended. %d of %d updates received, %d lost.%n",
                    received, finalSeq, lost);
        } else {
            // Without the server's total, the only losses visible are the gaps between
            // callbacks that did arrive. Anything dropped after the last one is invisible,
            // so the count is a floor and the wording must not imply otherwise.
            out.println("Monitor ended on local timer. MONITOR_END not received;");
            out.println("the lost-update count may be understated.");
            final int impliedLost = Math.max(0, highestSeq - received);
            out.printf("%d updates received, at least %d lost.%n", received, impliedLost);
        }

        if (repeatedEvents > 0) {
            out.printf("%d update(s) repeated a previous eventRequestId - "
                    + "the server executed those operations more than once.%n", repeatedEvents);
        }
        out.println();
        // endedByServer is deliberately not printed as a status: which of the two ended the
        // wait is a timing accident, not information about the registration.
        trace.note("MONITOR-SUMMARY", "flight=" + flightId + " received=" + received
                + " finalSeq=" + finalSeq + " repeats=" + repeatedEvents
                + " endedBy=" + (endedByServer ? "server" : "local-timer"));
    }
}
