package sc6103.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sc6103.client.MonitorSession;
import sc6103.client.RequestReply;
import sc6103.common.Header;
import sc6103.common.Protocol;
import sc6103.common.Replies;
import sc6103.common.Requests;
import sc6103.common.StatusCode;

/**
 * Runs the E1-E10 experiment plan of README sec. 13 and prints a Markdown result table.
 *
 * <p>Every exchange goes through the Java client's own {@link RequestReply}, and E10's
 * reconciliation through its {@link MonitorSession}, so the retries, reply matching,
 * {@code ackUpTo} watermark and monitor summary measured here are the ones the console
 * client uses. CLAUDE.md makes the Java client the test driver for the server; a harness
 * with its own retry loop would only test the harness.
 *
 * <p>Two things differ from a console session. The retry policy is 35 ms and 9 retries,
 * which keeps 20 trials per scenario quick on loopback and makes an exchange whose every
 * reply is dropped at p = 0.5 a one-in-1024 event. And loss is injected on the server only
 * (README sec. 10). E9's run without {@code ackUpTo} is the one place a hand-built datagram
 * is needed, because the real client always acknowledges.
 */
public final class ExperimentHarness {

    private static final int TIMEOUT_MS = 35;
    private static final int RETRIES = 9;

    /** The server's per-client reply-history cap (README sec. 8.3). */
    private static final int MAX_HISTORY_PER_CLIENT = 64;

    /** The client id E9 uses for its hand-built, never-acknowledging requests. */
    private static final long SILENT_CLIENT_ID = 0x50000001L;

    private static final Pattern CACHE_LINE =
            Pattern.compile("client=(0x[0-9A-F]{8}) .*client-entries=(\\d+)");
    private static final Pattern MONITOR_SUMMARY =
            Pattern.compile("(\\d+) of (\\d+) updates received");

    private static final class Results {
        int e1Correct;
        int e2Corrupt;
        int e3Correct;
        int e4Corrupt;
        int e5Correct;
        int e6Correct;
        int e6Reexecuted;
        int e7Correct;
        int e8Duplicate;
        int e9WithAck = -1;
        int e9WithoutAck = -1;
        int e9CapEvictions;
        int e10Reconciled;
        int e10WithLoss;
    }

    private ExperimentHarness() {
    }

    private static int freePort() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ServerIntegrationTest.RealClient client(int port, String semantics)
            throws IOException {
        return new ServerIntegrationTest.RealClient(port, semantics, TIMEOUT_MS, RETRIES);
    }

    private static boolean ok(RequestReply.Outcome outcome) {
        return !outcome.timedOut && outcome.header.status == StatusCode.OK;
    }

    private static Replies.Flight query(ServerIntegrationTest.RealClient c, int flightId)
            throws Exception {
        final RequestReply.Outcome o =
                c.engine.call(Protocol.OP_QUERY_BY_ID, new Requests.Flight(flightId)::write);
        return ok(o) ? Replies.Flight.read(o.body) : null;
    }

    /** Server output lines containing every fragment. */
    private static int countLines(ServerIntegrationTest.ServerProcess server,
                                  String... fragments) {
        int count = 0;
        for (String line : server.lines()) {
            boolean matches = true;
            for (String fragment : fragments) matches &= line.contains(fragment);
            if (matches) count++;
        }
        return count;
    }

    /** Largest reply-history size the server reported for one client on its CACHE lines. */
    private static int maxClientEntries(ServerIntegrationTest.ServerProcess server,
                                        String clientHex) {
        int max = 0;
        for (String line : server.lines()) {
            final Matcher m = CACHE_LINE.matcher(line);
            if (m.find() && m.group(1).equals(clientHex)) {
                max = Math.max(max, Integer.parseInt(m.group(2)));
            }
        }
        return max;
    }

