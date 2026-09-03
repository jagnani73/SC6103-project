package sc6103.conformance;

import java.util.Arrays;
import java.util.Objects;

import sc6103.common.Codec;
import sc6103.common.CodecException;
import sc6103.common.DepartureTime;
import sc6103.common.FlightRecord;
import sc6103.common.Header;
import sc6103.common.Protocol;
import sc6103.common.StatusCode;

/**
 * Executable form of {@code tests/conformance/vectors.md}: every vector V1 to V7,
 * asserted in both directions against the Java marshaller.
 *
 * <p><b>Why this is a bare {@code main} and not JUnit.</b> README sec. 15.1 commits the
 * project to plain {@code javac} with no build tool and no third-party jar, because the
 * submission is a single archive that a marker unpacks and compiles. A test that needs a
 * dependency resolved is a test the marker cannot run. So this class prints one line per
 * assertion and exits nonzero if any of them failed - the same contract a test runner
 * would give, expressed in the only tooling the brief guarantees.
 *
 * <p><b>Why the hex is embedded rather than parsed from vectors.md.</b> A test that reads
 * its expectations from a file at run time is only as trustworthy as the parser, and it
 * fails for the wrong reason when the file moves. The literals below were transcribed from
 * the "Flat hex" block of each vector; the vector file stays the human-readable normative
 * copy and this class is a self-contained restatement of it. Neither is derived from the
 * other's code, which is the point of README sec. 11: the C++ server is tested against the
 * same document, never against this file.
 *
 * <p><b>Each vector is asserted twice</b>, as the vector file's "How to use this file"
 * section requires:
 * <ol>
 *   <li><b>Encode.</b> Build the described value with the real classes, assert the bytes
 *       produced are byte-for-byte equal to the hex here, and assert the total length.</li>
 *   <li><b>Decode.</b> Feed the hex to the unmarshaller, assert every field, then
 *       re-encode the decoded value and assert it reproduces the same bytes.</li>
 * </ol>
 *
 * <p><b>Floats are compared as bit patterns, never as values.</b> {@code -0.0f == 0.0f} is
 * true in both Java and C++, so a value comparison passes on a dropped sign bit - the exact
 * bug V2.3 exists to catch. See {@link #checkBits}.
 *
 * <p>The negative cases at the end matter as much as the vectors. A decoder that accepts a
 * 64-byte string length, a negative length, a NaN or a header from an unrelated sender is
 * wrong in a way no positive vector can reveal, and README sec. 3.6 makes the rejections
 * part of the protocol rather than defensive extras.
 *
 * <p>Source is pure ASCII on purpose: it is compiled with {@code -encoding US-ASCII}, so a
 * stray non-ASCII byte is a compile error rather than a value that depends on the platform
 * default charset (README sec. 11.1, the same hazard {@code Codec.putString} names).
 */
public final class ConformanceTest {

    // ---- Vector hex, transcribed from tests/conformance/vectors.md ----

    /** V1, the 60-byte FlightRecord anchor. If this fails, nothing else is worth running. */
    private static final String V1_HEX =
            "00 00 07 8E 00 00 00 09 53 69 6E 67 61 70 6F 72"
          + "65 00 00 00 00 00 00 06 4C 6F 6E 64 6F 6E 00 00"
          + "00 00 07 EA 00 00 00 0A 00 00 00 0E 00 00 00 09"
          + "00 00 00 1E 44 60 E0 00 00 00 00 78";

    /** V3, REQUEST QUERY_BY_ROUTE("Singapore", "London"), 68 bytes. */
    private static final String V3_HEX =
            "53 43 36 31 00 00 00 01 00 00 00 00 00 00 00 01"
          + "00 00 00 01 A1 B2 C3 D4 00 00 00 07 00 00 00 06"
          + "00 00 00 00 00 00 00 1C 00 00 00 09 53 69 6E 67"
          + "61 70 6F 72 65 00 00 00 00 00 00 06 4C 6F 6E 64"
          + "6F 6E 00 00";

    /** V4, REPLY to V3, status OK, three ascending flight ids, 60 bytes. */
    private static final String V4_HEX =
            "53 43 36 31 00 00 00 01 00 00 00 01 00 00 00 01"
          + "00 00 00 01 A1 B2 C3 D4 00 00 00 07 FF FF FF FF"
          + "00 00 00 00 00 00 00 14 00 00 00 00 00 00 00 03"
          + "00 00 00 58 00 00 07 8E 00 00 08 99";

    /** V5, CALLBACK, 56 bytes. */
    private static final String V5_HEX =
            "53 43 36 31 00 00 00 01 00 00 00 02 00 00 00 00"
          + "00 00 00 01 0F 0E 0D 0C 00 00 00 0B FF FF FF FF"
          + "00 00 00 00 00 00 00 10 00 00 07 8E 00 00 00 75"
          + "00 00 00 16 00 00 00 01";

    /** V6, MONITOR_END, 48 bytes. */
    private static final String V6_HEX =
            "53 43 36 31 00 00 00 01 00 00 00 03 00 00 00 00"
          + "00 00 00 01 0F 0E 0D 0C 00 00 00 0B FF FF FF FF"
          + "00 00 00 00 00 00 00 08 00 00 07 8E 00 00 00 04";

    /** V7, error REPLY with no body, 40 bytes. */
    private static final String V7_HEX =
            "53 43 36 31 00 00 00 01 00 00 00 01 00 00 00 02"
          + "00 00 00 01 A1 B2 C3 D4 00 00 00 08 FF FF FF FF"
          + "00 00 00 01 00 00 00 00";

    /**
     * V2.4's 63-byte maximum string: A-Z, a-z, 0-9, then one more 'A'.
     * 26 + 26 + 10 + 1 = 63 = {@link Protocol#MAX_STRING_BYTES}.
     */
    private static final String MAX_STRING =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789A";

