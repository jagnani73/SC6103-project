package sc6103.common;

/**
 * Request bodies for the six operations of README sec. 4.
 *
 * <p>Each nested type is the body of one opcode: the bytes that follow the 40-byte
 * {@link Header}. The header is written separately, because the body length has to be
 * known before {@code payloadLen} can be filled in - encode the body first, then the
 * header in front of it.
 *
 * <p>These live in {@code common} rather than in the client because README sec. 16
 * shares this package between the Java client and the Java server. The C++ server
 * carries its own independent copy written against README Part I, never against this
 * file.
 *
 * <p><b>There are no type tags on the wire</b> (README sec. 3.3). The layout is derived
 * from {@code opCode} alone, so a decoder must already know which nested type it is
 * reading. Reading the wrong one against a well-formed datagram will usually succeed and
 * return nonsense, which is why the dispatcher selects the parser from the opcode rather
 * than from anything in the body.
 *
 * <p>Every field is validated on decode, not merely read. README sec. 3.6 step 6 makes
 * value validation part of the decoder contract, and these bodies carry the arguments an
 * attacker or a drifted peer controls.
 */
public final class Requests {

    /** Not instantiable: a namespace for the six body types. */
    private Requests() {
    }

    // ---- Op 1 ----

    /**
     * {@code QUERY_BY_ROUTE(source, destination)} - README sec. 4 Op 1.
     *
     * <p>Two length-prefixed UTF-8 strings, each padded to the next 4-byte boundary by
     * {@link Codec#putString(String)}. Body size is therefore variable: 8 bytes of length
     * prefix plus the padded content of both names.
     */
    public static final class Route {

        public final String source;
        public final String destination;

        public Route(String source, String destination) {
            this.source = requireName(source, "source");
            this.destination = requireName(destination, "destination");
        }

        public void write(Codec out) throws CodecException {
            out.putString(source);
            out.putString(destination);
        }

        public static Route read(Codec in) throws CodecException {
            // Order matters and is fixed by README sec. 4: source first. Reversing the
            // two would decode cleanly and search the wrong direction, which is exactly
            // the class of fault the conformance vector V3 exists to catch.
            final String src = in.getString();
            final String dst = in.getString();
            return new Route(src, dst);
        }

        /**
         * Rejects a place name that cannot be encoded within
         * {@link Protocol#MAX_STRING_BYTES}.
         *
         * <p>Checked in the constructor so the same rule applies whether the value came
         * from a console prompt or off the wire. {@link Codec#putString(String)}
         * deliberately does not enforce the cap - it is a field-validation rule, not a
         * codec rule, and this is the field layer.
         */
        private static String requireName(String v, String field) {
            if (v == null) {
                throw new IllegalArgumentException(field + " is null");
            }
            final int bytes = v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes == 0 || bytes > Protocol.MAX_STRING_BYTES) {
                throw new IllegalArgumentException(field + " is " + bytes
                        + " UTF-8 bytes, must be 1.." + Protocol.MAX_STRING_BYTES);
            }
            return v;
        }

