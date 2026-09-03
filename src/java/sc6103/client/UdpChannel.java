package sc6103.client;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import sc6103.common.Codec;
import sc6103.common.CodecException;
import sc6103.common.Header;
import sc6103.common.Protocol;

/**
 * The client's single UDP socket, and the only place in the client that touches
 * {@link DatagramSocket}.
 *
 * <p><b>One socket carries both replies and callbacks</b>, and that is forced rather
 * than convenient. The server learns a client's address only from the source address of
 * a datagram it received (README sec. 2.3), and it addresses callbacks there. A client
 * that opened a second socket to listen for callbacks would have no way to tell the
 * server about it except by putting the port in a payload - which sec. 2.3 refuses,
 * because a payload-supplied endpoint lets a client aim callbacks at a third party.
 *
 * <p>The socket is deliberately <b>not</b> {@code connect()}ed. A connected UDP socket
 * would surface ICMP port-unreachable as an immediate error when the server is down,
 * which sounds like an improvement and is not: README sec. 7.2 requires the client to
 * report a plain timeout without claiming whether the server executed, and an instant
 * hard failure is a materially stronger signal than the failure model of this project
 * admits. Losing it is the point.
 *
 * <h2>What this class does and does not decide</h2>
 *
 * It validates that an inbound datagram is a well-formed message of this protocol -
 * magic, version, message type, and the length agreement of README sec. 3.6 step 4 - and
 * silently drops anything that is not, because a UDP port receives whatever is addressed
 * to it and a stray datagram is not an error worth interrupting the caller for.
 *
 * <p>It does <b>not</b> decide whether a well-formed message is the one the caller is
 * waiting for. Matching {@code (clientId, requestId)}, routing callbacks, and applying
 * the retry policy all belong to {@link RequestReply}, which is the layer that knows what
 * it asked for.
 */
public final class UdpChannel implements AutoCloseable {

    /**
     * A validated inbound datagram: its header, and a cursor over its body.
     *
     * <p>The cursor is positioned at offset {@link Protocol#HEADER_SIZE} and bounded by
     * the number of bytes actually received, so a body decoder cannot read past the end
     * of this datagram into whatever the previous one left in the buffer.
     */
    public static final class Inbound {

        public final Header header;
        public final Codec body;
        public final InetSocketAddress from;

        private Inbound(Header header, Codec body, InetSocketAddress from) {
            this.header = header;
            this.body = body;
            this.from = from;
        }
    }

    private final DatagramSocket socket;
    private final InetAddress serverHost;
    private final int serverPort;
    private final TraceLog trace;

    /**
     * Receive buffer, reused across datagrams so the receive path allocates nothing.
     *
     * <p>Reuse is exactly why {@link Codec#reader(byte[], int)} must be handed the
     * datagram's own length rather than this array's capacity - see {@link #receive}.
     */
    private final byte[] rxBuf = new byte[Protocol.MAX_DATAGRAM];

    /**
     * Binds an ephemeral local port and records where the server is.
     *
     * <p>Port 0 asks the OS to assign; the assigned port is what the server observes and
     * sends callbacks to. It stays fixed for the socket's lifetime, so the common claim
     * that "the OS may move a client's ephemeral port mid-session" is not a hazard here -
     * README D5 says so explicitly and names client restart and NAT rebinding as the real
     * reasons identity moved off the transport.
     */
    public UdpChannel(InetAddress serverHost, int serverPort, TraceLog trace)
            throws SocketException {
        this.socket = new DatagramSocket();
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.trace = trace;
    }

