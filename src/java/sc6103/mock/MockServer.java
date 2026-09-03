package sc6103.mock;

// IOException is the checked exception of the DatagramSocket API and nothing more.
// The brief bans java.io *stream* classes as a wire encoding mechanism; only Codec
// touches bytes here, and no stream, reader, writer or object serialization appears
// anywhere in this file.
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import sc6103.common.Codec;
import sc6103.common.CodecException;
import sc6103.common.DepartureTime;
import sc6103.common.Header;
import sc6103.common.Protocol;
import sc6103.common.Replies;
import sc6103.common.Requests;
import sc6103.common.StatusCode;

/**
 * <b>DEVELOPMENT SCAFFOLDING. THIS IS NOT THE PROJECT'S SERVER AND MUST NOT BE GRADED
 * AS ONE.</b>
 *
 * <p>The graded server is the C++17 implementation of README sec. 22.1, scheduled for
 * milestone M6. It does not exist yet. This class exists so that the Java client can be
 * run, driven from the console and demonstrated <em>now</em>, against something that
 * speaks the wire protocol of README Part I correctly. It is a test fixture with a
 * socket attached, and it should be deleted or clearly quarantined before submission.
 *
 * <p>What it does implement, faithfully:
 *
 * <ul>
 *   <li>The 40-byte header of README sec. 3.4, through the shared {@link Header}.</li>
 *   <li>The decoder validation order of README sec. 3.6, including the
 *       {@code received == HEADER_SIZE + payloadLen} agreement check that
 *       {@link Header#read(Codec)} deliberately leaves to its caller.</li>
 *   <li>All six operations of README sec. 4 against an in-memory seeded flight table
 *       (README sec. 6.3), with the status codes of README sec. 5.</li>
 *   <li>The bounded-wait request loop of README sec. 6.1, single-threaded.</li>
 *   <li>The callback subsystem of README sec. 9: registration, replacement,
 *       fire-and-forget delivery on a successful {@code RESERVE} only, lazy plus swept
 *       expiry, and one {@code MONITOR_END} per expired registration.</li>
 *   <li>The request/reply trace both sides are required to print, in the column order
 *       of README sec. 10.2.</li>
 * </ul>
 *
 * <h2>Not implemented - do not mistake this for the real server</h2>
 *
 * <ul>
 *   <li><b>No at-most-once machinery whatsoever.</b> There is no reply history
 *       (README sec. 2.6, sec. 8.2), no request fingerprint (D24), no
 *       {@code ackUpTo} pruning (README sec. 8.3), and therefore no duplicate
 *       filtering. {@code --semantics} is parsed and a client in the opposite mode is
 *       refused with {@link StatusCode#ERR_SEMANTICS_MISMATCH}, purely so the client's
 *       own mismatch handling can be exercised - but a retransmission that reaches
 *       this server <em>re-executes</em> in either mode.
 *       <b>This server behaves as at-least-once regardless of the flag it was given,
 *       so it cannot be used for the invocation-semantics experiments of README
 *       sec. 13.</b> Any E-series number produced against it is meaningless.
 *       {@link StatusCode#ERR_REQUEST_ID_CONFLICT} is never returned.</li>
 *   <li><b>No fault injection.</b> None of {@code --drop-request},
 *       {@code --drop-reply}, {@code --drop-callback}, the {@code --drop-first-*}
 *       one-shot rules or {@code --seed} (README sec. 10.1, sec. 15.2) exist here.
 *       Nothing is ever dropped deliberately, so loss must come from the network or
 *       from the client's own injection.</li>
 *   <li><b>Fixed-capacity tables are not enforced.</b> D17 requires every server table
 *       to be fixed-capacity with no allocation on the request path; the registry here
 *       is an {@link ArrayList} that grows, so
 *       {@link StatusCode#ERR_MONITOR_TABLE_FULL} is never returned and that error
 *       path is untested by anything run against this server.</li>
 *   <li><b>No expected-state oracle.</b> README sec. 2.4 and the
 *       {@code assert_expected_state()} step of the sec. 6.1 loop are absent, so
 *       divergence is not detected here.</li>
 *   <li><b>A header that fails {@link Header#read(Codec)} is dropped, not answered.</b>
 *       The sec. 6.1 pseudocode replies {@link StatusCode#ERR_VERSION_MISMATCH} to a
 *       bad {@code version}, which requires reading {@code clientId} and
 *       {@code requestId} past the failing field. {@code Header.read} aborts before
 *       those are available, so this server takes the other branch README sec. 3.6
 *       step 7 permits - "otherwise log and drop". A real server needs the reply.</li>
 * </ul>
 *
 * <p><b>Every flight in the seed table is invented mock data</b> to be replaced by the
 * real repository. See {@link #seedFlights()}; each row is marked there.
 *
 * <p>Run it as:
 *
 * <pre>
 *   java -cp out sc6103.mock.MockServer --port 5555 [--semantics at-most-once]
 * </pre>
 */
public final class MockServer {

    // ---- Loop tuning ----

    /**
     * Ceiling on one socket wait, matching {@code MAX_POLL_MS} in the README sec. 6.1
     * loop. With no monitor registered there is no expiry to bound the wait, so this is
     * what stops the receive from becoming an unbounded block. It also caps how long a
     * Ctrl-C takes to be noticed.
     */
    private static final int MAX_POLL_MS = 250;

    /**
     * Floor on one socket wait, and the reason it is not zero:
     * {@link DatagramSocket#setSoTimeout(int)} treats 0 as "block forever". A monitor
     * that has just expired produces a remaining time of 0 or less, so without this
     * clamp the one iteration that most needs a bounded wait would take an unbounded
     * one - the exact failure regression test T15 exists to catch.
     */
    private static final int MIN_POLL_MS = 1;

    // ---- Log schema (README sec. 10.2) ----

    /**
     * Column order is fixed by README sec. 10.2 so that logs captured on three machines
     * can be read side by side. The {@code attempt} column is always {@code -} here: a
     * server learns a client's attempt number from a reply-history hit, and this server
     * has no history (see the class note).
     */
    private static final String LOG_FORMAT =
            "%s SERVER %-11s %-8s %-10s %-5s %-18s %-7s %-22s %s%n";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

    // ---- State ----

    private final DatagramSocket socket;

    /** The mode this server refuses to be mismatched on, never the mode it implements. */
    private final int semantics;

    /** Mock flight table. Mutable; see {@link #seedFlights()}. */
    private final List<MockFlight> flights;

