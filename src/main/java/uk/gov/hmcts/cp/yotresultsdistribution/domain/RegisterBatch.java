package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * One court centre's register for one day, as the {@code register_batch} row records it.
 *
 * <p>The batch is what the downstream half is about: the rows are grouped once, the group is given
 * an identity before anything is asked of another system, and every subsequent fact - the payload
 * that was stored, the document that came back, who was told and when it failed - is written
 * against that identity. The progression leg had no such row, which is why its failures were
 * invisible and its completions could flip registers that belonged to another day (defect P3).
 *
 * <p><strong>{@code batchId} is minted at assembly and travels as systemdocgenerator's
 * {@code sourceCorrelationId}.</strong> It is the only thing that correlates a public event back to
 * the rows the document was built from, so it is persisted before the render request rather than
 * after it: an event for a batch this service never recorded is unattributable, and an unattributed
 * document is a register nobody is told about.
 *
 * <p><strong>{@code supplementOf} and {@code supplementIndex} are the day's second document
 * (design Q27).</strong> A hearing re-shared after its key's batch has finished cannot join that
 * batch - the document is rendered and the Youth Offending Teams have been told - so its rows are
 * assembled into a batch of their own that names the batch it follows and counts up from it. A
 * day's first batch follows nothing and carries index 0, which is what the two columns default to,
 * so a writer that says nothing about them says something true.
 *
 * @param batchId           identity minted at assembly, sent as {@code sourceCorrelationId}
 * @param courtCentreId     the court centre the register is for
 * @param courtCentreOuCode that court centre's OU code, or {@code null} where the records carried
 *                          none
 * @param courtHouse        the hearing venue's court house, as progression's own column
 * @param registerDate      the London register day the batch groups
 * @param fileName          the first record's file name, as progression named the document
 * @param payloadFileId     minted before the file-service insert, sent as
 *                          {@code payloadFileServiceId}; {@code null} until then
 * @param documentFileId    the rendered PDF's file-service id, as the public event reported it;
 *                          {@code null} until the document exists
 * @param status            where the batch has got to
 * @param failureReason     the bounded reason the batch failed, or {@code null}
 * @param sdgReason         systemdocgenerator's own words about a failure, kept for support and
 *                          never logged at INFO, bounded by {@link #boundedReason(String)};
 *                          {@code null} where it said nothing
 * @param systemGenerated   true from the nightly schedule, false from the operations CLI
 * @param completedBy       which mechanism learned the outcome ({@link CompletedBy}): set on every
 *                          state reached through GENERATED and on the failures somebody outside
 *                          this service reported
 *                          ({@link BatchFailureReason#isGeneratorAttributed()}), and {@code null}
 *                          while the batch is PENDING or GENERATING and on this service's own
 *                          FAILED verdicts, which nobody outside it answered for
 * @param assembledAt       when the batch was grouped and stamped
 * @param requestedAt       when systemdocgenerator accepted the render request, or {@code null}
 * @param generatedAt       when the document was generated, or {@code null}
 * @param notifiedAt        when the last recipient was settled, or {@code null}
 * @param failedAt          when the batch reached FAILED, or {@code null}
 * @param attempts          lifetime tally of render requests made for this batch, never a control
 *                          variable
 * @param supplementOf      the batch this one follows for the same key, where a re-share arrived
 *                          after that key's earlier batches had all finished (design Q27), and
 *                          {@code null} on a day's first batch, which follows nothing
 * @param supplementIndex   0 on a day's first batch and counting up from 1 on each supplementary
 *                          one; the file name a supplement is rendered under is built from it
 */
public record RegisterBatch(
        UUID batchId,
        UUID courtCentreId,
        String courtCentreOuCode,
        String courtHouse,
        LocalDate registerDate,
        String fileName,
        UUID payloadFileId,
        UUID documentFileId,
        BatchStatus status,
        BatchFailureReason failureReason,
        String sdgReason,
        boolean systemGenerated,
        CompletedBy completedBy,
        Instant assembledAt,
        Instant requestedAt,
        Instant generatedAt,
        Instant notifiedAt,
        Instant failedAt,
        int attempts,
        UUID supplementOf,
        int supplementIndex) {

    /**
     * How much of systemdocgenerator's message this service keeps.
     *
     * <p>The same bound the column carries (data-model.md). It is a bound and not a validation:
     * how long the message is is systemdocgenerator's decision, not this service's, and a batch
     * whose failure could not be written because the renderer was verbose would stay GENERATING
     * until the next run gave up on it - the failure lost twice over.
     */
    public static final int REASON_LIMIT = 512;

    /** What a reader who cannot see the rest of the message is told about the rest. */
    private static final String TRUNCATION_MARKER = " [truncated]";

    /**
     * Bounds the renderer's words to what the row holds, and says so when it had to.
     */
    public RegisterBatch {
        sdgReason = boundedReason(sdgReason);
    }

    /**
     * The renderer's message as this service stores it.
     *
     * <p>Public because the store writes {@code sdg_reason} from a bare argument as well as from a
     * batch, and one bound applied in two places is two bounds waiting to disagree.
     *
     * <p>The marker replaces the tail rather than being appended past the bound, so the result is
     * exactly {@link #REASON_LIMIT} characters and the column never refuses it. A message that fits
     * is returned untouched, so nothing that was never truncated says it was.
     *
     * @param reason systemdocgenerator's own words, or {@code null} where it said nothing
     * @return the same words where they fit, and a marked prefix of them where they do not
     */
    public static String boundedReason(final String reason) {
        return reason == null || reason.length() <= REASON_LIMIT
                ? reason
                : reason.substring(0, REASON_LIMIT - TRUNCATION_MARKER.length())
                        + TRUNCATION_MARKER;
    }

    /**
     * The key this batch was assembled under.
     *
     * @return the court centre and register day pair
     */
    public CourtCentreDay key() {
        return new CourtCentreDay(courtCentreId, registerDate);
    }

    /**
     * How long this batch's render took, from the request systemdocgenerator accepted to the
     * outcome it was answered with.
     *
     * <p>The reading behind {@code yotresultsdistribution_generation_latency}, and it is derived from the row
     * rather than timed in memory for one reason: the pod that asked for a render is not always the
     * pod the topic delivers the outcome to. A duration measured from a field held since the request
     * would be absent on the pod that hears the answer, available only where one pod happened to do
     * both, and silently wrong across a restart. Both instants are columns of this row, so any pod
     * that can read the batch can state the reading, and two pods reading it state the same one.
     *
     * <p><strong>Both ends, or no reading at all.</strong> {@code requested_at} is written by the
     * mark that records the 202, so a batch whose render was never accepted has no start - which is
     * every batch the requesting leg fails on its own account, and every stale PENDING batch the
     * run gives up on. {@code generated_at} and {@code failed_at} are the two
     * ends a render can reach, and a batch still waiting for one has no end. Neither absence is a
     * nought to record: a timer told nought would say the round trip was instant, which is the
     * reading a fast renderer produces.
     *
     * <p><strong>And a negative reading is two clocks disagreeing rather than a round trip.</strong>
     * {@code requested_at} is the store's own {@code now()} and {@code generated_at} is
     * systemdocgenerator's account of when it rendered, so a generator running behind this service's
     * database can put the outcome before the request. That is a clock to fix, not a latency to
     * alert on, and it is answered as no reading here rather than handed on. Measured: Micrometer
     * refuses a negative measurement itself, but it refuses it by raising and logging - "'amount'
     * should not be negative but was: -42000000000", with an
     * {@code IllegalArgumentException: Timer measurements cannot be negative} attached - so passing
     * one on would put a stack trace in the estate's log index for a clock skew and leave a series
     * standing whose count does not match the renders behind it.
     *
     * @return the round trip, or empty where either end is missing or the two disagree about order
     */
    public Optional<Duration> generationRoundTrip() {
        return Optional.ofNullable(requestedAt)
                .flatMap(from -> Optional.ofNullable(renderingOutcomeAt())
                        .map(to -> Duration.between(from, to)))
                .filter(roundTrip -> !roundTrip.isNegative());
    }

    /**
     * The instant this batch's render was answered, whichever answer it got.
     *
     * <p>The document first, because a batch that has one has moved past every failure the state
     * machine admits and {@code generated_at} is the end of its render. {@code failed_at} is the end
     * of a batch that got no document at all, whether systemdocgenerator refused it or nobody ever
     * answered for it.
     *
     * @return {@code generated_at}, then {@code failed_at}, or {@code null} while neither stands
     */
    private Instant renderingOutcomeAt() {
        return generatedAt != null ? generatedAt : failedAt;
    }
}
