package sc6103.client;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import sc6103.common.Codec;
import sc6103.common.Header;
import sc6103.common.Protocol;
import sc6103.common.Replies;
import sc6103.common.Requests;
import sc6103.common.StatusCode;
import sc6103.harness.ScriptedPeer;

/**
 * Behavioural tests for the client's request-reply layer: README sec. 7.2 and sec. 7.3.
 *
 * <p>The conformance suite covers marshalling - whether the right bytes go on the wire.
 * This one covers the layer above it: whether the client retransmits identically, refuses
 * a reply that is not its own, keeps waiting when a callback interrupts, and admits
 * ignorance when nothing arrives. That is the part of the client the brief actually
 * grades, and until now it had no automated coverage at all.
 *
 * <p>Every test drives the real {@link RequestReply} over a real socket against a
 * {@link ScriptedPeer}, so the receive path, the length checks and the deadline
 * arithmetic are all exercised rather than stubbed.
 *
 * <p>Timing note: the tests use short timeouts (120-250 ms) to stay quick, which is well
 * clear of loopback latency but not of a stalled machine. A failure here that names a
 * timeout, on a build that otherwise passes, is worth re-running before it is believed.
 */
public final class ClientBehaviourTest {

    private static int passed;
    private static int failed;

    private interface Action {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        System.out.println("SC6103 client behaviour -- README sec. 7.2, 7.3");
        System.out.println();

        run("RETRY-identical", ClientBehaviourTest::retryIsByteIdentical);
        run("RETRY-exhaustion", ClientBehaviourTest::exhaustionReportsTimeout);
        run("DISCARD-stale", ClientBehaviourTest::staleReplyDiscarded);
        run("DISCARD-foreign", ClientBehaviourTest::foreignClientIdDiscarded);
        run("DISCARD-lengthlie", ClientBehaviourTest::payloadLenDisagreementDiscarded);
        run("DISCARD-magic", ClientBehaviourTest::badMagicDiscarded);
        run("ASYNC-midwait", ClientBehaviourTest::callbackDuringWaitDoesNotEndIt);
        run("ACK-watermark", ClientBehaviourTest::ackUpToStaysBelowRequestId);
        run("MONITOR-gaps", ClientBehaviourTest::monitorReportsGapsAndRepeats);
        run("MONITOR-noend", ClientBehaviourTest::monitorEndsOnLocalTimer);
        run("MONITOR-lateend", ClientBehaviourTest::monitorAcceptsLateEnd);