    /** Monitor registry of README sec. 2.5. Unbounded here, unlike D17's real table. */
    private final List<Registration> monitors = new ArrayList<>();

    /**
     * Receive buffer, deliberately four bytes larger than {@link Protocol#MAX_DATAGRAM}.
     *
     * <p>A datagram longer than the buffer is silently truncated to the buffer's size by
     * the socket, with no indication that anything was lost. Sized exactly at 512 that
     * truncation would be invisible for the one case that matters - a long datagram
     * whose header happens to claim {@code payloadLen = MAX_BODY} would pass the sec. 3.6
     * step 4 length check and then decode bytes that were never sent. The slack turns
     * "too long" into an observable {@code received > MAX_DATAGRAM}.
     */
    private final byte[] receiveBuffer = new byte[Protocol.MAX_DATAGRAM + 4];

    private MockServer(final int port, final int semantics) throws IOException {
        this.socket = new DatagramSocket(port);
        this.semantics = semantics;
        this.flights = seedFlights();
    }

    // ---- Entry point ----

    /**
     * Parses the command line and runs the loop until the process is killed.
     *
     * <p>Only the two flags this server can honour are accepted. The fault-injection
     * flags of README sec. 15.2 are rejected rather than ignored, because silently
     * accepting {@code --drop-reply 0.3} and then dropping nothing would produce an
     * experiment log that looks valid and is not.
     */
    public static void main(final String[] args) {
        int port = -1;
        int semantics = Protocol.SEM_AT_MOST_ONCE;

        for (int i = 0; i < args.length; i++) {
            final String flag = args[i];
            if ("--port".equals(flag)) {
                final String v = nextValue(args, i, flag);
                if (v == null) {
                    return;
                }
                try {
                    port = Integer.parseInt(v);
                } catch (final NumberFormatException e) {
                    usage("--port " + v + " is not a number");
                    return;
                }
                if (port < 1 || port > 65535) {
                    usage("--port " + port + " outside 1..65535");
                    return;
                }
                i++;
            } else if ("--semantics".equals(flag)) {
                final String v = nextValue(args, i, flag);
                if (v == null) {
                    return;
                }
                if ("at-least-once".equals(v)) {
                    semantics = Protocol.SEM_AT_LEAST_ONCE;
                } else if ("at-most-once".equals(v)) {
                    semantics = Protocol.SEM_AT_MOST_ONCE;
                } else {
                    usage("--semantics " + v + " must be at-least-once or at-most-once");
                    return;
                }
                i++;
            } else {
                usage("unrecognised argument " + flag);
                return;
            }
        }

        if (port < 0) {
            usage("--port is required");
            return;
        }

        try {
            final MockServer server = new MockServer(port, semantics);
            server.announce();
            server.run();
        } catch (final IOException e) {
            // Only a bind failure or an unrecoverable socket fault reaches here; a
            // per-datagram IOException is caught inside the loop, because README
            // sec. 3.6 step 7 requires the server to still be serving other clients
            // after a bad datagram.
            System.err.println("mock server failed: " + e);
            System.exit(1);
        }
    }

    private static String nextValue(final String[] args, final int i, final String flag) {
        if (i + 1 >= args.length) {
            usage(flag + " needs a value");
            return null;
        }
        return args[i + 1];
    }

    private static void usage(final String problem) {
        System.err.println("MockServer: " + problem);
        System.err.println("usage: java sc6103.mock.MockServer --port <n> "
                + "[--semantics at-least-once|at-most-once]");
        System.err.println("This is development scaffolding, not the project server. "
                + "The fault-injection flags of README sec. 15.2 are not implemented.");
    }

    // ---- The loop (README sec. 6.1) ----

    /**
     * Prints the startup banner README sec. 15.2 asks for, so a captured log is
     * self-describing, plus the warning that makes this server's status unmistakable in
     * any transcript it appears in.
     */
    private void announce() {
        System.out.println("=================================================================");
        System.out.println(" SC6103 MOCK SERVER - development scaffolding, NOT the graded");
        System.out.println(" server. No reply history, no duplicate filtering, no fault");
        System.out.println(" injection. It behaves as AT-LEAST-ONCE whatever --semantics");
        System.out.println(" says, so it cannot produce README sec. 13 measurements.");
        System.out.println("=================================================================");
        System.out.println("bound      : " + socket.getLocalSocketAddress());
        System.out.println("semantics  : " + semanticsName(semantics)
                + " (enforced on the wire, NOT implemented in the loop)");
        System.out.println("loss       : none - no fault injection in this build");
        System.out.println("flights    : " + flights.size() + " seeded (mock data)");
        for (final MockFlight f : flights) {
            System.out.println("             " + f);
        }
        // Column header for the trace below. Written out separately rather than through
        // LOG_FORMAT, which has the role column pinned to the literal SERVER; the widths
        // are chosen to line up with it (a HH:mm:ss.SSS stamp is 12 characters).
        System.out.printf(Locale.ROOT,
                "%-12s %-6s %-11s %-8s %-10s %-5s %-18s %-7s %-22s %s%n",
                "time", "role", "event", "dir", "client", "req", "opcode", "attempt",
                "decision", "state");
    }

    /**
     * The request loop of README sec. 6.1.
     *
     * <p><b>The socket wait is bounded by the time to the next monitor expiry.</b> This
     * is the "timer independence" property of README sec. 6.1 and the single easiest
     * thing in the server to get wrong. A plain blocking {@code receive()} would be
     * correct for every request-reply exchange and still be a correctness bug: on an
     * idle server the registrations of README sec. 9 would outlive their interval,
     * {@code MONITOR_END} would never be sent, and expiry would silently become
     * "whenever the next client happens to send something". Regression test T15 exists
     * for exactly this. {@link #pollBudgetMs(long)} computes the bound;
     * {@link SocketTimeoutException} is the normal idle path, not an error.
     *
     * <p>Every expiry decision below uses {@link #nowMs()}, a monotonic clock. Wall-clock
     * time can jump backwards, and a registration whose deadline is in the past by an
     * NTP correction is a bug that reproduces once a month.
     */
    private void run() {
        final DatagramPacket packet =
                new DatagramPacket(receiveBuffer, receiveBuffer.length);

        while (true) {
            try {
                socket.setSoTimeout(pollBudgetMs(nowMs()));

                // receive() sets the packet's length to the bytes actually received, so
                // it must be reset before every call. Without this the buffer shrinks to
                // the size of the smallest datagram seen so far and every later one is
                // truncated.
                packet.setLength(receiveBuffer.length);

                socket.receive(packet);
                processOneDatagram(packet);
            } catch (final SocketTimeoutException idle) {
                // The normal idle path, and the whole point of the bounded wait: no
                // request arrived within the budget, so fall through to the expiry
                // sweep. Deliberately not logged - this fires several times a second on
                // an idle server and would bury the request/reply trace the brief
                // requires. Caught before IOException because it is a subclass of it.
            } catch (final IOException e) {
                // A socket fault on one datagram must not end the server (README
                // sec. 3.6 step 7).
                log("ERROR", "-", "-", "-", "-", "io-failure", e.toString());
            }

            try {
                expireMonitors(nowMs());
            } catch (final IOException e) {
                log("ERROR", "callback", "-", "-", "-", "io-failure", e.toString());
            }
        }
    }

