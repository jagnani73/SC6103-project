package sc6103.client;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

import sc6103.common.Protocol;
import sc6103.common.StatusCode;

/**
 * The client's half of the protocol trace the brief requires both programs to
 * print: one line per datagram sent, suppressed, discarded or received.
 *
 * <p><b>This class prints the trace and nothing else.</b> The menu, the prompts,
 * the flight tables and the sentences a human is meant to read are the
 * {@code ConsoleUi}'s job and must not appear here. The two streams are
 * interleaved on one console on purpose (docs/console-script.md), so the only
 * thing that keeps them separable is that every line this class emits is a
 * fixed-shape, bracketed protocol line and no line the console prints is.
 *
 * <h2>Why the columns are positional</h2>
 *
 * <p>README sec. 10.2 fixes the field order:
 *
 * <pre>
 *   timestamp  role  event  dir  client  req  opcode  attempt  decision  state
 * </pre>
 *
 * <p>The order is fixed because the demo runs on three machines (README sec. 18)
 * and the report is written from logs captured separately on each of them
 * (README sec. 24.3). A reviewer reconciling a client trace against the server's
 * puts the two files side by side and reads down the same column in both; a
 * free-form message string would make that a manual re-reading exercise instead
 * of a scan. Every field therefore has a fixed position and is padded to a fixed
 * width, and a field that does not apply to an event prints {@code "-"} rather
 * than collapsing and shifting everything after it left.
 *
 * <p>The same section adds a rule this class enforces <em>by construction</em>:
 * never log a bare "failed" or "duplicate", because a line without the client,
 * the request ID and the opcode cannot be correlated against the other two
 * machines' logs. That is why the methods below take those values as parameters
 * instead of taking a message string. A caller physically cannot emit an
 * uncorrelatable per-request line through this API; the two escape hatches,
 * {@link #note(String, String)} and {@link #warn(String)}, exist only for
 * session-level events that genuinely have no request to name.
 *
 * <p>Opcode and status names come from {@link Protocol#opCodeName(int)} and
 * {@link StatusCode#name(int)} rather than being spelled out here. The C++
 * server logs the same constants from its own independent copy of the tables
 * (README sec. 11), and side-by-side reading only works if the two agree
 * textually, not merely numerically.
 *
 * <h2>The timestamp is not a clock</h2>
 *
 * <p>The leading timestamp is a <b>wall-clock</b> reading, and it is for human
 * eyes only. No value produced by this class may ever feed a protocol decision.
 * README sec. 6.1 and sec. 21 require every timeout, retry deadline and monitor
 * expiry to be measured on a <em>monotonic</em> clock ({@code System.nanoTime()}),
 * so that an NTP correction or a manual clock change during a demo cannot make a
 * timeout fire early, fire late, or - if the clock steps backwards - never fire
 * at all. Reading {@link LocalTime#now()} here is safe precisely because the
 * result is formatted into a string and then thrown away.
 *
 * <p>Not thread-safe, and does not need to be: the client is single-threaded,
 * including in monitor mode where it blocks on the socket rather than spawning a
 * listener (README sec. 7.3, D11).
 */
public final class TraceLog {

    /**
     * The {@code role} column. Constant for this class - the server writes
     * {@code SERVER} in the same position, which is what lets a reviewer
     * concatenate two logs and still tell the lines apart.
     */
    private static final String ROLE = "CLIENT";

    /** Printed in any column that does not apply to an event. See the class note. */
    private static final String NONE = "-";

    // ---- dir column, README sec. 10.2 ----

    /** Outbound: a REQUEST datagram. */
    private static final String DIR_REQUEST = "request";

    /** Inbound: a REPLY datagram, or the absence of one at a timeout. */
    private static final String DIR_REPLY = "reply";

    /** Inbound: an unsolicited CALLBACK (README sec. 9.2). */
    private static final String DIR_CALLBACK = "callback";

    /** Inbound: the advisory MONITOR_END (README sec. 9.3, D20). */
    private static final String DIR_MONITOR_END = "monitor-end";

    // ---- Column widths ----
    //
    // Each is the longest value that column can legitimately hold, so a normal
    // line needs no truncation and the columns line up. Over-long values are
    // never cut short - see pad().

