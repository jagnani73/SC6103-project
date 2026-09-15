package sc6103.integration;

import java.io.IOException;
import java.net.DatagramSocket;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import sc6103.common.Protocol;
import sc6103.common.Replies;
import sc6103.common.Requests;
import sc6103.common.StatusCode;

/** Runs the E1-E10 experiment plan and prints a compact Markdown result table. */
public final class ExperimentHarness {

    private static final int MAX_ATTEMPTS = 10;
    private static final int TIMEOUT_MS = 35;

    private static final class Retry {
        final ServerIntegrationTest.Inbound inbound;
        final int attempts;

        Retry(ServerIntegrationTest.Inbound inbound, int attempts) {
            this.inbound = inbound;
            this.attempts = attempts;
        }
    }

    private static final class Results {
        int e1Correct;
        int e2Corrupt;
        int e3Correct;
        int e4Corrupt;
        int e5Correct;
        int e6Correct;
        int e7Correct;
        int e8Duplicate;
        boolean e9Pruned;
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

    private static Retry retry(ServerIntegrationTest.Client client, int requestId,
                               int opcode, ServerIntegrationTest.BodyWriter body)
            throws Exception {
        byte[] bytes = client.requestBytes(requestId, opcode, body);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            client.send(bytes);
            ServerIntegrationTest.Inbound inbound = client.receiveMatching(
                    Protocol.MSG_REPLY, requestId, TIMEOUT_MS);
            if (inbound != null) return new Retry(inbound, attempt);
        }
        return new Retry(null, MAX_ATTEMPTS);
    }

    private static Replies.Flight query(ServerIntegrationTest.Client client,
                                        int requestId, int flightId) throws Exception {
        Retry result = retry(client, requestId, Protocol.OP_QUERY_BY_ID,
                out -> new Requests.Flight(flightId).write(out));
        if (result.inbound == null || result.inbound.header.status != StatusCode.OK) return null;
        return Replies.Flight.read(result.inbound.body);
    }

    private static void noLoss(Path binary, int trials, Results results) throws Exception {
        for (int trial = 0; trial < trials; trial++) {
            for (int semantics : new int[] {
                    Protocol.SEM_AT_LEAST_ONCE, Protocol.SEM_AT_MOST_ONCE }) {
                int port = freePort();
                String name = semantics == Protocol.SEM_AT_MOST_ONCE
                        ? "at-most-once" : "at-least-once";
                try (ServerIntegrationTest.ServerProcess server =
                             new ServerIntegrationTest.ServerProcess(binary, port, name,
                                     "--seed", Integer.toString(trial));
                     ServerIntegrationTest.Client client =
                             new ServerIntegrationTest.Client(port, 0x10000000L + trial,
                                     semantics)) {
                    Retry reserve = retry(client, 0, Protocol.OP_RESERVE,
                            out -> new Requests.Reserve(1934, 2).write(out));
                    if (reserve.inbound != null
                            && Replies.Reserve.read(reserve.inbound.body).seatsAvailable == 118) {
                        results.e1Correct++;
                    }
                    if (!server.isAlive()) throw new AssertionError("E1 server exited");
                }
            }
        }
    }

