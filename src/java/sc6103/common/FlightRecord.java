package sc6103.common;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * The six-field flight record of README sec. 11.2, in wire order: {@code flightId}
 * ({@code int32}), {@code source} ({@code string}), {@code destination}
 * ({@code string}), {@code departure} ({@code Time}), {@code airfare}
 * ({@code float32}), {@code seatsAvailable} ({@code int32}). Encodes to exactly 60
 * bytes for the values in that section.
 *
 * <p><b>This is the cross-language conformance fixture, not the server's flight table
 * entry, and it deliberately does not carry {@code totalSeats}.</b> README sec. 2.1
 * gives the server-side record seven fields; the sec. 11.2 vector encodes six, leaving
 * {@code totalSeats} out, and this class reproduces the vector rather than the table.
 * The omission is not an oversight to be "fixed": {@code totalSeats} is fixed at seed
 * time and never leaves the server, so no message needs it, and adding it here would
 * shift {@code seatsAvailable} four bytes and break V1 for both implementations. See
 * {@code tests/conformance/vectors.md} note D-4, which raises the labelling of sec.
 * 11.2 as a README amendment.
 *
 * <p>Equally, <b>no operation in README sec. 4 transmits a whole flight record.</b>
 * {@code QUERY_BY_ID} returns departure time, airfare and seats; {@code QUERY_BY_ROUTE}
 * returns only ids. This type exists because it is the one structure that exercises all
 * four wire primitives at once - {@code int32}, two strings with different pad counts,
 * the five-field time, and a {@code float32} - in a single buffer whose bytes both
 * implementations must reproduce. It is the anchor test: if V1 fails, nothing else in
 * the conformance file is worth running. The server's own mutable flight entry, with
 * {@code totalSeats} and whatever indexing it needs, is a separate server-side type.
 *
 * <p>Immutable. {@link String} and {@link DepartureTime} are themselves immutable, so
 * the {@code final} fields are enough and no defensive copying is required.
 */
public final class FlightRecord {

    private final int flightId;
    private final String source;
    private final String destination;
    private final DepartureTime departure;
    private final float airfare;
    private final int seatsAvailable;

    /**
     * Creates a flight record, rejecting place names that cannot be marshalled.
     *
     * <p><b>The {@link Protocol#MAX_STRING_BYTES} check lives here because the codec
     * does not do it on write.</b> {@link Codec#putString(String)} bounds itself by the
     * buffer only; enforcing the field cap on outbound values is a message-layer
     * responsibility (README sec. 3.6 step 6 validates field values), and this class is
     * that layer for this record. Without the check, a 70-byte place name would encode
     * happily into a 512-byte datagram and then be rejected by the peer's decoder,
     * where {@link Codec#getString()} does enforce the cap - a fault reported by the
     * wrong machine, one round trip after the mistake, with no clue as to which field
     * was too long.
     *
     * <p>The cap counts <em>UTF-8 bytes, not characters</em> (D15). The two differ the
     * moment a name contains a multi-byte sequence, and only the byte count is a bound
     * on what the C++ reader has to allocate.
     *
     * @throws NullPointerException     if any reference argument is null - our own bug,
     *                                  not a wire fault
     * @throws IllegalArgumentException if a place name exceeds
     *                                  {@link Protocol#MAX_STRING_BYTES} UTF-8 bytes
     */
    public FlightRecord(int flightId, String source, String destination,
            DepartureTime departure, float airfare, int seatsAvailable) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(departure, "departure");
        requireEncodable(source, "source");
        requireEncodable(destination, "destination");