    /** V2.4's encoding of {@link #MAX_STRING}: 4 length + 63 content + 1 pad = 68. */
    private static final String MAX_STRING_HEX =
            "00 00 00 3F"
          + "41 42 43 44 45 46 47 48 49 4A 4B 4C 4D 4E 4F 50"
          + "51 52 53 54 55 56 57 58 59 5A 61 62 63 64 65 66"
          + "67 68 69 6A 6B 6C 6D 6E 6F 70 71 72 73 74 75 76"
          + "77 78 79 7A 30 31 32 33 34 35 36 37 38 39 41"
          + "00";

    // ---- Header field offsets, README sec. 3.4. Used only by the mutation tests. ----

    private static final int OFF_MAGIC = 0;
    private static final int OFF_VERSION = 4;
    private static final int OFF_MESSAGE_TYPE = 8;
    private static final int OFF_OPCODE = 12;
    private static final int OFF_SEMANTICS = 16;
    private static final int OFF_PAYLOAD_LEN = 36;

    // ---- Values the vector file fixes for the identities used below ----

    /** The querying/reserving client (V3, V4, V7). Top bit set, so it exercises uint32. */
    private static final long CLIENT_QUERY = 0xA1B2C3D4L;

    /** The monitoring client (V5, V6). Distinct so a test that crosses the two fails. */
    private static final long CLIENT_MONITOR = 0x0F0E0D0CL;

    private static int passed;
    private static int failed;

    /** Not instantiable: this is an entry point, not a type. */
    private ConformanceTest() {
    }

    /**
     * A test body, allowed to throw. Declared here rather than reusing
     * {@code java.util.concurrent.Callable} so the signature says what it is and no
     * return value has to be invented.
     */
    private interface Action {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        System.out.println("SC6103 conformance vectors -- tests/conformance/vectors.md");
        System.out.println();

        run("V1", ConformanceTest::v1);
        run("V2.1", ConformanceTest::v2Int32);
        run("V2.2", ConformanceTest::v2UInt32);
        run("V2.3", ConformanceTest::v2Float32);
        run("V2.4", ConformanceTest::v2String);
        run("V3", ConformanceTest::v3);
        run("V4", ConformanceTest::v4);
        run("V5", ConformanceTest::v5);
        run("V6", ConformanceTest::v6);
        run("V7", ConformanceTest::v7);
        run("NEG-string", ConformanceTest::negStringLength);
        run("NEG-truncation", ConformanceTest::negTruncation);
        run("NEG-float", ConformanceTest::negFloat);
        run("NEG-header", ConformanceTest::negHeader);
        run("POS-opnone", ConformanceTest::posOpNoneAccepted);

        System.out.println();
        System.out.println("----------------------------------------------------------");
        System.out.println("assertions: " + (passed + failed)
                + "   passed: " + passed + "   failed: " + failed);
        if (failed > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    // ================================================================
    // V1 -- FlightRecord anchor
    // ================================================================

    private static void v1() throws CodecException {
        final byte[] want = hex(V1_HEX);
        checkNum("V1 vector is 60 bytes", 60, want.length);

        final DepartureTime when = new DepartureTime(2026, 10, 14, 9, 30);
        final FlightRecord expected =
                new FlightRecord(1934, "Singapore", "London", when, 899.5f, 120);

        // -- Encode --
        final byte[] buf = new byte[Protocol.MAX_DATAGRAM];
        final Codec out = Codec.writer(buf);
        expected.write(out);
        checkNum("V1 encoded length", 60, out.position());
        checkBytes("V1 encode", want, Arrays.copyOf(buf, out.position()));

        // -- Decode --
        final Codec in = Codec.reader(want, want.length);
        final FlightRecord got = FlightRecord.read(in);
        checkNum("V1 decode consumed the whole buffer", 60, in.position());
        checkNum("V1 decode flightId", 1934, got.flightId());
        checkObj("V1 decode source", "Singapore", got.source());
        checkObj("V1 decode destination", "London", got.destination());
        checkObj("V1 decode departureTime", when, got.departure());
        // Bit comparison, not value comparison: vectors.md "Rules that the assertions
        // must respect", first bullet.
        checkBits("V1 decode airfare", 899.5f, got.airfare());
        checkNum("V1 decode seatsAvailable", 120, got.seatsAvailable());
        checkObj("V1 decode equals the expected record", expected, got);

        // -- Round trip: re-encoding the decoded value must reproduce the same bytes --
        final byte[] buf2 = new byte[Protocol.MAX_DATAGRAM];
        final Codec out2 = Codec.writer(buf2);
        got.write(out2);
        checkBytes("V1 round-trip re-encode", want, Arrays.copyOf(buf2, out2.position()));
    }

    // ================================================================
    // V2 -- primitives in isolation
    // ================================================================

    private static void v2Int32() throws CodecException {
        // -1 and INT32_MIN are the two values that expose a sign-extension or
        // unsigned-shift error; -1 is also the on-wire form of an unused ackUpTo.
        final int[] values = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE};
        final String[] hexes = {
            "00 00 00 00", "00 00 00 01", "FF FF FF FF", "7F FF FF FF", "80 00 00 00",
        };

        for (int i = 0; i < values.length; i++) {
            final String label = "V2.1 int32 " + values[i];
            final byte[] want = hex(hexes[i]);
            checkNum(label + " is 4 bytes", 4, want.length);

            final byte[] buf = new byte[4];
            final Codec out = Codec.writer(buf);
            out.putInt32(values[i]);
            checkNum(label + " encoded length", 4, out.position());
            checkBytes(label + " encode", want, buf);

            final Codec in = Codec.reader(want, want.length);
            checkNum(label + " decode", values[i], in.getInt32());
            checkNum(label + " decode consumed 4 bytes", 4, in.position());
        }
    }

