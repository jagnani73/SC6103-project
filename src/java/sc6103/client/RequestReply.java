package sc6103.client;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import sc6103.common.Codec;
import sc6103.common.CodecException;
import sc6103.common.Header;
import sc6103.common.Protocol;
import sc6103.common.Replies;

/**
 * The client half of the request-reply protocol: README sec. 7.2.
 *
 * <p>This is the layer the brief actually asks students to write. Deck 2 calls it the
 * request-reply protocol and puts it directly above UDP; what it adds to a bare datagram
 * is a timeout, a retransmission policy, a stable request identity, and the discipline to
 * accept only the reply it asked for.
 *
 * <h2>Three properties that are load-bearing</h2>
 *
 * <ol>
 *   <li><b>The same bytes go out on every attempt.</b> The request is marshalled once,
 *       before the first send, and the identical array is retransmitted. If
 *       {@code requestId} changed between attempts the server could not recognise a
 *       duplicate and at-most-once would be unimplementable - the guarantee lives on the
 *       server, but it depends entirely on the client holding its identity still.</li>
 *   <li><b>Replies are matched on {@code (clientId, requestId)}.</b> A reply to attempt 1
 *       that arrives after attempt 2 was sent is a reply to <em>this</em> call and is
 *       accepted; a reply carrying any other pair is discarded. Without the check, a late
 *       reply to a previous call would be handed back as the answer to the current
 *       one.</li>
 *   <li><b>Exhaustion reports a timeout and claims nothing else.</b> The client cannot
 *       distinguish a lost request from a lost reply from a slow server from a dead one.
 *       Saying "failed" would assert the operation did not run, which this layer has no
 *       way to know.</li>
 * </ol>
 *
 * <p>Callbacks and MONITOR_END arrive on the same socket and may land in the middle of a
 * wait. They are handed to the {@link AsyncHandler} and the wait <em>continues</em> -
 * they are not replies and must never satisfy or shorten a pending call.
 */
public final class RequestReply {

    /** Writes one request body; the header is written separately by {@link #call}. */
    public interface BodyWriter {
        void write(Codec out) throws CodecException;
    }

    /** Receives the unsolicited traffic that shares the client's socket. */
    public interface AsyncHandler {
        void onCallback(Header header, Replies.Callback body);

        void onMonitorEnd(Header header, Replies.MonitorEnd body);
    }

    /**
     * The result of one call: either a reply, or an honest admission of ignorance.
     *
     * <p>There is no third case and no error field. A protocol-level failure arrives as a
     * reply carrying a non-OK {@code status}; the absence of any reply is
     * {@link #timedOut}.
     */
    public static final class Outcome {

        public final boolean timedOut;
        public final Header header;
        public final Codec body;
        public final int attempts;

        private Outcome(boolean timedOut, Header header, Codec body, int attempts) {
            this.timedOut = timedOut;
            this.header = header;
            this.body = body;
            this.attempts = attempts;
        }
    }

    private final UdpChannel channel;
    private final ClientConfig config;
    private final TraceLog trace;
    private final LossSimulator loss;

    private final long clientId;
    private final byte[] txBuf = new byte[Protocol.MAX_DATAGRAM];

    /**
     * Next request identifier. Monotonic and never reused within a process, which is what
     * makes {@code (clientId, requestId)} an identity rather than a hint.
     *
     * <p>Starts at 0 because README sec. 3.4 admits 0 as a legal {@code requestId} - it is
     * {@code ackUpTo} that needs the -1 sentinel, precisely because 0 is a real value
     * here.
     */
    private int nextRequestId;

    /**
     * The history-pruning watermark of README sec. 8.3: the highest {@code requestId} this
     * client holds a reply for, or {@link Protocol#NO_ACK}.
     *
     * <p><b>Invariant: this is always strictly less than the {@code requestId} of the
     * request carrying it.</b> It is raised only after a reply arrives, by which point
     * {@link #nextRequestId} has already moved past it. The server prunes on receipt,
     * before its duplicate lookup, so a watermark that reached its own request's id would
     * delete the entry that request's retransmission needs - degrading at-most-once to
     * at-least-once silently, under exactly the loss conditions the mechanism exists to
     * handle. README sec. 8.3 states the constraint and sec. 6.1 has the server reject a
     * violation rather than trust us.
     */
    private int ackUpTo = Protocol.NO_ACK;

    private AsyncHandler asyncHandler;
    private int requestsSent;
    private int timeouts;

    public RequestReply(UdpChannel channel, ClientConfig config, TraceLog trace,
                        LossSimulator loss, long clientId) {
        this.channel = channel;
        this.config = config;
        this.trace = trace;
        this.loss = loss;
        this.clientId = clientId;
    }

