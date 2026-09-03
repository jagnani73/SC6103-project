package sc6103.client;

import java.util.Locale;

import sc6103.common.Protocol;
import sc6103.common.StatusCode;

/**
 * Turns a wire status code (README sec. 5) into the sentence a user reads.
 *
 * <h2>Why the wording lives on the client</h2>
 *
 * <p>An error reply is a bare 40-byte header and nothing else - a status number, no
 * text (README sec. 5, D4). The server never sends a message, so every message a user
 * sees is composed here, by the client, out of the request the client itself just
 * sent. D4 records that as a deliberate trade rather than an oversight: carrying UTF-8
 * error text in the body would hand the server control of the wording, but it would
 * also make error replies variable-length, and it would stop the at-most-once replay
 * path (README sec. 8.2) from being a fixed-size buffer copy. The compensation is that
 * the client still holds the arguments it asked with, so it can say more than a
 * context-free server string could.
 *
 * <h2>The one rule that governs every string in this file</h2>
 *
 * <p><b>The client knows what it asked about. It does not know what the server's state
 * is.</b> It has the flight ID the user typed and the route the user typed, because it
 * put them on the wire. It does not have the seat count, the stored fare, the size of
 * the monitor table, or the server's reason for choosing one code over another - none
 * of that is in a bodiless reply, and there is no second request to go and fetch it.
 * So the text must never imply otherwise. "Not enough seats available on flight 1934"
 * is honest; "only 3 seats left" is a number this class does not have and must not
 * invent. docs/console-script.md sec. 6 states the rule and fixes the wording; this
 * file is its implementation, and the strings below are meant to match that table
 * exactly so that the captured demo log and the design document agree.
 *
 * <p>There is exactly one sentence here that talks about server state,
 * {@code ERR_INVALID_FARE}'s "Flight N is unchanged", and it is not an exception to
 * the rule. That is a protocol guarantee written into README sec. 4 Ops 5-6 - a
 * rejected fare change leaves the stored value untouched, so a refused
 * {@code ADJUST_FARE} is a no-op and not a partial application - and the client is
 * entitled to restate a guarantee the specification makes. It is not an observation of
 * the server, and nothing else in this file may become one.
 *
 * <h2>Two kinds of failure, and why the split matters</h2>
 *
 * <p>{@link #isSessionLevel(int)} divides the table the way docs/console-script.md
 * sec. 6 divides it. The division is a real distinction about what the user can do
 * next, not a formatting preference.
 *
 * <p><b>Codes 1-5 are the request being refused on its merits.</b> Everything worked:
 * the datagram arrived, decoded, dispatched, and the operation ran; the answer is
 * simply no for these arguments. A different flight ID or a smaller number of seats
 * would succeed. The console prints these inline, where the user typed, and returns to
 * the same menu.
 *
 * <p><b>Codes 6-12 are protocol or configuration faults.</b> A body that would not
 * parse, an opcode the server has never heard of, a version or semantics disagreement,
 * a reused request ID. No amount of retyping fixes any of them, because the fault is
 * in how the two programs were built or launched, not in what was asked. The console
 * prints these as session-level warnings, away from the prompt; for the semantics
 * mismatch it also stops, since the server will refuse every subsequent request too
 * (D19, docs/console-script.md sec. 1). Showing one of these at the prompt would tell
 * the user their input was the problem, which is false, and would send them off to fix
 * the wrong thing.
 *
 * <p>{@code ERR_MONITOR_TABLE_FULL} sits on the session-level side even though a later
 * retry may well succeed, for the same reason {@link StatusCode#isProtocolFault(int)}
 * puts it there: it is a server capacity condition (D17), so re-prompting the user for
 * different input would be misleading about what went wrong.
 *
 * <p>Companion to {@link StatusCode#name(int)}, which renders the constant's name for
 * the protocol trace of README sec. 10.2. That one is for the log and for whoever is
 * marking; this one is for the person at the keyboard. Both are needed, and neither
 * substitutes for the other.
 *
 * <p>Stateless, side-effect-free and thread-safe by having no state at all. It prints
 * nothing itself - the caller owns the console, because only the caller knows whether
 * a line belongs at the prompt or in the session-level stream.
 */
public final class StatusText {

    /**
     * The upper fare bound as the console formats fares, e.g. {@code 100000.00}.
     *
     * <p>Derived from {@link Protocol#MAX_FARE} rather than typed as a literal, so the
     * message cannot drift away from the constant the decoder actually enforces. The
     * C++ server carries its own copy of the same number (README sec. 11.1); if the two
     * ever disagree, the honest failure is a user seeing this client's bound, not this
     * client inventing a third one.
     *
     * <p>{@link Locale#ROOT} is not decoration. The default locale on a European
     * machine formats this as {@code 100000,00}, which would put a comma into a message
     * about a decimal fare and, worse, would make the same build print different text
     * on the three demo machines.
     */
    private static final String FARE_LIMIT =
            String.format(Locale.ROOT, "%.2f", Protocol.MAX_FARE);

