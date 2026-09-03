package sc6103.client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

import sc6103.common.Protocol;

/**
 * The client's parsed command line, and the startup banner built from it.
 *
 * <p>Immutable, and fully validated before the object exists: every field is
 * inside its permitted range by the time a {@code ClientConfig} is returned, so
 * the request/reply loop of README sec. 7.2 never has to defend against a
 * negative timeout or a probability above 1. A partially-valid config is not a
 * state this class can be in - {@link #parse(String[])} either returns a usable
 * one or throws.
 *
 * <h2>Why every startup fact reaches the console</h2>
 *
 * <p>The sec. 13 experiments are only worth reporting if a run can be replayed,
 * and README sec. 24.3 requires the captured logs to carry the command line, the
 * seed and the active fault rules alongside the transcript. A log that records
 * "at-most-once, 20 trials, 3 divergences" without its seed and its two loss
 * rates cannot be re-run, cannot be checked by a marker, and cannot be defended
 * in the report. {@link #banner} therefore prints the entire configuration, not
 * the parts that seem interesting - the banner <em>is</em> the log's header, and
 * it is written once at startup where nothing can have changed it yet.
 *
 * <h2>Why an unrecognised flag is fatal</h2>
 *
 * <p>{@link #parse(String[])} rejects any argument it does not recognise instead
 * of skipping it. The failure mode this prevents is specific and expensive: a
 * mistyped {@code --drop-replies 0.5} that is silently ignored leaves the run at
 * the 0.0 default, so experiment E3 "passes" without ever having lost a reply.
 * That result looks valid, goes into the report, and means nothing - the same
 * class of silent-agreement failure that D19 puts a {@code semantics} guard in
 * the header to prevent. Every argument is either understood or fatal.
 *
 * <p>This class carries the seed; it does not own a PRNG. The transport layer
 * seeds its own generator from {@link #seed} so that the two client-side drop
 * gates draw from one reproducible stream (D25).
 */
public final class ClientConfig {

    // ---- Defaults (README sec. 15.2, D13) ----

    /**
     * D13: ample on a LAN, and short enough that a live retry demonstration stays
     * watchable. Fixed rather than adaptive so retransmission counts are
     * comparable across trials and across the two semantics.
     */
    private static final int DEFAULT_TIMEOUT_MS = 750;

    /** D13. Bounded, so a downed server is a diagnosable error and not a hang. */
    private static final int DEFAULT_MAX_RETRIES = 3;

    /**
     * At-most-once by default (README sec. 15.2). The safe mode is the default and
     * the corrupting one has to be asked for by name, which also means an operator
     * who forgot the flag entirely is running the mode the report calls correct.
     */
    private static final int DEFAULT_SEMANTICS = Protocol.SEM_AT_MOST_ONCE;

    // ---- Banner geometry, fixed by docs/console-script.md sec. 1 ----

    private static final String TITLE = "SC6103 Flight Information Client";

    /**
     * Width of the two divider rules, counted from the sample banner in
     * docs/console-script.md sec. 1. The client and server transcripts are read
     * side by side during the demo, so the rule width is part of the layout
     * contract rather than a matter of taste.
     */
    private static final int RULE_WIDTH = 62;

    /**
     * Two-space indent, then a 12-column label field, which puts every value at
     * column 14 exactly as the sample banner shows. Held as one format string so
     * the indent and the label width cannot drift apart.
     */
    private static final String ROW_FORMAT = "  %-12s%s";

    /** Spelling of {@link Protocol#SEM_AT_LEAST_ONCE} on the command line and in the banner. */
    private static final String SEMANTICS_AT_LEAST_ONCE = "at-least-once";

    /** Spelling of {@link Protocol#SEM_AT_MOST_ONCE} on the command line and in the banner. */
    private static final String SEMANTICS_AT_MOST_ONCE = "at-most-once";

    /** Server address, already resolved. Never null. */
    public final InetAddress host;

    /** Server UDP port, 1..65535. */
    public final int port;

    /**
     * {@link Protocol#SEM_AT_LEAST_ONCE} or {@link Protocol#SEM_AT_MOST_ONCE},
     * placed in the header of every request so the server can refuse a client
     * running in the opposite mode (D19).
     *
     * <p>There is no third value and no "unset": {@code --semantics} accepts the
     * two exact strings and nothing else. A client that quietly fell back to a
     * default after a typo would make {@code ERR_SEMANTICS_MISMATCH} fire from the
     * server with no visible cause on the client's own command line, which is the
     * most confusing possible presentation of a guard whose entire purpose is to
     * be legible.
     */
    public final int semantics;