    private static void replyLoss(Path binary, int trials, Results results) throws Exception {
        for (int trial = 0; trial < trials; trial++) {
            int port = freePort();
            try (ServerIntegrationTest.ServerProcess server =
                         new ServerIntegrationTest.ServerProcess(binary, port, "at-least-once",
                                 "--drop-reply", "0.5", "--seed", Integer.toString(trial));
                 ServerIntegrationTest.Client client = new ServerIntegrationTest.Client(
                         port, 0x20000000L + trial, Protocol.SEM_AT_LEAST_ONCE);
                 ServerIntegrationTest.Client monitor = new ServerIntegrationTest.Client(
                         port, 0x21000000L + trial, Protocol.SEM_AT_LEAST_ONCE)) {
                retry(client, 0, Protocol.OP_RESERVE,
                        out -> new Requests.Reserve(1934, 2).write(out));
                Replies.Flight reserved = query(client, 100, 1934);
                if (reserved != null && reserved.seatsAvailable < 118) results.e2Corrupt++;

                retry(client, 1, Protocol.OP_ADJUST_FARE,
                        out -> new Requests.AdjustFare(2201, 1.0F).write(out));
                Replies.Flight adjusted = query(client, 101, 2201);
                if (adjusted != null && adjusted.airfare > 1205.0F) results.e4Corrupt++;

                retry(client, 2, Protocol.OP_SET_FARE,
                        out -> new Requests.SetFare(412, 750.0F).write(out));
                Replies.Flight set = query(client, 102, 412);
                if (set != null && set.airfare == 750.0F) results.e6Correct++;

                Retry registration = retry(monitor, 0, Protocol.OP_MONITOR,
                        out -> new Requests.Monitor(412, 500).write(out));
                if (registration.inbound != null) {
                    Retry mutation = retry(client, 3, Protocol.OP_RESERVE,
                            out -> new Requests.Reserve(412, 1).write(out));
                    int callbacks = 0;
                    Set<Integer> events = new HashSet<>();
                    while (true) {
                        ServerIntegrationTest.Inbound callback = monitor.receiveType(
                                Protocol.MSG_CALLBACK, 80);
                        if (callback == null) break;
                        Replies.Callback body = Replies.Callback.read(callback.body);
                        callbacks++;
                        events.add(body.eventRequestId);
                    }
                    if (mutation.attempts > 1 && callbacks > events.size()) {
                        results.e8Duplicate++;
                    }
                }
                if (!server.isAlive()) throw new AssertionError("reply-loss server exited");
            }

            port = freePort();
            try (ServerIntegrationTest.ServerProcess server =
                         new ServerIntegrationTest.ServerProcess(binary, port, "at-most-once",
                                 "--drop-reply", "0.5", "--seed", Integer.toString(trial));
                 ServerIntegrationTest.Client client = new ServerIntegrationTest.Client(
                         port, 0x30000000L + trial, Protocol.SEM_AT_MOST_ONCE)) {
                retry(client, 0, Protocol.OP_RESERVE,
                        out -> new Requests.Reserve(1934, 2).write(out));
                Replies.Flight reserved = query(client, 100, 1934);
                if (reserved != null && reserved.seatsAvailable == 118) results.e3Correct++;
                retry(client, 1, Protocol.OP_ADJUST_FARE,
                        out -> new Requests.AdjustFare(2201, 1.0F).write(out));
                Replies.Flight adjusted = query(client, 101, 2201);
                if (adjusted != null && adjusted.airfare == 1205.0F) results.e5Correct++;
                if (!server.isAlive()) throw new AssertionError("at-most server exited");
            }
        }
    }

    private static void requestLoss(Path binary, int trials, Results results) throws Exception {
        for (int trial = 0; trial < trials; trial++) {
            for (int semantics : new int[] {
                    Protocol.SEM_AT_LEAST_ONCE, Protocol.SEM_AT_MOST_ONCE }) {
                int port = freePort();
                String name = semantics == Protocol.SEM_AT_MOST_ONCE
                        ? "at-most-once" : "at-least-once";
                try (ServerIntegrationTest.ServerProcess server =
                             new ServerIntegrationTest.ServerProcess(binary, port, name,
                                     "--drop-request", "0.5", "--seed", Integer.toString(trial));
                     ServerIntegrationTest.Client client = new ServerIntegrationTest.Client(
                             port, 0x40000000L + trial + semantics * 1000L, semantics)) {
                    Retry reserve = retry(client, 0, Protocol.OP_RESERVE,
                            out -> new Requests.Reserve(1934, 2).write(out));
                    Replies.Flight state = query(client, 100, 1934);
                    boolean correct = state != null && (state.seatsAvailable == 118
                            || (reserve.inbound == null && state.seatsAvailable == 120));
                    if (correct) results.e7Correct++;
                    if (!server.isAlive()) throw new AssertionError("request-loss server exited");
                }
            }
        }
    }

    private static void historyBound(Path binary, Results results) throws Exception {
        int port = freePort();
        try (ServerIntegrationTest.ServerProcess server =
                     new ServerIntegrationTest.ServerProcess(binary, port, "at-most-once");
             ServerIntegrationTest.Client client = new ServerIntegrationTest.Client(
                     port, 0x50000000L, Protocol.SEM_AT_MOST_ONCE)) {
            for (int id = 0; id < 200; id++) {
                int ack = id == 0 ? Protocol.NO_ACK : id - 1;
                byte[] bytes = client.requestBytes(id, Protocol.OP_QUERY_BY_ID, ack,
                        out -> new Requests.Flight(1934).write(out));
                client.send(bytes);
                if (client.receiveMatching(Protocol.MSG_REPLY, id, 500) == null) break;
            }
            Thread.sleep(50L);
            results.e9Pruned = server.outputContains("ack-watermark");
        }
    }