    /**
     * Milliseconds this iteration may wait on the socket: the time to the nearest
     * monitor expiry, capped by {@link #MAX_POLL_MS} and floored by
     * {@link #MIN_POLL_MS}.
     *
     * <p>The floor is load-bearing - see {@link #MIN_POLL_MS}. An already-expired
     * registration yields a negative remainder, and passing that to
     * {@code setSoTimeout} would either throw or, at 0, block forever.
     */
    private int pollBudgetMs(final long now) {
        long budget = MAX_POLL_MS;
        for (final Registration r : monitors) {
            final long left = r.expiresAtMs - now;
            if (left < budget) {
                budget = left;
            }
        }
        if (budget < MIN_POLL_MS) {
            budget = MIN_POLL_MS;
        }
        return (int) budget;
    }

    // ---- Datagram validation (README sec. 3.6) ----

    /**
     * Validates one received datagram in the order README sec. 3.6 fixes, then
     * dispatches it.
     *
     * <p>The order is normative rather than stylistic: each step establishes what the
     * next is allowed to trust. In particular {@code payloadLen} is not believed until
     * magic and version have passed, because on a datagram from an unrelated sender it
     * is not a length at all.
     */
    private void processOneDatagram(final DatagramPacket packet) throws IOException {
        final int received = packet.getLength();
        final InetAddress from = packet.getAddress();
        final int fromPort = packet.getPort();
        final String peer = from.getHostAddress() + ":" + fromPort;

        // Step 1: at least a header's worth of bytes. Nothing below this line may
        // assume a field exists.
        if (received < Protocol.HEADER_SIZE) {
            log("DROP", "request", "-", "-", "-", "short-datagram",
                    "from=" + peer + " bytes=" + received);
            return;
        }

        // See the field note on receiveBuffer: a datagram longer than MAX_DATAGRAM was
        // truncated by the socket, so whatever the header claims, bytes are missing.
        if (received > Protocol.MAX_DATAGRAM) {
            log("DROP", "request", "-", "-", "-", "oversize-datagram",
                    "from=" + peer + " bytes>" + Protocol.MAX_DATAGRAM);
            return;
        }

        // The reader is bounded by the number of bytes the socket reported, NOT by
        // receiveBuffer.length. The buffer is reused across datagrams, so a reader
        // bounded by its capacity would happily read the tail of the *previous*
        // datagram and decode it as though it had arrived - a real bug in reused-buffer
        // UDP loops, and one no conformance vector can catch because the bytes on the
        // wire were correct.
        final Codec in = Codec.reader(packet.getData(), received);

        // Steps 2 to 4 (partial): Header.read verifies magic, version, a known
        // messageType, an in-range opCode on a REQUEST, a known semantics value and a
        // payloadLen within MAX_BODY.
        final Header hdr;
        try {
            hdr = Header.read(in);
        } catch (final CodecException e) {
            // No trustworthy clientId or requestId is available - Header.read aborts at
            // the failing field, and every field it validates sits before or among the
            // identity fields. README sec. 3.6 step 7's second branch applies: log and
            // drop. See the "Not implemented" note about ERR_VERSION_MISMATCH.
            log("DROP", "request", "-", "-", "-", "header-rejected",
                    "from=" + peer + " " + e.getMessage());
            return;
        }

        final String client = String.format(Locale.ROOT, "0x%08X", hdr.clientId);
        final String req = Integer.toString(hdr.requestId);
        final String op = Protocol.opCodeName(hdr.opCode);

        // A REPLY, CALLBACK or MONITOR_END arriving at the server is not ours to answer.
        if (hdr.messageType != Protocol.MSG_REQUEST) {
            log("DROP", "request", client, req, op, "not-a-request",
                    "from=" + peer + " messageType=" + hdr.messageType);
            return;
        }

        // Step 4, the half Header.read explicitly does not do: it decodes over a buffer
        // and so does not know what length the socket reported. The check is one line
        // and catches truncation and any disagreement between the two implementations
        // about how long a body is.
        if (received != Protocol.HEADER_SIZE + hdr.payloadLen) {
            log("VALIDATE", "request", client, req, op, "length-disagreement",
                    "received=" + received + " expected="
                            + (Protocol.HEADER_SIZE + hdr.payloadLen));
            sendError(hdr, StatusCode.ERR_MALFORMED_REQUEST, from, fromPort);
            return;
        }

        // D19: refuse a client in the other invocation mode outright. Serving it would
        // produce sec. 13 results that look valid and mean nothing. Enforced here even
        // though this server implements neither mode's duplicate handling, so that the
        // client's own mismatch path can be exercised against it.
        if (hdr.semantics != this.semantics) {
            log("VALIDATE", "request", client, req, op, "semantics-mismatch",
                    "client=" + semanticsName(hdr.semantics)
                            + " server=" + semanticsName(this.semantics));
            sendError(hdr, StatusCode.ERR_SEMANTICS_MISMATCH, from, fromPort);
            return;
        }

        // The brief requires both ends to print every request they handle.
        log("RECV", "request", client, req, op, "accepted",
                "from=" + peer + " len=" + hdr.payloadLen);

        // README sec. 6.1 property 5: a watermark at or above the request's own
        // requestId would erase the history entry that request is about to need, so it
        // is refused rather than honoured. Validated here even though this server has no
        // history to prune, because the client's ackUpTo arithmetic must be exercised
        // against a server that checks it.
        if (hdr.ackUpTo >= hdr.requestId) {
            log("VALIDATE", "request", client, req, op, "bad-ackUpTo",
                    "ackUpTo=" + hdr.ackUpTo + " requestId=" + hdr.requestId);
            sendError(hdr, StatusCode.ERR_INVALID_ARGUMENT, from, fromPort);
            return;
        }

        // Where the real server looks up the reply history, fingerprints the request and
        // may return without dispatching (README sec. 6.1 property 2, D24). This server
        // has no history, so it always falls through and always re-executes.
        dispatch(hdr, in, from, fromPort);
    }