    /** {@code "[MONITOR_END]"}, the longest bracketed event token. */
    private static final int W_EVENT = 13;

    /** {@code "monitor-end"}. */
    private static final int W_DIR = 11;

    /** Five digits of {@code requestId}; a demo session sends far fewer. */
    private static final int W_REQ = 5;

    /** {@code "OP_QUERY_BY_ROUTE"}, the longest name {@link Protocol#opCodeName(int)} returns. */
    private static final int W_OPCODE = 17;

    /** Attempt numbers are bounded by the retry limit (D13), so two digits plus slack. */
    private static final int W_ATTEMPT = 3;

    /** {@code "forced-first-request"}, the longest fault-rule name (README sec. 10.1). */
    private static final int W_DECISION = 20;

    /**
     * {@code HH:mm:ss.SSS}, matching the worked example in README sec. 10.2.
     *
     * <p>Pinned to {@link Locale#ROOT}: a default locale with a non-Latin
     * numbering system would render the digits in that script, and a log that
     * cannot be diffed against the C++ server's ASCII output defeats the purpose
     * of the whole schema. The same reasoning is why the integer columns below
     * are built with {@link Integer#toString(int)} rather than
     * {@code String.format}, which follows the default locale.
     */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

    /** Destination for trace lines. Typically {@code System.out}, or a redirected log file. */
    private final PrintStream out;

    /**
     * This client's identity, pre-rendered as {@code 0x0000A31F}.
     *
     * <p>Formatted once because it never changes: {@code clientId} is fixed for
     * the client's lifetime (README sec. 2.3, D5) and appears on every single
     * line. Hexadecimal because that is how the startup banner prints it
     * (docs/console-script.md sec. 1) and how a foreign ID appears in an
     * {@link #ignore(String, String)} line, so the three renderings match.
     */
    private final String clientHex;

    /**
     * @param out      where trace lines go; may be {@code System.out} or a file
     *                 stream when capturing evidence (README sec. 24.3)
     * @param clientId this client's {@code clientId}. Held as a {@code long}
     *                 because the wire field is a {@code uint32} and Java has no
     *                 unsigned 32-bit type (README sec. 11.1); only the low 32
     *                 bits are printed
     */
    public TraceLog(PrintStream out, long clientId) {
        this.out = Objects.requireNonNull(out, "out");
        this.clientHex = hex32(clientId);
    }

    /**
     * A request datagram left the client.
     *
     * <p>Emitted once per attempt, including the first. The {@code attempt}
     * column is what makes a retransmission visible as a retransmission rather
     * than as a second request, which matters because the server is expected to
     * recognise it as a duplicate under at-most-once (README sec. 8.2).
     *
     * @param attempt  1-based, so the first transmission reads {@code attempt=1}
     *                 exactly as in docs/console-script.md sec. 3
     * @param decision outcome, normally {@code "transmitted"} (README sec. 10.2).
     *                 A suppressed datagram is not a send - use
     *                 {@link #drop(int, int, String, String)}
     */
    public void send(int requestId, int opCode, int attempt, String decision) {
        emit("SEND", DIR_REQUEST, requestId, opCode, attempt, decision, NONE);
    }

    /**
     * The identical datagram is going out again after a timeout.
     *
     * <p>The {@code state} column carries {@code identical-bytes} because that is
     * the load-bearing fact of README sec. 7.2: the same encoded body and the same
     * {@code (clientId, requestId)} go out on every attempt. If either changed,
     * the server could not recognise the duplicate and at-most-once would be
     * unimplementable. The trace has to make the reuse visible, or the demo shows
     * duplicate suppression working without showing what it is suppressing.
     *
     * @param attempt the number of the attempt now being sent, so a retry after
     *                the first transmission reads {@code 2}
     */
    public void retry(int requestId, int opCode, int attempt) {
        emit("RETRY", DIR_REQUEST, requestId, opCode, attempt, "timeout", "identical-bytes");
    }

