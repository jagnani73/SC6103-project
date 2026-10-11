package sc6103.common;

/**
 * Reply, callback and monitor-end bodies for README sec. 4.
 *
 * <p>The counterpart to {@link Requests}: what comes back after the 40-byte
 * {@link Header}. The C++ server is the only server (README sec. 22.1) and carries its
 * own copy written against Part I, so these two files and that one are held together by
 * nothing but the specification and the vectors in {@code tests/conformance/}.
 *
 * <p><b>An error reply has no body at all</b> (README sec. 5, D4). Status lives in the
 * header, so a caller must check {@link StatusCode#isOk(int)} <em>before</em> attempting
 * to decode any of these. Decoding a body from a 40-byte error reply is a truncation
 * fault, and {@link Codec} will report it as one - but the intended flow never gets
 * there, because the status check comes first.
 */
public final class Replies {

    /** Not instantiable: a namespace for the reply body types. */
    private Replies() {
    }

    // ---- Op 1 ----

    /**
     * {@code QUERY_BY_ROUTE} reply - README sec. 4 Op 1.
     *
     * <p>{@code truncated} then a {@code uint32} count then that many {@code int32}
     * identifiers. Worst case 4 + 4 + 64 x 4 = 264 bytes, inside
     * {@link Protocol#MAX_BODY}.
     *
     * <p>{@code truncated} is a flag, not a count: it says matches were omitted, not how
     * many. D14 caps the reply at one datagram rather than paginating, so the honest
     * thing the protocol can report is that the list is incomplete.
     *
     * <p>An empty result never reaches here. README sec. 4 makes "no match" the error
     * {@link StatusCode#ERR_NO_ROUTE_MATCH}, because the brief requires an error rather
     * than an empty list.
     */
    public static final class Route {

        public final boolean truncated;
        public final int[] flightIds;

        public Route(boolean truncated, int[] flightIds) {
            if (flightIds == null) {
                throw new IllegalArgumentException("flightIds is null");
            }
            if (flightIds.length > Protocol.MAX_ROUTE_RESULTS) {
                throw new IllegalArgumentException("flightIds " + flightIds.length
                        + " exceeds " + Protocol.MAX_ROUTE_RESULTS);
            }
            this.truncated = truncated;
            this.flightIds = flightIds.clone();
        }

        public void write(Codec out) throws CodecException {
            out.putInt32(truncated ? 1 : 0);
            out.putUInt32(flightIds.length);
            for (final int id : flightIds) {
                out.putInt32(id);
            }
        }

        public static Route read(Codec in) throws CodecException {
            final int flag = in.getInt32();
            final long count = in.getUInt32();

            // Validate before allocating. The count is attacker-controlled and is the
            // number this method would otherwise hand straight to `new int[]`. Read as
            // an unsigned long precisely so a wire value with the top bit set cannot
            // arrive here as a negative int and slip past a "> MAX" test
            // (README sec. 11.1).
            if (count > Protocol.MAX_ROUTE_RESULTS) {
                throw new CodecException("route result count " + count
                        + " exceeds " + Protocol.MAX_ROUTE_RESULTS);
            }
            // Each element is 4 bytes; a count the datagram cannot back is a truncated
            // reply, caught here rather than 60 elements into the loop.
            if (count * 4L > in.remaining()) {
                throw new CodecException("route result count " + count + " needs "
                        + (count * 4L) + " bytes, " + in.remaining() + " remaining");
            }

            final int[] ids = new int[(int) count];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = in.getInt32();
            }
            return new Route(flag != 0, ids);
        }