    /** The bound local endpoint, for the startup banner (README sec. 15.2). */
    public InetSocketAddress localEndpoint() {
        return new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort());
    }

    /**
     * Sends {@code length} bytes of {@code bytes} to the server.
     *
     * <p>Loss simulation does not happen here. README sec. 10 places the gate after
     * encoding and before the send, so the caller decides not to call this method at all
     * - which keeps the codec on an identical path whether or not a datagram is
     * transmitted.
     */
    public void send(byte[] bytes, int length) throws IOException {
        socket.send(new DatagramPacket(bytes, length, serverHost, serverPort));
    }

    /**
     * Waits for the next well-formed datagram, or until {@code deadlineNanos} passes.
     *
     * <p>The deadline is an absolute {@link System#nanoTime()} value, not a duration, and
     * that matters for correctness rather than convenience: this method loops when it
     * discards a malformed datagram, and a duration would silently restart the clock on
     * every discard. A peer sending junk could then hold the client past its own timeout
     * indefinitely. An absolute deadline cannot be extended by anything that arrives.
     *
     * <p>{@code nanoTime} is monotonic. README sec. 21 forbids any protocol decision
     * depending on wall-clock time, which can jump backwards over an NTP correction and
     * would turn a 750 ms timeout into a hang.
     *
     * @return the next valid datagram, or {@code null} if the deadline passed first
     */
    public Inbound receive(long deadlineNanos) throws IOException {
        while (true) {
            final long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return null;
            }

            // Round the remaining time UP to whole milliseconds, because setSoTimeout(0)
            // means "block forever" and a sub-millisecond remainder would otherwise
            // truncate to 0 and hang the client at the exact moment it should give up.
            final int remainingMs = (int) Math.min(
                    Integer.MAX_VALUE, (remainingNanos + 999_999L) / 1_000_000L);
            socket.setSoTimeout(remainingMs);

            final DatagramPacket packet = new DatagramPacket(rxBuf, rxBuf.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                return null;
            }

            final Inbound in = validate(packet);
            if (in != null) {
                return in;
            }
            // Discarded. Loop, still bounded by the original deadline.
        }
    }

    /**
     * Applies the decoder validation order of README sec. 3.6 to one received packet.
     *
     * @return the datagram, or {@code null} if it must be discarded
     */
    private Inbound validate(DatagramPacket packet) {
        final int received = packet.getLength();

        // Step 1. Anything shorter than a header is not a message of this protocol.
        if (received < Protocol.HEADER_SIZE) {
            trace.ignore("runt", received + " bytes, minimum is " + Protocol.HEADER_SIZE);
            return null;
        }

        // The reader is bounded by what the socket reported, NEVER by rxBuf.length. The
        // buffer is reused, so bytes past `received` are whatever the previous datagram
        // left there; a decoder bounded by capacity would happily read them and decode
        // them as though they had arrived. No conformance vector can catch that, because
        // the bytes on the wire are correct - the fault is entirely in the reader's
        // bound.
        final Codec in = Codec.reader(rxBuf, received);

        final Header header;
        try {
            // Steps 2-4 minus the length check: magic, version, messageType, opCode,
            // semantics and the payloadLen range are all verified inside Header.read.
            header = Header.read(in);
        } catch (CodecException e) {
            // A bad magic lands here too. README sec. 5 drops those silently with no
            // reply, since the sender may not be a peer of this system at all - and a
            // client has nobody to reply to in any case.
            trace.ignore("malformed-header", e.getMessage());
            return null;
        }

        // Step 4, the part Header.read cannot do: it decodes a buffer and does not know
        // how many bytes the socket actually delivered. Comparing the two is what catches
        // truncation - including the silent kind, where a datagram larger than rxBuf is
        // cut to fit by the OS with no error raised at all.
        final int expected = Protocol.HEADER_SIZE + header.payloadLen;
        if (received != expected) {
            trace.ignore("length-mismatch",
                    "received " + received + ", header declares " + expected);
            return null;
        }

        // A client is never sent a REQUEST. Anything claiming to be one is either a
        // misconfigured peer or a reflection, and executing on it is not this program's
        // job.
        if (header.messageType == Protocol.MSG_REQUEST) {
            trace.ignore("unexpected-request",
                    "client received messageType REQUEST from " + packet.getSocketAddress());
            return null;
        }

        return new Inbound(header, in,
                new InetSocketAddress(packet.getAddress(), packet.getPort()));
    }

    @Override
    public void close() {
        socket.close();
    }
}
