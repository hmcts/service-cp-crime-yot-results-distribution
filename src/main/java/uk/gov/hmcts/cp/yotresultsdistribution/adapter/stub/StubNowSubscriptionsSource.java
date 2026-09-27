package uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;

/**
 * A now-subscriptions source that asks reference data nothing, and says so on every call.
 *
 * <p>For local runs and for the container suites whose subject is settlement, the processed log and
 * health rather than the register's recipients: standing a reference-data stub up for those would
 * make what they prove depend on infrastructure their scenarios never mention.
 *
 * <p><strong>It answers rather than refuses</strong>, and that is a decision worth stating. The core
 * reads this port between the group-proceedings decision and the transformation — where the legacy
 * orchestrator reads it — so a stub that threw would turn every stubbed run into a transient
 * failure and the suites that need a completed run could never have one. What makes an empty answer
 * safe is not the stub's own caution but the startup rules around it: {@code PropertiesValidator}
 * refuses this mode wherever the deployed credential source is in use, and refuses it alongside a
 * <em>live</em> payload source, so the one shape an empty answer could quietly mislabel — a real
 * hearing completing {@code no-subscriptions} because nobody was asked — cannot be configured.
 *
 * <p>It logs at INFO on every invocation, because a stub that is quiet is a stub somebody will
 * mistake for the real thing (constitution Principle V).
 */
public class StubNowSubscriptionsSource implements NowSubscriptionsSource {

    private static final Logger LOG = LoggerFactory.getLogger(StubNowSubscriptionsSource.class);

    /** Reference data's shape for "nobody is subscribed", which is an answer and not a silence. */
    private static final String NOBODY = "{\"nowSubscriptions\":[]}";

    private final JsonNode nobody;

    /**
     * Pre-parses the empty answer.
     *
     * @param objectMapper the shared, contract-configured mapper
     */
    public StubNowSubscriptionsSource(final ObjectMapper objectMapper) {
        this.nobody = objectMapper.readTree(NOBODY);
    }

    @Override
    public JsonNode subscriptionsOn(final LocalDate registerDay, final CallerIdentity caller) {
        LOG.info("STUB now-subscriptions source invoked: reference data is not asked, and the "
                + "answer is that nobody is subscribed. registerDay={}", registerDay);
        // The same immutable tree on every call. Nothing may mutate a node it did not construct.
        return nobody;
    }
}