    private static void noLoss(Path binary, int trials, Results r) throws Exception {
        for (int trial = 0; trial < trials; trial++) {
            for (String semantics : new String[] {"at-least-once", "at-most-once"}) {
                final int port = freePort();
                try (ServerIntegrationTest.ServerProcess server =
                             new ServerIntegrationTest.ServerProcess(binary, port, semantics,
                                     "--seed", Integer.toString(trial));
                     ServerIntegrationTest.RealClient c = client(port, semantics)) {
                    final RequestReply.Outcome reserve = c.engine.call(Protocol.OP_RESERVE,
                            new Requests.Reserve(1934, 2)::write);
                    if (ok(reserve) && Replies.Reserve.read(reserve.body).seatsAvailable == 118) {
                        r.e1Correct++;
                    }
                    if (!server.isAlive()) throw new AssertionError("E1 server exited");
                }
            }
        }
    }

    private static void replyLoss(Path binary, int trials, Results r) throws Exception {
        for (int trial = 0; trial < trials; trial++) {
            int port = freePort();
            try (ServerIntegrationTest.ServerProcess server =
                         new ServerIntegrationTest.ServerProcess(binary, port, "at-least-once",
                                 "--drop-reply", "0.5", "--seed", Integer.toString(trial));
                 ServerIntegrationTest.RealClient c = client(port, "at-least-once");
                 ServerIntegrationTest.RealClient monitor = client(port, "at-least-once")) {
                c.engine.call(Protocol.OP_RESERVE, new Requests.Reserve(1934, 2)::write);
                final Replies.Flight reserved = query(c, 1934);
                if (reserved != null && reserved.seatsAvailable < 118) r.e2Corrupt++;

                c.engine.call(Protocol.OP_ADJUST_FARE, new Requests.AdjustFare(2201, 1.0F)::write);
                final Replies.Flight adjusted = query(c, 2201);
                if (adjusted != null && adjusted.airfare > 1205.0F) r.e4Corrupt++;

                c.engine.call(Protocol.OP_SET_FARE, new Requests.SetFare(412, 750.0F)::write);
                final Replies.Flight set = query(c, 412);
                if (set != null && set.airfare == 750.0F) r.e6Correct++;

                final RequestReply.Outcome registration = monitor.engine.call(Protocol.OP_MONITOR,
                        new Requests.Monitor(412, 500)::write);
                if (ok(registration)) {
                    final RequestReply.Outcome mutation = c.engine.call(Protocol.OP_RESERVE,
                            new Requests.Reserve(412, 1)::write);
                    final int[] callbacks = {0};
                    final Set<Integer> events = new HashSet<>();
                    monitor.engine.setAsyncHandler(new RequestReply.AsyncHandler() {
                        @Override
                        public void onCallback(Header header, Replies.Callback body) {
                            callbacks[0]++;
                            events.add(body.eventRequestId);
                        }

                        @Override
                        public void onMonitorEnd(Header header, Replies.MonitorEnd body) {
                        }
                    });
                    // The callbacks are already queued on the monitor's socket; drain them.
                    monitor.engine.pumpUntil(System.nanoTime() + 150_000_000L, () -> false);
                    monitor.engine.setAsyncHandler(null);
                    if (mutation.transmissions > 1 && callbacks[0] > events.size()) {
                        r.e8Duplicate++;
                    }
                }

                // E6 claims SET_FARE stays correct despite re-execution, so count the trials
                // in which the server actually executed it twice rather than assuming so.
                Thread.sleep(50L);
                if (countLines(server, "EXECUTE", "opcode=SET_FARE", "re-executed") > 0) {
                    r.e6Reexecuted++;
                }
                if (!server.isAlive()) throw new AssertionError("reply-loss server exited");
            }

            port = freePort();
            try (ServerIntegrationTest.ServerProcess server =
                         new ServerIntegrationTest.ServerProcess(binary, port, "at-most-once",
                                 "--drop-reply", "0.5", "--seed", Integer.toString(trial));
                 ServerIntegrationTest.RealClient c = client(port, "at-most-once")) {
                c.engine.call(Protocol.OP_RESERVE, new Requests.Reserve(1934, 2)::write);
                final Replies.Flight reserved = query(c, 1934);
                if (reserved != null && reserved.seatsAvailable == 118) r.e3Correct++;

                c.engine.call(Protocol.OP_ADJUST_FARE, new Requests.AdjustFare(2201, 1.0F)::write);
                final Replies.Flight adjusted = query(c, 2201);
                if (adjusted != null && adjusted.airfare == 1205.0F) r.e5Correct++;
                if (!server.isAlive()) throw new AssertionError("at-most-once server exited");
            }
        }
    }

