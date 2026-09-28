package uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPause;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SubmissionFailedException;

/**
 * The one HTTP call this service makes outwards: {@code add-court-register}, once per hearing.
 *
 * <p>The contract is progression-owned and frozen. The path, the vendor media type and the identity
 * header are constants here because they are constants there
 * ({@code ProcessOutboundCourtRegister/index.js:17-25}), and the body is passed through as the bytes
 * the caller produced — nothing in this class inspects, reshapes or adds to it. A gateway that
 * touched the body would be a second place the outbound contract was defined, and the first one
 * ({@code adapter/progression/OutboundContractValidator}) has already refused anything progression
 * would.
 *
 * <p><strong>Success is {@code 202 Accepted} and nothing else.</strong> The contract declares one
 * success status, so any other 2xx is a failure rather than a lenient success: a 200 from a proxy or
 * a re-pointed route would otherwise mark the hearing POSTED for a command that was never enqueued,
 * and the register would be gone with the log saying it had been sent. It is not retried either —
 * the body may already have been applied, and {@code add-court-register} <em>appends</em> — so it is
 * reported non-transient under {@link uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode#SUBMISSION_NOT_ACCEPTED}
 * and parked where somebody can look at the endpoint.
 *
 * <p><strong>Defect fix C1.</strong> {@code index.js:17-25} makes this call with a bare
 * {@code axios.post}, catches whatever comes back, logs it and swallows it; the response status is
 * never inspected at all, so a refused register and a delivered one are the same run. Here every
 * outcome is classified, the status progression answered travels with the failure so it can be
 * written to {@code processed_output.response_code}, and nothing is absorbed.
 *
 * <p><strong>Defect fix C3.</strong> {@code CommonUtility/AxiosRetryWrapper.js:19,34} abandons the
 * moment a response arrives carrying a status at or below 429, which makes 429 and 408 — the two
 * statuses that most plainly mean "ask me again" — the least-retried failures there are, and its
 * one-second interval never grows. Here:
 *
 * <ul>
 *   <li>a connect or read failure, and a connection dropped mid-flight, are retried — the outcome is
 *       <em>unknown</em>, not failed;</li>
 *   <li>5xx, 429 and 408 are retried, with the wait doubling each time and bounded by
 *       {@code max-backoff};</li>
 *   <li>a {@code Retry-After} on any of them is honoured in delta-seconds only and capped by the
 *       same ceiling; an HTTP-date is classified before it is read, never parsed, because acting on
 *       one would measure a remote clock against this pod's and could park a run past the claim it
 *       holds;</li>
 *   <li>any other 4xx is a refusal, is never retried, and comes back non-transient: the same bytes
 *       will be refused again and the delivery budget is finite.</li>
 * </ul>
 *
 * <p>Not the same taxonomy as {@code ResultsQueryHearingPayloadClient} and
 * {@code ReferenceDataNowSubscriptionsClient} — <strong>the same object</strong>. All three are
 * handed a {@link uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy}, so what a redelivery can
 * fix, how long a wait grows and what a {@code Retry-After} is worth are decided once and cannot
 * drift apart between them, which is what C3's row promises and what three separate implementations
 * of one sentence could not hold to.
 *
 * <p><strong>Every one of those waits is bounded by the run's claim, not only by
 * {@code max-backoff} — and so is every attempt.</strong> The caller hands in the instant its run
 * promised to have stopped by, and it is read before every attempt and before every wait — the
 * back-off's and progression's {@code Retry-After} alike. An attempt is held to what it can
 * <em>cost</em> rather than to whether it may begin: its connect timeout plus its read timeout must
 * still fit, because an attempt started with a millisecond of budget left is a POST that lands after
 * the claim behind it became reclaimable. A wait that would end after the deadline is refused rather
 * than shortened: the run is handed back TRANSIENT under
 * {@link uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode#PROCESSING_DEADLINE_EXCEEDED} and the
 * redelivery gets a whole fresh budget. Without that check a policy sized in seconds can still spend
 * minutes — four attempts, each able to spend a connect and a read timeout, with a server-chosen
 * wait between them — and a POST made after the claim became reclaimable is a POST a second runner
 * may be making too, which for a command that <em>appends</em> is a second register for one hearing.
 *
 * <p><strong>An unknown outcome is retried, and that is not at-most-once.</strong> A POST that timed
 * out may have been applied. Retrying it can create a duplicate {@code court_register_request} row —
 * which progression's {@code max(register_time) per hearing_id} sweep absorbs for generation, like a
 * re-share — while not retrying it can lose the hearing, which nothing absorbs and nobody sees. The
 * trade is made deliberately in that direction and no code or comment here promises more than it.
 *
 * <p>Nothing progression says is ever carried out of this class. The failure reason is one of a
 * bounded set of codes, because it reaches a dead-letter description and the log index, and a
 * response body from a court-register command can name a child.
 *
 * <p>The endpoint and the identity are not validated here as the informant's gateway validates them:
 * {@code config/PropertiesValidator} already refuses a LIVE deployment with no
 * {@code yotresultsdistribution.progression.base-url} or {@code system-user-id}, so the fault is a startup
 * fault in one place rather than in two.
 */