    /**
     * Stand-in for a caller that passed no context at all.
     *
     * <p>Cheaper than a null check at every use site, and it makes the fallback
     * wording the single code path rather than a special case - see the note on null
     * fields in {@link Context}.
     */
    private static final Context NO_CONTEXT = new Context(null, null, null);

    /** Not instantiable: two static functions, no state to carry. */
    private StatusText() {
    }

    /**
     * What the client knows about the request that drew this status - which is all it
     * knows, the reply having carried no body (D4).
     *
     * <p><b>Every field is nullable, and null is an ordinary value here, not an
     * error.</b> Which fields are populated depends on the operation: a route query
     * (README sec. 4 Op 1) has a source and a destination and no flight ID, the other
     * five operations have a flight ID and no route, and a status that arrives on a
     * path the console cannot attribute to any menu selection - a version mismatch
     * rejected before dispatch, say - has neither.
     *
     * <p>{@link StatusText#forUser(int, Context)} therefore drops to a wording that
     * quotes nothing when a field it wanted is absent, rather than printing
     * "flight null" or throwing. That choice is deliberate: this runs on the error
     * path, where a message vaguer than intended still informs the user, and an
     * exception would destroy the only diagnostic they were going to get.
     *
     * <p>Immutable, and cheap enough to build one per request. Do not hold a shared
     * mutable instance: the request state machine (README sec. 7.2) keeps a context
     * alive across retransmissions, and a shared one would name the wrong flight as
     * soon as the user picked the next menu entry.
     */
    public static final class Context {

        /** Flight the request named, or null if the operation names no single flight. */
        public final Integer flightId;

        /** Origin from a route query, or null. */
        public final String source;

        /** Destination from a route query, or null. */
        public final String destination;

        /**
         * @param flightId    flight the request named, or null
         * @param source      origin of a route query, or null
         * @param destination destination of a route query, or null
         */
        public Context(Integer flightId, String source, String destination) {
            this.flightId = flightId;
            this.source = source;
            this.destination = destination;
        }

        /** Diagnostic form for the trace of README sec. 10.2; not shown to the user. */
        @Override
        public String toString() {
            return "Context[flight=" + flightId
                    + ", route=" + source + "->" + destination + "]";
        }
    }