    private static void requestLoss(Path binary, int trials, Results r) throws Exception {
        for (int trial = 0; trial < trials; trial++) {
            for (String semantics : new String[] {"at-least-once", "at-most-once"}) {
                final int port = freePort();
                try (ServerIntegrationTest.ServerProcess server =
                             new ServerIntegrationTest.ServerProcess(binary, port, semantics,
                                     "--drop-request", "0.5", "--seed", Integer.toString(trial));
                     ServerIntegrationTest.RealClient c = client(port, semantics)) {
                    final RequestReply.Outcome reserve = c.engine.call(Protocol.OP_RESERVE,
                            new Requests.Reserve(1934, 2)::write);
                    final Replies.Flight state = query(c, 1934);
                    final boolean correct = state != null && (state.seatsAvailable == 118
                            || (reserve.timedOut && state.seatsAvailable == 120));
                    if (correct) r.e7Correct++;
                    if (!server.isAlive()) throw new AssertionError("request-loss server exited");
                }
            }
        }
    }

    private static void historyBound(Path binary, Results r) throws Exception {
        final int port = freePort();
        try (ServerIntegrationTest.ServerProcess server =
                     new ServerIntegrationTest.ServerProcess(binary, port, "at-most-once");
             ServerIntegrationTest.RealClient acking = client(port, "at-most-once");
             ServerIntegrationTest.Client silent = new ServerIntegrationTest.Client(
                     port, SILENT_CLIENT_ID, Protocol.SEM_AT_MOST_ONCE)) {
            for (int i = 0; i < 200; i++) {
                acking.engine.call(Protocol.OP_QUERY_BY_ID, new Requests.Flight(1934)::write);
            }
            // Hand-built requests carry NO_ACK every time: the server can only fall back on
            // its per-client cap.
            for (int id = 0; id < 200; id++) {
                silent.send(silent.requestBytes(id, Protocol.OP_QUERY_BY_ID,
                        out -> new Requests.Flight(1934).write(out)));
                if (silent.receiveMatching(Protocol.MSG_REPLY, id, 500) == null) break;
            }
            Thread.sleep(100L);
            final String silentHex = String.format("0x%08X", SILENT_CLIENT_ID);
            r.e9WithAck = maxClientEntries(server, acking.clientHex());
            r.e9WithoutAck = maxClientEntries(server, silentHex);
            r.e9CapEvictions = countLines(server, "client=" + silentHex, "per-client-cap");
        }
    }

    private static void callbackLoss(Path binary, int trials, Results r) throws Exception {
        for (int trial = 0; trial < trials; trial++) {
            final int port = freePort();
            try (ServerIntegrationTest.ServerProcess server =
                         new ServerIntegrationTest.ServerProcess(binary, port, "at-most-once",
                                 "--drop-callback", "0.5", "--seed", Integer.toString(trial));
                 ServerIntegrationTest.RealClient monitor = client(port, "at-most-once");
                 ServerIntegrationTest.RealClient mutator = client(port, "at-most-once")) {
                final RequestReply.Outcome registration = monitor.engine.call(Protocol.OP_MONITOR,
                        new Requests.Monitor(1934, 350)::write);
                if (!ok(registration)) continue;
                final int baseline = Replies.Monitor.read(registration.body).seatsAvailable;
                for (int i = 0; i < 6; i++) {
                    mutator.engine.call(Protocol.OP_RESERVE, new Requests.Reserve(1934, 1)::write);
                }
                final ByteArrayOutputStream captured = new ByteArrayOutputStream();
                new MonitorSession(1934, 350, baseline, 200, monitor.trace,
                        new PrintStream(captured, true, StandardCharsets.UTF_8)).run(monitor.engine);
                final Matcher m = MONITOR_SUMMARY.matcher(captured.toString(StandardCharsets.UTF_8));
                if (m.find()) {
                    final int received = Integer.parseInt(m.group(1));
                    final int total = Integer.parseInt(m.group(2));
                    if (total == 6 && received <= total) r.e10Reconciled++;
                    if (total == 6 && received < total) r.e10WithLoss++;
                }
                if (!server.isAlive()) throw new AssertionError("callback-loss server exited");
            }
        }
    }