    /**
     * Probability of discarding an outgoing request after encoding and before
     * {@code sendto} (README sec. 10).
     *
     * <p>Client-side injection exists for single-machine development and for the
     * deliberate compose-both cases. The sec. 13 experiments use <em>server-side
     * injection only</em>, because a server-side reply drop means the operation
     * genuinely executed and the client genuinely did not hear - the exact
     * scenario that separates the two semantics. Setting this during an experiment
     * composes with the server's gate and confounds the measured rate, which is
     * why it appears in the banner: the log has to show it was zero.
     */
    public final double dropRequestRate;

    /** Probability of discarding a matched reply after {@code receive}. See {@link #dropRequestRate}. */
    public final double dropReplyRate;

    /**
     * One-shot rule: drop the first request transmission, then log
     * {@code RULE_CONSUMED} and stop firing (README sec. 10.1, D25).
     *
     * <p>Probabilistic loss is the wrong instrument for a live demonstration. At
     * <i>p</i> = 0.5 the drop that makes the graded point may simply not happen
     * while a marker is watching, and hunting for a seed that drops at the right
     * moment produces a seed that stops working the instant the demo script
     * changes order.
     */
    public final boolean dropFirstRequest;

    /**
     * Milliseconds to wait for a matching reply before retransmitting.
     *
     * <p>Not independent of the server: D8 requires the server's reply history to
     * outlive a client's full retry window, {@code HISTORY_TTL_MS > timeoutMs x
     * (maxRetries + 1)}, and that check runs on the server against the server's
     * own numbers. A client started with a large {@code --timeout} against an
     * already-running server can therefore violate the constraint from the outside,
     * with no way to detect it locally - the banner is what makes the violation
     * visible afterwards in the captured log.
     */
    public final int timeoutMs;

    /**
     * Retransmissions after the first attempt, so the total number of
     * transmissions is {@code maxRetries + 1}: README sec. 7.2 loops
     * {@code for attempt in 0 .. maxRetries} inclusive.
     */
    public final int maxRetries;

    /**
     * Seed for the client's loss-simulation PRNG, defaulting to
     * {@link System#nanoTime()} and always printed.
     *
     * <p>Defaulting to a fixed constant would make every run identical and hide
     * seed-dependent behaviour; defaulting to something unrecorded would make no
     * run repeatable. Choosing a fresh seed and then printing it gives both, which
     * is what README sec. 24.3 asks the evidence package to carry.
     *
     * <p>{@code nanoTime}'s origin is arbitrary, so this value is frequently
     * negative. That is not a fault: it is printed as a plain signed decimal and
     * {@code --seed} parses the same form back, so a seed copied out of a log
     * always reproduces the run it came from.
     */
    public final long seed;

    private ClientConfig(InetAddress host, int port, int semantics,
                         double dropRequestRate, double dropReplyRate,
                         boolean dropFirstRequest, int timeoutMs, int maxRetries,
                         long seed) {
        this.host = host;
        this.port = port;
        this.semantics = semantics;
        this.dropRequestRate = dropRequestRate;
        this.dropReplyRate = dropReplyRate;
        this.dropFirstRequest = dropFirstRequest;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.seed = seed;
    }