public class ProgressionCommandGateway {

    /** The command's path under the progression context, exactly as the command API declares it. */
    public static final String PATH =
            "/progression-command-api/command/api/rest/progression/court-register";

    /** The command's vendor media type; the framework routes on it, so it is not a formality. */
    public static final String ADD_COURT_REGISTER_MEDIA_TYPE =
            "application/vnd.progression.add-court-register+json";

    /**
     * The CPP identity header. Its value is never logged — it is either a secret or a user
     * identifier, and neither belongs in a log index.
     */
    public static final String IDENTITY_HEADER = "CJSCPPUID";

    /** The one status the contract calls success. */
    public static final int ACCEPTED = 202;

    private static final Logger LOG = LoggerFactory.getLogger(ProgressionCommandGateway.class);

    private final RestClient restClient;
    private final String systemUserId;
    private final Map<String, String> extraHeaders;
    private final RetryPolicy retryPolicy;
    private final RetryPause pause;
    private final Clock clock;

    /**
     * Builds the gateway over an already-configured HTTP client.
     *
     * @param restClient   the client, carrying the progression base URL and its timeouts
     * @param systemUserId the {@code CJSCPPUID} identity for a run naming no user; a secret, never
     *                     logged
     * @param extraHeaders any further headers the mesh requires, name to value
     * @param retryPolicy  the shared retry policy: attempts, back-off and what a
     *                     {@code Retry-After} is worth
     * @param pause        how a wait between attempts is taken
     * @param clock        elapsed-time source for the caller's deadline; local readings only, so no
     *                     decision here compares one pod's clock with another's
     */
    public ProgressionCommandGateway(final RestClient restClient, final String systemUserId,
            final Map<String, String> extraHeaders, final RetryPolicy retryPolicy,
            final RetryPause pause, final Clock clock) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.extraHeaders = Map.copyOf(extraHeaders);
        this.retryPolicy = retryPolicy;
        this.pause = pause;
        this.clock = clock;
    }

    /**
     * Posts one {@code add-court-register} body, retrying only what a retry could fix.
     *
     * <p>The command is attributed to the run's caller — the user who shared the results where the
     * message named one, and the configured system identity otherwise. That is what the legacy does
     * ({@code ProcessOutboundCourtRegister/index.js:21} sends {@code this.input.cjscppuid}, which is
     * literally {@code undefined} in the one test that asserts it), and the identity is resolved once
     * so that every attempt of a run posts as the same caller.
     *
     * @param body     the serialised document, sent byte for byte
     * @param caller   who the command is posted as
     * @param deadline the instant the run holding the claim promised to have stopped by; no wait is
     *                 taken across it, and no attempt is started whose own worst case — a connect
     *                 timeout and a read timeout — would end after it
     * @return the status progression answered with, which is {@code 202} or nothing
     * @throws uk.gov.hmcts.cp.yotresultsdistribution.domain.SubmissionFailedException carrying
     *     {@code NON_TRANSIENT} when the command was refused or answered with a success the contract
     *     does not define, and {@code TRANSIENT} when the attempts ran out with the outcome still
     *     unresolved or the run's budget ran out first; the status progression answered travels with
     *     it where there was one
     */
    public int post(final byte[] body, final CallerIdentity caller, final Instant deadline) {
        // Resolved once per command, not once per attempt: a retry made as a different caller would
        // be a second, differently attributed command for the same hearing.
        final String identity = caller.orSystem(systemUserId);
        final int maxAttempts = retryPolicy.maxAttempts();
        OptionalInt lastStatus = OptionalInt.empty();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!attemptFitsInside(deadline)) {
                throw overran(attempt, lastStatus);
            }
            final Outcome outcome = attempt(body, identity, attempt);
            if (outcome.status().isPresent()) {
                lastStatus = outcome.status();
            }
            if (outcome.accepted()) {
                return outcome.status().orElse(ACCEPTED);
            }
            if (outcome.refusal().isPresent()) {
                throw failed(
                        FailureClassification.NON_TRANSIENT, outcome.refusal().get(), lastStatus);
            }
            if (attempt < maxAttempts) {
                final Duration wait = retryPolicy.waitAfter(attempt, outcome.retryAfter());
                if (!fitsInside(wait, deadline)) {
                    throw overran(attempt, lastStatus);
                }
                waitFor(wait);
            }
        }

        // The transport's half of N42: an unresolved verdict handed back, never a register written
        // off. The FAILED row and the exhausted_message_id are the guard's, on the last permitted
        // delivery.
        LOG.error("The add-court-register attempts are exhausted with the outcome unresolved, so "
                + "the delivery is handed back. attempts={}", maxAttempts);
        throw failed(FailureClassification.TRANSIENT, ReasonCode.SUBMISSION_TRANSIENT, lastStatus);
    }

    /**
     * One attempt, classified.
     *
     * <p>The configured mesh headers are applied first and the two contract headers set over them, so
     * a header configured under a contract name replaces the contract value rather than joining it:
     * two values of {@code CJSCPPUID} is an ambiguous caller to a service authorising on identity,
     * and two content types is a 415.
     */
    private Outcome attempt(final byte[] body, final String identity, final int attempt) {
        Outcome outcome;
        try {
            outcome = restClient.post()
                    .uri(PATH)
                    .headers(headers -> {
                        extraHeaders.forEach(headers::set);
                        headers.setContentType(
                                MediaType.parseMediaType(ADD_COURT_REGISTER_MEDIA_TYPE));
                        headers.set(IDENTITY_HEADER, identity);
                    })
                    .body(body)
                    .exchange((request, response) -> classify(response, attempt));
        } catch (ResourceAccessException unreachable) {
            // Connect failure, read timeout, connection dropped: the command may or may not have
            // been applied. Unknown is not failed, and it is retried rather than written off.
            //
            // Only the exception's type travels with the line. What the pipeline acts on is the
            // classification; what a human acts on is what it *was* - a refused connection, a read
            // that timed out, a route the mesh dropped - and the class distinguishes those where
            // the bounded reason code cannot. The message does not go with it: it belongs to
            // whatever raised it, and a line carries this service's own words (Principle VII),
            // which the sweep in TelemetryPrivacyTest now holds by construction.
            LOG.warn("The add-court-register attempt did not reach a verdict, so the outcome is "
                    + "unknown; retrying. attempt={} cause={}", attempt,
                    unreachable.getClass().getName());
            outcome = Outcome.retryable(Optional.empty(), OptionalInt.empty());
        }
        return outcome;
    }

    /**
     * What progression's answer means.
     *
     * <p>Nothing progression wrote is read: the body of a court-register refusal can name a child,
     * and this method's decisions all travel into a log line and a dead-letter description.
     */
    private Outcome classify(final ClientHttpResponse response, final int attempt)
            throws IOException {
        final HttpStatusCode status = response.getStatusCode();
        final int code = status.value();
        final Outcome outcome;

        if (code == ACCEPTED) {
            outcome = Outcome.accepted(code);
        } else if (status.is2xxSuccessful()) {
            // The contract declares one success. A 200 or a 204 means something other than the
            // command endpoint answered — a proxy, or a route that no longer reaches it — and
            // calling it success would complete the run `submitted` for a command nothing enqueued.
            LOG.error("Progression answered a success this contract does not define, so the command "
                    + "cannot be assumed enqueued. status={}", code);
            outcome = Outcome.refused(ReasonCode.SUBMISSION_NOT_ACCEPTED, code);
        } else if (RetryPolicy.retryable(code)) {
            // 408, 429 and every server error — the two the legacy never retries and the family it
            // abandons. AxiosRetryWrapper.js:34 abandons on any status at or below 429, which makes
            // 408 and 429 — the two statuses that most plainly mean "ask me again" — the
            // least-retried failures it has (C3). A `Retry-After` is read from any of them: a 503
            // carrying one is a service saying when it expects to be back, and the header exists
            // because the server knows better than the client's schedule.
            LOG.warn("Progression could not process the command. attempt={} status={}",
                    attempt, code);
            outcome = Outcome.retryable(
                    RetryPolicy.retryAfter(response.getHeaders()), OptionalInt.of(code));
        } else {
            // Any other 4xx: the request was understood and declined, so the same bytes will be
            // declined again and the delivery budget is finite.
            LOG.error("Progression refused the command, and no redelivery can change that. "
                    + "status={}", code);
            outcome = Outcome.refused(ReasonCode.SUBMISSION_REJECTED, code);
        }
        return outcome;
    }

    /**
     * Whether a whole attempt can be made and still leave the run inside its claim.
     *
     * <p><strong>The attempt's own worst case is reserved, not merely the instant it starts.</strong>
     * Asking only whether the deadline had passed would licence the attempt this check exists to
     * stop: a POST begun with a millisecond of budget left can hang on its connect timeout and then
     * on its read timeout, and it finishes long after the claim behind it became reclaimable. A
     * second runner is by then working the same request, and {@code add-court-register}
     * <em>appends</em> — so the two POSTs are two registers for one hearing, which no downstream
     * sweep distinguishes from a re-share and no log line records as a fault.
     *
     * <p>The reservation is the shared policy's, read out of this client's connect and read
     * timeouts — the same pair {@code config/PropertiesValidator} budgets the whole run with, so the
     * transport cannot believe an attempt is cheaper than startup believed it was. Refusing costs
     * nothing that is not refunded: the delivery is handed back TRANSIENT and the redelivery gets
     * the whole deadline again, with nothing sent.
     *
     * <p>It subsumes the plain "has the budget gone" reading it replaced — an attempt cannot fit
     * inside a budget that is already spent — so there is one gate on the way into an attempt and
     * not two that could disagree.
     *
     * @param deadline the instant the run promised to have stopped by
     * @return whether the attempt fits inside what is left
     */
    private boolean attemptFitsInside(final Instant deadline) {
        return retryPolicy.attemptFitsBefore(clock.instant(), deadline);
    }

    /**
     * Whether a wait can be taken and still leave the run inside its claim.
     *
     * <p>The check that stops a retry policy outliving the claim it was made under. Waiting is the
     * longest thing this class does — a doubling back-off, or whatever number progression put in a
     * {@code Retry-After} — and a wait begun with less budget than it needs ends with the run past
     * its deadline and the claim behind it reclaimable, which for {@code add-court-register} is a
     * second register for the hearing. So a wait that would not finish inside the budget is not
     * shortened, it is refused: a truncated wait would hammer a service that has just asked for
     * room, and the delivery is worth more handed back with a fresh budget than spent here.
     *
     * @param wait     the wait that would be taken
     * @param deadline the instant the run promised to have stopped by
     * @return whether the wait ends before the deadline
     */
    private boolean fitsInside(final Duration wait, final Instant deadline) {
        return clock.instant().plus(wait).isBefore(deadline);
    }

    /**
     * The run's budget ran out before its attempts did.
     *
     * <p>TRANSIENT, and under its own reason: the command did not fail, the run ran out of the time
     * its claim guarantees it, and the redelivery gets a whole fresh budget with nothing sent twice.
     * It is deliberately not {@code SUBMISSION_TRANSIENT} — a rise in this code is a capacity signal
     * about this service, where a rise in that one is a signal about progression.
     *
     * @param attempt    the attempt the budget ran out on
     * @param lastStatus the last status progression answered, where anything answered
     * @return the failure to raise
     */
    private static SubmissionFailedException overran(
            final int attempt, final OptionalInt lastStatus) {
        LOG.error("The run's budget ran out before the add-court-register command was resolved, so "
                        + "the delivery is handed back rather than posted under a claim that may "
                        + "have been reclaimed. attempt={} reason={}",
                attempt, ReasonCode.PROCESSING_DEADLINE_EXCEEDED.code());
        return failed(FailureClassification.TRANSIENT,
                ReasonCode.PROCESSING_DEADLINE_EXCEEDED, lastStatus);
    }

    private void waitFor(final Duration wait) {
        try {
            pause.pause(wait);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting to retry the add-court-register command, so the "
                    + "delivery is handed back unresolved.");
            throw new SubmissionFailedException(
                    FailureClassification.TRANSIENT, ReasonCode.SUBMISSION_TRANSIENT);
        }
    }

    /**
     * The failure, carrying the status progression answered where there was one.
     *
     * <p>An empty status is a real answer: a connect failure has none, and a row carrying an invented
     * one would say an attempt was answered when nothing answered. A status is also all that may
     * cross this boundary — it is bounded, and says nothing about a child.
     */
    private static SubmissionFailedException failed(final FailureClassification classification,
            final ReasonCode reason, final OptionalInt status) {
        return new SubmissionFailedException(classification, reason,
                status.isPresent() ? status.getAsInt() : null);
    }

    /**
     * What one attempt came to.
     *
     * @param accepted   whether progression accepted the command
     * @param refusal    the bounded reason where the answer is final, empty where another attempt
     *                   may change it
     * @param retryAfter the wait progression asked for, where it asked for a usable one
     * @param status     the status progression answered with, where anything answered
     */
    private record Outcome(boolean accepted, Optional<ReasonCode> refusal,
            Optional<Duration> retryAfter, OptionalInt status) {

        private static Outcome accepted(final int status) {
            return new Outcome(true, Optional.empty(), Optional.empty(), OptionalInt.of(status));
        }

        private static Outcome refused(final ReasonCode reason, final int status) {
            return new Outcome(false, Optional.of(reason), Optional.empty(), OptionalInt.of(status));
        }

        private static Outcome retryable(
                final Optional<Duration> retryAfter, final OptionalInt status) {
            return new Outcome(false, Optional.empty(), retryAfter, status);
        }
    }
}
