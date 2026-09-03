package sc6103.client;

import java.util.Locale;
import java.util.Random;

/**
 * Client-side simulated message loss: the two seeded Bernoulli gates and the
 * one-shot rule the client applies to its own datagrams (README sec. 10, D12,
 * D25).
 *
 * <p>The brief asks for the loss of request and reply messages to be simulated
 * "in your program", so there is no {@code tc netem}, no Clumsy and no firewall
 * rule anywhere in this project. D12 rejected those outright: an external tool
 * needs privileged configuration on three demo machines, and the brief wants
 * the failure model inside the code being marked.
 *
 * <h2>Where the caller applies these gates</h2>
 * <ul>
 *   <li>{@link #shouldDropRequest()} - <b>after encoding, before
 *       {@code send}</b>. The datagram is built in full and then quietly not
 *       transmitted. Placing the gate here rather than before encoding is
 *       deliberate (README sec. 10, D12): the codec runs on an identical path
 *       whether or not the bytes reach the socket, so protocol-level behaviour
 *       stays deterministic under injected loss and a marshalling fault cannot
 *       hide behind a drop.</li>
 *   <li>{@link #shouldDropReply()} - <b>after the datagram has been received
 *       and decoded, before it reaches the request/reply state machine</b>.
 *       Same reason in the other direction: the decoder is exercised on every
 *       datagram that actually arrived, and the state machine then sees exactly
 *       what it would have seen had the reply never come back.</li>
 * </ul>
 *
 * <p>A suppressed request still counts as an attempt. The caller must start the
 * retry timer as though the datagram had gone out, because the whole point of
 * the exercise is that the client cannot tell a lost request from a slow one
 * (README sec. 7.2, D13). Likewise a suppressed reply must leave the pending
 * request pending; it must not be reported as a failure.
 *
 * <h2>Two gates, not one rate</h2>
 * <p>Deck 2 distinguishes request-lost, reply-lost and incomplete-interaction,
 * and only <em>reply</em> loss discriminates between at-least-once and
 * at-most-once - a lost request is simply retried until it lands, which is the
 * negative result experiment E7 reports (README sec. 13). A single combined
 * probability cannot target the discriminating case, which is why D12 specifies
 * independent gates rather than one global rate.
 *
 * <h2>Probability and the one-shot rule answer different questions</h2>
 * <p>Both mechanisms are here because neither substitutes for the other (D25).
 * The sec. 13 experiments need probabilistic loss to report outcome rates over
 * 20 trials. A live demonstration needs the opposite: at <i>p</i> = 0.5 the
 * drop that makes the point may simply not happen while a marker is watching,
 * so {@code --drop-first-request} forces it exactly once, on the first request
 * the client sends, and is then spent.
 *
 * <p>Unlike the server's {@code --drop-first-reply}, this rule takes no opcode
 * scope (README sec. 15.2). It does not need one: the client chooses what it
 * sends, so the operator issues the operation being demonstrated first. The
 * server cannot choose what arrives at its socket, which is why D25 scopes
 * <em>its</em> rule by opcode.
 *
 * <h2>Client-side injection is for demos, not for measurements</h2>
 * <p>The sec. 13 experiments use <b>server-side injection only</b> (README
 * sec. 10). This class exists to reproduce the deck 2 scenarios on one machine
 * and to make a demo drop happen on cue. Running both ends at once would
 * confound the measured rate - two independent gates at <i>p</i> compose to
 * 1 - (1 - <i>p</i>)^2, not <i>p</i>, so the number quoted in the report would
 * not be the number that was configured.
 *
 * <h2>Reproducibility</h2>
 * <p>Every decision comes from {@link Random} seeded from {@code --seed}, so a
 * run replays from the seed printed in the startup banner and stored with the
 * captured logs (D12, README sec. 24.3). {@code java.util.Random} is chosen
 * precisely because its generator is specified exactly in its own contract: the
 * same seed yields the same sequence on any JVM vendor or version, which
 * {@code Math.random()} and {@code ThreadLocalRandom} do not promise.
 *
 * <p>This class never prints. It returns a decision and a reason; the caller
 * emits the {@code [DROP]} line of {@code docs/console-script.md} sec. 4 and
 * the fixed-position log record of README sec. 10.2, because only the caller
 * knows the request id, opcode and attempt number those lines must carry. Apart
 * from advancing its own generators and spending the one-shot rule, the class
 * has no side effects.
 *
 * <p>Not thread-safe, and does not need to be: the client drives one request at
 * a time and blocks while monitoring (README sec. 7.3, D11).
 */
