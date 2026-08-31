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
 */
public class CodecException extends Exception {

    /**
     * Fixed rather than compiler-generated so the value cannot drift if a field
     * is ever added. Nothing in this project serialises an exception - Java
     * object serialization is forbidden by the brief - but {@link Exception} is
     * {@link java.io.Serializable} by inheritance and javac warns without it.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception describing a codec failure.
     *
     * @param message what failed, including the field size and buffer offset
     */
    public CodecException(String message) {
        super(message);
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
    }
}
