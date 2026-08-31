package sc6103.common;

/**
 * The fixed 40-byte header carried by every datagram (README sec. 3.4).
 *
 * <p>Ten {@code int32} fields at constant offsets 0, 4, ... 36, in this order:
 *
 * <pre>
 *   0  magic        0x53433631 ("SC61")
 *   4  version      1
 *   8  messageType  0=REQUEST 1=REPLY 2=CALLBACK 3=MONITOR_END
 *  12  opCode       1-6 on REQUEST/REPLY, OP_NONE on CALLBACK/MONITOR_END
 *  16  semantics    0=at-least-once 1=at-most-once
 *  20  clientId     uint32, random nonzero, fixed for the client's lifetime
 *  24  requestId    monotonic per client
 *  28  ackUpTo      history-pruning watermark, NO_ACK when unused
 *  32  status       replies and callbacks only
 *  36  payloadLen   exact body length
 * </pre>
 *
 * <p>The size is fixed and every field is 4 bytes wide (D23) so that offsets are
 * constant in both implementations. That is the main defence against the C++ and Java
 * marshallers drifting apart, since they share no code and only this specification
 * holds them together.
 *
 * <p>Instances are immutable. The public constructor is deliberately absent: a header
 * with ten independent integer fields is trivially easy to build with two of them
 * transposed, and the transposition would not be caught until it corrupted a value on
 * the wire. Use the four static factories, each of which fills the fields that its
 * message type is required to carry.
 */
public final class Header {

    /** Field count, purely for the size assertion in {@link #write(Codec)}. */
    private static final int FIELD_COUNT = 10;

    public final int magic;
    public final int version;
    public final int messageType;
    public final int opCode;
    public final int semantics;

    /** uint32 on the wire; held as a long because Java has no unsigned int. */
    public final long clientId;

    public final int requestId;
    public final int ackUpTo;
    public final int status;
    public final int payloadLen;

    private Header(int magic, int version, int messageType, int opCode, int semantics,
                   long clientId, int requestId, int ackUpTo, int status, int payloadLen) {
        this.magic = magic;
        this.version = version;
        this.messageType = messageType;
        this.opCode = opCode;
        this.semantics = semantics;
        this.clientId = clientId;
        this.requestId = requestId;
        this.ackUpTo = ackUpTo;
        this.status = status;
        this.payloadLen = payloadLen;
    }

    // ---- Factories ----

    /**
     * A client-to-server request.
     *
     * <p>{@code status} is 0. A request has no status to report, and pinning the value
     * rather than leaving it undefined stops a zero-initialised C++ struct and a Java
     * builder disagreeing on a byte that neither side reads.
     */
    public static Header request(long clientId, int requestId, int opCode,
                                 int semantics, int ackUpTo, int payloadLen) {
        return new Header(Protocol.MAGIC, Protocol.VERSION, Protocol.MSG_REQUEST,
                opCode, semantics, clientId, requestId, ackUpTo, 0, payloadLen);
    }

    /**
     * The reply to {@code request}, echoing its identity so the client can match it.
     *
     * <p>{@code clientId}, {@code requestId} and {@code opCode} are copied from the
     * request: the client discards any reply whose identity does not match the call it
     * is waiting on (README sec. 7.2), so these are what make a reply routable at all.
     *
     * <p>{@code semantics} is echoed too. The field exists so a mode mismatch is
     * rejected rather than silently served (D19); echoing it lets the client confirm
     * the server agreed, instead of only learning when the server refuses.
     *
     * <p>{@code ackUpTo} is {@link Protocol#NO_ACK}, not 0 - see {@link #read(Codec)}.
     */
    public static Header reply(Header request, int status, int payloadLen) {
        return new Header(Protocol.MAGIC, Protocol.VERSION, Protocol.MSG_REPLY,
                request.opCode, request.semantics, request.clientId, request.requestId,
                Protocol.NO_ACK, status, payloadLen);
    }

    /**
     * An unsolicited seat-availability callback.
     *
     * <p>{@code requestId} is the requestId of the MONITOR registration being served,
     * not a fresh number. A client may hold several registrations, and this is what
     * tells it which one produced the update.
     *
     * <p>{@code opCode} is {@link Protocol#OP_NONE}: a callback is not the result of an
     * operation the client invoked, so no opcode of the six describes it.
     *
     * <p>{@code status} is 0. A callback is only ever sent on a successful state
     * change, so there is no failure to report (README sec. 9.2).
     */
    public static Header callback(long clientId, int monitorRequestId,
                                  int semantics, int payloadLen) {
        return new Header(Protocol.MAGIC, Protocol.VERSION, Protocol.MSG_CALLBACK,
                Protocol.OP_NONE, semantics, clientId, monitorRequestId,
                Protocol.NO_ACK, StatusCode.OK, payloadLen);
    }

    /**
     * The advisory notice that a monitor registration has expired (D20).
     *
     * <p>Same header shape as a callback. It is advisory because it can itself be lost,
     * so the client's own deadline stays authoritative; it earns its place by carrying
     * the server's final callback count, which is what lets the client report how many
     * updates it missed rather than only the gaps between those that arrived.
     */
    public static Header monitorEnd(long clientId, int monitorRequestId,
                                    int semantics, int payloadLen) {
        return new Header(Protocol.MAGIC, Protocol.VERSION, Protocol.MSG_MONITOR_END,
                Protocol.OP_NONE, semantics, clientId, monitorRequestId,
                Protocol.NO_ACK, StatusCode.OK, payloadLen);
    }

    // ---- Wire form ----