    // ---- Dispatch (README sec. 4) ----

    /**
     * Decodes the body for {@code hdr.opCode}, applies the operation to the mock flight
     * table, sends the reply and then fires any callbacks a successful {@code RESERVE}
     * earned.
     *
     * <p>The body is decoded straight from {@code in}, which {@link Header#read(Codec)}
     * left positioned at offset 40 with its limit at the received length. The nested
     * types in {@link Requests} and {@link Replies} own the layout; nothing here packs a
     * field by hand.
     *
     * <p>The reply is sent <em>before</em> the callbacks, matching README sec. 6.1. The
     * ordering matters in the real server, where a dropped reply models loss on the
     * return path and the callbacks must still fire - that is what makes at-least-once
     * corruption of {@code RESERVE} visible to a third machine.
     */
    private void dispatch(final Header hdr, final Codec in,
                          final InetAddress from, final int fromPort) throws IOException {

        final byte[] bodyBuf = new byte[Protocol.MAX_BODY];
        final Codec out = Codec.writer(bodyBuf);

        final String client = String.format(Locale.ROOT, "0x%08X", hdr.clientId);
        final String req = Integer.toString(hdr.requestId);
        final String op = Protocol.opCodeName(hdr.opCode);

        int status;
        String state;

        // Set only when a RESERVE actually changed the seat pool. It is the trigger for
        // README sec. 9.2 delivery and the reason this is not a pure function.
        MockFlight reserved = null;

        try {
            switch (hdr.opCode) {

                // ---- Op 1: QUERY_BY_ROUTE, idempotent ----
                case Protocol.OP_QUERY_BY_ROUTE: {
                    final Requests.Route request = Requests.Route.read(in);
                    final String wantSrc = normalise(request.source);
                    final String wantDst = normalise(request.destination);

                    final List<Integer> hits = new ArrayList<>();
                    for (final MockFlight f : flights) {
                        if (normalise(f.source).equals(wantSrc)
                                && normalise(f.destination).equals(wantDst)) {
                            hits.add(f.id);
                        }
                    }

                    if (hits.isEmpty()) {
                        // README sec. 4 Op 1: the brief requires an error here, not an
                        // empty list.
                        status = StatusCode.ERR_NO_ROUTE_MATCH;
                        state = "route=" + request + " matches=0";
                        break;
                    }

                    // Ascending order is part of the interface (README sec. 4 Op 1); the
                    // table's iteration order is not, so it is sorted explicitly rather
                    // than relied upon. Sorting happens before the cap so that
                    // truncation drops the highest ids, deterministically.
                    Collections.sort(hits);
                    final boolean truncated = hits.size() > Protocol.MAX_ROUTE_RESULTS;
                    final int n = truncated ? Protocol.MAX_ROUTE_RESULTS : hits.size();
                    final int[] ids = new int[n];
                    for (int i = 0; i < n; i++) {
                        ids[i] = hits.get(i);
                    }

                    new Replies.Route(truncated, ids).write(out);
                    status = StatusCode.OK;
                    state = "route=" + request + " matches=" + n
                            + (truncated ? " truncated" : "");
                    break;
                }

                // ---- Op 2: QUERY_BY_ID, idempotent ----
                case Protocol.OP_QUERY_BY_ID: {
                    final Requests.Flight request = Requests.Flight.read(in);
                    final MockFlight f = find(request.flightId);
                    if (f == null) {
                        status = StatusCode.ERR_UNKNOWN_FLIGHT;
                        state = "flight=" + request.flightId + " unknown";
                        break;
                    }
                    // The reply carries no flight id, source or destination: the client
                    // asked by id and already knows them (README sec. 4 Op 2, D4).
                    new Replies.Flight(f.departure, f.airfare, f.seatsAvailable).write(out);
                    status = StatusCode.OK;
                    state = "flight=" + f.id + " seats=" + f.seatsAvailable
                            + " fare=" + f.airfare;
                    break;
                }

                // ---- Op 3: RESERVE, non-idempotent, the only operation that fires
                //      callbacks (README sec. 4 Op 3, sec. 9.2) ----
                case Protocol.OP_RESERVE: {
                    final Requests.Reserve request = Requests.Reserve.read(in);
                    final MockFlight f = find(request.flightId);
                    if (f == null) {
                        status = StatusCode.ERR_UNKNOWN_FLIGHT;
                        state = "flight=" + request.flightId + " unknown";
                        break;
                    }
                    if (request.numSeats > f.seatsAvailable) {
                        status = StatusCode.ERR_INSUFFICIENT_SEATS;
                        state = "flight=" + f.id + " seats=" + f.seatsAvailable
                                + " wanted=" + request.numSeats;
                        break;
                    }

                    // Check-and-decrement, atomic by construction: the request path is
                    // single-threaded (D11), so no lock is needed and none would help.
                    final int before = f.seatsAvailable;
                    f.seatsAvailable -= request.numSeats;
                    reserved = f;

                    // The post-state, not a bare acknowledgement: under at-least-once a
                    // re-executed RESERVE then returns a *different* seat count, so the
                    // corruption is visible in the reply itself (README sec. 4 Op 3).
                    new Replies.Reserve(f.seatsAvailable).write(out);
                    status = StatusCode.OK;
                    state = "flight=" + f.id + " seats=" + before + "->" + f.seatsAvailable;
                    break;
                }

                // ---- Op 4: MONITOR, registration only ----
                case Protocol.OP_MONITOR: {
                    final Requests.Monitor request = Requests.Monitor.read(in);
                    final MockFlight f = find(request.flightId);
                    if (f == null) {
                        status = StatusCode.ERR_UNKNOWN_FLIGHT;
                        state = "flight=" + request.flightId + " unknown";
                        break;
                    }

                    // ERR_MONITOR_TABLE_FULL cannot occur here: the registry is not
                    // fixed-capacity in this build. See the class note.
                    final boolean replaced = register(hdr, request, from, fromPort);

                    // The snapshot gives the client a baseline to compare the first
                    // callback against. expiresInMs is advisory - the client's own
                    // monotonic deadline is authoritative (D20).
                    new Replies.Monitor(f.seatsAvailable, request.intervalMs).write(out);
                    status = StatusCode.OK;
                    state = "flight=" + f.id + " seats=" + f.seatsAvailable
                            + " interval=" + request.intervalMs + "ms"
                            + (replaced ? " replaced" : " added")
                            + " monitors=" + monitors.size();
                    break;
                }

                // ---- Op 5: ADJUST_FARE, the required NON-idempotent extra ----
                case Protocol.OP_ADJUST_FARE: {
                    final Requests.AdjustFare request = Requests.AdjustFare.read(in);
                    final MockFlight f = find(request.flightId);
                    if (f == null) {
                        status = StatusCode.ERR_UNKNOWN_FLIGHT;
                        state = "flight=" + request.flightId + " unknown";
                        break;
                    }

                    // Relative arithmetic: deck 2's canonical non-idempotent operation.
                    // A replay applies the delta twice, which is precisely the divergence
                    // the sec. 13 experiments measure - and which this server cannot
                    // prevent, having no history.
                    final float updated = f.airfare + request.delta;

                    // isLegalFare also rejects a non-finite result, which is reachable
                    // here even though both operands are finite: two large finite floats
                    // can sum to infinity.
                    if (!Requests.isLegalFare(updated)) {
                        // README sec. 4 Op 5: the stored value is left untouched, so a
                        // rejected adjustment is a no-op and never a partial application.
                        status = StatusCode.ERR_INVALID_FARE;
                        state = "flight=" + f.id + " fare=" + f.airfare
                                + " unchanged (would be " + updated + ")";
                        break;
                    }

                    final float before = f.airfare;
                    f.airfare = updated;
                    new Replies.Fare(f.airfare).write(out);
                    status = StatusCode.OK;
                    state = "flight=" + f.id + " fare=" + before + "->" + f.airfare
                            + " delta=" + request.delta;
                    break;
                }

                // ---- Op 6: SET_FARE, the required IDEMPOTENT extra ----
                case Protocol.OP_SET_FARE: {
                    final Requests.SetFare request = Requests.SetFare.read(in);
                    final MockFlight f = find(request.flightId);
                    if (f == null) {
                        status = StatusCode.ERR_UNKNOWN_FLIGHT;
                        state = "flight=" + request.flightId + " unknown";
                        break;
                    }

                    // Redundant today and kept deliberately: Requests.SetFare.read
                    // already enforces the range on decode, so this branch is
                    // unreachable from the wire. The rule is stated in two places
                    // (README sec. 4 Op 6 makes it the *server's* refusal), and if the
                    // decoder's check ever relaxes this is what keeps the answer
                    // ERR_INVALID_FARE instead of a silently stored bad value.
                    if (!Requests.isLegalFare(request.newFare)) {
                        status = StatusCode.ERR_INVALID_FARE;
                        state = "flight=" + f.id + " fare=" + f.airfare + " unchanged";
                        break;
                    }

                    // Absolute assignment, so a second execution writes the same value
                    // and the final state is identical. That is the claim experiment E6
                    // exists to demonstrate, and the reason the idempotent extra is a
                    // write rather than a read-only query (D9).
                    final float before = f.airfare;
                    f.airfare = request.newFare;
                    new Replies.Fare(f.airfare).write(out);
                    status = StatusCode.OK;
                    state = "flight=" + f.id + " fare=" + before + "->" + f.airfare;
                    break;
                }

                default:
                    // Unreachable: Header.read applies Protocol.isValidOpCode to a
                    // REQUEST. Kept so that adding an opcode to Protocol without adding
                    // a case here answers the client instead of falling through to OK.
                    status = StatusCode.ERR_UNKNOWN_OPCODE;
                    state = "opCode=" + hdr.opCode;
                    break;
            }
        } catch (final CodecException e) {
            // A decode failure over untrusted bytes: routine, and never fatal. README
            // sec. 3.6 step 7 requires a status-code reply with the server still
            // running.
            status = decodeFailureStatus(hdr.opCode, hdr.payloadLen, packetBody(in));
            state = "decode-failed: " + e.getMessage();
        } catch (final IllegalArgumentException e) {
            // The Requests constructors raise this unchecked for a value their read()
            // does not itself screen - an empty place name is the live example, since
            // Codec.getString caps the length but accepts zero. Caught here rather than
            // allowed to unwind out of the loop, for the same sec. 3.6 step 7 reason.
            status = StatusCode.ERR_INVALID_ARGUMENT;
            state = "rejected-argument: " + e.getMessage();
        }

        // An error reply is a bare header with no body (README sec. 5, D4), so the
        // encoded body length counts only on success.
        final int bodyLen = StatusCode.isOk(status) ? out.position() : 0;

        log("EXECUTE", "request", client, req, op,
                StatusCode.isOk(status) ? "executed" : StatusCode.name(status), state);

        sendReply(hdr, status, bodyBuf, bodyLen, from, fromPort);

        if (StatusCode.isOk(status) && reserved != null) {
            fireCallbacks(reserved, hdr.requestId);
        }
    }