    /**
     * The sentence to show the user for {@code status}.
     *
     * <p>Wording is fixed by the table in docs/console-script.md sec. 6, with the
     * placeholders filled from {@code ctx}. Returns the empty string for
     * {@link StatusCode#OK}, because success is reported by printing the operation's
     * result and a second "it worked" line would be noise.
     *
     * <p>The returned string is one line, with no trailing newline and no prefix. The
     * caller decides where it goes; {@link #isSessionLevel(int)} answers that.
     *
     * @param status status field from the reply header, any {@code int} value
     * @param ctx    what the client asked; null is treated as a context with no fields
     *               set, since throwing on the error path would replace the user's only
     *               diagnostic with a stack trace
     * @return a complete sentence, or {@code ""} for {@link StatusCode#OK}
     */
    public static String forUser(int status, Context ctx) {
        final Context c = (ctx == null) ? NO_CONTEXT : ctx;

        switch (status) {
            case StatusCode.OK:
                // Deliberately empty: the caller prints the operation's result instead.
                return "";

            case StatusCode.ERR_UNKNOWN_FLIGHT:
                return (c.flightId == null)
                        ? "No flight with that ID."
                        : "No flight with ID " + c.flightId + ".";

            case StatusCode.ERR_NO_ROUTE_MATCH:
                // README sec. 4 Op 1 makes an empty result an error rather than a
                // zero-length reply, so this is the normal "nothing found" path for a
                // route query and has to read like an answer, not like a failure.
                return (c.source == null || c.destination == null)
                        ? "No flights on that route."
                        : "No flights from " + c.source + " to " + c.destination + ".";

            case StatusCode.ERR_INSUFFICIENT_SEATS:
                // No count, and none available to add: the reply has no body, so the
                // client cannot say how many seats are left without inventing a number.
                return (c.flightId == null)
                        ? "Not enough seats available on that flight."
                        : "Not enough seats available on flight " + c.flightId + ".";

            case StatusCode.ERR_INVALID_ARGUMENT:
                // Which argument is not on the wire either. Naming the field would be a
                // guess, and a wrong guess sends the user to correct the wrong prompt.
                return "The server rejected an argument in that request.";

            case StatusCode.ERR_INVALID_FARE:
                // NOTE: docs/console-script.md sec. 6 still lists code 5 as
                // NEGATIVE_FARE, "That change would make the fare negative." That row is
                // stale and this text is the correct one - do not "fix" it back. The
                // spec settled on StatusCode.ERR_INVALID_FARE, and README sec. 4 Ops 5-6
                // define the rule over the whole range: the resulting fare must satisfy
                // 0 < fare <= MAX_FARE. A fare pushed above the maximum returns this
                // same code, so text that mentions only the negative case would be wrong
                // at the upper boundary - which test plan README sec. 14 exercises
                // directly, both one increment short of MAX_FARE and one past it.
                //
                // "unchanged" is a guarantee restated, not server state observed: README
                // sec. 4 Ops 5-6 require the stored value to be left untouched when the
                // result is out of range, so a rejected ADJUST_FARE is a no-op. Saying so
                // matters most under at-least-once, where the user is already unsure how
                // many times the delta was applied.
                return "The resulting fare must be above 0 and at most " + FARE_LIMIT
                        + ". " + ((c.flightId == null)
                                ? "The flight is unchanged."
                                : "Flight " + c.flightId + " is unchanged.");

            case StatusCode.ERR_MALFORMED_REQUEST:
                return "Protocol error: the server could not parse that request.";

            case StatusCode.ERR_UNKNOWN_OPCODE:
                return "Protocol error: the server does not support that operation.";

            case StatusCode.ERR_MONITOR_TABLE_FULL:
                return "The server cannot accept more monitors right now. Try again shortly.";

            case StatusCode.ERR_SERVER_ERROR:
                // Points at the server log because that is where the detail is: the
                // server knows which invariant broke and the client never will.
                return "The server reported an internal error. Check the server log.";

            case StatusCode.ERR_VERSION_MISMATCH:
                return "Protocol version mismatch. Client and server builds differ.";

            case StatusCode.ERR_SEMANTICS_MISMATCH:
                // The flag name is quoted exactly as README sec. 15.2 spells it, since
                // the fix is a command line the user has to retype on both machines.
                return "Semantics mismatch. Restart both with the same --semantics.";

            case StatusCode.ERR_REQUEST_ID_CONFLICT:
                // console-script.md sec. 6 renders the dash in this line as an em dash.
                // Sources are compiled with -encoding US-ASCII (scripts/build.ps1) so a
                // non-ASCII byte is a compile error, and the demo consoles are not all
                // UTF-8 anyway; a hyphen is the same sentence and survives every code
                // page. The same substitution applies to any row added later.
                return "Request ID collision. This is a client bug - report it.";

            default:
                // Rendered, never thrown. An unrecognised code is precisely the symptom
                // of a drifted or hostile peer (D19), and that is the moment the raw
                // number has to reach both the user and the log intact - a swallowed or
                // remapped code would hide the one piece of evidence worth having.
                return "The server returned status code " + status
                        + ", which this client does not recognise.";
        }
    }

    /**
     * Whether {@code status} should be reported as a session-level warning rather than
     * inline at the prompt.
     *
     * <p>False for codes 1-5, the refusals on the merits: the exchange worked and the
     * answer is no, so the message belongs where the user typed and the console stays
     * in the same menu. True for codes 6-12, the protocol and configuration faults,
     * which no retyping can fix. The reasoning behind the split is in the class
     * documentation; it comes from docs/console-script.md sec. 6.
     *
     * <p>{@link StatusCode#OK} is false: nothing is printed for success at all.
     *
     * <p><b>Unrecognised codes are session-level.</b> That is why this does not simply
     * delegate to {@link StatusCode#isProtocolFault(int)}, which asks a narrower
     * question - "is this in 6..12" - and answers false for a code from a peer that has
     * drifted past this build's table. The question here is whether the console may
     * present the message as something the user can fix by typing differently, and for
     * a code this client cannot even name, it may not.
     *
     * <p>The merits range is expressed with the boundary constants rather than the
     * literals 1 and 5, so appending a code to {@link StatusCode} cannot silently move
     * the line between the two halves.
     *
     * @param status status field from the reply header, any {@code int} value
     * @return true to print as a session-level warning, false to print at the prompt
     */
    public static boolean isSessionLevel(int status) {
        if (status == StatusCode.OK) {
            return false;
        }
        final boolean refusedOnMerits = status >= StatusCode.ERR_UNKNOWN_FLIGHT
                && status <= StatusCode.ERR_INVALID_FARE;
        return !refusedOnMerits;
    }
}