    private static void v2UInt32() throws CodecException {
        // 4294967295 does not fit a Java int; the codec's API takes and returns long for
        // exactly this reason, and the assertion is on the bytes, never on a signed
        // decimal rendering. vectors.md V2.2.
        final long[] values = {0L, 4294967295L};
        final String[] hexes = {"00 00 00 00", "FF FF FF FF"};

        for (int i = 0; i < values.length; i++) {
            final String label = "V2.2 uint32 " + values[i];
            final byte[] want = hex(hexes[i]);

            final byte[] buf = new byte[4];
            final Codec out = Codec.writer(buf);
            out.putUInt32(values[i]);
            checkNum(label + " encoded length", 4, out.position());
            checkBytes(label + " encode", want, buf);

            final Codec in = Codec.reader(want, want.length);
            checkNum(label + " decode", values[i], in.getUInt32());
        }

        // The same four bytes read as int32 are -1: identical encoding, different
        // interpretation. Printing the unsigned form is what vectors.md asks the Java
        // side to do, so assert the two views agree.
        final Codec asSigned = Codec.reader(hex("FF FF FF FF"), 4);
        final int bits = asSigned.getInt32();
        checkNum("V2.2 uint32 FF FF FF FF reads as int32 -1", -1, bits);
        checkObj("V2.2 uint32 FF FF FF FF prints unsigned as 4294967295",
                "4294967295", Integer.toUnsignedString(bits));
    }

    private static void v2Float32() throws CodecException {
        // -0.0f catches a sign bit dropped by a value-comparison assertion; 0.1f catches
        // a float that crossed the wire as text. vectors.md V2.3, footnotes F1-F6.
        final float[] values = {0.0f, -0.0f, 1.0f, -1.0f, 899.5f, 0.1f};
        final String[] hexes = {
            "00 00 00 00", "80 00 00 00", "3F 80 00 00",
            "BF 80 00 00", "44 60 E0 00", "3D CC CC CD",
        };
        final String[] names = {"0.0f", "-0.0f", "1.0f", "-1.0f", "899.5f", "0.1f"};

        for (int i = 0; i < values.length; i++) {
            final String label = "V2.3 float32 " + names[i];
            final byte[] want = hex(hexes[i]);

            final byte[] buf = new byte[4];
            final Codec out = Codec.writer(buf);
            out.putFloat32(values[i]);
            checkNum(label + " encoded length", 4, out.position());
            checkBytes(label + " encode", want, buf);

            final Codec in = Codec.reader(want, want.length);
            checkBits(label + " decode", values[i], in.getFloat32());
        }
    }

    private static void v2String() throws CodecException {
        // "abc" and "abcd" differ by one content byte and encode to the same total size.
        // An encoder that pads unconditionally passes the first and fails the second,
        // which is why both are here. vectors.md V2.4.
        final String[] values = {"", "A", "abc", "abcd", MAX_STRING};
        final String[] hexes = {
            "00 00 00 00",
            "00 00 00 01 41 00 00 00",
            "00 00 00 03 61 62 63 00",
            "00 00 00 04 61 62 63 64",
            MAX_STRING_HEX,
        };
        final String[] names = {"empty", "\"A\"", "\"abc\"", "\"abcd\"", "63-byte max"};
        final int[] totals = {4, 8, 8, 8, 68};

        checkNum("V2.4 63-byte string really is 63 bytes",
                Protocol.MAX_STRING_BYTES, MAX_STRING.length());

        for (int i = 0; i < values.length; i++) {
            final String label = "V2.4 string " + names[i];
            final byte[] want = hex(hexes[i]);
            checkNum(label + " vector length", totals[i], want.length);

            final byte[] buf = new byte[Protocol.MAX_DATAGRAM];
            final Codec out = Codec.writer(buf);
            out.putString(values[i]);
            checkNum(label + " encoded length", totals[i], out.position());
            checkBytes(label + " encode", want, Arrays.copyOf(buf, out.position()));

            final Codec in = Codec.reader(want, want.length);
            checkObj(label + " decode", values[i], in.getString());
            checkNum(label + " decode consumed the pad too", totals[i], in.position());
        }

        // The encoded-size summary table: the two place names used by V1 and V3.
        checkNum("V2.4 \"Singapore\" encodes to 16 bytes", 16, encodedStringSize("Singapore"));
        checkNum("V2.4 \"London\" encodes to 12 bytes", 12, encodedStringSize("London"));
    }

    private static int encodedStringSize(String s) throws CodecException {
        final Codec out = Codec.writer(new byte[Protocol.MAX_DATAGRAM]);
        out.putString(s);
        return out.position();
    }

    // ================================================================
    // V3 -- REQUEST QUERY_BY_ROUTE
    // ================================================================

    /** The V3 header, rebuilt wherever a test needs it (V4 is its reply). */
    private static Header v3Header() {
        // ackUpTo = 6 is deliberately not the NO_ACK sentinel: the field is exercised
        // with a real history watermark (vectors.md V3).
        return Header.request(CLIENT_QUERY, 7, Protocol.OP_QUERY_BY_ROUTE,
                Protocol.SEM_AT_MOST_ONCE, 6, 28);
    }

    private static void v3() throws CodecException {
        final byte[] want = hex(V3_HEX);
        checkNum("V3 vector is 68 bytes", 68, want.length);

        // -- Encode --
        final Header header = v3Header();
        final byte[] buf = new byte[Protocol.MAX_DATAGRAM];
        final Codec out = Codec.writer(buf);
        header.write(out);
        checkNum("V3 header is 40 bytes", Protocol.HEADER_SIZE, out.position());
        out.putString("Singapore");
        out.putString("London");
        checkNum("V3 encoded length", 68, out.position());
        checkNum("V3 payloadLen equals datagram length minus 40",
                header.payloadLen, out.position() - Protocol.HEADER_SIZE);
        checkBytes("V3 encode", want, Arrays.copyOf(buf, out.position()));

        // -- Decode --
        final Codec in = Codec.reader(want, want.length);
        final Header got = Header.read(in);
        // README sec. 3.6 step 4: this check needs the length the socket reported, so
        // Header.read cannot do it and the caller must. Assert it explicitly.
        checkNum("V3 decode datagram length equals 40 + payloadLen",
                want.length, Protocol.HEADER_SIZE + got.payloadLen);
        checkNum("V3 decode magic", Protocol.MAGIC, got.magic);
        checkNum("V3 decode version", Protocol.VERSION, got.version);
        checkNum("V3 decode messageType", Protocol.MSG_REQUEST, got.messageType);
        checkNum("V3 decode opCode", Protocol.OP_QUERY_BY_ROUTE, got.opCode);
        checkNum("V3 decode semantics", Protocol.SEM_AT_MOST_ONCE, got.semantics);
        checkNum("V3 decode clientId", CLIENT_QUERY, got.clientId);
        checkNum("V3 decode requestId", 7, got.requestId);
        checkNum("V3 decode ackUpTo", 6, got.ackUpTo);
        checkNum("V3 decode status", 0, got.status);
        checkNum("V3 decode payloadLen", 28, got.payloadLen);
        checkObj("V3 decode header equals the built header", header, got);
        checkObj("V3 decode source", "Singapore", in.getString());
        checkObj("V3 decode destination", "London", in.getString());
        checkNum("V3 decode consumed the whole datagram", want.length, in.position());

        // -- Round trip --
        final byte[] buf2 = new byte[Protocol.MAX_DATAGRAM];
        final Codec out2 = Codec.writer(buf2);
        got.write(out2);
        out2.putString("Singapore");
        out2.putString("London");
        checkBytes("V3 round-trip re-encode", want, Arrays.copyOf(buf2, out2.position()));
    }

