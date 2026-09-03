package sc6103.common;

import java.util.Locale;
import java.util.Objects;

/**
 * A departure instant as the five {@code int32} fields of README sec. 2.2:
 * {@code year}, {@code month}, {@code day}, {@code hour}, {@code minute}, in that
 * order on the wire, 20 bytes total.
 *
 * <p><b>Why five integers rather than an epoch timestamp.</b> D6. The brief asks the
 * group to design its own time representation, so this is graded rather than
 * incidental. Discrete integer fields marshal identically in C++ and in Java with no
 * library on either side, and they are directly readable in the console trace both
 * ends are required to print. A 64-bit epoch value was rejected because {@code time_t}
 * width and signedness vary across platforms while Java's {@code long} does not, and a
 * packed representation ({@code uint16} year, {@code uint8} month) was rejected under
 * D23: narrow types are the main cross-language hazard and twenty bytes is free at
 * this scale.
 *
 * <p><b>No timezone and no seconds are transmitted.</b> README sec. 2.2 puts all times
 * in one agreed zone and README sec. 21 names that zone as UTC, so no offset field
 * exists and no conversion is ever performed on either side. {@link #toString()}
 * therefore prints a literal {@code UTC} suffix: the zone is a property of the
 * protocol, not of the value, and a reader of the log should not have to know that to
 * interpret the number.
 *
 * <p>Immutable: all fields are {@code final} and the class is {@code final}, so an
 * instance can be shared between the flight table and a reply being marshalled without
 * a defensive copy.
 *
 * <p>Exercised by conformance vector V1 at offsets 0x20 to 0x33
 * ({@code tests/conformance/vectors.md}), which pins 2026-10-14 09:30 to the twenty
 * bytes {@code 00 00 07 EA 00 00 00 0A 00 00 00 0E 00 00 00 09 00 00 00 1E}.
 */
public final class DepartureTime {

    // ---- Field ranges, README sec. 2.2 ----
    //
    // These bounds are the specification's, not this class's invention, and they are
    // duplicated independently in the C++ server against README sec. 2.2 rather than
    // against this file (README sec. 11). A date that satisfies them is accepted even
    // if no such calendar day exists - see the note on validate() below.

    private static final int MIN_YEAR = 1970;
    private static final int MAX_YEAR = 9999;
    private static final int MIN_MONTH = 1;
    private static final int MAX_MONTH = 12;
    private static final int MIN_DAY = 1;
    private static final int MAX_DAY = 31;
    private static final int MIN_HOUR = 0;
    private static final int MAX_HOUR = 23;
    private static final int MIN_MINUTE = 0;
    private static final int MAX_MINUTE = 59;

    private final int year;
    private final int month;
    private final int day;
    private final int hour;
    private final int minute;

    /**
     * Creates a departure time, rejecting any field outside the ranges README sec. 2.2
     * fixes.
     *
     * <p>The exception is unchecked because this constructor is reached from our own
     * code with our own values - seed data, a console entry the client has already
     * parsed, a value just decoded and range-checked. A bad argument here is a bug to
     * fix, not a network condition to handle, which is the split
     * {@link CodecException} documents. Untrusted bytes arrive through
     * {@link #read(Codec)}, which converts the same failure into a protocol error.
     *
     * @throws IllegalArgumentException if any field is out of range
     */
    public DepartureTime(int year, int month, int day, int hour, int minute) {
        final String fault = validate(year, month, day, hour, minute);
        if (fault != null) {
            throw new IllegalArgumentException(fault);
        }
        this.year = year;
        this.month = month;
        this.day = day;
        this.hour = hour;
        this.minute = minute;
    }

    // ---- Accessors ----

    /** @return the year, 1970 to 9999. */
    public int year() {
        return year;
    }

    /** @return the month, 1 to 12. */
    public int month() {
        return month;
    }

    /** @return the day of month, 1 to 31. */
    public int day() {
        return day;
    }

    /** @return the hour, 0 to 23, on a 24-hour clock. */
    public int hour() {
        return hour;
    }

    /** @return the minute, 0 to 59. */
    public int minute() {
        return minute;
    }

    // ---- Wire form ----

    /**
     * Writes the five fields in README sec. 2.2 order: year, month, day, hour, minute.
     *
     * <p>Field order is part of the wire contract, not an implementation detail. There
     * are no type tags and no field names on the wire (README sec. 3.3), so the only
     * thing telling the C++ reader that the second {@code int32} is a month is that
     * both sides write them in this sequence. Reordering here would still produce a
     * datagram that decodes without error and means a different date.
     *
     * @param out cursor positioned where the time field begins
     * @throws CodecException if fewer than 20 bytes remain
     */
    public void write(Codec out) throws CodecException {
        out.putInt32(year);
        out.putInt32(month);
        out.putInt32(day);
        out.putInt32(hour);
        out.putInt32(minute);
    }