    /**
     * A random nonzero {@code uint32} identifier, generated once per process (README
     * sec. 2.3, D5).
     *
     * <p>Identity is deliberately not taken from the socket's address and port, though
     * deck 2 offers that as an example. Two failure modes motivate the split, and both
     * corrupt at-most-once <em>silently</em>: a restarted client landing on the same
     * ephemeral port could be answered from the previous process's history, and a NAT
     * rebinding between a request and its retransmission would make the server treat the
     * retry as a new call and execute twice.
     *
     * <p>Nonzero because 0 is reserved as "unset", so a zero-filled header is
     * recognisably uninitialised rather than a valid client.
     */
    public static long newClientId() {
        final SecureRandom rng = new SecureRandom();
        long id;
        do {
            id = rng.nextInt() & 0xFFFFFFFFL;
        } while (id == 0L);
        return id;
    }

    public void setAsyncHandler(AsyncHandler handler) {
        this.asyncHandler = handler;
    }

    public long clientId() {
        return clientId;
    }

    public int requestsSent() {
        return requestsSent;
    }

    public int timeouts() {
        return timeouts;
    }

    /**
     * Sends one request and waits for its reply, retransmitting on timeout.
     *
     * @param opCode one of the six operations of README sec. 4
     * @param body   writes the operation's request body; may write nothing
     * @return the reply, or an {@link Outcome} marked {@link Outcome#timedOut}
     */
    public Outcome call(int opCode, BodyWriter body) throws IOException {
        final int requestId = nextRequestId++;
        final int length = marshal(requestId, opCode, body);

        // Asserted rather than assumed. The invariant is cheap to check here and its
        // violation is invisible at the point of failure - the symptom appears later, on
        // the server, as an at-most-once guarantee that quietly stopped holding.
        if (ackUpTo >= requestId) {
            throw new IllegalStateException("ackUpTo " + ackUpTo
                    + " must be below requestId " + requestId + " (README sec. 8.3)");
        }

        for (int attempt = 1; attempt <= config.maxRetries + 1; attempt++) {
            if (attempt > 1) {
                trace.retry(requestId, opCode, attempt);
            }

            if (loss.shouldDropRequest()) {
                // Suppressed after encoding, before the send, so the codec ran on exactly
                // the path it would have on a transmitted datagram (README sec. 10).
                trace.drop(requestId, opCode, attempt, "request", loss.lastReason());
            } else {
                trace.send(requestId, opCode, attempt, "transmitted");
                channel.send(txBuf, length);
                requestsSent++;
            }

            final long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(config.timeoutMs);

            final Outcome reply = awaitReply(requestId, opCode, attempt, deadline);
            if (reply != null) {
                return reply;
            }

            trace.timeout(requestId, opCode, attempt, config.timeoutMs);
        }

        timeouts++;
        return new Outcome(true, null, null, config.maxRetries + 1);
    }

    /**
     * Waits until {@code deadline} for the reply to {@code requestId}, dispatching any
     * unsolicited traffic that arrives meanwhile.
     *
     * @return the matching reply, or {@code null} if the deadline passed
     */
    private Outcome awaitReply(int requestId, int opCode, int attempt, long deadline)
            throws IOException {
        while (true) {
            final UdpChannel.Inbound in = channel.receive(deadline);
            if (in == null) {
                return null;
            }

            // Unsolicited traffic shares this socket. Handle it and keep waiting: a
            // callback is not an answer, and letting one end the wait would abandon a
            // request that is still perfectly likely to be answered.
            if (in.header.messageType == Protocol.MSG_CALLBACK
                    || in.header.messageType == Protocol.MSG_MONITOR_END) {
                dispatchAsync(in);
                continue;
            }

            if (in.header.clientId != clientId) {
                // Someone else's reply, or a reflection. Never match on address alone -
                // the identity that matters is in the header.
                trace.ignore("foreign", String.format("clientId=0x%08X", in.header.clientId));
                continue;
            }

            if (in.header.requestId != requestId) {
                // A late reply to a call this client has already given up on. Accepting it
                // would answer the current question with a previous answer.
                trace.ignore("stale", "req=" + in.header.requestId
                        + " while awaiting req=" + requestId);
                continue;
            }

            if (loss.shouldDropReply()) {
                // Client-side reply loss, applied after receipt. The operation genuinely
                // ran on the server; this models the return path vanishing.
                trace.drop(requestId, opCode, attempt, "reply", loss.lastReason());
                continue;
            }

            // README sec. 3.4 requires the server to echo the request's semantics
            // unchanged. Checking it is the whole reason the field is echoed rather than
            // zeroed: without this the client learns about a mode disagreement only when
            // the server bothers to refuse, and a server that serves a mismatched client
            // anyway would produce experimental results that look valid and mean nothing
            // (D19). A mismatch here means the peer is broken or is not running this
            // protocol version's rules, so it is reported and never silently accepted.
            if (in.header.semantics != config.semantics) {
                trace.warn("server echoed semantics " + in.header.semantics
                        + " on req=" + requestId + ", this client sent " + config.semantics
                        + " - results from this session are not trustworthy");
            }

            // Raise the watermark only now, and only to this request's own id, keeping the
            // strict inequality the next call asserts.
            if (requestId > ackUpTo) {
                ackUpTo = requestId;
            }

            trace.recv(requestId, opCode, attempt, in.header.status, decisionFor(attempt));
            return new Outcome(false, in.header, in.body, attempt);
        }
    }

