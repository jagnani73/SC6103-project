package sc6103.common;

/**
 * Status codes carried in the header's {@code status} field (README &sect;5).
 *
 * <p>Error replies are a bare 40-byte header with no body: the server sends a number
 * and the client generates the wording. That keeps the duplicate-replay path a
 * fixed-size buffer copy and lets the client word its own messages better than a
 * context-free server string could (D4). The consequence for this file is that every
 * code needs a client-side name and a client-side handling rule, which is what
 * {@link #name(int)} and {@link #isProtocolFault(int)} supply.
 *
 * <p>These numbers are on the wire and are duplicated independently in the C++
 * server. Never renumber; append instead.
 */
public final class StatusCode {

    /** Success. The reply carries the operation's body. */
    public static final int OK = 0;

    // ---- 1-5: the request was understood and refused on its merits ----

    /** No flight with that identifier. */
    public static final int ERR_UNKNOWN_FLIGHT = 1;

    /**
     * No flight on that source/destination pair. Distinct from an empty result list
     * because the brief requires an error here, not a zero-length reply (README &sect;4,
     * Op 1).
     */
    public static final int ERR_NO_ROUTE_MATCH = 2;

    /** Requested seats exceed availability. Raised by RESERVE and by opcode 6. */
    public static final int ERR_INSUFFICIENT_SEATS = 3;

    /** Out-of-range or nonsensical argument, caught at decoder step 6 (README &sect;3.6). */
    public static final int ERR_INVALID_ARGUMENT = 4;

    /**
     * An operation 5 or 6 would leave the airfare outside {@code 0 < fare <=}
     * {@link Protocol#MAX_FARE}. The stored value is left untouched, so a rejected
     * {@code ADJUST_FARE} is a no-op rather than a partial application (README
     * &sect;4 Ops 5&ndash;6).
     *
     * <p>Distinct from {@link #ERR_INVALID_ARGUMENT}, which covers a delta that is NaN
     * or infinite and is rejected at the codec before any arithmetic happens. This code
     * means the argument was well-formed and the <em>result</em> is not allowed &mdash;
     * a business-rule refusal, not a malformed message.
     *
     * <p>Code 5 previously carried {@code ERR_NOT_ENOUGH_HELD} under the seat-based
     * extra operations withdrawn at README &sect;22.2. The number is reused rather than
     * retired because nothing was ever implemented against the old meaning.
     */
    public static final int ERR_INVALID_FARE = 5;

    // ---- 6-12: the exchange itself is broken ----

    /** Body did not parse, or {@code payloadLen} disagreed with the datagram length. */
    public static final int ERR_MALFORMED_REQUEST = 6;

    /** Opcode outside 1-6; see {@link Protocol#isValidOpCode(int)}. */
    public static final int ERR_UNKNOWN_OPCODE = 7;

    /**
     * Monitor registry at capacity. Server tables are fixed-capacity by design, which
     * turns what would be an allocation failure into a clean protocol error (D17).
     */
    public static final int ERR_MONITOR_TABLE_FULL = 8;

    /** Server-side invariant violation. Should never be observed; log it loudly if it is. */
    public static final int ERR_SERVER_ERROR = 9;

    /** Header {@code version} not recognised &mdash; the two implementations have drifted (D19). */
    public static final int ERR_VERSION_MISMATCH = 10;

    /**
     * Client and server are in different invocation modes. Refusing outright is the
     * point: serving the request would produce &sect;13 measurements that look valid and
     * mean nothing (D19).
     */
    public static final int ERR_SEMANTICS_MISMATCH = 11;

    /**
     * The at-most-once history already holds this {@code (clientId, requestId)} with a
     * different request fingerprint, so this is a reused ID rather than a
     * retransmission and must not be answered from the stored reply (D24).
     */
    public static final int ERR_REQUEST_ID_CONFLICT = 12;

    /** Not instantiable: this is a constant holder, not a type. */
    private StatusCode() {
    }

    /** True only for {@link #OK}; every other code means no operation body follows. */
    public static boolean isOk(int status) {
        return status == OK;
    }

    /**
     * Splits the codes the way README &sect;5 groups them, because the two halves
     * demand different client behaviour and the distinction is a design decision
     * rather than a formatting one.
     *
     * <p>Codes 1&ndash;5 are refusals on the merits: the datagram was well-formed and
     * the operation ran, the server just said no to these arguments. The user can fix
     * them by typing something else, so the console reports them inline at the prompt
     * and stays in the same menu.
     *
     * <p>Codes 6&ndash;12 are protocol or configuration faults &mdash; a decode
     * failure, an unknown opcode, a version or semantics mismatch, a reused request
     * ID. No amount of retyping helps, so the client surfaces them as faults and, for
     * the mismatch cases, stops rather than continuing to generate meaningless
     * traffic. {@link #ERR_MONITOR_TABLE_FULL} sits on this side even though a later
     * retry might succeed: it is a server capacity condition, not a bad argument, so
     * re-prompting the user for different input would be misleading.
     *
     * <p>Bounds are expressed as constants so that appending codes cannot silently
     * shift the boundary.
     */
    public static boolean isProtocolFault(int status) {
        return status >= ERR_MALFORMED_REQUEST && status <= ERR_REQUEST_ID_CONFLICT;
    }

    /**
     * Constant name for a status code, for logging and for the client-side message
     * text that D4 makes the client's responsibility.
     *
     * <p>An unrecognised code is rendered rather than rejected: a reply from a drifted
     * or hostile peer is exactly the case where the raw number must reach the log.
     *
     * @return the constant's name, or {@code "UNKNOWN(n)"} for any other value
     */
    public static String name(int status) {
        switch (status) {
            case OK:
                return "OK";
            case ERR_UNKNOWN_FLIGHT:
                return "ERR_UNKNOWN_FLIGHT";
            case ERR_NO_ROUTE_MATCH:
                return "ERR_NO_ROUTE_MATCH";
            case ERR_INSUFFICIENT_SEATS:
                return "ERR_INSUFFICIENT_SEATS";
            case ERR_INVALID_ARGUMENT:
                return "ERR_INVALID_ARGUMENT";
            case ERR_INVALID_FARE:
                return "ERR_INVALID_FARE";
            case ERR_MALFORMED_REQUEST:
                return "ERR_MALFORMED_REQUEST";
            case ERR_UNKNOWN_OPCODE:
                return "ERR_UNKNOWN_OPCODE";
            case ERR_MONITOR_TABLE_FULL:
                return "ERR_MONITOR_TABLE_FULL";
            case ERR_SERVER_ERROR:
                return "ERR_SERVER_ERROR";
            case ERR_VERSION_MISMATCH:
                return "ERR_VERSION_MISMATCH";
            case ERR_SEMANTICS_MISMATCH:
                return "ERR_SEMANTICS_MISMATCH";
            case ERR_REQUEST_ID_CONFLICT:
                return "ERR_REQUEST_ID_CONFLICT";
            default:
                return "UNKNOWN(" + status + ")";
        }
    }
}