    // ================================================================
    // V4 -- REPLY, three ascending flight ids
    // ================================================================

    private static void v4() throws CodecException {
        final byte[] want = hex(V4_HEX);
        checkNum("V4 vector is 60 bytes", 60, want.length);

        // Ascending, not repository order: README sec. 4 Op 1 makes the sort part of the
        // interface, and the vector lists ids whose natural insertion order differs.
        final int[] ids = {88, 1934, 2201};

        // -- Encode --
        final Header header = Header.reply(v3Header(), StatusCode.OK, 20);
        final byte[] buf = new byte[Protocol.MAX_DATAGRAM];
        final Codec out = Codec.writer(buf);
        header.write(out);
        out.putInt32(0);          // truncated = 0
        out.putInt32(ids.length); // count = 3
        for (int id : ids) {
            out.putInt32(id);
        }
        checkNum("V4 encoded length", 60, out.position());
        checkNum("V4 payloadLen equals datagram length minus 40",
                header.payloadLen, out.position() - Protocol.HEADER_SIZE);
        checkNum("V4 ackUpTo is NO_ACK on a reply", Protocol.NO_ACK, header.ackUpTo);
        checkBytes("V4 encode", want, Arrays.copyOf(buf, out.position()));

        // -- Decode --
        final Codec in = Codec.reader(want, want.length);
        final Header got = Header.read(in);
        checkNum("V4 decode datagram length equals 40 + payloadLen",
                want.length, Protocol.HEADER_SIZE + got.payloadLen);
        checkNum("V4 decode messageType", Protocol.MSG_REPLY, got.messageType);
        checkNum("V4 decode opCode is echoed", Protocol.OP_QUERY_BY_ROUTE, got.opCode);
        checkNum("V4 decode semantics is echoed", Protocol.SEM_AT_MOST_ONCE, got.semantics);
        checkNum("V4 decode clientId is echoed", CLIENT_QUERY, got.clientId);
        checkNum("V4 decode requestId is echoed", 7, got.requestId);
        checkNum("V4 decode ackUpTo", Protocol.NO_ACK, got.ackUpTo);
        checkNum("V4 decode status", StatusCode.OK, got.status);
        checkNum("V4 decode payloadLen", 20, got.payloadLen);
        checkObj("V4 decode header equals the built header", header, got);

        checkNum("V4 decode truncated", 0, in.getInt32());
        final int count = in.getInt32();
        checkNum("V4 decode count", 3, count);
        final int[] decoded = new int[count];
        for (int i = 0; i < count; i++) {
            decoded[i] = in.getInt32();
        }
        checkObj("V4 decode flightIds", Arrays.toString(ids), Arrays.toString(decoded));
        checkObj("V4 decode flightIds are ascending", Boolean.TRUE, isAscending(decoded));
        checkNum("V4 decode consumed the whole datagram", want.length, in.position());

        // -- Round trip --
        final byte[] buf2 = new byte[Protocol.MAX_DATAGRAM];
        final Codec out2 = Codec.writer(buf2);
        got.write(out2);
        out2.putInt32(0);
        out2.putInt32(count);
        for (int id : decoded) {
            out2.putInt32(id);
        }
        checkBytes("V4 round-trip re-encode", want, Arrays.copyOf(buf2, out2.position()));
    }