    /**
     * No reply arrived before this attempt's deadline.
     *
     * <p>{@code dir} is {@code reply} because the line is about the reply that did
     * not come, not about the request that did go. The decision is recorded as
     * {@code no-reply} and never as {@code failed}: a lost request means the
     * operation never ran, a lost reply means it ran and the answer vanished, and
     * the client cannot tell which (README sec. 7.2). Writing "failed" in the log
     * would assert something the client does not know.
     *
     * @param afterMs the elapsed wait, measured on the monotonic clock by the
     *                caller - see the class note on why the timestamp column is
     *                not a substitute for it
     */
    public void timeout(int requestId, int opCode, int attempt, int afterMs) {
        emit("TIMEOUT", DIR_REPLY, requestId, opCode, attempt, "no-reply",
                "after=" + Integer.toString(afterMs) + "ms");
    }

    /**
     * A reply matching this client's outstanding {@code (clientId, requestId)}
     * was accepted.
     *
     * <p>The {@code decision} column is the one a marker watches. Under
     * at-most-once a reply to a retransmission is served from the server's reply
     * history and the client records {@code cache-replay}; under at-least-once
     * the same sequence means the operation ran a second time and it records
     * {@code re-executed} (docs/console-script.md sec. 4). Printing that
     * distinction is what turns duplicate suppression into something observable
     * during the demo instead of a claim in the report.
     *
     * <p>The reply's {@code status} goes in the {@code state} column - it is the
     * only state the client learns from an exchange, the server having spent that
     * column on its own seat count. It is rendered through
     * {@link StatusCode#name(int)} so an unrecognised code from a drifted peer
     * still reaches the log as a number rather than being swallowed.
     *
     * @param attempt  the attempt this reply answers, so a {@code cache-replay} on
     *                 attempt 2 reads as one line
     * @param decision {@code "cache-replay"}, {@code "re-executed"}, or
     *                 {@code null} when the semantics in force make the
     *                 distinction meaningless
     */
    public void recv(int requestId, int opCode, int attempt, int status, String decision) {
        emit("RECV", DIR_REPLY, requestId, opCode, attempt, decision,
                "status=" + StatusCode.name(status));
    }

    /**
     * A datagram was suppressed by this client's own fault injection.
     *
     * <p>Injected loss is logged so that a demo trace reads as a narrative rather
     * than as an unexplained gap (docs/console-script.md sec. 4). The gate fires
     * <em>after</em> encoding (README sec. 10), so the codec has run on exactly
     * the path it would have run on had the datagram been sent - which is the
     * whole point of injecting in-process rather than with an external tool (D12).
     *
     * <p>README sec. 10 also asks for the attempt number on a suppression line.
     * This method does not take one; the {@code SEND} or {@code RETRY} line for
     * the same {@code requestId} immediately above it supplies that column.
     *
     * @param dir    {@code "request"} or {@code "reply"} - the client has exactly
     *               those two gates ({@code --drop-request},
     *               {@code --drop-reply}, README sec. 10). The literals are the
     *               {@code dir} column's vocabulary and must match the server's
     *               spelling of the same two words
     * @param reason the rule that fired, for example {@code "forced-first-request"}
     *               for a one-shot rule or {@code "bernoulli"} for the seeded
     *               probability gate (README sec. 10.1)
     */
    public void drop(int requestId, int opCode, String dir, String reason) {
        emit("DROP", dir, Integer.toString(requestId), Protocol.opCodeName(opCode),
                NONE, reason, NONE);
    }

    /**
     * An inbound datagram was discarded without being treated as this request's
     * reply.
     *
     * <p>Covers the two discard cases of README sec. 7.2 and
     * docs/console-script.md sec. 4:
     *
     * <ul>
     *   <li>a <b>stale</b> reply - a reply to an earlier attempt or an earlier
     *       request arriving after the client has moved on. Taking it as the
     *       answer to the current request would silently pair a reply with the
     *       wrong question;</li>
     *   <li>a <b>foreign</b> datagram - one carrying another client's
     *       {@code clientId}. {@code clientId} is client-asserted and unverified
     *       (D5), so this is a routine occurrence on a shared LAN during the
     *       three-machine demo, not an attack.</li>
     * </ul>
     *
     * <p>This is the one event whose {@code req} and {@code opcode} columns are
     * necessarily empty: the identifiers on the discarded datagram are not this
     * exchange's identifiers, and printing them in the correlation columns would
     * make a search by request ID join two unrelated conversations. Put them in
     * {@code detail} instead - {@code "awaiting req=27"},
     * {@code "clientId=0x0000B004"} - so the line still says which datagram was
     * dropped. The {@code client} column keeps the line anchored to this client's
     * log, which is what README sec. 10.2 requires it for.
     *
     * @param reason short discard cause, {@code "stale"} or {@code "foreign"}
     * @param detail the discarded datagram's identifiers, as free text
     */
    public void ignore(String reason, String detail) {
        emit("IGNORE", DIR_REPLY, NONE, NONE, NONE, reason, detail);
    }