    private static void callbackLoss(Path binary, int trials, Results results) throws Exception {
        for (int trial = 0; trial < trials; trial++) {
            int port = freePort();
            try (ServerIntegrationTest.ServerProcess server =
                         new ServerIntegrationTest.ServerProcess(binary, port, "at-most-once",
                                 "--drop-callback", "0.5", "--seed", Integer.toString(trial));
                 ServerIntegrationTest.Client monitor = new ServerIntegrationTest.Client(
                         port, 0x60000000L + trial, Protocol.SEM_AT_MOST_ONCE);
                 ServerIntegrationTest.Client mutator = new ServerIntegrationTest.Client(
                         port, 0x61000000L + trial, Protocol.SEM_AT_MOST_ONCE)) {
                Retry registered = retry(monitor, 0, Protocol.OP_MONITOR,
                        out -> new Requests.Monitor(1934, 350).write(out));
                if (registered.inbound == null) continue;
                for (int id = 0; id < 6; id++) {
                    retry(mutator, id, Protocol.OP_RESERVE,
                            out -> new Requests.Reserve(1934, 1).write(out));
                }
                int received = 0;
                int finalTotal = -1;
                long deadline = System.nanoTime() + 1_500_000_000L;
                while (System.nanoTime() < deadline && finalTotal < 0) {
                    ServerIntegrationTest.Inbound asynchronous = monitor.receiveAny(500);
                    if (asynchronous == null) continue;
                    if (asynchronous.header.messageType == Protocol.MSG_CALLBACK) {
                        received++;
                    } else if (asynchronous.header.messageType == Protocol.MSG_MONITOR_END) {
                        finalTotal = Replies.MonitorEnd.read(asynchronous.body).callbackSeq;
                    }
                }
                if (finalTotal == 6 && received <= finalTotal) results.e10Reconciled++;
                if (finalTotal == 6 && received < finalTotal) results.e10WithLoss++;
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
        String binaryProperty = System.getProperty("sc6103.server.binary");
        if (binaryProperty == null || binaryProperty.isEmpty()) {
            throw new IllegalArgumentException(
                    "Set -Dsc6103.server.binary=<path> to the C++ server binary");
        }
        int trials = Integer.getInteger("sc6103.experiment.trials", 20);
        if (trials < 1) throw new IllegalArgumentException("trials must be positive");
        Path binary = Path.of(binaryProperty);
        Results results = new Results();
        noLoss(binary, trials, results);
        replyLoss(binary, trials, results);
        requestLoss(binary, trials, results);
        historyBound(binary, results);
        callbackLoss(binary, trials, results);

        System.out.println("# Invocation-semantics experiment results\n");
        System.out.println("Trials per probabilistic scenario: " + trials + "\n");
        System.out.println("| Experiment | Observation | Result |");
        System.out.println("|---|---:|---|");
        int passed = 0;
        if (row("E1", results.e1Correct + "/" + (trials * 2) + " correct without loss",
                results.e1Correct == trials * 2)) passed++;
        if (row("E2", results.e2Corrupt + "/" + trials + " at-least-once trials over-reserved",
                results.e2Corrupt > 0)) passed++;
        if (row("E3", results.e3Correct + "/" + trials + " at-most-once trials correct",
                results.e3Correct == trials)) passed++;
        if (row("E4", results.e4Corrupt + "/" + trials + " at-least-once fares over-adjusted",
                results.e4Corrupt > 0)) passed++;
        if (row("E5", results.e5Correct + "/" + trials + " at-most-once fares correct",
                results.e5Correct == trials)) passed++;
        if (row("E6", results.e6Correct + "/" + trials + " repeated SET_FARE results correct",
                results.e6Correct == trials)) passed++;
        if (row("E7", results.e7Correct + "/" + (trials * 2) + " request-loss trials correct",
                results.e7Correct == trials * 2)) passed++;
        if (row("E8", results.e8Duplicate + "/" + trials + " monitors saw repeated event IDs",
                results.e8Duplicate > 0)) passed++;
        if (row("E9", results.e9Pruned ? "ackUpTo evictions observed" : "no eviction observed",
                results.e9Pruned)) passed++;
        if (row("E10", results.e10Reconciled + "/" + trials + " totals reconciled; "
                + results.e10WithLoss + " had callback loss",
                results.e10Reconciled == trials && results.e10WithLoss > 0)) passed++;
        System.out.println("\nOverall: " + passed + "/10 experiments passed.");
        if (passed != 10) System.exit(1);
    }
}