    private static Boolean isAscending(int[] a) {
        for (int i = 1; i < a.length; i++) {
            if (a[i] <= a[i - 1]) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }

    // ================================================================
    // V5 -- CALLBACK
    // ================================================================

    private static void v5() throws CodecException {
        final byte[] want = hex(V5_HEX);
        checkNum("V5 vector is 56 bytes", 56, want.length);

        // -- Encode --
        final Header header =
                Header.callback(CLIENT_MONITOR, 11, Protocol.SEM_AT_MOST_ONCE, 16);
        final byte[] buf = new byte[Protocol.MAX_DATAGRAM];
        final Codec out = Codec.writer(buf);
        header.write(out);
        out.putInt32(1934); // flightId
        out.putInt32(117);  // seatsAvailable
        // requestId 11 and eventRequestId 22 are different on purpose: the first is the
        // monitoring client's own MONITOR, the second belongs to another client's
        // RESERVE. Swapping them decodes cleanly and means the wrong thing.
        out.putInt32(22);   // eventRequestId
        out.putInt32(1);    // callbackSeq
        checkNum("V5 encoded length", 56, out.position());
        checkNum("V5 payloadLen equals datagram length minus 40",
                header.payloadLen, out.position() - Protocol.HEADER_SIZE);
        checkNum("V5 opCode is OP_NONE", Protocol.OP_NONE, header.opCode);
        checkBytes("V5 encode", want, Arrays.copyOf(buf, out.position()));

        // -- Decode --
        final Codec in = Codec.reader(want, want.length);
        final Header got = Header.read(in);
        checkNum("V5 decode datagram length equals 40 + payloadLen",
                want.length, Protocol.HEADER_SIZE + got.payloadLen);
        checkNum("V5 decode messageType", Protocol.MSG_CALLBACK, got.messageType);
        checkNum("V5 decode opCode", Protocol.OP_NONE, got.opCode);
        checkNum("V5 decode clientId", CLIENT_MONITOR, got.clientId);
        checkNum("V5 decode requestId is the MONITOR registration", 11, got.requestId);
        checkNum("V5 decode ackUpTo", Protocol.NO_ACK, got.ackUpTo);
        checkNum("V5 decode status", StatusCode.OK, got.status);
        checkNum("V5 decode payloadLen", 16, got.payloadLen);
        checkObj("V5 decode header equals the built header", header, got);
        checkNum("V5 decode flightId", 1934, in.getInt32());
        checkNum("V5 decode seatsAvailable", 117, in.getInt32());
        checkNum("V5 decode eventRequestId", 22, in.getInt32());
        checkNum("V5 decode callbackSeq", 1, in.getInt32());
        checkNum("V5 decode consumed the whole datagram", want.length, in.position());

        // -- Round trip --
        final byte[] buf2 = new byte[Protocol.MAX_DATAGRAM];
        final Codec out2 = Codec.writer(buf2);
        got.write(out2);
        out2.putInt32(1934).putInt32(117).putInt32(22).putInt32(1);
        checkBytes("V5 round-trip re-encode", want, Arrays.copyOf(buf2, out2.position()));
    }

    // ================================================================
    // V6 -- MONITOR_END
    // ================================================================

    private static void v6() throws CodecException {
        final byte[] want = hex(V6_HEX);
        checkNum("V6 vector is 48 bytes", 48, want.length);

        // -- Encode --
        final Header header =
                Header.monitorEnd(CLIENT_MONITOR, 11, Protocol.SEM_AT_MOST_ONCE, 8);
        final byte[] buf = new byte[Protocol.MAX_DATAGRAM];
        final Codec out = Codec.writer(buf);
        header.write(out);
        out.putInt32(1934); // flightId
        out.putInt32(4);    // callbackSeq: the final count, so the client can report
                            // how many updates it missed rather than only the gaps
        checkNum("V6 encoded length", 48, out.position());
        checkNum("V6 payloadLen equals datagram length minus 40",
                header.payloadLen, out.position() - Protocol.HEADER_SIZE);
        checkBytes("V6 encode", want, Arrays.copyOf(buf, out.position()));

        // V5 and V6 are the same registration, so their headers agree everywhere except
        // where they must not: messageType (byte 11, 02 versus 03) and payloadLen
        // (byte 39, 16 versus 8). A dispatcher switching on messageType must still route
        // them apart, which is what makes this worth pinning.
        //
        // NOTE: vectors.md's prose says these "differ in exactly one header byte
        // (0x000B: 02 versus 03) plus their bodies". That sentence overlooks payloadLen,
        // which is header field ten and necessarily differs because the bodies differ in
        // length. The hex blocks in the file are right; only the sentence is wrong, so
        // this assertion follows the bytes. Raised as a vectors.md prose amendment.
        final byte[] v5 = hex(V5_HEX);
        checkObj("V6 header differs from V5 only at messageType and payloadLen",
                "[11, 39]", headerDiffOffsets(v5, want));

        // -- Decode --
        final Codec in = Codec.reader(want, want.length);
        final Header got = Header.read(in);
        checkNum("V6 decode datagram length equals 40 + payloadLen",
                want.length, Protocol.HEADER_SIZE + got.payloadLen);
        checkNum("V6 decode messageType", Protocol.MSG_MONITOR_END, got.messageType);
        checkNum("V6 decode opCode", Protocol.OP_NONE, got.opCode);
        checkNum("V6 decode clientId", CLIENT_MONITOR, got.clientId);
        checkNum("V6 decode requestId is the MONITOR registration", 11, got.requestId);
        checkNum("V6 decode ackUpTo", Protocol.NO_ACK, got.ackUpTo);
        checkNum("V6 decode status", StatusCode.OK, got.status);
        checkNum("V6 decode payloadLen", 8, got.payloadLen);
        checkObj("V6 decode header equals the built header", header, got);
        checkNum("V6 decode flightId", 1934, in.getInt32());
        checkNum("V6 decode callbackSeq", 4, in.getInt32());
        checkNum("V6 decode consumed the whole datagram", want.length, in.position());

        // -- Round trip --
        final byte[] buf2 = new byte[Protocol.MAX_DATAGRAM];
        final Codec out2 = Codec.writer(buf2);
        got.write(out2);
        out2.putInt32(1934).putInt32(4);
        checkBytes("V6 round-trip re-encode", want, Arrays.copyOf(buf2, out2.position()));
    }

    /** Offsets at which the first 40 bytes of two datagrams differ, as "[i, j]". */
    private static String headerDiffOffsets(byte[] a, byte[] b) {
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Protocol.HEADER_SIZE; i++) {
            if (a[i] != b[i]) {
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append(i);
            }
        }
        return sb.append(']').toString();
    }

    // ================================================================
    // V7 -- error REPLY with no body
    // ================================================================