    /**
     * Parses the client command line of README sec. 15.2.
     *
     * <p>{@code --host} and {@code --port} are required; everything else has the
     * documented default. A flag given more than once takes its last value, which
     * is deliberate: the sec. 13 harness runs N = 20 trials by appending a fresh
     * {@code --seed} to one base command line, and last-wins is what makes that
     * work. Unknown flags are still fatal, so the concession costs nothing.
     *
     * @param args the raw {@code main} arguments
     * @return a fully validated configuration
     * @throws IllegalArgumentException on any malformed, out-of-range, missing or
     *         unrecognised argument. The message names the offending flag and its
     *         value, and does not include the usage block - the caller is expected
     *         to print {@link #usage()} after it.
     *         <p><b>{@code --help} is reported through this same exception, with
     *         the message set to exactly {@link #usage()}.</b> There is no other
     *         way out of a method whose only return type is a configuration, and a
     *         caller distinguishes the two cases with
     *         {@code usage().equals(e.getMessage())} to pick its exit status.
     */
    public static ClientConfig parse(String[] args) {
        Objects.requireNonNull(args, "args");

        InetAddress host = null;
        int port = -1;
        int semantics = DEFAULT_SEMANTICS;
        double dropRequestRate = 0.0;
        double dropReplyRate = 0.0;
        boolean dropFirstRequest = false;
        int timeoutMs = DEFAULT_TIMEOUT_MS;
        int maxRetries = DEFAULT_MAX_RETRIES;
        long seed = System.nanoTime();

        // Hand-rolled, because a two-flag argument grammar does not justify a
        // dependency and this project ships as a single source archive with no
        // dependency manifest (README sec. 15.1).
        int i = 0;
        while (i < args.length) {
            final String flag = args[i];
            switch (flag) {
                case "--help":
                    throw new IllegalArgumentException(usage());
                case "--drop-first-request":
                    // The only valueless flag, so it advances by one rather than two.
                    dropFirstRequest = true;
                    i += 1;
                    break;
                case "--host":
                    host = parseHost(value(args, i));
                    i += 2;
                    break;
                case "--port":
                    port = parsePort(value(args, i));
                    i += 2;
                    break;
                case "--semantics":
                    semantics = parseSemantics(value(args, i));
                    i += 2;
                    break;
                case "--drop-request":
                    dropRequestRate = parseProbability(flag, value(args, i));
                    i += 2;
                    break;
                case "--drop-reply":
                    dropReplyRate = parseProbability(flag, value(args, i));
                    i += 2;
                    break;
                case "--timeout":
                    timeoutMs = parsePositiveInt(flag, value(args, i));
                    i += 2;
                    break;
                case "--retries":
                    maxRetries = parseRetries(flag, value(args, i));
                    i += 2;
                    break;
                case "--seed":
                    seed = parseSeed(value(args, i));
                    i += 2;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "unknown argument \"" + flag + "\"; run with --help for the flag list");
            }
        }

