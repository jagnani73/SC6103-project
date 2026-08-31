package sc6103.common;

/**
 * Wire-protocol constants for the SC6103 flight information system.
 *
 * <p>Every value here is visible on the wire and is therefore part of the contract
 * with the C++ server, which carries its own independent copy of these numbers
 * written against README.md Part I rather than against this file (README &sect;16).
 * Changing a value here without changing it there silently breaks the demo, so
 * treat these as frozen once the conformance vector (README &sect;11.2) is built.
 *
 * <p>Constants only &mdash; no encoding logic. The header codec and the marshaller
 * live elsewhere so that this file can be read start to finish while checking a
 * hex dump by hand.
 */
public final class Protocol {

    // ---- Header guards (README sec. 3.4, D19) ----

    /**
     * ASCII {@code "SC61"}. Rejects unrelated traffic that happens to arrive on the
     * UDP port; a datagram failing this check is dropped with no reply, because it
     * may not be from a client of this system at all (README &sect;5).
     *
     * <p>The pattern is under 0x80000000, so it stays positive as a Java {@code int}
     * and a decoded {@code uint32} can be compared to it directly. Equality is a
     * bit-pattern test either way, so Java's lack of unsigned types is harmless here
     * &mdash; unlike in the narrow-field design D23 rejected.
     */
    public static final int MAGIC = 0x53433631;

    /**
     * Bumped whenever the wire layout changes. With two implementations sharing no
     * code, this field is how one side announces it has drifted; without it a
     * mismatch surfaces as corrupted values instead of {@code ERR_VERSION_MISMATCH}
     * (D19).
     */
    public static final int VERSION = 1;

    // ---- messageType, header offset 8 (README sec. 3.4) ----

    /** Client to server, carries an {@code opCode} from the table below. */
    public static final int MSG_REQUEST = 0;

    /** Server to client, echoes {@code requestId} and carries a {@link StatusCode}. */
    public static final int MSG_REPLY = 1;

    /**
     * Unsolicited seat update to a monitoring client. Never acknowledged and never
     * retried, so it must not be routed through the request/reply state machine
     * (README &sect;9.2, D10).
     */
    public static final int MSG_CALLBACK = 2;

    /**
     * Sent once when a monitor registration expires. Advisory only: it may itself be
     * lost, so the client's own deadline stays authoritative (D20).
     */
    public static final int MSG_MONITOR_END = 3;

    // ---- semantics, header offset 16 (README sec. 8, D19) ----

    /**
     * Retransmit and re-execute. Duplicates reach the operation, which is the
     * corruption the &sect;13 experiments are designed to measure.
     */
    public static final int SEM_AT_LEAST_ONCE = 0;

    /**
     * Retransmit, filter duplicates against the reply history, resend the stored
     * bytes. Carried in the header so the server can refuse a client running in the
     * opposite mode: a silent mismatch yields experimental results that look valid
     * and mean nothing (D19).
     */
    public static final int SEM_AT_MOST_ONCE = 1;

    // ---- opCode, header offset 12 (README sec. 4) ----

    /** Match on source/destination; returns flight IDs in ascending order. */
    public static final int OP_QUERY_BY_ROUTE = 1;

    /** Departure time, airfare and seat availability for one flight ID. */
    public static final int OP_QUERY_BY_ID = 2;

    /** Relative seat decrement. Non-idempotent by construction. */
    public static final int OP_RESERVE = 3;

    /** Register for callbacks for a bounded interval; re-registering replaces. */
    public static final int OP_MONITOR = 4;

    /**
     * The brief's required non-idempotent extra operation.
     *
     * <p>The <em>numbering is fixed</em> &mdash; 5 is on the wire and in the
     * conformance vector &mdash; but <em>which operation it is remains an open group
     * decision</em> (README &sect;22.2, docs/decisions-to-close.md &sect;2). Under the assumed
     * seat pair it is {@code CANCEL_SEATS}; under the fare alternative it is
     * {@code ADJUST_FARE}. The name stays neutral so that nothing hard-codes the
     * wrong operation before the group closes the fork; rename it in one place once
     * they do.
     */
    public static final int OP_EXTRA_NON_IDEMPOTENT = 5;

    /**
     * The brief's required idempotent extra operation. Absolute assignment, so a
     * replay computes a zero delta and leaves state identical.
     *
     * <p>Same caveat as opcode 5: the number is settled, the meaning is not
     * ({@code SET_RESERVATION} versus {@code SET_FARE}, README &sect;22.2).
     */
    public static final int OP_EXTRA_IDEMPOTENT = 6;

    /**
     * Placeholder written into the {@code opCode} field of CALLBACK and MONITOR_END,
     * which are not invocations of anything and so have no operation to name
     * (README &sect;3.4). Deliberately outside the 1&ndash;6 dispatch range &mdash; see
     * {@link #isValidOpCode(int)}.
     */
    public static final int OP_NONE = 0;

