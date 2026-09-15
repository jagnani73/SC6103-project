package sc6103.integration;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import sc6103.client.ClientConfig;
import sc6103.client.LossSimulator;
import sc6103.client.MonitorSession;
import sc6103.client.RequestReply;
import sc6103.client.TraceLog;
import sc6103.client.UdpChannel;
import sc6103.common.Codec;
import sc6103.common.CodecException;
import sc6103.common.Header;
import sc6103.common.Protocol;
import sc6103.common.Replies;
import sc6103.common.Requests;
import sc6103.common.StatusCode;

/**
 * Black-box Java-to-C++ integration coverage for README sec. 14.
 *
 * <p>The test deliberately uses the Java codec and a real UDP socket. A C++ reply that
 * this class accepts has therefore crossed the language boundary and survived an
 * independently-written decoder; testing the C++ dispatcher only through C++ calls
 * would not establish that contract.
 */
public final class ServerIntegrationTest {

    interface BodyWriter {
        void write(Codec out) throws CodecException;
    }

    static final class Inbound {
        final Header header;
        final Codec body;
        final byte[] bytes;

        Inbound(Header header, Codec body, byte[] bytes) {
            this.header = header;
            this.body = body;
            this.bytes = bytes;
        }
    }

    static final class Client implements AutoCloseable {
        private final DatagramSocket socket;
        private final InetSocketAddress server;
        private final long clientId;
        private final int semantics;
        private int requestId;

        Client(int port, long clientId, int semantics) throws IOException {
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
            this.clientId = clientId;
            this.semantics = semantics;
        }

        byte[] requestBytes(int id, int opcode, BodyWriter body) throws CodecException {
            return requestBytes(id, opcode, Protocol.NO_ACK, body);
        }

        byte[] requestBytes(int id, int opcode, int ackUpTo, BodyWriter body)
                throws CodecException {
            byte[] bodyBytes = new byte[Protocol.MAX_BODY];
            Codec bodyOut = Codec.writer(bodyBytes);
            body.write(bodyOut);
            int bodyLength = bodyOut.position();
            byte[] request = new byte[Protocol.HEADER_SIZE + bodyLength];
            Codec out = Codec.writer(request);
            Header.request(clientId, id, opcode, semantics, ackUpTo, bodyLength).write(out);
            System.arraycopy(bodyBytes, 0, request, Protocol.HEADER_SIZE, bodyLength);
            return request;
        }

        Inbound call(int opcode, BodyWriter body) throws Exception {
            int id = requestId++;
            byte[] request = requestBytes(id, opcode, body);
            send(request);
            return receiveMatching(Protocol.MSG_REPLY, id, 1500);
        }

        void send(byte[] bytes) throws IOException {
            socket.send(new DatagramPacket(bytes, bytes.length, server));
        }