    /**
     * Maps a body decode failure onto the status code README sec. 4 and sec. 5 call for.
     *
     * <p>{@link CodecException} conflates two different faults - a body that is
     * structurally short and a field whose value is out of range - because
     * {@link Codec} raises the same type for both. README distinguishes them:
     * {@link StatusCode#ERR_MALFORMED_REQUEST} is "body did not parse, or length
     * disagreed", while {@link StatusCode#ERR_INVALID_ARGUMENT} is an argument that
     * parsed and is nonsense.
     *
     * <p>The discriminator is the body length. Every operation but Op 1 has a fixed body
     * size, so a body of exactly the right size cannot have failed on truncation and the
     * failure must be a value check. Op 1 is variable-length, so this cannot separate a
     * truncated route query from an over-long place name; both come back as
     * {@code ERR_MALFORMED_REQUEST}.
     */
    private static int decodeFailureStatus(final int opCode, final int payloadLen,
                                           final byte[] body) {
        final int fixed = fixedBodySize(opCode);
        if (fixed >= 0 && payloadLen != fixed) {
            return StatusCode.ERR_MALFORMED_REQUEST;
        }

        switch (opCode) {
            case Protocol.OP_RESERVE:      // numSeats < 1
            case Protocol.OP_MONITOR:      // intervalMs outside 1..MAX_INTERVAL_MS
            case Protocol.OP_ADJUST_FARE:  // delta NaN or infinite
                return StatusCode.ERR_INVALID_ARGUMENT;

            case Protocol.OP_SET_FARE:
                return setFareFailureStatus(body);

            default:
                // Op 1 (truncation or an over-long name) and Op 2 (truncation only,
                // since Requests.Flight.read validates nothing).
                return StatusCode.ERR_MALFORMED_REQUEST;
        }
    }