    // ---- Size limits (README sec. 3.5) ----

    /**
     * One operation is one datagram in each direction: no fragmentation and no
     * continuation messages (D14). 512 bytes is fragmentation-free on any realistic
     * path, so a lost reply is a lost reply rather than a lost fragment &mdash;
     * which keeps the &sect;13 loss measurements interpretable.
     */
    public static final int MAX_DATAGRAM = 512;

    /**
     * Ten fields of four bytes each (README &sect;3.4). Fixed size means constant offsets,
     * which is the main defence against the two implementations drifting apart on
     * layout (D4, D23).
     */
    public static final int HEADER_SIZE = 40;

    /**
     * Derived, not independently chosen: 512 &minus; 40 = 472. Written as the
     * subtraction so the two cannot fall out of step, and it stays a compile-time
     * constant. This is the upper bound checked against the header's
     * {@code payloadLen}.
     */
    public static final int MAX_BODY = MAX_DATAGRAM - HEADER_SIZE;

    /**
     * Cap on the UTF-8 <em>byte</em> length of a place name, not its character count
     * (D15). Bounds every buffer a decoder allocates from an untrusted length field,
     * and 63 keeps a string at most 4 + 63 + 1 padding = 68 bytes once padded to the
     * 4-byte boundary (README &sect;3.3).
     */
    public static final int MAX_STRING_BYTES = 63;

    /**
     * Route queries are truncated past this many matches, with {@code truncated = 1}
     * in the reply, rather than being split across datagrams (D14). The worst case is
     * 4 (truncated) + 4 (count) + 64 &times; 4 (IDs) = 264 bytes, comfortably inside
     * {@link #MAX_BODY}.
     */
    public static final int MAX_ROUTE_RESULTS = 64;

    // ---- Reply-history watermark (README sec. 8.3, D8) ----

    /**
     * Value for the header's {@code ackUpTo} when the client holds no reply yet, so
     * the server prunes nothing for it. A sentinel is needed because
     * {@code ackUpTo = 0} is a legal watermark meaning "I hold the reply for request
     * 0"; &minus;1 sits below every valid {@code requestId} and cannot collide.
     *
     * <p>Also the required filler on REPLY, CALLBACK and MONITOR_END, where the field
     * is unused: the conformance vector pins it to {@code FF FF FF FF} rather than
     * zero, for the same non-collision reason. Zero-filling it would encode a real
     * watermark on a message that never carries one.
     */
    public static final int NO_ACK = -1;

    /** Not instantiable: this is a constant holder, not a type. */
    private Protocol() {
    }

    /**
     * True for the six dispatchable operations.
     *
     * <p>{@link #OP_NONE} is excluded on purpose. README &sect;5 defines
     * {@code ERR_UNKNOWN_OPCODE} as "opcode not in 1&ndash;6", and this check gates
     * step 5 of the decoder order (README &sect;3.6) where an opcode selects a body
     * parser. CALLBACK and MONITOR_END carry {@code OP_NONE} and never reach that
     * step, so accepting 0 here would only let a malformed REQUEST through.
     */
    public static boolean isValidOpCode(int opCode) {
        return opCode >= OP_QUERY_BY_ROUTE && opCode <= OP_EXTRA_IDEMPOTENT;
    }

    /**
     * True for the four defined message types. Checked at decoder step 4, before
     * {@code payloadLen} is trusted, because the type decides which fields of the
     * header are meaningful at all (README &sect;3.6).
     */
    public static boolean isValidMessageType(int messageType) {
        return messageType >= MSG_REQUEST && messageType <= MSG_MONITOR_END;
    }

    /** True for the two invocation modes; anything else is a corrupt or foreign header. */
    public static boolean isValidSemantics(int semantics) {
        return semantics == SEM_AT_LEAST_ONCE || semantics == SEM_AT_MOST_ONCE;
    }

    /**
     * Constant name for an opcode, for the request/reply logging both sides are
     * required to produce.
     *
     * <p>An unrecognised value is rendered rather than rejected, because the log line
     * for a bad datagram is exactly where its opcode needs to be readable.
     *
     * @return the constant's name, or {@code "UNKNOWN(n)"} for any other value
     */
    public static String opCodeName(int opCode) {
        switch (opCode) {
            case OP_NONE:
                return "OP_NONE";
            case OP_QUERY_BY_ROUTE:
                return "OP_QUERY_BY_ROUTE";
            case OP_QUERY_BY_ID:
                return "OP_QUERY_BY_ID";
            case OP_RESERVE:
                return "OP_RESERVE";
            case OP_MONITOR:
                return "OP_MONITOR";
            case OP_EXTRA_NON_IDEMPOTENT:
                return "OP_EXTRA_NON_IDEMPOTENT";
            case OP_EXTRA_IDEMPOTENT:
                return "OP_EXTRA_IDEMPOTENT";
            default:
                return "UNKNOWN(" + opCode + ")";
        }
    }
}