    private static void v7() throws CodecException {
        final byte[] want = hex(V7_HEX);
        checkNum("V7 vector is 40 bytes", Protocol.HEADER_SIZE, want.length);

        // The request being answered. Its own payloadLen is irrelevant here: only
        // opCode, semantics, clientId and requestId are echoed into the reply.
        final Header request = Header.request(CLIENT_QUERY, 8, Protocol.OP_QUERY_BY_ID,
                Protocol.SEM_AT_MOST_ONCE, Protocol.NO_ACK, 4);
        final Header header =
                Header.reply(request, StatusCode.ERR_UNKNOWN_FLIGHT, 0);

        // -- Encode --
        final byte[] buf = new byte[Protocol.MAX_DATAGRAM];
        final Codec out = Codec.writer(buf);
        header.write(out);
        // No body is written at all: a zero payloadLen is the whole statement. This is
        // the vector that catches a sender padding every datagram to a minimum size.
        checkNum("V7 encoded length is the header alone", 40, out.position());
        checkNum("V7 payloadLen equals datagram length minus 40",
                header.payloadLen, out.position() - Protocol.HEADER_SIZE);
        checkBytes("V7 encode", want, Arrays.copyOf(buf, out.position()));

        // -- Decode --
        final Codec in = Codec.reader(want, want.length);
        final Header got = Header.read(in);
        checkNum("V7 decode datagram length equals 40 + payloadLen",
                want.length, Protocol.HEADER_SIZE + got.payloadLen);
        checkNum("V7 decode messageType", Protocol.MSG_REPLY, got.messageType);
        checkNum("V7 decode opCode is echoed", Protocol.OP_QUERY_BY_ID, got.opCode);
        checkNum("V7 decode clientId is echoed", CLIENT_QUERY, got.clientId);
        checkNum("V7 decode requestId is echoed", 8, got.requestId);
        checkNum("V7 decode ackUpTo", Protocol.NO_ACK, got.ackUpTo);
        checkNum("V7 decode status", StatusCode.ERR_UNKNOWN_FLIGHT, got.status);
        checkNum("V7 decode payloadLen", 0, got.payloadLen);
        checkObj("V7 decode header equals the built header", header, got);
        checkNum("V7 decode left no body to read", 0, in.remaining());

        // -- Round trip --
        final byte[] buf2 = new byte[Protocol.MAX_DATAGRAM];
        final Codec out2 = Codec.writer(buf2);
        got.write(out2);
        checkBytes("V7 round-trip re-encode", want, Arrays.copyOf(buf2, out2.position()));
    }

    // ================================================================
    // Negative cases -- decoder rejections, README sec. 3.6 and sec. 11.1
    // ================================================================

    /**
     * A bad string length must be rejected <em>before</em> anything is allocated.
     *
     * <p>The buffers below are exactly four bytes long: the length field and nothing
     * else. That is what makes "allocates nothing" testable rather than merely asserted.
     * An implementation that allocated from the wire length before validating it would
     * fail with an unchecked exception instead - {@code NegativeArraySizeException} or
     * {@code StringIndexOutOfBoundsException} for the negative cases, an out-of-bounds
     * read for the 64 case - and {@link #checkThrows} demands the exact class
     * {@link CodecException}, so any of those is a failure here.
     */
    private static void negStringLength() {
        // 64 = MAX_STRING_BYTES + 1: legal-looking, one past the cap.
        checkThrows("NEG getString rejects length 64", CodecException.class,
                () -> Codec.reader(hex("00 00 00 40"), 4).getString(),
                "outside 0..63");

        // 0xFFFFFFFF: a uint32 whose top bit is set, which is the Java int -1. Reported
        // with Integer.toUnsignedString so the log shows what actually arrived.
        checkThrows("NEG getString rejects length 0xFFFFFFFF", CodecException.class,
                () -> Codec.reader(hex("FF FF FF FF"), 4).getString(),
                "4294967295");

        // Integer.MIN_VALUE = 0x80000000: the most negative length, and the one that
        // wraps if anyone negates it.
        checkThrows("NEG getString rejects length Integer.MIN_VALUE", CodecException.class,
                () -> Codec.reader(hex("80 00 00 00"), 4).getString(),
                "2147483648");
    }

    /** Truncation must throw, never read past the datagram the socket reported. */
    private static void negTruncation() {
        final byte[] v3 = hex(V3_HEX);

        checkThrows("NEG getInt32 on a 3-byte buffer throws", CodecException.class,
                () -> Codec.reader(hex("00 00 00"), 3).getInt32(), "needs 4 bytes");

        checkThrows("NEG getFloat32 on a 2-byte buffer throws", CodecException.class,
                () -> Codec.reader(hex("44 60"), 2).getFloat32(), "needs 4 bytes");

        // Length 9 declared, no content behind it.
        checkThrows("NEG getString with content beyond the limit throws",
                CodecException.class,
                () -> Codec.reader(hex("00 00 00 09"), 4).getString(), "needs 9 bytes");

        // Content present, alignment padding missing: truncated between the string and
        // the next field. The pad must be consumed or every later field shifts.
        checkThrows("NEG getString with missing pad bytes throws", CodecException.class,
                () -> Codec.reader(hex("00 00 00 01 41"), 5).getString(),
                "string body with pad");

        // A reader is bounded by the received length, not by the array's capacity: the
        // array here is a full 68 bytes and the limit says 20, so anything that read
        // past 20 would silently succeed on stale bytes.
        checkThrows("NEG Header.read on a 20-byte datagram throws", CodecException.class,
                () -> Header.read(Codec.reader(v3, 20)), "needs 4 bytes");

        checkThrows("NEG truncated body after a valid header throws", CodecException.class,
                () -> {
                    final Codec in = Codec.reader(v3, 48);
                    Header.read(in);
                    in.getString();
                }, "needs 9 bytes");

        checkThrows("NEG FlightRecord.read on a truncated V1 throws", CodecException.class,
                () -> FlightRecord.read(Codec.reader(hex(V1_HEX), 40)), "needs 4 bytes");

        // A writer that runs out of room throws rather than growing: a body that grew
        // silently would produce a datagram the peer rejects, or one that fragments.
        checkThrows("NEG writer past the end of its array throws", CodecException.class,
                () -> {
                    final Codec out = Codec.writer(new byte[4]);
                    out.putInt32(1);
                    out.putInt32(2);
                }, "needs 4 bytes");
    }

