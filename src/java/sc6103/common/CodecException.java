package sc6103.common;

/**
 * Signals that a byte buffer could not be read or written as the wire format of
 * README sec. 3.3 defines it: a truncated field, a value outside its encodable
 * range, a non-finite float, or a string length that a datagram from the network
 * claims but cannot back with bytes.
 *
 * <p><b>Checked, not a {@code RuntimeException}, and that is deliberate.</b> The
 * inputs to the decoder arrive from a UDP socket, so a malformed buffer is a
 * routine event on the expected path, not a programming error. README sec. 3.6
 * makes rejection part of the protocol: steps 1 to 6 are a validation sequence
 * whose failure branch ends in "log and drop" or in a status-code reply, and
 * step 7 requires the server to still be running afterwards. Extending
 * {@link Exception} forces every call site to write that branch. An unchecked
 * exception would let a caller omit it silently, and the first hostile or merely
 * corrupt datagram would then take down a server the brief requires to stay up
 * and keep serving other clients.
 *
 * <p>The complementary rule: a fault in <em>our own</em> code - a null argument,
 * a buffer sized wrongly by the caller - is not a {@code CodecException}. Those
 * stay unchecked ({@link NullPointerException},
 * {@link IllegalArgumentException}), because they are bugs to fix rather than
 * network conditions to handle, and blurring the two would hide our own defects
 * inside the drop-the-datagram path.
 *
 * <p>Messages name the field size and the buffer position that failed, because
 * an off-by-one in the packer is invisible until it corrupts a value on the
 * wire, and the offset is what localises it against the hex dumps in
 * {@code tests/conformance/vectors.md}.
 *
 * <h2>Structural faults and value faults are different replies</h2>
 *
 * README sec. 5 gives them different status codes, and a dispatcher cannot tell them
 * apart from an exception type alone. A body that ran off the end of the datagram is
 * {@link StatusCode#ERR_MALFORMED_REQUEST}; a body that parsed perfectly and carried a
 * seat count of zero is {@link StatusCode#ERR_INVALID_ARGUMENT}, and sec. 4 says so
 * explicitly - "argument validation failures return ERR_INVALID_ARGUMENT throughout".
 *
 * <p>Guessing between them from the body length works only for the fixed-size
 * operations and fails for {@code QUERY_BY_ROUTE}, which is variable-length: a
 * truncated route request and an over-long place name are the same size question with
 * different answers. So the status travels on the exception, set by whichever check
 * actually failed, and {@link #status()} is what the dispatcher replies with.
 *
 * <p>The default is {@code ERR_MALFORMED_REQUEST}, so every existing throw site in
 * {@link Codec} - all of which are genuine structural faults - keeps the right code
 * without naming it.
 */
public class CodecException extends Exception {

    /**
     * Fixed rather than compiler-generated so the value cannot drift if a field
     * is ever added. Nothing in this project serialises an exception - Java
     * object serialization is forbidden by the brief - but {@link Exception} is
     * {@link java.io.Serializable} by inheritance and javac warns without it.
     */
    private static final long serialVersionUID = 1L;

    /** The README sec. 5 status code a server should reply with. */
    private final int status;

    /**
     * Creates an exception describing a codec failure.
     *
     * @param message what failed, including the field size and buffer offset
     */
    public CodecException(String message) {
        this(message, StatusCode.ERR_MALFORMED_REQUEST);
    }

    /**
     * Creates an exception for a field whose value was decoded cleanly and is not
     * acceptable - an out-of-range interval, a seat count below one, a fare outside
     * {@code (0, MAX_FARE]}.
     *
     * @param message what failed, including the field and the offending value
     * @param status  the sec. 5 code to reply with, normally
     *                {@link StatusCode#ERR_INVALID_ARGUMENT} or
     *                {@link StatusCode#ERR_INVALID_FARE}
     */
    public CodecException(String message, int status) {
        super(message);
        this.status = status;
    }

    /**
     * Creates an exception describing a codec failure that was triggered by
     * another.
     *
     * @param message what failed, including the field size and buffer offset
     * @param cause   the underlying failure, preserved so a decode fault
     *                originating in, say, a character-set operation is not
     *                flattened into a bare string
     */
    public CodecException(String message, Throwable cause) {
        super(message, cause);
        this.status = StatusCode.ERR_MALFORMED_REQUEST;
    }

    /**
     * The status code a server should put in its reply header for this failure.
     *
     * <p>A client ignores it: a malformed <em>reply</em> means the two implementations
     * disagree about a body layout, which is a conformance fault to investigate offline
     * against sec. 11.2, not something to answer with a status code.
     */
    public int status() {
        return status;
    }
}