    /** Writes the ten fields in offset order. */
    public void write(Codec out) throws CodecException {
        final int start = out.position();
        out.putInt32(magic);
        out.putInt32(version);
        out.putInt32(messageType);
        out.putInt32(opCode);
        out.putInt32(semantics);
        out.putUInt32(clientId);
        out.putInt32(requestId);
        out.putInt32(ackUpTo);
        out.putInt32(status);
        out.putInt32(payloadLen);

        // Cheap guard against a field being added here without HEADER_SIZE following.
        // The body's offsets are all relative to a 40-byte header, so a mismatch here
        // would silently shift every field after it.
        final int written = out.position() - start;
        if (written != Protocol.HEADER_SIZE) {
            throw new CodecException("header wrote " + written + " bytes, expected "
                    + Protocol.HEADER_SIZE + " (" + FIELD_COUNT + " int32 fields)");
        }
    }

    /**
     * Reads and validates a header, in the order README sec. 3.6 fixes.
     *
     * <p>The order is normative rather than stylistic: each check establishes what the
     * next one is allowed to trust. In particular {@code payloadLen} is not believed
     * until magic and version have passed, because on a datagram from an unrelated
     * sender it is not a length at all.
     *
     * <p><b>This method does not check the received datagram length.</b> Verifying
     * {@code received == HEADER_SIZE + payloadLen} needs the size the socket reported,
     * which a decoder over a buffer does not have. That check belongs to the caller,
     * immediately after this returns, and it is the one that catches truncation.
     *
     * @throws CodecException naming the field that failed, so a rejected datagram is
     *         diagnosable from the log rather than merely counted
     */
    public static Header read(Codec in) throws CodecException {
        final int magic = in.getInt32();
        if (magic != Protocol.MAGIC) {
            // Not necessarily an error: any process may send to this port. The caller
            // drops these silently rather than replying (README sec. 5).
            throw new CodecException(String.format(
                    "bad magic 0x%08X, expected 0x%08X", magic, Protocol.MAGIC));
        }

        final int version = in.getInt32();
        if (version != Protocol.VERSION) {
            // Worth far more here than in a single-language project: with two
            // independent implementations the format is the only thing holding them
            // together, and a version bump is how one side announces it changed.
            throw new CodecException("unsupported version " + version
                    + ", expected " + Protocol.VERSION);
        }

        final int messageType = in.getInt32();
        if (!Protocol.isValidMessageType(messageType)) {
            throw new CodecException("unknown messageType " + messageType);
        }

        final int opCode = in.getInt32();
        // Only a REQUEST is required to name one of the six operations. CALLBACK and
        // MONITOR_END legitimately carry OP_NONE, so applying the 1-6 test to them
        // would reject well-formed traffic.
        final boolean opCodeMustBeOperation = (messageType == Protocol.MSG_REQUEST);
        if (opCodeMustBeOperation && !Protocol.isValidOpCode(opCode)) {
            throw new CodecException("unknown opCode " + opCode + " on REQUEST");
        }

        final int semantics = in.getInt32();
        if (!Protocol.isValidSemantics(semantics)) {
            throw new CodecException("unknown semantics " + semantics);
        }

        final long clientId = in.getUInt32();
        final int requestId = in.getInt32();
        final int ackUpTo = in.getInt32();
        final int status = in.getInt32();

        final int payloadLen = in.getInt32();
        if (payloadLen < 0 || payloadLen > Protocol.MAX_BODY) {
            // Validated before the body is touched, so an absurd length can never reach
            // an allocation (README sec. 11.1).
            throw new CodecException("payloadLen " + payloadLen
                    + " outside 0.." + Protocol.MAX_BODY);
        }

        return new Header(magic, version, messageType, opCode, semantics,
                clientId, requestId, ackUpTo, status, payloadLen);
    }

    // ---- Object contract ----

    /**
     * One line for the request/reply trace the brief requires both sides to print,
     * in the field order of the log schema in README sec. 10.2 so a client log and a
     * server log can be read side by side.
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(96);
        sb.append(messageTypeName(messageType))
          .append(" client=0x").append(String.format("%08X", clientId))
          .append(" req=").append(requestId)
          .append(" op=").append(Protocol.opCodeName(opCode));
        if (messageType == Protocol.MSG_REQUEST) {
            sb.append(" ackUpTo=").append(ackUpTo);
        } else {
            sb.append(" status=").append(StatusCode.name(status));
        }
        sb.append(" len=").append(payloadLen);
        return sb.toString();
    }

    private static String messageTypeName(int t) {
        switch (t) {
            case Protocol.MSG_REQUEST:     return "REQUEST";
            case Protocol.MSG_REPLY:       return "REPLY";
            case Protocol.MSG_CALLBACK:    return "CALLBACK";
            case Protocol.MSG_MONITOR_END: return "MONITOR_END";
            default:                       return "UNKNOWN(" + t + ")";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Header)) {
            return false;
        }
        final Header h = (Header) o;
        return magic == h.magic
                && version == h.version
                && messageType == h.messageType
                && opCode == h.opCode
                && semantics == h.semantics
                && clientId == h.clientId
                && requestId == h.requestId
                && ackUpTo == h.ackUpTo
                && status == h.status
                && payloadLen == h.payloadLen;
    }

    @Override
    public int hashCode() {
        int r = magic;
        r = 31 * r + version;
        r = 31 * r + messageType;
        r = 31 * r + opCode;
        r = 31 * r + semantics;
        r = 31 * r + (int) (clientId ^ (clientId >>> 32));
        r = 31 * r + requestId;
        r = 31 * r + ackUpTo;
        r = 31 * r + status;
        r = 31 * r + payloadLen;
        return r;
    }
}