        System.out.println();
        System.out.println("----------------------------------------------------------");
        System.out.println("assertions: " + (passed + failed)
                + "   passed: " + passed + "   failed: " + failed);
        if (failed > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    // ================================================================
    // Retransmission
    // ================================================================

    /**
     * The bytes of a retransmission are identical to the original, including
     * {@code requestId}.
     *
     * <p>This is the property at-most-once is built on. If {@code requestId} moved between
     * attempts the server could not recognise a duplicate at all, and the guarantee - which
     * lives entirely on the server - would be unimplementable no matter how the server was
     * written. It is asserted byte for byte rather than field by field, because a field
     * comparison would pass a client that quietly re-marshalled with a different
     * {@code ackUpTo}.
     */
    private static void retryIsByteIdentical() throws Exception {
        try (Fixture f = new Fixture(3, 150, 2)) {
            f.peer.then(ScriptedPeer.silence())
                  .then(ScriptedPeer.silence())
                  .then(ScriptedPeer.ok())
                  .start();

            final RequestReply.Outcome o =
                    f.engine.call(Protocol.OP_QUERY_BY_ID, new Requests.Flight(1934)::write);

            checkObj("reply arrived", Boolean.FALSE, o.timedOut);
            checkNum("took three attempts", 3, o.attempts);

            final List<byte[]> raw = f.peer.receivedRaw();
            checkNum("peer saw three datagrams", 3, raw.size());
            checkObj("attempt 2 is byte-identical to attempt 1",
                    Boolean.TRUE, Arrays.equals(raw.get(0), raw.get(1)));
            checkObj("attempt 3 is byte-identical to attempt 1",
                    Boolean.TRUE, Arrays.equals(raw.get(0), raw.get(2)));

            final List<Header> hs = f.peer.received();
            checkNum("requestId is stable across attempts",
                    hs.get(0).requestId, hs.get(2).requestId);
            checkNum("clientId is stable across attempts",
                    hs.get(0).clientId, hs.get(2).clientId);
        }
    }

    /**
     * When nothing ever replies, the outcome is a timeout after {@code maxRetries + 1}
     * transmissions and the client asserts nothing about execution.
     *
     * <p>README sec. 7.2 is explicit that the client cannot distinguish a lost request
     * from a lost reply, so the only honest report is that no reply arrived. The test
     * pins the transmission count because that is what an experiment's retransmission
     * column is counted from.
     */
    private static void exhaustionReportsTimeout() throws Exception {
        try (Fixture f = new Fixture(2, 120, 3)) {
            f.peer.start();   // empty script: never answers

            final RequestReply.Outcome o =
                    f.engine.call(Protocol.OP_QUERY_BY_ID, new Requests.Flight(1934)::write);

            // Four transmissions, not three: README sec. 7.2 loops
            // "for attempt in 0 .. maxRetries" inclusive, so --retries 3 means one
            // original plus three retransmissions. docs/console-script.md sec. 4 shows
            // the same shape, ending "No reply after 4 attempts."
            checkObj("outcome is a timeout", Boolean.TRUE, o.timedOut);
            checkNum("attempts == maxRetries + 1", 4, o.attempts);
            checkObj("no header is offered", null, o.header);
            checkNum("engine counted one timeout", 1, f.engine.timeouts());
            f.peer.awaitRequests(4, 500);
            checkNum("peer saw four datagrams", 4, f.peer.received().size());
        }
    }

    // ================================================================
    // Discard rules
    // ================================================================

    /**
     * A reply carrying a different {@code requestId} is discarded and the wait continues.
     *
     * <p>The scenario is ordinary rather than adversarial: attempt 1's reply arrives after
     * attempt 2 went out. Accepting any reply that happens to arrive would answer the
     * current question with a previous answer, and the failure would be invisible because
     * both replies decode perfectly.
     */
    private static void staleReplyDiscarded() throws Exception {
        try (Fixture f = new Fixture(2, 400, 1)) {
            f.peer.then((request, body, peer) -> {
                      // Answer a requestId the client is not waiting on, then the real one.
                      final RawDatagram wrong = forgeReplyWithRequestId(request, request.requestId - 7);
                      peer.sendRaw(wrong.bytes, wrong.length);
                      Thread.sleep(20);
                      peer.replyStatus(request, StatusCode.OK);
                  }).start();

            final RequestReply.Outcome o =
                    f.engine.call(Protocol.OP_QUERY_BY_ID, new Requests.Flight(1934)::write);

            checkObj("stale reply did not satisfy the call", Boolean.FALSE, o.timedOut);
            checkNum("matched on the first attempt", 1, o.attempts);
            checkNum("the accepted reply carries our requestId",
                    f.peer.received().get(0).requestId, o.header.requestId);
        }
    }

    /**
     * A reply carrying a foreign {@code clientId} is discarded.
     *
     * <p>Identity is the header pair, never the source address (D5). A peer on the right
     * address is not automatically the right client, and this is the check that says so.
     */
    private static void foreignClientIdDiscarded() throws Exception {
        try (Fixture f = new Fixture(2, 400, 1)) {
            f.peer.then((request, body, peer) -> {
                      final RawDatagram forged = forgeReplyWithClientId(request, 0xB004L);
                      peer.sendRaw(forged.bytes, forged.length);
                      Thread.sleep(20);
                      peer.replyStatus(request, StatusCode.OK);
                  }).start();

            final RequestReply.Outcome o =
                    f.engine.call(Protocol.OP_QUERY_BY_ID, new Requests.Flight(1934)::write);

            checkObj("foreign reply did not satisfy the call", Boolean.FALSE, o.timedOut);
            checkNum("the accepted reply is ours", f.engine.clientId(), o.header.clientId);
        }
    }

    /**
     * A datagram whose {@code payloadLen} disagrees with its length is discarded.
     *
     * <p>README sec. 3.6 step 4. This is the check {@code Header.read} cannot perform,
     * because a decoder over a buffer does not know what the socket delivered - so it is
     * the client's, and it is the one that catches truncation, including the silent kind
     * where the OS trims an oversized datagram with no error.
     */
    private static void payloadLenDisagreementDiscarded() throws Exception {
        try (Fixture f = new Fixture(2, 400, 1)) {
            f.peer.then((request, body, peer) -> {
                      // Claim an 8-byte body and send none of it.
                      final byte[] out = new byte[Protocol.MAX_DATAGRAM];
                      final Codec c = Codec.writer(out);
                      Header.reply(request, StatusCode.OK, 8).write(c);
                      peer.sendRaw(out, Protocol.HEADER_SIZE);
                      Thread.sleep(20);
                      peer.replyStatus(request, StatusCode.OK);
                  }).start();

            final RequestReply.Outcome o =
                    f.engine.call(Protocol.OP_QUERY_BY_ID, new Requests.Flight(1934)::write);

            checkObj("length-lying reply was discarded", Boolean.FALSE, o.timedOut);
            checkNum("the accepted reply declares no body", 0, o.header.payloadLen);
        }
    }

    /**
     * A datagram with the wrong magic is discarded silently.
     *
     * <p>A UDP socket receives whatever is addressed to it - broadcast traffic, a scanner,
     * a stray datagram from a previous run of this same program. Without the guard, any of
     * those is parsed as a reply.
     */
    private static void badMagicDiscarded() throws Exception {
        try (Fixture f = new Fixture(2, 400, 1)) {
            f.peer.then((request, body, peer) -> {
                      final byte[] junk = new byte[Protocol.HEADER_SIZE];
                      junk[0] = 'X';
                      junk[1] = 'X';
                      peer.sendRaw(junk, junk.length);
                      Thread.sleep(20);
                      peer.replyStatus(request, StatusCode.OK);
                  }).start();

            final RequestReply.Outcome o =
                    f.engine.call(Protocol.OP_QUERY_BY_ID, new Requests.Flight(1934)::write);

            checkObj("alien datagram was discarded", Boolean.FALSE, o.timedOut);
            checkNum("magic on the accepted reply", Protocol.MAGIC, o.header.magic);
        }
    }

    // ================================================================
    // Unsolicited traffic
    // ================================================================

    /**
     * A CALLBACK arriving mid-wait is dispatched and the wait continues.
     *
     * <p>Callbacks share the client's one socket (README sec. 2.3), so they land in the
     * middle of pending calls as a matter of course. Letting one end the wait would abandon
     * a request that was still likely to be answered, and the symptom would be a spurious
     * timeout that only appears when a monitor is active.
     */
    private static void callbackDuringWaitDoesNotEndIt() throws Exception {
        try (Fixture f = new Fixture(2, 500, 1)) {
            final int[] callbacksSeen = {0};
            f.engine.setAsyncHandler(new RequestReply.AsyncHandler() {
                @Override
                public void onCallback(Header header, Replies.Callback body) {
                    callbacksSeen[0]++;
                }

                @Override
                public void onMonitorEnd(Header header, Replies.MonitorEnd body) {
                }
            });

            f.peer.then((request, body, peer) -> {
                      peer.callback(request.clientId, 99, request.semantics, 1934, 118, 42, 1);
                      Thread.sleep(20);
                      peer.callback(request.clientId, 99, request.semantics, 1934, 117, 43, 2);
                      Thread.sleep(20);
                      peer.replyStatus(request, StatusCode.OK);
                  }).start();

            final RequestReply.Outcome o =
                    f.engine.call(Protocol.OP_QUERY_BY_ID, new Requests.Flight(1934)::write);

            checkObj("call still completed", Boolean.FALSE, o.timedOut);
            checkNum("both callbacks were dispatched", 2, callbacksSeen[0]);
            checkNum("still only one transmission", 1, o.attempts);
        }
    }

    /**
     * {@code ackUpTo} is {@link Protocol#NO_ACK} on the first request and thereafter stays
     * strictly below the {@code requestId} carrying it.
     *
     * <p>The strict inequality is README sec. 8.3's second hard constraint. The server
     * prunes its reply history on receipt, before the duplicate lookup, so a watermark that
     * reached its own request's id would delete the entry that request's retransmission
     * needs - degrading at-most-once to at-least-once silently, under exactly the loss
     * conditions it exists to handle.
     */
    private static void ackUpToStaysBelowRequestId() throws Exception {
        try (Fixture f = new Fixture(4, 400, 1)) {
            f.peer.then(ScriptedPeer.ok()).then(ScriptedPeer.ok()).then(ScriptedPeer.ok())
                  .start();

            for (int i = 0; i < 3; i++) {
                f.engine.call(Protocol.OP_QUERY_BY_ID, new Requests.Flight(1934)::write);
            }

            final List<Header> hs = f.peer.received();
            checkNum("three requests seen", 3, hs.size());
            checkNum("first request acknowledges nothing", Protocol.NO_ACK, hs.get(0).ackUpTo);
            for (int i = 0; i < hs.size(); i++) {
                final Header h = hs.get(i);
                checkObj("req " + h.requestId + " carries ackUpTo < requestId",
                        Boolean.TRUE, h.ackUpTo < h.requestId);
            }
            checkNum("watermark reached the previous reply",
                    hs.get(1).requestId, hs.get(2).ackUpTo);
        }
    }

    // ================================================================
    // Monitor mode
    // ================================================================

    /**
     * The monitor session reports a {@code callbackSeq} gap, names a repeated
     * {@code eventRequestId}, and reconciles against MONITOR_END's total.
     *
     * <p>All three exist because callbacks are fire-and-forget (D10) and nothing recovers a
     * lost one. The client's job is to account for them, so loss appears as a measured
     * quantity rather than as silence a marker would read as a defect.
     */
    private static void monitorReportsGapsAndRepeats() throws Exception {
        try (Fixture f = new Fixture(2, 400, 1)) {
            final long cid = f.engine.clientId();
            final int sem = f.config.semantics;
            f.peer.then((request, body, peer) -> {
                      peer.reply(request, StatusCode.OK, c -> {
                          c.putInt32(120);   // seatsAvailable at registration
                          c.putInt32(1000);  // expiresInMs, advisory only
                      });
                      Thread.sleep(30);
                      peer.callback(cid, request.requestId, sem, 1934, 118, 42, 1);
                      Thread.sleep(20);
                      // seq 2 is never sent: a gap the client must report.
                      peer.callback(cid, request.requestId, sem, 1934, 116, 42, 3);
                      Thread.sleep(20);
                      peer.monitorEnd(cid, request.requestId, sem, 1934, 3);
                  }).start();

            final RequestReply.Outcome reg = f.engine.call(Protocol.OP_MONITOR,
                    new Requests.Monitor(1934, 900)::write);
            checkObj("registration succeeded", Boolean.FALSE, reg.timedOut);

            final Replies.Monitor m = Replies.Monitor.read(reg.body);
            final ByteArrayOutputStream captured = new ByteArrayOutputStream();
            final PrintStream ui = new PrintStream(captured, true, StandardCharsets.UTF_8);
            new MonitorSession(1934, 900, m.seatsAvailable, f.config.timeoutMs, f.trace, ui)
                    .run(f.engine);

            final String text = captured.toString(StandardCharsets.UTF_8);
            checkObj("reports the missing seq=2",
                    Boolean.TRUE, text.contains("gap: callback seq=2"));
            checkObj("names the repeated event",
                    Boolean.TRUE, text.contains("executed req42 twice"));
            checkObj("reconciles 2 of 3 against MONITOR_END",
                    Boolean.TRUE, text.contains("2 of 3 updates received"));
        }
    }

    /**
     * A MONITOR_END that lands just after the local deadline is still used to reconcile.
     *
     * <p>The server sends MONITOR_END from its expiry sweep, a few milliseconds after the
     * interval both sides are timing. With no grace window the client's own deadline won
     * that race in 4 of 10 lossless loopback sessions and reported an understated count.
     */
    private static void monitorAcceptsLateEnd() throws Exception {
        try (Fixture f = new Fixture(2, 400, 1)) {
            final long cid = f.engine.clientId();
            final int sem = f.config.semantics;
            f.peer.then((request, body, peer) -> {
                      peer.reply(request, StatusCode.OK, c -> {
                          c.putInt32(120);
                          c.putInt32(300);
                      });
                      Thread.sleep(30);
                      peer.callback(cid, request.requestId, sem, 1934, 118, 42, 1);
                      // About 360 ms after the reply: past the client's 300 ms deadline and
                      // well inside its 400 ms grace window.
                      Thread.sleep(330);
                      peer.monitorEnd(cid, request.requestId, sem, 1934, 1);
                  }).start();

            final RequestReply.Outcome reg = f.engine.call(Protocol.OP_MONITOR,
                    new Requests.Monitor(1934, 300)::write);
            checkObj("registration succeeded", Boolean.FALSE, reg.timedOut);
            final Replies.Monitor m = Replies.Monitor.read(reg.body);

            final ByteArrayOutputStream captured = new ByteArrayOutputStream();
            final PrintStream ui = new PrintStream(captured, true, StandardCharsets.UTF_8);
            new MonitorSession(1934, 300, m.seatsAvailable, f.config.timeoutMs, f.trace, ui)
                    .run(f.engine);

            final String text = captured.toString(StandardCharsets.UTF_8);
            checkObj("reconciles 1 of 1 against the late MONITOR_END",
                    Boolean.TRUE, text.contains("1 of 1 updates received"));
            checkObj("did not fall back to the local timer",
                    Boolean.FALSE, text.contains("Monitor ended on local timer"));
        }
    }

    /**
     * With no MONITOR_END, the session ends on its own deadline and says the loss count
     * may be understated.
     *
     * <p>MONITOR_END is advisory and can itself be lost (D20), so the local monotonic
     * deadline is authoritative. A client that waited for the server's word would hang
     * exactly when the network is worst, and one that reported a confident total would be
     * quoting a number it cannot know.
     */
    private static void monitorEndsOnLocalTimer() throws Exception {
        try (Fixture f = new Fixture(2, 400, 1)) {
            final long cid = f.engine.clientId();
            final int sem = f.config.semantics;
            f.peer.then((request, body, peer) -> {
                      peer.reply(request, StatusCode.OK, c -> {
                          c.putInt32(120);
                          c.putInt32(300);
                      });
                      Thread.sleep(30);
                      peer.callback(cid, request.requestId, sem, 1934, 118, 42, 1);
                      // No MONITOR_END: the session must end on its own clock.
                  }).start();

            final RequestReply.Outcome reg = f.engine.call(Protocol.OP_MONITOR,
                    new Requests.Monitor(1934, 300)::write);
            final Replies.Monitor m = Replies.Monitor.read(reg.body);

            final ByteArrayOutputStream captured = new ByteArrayOutputStream();
            final PrintStream ui = new PrintStream(captured, true, StandardCharsets.UTF_8);
            final long before = System.nanoTime();
            new MonitorSession(1934, 300, m.seatsAvailable, f.config.timeoutMs, f.trace, ui)
                    .run(f.engine);
            final long elapsedMs = (System.nanoTime() - before) / 1_000_000L;

            final String text = captured.toString(StandardCharsets.UTF_8);
            checkObj("ended on the local timer",
                    Boolean.TRUE, text.contains("Monitor ended on local timer"));
            checkObj("admits the count may be understated",
                    Boolean.TRUE, text.contains("may be understated"));
            checkObj("waited out the interval and the 400 ms grace window",
                    Boolean.TRUE, elapsedMs >= 650);
            // The grace window is bounded, so a MONITOR_END that never comes cannot hold
            // the client indefinitely (D20).
            checkObj("gave up soon after the grace window",
                    Boolean.TRUE, elapsedMs < 1500);
        }
    }

    // ================================================================
    // Fixture and forgery helpers
    // ================================================================

    /** A client engine wired to a scripted peer on loopback, with output suppressed. */
    private static final class Fixture implements AutoCloseable {

        final ScriptedPeer peer;
        final ClientConfig config;
        final TraceLog trace;
        final UdpChannel channel;
        final RequestReply engine;

        Fixture(int maxSteps, int timeoutMs, int retries) throws Exception {
            this.peer = new ScriptedPeer(maxSteps);
            this.config = ClientConfig.parse(new String[]{
                    "--host", InetAddress.getLoopbackAddress().getHostAddress(),
                    "--port", Integer.toString(peer.port()),
                    "--timeout", Integer.toString(timeoutMs),
                    "--retries", Integer.toString(retries),
                    "--seed", "1"});
            // Trace output is discarded: these tests assert on behaviour, and the log
            // schema is TraceLog's own concern. The stream is still real, so a formatting
            // fault in it would surface here as an exception rather than be skipped.
            this.trace = new TraceLog(new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8), 0L);
            final LossSimulator noLoss = new LossSimulator(1L, 0.0, 0.0, false);
            this.channel = new UdpChannel(config.host, config.port, trace);
            this.engine = new RequestReply(channel, config, trace, noLoss,
                    RequestReply.newClientId());
        }

        @Override
        public void close() {
            channel.close();
            peer.close();
        }
    }

