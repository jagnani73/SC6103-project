package sc6103.harness;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import sc6103.common.Codec;
import sc6103.common.CodecException;
import sc6103.common.Header;
import sc6103.common.Protocol;
import sc6103.common.StatusCode;

/**
 * A scripted UDP peer for testing the client, and <b>not a server</b>.
 *
 * <p>The distinction matters and is not pedantry. This class holds no flight table, runs
 * no operation, and decides nothing: a test hands it a list of things to do in order, and
 * it does them. It exists because the client's request-reply layer - the retry loop, reply
 * matching, stale and foreign discard, callback demultiplexing - is the graded core of
 * README sec. 7.2 and had no automated coverage above the codec. The 222 conformance
 * assertions check marshalling; nothing checked behaviour.
 *
 * <p>It lives under {@code tests/java} deliberately. README sec. 22.1 settles on exactly
 * one server, written in C++17, and nothing in this tree is a candidate for that role or
 * for submission as it.
 *
 * <h2>Why it speaks real UDP instead of stubbing an interface</h2>
 *
 * The obvious alternative is to extract a transport interface from
 * {@code UdpChannel} and inject a fake. That would test more quickly and test less: the
 * faults worth catching in that class are the reused receive buffer, the
 * {@code received == HEADER_SIZE + payloadLen} agreement check, and the absolute-deadline
 * arithmetic, and all three live in the code an interface would replace. So the client
 * under test opens a real socket to a real peer on loopback, and the only thing faked is
 * what the peer decides to say.
 *
 * <h2>Threading</h2>
 *
 * One background thread runs the script while the test drives the client on the main
 * thread. This is test code; the brief's permission to avoid threads in the client
 * (D11) has nothing to say about a fixture. Nothing here is shared with the client except
 * datagrams.
 */
public final class ScriptedPeer implements AutoCloseable {

    /** One scripted response to one received request. */
    public interface Step {
        /**
         * @param request the header just received
         * @param body    a cursor over its body, positioned after the header
         * @param peer    this peer, for the {@code send*} helpers
         */
        void act(Header request, Codec body, ScriptedPeer peer) throws Exception;
    }

    private final DatagramSocket socket;
    private final BlockingQueue<Step> script;
    private final List<Header> received = new ArrayList<>();
    private final List<byte[]> receivedRaw = new ArrayList<>();
    private volatile InetSocketAddress client;
    private volatile Thread worker;
    private volatile boolean running = true;
    private volatile Exception failure;

    public ScriptedPeer(int maxSteps) throws IOException {
        this.socket = new DatagramSocket();
        this.script = new ArrayBlockingQueue<>(maxSteps);
        this.socket.setSoTimeout(50);
    }

    public int port() {
        return socket.getLocalPort();
    }

    public InetAddress host() throws IOException {
        return InetAddress.getLoopbackAddress();
    }

    /** Queues one step. Steps are consumed in order, one per received request. */
    public ScriptedPeer then(Step step) {
        script.add(step);
        return this;
    }

    /** Starts consuming the script. */
    public ScriptedPeer start() {
        worker = new Thread(this::loop, "scripted-peer");
        worker.setDaemon(true);
        worker.start();
        return this;
    }