    /**
     * Reads five {@code int32} fields and validates them as a departure time.
     *
     * <p><b>An out-of-range value here throws {@link CodecException}, never
     * {@link IllegalArgumentException}, and that distinction is the whole point of this
     * method.</b> These bytes came off a UDP socket, so they are untrusted input:
     * README sec. 3.6 step 6 makes "dates form a real calendar date" a decoder
     * validation step, and step 7 requires the outcome to be a status code reply or a
     * logged drop with the server still running. An unchecked exception escaping here
     * would bypass that branch and kill a server the brief requires to keep serving
     * other clients, on nothing more than a corrupted datagram. So the range failure is
     * translated: the same condition that is a bug when it comes from our code is a
     * protocol error when it comes from the wire.
     *
     * <p>All five fields are read before any is validated, so the cursor always ends up
     * past the whole 20-byte field and the message text can name every fault rather
     * than only the first.
     *
     * @param in cursor positioned at the start of the time field
     * @return the decoded time
     * @throws CodecException if the field is truncated or any value is out of range
     */
    public static DepartureTime read(Codec in) throws CodecException {
        final int start = in.position();
        final int y = in.getInt32();
        final int mo = in.getInt32();
        final int d = in.getInt32();
        final int h = in.getInt32();
        final int mi = in.getInt32();

        final String fault = validate(y, mo, d, h, mi);
        if (fault != null) {
            throw new CodecException("time at " + start + ": " + fault);
        }
        return new DepartureTime(y, mo, d, h, mi);
    }

    // ---- Object contract ----

    /**
     * Formats as {@code "2026-10-14 09:30 UTC"}: zero-padded ISO-style date, a space,
     * a 24-hour time, and the zone that README sec. 21 fixes for the whole system.
     *
     * <p>Both ends must print every request and reply they handle (course brief), and
     * this string is what appears in that trace, so it is fixed here rather than left
     * to each call site.
     *
     * <p>{@link Locale#ROOT} is named explicitly. Java's default locale is a property
     * of the machine, and under a locale with non-Latin default digits {@code %02d}
     * emits those digits instead of ASCII ones - which would make the demo log on one
     * laptop unreadable beside the C++ server's on another, for a reason nobody would
     * think to look for. Same class of platform-default hazard as the character set
     * named in {@link Codec#putString(String)} (README sec. 11.1).
     */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d UTC",
                year, month, day, hour, minute);
    }

    /**
     * Value equality over all five fields.
     *
     * <p>Needed because the conformance tests decode a vector and compare the result
     * against the expected value as a whole object; without this they would compare
     * identities and pass on any pair of instances only if they were the same object.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DepartureTime other)) {
            return false;
        }
        return year == other.year
                && month == other.month
                && day == other.day
                && hour == other.hour
                && minute == other.minute;
    }

    @Override
    public int hashCode() {
        return Objects.hash(year, month, day, hour, minute);
    }

    // ---- Internals ----

    /**
     * Checks the five fields against README sec. 2.2 and returns a description of the
     * first violation, or {@code null} if all are in range.
     *
     * <p>Returning the fault rather than throwing is what lets the constructor and
     * {@link #read(Codec)} apply one rule and raise two different exception types (see
     * {@code read}). Duplicating the bounds in both would eventually let them drift.
     *
     * <p><b>Known specification gap, deliberately not closed here.</b> README sec. 2.2
     * gives the day range as 1 to 31 while README sec. 3.6 step 6 asks decoders to
     * verify that "dates form a real calendar date". Those differ: 31 February passes
     * the first and fails the second. This class implements sec. 2.2 literally, because
     * it is the section that states the field ranges and because the C++ server is
     * being written against the same table - a Java side that additionally applied
     * month lengths and leap years would reject datagrams the server considers valid,
     * and the disagreement would surface as an unexplained error only in the
     * cross-machine demo. The stricter rule is a README amendment for the group to make
     * in one place, after which both sides tighten together.
     */
    private static String validate(int year, int month, int day, int hour, int minute) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            return "year " + year + " outside " + MIN_YEAR + ".." + MAX_YEAR;
        }
        if (month < MIN_MONTH || month > MAX_MONTH) {
            return "month " + month + " outside " + MIN_MONTH + ".." + MAX_MONTH;
        }
        if (day < MIN_DAY || day > MAX_DAY) {
            return "day " + day + " outside " + MIN_DAY + ".." + MAX_DAY;
        }
        if (hour < MIN_HOUR || hour > MAX_HOUR) {
            return "hour " + hour + " outside " + MIN_HOUR + ".." + MAX_HOUR;
        }
        if (minute < MIN_MINUTE || minute > MAX_MINUTE) {
            return "minute " + minute + " outside " + MIN_MINUTE + ".." + MAX_MINUTE;
        }
        return null;
    }
}