    /** NaN and both infinities are rejected in both directions (README sec. 3.6 step 6). */
    private static void negFloat() {
        final Codec w1 = Codec.writer(new byte[4]);
        checkThrows("NEG putFloat32 rejects NaN", CodecException.class,
                () -> w1.putFloat32(Float.NaN), "not finite");
        // The finiteness test runs before the bounds check and before any byte is
        // written, so a rejected value leaves the cursor exactly where it was - no
        // half-written field for the next call to append to.
        checkNum("NEG putFloat32 NaN left the cursor untouched", 0, w1.position());

        checkThrows("NEG putFloat32 rejects +Infinity", CodecException.class,
                () -> Codec.writer(new byte[4]).putFloat32(Float.POSITIVE_INFINITY),
                "not finite");
        checkThrows("NEG putFloat32 rejects -Infinity", CodecException.class,
                () -> Codec.writer(new byte[4]).putFloat32(Float.NEGATIVE_INFINITY),
                "not finite");

        // The decode side matters more: a corrupt datagram can carry any of the 16
        // million NaN patterns, and a NaN that reached the flight table would break
        // every comparison it touched instead of failing loudly.
        checkThrows("NEG getFloat32 rejects a NaN bit pattern", CodecException.class,
                () -> Codec.reader(hex("7F C0 00 00"), 4).getFloat32(), "not finite");
        checkThrows("NEG getFloat32 rejects +Infinity bits", CodecException.class,
                () -> Codec.reader(hex("7F 80 00 00"), 4).getFloat32(), "not finite");
        checkThrows("NEG getFloat32 rejects -Infinity bits", CodecException.class,
                () -> Codec.reader(hex("FF 80 00 00"), 4).getFloat32(), "not finite");
    }

    /** Header guards, in the order README sec. 3.6 fixes. Each mutates one V3 field. */
    private static void negHeader() {
        final byte[] v3 = hex(V3_HEX);

        // Magic: not necessarily an error, since any process may send to this port. The
        // caller drops these silently rather than replying (README sec. 5).
        checkThrows("NEG Header.read rejects bad magic", CodecException.class,
                () -> Header.read(reader(mutate(v3, OFF_MAGIC, "53 43 36 32"))),
                "bad magic");

        // Version: with two independent implementations, this field is how one side
        // announces it has drifted (D19).
        checkThrows("NEG Header.read rejects an unsupported version", CodecException.class,
                () -> Header.read(reader(mutate(v3, OFF_VERSION, "00 00 00 02"))),
                "unsupported version 2");

        checkThrows("NEG Header.read rejects an unknown messageType", CodecException.class,
                () -> Header.read(reader(mutate(v3, OFF_MESSAGE_TYPE, "00 00 00 04"))),
                "unknown messageType 4");
        checkThrows("NEG Header.read rejects a negative messageType", CodecException.class,
                () -> Header.read(reader(mutate(v3, OFF_MESSAGE_TYPE, "FF FF FF FF"))),
                "unknown messageType -1");

        // Opcode: only a REQUEST is required to name one of the six operations.
        checkThrows("NEG Header.read rejects opCode 7 on a REQUEST", CodecException.class,
                () -> Header.read(reader(mutate(v3, OFF_OPCODE, "00 00 00 07"))),
                "unknown opCode 7 on REQUEST");
        checkThrows("NEG Header.read rejects opCode 0 on a REQUEST", CodecException.class,
                () -> Header.read(reader(mutate(v3, OFF_OPCODE, "00 00 00 00"))),
                "unknown opCode 0 on REQUEST");

        checkThrows("NEG Header.read rejects unknown semantics", CodecException.class,
                () -> Header.read(reader(mutate(v3, OFF_SEMANTICS, "00 00 00 02"))),
                "unknown semantics 2");

        // payloadLen above MAX_BODY: validated before the body is touched, so an absurd
        // length can never reach an allocation. 473 = MAX_BODY + 1.
        checkThrows("NEG Header.read rejects payloadLen above MAX_BODY",
                CodecException.class,
                () -> Header.read(reader(mutate(v3, OFF_PAYLOAD_LEN, "00 00 01 D9"))),
                "payloadLen 473 outside 0..472");
        checkThrows("NEG Header.read rejects a negative payloadLen", CodecException.class,
                () -> Header.read(reader(mutate(v3, OFF_PAYLOAD_LEN, "FF FF FF FF"))),
                "payloadLen -1 outside 0..472");
        checkNum("NEG MAX_BODY is 472", 472, Protocol.MAX_BODY);
    }

    /**
     * The complement of the opcode rejection: {@link Protocol#OP_NONE} is <em>valid</em>
     * on CALLBACK and MONITOR_END, which are not invocations of anything. Applying the
     * 1-6 test to them would reject well-formed traffic, so assert they are accepted.
     */
    private static void posOpNoneAccepted() throws CodecException {
        final Header cb = Header.read(reader(hex(V5_HEX)));
        checkNum("POS Header.read accepts OP_NONE on CALLBACK",
                Protocol.OP_NONE, cb.opCode);
        checkNum("POS the accepted CALLBACK is messageType 2",
                Protocol.MSG_CALLBACK, cb.messageType);

        final Header end = Header.read(reader(hex(V6_HEX)));
        checkNum("POS Header.read accepts OP_NONE on MONITOR_END",
                Protocol.OP_NONE, end.opCode);
        checkNum("POS the accepted MONITOR_END is messageType 3",
                Protocol.MSG_MONITOR_END, end.messageType);

        // And the opcode table itself: 0 is outside the dispatch range, 1-6 are inside.
        checkObj("POS isValidOpCode(OP_NONE) is false",
                Boolean.FALSE, Protocol.isValidOpCode(Protocol.OP_NONE));
        checkObj("POS isValidOpCode(1) is true",
                Boolean.TRUE, Protocol.isValidOpCode(Protocol.OP_QUERY_BY_ROUTE));
        checkObj("POS isValidOpCode(6) is true",
                Boolean.TRUE, Protocol.isValidOpCode(Protocol.OP_SET_FARE));
        checkObj("POS isValidOpCode(7) is false", Boolean.FALSE, Protocol.isValidOpCode(7));
    }

    // ================================================================
    // Assertion helpers
    // ================================================================

    /** Runs one vector, converting an unexpected throw into a single failure. */
    private static void run(String section, Action body) {
        System.out.println("-- " + section + " --");
        try {
            body.run();
        } catch (Throwable t) {
            fail(section + " threw unexpectedly",
                    "no exception",
                    t.getClass().getName() + ": " + t.getMessage());
        }
        System.out.println();
    }