    /**
     * Separates the two reasons {@code Requests.SetFare.read} can refuse a well-sized
     * body: a non-finite fare, which README sec. 4 Op 6 answers with
     * {@link StatusCode#ERR_INVALID_ARGUMENT}, and a finite fare outside
     * {@code 0 < fare <= MAX_FARE}, which it answers with
     * {@link StatusCode#ERR_INVALID_FARE}.
     *
     * <p>{@code SetFare.read} raises one exception type for both, so the body is read a
     * second time here with the fare taken as raw bits - {@link Codec#getFloat32()}
     * would itself reject the non-finite case and destroy the distinction. This runs
     * only on the error path, so the extra copy costs nothing on a healthy exchange.
     */
    private static int setFareFailureStatus(final byte[] body) {
        final Codec probe = Codec.reader(body, body.length);
        try {
            probe.getInt32();                      // flightId, not needed here
            final int bits = probe.getInt32();     // the fare, as its wire bit pattern
            final float fare = Float.intBitsToFloat(bits);
            return Float.isFinite(fare)
                    ? StatusCode.ERR_INVALID_FARE
                    : StatusCode.ERR_INVALID_ARGUMENT;
        } catch (final CodecException e) {
            return StatusCode.ERR_MALFORMED_REQUEST;
        }
    }

    /** Body size in bytes for the fixed-layout operations; -1 for variable Op 1. */
    private static int fixedBodySize(final int opCode) {
        switch (opCode) {
            case Protocol.OP_QUERY_BY_ID:
                return 4;
            case Protocol.OP_RESERVE:
            case Protocol.OP_MONITOR:
            case Protocol.OP_ADJUST_FARE:
            case Protocol.OP_SET_FARE:
                return 8;
            default:
                return -1;
        }
    }

    /**
     * The request body as its own array, for the error-path re-read in
     * {@link #setFareFailureStatus(byte[])}.
     *
     * <p>{@link Codec} is a forward-only cursor with no seek, so a second pass over the
     * body cannot start at offset 40 of the datagram; it needs a buffer that begins
     * there. Copying is acceptable because this is only ever reached after a decode has
     * already failed.
     */
    private static byte[] packetBody(final Codec datagram) {
        final int end = datagram.position() + datagram.remaining();
        final int start = Math.min(Protocol.HEADER_SIZE, end);
        return Arrays.copyOfRange(datagram.array(), start, end);
    }

    // ---- Callback subsystem (README sec. 9) ----

    /**
     * Adds or replaces a monitor registration (README sec. 9.1).
     *
     * <p>The endpoint is the <em>observed</em> source address of the datagram, never a
     * value from the body. A payload-supplied address would let a client aim a callback
     * stream at a third party.
     *
     * <p>Re-registering the same {@code (clientId, flightId)} replaces the entry, so a
     * client cannot accumulate registrations by retransmitting. The replacement is a
     * fresh entry with {@code callbackSeq} back at 0, matching the append template of
     * README sec. 9.1: the new registration is a new stream of updates, and continuing
     * the old counter would tell the client it had missed everything sent before it
     * re-registered.
     *
     * @return true if an existing registration was replaced
     */
    private boolean register(final Header hdr, final Requests.Monitor request,
                             final InetAddress from, final int fromPort) {
        boolean replaced = false;
        final Iterator<Registration> it = monitors.iterator();
        while (it.hasNext()) {
            final Registration r = it.next();
            if (r.clientId == hdr.clientId && r.flightId == request.flightId) {
                it.remove();
                replaced = true;
            }
        }
        monitors.add(new Registration(hdr.clientId, request.flightId, from, fromPort,
                nowMs() + request.intervalMs, hdr.requestId, hdr.semantics));
        return replaced;
    }

    /**
     * Sends one {@code CALLBACK} to every live registrant of this flight (README
     * sec. 9.2).
     *
     * <p>Reached only from a successful {@code RESERVE}. {@code ADJUST_FARE} and
     * {@code SET_FARE} fire nothing: the brief scopes the monitor service to "updates
     * made to the seat availability information of a flight", and a fare change is not
     * one. That is the disclosed cost of the fare pair (D9), and it is a specification
     * decision rather than an omission here.
     *
     * <p>Expiry is swept first, so a registration that expired since the last loop
     * iteration never receives a late update. That lazy check at delivery time is what
     * makes expiry exact (README sec. 9.3).
     *
     * <p>Delivery is fire and forget - no acknowledgement, no retry, no history. That is
     * <em>maybe</em> semantics in deck 3's taxonomy, and D10 accepts it deliberately:
     * {@code callbackSeq} and {@code eventRequestId} make the resulting loss and
     * duplication measurable instead of merely admitted.
     */
    private void fireCallbacks(final MockFlight flight, final int eventRequestId)
            throws IOException {

        expireMonitors(nowMs());

        for (final Registration r : monitors) {
            if (r.flightId != flight.id) {
                continue;
            }

            // Per registration, counting from 1. A client seeing 1, 2, 4 knows one
            // update was lost rather than guessing.
            r.callbackSeq++;

            // eventRequestId is the requestId of the operation that caused the update -
            // the RESERVE, not the MONITOR. Two callbacks carrying the same value came
            // from one duplicated operation; two different values came from two distinct
            // operations. That is the direct observable signature of at-least-once
            // re-execution (README sec. 4 CALLBACK).
            final byte[] body = new byte[Protocol.MAX_BODY];
            final Codec out = Codec.writer(body);
            final int len;
            try {
                new Replies.Callback(flight.id, flight.seatsAvailable,
                        eventRequestId, r.callbackSeq).write(out);
                len = out.position();
            } catch (final CodecException e) {
                // Encoding a 16-byte body into a 472-byte buffer cannot fail; if it does,
                // that is our bug and not a wire condition.
                throw new IllegalStateException("callback body encode failed", e);
            }

            // requestId on the wire is the MONITOR registration's, not a fresh number:
            // a client holding several registrations has nothing else to tell it which
            // one produced the update.
            send(Header.callback(r.clientId, r.monitorRequestId, r.semantics, len),
                    body, len, r.address, r.port);

            log("CALLBACK", "callback", String.format(Locale.ROOT, "0x%08X", r.clientId),
                    Integer.toString(r.monitorRequestId), "OP_NONE", "sent",
                    "flight=" + flight.id + " seats=" + flight.seatsAvailable
                            + " seq=" + r.callbackSeq + " event=" + eventRequestId);
        }
    }

