package uk.gov.hmcts.cp.yotresultsdistribution.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.yotresultsdistribution.api.dto.FlagResponse;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;

/**
 * {@code GET /operations/flag} - what the one lever says, asked from this pod.
 *
 * <p>The endpoint the {@code check-flag} command was, and the same reader behind it: the question
 * is what <em>this</em> pod would get at 18:00, and a second reader configured differently would
 * answer a different one. It is read once per call with no cache, because a flag that was on an
 * hour ago says nothing about a rollback ten minutes ago.
 *
 * <p><strong>All three readings are {@code 200}</strong> (spec assumption 2). The command exited
 * {@code 2} on an unreadable flag and an earlier draft mapped that to {@code 503}; it is not. The
 * endpoint answered the question it exists to answer, and the answer is "nobody can read the flag".
 * A {@code 503} would be a claim about this service rather than about the flag, would read to a
 * gateway as this pod being down, and may cost the body that says why.
 *
 * <p>Nothing is interpreted here and nothing is written down. The reading's own bounded code is
 * what comes back, so the store's words about itself reach an operator's terminal no more than they
 * reach the log (constitution Principle VII).
 *
 * <p><strong>Served on every pod, generation half or no generation half</strong> (T040/T041,
 * 2026-09-21). An earlier draft made it conditional on {@code yotresultsdistribution.generation.enabled},
 * because that was where {@link FeatureFlagReader} was contributed; the decision went the other
 * way, and the reader is the thing that moved. This is the endpoint an operator checks a cutover
 * with, and "which implementation is live" is not a question only a rendering pod can be asked -
 * answering {@code 501 command-not-wired} on the pod somebody happened to reach would make the
 * lever's state look like a property of the replica. The reader is contributed wherever the
 * service runs, and on a pod with no App Configuration endpoint it answers
 * {@code UNREADABLE unreadable-not-configured}, which is a reading with a cause on it rather than
 * a refusal.
 *
 * <p><strong>Conditional on the profile</strong>, because both configurations that contribute a
 * reader, live and stub, are declared {@code !test} - so a {@code test}-profile context has the
 * switch without the bean, which is the exact shape four of this repository's context suites run
 * in.
 *
 * <p><strong>And on {@code yotresultsdistribution.operations.enabled}, the switch that decides whether this
 * service answers the operator's paths at all</strong> (FR-044). Without it this endpoint went on
 * serving {@code /operations/flag} on a pod whose operations API had been switched off - a surface
 * the switch was supposed to have taken away, and one the action filter is no longer registered in
 * front of.
 */
@RestController
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.operations", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class FlagController {

    /** The one lever's reader, asked once per call and never remembered. */
    private final FeatureFlagReader reader;

    /**
     * Creates the endpoint over the reader the nightly run uses.
     *
     * @param flagReader the one lever's reader, which never throws
     */
    public FlagController(final FeatureFlagReader flagReader) {
        this.reader = flagReader;
    }

    /**
     * Reads the flag and says what it said.
     *
     * @return the reading, as one of three bounded words and, where it could not be read, the
     *         bounded cause
     */
    @GetMapping(path = "/operations/flag", produces = MediaType.APPLICATION_JSON_VALUE)
    public FlagResponse flag() {
        return answered(reader.read());
    }

    /**
     * Maps one reading onto the body it is answered as.
     *
     * <p>A switch expression over the sealed type, as {@code CheckFlagCli.answered} was: a fourth
     * reading would not compile rather than falling to a default that said {@code OFF}.
     *
     * @param reading what the reader answered, which is never an exception
     * @return the body for that reading
     */
    private static FlagResponse answered(final FlagDecision reading) {
        return switch (reading) {
            case FlagDecision.Enabled ignored -> new FlagResponse(FlagResponse.ON, null);
            case FlagDecision.Disabled ignored -> new FlagResponse(FlagResponse.OFF, null);
            case FlagDecision.Unreadable unreadable ->
                new FlagResponse(FlagResponse.UNREADABLE, unreadable.reason().code());
        };
    }
}