        if (host == null) {
            throw new IllegalArgumentException("--host is required; the client is told the server's "
                    + "address on the command line (README sec. 15.2)");
        }
        if (port < 0) {
            throw new IllegalArgumentException("--port is required and must match the server's --port");
        }
        return new ClientConfig(host, port, semantics, dropRequestRate, dropReplyRate,
                dropFirstRequest, timeoutMs, maxRetries, seed);
    }

    /**
     * The flag list, for {@code --help} and for printing after a parse failure.
     *
     * <p>Reproduces the client block of README sec. 15.2, including the program
     * name used there. It is deliberately not the real {@code java -cp out ...}
     * invocation: the two implementations are described by one specification, and
     * a usage message that drifts from the specification it documents is worse
     * than no usage message.
     */
    public static String usage() {
        return String.join("\n",
                "Usage:",
                "  flight_client --host <server-ip> --port <port>",
                "                [--semantics at-least-once|at-most-once]  must match the server;"
                        + " default at-most-once",
                "                [--drop-request <p>] [--drop-reply <p>]   default 0.0",
                "                [--drop-first-request]                    one-shot",
                "                [--timeout <ms>]                          default 750",
                "                [--retries <n>]                           default 3",
                "                [--seed <n>]                              default: a fresh seed,"
                        + " printed in the banner",
                "                [--help]",
                "",
                "Use the server machine's LAN address, not a loopback address, and open the UDP",
                "port inbound on every firewall between the two (README sec. 15.2).");
    }

    /**
     * Renders the startup banner of docs/console-script.md sec. 1.
     *
     * <p>Everything on it is a startup-time fact that decides how the rest of the
     * transcript should be read, which is why it is emitted before the first menu
     * draw and never again. The layout is fixed by that document rather than
     * chosen here, so a marker comparing a client log against a server log finds
     * the same fields in the same columns.
     *
     * <p>All formatting goes through {@link Locale#ROOT}. Java's default locale is
     * a property of whichever machine happens to run the client, and in a
     * comma-decimal locale a loss rate would print as {@code 0,50} - which no
     * longer feeds back into {@code --drop-reply}, breaking the replay that
     * README sec. 24.3 exists to guarantee. The three demo machines are not
     * assumed to share a locale.
     *
     * <p>Line endings are {@code \n} throughout and there is no trailing newline;
     * the caller supplies its own line break. Fixing the separator keeps the
     * banner byte-identical across the Windows and Linux machines of the sec. 15.2
     * multi-host demo.
     *
     * @param localEndpoint the client's own bound address, normally
     *                      {@code 0.0.0.0} plus the ephemeral port the OS chose.
     *                      Read from the socket after binding rather than guessed:
     *                      that port is where the server will address callbacks
     *                      (README sec. 9.2), so it belongs in the log verbatim
     * @param clientId      the random nonzero {@code uint32} of README sec. 3.4,
     *                      held in a {@code long} because Java has no unsigned int
     * @return the multi-line banner
     */
    public String banner(InetSocketAddress localEndpoint, long clientId) {
        Objects.requireNonNull(localEndpoint, "localEndpoint");

        final String rule = "-".repeat(RULE_WIDTH);
        final StringBuilder sb = new StringBuilder();
        sb.append(TITLE).append('\n');
        sb.append(rule).append('\n');
        row(sb, "server", host.getHostAddress() + ":" + port);
        row(sb, "local", endpointText(localEndpoint));
        // Masked to 32 bits before formatting: the field is a uint32 carried in a
        // long, and an unmasked negative would render as sixteen hex digits and no
        // longer match the id the server logs for the same client.
        row(sb, "client id", String.format(Locale.ROOT, "0x%08X", clientId & 0xFFFFFFFFL));
        row(sb, "semantics", semanticsName(semantics));
        row(sb, "timeout", timeoutMs + " ms, " + maxRetries + " retries");
        row(sb, "loss", String.format(Locale.ROOT, "request %.2f  reply %.2f",
                dropRequestRate, dropReplyRate));
        // README sec. 10.1 requires active fault rules to be printed at startup and
        // recorded with the captured logs, so a scenario can be replayed exactly. A
        // one-shot rule changes what the trace means, and D25 makes it the mechanism a
        // live demonstration should use - a banner that omitted it would leave the most
        // load-bearing flag of the demo invisible in the evidence package.
        row(sb, "rules", dropFirstRequest ? "drop-first-request" : "none");
        row(sb, "seed", Long.toString(seed));
        sb.append(rule);
        return sb.toString();
    }

    // ---- Banner helpers ----

    private static void row(StringBuilder sb, String label, String value) {
        sb.append(String.format(Locale.ROOT, ROW_FORMAT, label, value)).append('\n');
    }

    /**
     * {@code address:port} for the banner's {@code local} row.
     *
     * <p>Prints the numeric address, never a reverse-resolved name: a name in a
     * log is a claim about DNS at read time, whereas the demo needs the address
     * the socket actually bound. {@code getAddress()} is null only for an
     * unresolved placeholder, which a bound socket never produces - handled anyway
     * because a banner that throws while reporting the configuration destroys the
     * evidence it was printed to create.
     */
    private static String endpointText(InetSocketAddress endpoint) {
        final InetAddress address = endpoint.getAddress();
        final String text = (address == null) ? endpoint.getHostString() : address.getHostAddress();
        return text + ":" + endpoint.getPort();
    }

    private static String semanticsName(int semantics) {
        return (semantics == Protocol.SEM_AT_LEAST_ONCE)
                ? SEMANTICS_AT_LEAST_ONCE
                : SEMANTICS_AT_MOST_ONCE;
    }

    // ---- Argument helpers ----

    /**
     * The value following the flag at {@code flagIndex}.
     *
     * <p>A flag at the end of the argument list is an error rather than a flag
     * with an empty value, because {@code --timeout} with nothing after it is
     * almost always a shell quoting accident and silently defaulting it would
     * change the retry window without saying so.
     */
    private static String value(String[] args, int flagIndex) {
        if (flagIndex + 1 >= args.length) {
            throw new IllegalArgumentException(args[flagIndex] + " requires a value");
        }
        return args[flagIndex + 1];
    }

    /**
     * Resolves {@code --host}.
     *
     * <p>The empty string is rejected explicitly before {@link InetAddress} sees
     * it: {@code InetAddress.getByName("")} returns the <em>loopback</em> address
     * rather than failing, so {@code --host ""} from an unset shell variable would
     * silently point a client meant for the three-machine demo at its own machine
     * and produce a transcript that looks like a successful LAN run.
     *
     * <p>Loopback itself is accepted, because M1 and M2 develop on one machine.
     * The banner prints the resolved numeric address, so a run that reached
     * {@code 127.0.0.1} says so in its own log.
     */
    private static InetAddress parseHost(String text) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("--host is empty; give the server's LAN address");
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(
                    "--host \"" + text + "\" is not a valid address or resolvable name");
        }
    }

    /**
     * Parses {@code --port}, rejecting 0 along with everything outside 1..65535.
     * Port 0 is legal to <em>bind</em> - it is how the client gets its ephemeral
     * port - but it is never a destination, so accepting it here would turn a
     * typo into a send that fails much later and much less clearly.
     */
    private static int parsePort(String text) {
        final int port = parseInt("--port", text);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("--port " + port + " is outside 1..65535");
        }
        return port;
    }

    /**
     * Maps the two exact spellings to the header's {@code semantics} field.
     *
     * <p>Exact, case-sensitive string equality with no abbreviations and no
     * fallback. D19 has the server answer a mismatched client with
     * {@code ERR_SEMANTICS_MISMATCH}; if a near-miss such as {@code at-most_once}
     * were quietly rounded to a default here, that error would arrive on the first
     * request with nothing on the client's command line to explain it, and the
     * operator would go looking for a bug in the server.
     */
    private static int parseSemantics(String text) {
        if (SEMANTICS_AT_LEAST_ONCE.equals(text)) {
            return Protocol.SEM_AT_LEAST_ONCE;
        }
        if (SEMANTICS_AT_MOST_ONCE.equals(text)) {
            return Protocol.SEM_AT_MOST_ONCE;
        }
        throw new IllegalArgumentException("--semantics \"" + text + "\" is not "
                + SEMANTICS_AT_LEAST_ONCE + " or " + SEMANTICS_AT_MOST_ONCE);
    }

    /**
     * Parses a loss probability and confines it to {@code [0.0, 1.0]} inclusive.
     *
     * <p>Both endpoints are meaningful and both are kept: 0.0 is the default, and
     * 1.0 is a total-loss run worth being able to ask for. NaN and the infinities
     * are rejected separately, because the range comparison alone lets NaN through
     * - every comparison against NaN is false, so {@code p < 0.0 || p > 1.0} is
     * false for it and an unchecked NaN would make the drop gate silently never
     * fire.
     */
    private static double parseProbability(String flag, String text) {
        final double p;
        try {
            p = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " \"" + text + "\" is not a number");
        }
        if (!Double.isFinite(p)) {
            throw new IllegalArgumentException(flag + " " + text + " is not a finite probability");
        }
        if (p < 0.0 || p > 1.0) {
            throw new IllegalArgumentException(flag + " " + text + " is outside 0.0..1.0");
        }
        return p;
    }

    /**
     * Parses a flag that must be at least 1. Used for {@code --timeout}, where a
     * zero-millisecond deadline would expire before any reply could arrive.
     */
    private static int parsePositiveInt(String flag, String text) {
        final int n = parseInt(flag, text);
        if (n < 1) {
            throw new IllegalArgumentException(flag + " " + n + " must be at least 1");
        }
        return n;
    }

    /**
     * Parses {@code --retries}, whose floor is 0 rather than 1.
     *
     * <p>Zero is a meaningful configuration, not a mistake: README sec. 7.2 loops
     * {@code for attempt in 0 .. maxRetries} inclusive, so 0 means one transmission and
     * no retransmission. That is the control condition the sec. 13 experiments need -
     * a run with the retry policy switched off, against which the retrying runs are
     * compared. Rejecting it would remove the baseline.
     *
     * <p>It is emphatically <em>not</em> the "0 means unlimited" convention some tools
     * use. D13 rejects infinite retries outright, because a downed server would then
     * present as a hung client rather than a diagnosable error, and no value of this
     * flag can express it.
     */
    private static int parseRetries(String flag, String text) {
        final int n = parseInt(flag, text);
        if (n < 0) {
            throw new IllegalArgumentException(flag + " " + n + " cannot be negative");
        }
        return n;
    }

    /**
     * Parses {@code --seed}. Every {@code long} is a legal seed, including the
     * negative values {@link System#nanoTime()} routinely produces, so there is a
     * range check on the width and none on the value - a seed copied from a
     * banner has to parse back exactly as printed.
     */
    private static long parseSeed(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "--seed \"" + text + "\" is not a 64-bit integer");
        }
    }

    private static int parseInt(String flag, String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " \"" + text + "\" is not an integer");
        }
    }
}
