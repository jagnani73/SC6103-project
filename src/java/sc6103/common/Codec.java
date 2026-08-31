package sc6103.common;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Hand-written byte-level marshaller for the four wire primitives of README
 * sec. 3.3: {@code int32}, {@code uint32}, {@code float32} and {@code string}.
 * A {@code Codec} is a cursor over a caller-supplied {@code byte[]} that
 * bounds-checks before every read and before every write.
 *
 * <p>This is the class the brief asks for by hand. No {@code ByteBuffer}, no
 * {@code DataOutputStream}, no object serialization, no third-party encoder -
 * writing the packing is the exercise, and every one of those alternatives is
 * either forbidden outright or hides the byte layout the C++ server has to
 * match independently. The C++ side carries its own copy of these rules written
 * against README Part I, never against this file (README sec. 11).
 *
 * <h2>Invariants this class keeps</h2>
 * <ul>
 *   <li><b>Big-endian, always.</b> Network byte order, README sec. 3.2. Java
 *       has no {@code htonl}, so the shifts below <em>are</em> the byte order:
 *       they are written most-significant byte first and are correct regardless
 *       of the host's own endianness, which is why manual shifts are specified
 *       for the Java side in README sec. 11.1 rather than any native helper.</li>
 *   <li><b>Every field is 4 bytes and every field starts 4-aligned.</b> README
 *       sec. 3.2 and D23. Strings restore the alignment with zero padding, so a
 *       caller never has to track it.</li>
 *   <li><b>The array never grows.</b> A writer that runs out of room throws;
 *       it does not reallocate. Growing would let a body silently exceed
 *       {@link Protocol#MAX_BODY} and produce a datagram the peer rejects - or
 *       worse, one that fragments, which would make the sec. 13 loss
 *       measurements meaningless.</li>
 *   <li><b>Nothing is allocated from a length that came off the wire until that
 *       length has been validated.</b> See {@link #getString()}.</li>
 * </ul>
 *
 * <p>Not thread-safe, and does not need to be: the server is single-threaded on
 * the request path, and each datagram gets its own cursor.
 *
 * <p>Hand-verified against {@code tests/conformance/vectors.md} V2 - the empty
 * string (4 bytes), {@code "A"} (8), {@code "abc"} (8), {@code "abcd"} (8), the
 * 63-byte maximum (68), and {@code 899.5f} as {@code 44 60 E0 00}.
 */
public final class Codec {

    /**
     * Largest value a {@code uint32} field can carry. Held as a {@code long}
     * because Java has no unsigned 32-bit type: the wire value 4294967295 does
     * not fit an {@code int}, so the API takes and returns {@code long} and the
     * range check happens here rather than in every caller (README sec. 11.1).
     */
    private static final long UINT32_MAX = 4294967295L;

    /** Backing store. Never replaced, never resized - see the class note. */
    private final byte[] buf;

    /**
     * One past the last byte this cursor may touch.
     *
     * <p>The only difference between a writer and a reader. For a writer it is
     * the array's capacity; for a reader it is the number of bytes actually
     * received, which is normally smaller than the 512-byte receive buffer. That
     * distinction is the whole point of {@link #reader(byte[], int)}: bounding a
     * decoder by the buffer's capacity instead of by the datagram's length would
     * let it read stale bytes left over from the previous datagram and decode
     * them as though they had arrived, which is a real bug in reused-buffer UDP
     * loops and one that no conformance vector can catch.
     */
    private final int limit;

    /** Next byte to be read or written; also the offset quoted in errors. */
    private int pos;

    private Codec(byte[] buf, int limit) {
        this.buf = buf;
        this.limit = limit;
        this.pos = 0;
    }

    // ---- Factories ----

    /**
     * A cursor that writes from offset 0 up to {@code buf.length}.
     *
     * @param buf destination, typically {@link Protocol#MAX_DATAGRAM} bytes
     * @return a fresh writer positioned at 0
     * @throws NullPointerException if {@code buf} is null - a caller bug, not a
     *                              wire fault, so it is unchecked (see
     *                              {@link CodecException})
     */
    public static Codec writer(byte[] buf) {
        Objects.requireNonNull(buf, "buf");
        return new Codec(buf, buf.length);
    }

    /**
     * A cursor that reads from offset 0 up to {@code length}.
     *
     * @param buf    the receive buffer
     * @param length bytes actually received, i.e.
     *               {@code DatagramPacket.getLength()}, <em>not</em>
     *               {@code buf.length}
     * @return a fresh reader positioned at 0
     * @throws NullPointerException     if {@code buf} is null
     * @throws IllegalArgumentException if {@code length} is negative or exceeds
     *                                  the array. Unchecked because the socket
     *                                  cannot produce such a value; only our own
     *                                  code can, and a checked exception here
     *                                  would also be impossible to declare on a
     *                                  factory the API fixes as throwing nothing
     */
    public static Codec reader(byte[] buf, int length) {
        Objects.requireNonNull(buf, "buf");
        if (length < 0 || length > buf.length) {
            throw new IllegalArgumentException(
                    "length " + length + " outside 0.." + buf.length);
        }
        return new Codec(buf, length);
    }

    // ---- Writes ----

    /**
     * Writes a signed 32-bit integer, big-endian, two's complement
     * (README sec. 3.3).
     *
     * @param v the value to encode; the full {@code int} range is legal
     * @return this cursor, so field sequences read like the layout tables in
     *         README sec. 4
     * @throws CodecException if fewer than 4 bytes remain
     */
    public Codec putInt32(int v) throws CodecException {
        require(4, "int32");
        writeRawInt32(v);
        return this;
    }

    /**
     * Writes an unsigned 32-bit integer, big-endian.
     *
     * <p>Takes a {@code long} because values above {@link Integer#MAX_VALUE} -
     * {@code clientId} {@code 0xA1B2C3D4} among them - have no {@code int}
     * representation in Java. The range check rejects anything that would not
     * survive the narrowing: without it, {@code 4294967296L} would silently
     * encode as 0 and {@code -1L} as {@code FF FF FF FF}, both of which produce
     * a datagram that decodes cleanly and means something else. On the wire this
     * is byte-identical to {@link #putInt32(int)}; only the interpretation
     * differs (vectors.md V2.2).
     *
     * @param v value in the range 0 to 4294967295 inclusive
     * @return this cursor
     * @throws CodecException if {@code v} is outside that range, or if fewer
     *                        than 4 bytes remain
     */
    public Codec putUInt32(long v) throws CodecException {
        if (v < 0 || v > UINT32_MAX) {
            throw new CodecException(
                    "uint32 value " + v + " at " + pos + " outside 0.." + UINT32_MAX);
        }
        require(4, "uint32");
        // Narrowing is safe and lossless: the guard above proves the value fits
        // 32 bits, and the cast keeps exactly those bits.
        writeRawInt32((int) v);
        return this;
    }

    /**
     * Writes an IEEE-754 binary32 bit pattern, big-endian (README sec. 3.3,
     * D16).
     *
     * <p>{@link Float#floatToIntBits(float)} is the Java half of D16's
     * bit-for-bit transfer, matching the C++ side's {@code memcpy} into a
     * {@code uint32_t} followed by {@code htonl}. It is a reinterpretation of
     * the 32 bits, never a numeric conversion, so no decimal formatting ever
     * enters the path - which is what vectors.md V2.3 checks with {@code 0.1f}
     * ({@code 3D CC CC CD}): any implementation that formats and reparses lands
     * somewhere else. {@code -0.0f} keeps its sign bit here and encodes as
     * {@code 80 00 00 00}, as that vector requires.
     *
     * @param v a finite float
     * @return this cursor
     * @throws CodecException if {@code v} is NaN or infinite, or if fewer than
     *                        4 bytes remain
     */
    public Codec putFloat32(float v) throws CodecException {
        // README sec. 3.6 step 6 makes "floats are finite" a standing validation
        // rule, and it is enforced on the encode side as well so a NaN can never
        // leave this process. The rule bites at the boundary: NaN breaks every
        // comparison it touches (NaN != NaN), so a NaN airfare that reached the
        // flight table would corrupt sorting and equality silently rather than
        // failing loudly, and infinity has no meaning as a fare or a seat price.
        // Rejecting at the codec means neither can be introduced by an operation
        // added later forgetting to check.
        requireFinite(v, pos);
        require(4, "float32");
        writeRawInt32(Float.floatToIntBits(v));
        return this;
    }

    /**
     * Writes a {@code uint32} byte length, the string's UTF-8 bytes, then 0 to 3
     * zero bytes so that the next field starts on a 4-byte boundary
     * (README sec. 3.3, D15). No NUL terminator is transmitted - the trailing
     * zeros are alignment padding, and a terminator would put the two
     * implementations one byte apart on every string.
     *
     * <p>The character set is named explicitly. Java's platform default varies
     * by machine and locale, so relying on it would make the bytes depend on
     * which laptop ran the client - undetectable until the three-machine demo
     * (D15, README sec. 11.1).
     *
     * <p>The length is a <em>byte</em> count, not a character count: the two
     * differ the moment a multi-byte sequence appears, and only the byte count
     * is unambiguous to a C++ reader that has no character concept.
     *
     * <p>This writer does not enforce {@link Protocol#MAX_STRING_BYTES}, and the
     * asymmetry with {@link #getString()} is intentional. That cap is a
     * field-validation rule (README sec. 3.6 step 6) applied to values before
     * they are marshalled; on the read side it is also a memory-safety rule,
     * because the length there is untrusted input. Here the value is our own and
     * the buffer bound is what guarantees safety. Callers marshalling place
     * names still validate against {@link Protocol#MAX_STRING_BYTES} at the
     * message layer.
     *
     * @param s the string to encode; may be empty, which encodes to the 4-byte
     *          length 0 with no content and no padding (vectors.md V2.4)
     * @return this cursor
     * @throws NullPointerException if {@code s} is null - our own bug, not a
     *                              wire fault
     * @throws CodecException       if the whole field does not fit
     */
    public Codec putString(String s) throws CodecException {
        Objects.requireNonNull(s, "s");
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        final int pad = padFor(bytes.length);

        // Reserve the entire field - length, bytes and padding - in one check,
        // so a failure leaves the cursor untouched rather than stranding a
        // length prefix with no string behind it. A half-written field would be
        // far harder to diagnose from a hex dump than a rejected one.
        //
        // Summed as a long rather than an int: this method does not cap the
        // string (see above), so bytes.length is bounded only by the JVM, and an
        // int sum could wrap negative on a huge string and slip past the check
        // into an out-of-bounds copy. The long cannot wrap for any array length.
        final long need = 4L + bytes.length + pad;
        if (need > remaining()) {
            throw new CodecException("string at " + pos + " needs " + need
                    + " bytes, " + remaining() + " remaining");
        }

        writeRawInt32(bytes.length);
        System.arraycopy(bytes, 0, buf, pos, bytes.length);
        pos += bytes.length;

        // Zero the padding explicitly. The buffer is caller-supplied and is
        // reused across datagrams, so the bytes sitting here are whatever the
        // previous message left. vectors.md requires an encoder to emit zero
        // padding (a decoder must tolerate nonzero, an encoder must not produce
        // it), so leaving stale bytes would fail the encode test and leak the
        // previous message's contents onto the wire.
        for (int i = 0; i < pad; i++) {
            buf[pos + i] = 0;
        }
        pos += pad;
        return this;
    }

    // ---- Reads ----

    /**
     * Reads a signed 32-bit integer, big-endian (README sec. 3.3).
     *
     * @return the decoded value, over the full {@code int} range
     * @throws CodecException if fewer than 4 bytes remain
     */
    public int getInt32() throws CodecException {
        require(4, "int32");
        return readRawInt32();
    }

    /**
     * Reads an unsigned 32-bit integer, big-endian.
     *
     * <p>Widening through {@code & 0xFFFFFFFFL} is what turns the wire's
     * {@code FF FF FF FF} into 4294967295 rather than into the {@code int}
     * {@code -1} that the same bits denote in Java. Callers that only compare or
     * echo the value (a {@code clientId}, say) may keep it as bits; callers that
     * <em>use</em> it as a magnitude must have the unsigned value, and getting
     * this wrong turns a large length into a negative one, which is the
     * allocation hazard README sec. 11.1 names.
     *
     * @return the value in the range 0 to 4294967295
     * @throws CodecException if fewer than 4 bytes remain
     */
    public long getUInt32() throws CodecException {
        require(4, "uint32");
        return readRawInt32() & 0xFFFFFFFFL;
    }

    /**
     * Reads an IEEE-754 binary32 bit pattern, big-endian (README sec. 3.3,
     * D16).
     *
     * @return a finite float
     * @throws CodecException if fewer than 4 bytes remain, or if the decoded
     *                        bits are NaN or infinite
     */
    public float getFloat32() throws CodecException {
        final int start = pos;
        require(4, "float32");
        // intBitsToFloat is the exact inverse of floatToIntBits - a
        // reinterpretation of 32 bits, never a parse (D16, README sec. 11.1).
        final float v = Float.intBitsToFloat(readRawInt32());
        // Checked after decoding, because on this side the bits are untrusted
        // input: a corrupt or hostile datagram can carry any of the 16 million
        // NaN patterns, and README sec. 3.6 step 6 requires floats to be
        // validated as finite before any field value is used. Rejecting here
        // means no NaN can reach a comparison anywhere downstream. The offset
        // reported is the field's own start, not the cursor's post-read
        // position, so it lines up with a hex dump.
        requireFinite(v, start);
        return v;
    }

    /**
     * Reads a length-prefixed UTF-8 string and consumes its alignment padding
     * (README sec. 3.3, D15).
     *
     * <p><b>Validate before allocating.</b> The length is the single most
     * dangerous field in the protocol: it is attacker- or corruption-controlled,
     * and it is the number this method would otherwise hand straight to an
     * allocator. README sec. 11.1 makes the order explicit for the Java side -
     * check non-negative <em>and</em> in range <em>before</em> use. So the
     * sequence below is: read the four bytes, reject a negative or oversized
     * length, reject a length the remaining buffer cannot back, and only then
     * construct the {@code String}. Reversing any two of those steps turns a
     * malformed 12-byte datagram into a multi-gigabyte allocation attempt or an
     * {@code ArrayIndexOutOfBoundsException} escaping as an unchecked exception
     * from a server the brief requires to keep running.
     *
     * <p>A wire length with its top bit set decodes to a negative Java
     * {@code int}, so it is printed with
     * {@link Integer#toUnsignedString(int)} to report what was actually
     * received rather than the misleading signed rendering
     * (README sec. 11.1).
     *
     * <p>The padding is skipped, not inspected. README sec. 3.2 says padding is
     * "ignored on read", so a decoder must not reject nonzero pad bytes; it must
     * still <em>consume</em> them, or the cursor lands off the 4-byte boundary
     * and every subsequent field in the body decodes from the wrong offset. The
     * pad bytes are required to be present, though - a datagram that ends
     * mid-field is truncated, not merely unpadded.
     *
     * @return the decoded string, with the cursor left on the next 4-byte
     *         boundary
     * @throws CodecException on a truncated field, a negative length, a length
     *                        above {@link Protocol#MAX_STRING_BYTES}, or a
     *                        length the buffer cannot supply
     */
    public String getString() throws CodecException {
        final int start = pos;
        require(4, "string length");
        final int rawLen = readRawInt32();

        // Step 1: range. Covers negatives implicitly (a negative int is not
        // between 0 and 63) but the sign is tested explicitly because it is the
        // named hazard and because the message should say which rule was broken.
        if (rawLen < 0 || rawLen > Protocol.MAX_STRING_BYTES) {
            throw new CodecException("string length "
                    + Integer.toUnsignedString(rawLen) + " at " + start
                    + " outside 0.." + Protocol.MAX_STRING_BYTES);
        }

        // Step 2: the buffer must actually hold that many content bytes. A
        // length of 63 is legal in isolation and still a lie in an 8-byte
        // datagram.
        if (rawLen > remaining()) {
            throw new CodecException("string body at " + pos + " needs " + rawLen
                    + " bytes, " + remaining() + " remaining");
        }

        // Step 3: the alignment padding must be present too, otherwise the
        // datagram was truncated between the content and the next field.
        final int pad = padFor(rawLen);
        require(rawLen + pad, "string body with pad");

        // Only now is anything allocated, and the charset is named rather than
        // inherited from the platform default (D15, README sec. 11.1).
        final String s = new String(buf, pos, rawLen, StandardCharsets.UTF_8);
        pos += rawLen + pad;
        return s;
    }

    // ---- Cursor state ----

    /**
     * Bytes consumed or produced so far, which is also the offset of the next
     * field. For a writer this is the length to hand to
     * {@code DatagramPacket}; comparing it against the byte offsets in
     * {@code tests/conformance/vectors.md} is how a layout fault is localised.
     *
     * @return the cursor's current offset
     */
    public int position() {
        return pos;
    }

    /**
     * Room left before the limit. Every bounds check in this class is stated in
     * terms of this value.
     *
     * @return unread input for a reader, free space for a writer
     */
    public int remaining() {
        return limit - pos;
    }

    /**
     * The backing array, not a copy, so the send path stays allocation-free.
     * Only the first {@link #position()} bytes of a writer's array are
     * meaningful; the rest is untouched or stale.
     *
     * @return the array this cursor was built over
     */
    public byte[] array() {
        return buf;
    }

    // ---- Internals ----

    /**
     * Zero bytes needed after {@code len} content bytes to reach the next 4-byte
     * boundary: {@code (4 - (len mod 4)) mod 4}, giving 0 for an already-aligned
     * length rather than a wasteful 4. The masks are the {@code mod 4} - exact
     * for the non-negative lengths this is called with. An implementation that
     * pads unconditionally passes {@code "abc"} and fails {@code "abcd"}, which
     * is why vectors.md V2.4 carries both.
     */
    private static int padFor(int len) {
        return (4 - (len & 3)) & 3;
    }

    /**
     * Bounds check run before every read and every write.
     *
     * @param n    bytes the field needs
     * @param what field kind, for the message
     * @throws CodecException naming the field, the offset, the need and what is
     *                        left, e.g. "int32 at 36 needs 4 bytes, 2 remaining"
     */
    private void require(int n, String what) throws CodecException {
        if (remaining() < n) {
            throw new CodecException(what + " at " + pos + " needs " + n
                    + " bytes, " + remaining() + " remaining");
        }
    }

    /**
     * Rejects NaN and both infinities. Shared by {@link #putFloat32(float)} and
     * {@link #getFloat32()}; see both for why the rule is enforced in each
     * direction.
     *
     * @param at offset of the float field, passed in rather than read from
     *           {@link #pos} because on the read side the cursor has already
     *           advanced past the field by the time the value can be tested
     */
    private void requireFinite(float v, int at) throws CodecException {
        if (!Float.isFinite(v)) {
            throw new CodecException("float32 at " + at + " is not finite: " + v);
        }
    }

    /**
     * Writes 4 bytes most-significant first. The caller has already bounds
     * checked.
     *
     * <p>Each byte is isolated with {@code & 0xFF} before the implicit narrowing
     * to {@code byte}. The mask is not strictly required on the write side - the
     * narrowing cast keeps the low 8 bits either way - but it is written to make
     * the intent explicit and to keep the packer and the unpacker visibly
     * symmetric, since on the read side the same mask is mandatory.
     */
    private void writeRawInt32(int v) {
        buf[pos] = (byte) ((v >>> 24) & 0xFF);
        buf[pos + 1] = (byte) ((v >>> 16) & 0xFF);
        buf[pos + 2] = (byte) ((v >>> 8) & 0xFF);
        buf[pos + 3] = (byte) (v & 0xFF);
        pos += 4;
    }

    /**
     * Reads 4 bytes most-significant first. The caller has already bounds
     * checked.
     *
     * <p><b>Every byte is masked with {@code & 0xFF}, and that is mandatory.</b>
     * Java's {@code byte} is signed, so a wire byte of {@code 0xE0} is held as
     * {@code -32}; widening it to {@code int} for the shift sign-extends it to
     * {@code 0xFFFFFFE0}, and the {@code OR} then smears ones across every byte
     * above it. Dropping the mask on even one byte corrupts any value with a
     * high byte at or above {@code 0x80}. README sec. 11.1 calls this out as the
     * characteristic Java-side bug, and vectors.md carries {@code 0xFF},
     * {@code 0xA1}, {@code 0xE0} and {@code 0x80} precisely so that an omission
     * fails a test rather than the demo - {@code 899.5f} alone
     * ({@code 44 60 E0 00}) catches it.
     *
     * <p>The mask on the final byte is what does the work there; the shift
     * distances 24, 16 and 8 are the big-endian order of README sec. 3.2 and are
     * independent of the host's own byte order.
     */
    private int readRawInt32() {
        final int v = ((buf[pos] & 0xFF) << 24)
                | ((buf[pos + 1] & 0xFF) << 16)
                | ((buf[pos + 2] & 0xFF) << 8)
                | (buf[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }
}