public final class LossSimulator {

    /**
     * Value of {@link #lastReason()} before any gate has returned {@code true}.
     *
     * <p>A correct caller never logs this, since it only reads the reason on the
     * drop path. It exists so the accessor is never {@code null}: a log
     * formatter tripping over a {@code NullPointerException} while reporting an
     * injected drop would destroy the very evidence the run was capturing.
     */
    private static final String REASON_NONE = "none";

    /**
     * Reason text for the one-shot rule, matching the {@code reason=} field of
     * the {@code [DROP]} line in {@code docs/console-script.md} sec. 4.
     *
     * <p>By construction it is returned at most once per run, so the single log
     * line carrying it <em>is</em> the {@code RULE_CONSUMED} event of README
     * sec. 10.1 - the rule fires and is spent in the same call. The caller emits
     * that consumption record when it sees this reason.
     */
    private static final String REASON_FORCED_FIRST_REQUEST = "forced-first-request";

    /**
     * Per-gate seed salts. Two distinct odd 64-bit constants, so the request and
     * reply generators start from unrelated states derived from one configured
     * seed. See {@link #derive(long, long)}.
     */
    private static final long SALT_REQUEST = 0x9E3779B97F4A7C15L;
    private static final long SALT_REPLY = 0xD1B54A32D192ED03L;

    /** Configured seed, retained only so {@link #describe()} can report it. */
    private final long seed;

    /** Probability in [0,1] that an outbound request datagram is suppressed. */
    private final double dropRequestRate;

    /** Probability in [0,1] that an inbound reply is treated as never arrived. */
    private final double dropReplyRate;

    /**
     * Generator for the request gate.
     *
     * <p><b>The two gates draw from separate streams on purpose.</b> One shared
     * generator would make each reply decision depend on how many requests had
     * been drawn before it, so adding a single retransmission - which the retry
     * policy of D13 does routinely - would shift every later reply decision, and
     * a captured seed would stop replaying its own log. Separate streams make
     * the <i>n</i>-th reply decision a function of the seed and <i>n</i> alone.
     *
     * <p>Seeding both from the raw {@code seed} would be worse still: the two
     * gates would then draw identical sequences and fire on exactly the same
     * trials, so a run with both rates at 0.5 would lose the request and the
     * reply of the same attempt every time - a perfect correlation no real
     * network has, and one that would quietly poison any rate reported from it.
     */
    private final Random requestRng;

    /**
     * Generator for the reply gate. See {@link #requestRng} for why it is
     * separate.
     */
    private final Random replyRng;

    /**
     * Reason strings for probabilistic drops, e.g. {@code injected-p=0.50}.
     *
     * <p>Formatted once here rather than per call because the rates are final -
     * the gates then allocate nothing on the hot path and cannot report a rate
     * that disagrees with the startup banner.
     */
    private final String requestDropReason;
    private final String replyDropReason;

    /**
     * Whether {@code --drop-first-request} was requested on the command line.
     *
     * <p>Held separately from {@link #firstRequestRuleArmed} so that
     * {@link #describe()} keeps reporting the configuration after the rule has
     * been spent. The banner has to reproduce the command line that produced the
     * log (README sec. 24.3); it is not a live status display.
     */
    private final boolean firstRequestRuleConfigured;

    /** The one-shot rule's remaining charge. Cleared the first time it fires. */
    private boolean firstRequestRuleArmed;

    /** Why the most recent {@code true} was returned. Never {@code null}. */
    private String lastReason = REASON_NONE;