    private static boolean row(String id, String observation, boolean pass) {
        System.out.println("| " + id + " | " + observation + " | "
                + (pass ? "PASS" : "FAIL") + " |");
        return pass;
    }

    public static void main(String[] args) throws Exception {
        final String binaryProperty = System.getProperty("sc6103.server.binary");
        if (binaryProperty == null || binaryProperty.isEmpty()) {
            throw new IllegalArgumentException(
                    "Set -Dsc6103.server.binary=<path> to the C++ server binary");
        }
        final int trials = Integer.getInteger("sc6103.experiment.trials", 20);
        if (trials < 1) throw new IllegalArgumentException("trials must be positive");
        final Path binary = Path.of(binaryProperty);
        final Results r = new Results();
        noLoss(binary, trials, r);
        replyLoss(binary, trials, r);
        requestLoss(binary, trials, r);
        historyBound(binary, r);
        callbackLoss(binary, trials, r);

        System.out.println("# Invocation-semantics experiment results\n");
        System.out.println("Trials per probabilistic scenario: " + trials);
        System.out.println("Client: sc6103.client.RequestReply, timeout " + TIMEOUT_MS
                + " ms, " + RETRIES + " retries; loss injected on the server only\n");
        System.out.println("| Experiment | Observation | Result |");
        System.out.println("|---|---:|---|");
        int passed = 0;
        if (row("E1", r.e1Correct + "/" + (trials * 2) + " correct without loss",
                r.e1Correct == trials * 2)) passed++;
        if (row("E2", r.e2Corrupt + "/" + trials + " at-least-once trials over-reserved",
                r.e2Corrupt > 0)) passed++;
        if (row("E3", r.e3Correct + "/" + trials + " at-most-once trials correct",
                r.e3Correct == trials)) passed++;
        if (row("E4", r.e4Corrupt + "/" + trials + " at-least-once fares over-adjusted",
                r.e4Corrupt > 0)) passed++;
        if (row("E5", r.e5Correct + "/" + trials + " at-most-once fares correct",
                r.e5Correct == trials)) passed++;
        if (row("E6", r.e6Correct + "/" + trials + " SET_FARE results correct; re-executed in "
                + r.e6Reexecuted + " of them", r.e6Correct == trials && r.e6Reexecuted > 0)) passed++;
        if (row("E7", r.e7Correct + "/" + (trials * 2) + " request-loss trials correct",
                r.e7Correct == trials * 2)) passed++;
        if (row("E8", r.e8Duplicate + "/" + trials + " monitors saw repeated event IDs",
                r.e8Duplicate > 0)) passed++;
        if (row("E9", "largest history per client over 200 requests: " + r.e9WithAck
                + " with ackUpTo, " + r.e9WithoutAck + " without (" + r.e9CapEvictions
                + " cap evictions)", r.e9WithAck == 1
                && r.e9WithoutAck == MAX_HISTORY_PER_CLIENT && r.e9CapEvictions > 0)) passed++;
        if (row("E10", r.e10Reconciled + "/" + trials + " monitor summaries reconciled; "
                + r.e10WithLoss + " reported callback loss",
                r.e10Reconciled == trials && r.e10WithLoss > 0)) passed++;
        System.out.println("\nOverall: " + passed + "/10 experiments passed.");
        if (passed != 10) System.exit(1);
    }
}