    /**
     * A callback arrived from a flight this client is monitoring.
     *
     * <p>Callbacks are fire-and-forget: never acknowledged, never retried, and
     * exhibiting maybe-semantics by design (README sec. 9.2, D10). They therefore
     * carry no {@code requestId} of this client's own and their header opcode is
     * {@link Protocol#OP_NONE}, which is what the {@code opcode} column prints -
     * from {@link Protocol#opCodeName(int)}, so it matches the server's word for
     * the same placeholder.
     *
     * <p>{@code eventRequestId} identifies the {@code RESERVE} that caused the
     * update, and it belongs to <em>another</em> client. It is deliberately kept
     * out of the {@code req} column, which means "this client's request", and put
     * in {@code state} instead. It is worth logging because the same
     * {@code eventRequestId} appearing under two sequence numbers is the visible
     * signature of an at-least-once re-execution on the reserving client
     * (docs/console-script.md sec. 5).
     *
     * <p>Gaps in {@code callbackSeq} are not detected here. This class records
     * what arrived; the console reports the gap in prose, and
     * {@link #warn(String)} is available if the gap should also appear in the
     * trace.
     */
    public void callback(int flightId, int callbackSeq, int seats, int eventRequestId) {
        emit("CALLBACK", DIR_CALLBACK, NONE, Protocol.opCodeName(Protocol.OP_NONE), NONE,
                "received",
                "seq=" + Integer.toString(callbackSeq)
                        + " flight=" + Integer.toString(flightId)
                        + " seats=" + Integer.toString(seats)
                        + " event=req" + Integer.toString(eventRequestId));
    }

    /**
     * The server announced that a monitor registration has expired.
     *
     * <p>{@code finalSeq} is the total number of callbacks the server sent, which
     * lets the client report "3 of 4 received" rather than only the gaps it
     * happened to notice between updates that did arrive.
     *
     * <p>MONITOR_END is advisory and can itself be lost (D20), so its absence is
     * not an error and the client's own monotonic deadline stays authoritative
     * (README sec. 7.3). A monitor that ends on the local timer produces no line
     * from this method at all - the console says so instead.
     */
    public void monitorEnd(int flightId, int finalSeq) {
        emit("MONITOR_END", DIR_MONITOR_END, NONE, Protocol.opCodeName(Protocol.OP_NONE),
                NONE, "received",
                "flight=" + Integer.toString(flightId)
                        + " final-seq=" + Integer.toString(finalSeq));
    }

    /**
     * A session-level event with no request to correlate against.
     *
     * <p>For facts that frame how the rest of the log should be read and that
     * README sec. 24.3 wants inside the captured file: the startup configuration,
     * the bound local port, the seed and active fault rules, the closing request
     * count. Anything belonging to a particular exchange must go through the
     * typed methods above, which force the correlation columns to be filled -
     * routing a per-request event through here would produce exactly the
     * uncorrelatable line README sec. 10.2 prohibits.
     *
     * @param event short event name; upper-cased and bracketed to match the rest
     *              of the trace
     */
    public void note(String event, String text) {
        emit(upper(event), NONE, NONE, NONE, NONE, NONE, text);
    }

    /**
     * A session-level fault or anomaly.
     *
     * <p>Intended for the protocol faults of README sec. 5 codes 6-12, which the
     * user cannot fix by retyping ({@link StatusCode#isProtocolFault(int)}), and
     * for anomalies worth recording in the trace such as a gap in
     * {@code callbackSeq}. The status code itself still reaches the log through
     * the {@link #recv(int, int, int, int, String)} line for the reply that
     * carried it, so this line adds explanation rather than replacing the
     * correlated record.
     */
    public void warn(String text) {
        emit("WARN", NONE, NONE, NONE, NONE, NONE, text);
    }

