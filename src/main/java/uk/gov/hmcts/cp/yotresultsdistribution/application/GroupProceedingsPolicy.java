package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;

/**
 * Whether a hearing's group-proceedings flag suppresses its register.
 *
 * <p>The business rule ports unchanged: a group-proceedings hearing produces no court register. What
 * does not port is how the legacy reads the flag.
 * {@code CourtRegisterOrchestrator/index.js:21-23} tests
 * {@code isGroupProceedings == null || isGroupProceedings == false} and proceeds — so every value
 * that is neither of those suppresses the register, including the <em>string</em> {@code "false"},
 * which under JavaScript's loose equality is not equal to {@code false} at all. A producer that
 * serialised the flag as text would suppress every register it published, and the run would report
 * success with nothing to show for it.
 *
 * <p><strong>Defect fix C7.</strong> Only a JSON boolean {@code true} suppresses. {@code false},
 * {@code null} and an absent field all proceed, as they do today. Any other value is a contract
 * anomaly: it does not decide anything, it is WARN-logged, and it is counted as
 * {@code non-boolean-group-proceedings} so a producer sending one is visible. The suppression itself
 * is recorded by the pipeline as {@code COMPLETED, completion_reason = group-proceedings}, which is
 * the other half of the fix — the legacy records nothing at all.
 *
 * <p>The WARN names the field and the JSON type it arrived as, never the value: this line reaches a
 * log index shared by the whole estate, and the rule that keeps payload content out of it does not
 * bend for a value that looks harmless (constitution Principle VII).
 */
public class GroupProceedingsPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(GroupProceedingsPolicy.class);

    /** The one field of the hearing payload this policy reads. */
    private static final String FLAG = "isGroupProceedings";

    private final ProcessingMetrics metrics;

    /**
     * Creates the policy over the instrument surface a contract anomaly is counted on.
     *
     * @param metrics the instrument surface
     */
    public GroupProceedingsPolicy(final ProcessingMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Whether this hearing's register is suppressed as group proceedings.
     *
     * @param command the request being run, for the correlation identifiers a WARN must carry
     * @param hearing the hearing payload, exactly as the producer sent it
     * @return whether the register is suppressed
     */
    public boolean suppresses(final DistributionCommand command, final JsonNode hearing) {
        final JsonNode flag = hearing.get(FLAG);
        final boolean suppressed;
        if (flag == null || flag.isNull()) {
            // `== null` in JavaScript is true for both an absent field and an explicit null, and
            // both proceed there too. This is the shape of every court-register payload in the
            // legacy repo and of the overwhelming majority of live hearings.
            suppressed = false;
        } else if (flag.isBoolean()) {
            suppressed = flag.booleanValue();
        } else {
            report(command, flag);
            suppressed = false;
        }
        return suppressed;
    }

    /**
     * Reports a flag that is not a boolean: once, naming the field and the JSON type it arrived as,
     * and never the value.
     *
     * @param command the request being run, for the correlation identifiers
     * @param flag    the value the hearing carried
     */
    private void report(final DistributionCommand command, final JsonNode flag) {
        LOG.warn("Hearing payload carries a non-boolean {}, so it decides nothing. "
                        + "source={} requestId={} hearingId={} type={}",
                FLAG, command.source(), command.requestId(), command.hearingId(), typeOf(flag));
        metrics.transformationAnomaly(TransformationAnomaly.NON_BOOLEAN_GROUP_PROCEEDINGS);
    }

    /**
     * The JSON type a value arrived as, named the way a reader would name it — {@code String},
     * {@code Number}, {@code Array}, {@code Object}.
     *
     * <p>The type and not the value: a flag arriving as text is a producer defect, and the next one
     * may carry something that is not a flag at all. A type says everything an operator needs; the
     * value would be an unbounded field of the payload in the estate's shared log index, on a flow
     * whose every defendant is a child (constitution Principle VII).
     *
     * @param flag the value the hearing carried
     * @return the name of its JSON type
     */
    private static String typeOf(final JsonNode flag) {
        final String type = flag.getNodeType().name();
        return type.charAt(0) + type.substring(1).toLowerCase(Locale.ROOT);
    }
}