    private void loop() {
        final byte[] buf = new byte[Protocol.MAX_DATAGRAM];
        while (running) {
            final DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                return;
            }

            client = new InetSocketAddress(packet.getAddress(), packet.getPort());

            final byte[] raw = new byte[packet.getLength()];
            System.arraycopy(buf, 0, raw, 0, packet.getLength());

            try {
                final Codec in = Codec.reader(raw, raw.length);
                final Header hdr = Header.read(in);
                synchronized (this) {
                    received.add(hdr);
                    receivedRaw.add(raw);
                }
                final Step step = script.poll();
                if (step != null) {
                    step.act(hdr, in, this);
                }
                // A null step means the script is exhausted: stay silent and let the
                // client time out. That is itself a scripted outcome, not a fixture bug.
            } catch (Exception e) {
                failure = e;
            }
        }
    }

    // ---- Sending ----

    /** Sends a REPLY echoing the request's identity, with a body written by {@code body}. */
    public void reply(Header request, int status, RequestReplyBody body) throws Exception {
        final byte[] out = new byte[Protocol.MAX_DATAGRAM];
        final Codec bodyCursor = Codec.writer(new byte[Protocol.MAX_BODY]);
        if (body != null) {
            body.write(bodyCursor);
        }
        final int len = bodyCursor.position();
        final Codec c = Codec.writer(out);
        Header.reply(request, status, len).write(c);
        System.arraycopy(bodyCursor.array(), 0, out, Protocol.HEADER_SIZE, len);
        sendRaw(out, Protocol.HEADER_SIZE + len);
    }

    public void replyStatus(Header request, int status) throws Exception {
        reply(request, status, null);
    }

    /** Sends a CALLBACK for the given monitor registration. */
    public void callback(long clientId, int monitorRequestId, int semantics,
                         int flightId, int seats, int eventRequestId, int seq)
            throws Exception {
        sendUnsolicited(Header.callback(clientId, monitorRequestId, semantics, 16),
                c -> {
                    c.putInt32(flightId);
                    c.putInt32(seats);
                    c.putInt32(eventRequestId);
                    c.putInt32(seq);
                });
    }

    /** Sends MONITOR_END carrying the total callbacks the peer claims to have sent. */
    public void monitorEnd(long clientId, int monitorRequestId, int semantics,
                           int flightId, int totalSeq) throws Exception {
        sendUnsolicited(Header.monitorEnd(clientId, monitorRequestId, semantics, 8),
                c -> {
                    c.putInt32(flightId);
                    c.putInt32(totalSeq);
                });
    }

    private void sendUnsolicited(Header header, RequestReplyBody body) throws Exception {
        final byte[] out = new byte[Protocol.MAX_DATAGRAM];
        final Codec bodyCursor = Codec.writer(new byte[Protocol.MAX_BODY]);
        body.write(bodyCursor);
        final Codec c = Codec.writer(out);
        header.write(c);
        System.arraycopy(bodyCursor.array(), 0, out, Protocol.HEADER_SIZE,
                bodyCursor.position());
        sendRaw(out, Protocol.HEADER_SIZE + bodyCursor.position());
    }

    /**
     * Sends bytes verbatim, with no header construction.
     *
     * <p>This is how the malformed cases are scripted - a bad magic, a {@code payloadLen}
     * that disagrees with the datagram length, a truncated body. None of those can be
     * built through {@link Header}, which is the point of having built it that way.
     */
    public void sendRaw(byte[] bytes, int length) throws IOException {
        final InetSocketAddress to = client;
        if (to == null) {
            throw new IllegalStateException("no client seen yet");
        }
        socket.send(new DatagramPacket(bytes, length, to.getAddress(), to.getPort()));
    }

    /** Body writer for the helpers above. */
    public interface RequestReplyBody {
        void write(Codec out) throws CodecException;
    }

    // ---- Observation ----

    /** Headers of every request received, in arrival order, retransmissions included. */
    public synchronized List<Header> received() {
        return new ArrayList<>(received);
    }

    /** Raw bytes of every request received, for asserting that a retry is byte-identical. */
    public synchronized List<byte[]> receivedRaw() {
        return new ArrayList<>(receivedRaw);
    }

    /** Any exception thrown while running the script, or null. */
    public Exception failure() {
        return failure;
    }

    /**
     * Blocks until at least {@code n} requests have arrived, or the timeout passes.
     *
     * @return true if the count was reached
     */
    public boolean awaitRequests(int n, long timeoutMs) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            synchronized (this) {
                if (received.size() >= n) {
                    return true;
                }
            }
            Thread.sleep(5);
        }
        synchronized (this) {
            return received.size() >= n;
        }
    }

    /** A step that ignores the request entirely, modelling a lost request or reply. */
    public static Step silence() {
        return (request, body, peer) -> { };
    }

    /** A step that replies OK with no body. */
    public static Step ok() {
        return (request, body, peer) -> peer.replyStatus(request, StatusCode.OK);
    }

    @Override
    public void close() {
        running = false;
        socket.close();
        final Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }
}