    /**
     * @param seed             PRNG seed from {@code --seed}; printed in the
     *                         startup banner so the run can be replayed exactly
     *                         (D12, README sec. 24.3). Any {@code long} is legal
     * @param dropRequestRate  probability of suppressing an outbound request,
     *                         from {@code --drop-request}; 0.0 disables the gate
     * @param dropReplyRate    probability of discarding an inbound reply, from
     *                         {@code --drop-reply}; 0.0 disables the gate
     * @param dropFirstRequest arms the one-shot rule of README sec. 10.1
     * @throws IllegalArgumentException if either rate is NaN or outside [0,1]. A
     *                                  bad rate is a command-line fault, so it is
     *                                  rejected at startup rather than clamped:
     *                                  silently turning {@code --drop-reply 50}
     *                                  into 1.0 would produce a log whose banner
     *                                  disagrees with the experiment it claims to
     *                                  record
     */
    public LossSimulator(long seed, double dropRequestRate, double dropReplyRate,
                         boolean dropFirstRequest) {
        this.seed = seed;
        this.dropRequestRate = requireRate(dropRequestRate, "--drop-request");
        this.dropReplyRate = requireRate(dropReplyRate, "--drop-reply");
        this.requestRng = new Random(derive(seed, SALT_REQUEST));
        this.replyRng = new Random(derive(seed, SALT_REPLY));
        this.requestDropReason = injectedReason(this.dropRequestRate);
        this.replyDropReason = injectedReason(this.dropReplyRate);
        this.firstRequestRuleConfigured = dropFirstRequest;
        this.firstRequestRuleArmed = dropFirstRequest;
    }

    /**
     * Decides whether this outbound request datagram is suppressed. Called once
     * per transmission attempt, after encoding and immediately before
     * {@code send} (README sec. 10).
     *
     * <p>A retransmission is a fresh attempt and gets a fresh draw, which is what
     * makes {@code --drop-request 0.5} converge rather than deadlock: the bounded
     * retries of D13 give the request several independent chances to land. The
     * retried datagram is byte-identical, exactly as
     * {@code docs/console-script.md} sec. 4 requires - this gate decides whether
     * bytes leave the socket, never what they contain.
     *
     * @return {@code true} if the caller must not send; it should then log the
     *         drop with {@link #lastReason()}, count the attempt, and start the
     *         retry timer as though the datagram had gone out (README sec. 7.2)
     */
    public boolean shouldDropRequest() {
        // The draw happens first and unconditionally - even when the one-shot
        // rule pre-empts it, and even when the rate is 0.0 - so that the stream
        // position stays a pure function of the number of calls. Skipping the
        // draw would make one seed produce different sequences depending on
        // whether --drop-first-request was passed, and D25 means the two
        // mechanisms to be selectable independently of each other.
        final boolean sampled = bernoulli(requestRng, dropRequestRate);

        // The forced rule wins when both would drop: it is the drop the operator
        // explicitly asked for, and reporting it as probabilistic would make the
        // demo trace lie about why the datagram vanished.
        if (firstRequestRuleArmed) {
            firstRequestRuleArmed = false;
            lastReason = REASON_FORCED_FIRST_REQUEST;
            return true;
        }
        if (sampled) {
            lastReason = requestDropReason;
            return true;
        }
        return false;
    }

    /**
     * Decides whether this inbound reply is treated as never having arrived.
     * Called once per received reply, after decoding and before the
     * request/reply state machine sees it.
     *
     * <p><b>Replies only.</b> Callbacks and {@code MONITOR_END} must never be
     * passed through this gate: the client has no {@code --drop-callback} flag
     * (README sec. 15.2), and silently discarding a callback here would open a
     * gap in {@code callbackSeq} that experiment E10 would then attribute to the
     * server's callback gate.
     *
     * @return {@code true} if the caller must discard the datagram, leave the
     *         pending request pending and let the retry timer keep running -
     *         never reporting it as a failure, since under either semantics the
     *         operation may well have executed (README sec. 7.2, D13)
     */
    public boolean shouldDropReply() {
        // One draw per call, from this gate's own stream. See requestRng for why
        // the request gate's activity cannot shift this sequence.
        if (bernoulli(replyRng, dropReplyRate)) {
            lastReason = replyDropReason;
            return true;
        }
        return false;
    }