        @Override
        public String toString() {
            return "Route[" + source + " -> " + destination + "]";
        }
    }

    // ---- Op 2 ----

    /** {@code QUERY_BY_ID(flightId)} - README sec. 4 Op 2. Fixed 4-byte body. */
    public static final class Flight {

        public final int flightId;

        public Flight(int flightId) {
            this.flightId = flightId;
        }

        public void write(Codec out) throws CodecException {
            out.putInt32(flightId);
        }

        public static Flight read(Codec in) throws CodecException {
            return new Flight(in.getInt32());
        }

        @Override
        public String toString() {
            return "Flight[" + flightId + "]";
        }
    }

    // ---- Op 3 ----

    /**
     * {@code RESERVE(flightId, numSeats)} - README sec. 4 Op 3. Fixed 8-byte body.
     *
     * <p>{@code numSeats >= 1} is enforced here rather than left to the server, so the
     * console rejects it before a datagram is sent (docs/console-script.md sec. 3). The
     * server still validates independently - a client-side check is a courtesy to the
     * user, never a guarantee to the peer.
     */
    public static final class Reserve {

        public final int flightId;
        public final int numSeats;

        public Reserve(int flightId, int numSeats) {
            if (numSeats < 1) {
                throw new IllegalArgumentException("numSeats " + numSeats + " must be >= 1");
            }
            this.flightId = flightId;
            this.numSeats = numSeats;
        }

        public void write(Codec out) throws CodecException {
            out.putInt32(flightId);
            out.putInt32(numSeats);
        }

        public static Reserve read(Codec in) throws CodecException {
            final int id = in.getInt32();
            final int n = in.getInt32();
            if (n < 1) {
                // A decoded value, unlike a constructed one, is untrusted input: report
                // it as a wire fault so the dispatcher answers ERR_INVALID_ARGUMENT
                // rather than throwing an unchecked exception out of the request loop.
                throw new CodecException("RESERVE numSeats " + n + " must be >= 1");
            }
            return new Reserve(id, n);
        }

        @Override
        public String toString() {
            return "Reserve[flight=" + flightId + " seats=" + numSeats + "]";
        }
    }

    // ---- Op 4 ----

    /**
     * {@code MONITOR(flightId, intervalMs)} - README sec. 4 Op 4. Fixed 8-byte body.
     *
     * <p>The interval is milliseconds on the wire even though the console prompts in
     * seconds, because README sec. 4 specifies {@code intervalMs} and the unit must not
     * be inferred from the field name on the far side.
     */
    public static final class Monitor {

        /**
         * Upper bound on a registration, five minutes. The brief specifies no range
         * (README sec. 23), so this is ours: an unbounded interval lets one client hold a
         * registry slot for the life of the server, and the registry is fixed-capacity
         * (D17). Long enough for the demo's 60-second monitor with room to spare.
         */
        public static final int MAX_INTERVAL_MS = 300_000;

        public final int flightId;
        public final int intervalMs;

        public Monitor(int flightId, int intervalMs) {
            if (intervalMs < 1 || intervalMs > MAX_INTERVAL_MS) {
                throw new IllegalArgumentException("intervalMs " + intervalMs
                        + " outside 1.." + MAX_INTERVAL_MS);
            }
            this.flightId = flightId;
            this.intervalMs = intervalMs;
        }

        public void write(Codec out) throws CodecException {
            out.putInt32(flightId);
            out.putInt32(intervalMs);
        }

        public static Monitor read(Codec in) throws CodecException {
            final int id = in.getInt32();
            final int ms = in.getInt32();
            if (ms < 1 || ms > MAX_INTERVAL_MS) {
                throw new CodecException("MONITOR intervalMs " + ms
                        + " outside 1.." + MAX_INTERVAL_MS);
            }
            return new Monitor(id, ms);
        }

        @Override
        public String toString() {
            return "Monitor[flight=" + flightId + " " + intervalMs + "ms]";
        }
    }

    // ---- Op 5 ----

    /**
     * {@code ADJUST_FARE(flightId, delta)} - README sec. 4 Op 5, the required
     * non-idempotent extra operation. Fixed 8-byte body.
     *
     * <p>This is the first request body in the protocol to carry a {@code float32}
     * (D9). Before operations 5 and 6, a float crossed the wire only in
     * {@code QUERY_BY_ID}'s reply, so the IEEE-754 path was exercised in one direction
     * only - which is precisely where a cross-language marshalling fault would have
     * hidden.
     *
     * <p>{@code delta} may be negative; that is the point of the operation. It may not
     * be NaN or infinite, and {@link Codec#putFloat32(float)} rejects both on encode as
     * well as decode, so a non-finite value cannot leave or enter this process.
     */
    public static final class AdjustFare {

        public final int flightId;
        public final float delta;

        public AdjustFare(int flightId, float delta) {
            if (!Float.isFinite(delta)) {
                throw new IllegalArgumentException("delta must be finite, was " + delta);
            }
            this.flightId = flightId;
            this.delta = delta;
        }

        public void write(Codec out) throws CodecException {
            out.putInt32(flightId);
            out.putFloat32(delta);
        }

        public static AdjustFare read(Codec in) throws CodecException {
            final int id = in.getInt32();
            // getFloat32 already rejects NaN and both infinities (README sec. 3.6 step 6),
            // so by the time this returns the value is finite and the constructor's own
            // check cannot fire on wire input.
            final float d = in.getFloat32();
            return new AdjustFare(id, d);
        }

        @Override
        public String toString() {
            return "AdjustFare[flight=" + flightId + " delta=" + delta + "]";
        }
    }

    // ---- Op 6 ----

    /**
     * {@code SET_FARE(flightId, newFare)} - README sec. 4 Op 6, the required idempotent
     * extra operation. Fixed 8-byte body.
     *
     * <p>The range {@code 0 < newFare <= MAX_FARE} is checked here as well as at the
     * server. The server's check is the authoritative one and produces
     * {@link StatusCode#ERR_INVALID_FARE}; this one exists so the console can refuse an
     * impossible fare at the prompt instead of spending a round trip to be told no.
     */
    public static final class SetFare {

        public final int flightId;
        public final float newFare;

        public SetFare(int flightId, float newFare) {
            if (!isLegalFare(newFare)) {
                throw new IllegalArgumentException("newFare " + newFare
                        + " outside (0, " + Protocol.MAX_FARE + "]");
            }
            this.flightId = flightId;
            this.newFare = newFare;
        }

        public void write(Codec out) throws CodecException {
            out.putInt32(flightId);
            out.putFloat32(newFare);
        }

        public static SetFare read(Codec in) throws CodecException {
            final int id = in.getInt32();
            final float f = in.getFloat32();
            if (!isLegalFare(f)) {
                throw new CodecException("SET_FARE newFare " + f
                        + " outside (0, " + Protocol.MAX_FARE + "]");
            }
            return new SetFare(id, f);
        }

        @Override
        public String toString() {
            return "SetFare[flight=" + flightId + " fare=" + newFare + "]";
        }
    }

    /**
     * The fare range rule of README sec. 4 Ops 5-6, in one place so the two operations
     * and the server's post-adjustment check cannot drift apart.
     *
     * <p>Zero is excluded deliberately: a free flight is not a fare, and excluding it
     * means {@code ADJUST_FARE} by the exact current fare is a refusal rather than a
     * silent zeroing.
     */
    public static boolean isLegalFare(float fare) {
        return Float.isFinite(fare) && fare > 0.0f && fare <= Protocol.MAX_FARE;
    }
}
