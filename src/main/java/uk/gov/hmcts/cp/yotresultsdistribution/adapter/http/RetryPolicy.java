package uk.gov.hmcts.cp.yotresultsdistribution.adapter.http;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * The one retry policy, shared by the four clients that make this service's outbound calls.
 *
 * <p>The four are C3's original three - the progression command gateway, the payload query and the
 * reference-data read - and the notification client 002 added, which classifies its own single
 * attempt through this object rather than growing a fourth opinion about what is worth asking again.
 *
 * <p><strong>Defect fix C3, stated once.</strong> The register's fix row promises "a config-driven
 * retry policy applied identically to all three named clients", and three clients that each
 * implement the same sentence are three places it can drift: the progression gateway grew a doubling
 * back-off and a {@code Retry-After} reader while the payload query and the reference-data read kept
 * the legacy's fixed one-second interval and ignored the header entirely — so "identically" was true
 * of the status taxonomy and of nothing else. It is one object now, and a client cannot hold a
 * different opinion about a wait than the object it was given.
 *
 * <p>What the policy decides:
 *
 * <ul>
 *   <li><strong>Which statuses are worth asking again</strong>: 408, 429 and every 5xx. Connect and
 *       read failures are too, and are recognised by the client rather than by a status. The
 *       legacy's rule is the inverse for the two that matter — {@code AxiosRetryWrapper.js:34}
 *       abandons on any status at or below 429, which makes "slow down" and "you timed out" the
 *       least-retried failures it has.</li>
 *   <li><strong>How long to wait</strong>: {@code initial-backoff}, doubling per attempt, bounded by
 *       {@code max-backoff}. The legacy waits a fixed second however unwell the other side is.</li>
 *   <li><strong>What a {@code Retry-After} is worth</strong>: it is honoured on <em>every</em>
 *       retryable response rather than only on a 429 — a 503 carrying one is a service saying when
 *       it expects to be back, and the whole point of the header is that the server knows better
 *       than the client's schedule. Delta-seconds only, and bounded by the same
 *       {@code max-backoff}: RFC 9110 also permits an HTTP-date, and acting on one would mean
 *       measuring a remote clock against this pod's, so a server a few minutes ahead could park a
 *       run past the claim lease it holds. The form is recognised before it is read, so an unusable
 *       value is classified rather than parsed and caught, and every unusable form falls back to the
 *       back-off — the same outcome as no header at all.</li>
 *   <li><strong>What one attempt can cost</strong>: its connect timeout plus its read timeout, which
 *       is the reservation {@link #attemptFitsBefore(Instant, Instant)} holds a deadline-bounded
 *       client to. It is the same number {@code config/PropertiesValidator} budgets a whole run
 *       with, read out of the same two settings, so a client cannot believe an attempt is cheaper
 *       than startup believed it was.</li>
 * </ul>
 *
 * <p>The bound matters more here than it looks. Every wait this policy hands out is spent inside a
 * run holding a claim, and {@code config/PropertiesValidator} budgets the whole run against
 * {@code yotresultsdistribution.claim.processing-deadline} on the assumption that no single wait can exceed
 * {@code max-backoff} — which is true only because a server-supplied wait is capped by it too.
 *
 * <p><strong>A wait is not the only thing that can outlive a claim.</strong> An attempt can too: a
 * connect that hangs to its timeout and then a read that hangs to its own is the longest single
 * thing an outbound call does, and a client that only asked whether the deadline had already passed
 * would start that attempt with a millisecond of budget left and finish it fifteen seconds after
 * the claim behind it became reclaimable. So the reservation is stated here rather than in the
 * client that happens to hold a deadline today: {@link #attemptFitsBefore(Instant, Instant)} refuses
 * an attempt whose own worst case does not fit in what is left, and refusing costs nothing — the
 * delivery is handed back and the redelivery gets a whole fresh budget.
 */
public final class RetryPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(RetryPolicy.class);

    private static final String RETRY_AFTER_HEADER = "Retry-After";

    /**
     * The only {@code Retry-After} form this policy acts on: delta-seconds, at most ten digits.
     *
     * <p>Ten digits rather than any number of them, so an absurd value is refused by the grammar
     * instead of overflowing a parse that would then have to be caught.
     */
    private static final Pattern DELTA_SECONDS = Pattern.compile("\\d{1,10}");

    private final int attempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration attemptWorstCase;

    /**
     * Builds the policy from an environment's settings for one client.
     *
     * @param maxAttempts      total attempts including the first
     * @param initialBackoff   the first wait between retryable attempts; doubled each time
     * @param maxBackoff       the ceiling on any wait, a server-supplied {@code Retry-After}
     *                         included
     * @param attemptWorstCase what one attempt can cost at worst — this client's connect timeout
     *                         plus its read timeout, the same pair startup budgets the run with
     */
    public RetryPolicy(final int maxAttempts, final Duration initialBackoff,
            final Duration maxBackoff, final Duration attemptWorstCase) {
        this.attempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.attemptWorstCase = attemptWorstCase;
    }

    /**
     * Total attempts including the first.
     *
     * @return the attempt budget
     */
    public int maxAttempts() {
        return attempts;
    }

    /**
     * Whether a whole attempt can be made and still leave the run inside its claim.
     *
     * <p>The reservation, and it is deliberately made against the attempt's <em>worst</em> case
     * rather than against its usual one. An attempt that answers in ten milliseconds ninety-nine
     * times out of a hundred is not the attempt a deadline exists for; the hundredth hangs on a
     * connect and then on a read, and a run that started it with a millisecond of budget left ends
     * it long after the claim behind it became reclaimable. For a command another runner may then
     * also send, and that progression <em>appends</em>, the arithmetic is a second register for one
     * hearing.
     *
     * <p>Strictly before, the same reading the pipeline and the clients give every other deadline
     * check: an attempt that would end exactly on the deadline has not finished inside the budget.
     *
     * @param now      this pod's reading of now; a local reading only, never compared with another
     *                 node's clock
     * @param deadline the instant the run promised to have stopped by
     * @return whether the attempt fits inside what is left
     */
    public boolean attemptFitsBefore(final Instant now, final Instant deadline) {
        return now.plus(attemptWorstCase).isBefore(deadline);
    }

    /**
     * The wait to take before the next attempt.
     *
     * <p>The server's answer where it gave a usable one, the back-off schedule otherwise, and the
     * ceiling over both.
     *
     * @param attemptsTaken how many attempts have been made, the failed one included; 1 for the
     *                      wait after the first attempt
     * @param retryAfter    what the response asked for, where it asked for something usable
     * @return the wait, bounded by {@code max-backoff}
     */
    public Duration waitAfter(final int attemptsTaken, final Optional<Duration> retryAfter) {
        return capped(retryAfter.orElseGet(() -> backoffAfter(attemptsTaken)));
    }

    /**
     * The scheduled wait after a given number of attempts, ignoring anything the server asked for.
     *
     * <p>Accumulated a doubling at a time rather than by formula, and stopped at the ceiling as soon
     * as it is reached, so a long attempt count cannot overflow the doubling before the cap has a
     * chance to stop it.
     *
     * @param attemptsTaken how many attempts have been made, the failed one included
     * @return the scheduled wait, bounded by {@code max-backoff}
     */
    public Duration backoffAfter(final int attemptsTaken) {
        Duration wait = initialBackoff;
        for (int doubled = 1; doubled < attemptsTaken && wait.compareTo(maxBackoff) < 0; doubled++) {
            wait = wait.multipliedBy(2);
        }
        return capped(wait);
    }

    /** The ceiling on any wait, a server-supplied one included: a run holds a bounded claim. */
    private Duration capped(final Duration wait) {
        return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
    }

    /**
     * Whether another attempt may answer differently.
     *
     * <p>The positive set, written out, rather than the legacy's {@code status <= 429} cut-off: 408
     * and 429 are the two statuses that most plainly mean "ask me again", and the cut-off makes them
     * the two it never asks.
     *
     * @param status the status the other side answered with
     * @return whether the status is worth another attempt
     */
    public static boolean retryable(final int status) {
        return status == HttpStatus.REQUEST_TIMEOUT.value()
                || status == HttpStatus.TOO_MANY_REQUESTS.value()
                || status >= HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    /**
     * The wait a response asked for, where it asked for one this service can act on.
     *
     * @param headers the response's headers, or {@code null} where the failure carried none
     * @return the delta-seconds wait, or empty for every other form
     */
    public static Optional<Duration> retryAfter(final HttpHeaders headers) {
        return retryAfter(headers == null ? null : headers.getFirst(RETRY_AFTER_HEADER));
    }

    /**
     * The wait a {@code Retry-After} value asks for, where the value is one this service can act on.
     *
     * @param header the header's value, or {@code null} where there was none
     * @return the delta-seconds wait, or empty for every other form — which falls back to the
     *     back-off, the same outcome as no header at all
     */
    public static Optional<Duration> retryAfter(final String header) {
        final String asked = header == null ? "" : header.trim();
        final Optional<Duration> wait;
        if (DELTA_SECONDS.matcher(asked).matches()) {
            wait = Optional.of(Duration.ofSeconds(Long.parseLong(asked)));
        } else {
            if (!asked.isEmpty()) {
                // The value is not repeated: it is the other side's text, and this line reaches the
                // estate's log index.
                LOG.warn("Retry-After was not a number of seconds, so the back-off is used instead.");
            }
            wait = Optional.empty();
        }
        return wait;
    }
}