    /**
     * Why the most recent {@code true} was returned, for the {@code reason=}
     * field of the {@code [DROP]} line ({@code docs/console-script.md} sec. 4):
     * {@code forced-first-request} for the one-shot rule, or
     * {@code injected-p=0.50} for a probabilistic drop.
     *
     * <p>A {@code false} deliberately does not clear this. The value is defined
     * only immediately after a gate returned {@code true}, and leaving it intact
     * means a caller that logs one line late still logs a real reason rather
     * than a sentinel.
     *
     * @return the reason text, never {@code null}; {@code "none"} before the
     *         first drop
     */
    public String lastReason() {
        return lastReason;
    }

    /**
     * One-line summary of the active gates for the startup banner
     * ({@code docs/console-script.md} sec. 1), for example
     * {@code request=0.00 reply=0.50 seed=42 rules=drop-first-request}.
     *
     * <p>Both rates and the seed are always reported, even when everything is
     * off, because "this run had no injected loss" is itself a fact the evidence
     * package has to prove (D25, README sec. 24.3) - a missing line proves
     * nothing. The output is a pure function of the constructor arguments, so it
     * reads the same before and after the one-shot rule fires.
     *
     * <p>{@link Locale#ROOT} is not decoration: under a locale with a comma
     * decimal separator {@code %.2f} would render {@code 0,50}, and logs captured
     * on three machines for the same experiment would no longer be comparable
     * line for line.
     *
     * @return the banner line, without a trailing newline
     */
    public String describe() {
        return String.format(Locale.ROOT,
                "request=%.2f reply=%.2f seed=%d rules=%s",
                dropRequestRate, dropReplyRate, seed,
                firstRequestRuleConfigured ? "drop-first-request" : "none");
    }

    /**
     * One Bernoulli trial. Always consumes exactly one value from {@code rng},
     * whatever the rate, to keep the stream position predictable.
     *
     * <p>The comparison is strictly {@code <}. {@link Random#nextDouble()}
     * returns a value in [0,1), so a rate of 0.0 can never fire and 1.0 always
     * does. With {@code <=} a rate of 0.0 would drop on the one-in-2^53 draw of
     * exactly zero - a fault no test would ever reproduce and no one would ever
     * find.
     */
    private static boolean bernoulli(Random rng, double rate) {
        return rng.nextDouble() < rate;
    }

    /**
     * Derives a per-gate seed from the single configured seed.
     *
     * <p>This is the SplitMix64 finalizer, used here only to space the two
     * generators apart in the seed space; the drop decisions themselves still
     * come from {@link Random}, whose sequence is contractually specified and
     * therefore reproducible across JVMs. Adjacent seeds such as {@code seed} and
     * {@code seed + 1} would very probably be fine, but "very probably" is not a
     * property worth relying on in the one component whose job is to make an
     * experiment reproducible. Mixing removes the question for three lines of
     * arithmetic and no dependency.
     */
    private static long derive(long seed, long salt) {
        long z = seed + salt;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Validates one probability from the command line.
     *
     * <p>The test is written as a negated range check rather than
     * {@code v < 0.0 || v > 1.0} because every comparison against NaN is false:
     * the positive form would let {@code --drop-reply NaN} through, and the gate
     * would then silently never fire.
     */
    private static double requireRate(double v, String flag) {
        if (!(v >= 0.0 && v <= 1.0)) {
            throw new IllegalArgumentException(
                    flag + " must be a probability in 0.0..1.0, got " + v);
        }
        return v;
    }

    /**
     * Builds the {@code injected-p=0.50} reason text. Two decimal places match
     * the loss line of the startup banner; the authoritative rate is the command
     * line, which README sec. 24.3 requires be captured beside the log anyway.
     */
    private static String injectedReason(double rate) {
        return String.format(Locale.ROOT, "injected-p=%.2f", rate);
    }
}