    private static void pass(String name) {
        passed++;
        System.out.println("PASS " + name);
    }

    private static void fail(String name, String expected, String actual) {
        failed++;
        System.out.println("FAIL " + name + ": expected " + expected + " got " + actual);
    }

    private static void checkNum(String name, long expected, long actual) {
        if (expected == actual) {
            pass(name);
        } else {
            fail(name, Long.toString(expected), Long.toString(actual));
        }
    }

    private static void checkObj(String name, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            pass(name);
        } else {
            fail(name, String.valueOf(expected), String.valueOf(actual));
        }
    }

    /**
     * Compares two floats by raw bit pattern.
     *
     * <p>{@code floatToRawIntBits}, not {@code floatToIntBits}: the raw form does not
     * collapse NaN payloads, and value equality is exactly what must not be used here
     * because {@code -0.0f == 0.0f} holds in both languages (vectors.md V2.3, F2).
     */
    private static void checkBits(String name, float expected, float actual) {
        final int e = Float.floatToRawIntBits(expected);
        final int a = Float.floatToRawIntBits(actual);
        if (e == a) {
            pass(name);
        } else {
            fail(name,
                    String.format("%s (0x%08X)", Float.toString(expected), e),
                    String.format("%s (0x%08X)", Float.toString(actual), a));
        }
    }

    /** Byte-for-byte comparison; on failure both buffers are dumped with a diff marker. */
    private static void checkBytes(String name, byte[] expected, byte[] actual) {
        if (Arrays.equals(expected, actual)) {
            pass(name);
            return;
        }
        final int at = firstDifference(expected, actual);
        fail(name,
                expected.length + " bytes " + summary(expected),
                actual.length + " bytes " + summary(actual));
        System.out.println("  first difference at offset " + at
                + " (0x" + String.format("%04X", at) + ")");
        dumpDiff(expected, actual);
    }

    /**
     * Asserts that {@code body} throws exactly {@code type} and that the message names
     * the rule that was broken.
     *
     * <p>The class match is exact, not {@code instanceof}. A decoder that allocated from
     * an unvalidated wire length would throw an unchecked exception instead, and the
     * whole point of these cases is that the failure stays inside the protocol's
     * "log and drop" branch (README sec. 3.6 step 7) rather than unwinding out of the
     * receive loop.
     */
    private static void checkThrows(String name, Class<? extends Throwable> type,
            Action body, String messageFragment) {
        try {
            body.run();
        } catch (Throwable t) {
            if (!type.equals(t.getClass())) {
                fail(name, "exactly " + type.getName(),
                        t.getClass().getName() + ": " + t.getMessage());
                return;
            }
            final String msg = t.getMessage();
            if (messageFragment != null && (msg == null || !msg.contains(messageFragment))) {
                fail(name, type.getSimpleName() + " whose message contains \""
                        + messageFragment + "\"", "message \"" + msg + "\"");
                return;
            }
            pass(name);
            return;
        }
        fail(name, type.getSimpleName(), "no exception");
    }

    // ================================================================
    // Hex and dump utilities
    // ================================================================

    /** Parses whitespace-separated hex into bytes. Whitespace anywhere is ignored. */
    private static byte[] hex(String s) {
        final StringBuilder compact = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                compact.append(c);
            }
        }
        if ((compact.length() & 1) != 0) {
            throw new IllegalArgumentException("odd number of hex digits: " + compact);
        }
        final byte[] out = new byte[compact.length() / 2];
        for (int i = 0; i < out.length; i++) {
            final int hi = digit(compact.charAt(2 * i));
            final int lo = digit(compact.charAt(2 * i + 1));
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int digit(char c) {
        final int d = Character.digit(c, 16);
        if (d < 0) {
            throw new IllegalArgumentException("not a hex digit: " + c);
        }
        return d;
    }

    /** A reader bounded by the array's full length, for the header mutation tests. */
    private static Codec reader(byte[] b) {
        return Codec.reader(b, b.length);
    }

    /** Returns a copy of {@code src} with {@code hexBytes} written at {@code offset}. */
    private static byte[] mutate(byte[] src, int offset, String hexBytes) {
        final byte[] copy = Arrays.copyOf(src, src.length);
        final byte[] patch = hex(hexBytes);
        System.arraycopy(patch, 0, copy, offset, patch.length);
        return copy;
    }

    private static int firstDifference(byte[] a, byte[] b) {
        final int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) {
                return i;
            }
        }
        return n;
    }

    /** First 8 bytes, for the one-line FAIL summary. */
    private static String summary(byte[] b) {
        final StringBuilder sb = new StringBuilder();
        final int n = Math.min(8, b.length);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02X", b[i] & 0xFF));
            if (i < n - 1) {
                sb.append(' ');
            }
        }
        if (b.length > n) {
            sb.append(" ...");
        }
        return "[" + sb + "]";
    }

    /**
     * Prints both buffers as 16-byte rows with a caret row underneath, so the offset of
     * a wrong byte is readable straight off the terminal and can be matched against the
     * offset column in vectors.md. Absent bytes (a length mismatch) print as "--".
     */
    private static void dumpDiff(byte[] expected, byte[] actual) {
        final int max = Math.max(expected.length, actual.length);
        for (int off = 0; off < max; off += 16) {
            System.out.println(String.format("  expected 0x%04X  ", off) + row(expected, off));
            System.out.println(String.format("  actual   0x%04X  ", off) + row(actual, off));
            System.out.println(" ".repeat(19) + markerRow(expected, actual, off));
        }
    }

    private static String row(byte[] b, int off) {
        final StringBuilder sb = new StringBuilder(48);
        for (int i = off; i < off + 16; i++) {
            sb.append(i < b.length ? String.format("%02X", b[i] & 0xFF) : "--");
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String markerRow(byte[] a, byte[] b, int off) {
        final StringBuilder sb = new StringBuilder(48);
        for (int i = off; i < off + 16; i++) {
            final boolean same = i < a.length && i < b.length ? a[i] == b[i]
                    : a.length == b.length;
            sb.append(same ? "   " : "^^ ");
        }
        return sb.toString();
    }
}