    /** A raw datagram built to be wrong in one specific way. */
    private static final class RawDatagram {
        final byte[] bytes;
        final int length;

        RawDatagram(byte[] bytes, int length) {
            this.bytes = bytes;
            this.length = length;
        }
    }

    /**
     * Builds a REPLY with a substituted {@code requestId}.
     *
     * <p>Written by patching bytes rather than through {@link Header}, because the
     * factories deliberately make an inconsistent header hard to construct - which is
     * exactly why a test that needs one has to go around them.
     */
    private static RawDatagram forgeReplyWithRequestId(Header request, int requestId)
            throws Exception {
        final byte[] out = new byte[Protocol.HEADER_SIZE];
        final Codec c = Codec.writer(out);
        Header.reply(request, StatusCode.OK, 0).write(c);
        writeInt32At(out, 24, requestId);
        return new RawDatagram(out, Protocol.HEADER_SIZE);
    }

    /** Builds a REPLY with a substituted {@code clientId}. */
    private static RawDatagram forgeReplyWithClientId(Header request, long clientId)
            throws Exception {
        final byte[] out = new byte[Protocol.HEADER_SIZE];
        final Codec c = Codec.writer(out);
        Header.reply(request, StatusCode.OK, 0).write(c);
        writeInt32At(out, 20, (int) clientId);
        return new RawDatagram(out, Protocol.HEADER_SIZE);
    }