    /**
     * Pumps the socket until {@code deadline}, dispatching unsolicited traffic to the
     * {@link AsyncHandler}, with no request outstanding. This is monitor mode
     * (README sec. 7.3).
     *
     * <p>Returns early when {@code stop} becomes true - a MONITOR_END arriving before the
     * interval elapses - but the deadline is what guarantees termination. MONITOR_END is
     * advisory and can itself be lost (D20), so a client that waited only for {@code stop}
     * would hang whenever the network dropped one message.
     *
     * <p>Filtering lives here rather than in the caller so that {@code clientId} matching
     * and the discard rules are written once and apply identically whether or not a
     * request is in flight.
     *
     * @return true if {@code stop} ended the wait, false if the deadline did
     */
    public boolean pumpUntil(long deadline, java.util.function.BooleanSupplier stop)
            throws IOException {
        while (!stop.getAsBoolean()) {
            final UdpChannel.Inbound in = channel.receive(deadline);
            if (in == null) {
                return false;
            }

            if (in.header.clientId != clientId) {
                trace.ignore("foreign", String.format("clientId=0x%08X", in.header.clientId));
                continue;
            }

            if (in.header.messageType == Protocol.MSG_CALLBACK
                    || in.header.messageType == Protocol.MSG_MONITOR_END) {
                dispatchAsync(in);
                continue;
            }

            // A REPLY reaching here is the answer to a call this client already timed out
            // on. It is genuinely stale - nothing is waiting for it.
            trace.ignore("stale", "reply req=" + in.header.requestId + " during monitor");
        }
        return true;
    }

    /**
     * Labels how a reply most likely came about, for the trace line.
     *
     * <p>Explicitly an <em>inference</em>, not knowledge. A reply arriving on a
     * retransmission was probably served from the server's reply history under
     * at-most-once, and probably produced by a second execution under at-least-once - but
     * it could equally be the first attempt's reply arriving late, and nothing on the wire
     * distinguishes those. The label is marked inferred so a demo trace is suggestive
     * without the client asserting something it cannot know (README sec. 7.2).
     */
    private String decisionFor(int attempt) {
        if (attempt == 1) {
            return "first-attempt";
        }
        return config.semantics == Protocol.SEM_AT_MOST_ONCE
                ? "cache-replay?inferred"
                : "re-executed?inferred";
    }

    private void dispatchAsync(UdpChannel.Inbound in) {
        if (asyncHandler == null) {
            // Arrives outside monitor mode: a callback from a registration whose interval
            // has not expired on the server, or a MONITOR_END for one that just did.
            // Neither is an error and neither has anywhere to go.
            trace.ignore("no-monitor-active",
                    "messageType=" + in.header.messageType + " req=" + in.header.requestId);
            return;
        }
        try {
            if (in.header.messageType == Protocol.MSG_CALLBACK) {
                asyncHandler.onCallback(in.header, Replies.Callback.read(in.body));
            } else {
                asyncHandler.onMonitorEnd(in.header, Replies.MonitorEnd.read(in.body));
            }
        } catch (CodecException e) {
            // A malformed callback is dropped, never fatal. Callbacks are fire-and-forget
            // (D10), so losing one to a decode fault is the same class of event as losing
            // one to the network, and the client already reports that through callbackSeq
            // gaps.
            trace.ignore("bad-callback-body", e.getMessage());
        }
    }

    /**
     * Writes the header and body into {@link #txBuf} exactly once per call.
     *
     * <p>The body is encoded first, at the offset it will occupy, so its length is known
     * before the header that declares {@code payloadLen} is written in front of it. This
     * is why the buffer is written out of order rather than streamed.
     *
     * @return the total datagram length
     */
    private int marshal(int requestId, int opCode, BodyWriter body) {
        try {
            final Codec bodyCursor = Codec.writer(new byte[Protocol.MAX_BODY]);
            body.write(bodyCursor);
            final int payloadLen = bodyCursor.position();

            final Codec out = Codec.writer(txBuf);
            Header.request(clientId, requestId, opCode, config.semantics, ackUpTo, payloadLen)
                    .write(out);
            System.arraycopy(bodyCursor.array(), 0, txBuf, Protocol.HEADER_SIZE, payloadLen);
            return Protocol.HEADER_SIZE + payloadLen;
        } catch (CodecException e) {
            // Our own values overflowing our own buffer is a client bug, not a wire fault,
            // and there is no sensible datagram to send instead.
            throw new IllegalStateException("failed to marshal op "
                    + Protocol.opCodeName(opCode) + " req=" + requestId, e);
        }
    }
}