        Inbound receiveMatching(int messageType, int id, int timeoutMs) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                int remaining = (int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(
                        deadline - System.nanoTime()));
                socket.setSoTimeout(remaining);
                byte[] buffer = new byte[Protocol.MAX_DATAGRAM];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    return null;
                }
                byte[] bytes = Arrays.copyOf(buffer, packet.getLength());
                Codec in = Codec.reader(bytes, bytes.length);
                Header header = Header.read(in);
                if (bytes.length != Protocol.HEADER_SIZE + header.payloadLen) {
                    throw new AssertionError("reply length disagrees with header");
                }
                if (header.clientId == clientId && header.messageType == messageType
                        && header.requestId == id) {
                    return new Inbound(header, in, bytes);
                }
            }
            return null;
        }

        Inbound receiveType(int messageType, int timeoutMs) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                int remaining = (int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(
                        deadline - System.nanoTime()));
                socket.setSoTimeout(remaining);
                byte[] buffer = new byte[Protocol.MAX_DATAGRAM];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    return null;
                }
                byte[] bytes = Arrays.copyOf(buffer, packet.getLength());
                Codec in = Codec.reader(bytes, bytes.length);
                Header header = Header.read(in);
                if (header.clientId == clientId && header.messageType == messageType) {
                    return new Inbound(header, in, bytes);
                }
            }
            return null;
        }

        Inbound receiveAny(int timeoutMs) throws Exception {
            socket.setSoTimeout(timeoutMs);
            byte[] buffer = new byte[Protocol.MAX_DATAGRAM];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                return null;
            }
            byte[] bytes = Arrays.copyOf(buffer, packet.getLength());
            Codec in = Codec.reader(bytes, bytes.length);
            Header header = Header.read(in);
            if (header.clientId != clientId
                    || bytes.length != Protocol.HEADER_SIZE + header.payloadLen) {
                return null;
            }
            return new Inbound(header, in, bytes);
        }

        @Override
        public void close() {
            socket.close();
        }
    }

    static final class ServerProcess implements Closeable {
        private final Process process;
        private final CountDownLatch ready = new CountDownLatch(1);
        private final List<String> output = new ArrayList<>();

        ServerProcess(Path binary, int port, String semantics, String... extra) throws Exception {
            List<String> command = new ArrayList<>();
            command.add(binary.toAbsolutePath().toString());
            command.add("--port");
            command.add(Integer.toString(port));
            command.add("--semantics");
            command.add(semantics);
            command.add("--seed");
            command.add("42");
            command.addAll(Arrays.asList(extra));
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Thread reader = new Thread(() -> consume(process), "server-output");
            reader.setDaemon(true);
            reader.start();
            if (!ready.await(4, TimeUnit.SECONDS)) {
                throw new AssertionError("server did not become ready; output=" + output);
            }
        }

        private void consume(Process running) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    running.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (output) {
                        output.add(line);
                    }
                    if (line.equals("Seeded flights:")) ready.countDown();
                }
            } catch (IOException ignored) {
                // The stream closes when the short-lived test process is destroyed.
            }
        }

        boolean isAlive() {
            return process.isAlive();
        }

        /** A snapshot of every line the server has printed so far. */
        List<String> lines() {
            synchronized (output) {
                return new ArrayList<>(output);
            }
        }

        boolean outputContains(String text) {
            synchronized (output) {
                for (String line : output) {
                    if (line.contains(text)) return true;
                }
            }
            return false;
        }

        @Override
        public void close() throws IOException {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("interrupted while stopping test server", e);
            }
        }
    }

    private static int checks;
    private static int failures;

    private ServerIntegrationTest() {
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (condition) {
            System.out.println("PASS " + label);
        } else {
            failures++;
            System.err.println("FAIL " + label);
        }
    }

    private static int freePort() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void checkStatus(Inbound inbound, int status, String label) {
        check(inbound != null && inbound.header.status == status, label);
    }

    private static void generalOperations(Path binary) throws Exception {
        int port = freePort();
        try (ServerProcess server = new ServerProcess(binary, port, "at-most-once");
             Client a = new Client(port, 0xA1B2C3D4L, Protocol.SEM_AT_MOST_ONCE);
             Client b = new Client(port, 0x0000B002L, Protocol.SEM_AT_MOST_ONCE);
             Client c = new Client(port, 0x0000C003L, Protocol.SEM_AT_MOST_ONCE)) {
            check(server.isAlive(), "I00 general server is running");

            Inbound route = a.call(Protocol.OP_QUERY_BY_ROUTE,
                    out -> new Requests.Route(" singapore ", "LONDON").write(out));
            checkStatus(route, StatusCode.OK, "I01 route query succeeds with trim/case folding");
            Replies.Route routeBody = Replies.Route.read(route.body);
            check(Arrays.equals(routeBody.flightIds, new int[] {88, 1934, 2201}),
                    "I02 route IDs are complete and ascending");

            Inbound details = a.call(Protocol.OP_QUERY_BY_ID,
                    out -> new Requests.Flight(1934).write(out));
            checkStatus(details, StatusCode.OK, "I03 query by id succeeds");
            Replies.Flight flight = Replies.Flight.read(details.body);
            check(flight.airfare == 899.5F && flight.seatsAvailable == 120
                    && flight.departure.year() == 2026, "I04 C++ flight body decodes in Java");

            Inbound noRoute = a.call(Protocol.OP_QUERY_BY_ROUTE,
                    out -> new Requests.Route("Singapore", "Reykjavik").write(out));
            checkStatus(noRoute, StatusCode.ERR_NO_ROUTE_MATCH, "I05 no route is an error");
            check(noRoute.header.payloadLen == 0, "I06 error replies have no body");

            Inbound missing = a.call(Protocol.OP_QUERY_BY_ID,
                    out -> new Requests.Flight(9999).write(out));
            checkStatus(missing, StatusCode.ERR_UNKNOWN_FLIGHT, "I07 unknown flight is rejected");

            int duplicateId = 40;
            byte[] reserve = a.requestBytes(duplicateId, Protocol.OP_RESERVE,
                    out -> new Requests.Reserve(1934, 2).write(out));
            a.send(reserve);
            Inbound first = a.receiveMatching(Protocol.MSG_REPLY, duplicateId, 1000);
            a.send(reserve);
            Inbound duplicate = a.receiveMatching(Protocol.MSG_REPLY, duplicateId, 1000);
            checkStatus(first, StatusCode.OK, "I08 first reserve succeeds");
            checkStatus(duplicate, StatusCode.OK, "I09 duplicate reserve gets a reply");
            check(Arrays.equals(first.bytes, duplicate.bytes), "I10 at-most-once replays exact reply bytes");
            check(Replies.Reserve.read(duplicate.body).seatsAvailable == 118,
                    "I11 duplicate reserve executes once");

            byte[] changed = a.requestBytes(duplicateId, Protocol.OP_RESERVE,
                    out -> new Requests.Reserve(1934, 1).write(out));
            a.send(changed);
            Inbound conflict = a.receiveMatching(Protocol.MSG_REPLY, duplicateId, 1000);
            checkStatus(conflict, StatusCode.ERR_REQUEST_ID_CONFLICT,
                    "I12 reused request id with changed body conflicts");

            Inbound insufficient = a.call(Protocol.OP_RESERVE,
                    out -> new Requests.Reserve(88, 1).write(out));
            checkStatus(insufficient, StatusCode.ERR_INSUFFICIENT_SEATS,
                    "I13 insufficient seats is rejected");

            Inbound invalidSeats = a.call(Protocol.OP_RESERVE, out -> {
                out.putInt32(1934);
                out.putInt32(0);
            });
            checkStatus(invalidSeats, StatusCode.ERR_INVALID_ARGUMENT,
                    "I14 zero-seat reservation is rejected");

            Inbound exactSeats = a.call(Protocol.OP_RESERVE,
                    out -> new Requests.Reserve(2201, 6).write(out));
            check(exactSeats.header.status == StatusCode.OK
                    && Replies.Reserve.read(exactSeats.body).seatsAvailable == 0,
                    "I14a reserving exactly all remaining seats succeeds");
            Inbound oneTooMany = a.call(Protocol.OP_RESERVE,
                    out -> new Requests.Reserve(2201, 1).write(out));
            checkStatus(oneTooMany, StatusCode.ERR_INSUFFICIENT_SEATS,
                    "I14b one seat beyond the boundary is rejected");

            Inbound adjust = a.call(Protocol.OP_ADJUST_FARE,
                    out -> new Requests.AdjustFare(1934, -49.5F).write(out));
            checkStatus(adjust, StatusCode.OK, "I15 adjust fare succeeds");
            check(Replies.Fare.read(adjust.body).airfare == 850.0F,
                    "I16 relative fare result crosses language boundary exactly");

            Inbound set = a.call(Protocol.OP_SET_FARE,
                    out -> new Requests.SetFare(1934, 750.0F).write(out));
            checkStatus(set, StatusCode.OK, "I17 set fare succeeds");
            check(Replies.Fare.read(set.body).airfare == 750.0F, "I18 absolute fare is exact");

            Inbound invalidFare = a.call(Protocol.OP_SET_FARE, out -> {
                out.putInt32(1934);
                out.putFloat32(0.0F);
            });
            checkStatus(invalidFare, StatusCode.ERR_INVALID_FARE, "I19 zero fare is rejected");

            Inbound nanFare = a.call(Protocol.OP_ADJUST_FARE, out -> {
                out.putInt32(1934);
                out.putInt32(0x7FC00000);
            });
            checkStatus(nanFare, StatusCode.ERR_INVALID_ARGUMENT, "I20 NaN delta is rejected");

            Inbound maximumFare = a.call(Protocol.OP_ADJUST_FARE,
                    out -> new Requests.AdjustFare(9500, 0.5F).write(out));
            check(maximumFare.header.status == StatusCode.OK
                    && Replies.Fare.read(maximumFare.body).airfare == Protocol.MAX_FARE,
                    "I20a ADJUST_FARE may land exactly on MAX_FARE");
            Inbound aboveMaximum = a.call(Protocol.OP_ADJUST_FARE,
                    out -> new Requests.AdjustFare(9500, 0.25F).write(out));
            checkStatus(aboveMaximum, StatusCode.ERR_INVALID_FARE,
                    "I20b ADJUST_FARE above MAX_FARE is rejected");
            Inbound lowFare = a.call(Protocol.OP_ADJUST_FARE,
                    out -> new Requests.AdjustFare(7300, -0.75F).write(out));
            check(lowFare.header.status == StatusCode.OK
                    && Replies.Fare.read(lowFare.body).airfare == 0.25F,
                    "I20c ADJUST_FARE may land just above zero");
            Inbound zeroResult = a.call(Protocol.OP_ADJUST_FARE,
                    out -> new Requests.AdjustFare(7300, -0.25F).write(out));
            checkStatus(zeroResult, StatusCode.ERR_INVALID_FARE,
                    "I20d ADJUST_FARE landing on zero is rejected");
            Inbound negativeSet = a.call(Protocol.OP_SET_FARE, out -> {
                out.putInt32(1934);
                out.putFloat32(-1.0F);
            });
            checkStatus(negativeSet, StatusCode.ERR_INVALID_FARE,
                    "I20e negative SET_FARE is rejected");
            Inbound invalidInterval = a.call(Protocol.OP_MONITOR, out -> {
                out.putInt32(1934);
                out.putInt32(Requests.Monitor.MAX_INTERVAL_MS + 1);
            });
            checkStatus(invalidInterval, StatusCode.ERR_INVALID_ARGUMENT,
                    "I20f monitor interval above the bound is rejected");

            Inbound monitorA = a.call(Protocol.OP_MONITOR,
                    out -> new Requests.Monitor(1934, 600).write(out));
            Inbound monitorB = b.call(Protocol.OP_MONITOR,
                    out -> new Requests.Monitor(1934, 600).write(out));
            checkStatus(monitorA, StatusCode.OK, "I21 first monitor registers");
            checkStatus(monitorB, StatusCode.OK, "I22 concurrent monitor registers");

            Inbound thirdReserve = c.call(Protocol.OP_RESERVE,
                    out -> new Requests.Reserve(1934, 1).write(out));
            checkStatus(thirdReserve, StatusCode.OK, "I23 third client reserves");
            Inbound callbackA = a.receiveType(Protocol.MSG_CALLBACK, 1000);
            Inbound callbackB = b.receiveType(Protocol.MSG_CALLBACK, 1000);
            check(callbackA != null && Replies.Callback.read(callbackA.body).seatsAvailable == 117,
                    "I24 first monitor receives callback");
            check(callbackB != null && Replies.Callback.read(callbackB.body).seatsAvailable == 117,
                    "I25 second monitor receives callback");
            Inbound endA = a.receiveType(Protocol.MSG_MONITOR_END, 1500);
            Inbound endB = b.receiveType(Protocol.MSG_MONITOR_END, 1500);
            check(endA != null && Replies.MonitorEnd.read(endA.body).callbackSeq == 1,
                    "I26 idle server expires first monitor and reports total");
            check(endB != null && Replies.MonitorEnd.read(endB.body).callbackSeq == 1,
                    "I27 idle server expires second monitor and reports total");

            List<Client> tableClients = new ArrayList<>();
            try {
                for (int i = 0; i < 65; i++) {
                    Client monitorClient = new Client(port, 0x70000000L + i,
                            Protocol.SEM_AT_MOST_ONCE);
                    tableClients.add(monitorClient);
                    Inbound registered = monitorClient.call(Protocol.OP_MONITOR,
                            out -> new Requests.Monitor(1934, 5_000).write(out));
                    if (i == 64) {
                        checkStatus(registered, StatusCode.ERR_MONITOR_TABLE_FULL,
                                "I27a fixed monitor table returns its defined full error");
                    } else if (registered.header.status != StatusCode.OK) {
                        throw new AssertionError("monitor table filled before capacity at " + i);
                    }
                }
            } finally {
                for (Client monitorClient : tableClients) monitorClient.close();
            }

            Client mismatchClient = new Client(port, 0x0000D004L, Protocol.SEM_AT_LEAST_ONCE);
            try {
                Inbound mismatch = mismatchClient.call(Protocol.OP_QUERY_BY_ID,
                        out -> new Requests.Flight(1934).write(out));
                checkStatus(mismatch, StatusCode.ERR_SEMANTICS_MISMATCH,
                        "I28 semantics mismatch is rejected");
            } finally {
                mismatchClient.close();
            }

            byte[] unknown = a.requestBytes(90, 99, out -> { });
            a.send(unknown);
            Inbound unknownReply = a.receiveMatching(Protocol.MSG_REPLY, 90, 1000);
            checkStatus(unknownReply, StatusCode.ERR_UNKNOWN_OPCODE, "I29 unknown opcode is rejected");

            byte[] malformed = a.requestBytes(91, Protocol.OP_QUERY_BY_ID,
                    out -> out.putInt32(1934));
            malformed[39] = 8; // declare 8 body bytes while transmitting 4
            a.send(malformed);
            Inbound malformedReply = a.receiveMatching(Protocol.MSG_REPLY, 91, 1000);
            checkStatus(malformedReply, StatusCode.ERR_MALFORMED_REQUEST,
                    "I30 payload length disagreement is rejected");

            byte[] wrongVersion = a.requestBytes(93, Protocol.OP_QUERY_BY_ID,
                    out -> out.putInt32(1934));
            wrongVersion[7] = 2;
            a.send(wrongVersion);
            Inbound versionReply = a.receiveMatching(Protocol.MSG_REPLY, 93, 1000);
            checkStatus(versionReply, StatusCode.ERR_VERSION_MISMATCH,
                    "I30a protocol version mismatch is reported");

            byte[] oversized = Arrays.copyOf(a.requestBytes(94, Protocol.OP_QUERY_BY_ID,
                    out -> out.putInt32(1934)), Protocol.MAX_DATAGRAM + 1);
            a.send(oversized);
            Inbound oversizedReply = a.receiveMatching(Protocol.MSG_REPLY, 94, 1000);
            checkStatus(oversizedReply, StatusCode.ERR_MALFORMED_REQUEST,
                    "I30b oversized datagram is rejected rather than truncated into validity");

            byte[] alien = a.requestBytes(92, Protocol.OP_QUERY_BY_ID,
                    out -> out.putInt32(1934));
            alien[0] = 0;
            a.send(alien);
            check(a.receiveMatching(Protocol.MSG_REPLY, 92, 250) == null,
                    "I31 bad magic is silently dropped");
        }
    }

    private static void retrySemantics(Path binary, String semantics, int expectedSeats,
                                       String labelPrefix) throws Exception {
        int port = freePort();
        int mode = semantics.equals("at-most-once")
                ? Protocol.SEM_AT_MOST_ONCE : Protocol.SEM_AT_LEAST_ONCE;
        try (ServerProcess server = new ServerProcess(binary, port, semantics,
                    "--drop-first-reply", Integer.toString(Protocol.OP_RESERVE));
             Client client = new Client(port, 0x51525354L, mode)) {
            check(server.isAlive(), labelPrefix + " server is running");
            byte[] request = client.requestBytes(0, Protocol.OP_RESERVE,
                    out -> new Requests.Reserve(1934, 2).write(out));
            client.send(request);
            check(client.receiveMatching(Protocol.MSG_REPLY, 0, 250) == null,
                    labelPrefix + " first reply is deterministically dropped");
            client.send(request);
            Inbound retry = client.receiveMatching(Protocol.MSG_REPLY, 0, 1000);
            checkStatus(retry, StatusCode.OK, labelPrefix + " retransmission receives reply");
            check(Replies.Reserve.read(retry.body).seatsAvailable == expectedSeats,
                    labelPrefix + " final state matches invocation semantics");
        }
    }

    private static void restartRestoresSeed(Path binary) throws Exception {
        int port = freePort();
        try (ServerProcess server = new ServerProcess(binary, port, "at-most-once");
             Client client = new Client(port, 0x61626364L, Protocol.SEM_AT_MOST_ONCE)) {
            check(server.isAlive(), "I38 restart server is running");
            Inbound query = client.call(Protocol.OP_QUERY_BY_ID,
                    out -> new Requests.Flight(1934).write(out));
            check(Replies.Flight.read(query.body).seatsAvailable == 120,
                    "I38 server restart restores in-memory seed state");
        }
    }

    /**
     * The Java console client's own request-reply stack without the console:
     * {@link ClientConfig}, {@link UdpChannel}, {@link TraceLog}, {@link LossSimulator} and
     * {@link RequestReply}, wired the way {@code Main} wires them.
     *
     * <p>CLAUDE.md makes the real client the test driver for the server. Checks about
     * retries, reply matching and monitoring go through this class; the hand-built
     * {@link Client} remains for datagrams the real client would never send.
     */
    static final class RealClient implements AutoCloseable {
        final ClientConfig config;
        final TraceLog trace;
        final UdpChannel channel;
        final RequestReply engine;

        RealClient(int port, String semantics, int timeoutMs, int retries) throws IOException {
            config = ClientConfig.parse(new String[] {
                    "--host", InetAddress.getLoopbackAddress().getHostAddress(),
                    "--port", Integer.toString(port),
                    "--semantics", semantics,
                    "--timeout", Integer.toString(timeoutMs),
                    "--retries", Integer.toString(retries),
                    "--seed", "1"});
            final long clientId = RequestReply.newClientId();
            trace = new TraceLog(new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8), clientId);
            channel = new UdpChannel(config.host, config.port, trace);
            engine = new RequestReply(channel, config, trace,
                    new LossSimulator(1L, 0.0, 0.0, false), clientId);
        }

        /** The client id in the form both programs print it. */
        String clientHex() {
            return String.format(Locale.ROOT, "0x%08X", engine.clientId() & 0xFFFFFFFFL);
        }

        @Override
        public void close() {
            channel.close();
        }
    }

    /**
     * README sec. 18 steps 6 and 7 with the real client on both ends: a monitoring client
     * registers, a second client reserves while the server drops the first RESERVE reply,
     * and the monitor session summarises what it saw. Under at-least-once the reservation
     * runs twice and the monitor names the repeat; under at-most-once it runs once.
     */
    private static void realClientReplay(Path binary, String semantics, int expectedSeats,
                                         String expectedSummary, boolean expectRepeat,
                                         String labelPrefix) throws Exception {
        int port = freePort();
        try (ServerProcess server = new ServerProcess(binary, port, semantics,
                    "--drop-first-reply", Integer.toString(Protocol.OP_RESERVE));
             RealClient monitor = new RealClient(port, semantics, 150, 3);
             RealClient reserver = new RealClient(port, semantics, 150, 3)) {
            RequestReply.Outcome registration = monitor.engine.call(Protocol.OP_MONITOR,
                    new Requests.Monitor(1934, 600)::write);
            boolean registered = !registration.timedOut
                    && registration.header.status == StatusCode.OK;
            check(registered, labelPrefix + " real client registers a monitor");
            if (!registered) return;

            RequestReply.Outcome reserve = reserver.engine.call(Protocol.OP_RESERVE,
                    new Requests.Reserve(1934, 2)::write);
            check(!reserve.timedOut && reserve.transmissions == 2
                            && Replies.Reserve.read(reserve.body).seatsAvailable == expectedSeats,
                    labelPrefix + " real client retransmits once and sees " + expectedSeats
                            + " seats");

            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            new MonitorSession(1934, 600, Replies.Monitor.read(registration.body).seatsAvailable,
                    monitor.config.timeoutMs, monitor.trace,
                    new PrintStream(captured, true, StandardCharsets.UTF_8)).run(monitor.engine);
            String summary = captured.toString(StandardCharsets.UTF_8);
            check(summary.contains(expectedSummary),
                    labelPrefix + " monitor session reports \"" + expectedSummary + "\"");
            check(summary.contains("twice") == expectRepeat, labelPrefix
                    + (expectRepeat ? " monitor names the repeated execution"
                                    : " monitor sees no repeated execution"));
            check(server.outputContains("client=" + reserver.clientHex()),
                    labelPrefix + " server log names the client as the client prints it");
        }
    }

    public static void main(String[] args) throws Exception {
        String binaryProperty = System.getProperty("sc6103.server.binary");
        if (binaryProperty == null || binaryProperty.isEmpty()) {
            System.err.println("Set -Dsc6103.server.binary=<path> to the C++ flight_server binary");
            System.exit(2);
        }
        Path binary = Path.of(binaryProperty);
        generalOperations(binary);
        retrySemantics(binary, "at-most-once", 118, "I32-I34 at-most-once:");
        retrySemantics(binary, "at-least-once", 116, "I35-I37 at-least-once:");
        restartRestoresSeed(binary);
        realClientReplay(binary, "at-least-once", 116, "2 of 2 updates received", true,
                "I39-I43 at-least-once:");
        realClientReplay(binary, "at-most-once", 118, "1 of 1 updates received", false,
                "I44-I48 at-most-once:");
        System.out.println("\nServer integration: " + checks + " checks, " + failures
                + " failures");
        if (failures != 0) System.exit(1);
    }
}