    /**
     * Removes every expired registration and sends one {@code MONITOR_END} for each
     * (README sec. 9.3).
     *
     * <p>Called from the loop each iteration, so memory is reclaimed with no reservation
     * traffic at all, and again from {@link #fireCallbacks(MockFlight, int)} so that
     * delivery-time expiry is exact. The entry is removed <em>before</em> the message is sent, which is what
     * makes "sent once" true regardless of how many times this is called.
     *
     * <p>{@code MONITOR_END} is advisory (D20). It is unacknowledged and may itself be
     * lost, so the client's own deadline stays authoritative; it earns its place by
     * carrying the final callback count, which lets the client report how many updates
     * it missed rather than only the gaps between those that arrived.
     */
    private void expireMonitors(final long now) throws IOException {
        final Iterator<Registration> it = monitors.iterator();
        while (it.hasNext()) {
            final Registration r = it.next();
            if (now < r.expiresAtMs) {
                continue;
            }
            it.remove();

            final byte[] body = new byte[Protocol.MAX_BODY];
            final Codec out = Codec.writer(body);
            final int len;
            try {
                new Replies.MonitorEnd(r.flightId, r.callbackSeq).write(out);
                len = out.position();
            } catch (final CodecException e) {
                throw new IllegalStateException("monitor-end body encode failed", e);
            }

            send(Header.monitorEnd(r.clientId, r.monitorRequestId, r.semantics, len),
                    body, len, r.address, r.port);

            log("MONITOR_END", "callback",
                    String.format(Locale.ROOT, "0x%08X", r.clientId),
                    Integer.toString(r.monitorRequestId), "OP_NONE", "expired",
                    "flight=" + r.flightId + " total=" + r.callbackSeq
                            + " monitors=" + monitors.size());
        }
    }

    // ---- Sending ----

    /** Sends the reply for {@code request} and logs it, as the brief requires. */
    private void sendReply(final Header request, final int status, final byte[] body,
                           final int bodyLen, final InetAddress to, final int port)
            throws IOException {

        send(Header.reply(request, status, bodyLen), body, bodyLen, to, port);

        log("SEND", "reply", String.format(Locale.ROOT, "0x%08X", request.clientId),
                Integer.toString(request.requestId),
                Protocol.opCodeName(request.opCode), StatusCode.name(status),
                "len=" + bodyLen);
    }

    /** Sends a bare error reply: header only, no body (README sec. 5, D4). */
    private void sendError(final Header request, final int status,
                           final InetAddress to, final int port) throws IOException {
        sendReply(request, status, null, 0, to, port);
    }

    /**
     * Marshals one message and puts it on the wire.
     *
     * <p>The body is encoded first and the header written in front of it, because
     * {@code payloadLen} is a header field and cannot be filled in until the body's
     * length is known. The two are then joined by a copy rather than by encoding the
     * body twice.
     */
    private void send(final Header hdr, final byte[] body, final int bodyLen,
                      final InetAddress to, final int port) throws IOException {

        final byte[] datagram = new byte[Protocol.HEADER_SIZE + bodyLen];
        final Codec out = Codec.writer(datagram);
        try {
            hdr.write(out);
        } catch (final CodecException e) {
            // The buffer was sized as HEADER_SIZE + bodyLen, so the header always fits.
            // Reaching here means Header.write and Protocol.HEADER_SIZE disagree, which
            // is our bug rather than a wire fault.
            throw new IllegalStateException("header encode failed", e);
        }
        if (bodyLen > 0) {
            System.arraycopy(body, 0, datagram, Protocol.HEADER_SIZE, bodyLen);
        }
        socket.send(new DatagramPacket(datagram, datagram.length, to, port));
    }

    // ---- Flight table ----

    /**
     * The hard-coded seed table of README sec. 6.3 (D18).
     *
     * <p><b>EVERY ROW BELOW IS INVENTED MOCK DATA</b> and exists only so the client has
     * something to query. The real server owns the repository; when it lands, this
     * method and {@link MockFlight} go away together.
     *
     * <p>The composition is not arbitrary - README sec. 6.3 requires all four of these
     * properties, and each row is annotated with the one it supplies:
     *
     * <ul>
     *   <li>two flights on the same source/destination pair, so a route query returns
     *       more than one identifier;</li>
     *   <li>at least one flight on a distinct route;</li>
     *   <li>one flight with a small seat count, so a demo can exhaust it in two or three
     *       reservations rather than forty;</li>
     *   <li>a fare spread with one fare high enough and one low enough that a small
     *       {@code ADJUST_FARE} delta crosses the {@code 0 < fare <= MAX_FARE} boundary
     *       and exercises {@code ERR_INVALID_FARE} without a contrived argument.</li>
     * </ul>
     */
    private static List<MockFlight> seedFlights() {
        final List<MockFlight> seed = new ArrayList<>();

        // MOCK DATA. Flight 1934 Singapore -> London reproduces the conformance vector
        // of README sec. 11.2 field for field - id, both place names, 2026-10-14 09:30,
        // 899.5f and 120 seats - so that a QUERY_BY_ID against this server can be
        // checked against a hex dump the C++ side must also produce. Do not renumber it
        // or change its values; the demo script and the vector both name it.
        seed.add(new MockFlight(1934, "Singapore", "London",
                new DepartureTime(2026, 10, 14, 9, 30), 899.5f, 120));

        // MOCK DATA. Second flight on the SAME route as 1934, which is what makes
        // QUERY_BY_ROUTE("Singapore", "London") return two identifiers rather than one.
        seed.add(new MockFlight(1935, "Singapore", "London",
                new DepartureTime(2026, 10, 14, 21, 45), 1042.0f, 180));

        // MOCK DATA. A distinct route, and deliberately the reverse direction of the
        // pair above: matching is exact after folding, so a client that queries
        // London -> Singapore must get this one and not 1934/1935. A marshaller that
        // swapped the two strings in Requests.Route would pass every same-route test and
        // fail this one.
        seed.add(new MockFlight(2208, "London", "Singapore",
                new DepartureTime(2026, 10, 15, 7, 5), 780.25f, 150));

        // MOCK DATA. Small seat count, for fast exhaustion: four seats means
        // ERR_INSUFFICIENT_SEATS is two reservations away in a live demo. Also the LOW
        // end of the fare spread - ADJUST_FARE with delta -50.0 drives 45.0 to -5.0,
        // outside 0 < fare, so ERR_INVALID_FARE is reachable with an obvious argument.
        seed.add(new MockFlight(3050, "Singapore", "Tokyo",
                new DepartureTime(2026, 10, 16, 8, 0), 45.0f, 4));

        // MOCK DATA. The HIGH end of the fare spread. MAX_FARE is 100000.0f, so
        // ADJUST_FARE with delta +100.0 pushes 99950.0 to 100050.0 and is refused from
        // the other side of the same boundary. Both fares are exactly representable in
        // binary32, so the boundary test compares exactly rather than within a
        // tolerance (README sec. 13).
        seed.add(new MockFlight(4127, "Singapore", "New York",
                new DepartureTime(2026, 10, 17, 23, 15), 99950.0f, 60));

        return seed;
    }