        this.flightId = flightId;
        this.source = source;
        this.destination = destination;
        this.departure = departure;
        this.airfare = airfare;
        this.seatsAvailable = seatsAvailable;
    }

    // ---- Accessors ----

    /** @return the unique flight identifier (README sec. 2.1). */
    public int flightId() {
        return flightId;
    }

    /** @return the origin place name, at most {@link Protocol#MAX_STRING_BYTES} UTF-8 bytes. */
    public String source() {
        return source;
    }

    /** @return the destination place name, same bound as {@link #source()}. */
    public String destination() {
        return destination;
    }

    /** @return the departure time (README sec. 2.2). */
    public DepartureTime departure() {
        return departure;
    }

    /** @return the airfare as IEEE-754 binary32 (D16). */
    public float airfare() {
        return airfare;
    }

    /** @return seats currently available - the mutable state every sec. 13 experiment observes. */
    public int seatsAvailable() {
        return seatsAvailable;
    }

    // ---- Wire form ----

    /**
     * Writes the six fields in README sec. 11.2 order.
     *
     * <p>Order is the contract. There are no type tags on the wire (README sec. 3.3),
     * so nothing but this sequence tells the C++ side that the bytes after the second
     * string are a year. Swapping {@code airfare} and {@code seatsAvailable} - both
     * four bytes - would produce a buffer that decodes without any error at all and
     * reports a fare of 120 dollars for 899 seats.
     *
     * <p>Each field's own encoder restores 4-byte alignment ({@link Codec#putString})
     * so this method never tracks padding: after {@code "Singapore"} (4 + 9 + 3) and
     * {@code "London"} (4 + 6 + 2) the cursor is at 32, and the time starts there.
     *
     * @param out cursor positioned where the record begins
     * @throws CodecException if the buffer cannot hold the whole record
     */
    public void write(Codec out) throws CodecException {
        out.putInt32(flightId);
        out.putString(source);
        out.putString(destination);
        departure.write(out);
        out.putFloat32(airfare);
        out.putInt32(seatsAvailable);
    }

    /**
     * Reads a flight record, validating every field as it goes.
     *
     * <p>Each primitive read is bounds-checked by the codec, {@link Codec#getString()}
     * rejects a length that is negative or above {@link Protocol#MAX_STRING_BYTES}
     * before allocating, {@link Codec#getFloat32()} rejects a non-finite fare, and
     * {@link DepartureTime#read(Codec)} range-checks the date. That is README sec. 3.6
     * steps 5 and 6 applied field by field.
     *
     * <p>The constructor's own argument checks are converted to
     * {@link CodecException} rather than allowed to escape. Today that branch is
     * unreachable - the codec's decoded length cap is the same constant the constructor
     * enforces - but the cap is stated in two places, and if either ever moves, this
     * conversion is what keeps a malformed datagram producing a protocol error instead
     * of an unchecked exception unwinding out of the server's receive loop. README sec.
     * 3.6 step 7 requires the server to still be running afterwards.
     *
     * @param in cursor positioned at the start of the record
     * @return the decoded record
     * @throws CodecException on truncation, a bad length, a non-finite fare, or an
     *                        out-of-range date
     */
    public static FlightRecord read(Codec in) throws CodecException {
        final int start = in.position();
        final int id = in.getInt32();
        final String src = in.getString();
        final String dst = in.getString();
        final DepartureTime when = DepartureTime.read(in);
        final float fare = in.getFloat32();
        final int seats = in.getInt32();

        try {
            return new FlightRecord(id, src, dst, when, fare, seats);
        } catch (IllegalArgumentException e) {
            throw new CodecException(
                    "flight record at " + start + ": " + e.getMessage(), e);
        }
    }

    // ---- Object contract ----

    /**
     * One line for the request/reply trace the brief requires both sides to print.
     *
     * <p>The fare is printed with its wire bit pattern beside the decimal. Two floats
     * that print identically can differ in their last bit, and when the whole point of
     * the exercise is that two implementations produce the same 32 bits, the decimal
     * rendering is exactly the thing that hides a mismatch - see vectors.md F6, where
     * {@code 0.1f} is the value that exposes a float crossing the wire as text. The
     * hex is what a reader can check against a capture: 899.5 prints as
     * {@code 0x4460E000}, the four bytes at V1 offset 0x34.
     */
    @Override
    public String toString() {
        return "FlightRecord{id=" + flightId
                + ", " + source + "->" + destination
                + ", departs=" + departure
                + ", fare=" + airfare
                + String.format(Locale.ROOT, " (0x%08X)", Float.floatToIntBits(airfare))
                + ", seatsAvailable=" + seatsAvailable + "}";
    }

    /**
     * Value equality over all six fields.
     *
     * <p><b>The fare is compared as bits, not as a number</b>, which
     * {@code tests/conformance/vectors.md} requires of every float assertion:
     * {@code -0.0f == 0.0f} is true in both Java and C++, so a value comparison passes
     * on a dropped sign bit - precisely the bug vector V2.3 exists to catch. Comparing
     * {@link Float#floatToIntBits(float)} tests what actually goes on the wire.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FlightRecord other)) {
            return false;
        }
        return flightId == other.flightId
                && seatsAvailable == other.seatsAvailable
                && Float.floatToIntBits(airfare) == Float.floatToIntBits(other.airfare)
                && source.equals(other.source)
                && destination.equals(other.destination)
                && departure.equals(other.departure);
    }

    @Override
    public int hashCode() {
        // The fare contributes its bit pattern, for the same reason equals compares
        // bits: two records that are equal here must hash alike, and floatToIntBits is
        // what equals uses.
        return Objects.hash(flightId, source, destination, departure,
                Float.floatToIntBits(airfare), seatsAvailable);
    }

    // ---- Internals ----

    /**
     * Rejects a place name whose UTF-8 encoding exceeds
     * {@link Protocol#MAX_STRING_BYTES}.
     *
     * <p>The encoding is measured, not estimated from the character count, and the
     * charset is named rather than inherited from the platform default - the same rule
     * {@link Codec#putString(String)} follows, and for the same reason (D15, README
     * sec. 11.1). Measuring against {@code length()} would let a name of 40 accented
     * characters pass here and overflow the field on the wire.
     */
    private static void requireEncodable(String value, String field) {
        final int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > Protocol.MAX_STRING_BYTES) {
            throw new IllegalArgumentException(field + " is " + bytes
                    + " UTF-8 bytes, limit " + Protocol.MAX_STRING_BYTES);
        }
    }
}