    /**
     * Convenience overload for the common case where the request context is a
     * live {@code (requestId, opCode, attempt)} triple, rendering each column and
     * delegating to the string form.
     */
    private void emit(String event, String dir, int requestId, int opCode, int attempt,
                      String decision, String state) {
        emit(event, dir, Integer.toString(requestId), Protocol.opCodeName(opCode),
                Integer.toString(attempt), decision, state);
    }

    /**
     * Writes exactly one line in the fixed column order of README sec. 10.2.
     *
     * <p>Single point of assembly on purpose: the order of the ten fields is the
     * contract with the server's log, so it is expressed once here rather than
     * repeated in ten call sites where one of them would eventually transpose two
     * columns. A transposition is invisible in the client's own log and only
     * surfaces when someone tries to reconcile it against another machine's,
     * which is after the demo.
     *
     * <p>The stream is flushed on every line. The evidence package is captured by
     * redirecting this stream to a file (README sec. 24.3), and the lines most
     * worth having are the last ones before a crash or a Ctrl-C - exactly the ones
     * a buffered stream would discard.
     */
    private void emit(String event, String dir, String req, String opCode, String attempt,
                      String decision, String state) {
        final StringBuilder sb = new StringBuilder(128);
        sb.append(LocalTime.now().format(TIME_FORMAT)).append(' ');
        sb.append(ROLE).append(' ');
        sb.append(pad("[" + event + "]", W_EVENT)).append(' ');
        sb.append(pad(dir, W_DIR)).append(' ');
        sb.append(clientHex).append(' ');
        sb.append(pad(req, W_REQ)).append(' ');
        sb.append(pad(opCode, W_OPCODE)).append(' ');
        sb.append(pad(attempt, W_ATTEMPT)).append(' ');
        sb.append(pad(decision, W_DECISION)).append(' ');
        sb.append(oneLine(state));
        out.println(sb);
        out.flush();
    }

    /**
     * Left-justifies a column value to {@code width}, substituting {@code "-"} for
     * a null or blank value.
     *
     * <p><b>Never truncates.</b> An over-long value pushes the columns after it
     * right on that one line, which is ugly; cutting it short would instead
     * produce a shortened opcode or status name that no longer matches the
     * server's spelling of the same constant, and silent textual divergence is
     * the failure this whole schema exists to prevent. Ugly and correct beats
     * tidy and wrong.
     */
    private static String pad(String value, int width) {
        final String text = oneLine(value);
        if (text.length() >= width) {
            return text;
        }
        final StringBuilder sb = new StringBuilder(width);
        sb.append(text);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Collapses a value to a single line, mapping null or blank to {@code "-"}.
     *
     * <p>One event is one line, without exception: a value containing a newline
     * would put the columns after it at the start of the next line, where a reader
     * scanning down the {@code decision} column - or a {@code grep} for a request
     * ID - would silently miss it. Tabs go too, because they would re-flow the
     * padding this class just computed in spaces.
     */
    private static String oneLine(String value) {
        if (value == null) {
            return NONE;
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return NONE;
        }
        return trimmed.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

    /**
     * Renders the low 32 bits of a {@code clientId} as {@code 0x0000A31F}.
     *
     * <p>Written by hand rather than with {@code String.format("%08X", ...)} for
     * the locale reason given on {@link #TIME_FORMAT}, and zero-padded to a fixed
     * eight digits so the {@code client} column keeps its width whatever value the
     * client drew at startup. The mask discards the sign extension that would
     * otherwise print sixteen digits for an ID above {@code 0x7FFFFFFF}, which is
     * half of the random range D5 draws from.
     */
    private static String hex32(long clientId) {
        final String digits = Long.toHexString(clientId & 0xFFFFFFFFL).toUpperCase(Locale.ROOT);
        final StringBuilder sb = new StringBuilder(10);
        sb.append("0x");
        for (int i = digits.length(); i < 8; i++) {
            sb.append('0');
        }
        sb.append(digits);
        return sb.toString();
    }

    /** Upper-cases a caller-supplied event name in a locale-independent way. */
    private static String upper(String event) {
        return oneLine(event).toUpperCase(Locale.ROOT);
    }
}