    private static void writeInt32At(byte[] buf, int offset, int v) {
        buf[offset] = (byte) ((v >>> 24) & 0xFF);
        buf[offset + 1] = (byte) ((v >>> 16) & 0xFF);
        buf[offset + 2] = (byte) ((v >>> 8) & 0xFF);
        buf[offset + 3] = (byte) (v & 0xFF);
    }

    // ---- Assertions, matching ConformanceTest's harness ----

    private static void run(String section, Action body) {
        System.out.println("-- " + section + " --");
        try {
            body.run();
        } catch (Throwable t) {
            fail(section + " threw unexpectedly", "no exception",
                    t.getClass().getName() + ": " + t.getMessage());
        }
        System.out.println();
    }

    private static void pass(String name) {
        passed++;
        System.out.println("PASS " + name);
    }

    private static void fail(String name, String expected, String actual) {
        failed++;
        System.out.println("FAIL " + name + ": expected " + expected + " got " + actual);
    }

    private static void checkNum(String name, long expected, long actual) {
        if (expected == actual) {
            pass(name);
        } else {
            fail(name, Long.toString(expected), Long.toString(actual));
        }
    }

    private static void checkObj(String name, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            pass(name);
        } else {
            fail(name, String.valueOf(expected), String.valueOf(actual));
        }
    }
}