    /** @return the flight with this id, or null. Linear scan: the table has five rows. */
    private MockFlight find(final int flightId) {
        for (final MockFlight f : flights) {
            if (f.id == flightId) {
                return f;
            }
        }
        return null;
    }

    /**
     * Normalises a place name for route matching: outer ASCII whitespace trimmed, then
     * ASCII case folded, then compared exactly (README sec. 4 Op 1).
     *
     * <p>Both halves are written out rather than delegated, because the convenient
     * library calls are both subtly wrong for a cross-language protocol.
     * {@link String#strip()} is Unicode-aware and would also trim characters such as
     * U+00A0 that the C++ side would keep. {@link String#toLowerCase()} without a locale
     * uses the platform default, and under a Turkish locale it maps {@code I} to a
     * dotless {@code i} - so the same query would match on one laptop and not on
     * another, for a reason nobody would think to look for. Folding only A-Z is what the
     * specification says and what the other implementation can reproduce exactly.
     */
    private static String normalise(final String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isAsciiSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isAsciiSpace(s.charAt(end - 1))) {
            end--;
        }

        final StringBuilder sb = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            final char c = s.charAt(i);
            sb.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return sb.toString();
    }

    private static boolean isAsciiSpace(final char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    // ---- Small helpers ----

    /**
     * Monotonic milliseconds. Every expiry and deadline decision in this file uses this
     * and never wall-clock time, which can jump backwards under an NTP correction and
     * turn a live registration into an expired one (README sec. 6.1).
     *
     * <p>The absolute value is meaningless; only differences are.
     */
    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private static String semanticsName(final int semantics) {
        return semantics == Protocol.SEM_AT_MOST_ONCE ? "at-most-once" : "at-least-once";
    }

    /** One line in the README sec. 10.2 column order. See {@link #LOG_FORMAT}. */
    private static void log(final String event, final String dir, final String client,
                            final String req, final String opcode, final String decision,
                            final String state) {
        System.out.printf(Locale.ROOT, LOG_FORMAT, LocalTime.now().format(STAMP),
                event, dir, client, req, opcode, "-", decision, state);
    }

    // ---- Server-side types ----

    /**
     * The server's mutable flight entry: the seven fields of README sec. 2.1.
     *
     * <p>Deliberately not {@link sc6103.common.FlightRecord}. That type is the
     * conformance fixture of README sec. 11.2 - immutable, and without
     * {@code totalSeats}, which is seed state that never crosses the wire. The server's
     * table needs the opposite of both: {@code seatsAvailable} and {@code airfare} are
     * exactly the values operations 3, 5 and 6 mutate.
     *
     * <p>MOCK: replaced by the real repository's entry type at M6.
     */
    private static final class MockFlight {

        private final int id;
        private final String source;
        private final String destination;
        private final DepartureTime departure;

        /** Mutated by ADJUST_FARE and SET_FARE. */
        private float airfare;

        /** Mutated by RESERVE. */
        private int seatsAvailable;

        /** Fixed at seed time and never transmitted; kept for the log's before/after. */
        private final int totalSeats;

        private MockFlight(final int id, final String source, final String destination,
                           final DepartureTime departure, final float airfare,
                           final int totalSeats) {
            this.id = id;
            this.source = source;
            this.destination = destination;
            this.departure = departure;
            this.airfare = airfare;
            this.totalSeats = totalSeats;
            this.seatsAvailable = totalSeats;
        }

        @Override
        public String toString() {
            // The fare's bit pattern is printed beside the decimal for the same reason
            // FlightRecord.toString does it: two floats that print identically can
            // differ in their last bit, and the decimal rendering is exactly what hides
            // a cross-language mismatch.
            return String.format(Locale.ROOT,
                    "flight=%d %s->%s departs=%s fare=%.2f (0x%08X) seats=%d/%d",
                    id, source, destination, departure, airfare,
                    Float.floatToIntBits(airfare), seatsAvailable, totalSeats);
        }
    }

    /**
     * One entry of the monitor registry of README sec. 2.5.
     *
     * <p>{@code address} and {@code port} are the observed source of the MONITOR
     * datagram, never a payload value (README sec. 9.1). {@code semantics} is stored so
     * that callbacks echo the mode the registration was made in, as README sec. 3.4
     * requires of every non-request message.
     */
    private static final class Registration {

        private final long clientId;
        private final int flightId;
        private final InetAddress address;
        private final int port;

        /** Monotonic deadline - see {@link MockServer#nowMs()}. */
        private final long expiresAtMs;

        /** The requestId of the MONITOR that created this entry; echoed in every
         *  callback so a client with several registrations knows which one spoke. */
        private final int monitorRequestId;

        private final int semantics;

        /** Callbacks sent so far. Counts from 1 on the wire, so it starts at 0 here. */
        private int callbackSeq;

        private Registration(final long clientId, final int flightId,
                             final InetAddress address, final int port,
                             final long expiresAtMs, final int monitorRequestId,
                             final int semantics) {
            this.clientId = clientId;
            this.flightId = flightId;
            this.address = address;
            this.port = port;
            this.expiresAtMs = expiresAtMs;
            this.monitorRequestId = monitorRequestId;
            this.semantics = semantics;
            this.callbackSeq = 0;
        }
    }
}