        @Override
        public String toString() {
            return "Route[" + flightIds.length + " ids"
                    + (truncated ? ", truncated" : "") + "]";
        }
    }

    // ---- Op 2 ----

    /**
     * {@code QUERY_BY_ID} reply - README sec. 4 Op 2. Fixed 28-byte body: five
     * {@code int32} date components, a {@code float32} fare, an {@code int32} seat count.
     *
     * <p>Note what is <em>absent</em>: no flight id, no source, no destination. The
     * client asked by id and already knows it, and D4 keeps replies minimal. This is why
     * the console composes "Flight 1934" from its own request rather than from the reply
     * (docs/console-script.md sec. 6).
     */
    public static final class Flight {

        public final DepartureTime departure;
        public final float airfare;
        public final int seatsAvailable;

        public Flight(DepartureTime departure, float airfare, int seatsAvailable) {
            if (departure == null) {
                throw new IllegalArgumentException("departure is null");
            }
            this.departure = departure;
            this.airfare = airfare;
            this.seatsAvailable = seatsAvailable;
        }

        public void write(Codec out) throws CodecException {
            departure.write(out);
            out.putFloat32(airfare);
            out.putInt32(seatsAvailable);
        }

        public static Flight read(Codec in) throws CodecException {
            final DepartureTime t = DepartureTime.read(in);
            final float fare = in.getFloat32();
            final int seats = in.getInt32();
            if (seats < 0) {
                throw new CodecException("seatsAvailable " + seats + " is negative");
            }
            return new Flight(t, fare, seats);
        }

        @Override
        public String toString() {
            return "Flight[" + departure + " fare=" + airfare
                    + " seats=" + seatsAvailable + "]";
        }
    }

    // ---- Op 3 ----

    /**
     * {@code RESERVE} reply - README sec. 4 Op 3. Fixed 4-byte body.
     *
     * <p>The post-state is returned rather than a bare acknowledgement so that under
     * at-least-once with a lost reply the second execution returns a <em>different</em>
     * seat count from the first. The corruption is then visible in the reply itself and
     * not only in a later query - which is what makes experiment E2 legible in a console
     * trace.
     */
    public static final class Reserve {

        public final int seatsAvailable;

        public Reserve(int seatsAvailable) {
            this.seatsAvailable = seatsAvailable;
        }

        public void write(Codec out) throws CodecException {
            out.putInt32(seatsAvailable);
        }

        public static Reserve read(Codec in) throws CodecException {
            final int seats = in.getInt32();
            if (seats < 0) {
                throw new CodecException("seatsAvailable " + seats + " is negative");
            }
            return new Reserve(seats);
        }

        @Override
        public String toString() {
            return "Reserve[seats=" + seatsAvailable + "]";
        }
    }

    // ---- Op 4 ----

    /**
     * {@code MONITOR} reply - README sec. 4 Op 4. Fixed 8-byte body.
     *
     * <p>{@code seatsAvailable} is the baseline snapshot at registration time, so the
     * client has something to compare the first callback against.
     *
     * <p>{@code expiresInMs} is advisory for display only. The client's own monotonic
     * deadline is authoritative (D20, README sec. 7.3): this value was computed on the
     * server, travelled over a lossy link, and says nothing about how long the reply took
     * to arrive.
     */
    public static final class Monitor {

        public final int seatsAvailable;
        public final int expiresInMs;

        public Monitor(int seatsAvailable, int expiresInMs) {
            this.seatsAvailable = seatsAvailable;
            this.expiresInMs = expiresInMs;
        }

        public void write(Codec out) throws CodecException {
            out.putInt32(seatsAvailable);
            out.putInt32(expiresInMs);
        }

        public static Monitor read(Codec in) throws CodecException {
            final int seats = in.getInt32();
            final int ms = in.getInt32();
            if (seats < 0) {
                throw new CodecException("seatsAvailable " + seats + " is negative");
            }
            if (ms < 0) {
                throw new CodecException("expiresInMs " + ms + " is negative");
            }
            return new Monitor(seats, ms);
        }

        @Override
        public String toString() {
            return "Monitor[seats=" + seatsAvailable + " expiresIn=" + expiresInMs + "ms]";
        }
    }

    // ---- Ops 5 and 6 ----

    /**
     * Reply to {@code ADJUST_FARE} and to {@code SET_FARE} - README sec. 4 Ops 5-6.
     * Fixed 4-byte body carrying the airfare after the operation applied.
     *
     * <p>One type serves both operations because both return the same single field. The
     * console still prints them differently: {@code ADJUST_FARE} shows the delta
     * alongside the result, because under at-least-once a user who sees only the final
     * figure cannot tell whether the operation ran once or twice
     * (docs/console-script.md sec. 3).
     *
     * <p>Not validated against {@link Requests#isLegalFare(float)} on decode. That range
     * is a rule about what the server may <em>store</em>; a reply reporting a value
     * outside it would mean the server is broken, and the honest response is to show the
     * user what actually arrived rather than to reject the datagram and hide it.
     * Non-finite values are still refused, by {@link Codec#getFloat32()}.
     */
    public static final class Fare {

        public final float airfare;

        public Fare(float airfare) {
            this.airfare = airfare;
        }

        public void write(Codec out) throws CodecException {
            out.putFloat32(airfare);
        }

        public static Fare read(Codec in) throws CodecException {
            return new Fare(in.getFloat32());
        }

        @Override
        public String toString() {
            return "Fare[" + airfare + "]";
        }
    }

    // ---- Unsolicited ----

    /**
     * {@code CALLBACK} body - README sec. 4, {@code messageType = 2}. Fixed 20 bytes.
     *
     * <p>Three of these five fields exist purely to make loss and duplication
     * <em>measurable</em> rather than merely admitted (D10):
     *
     * <ul>
     *   <li>{@code callbackSeq} counts from 1 per registration, so a client seeing
     *       1, 2, 4 knows one update was lost rather than guessing.</li>
     *   <li>{@code eventClientId} and {@code eventRequestId} together name the operation
     *       that caused the update, so two callbacks from one duplicated operation (same
     *       pair) are distinguishable from two genuinely separate operations (different
     *       pairs). That is the direct observable signature of at-least-once
     *       re-execution, and it is what experiment E8 reads.</li>
     * </ul>
     *
     * <p>Both halves are needed. Deck 2 slide 71 defines a unique request identifier as
     * the requestId plus an identifier for the client process, and every client counts
     * its requestIds from 0, so two clients that each reserve once would otherwise look
     * like one request executed twice.
     */
    public static final class Callback {

        public final int flightId;
        public final int seatsAvailable;

        /** The reserving client's {@code clientId}: a {@code uint32}, so held in a long. */
        public final long eventClientId;

        public final int eventRequestId;
        public final int callbackSeq;

        public Callback(int flightId, int seatsAvailable, long eventClientId,
                        int eventRequestId, int callbackSeq) {
            this.flightId = flightId;
            this.seatsAvailable = seatsAvailable;
            this.eventClientId = eventClientId;
            this.eventRequestId = eventRequestId;
            this.callbackSeq = callbackSeq;
        }

        public void write(Codec out) throws CodecException {
            out.putInt32(flightId);
            out.putInt32(seatsAvailable);
            out.putUInt32(eventClientId);
            out.putInt32(eventRequestId);
            out.putInt32(callbackSeq);
        }

        public static Callback read(Codec in) throws CodecException {
            final int id = in.getInt32();
            final int seats = in.getInt32();
            final long eventClient = in.getUInt32();
            final int event = in.getInt32();
            final int seq = in.getInt32();
            if (seats < 0) {
                throw new CodecException("seatsAvailable " + seats + " is negative");
            }
            if (eventClient == 0L) {
                // 0 is the reserved "unset" clientId (README sec. 2.3). The server refuses
                // a request carrying it, so no reservation can have come from it.
                throw new CodecException("eventClientId 0 is not a client");
            }
            if (seq < 1) {
                // Sequence numbers start at 1, so 0 would make "no callbacks yet"
                // indistinguishable from "the first callback" in the gap arithmetic.
                throw new CodecException("callbackSeq " + seq + " must be >= 1");
            }
            return new Callback(id, seats, eventClient, event, seq);
        }

        @Override
        public String toString() {
            return "Callback[seq=" + callbackSeq + " flight=" + flightId
                    + " seats=" + seatsAvailable + " event=" + eventRequestId
                    + " by=" + String.format(java.util.Locale.ROOT, "0x%08X", eventClientId)
                    + "]";
        }
    }

    /**
     * {@code MONITOR_END} body - README sec. 4, {@code messageType = 3}. Fixed 8 bytes.
     *
     * <p>{@code callbackSeq} here is the <em>total</em> the server sent for the
     * registration, not a sequence position. It is the whole reason this message exists
     * (D20): with it the client reports "3 of 4 updates received, 1 lost"; without it the
     * client can only report gaps between updates that happened to arrive, and a run of
     * losses at the tail is invisible.
     *
     * <p>The message is advisory. It is unacknowledged and can itself be lost, so a
     * client that waited for it instead of its own deadline would hang.
     */
    public static final class MonitorEnd {

        public final int flightId;
        public final int callbackSeq;

        public MonitorEnd(int flightId, int callbackSeq) {
            this.flightId = flightId;
            this.callbackSeq = callbackSeq;
        }

        public void write(Codec out) throws CodecException {
            out.putInt32(flightId);
            out.putInt32(callbackSeq);
        }

        public static MonitorEnd read(Codec in) throws CodecException {
            final int id = in.getInt32();
            final int total = in.getInt32();
            if (total < 0) {
                throw new CodecException("callbackSeq total " + total + " is negative");
            }
            return new MonitorEnd(id, total);
        }

        @Override
        public String toString() {
            return "MonitorEnd[flight=" + flightId + " total=" + callbackSeq + "]";
        }
    }
}
