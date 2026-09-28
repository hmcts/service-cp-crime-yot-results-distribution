package uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.application.HearingPayloadSource;
import uk.gov.hmcts.cp.yotresultsdistribution.config.PayloadFailureMode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.YotResultsDistributionProperties;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;

/**
 * A payload source that fetches nothing, and says so on every call.
 *
 * <p>For local runs and for the container suites whose subject is settlement, the processed log and
 * health rather than the payload: standing a cache and an HTTP stub up for those would make what
 * they prove depend on infrastructure their scenarios never mention. Startup refuses it wherever the
 * deployed credential source is in use, so it can never be the thing a deployed pod is quietly
 * running (constitution Principle V). It logs at INFO on every invocation, because a stub that is
 * quiet is a stub somebody will mistake for the real thing.
 *
 * <p>It is also where the simulated transient failure lives. The switch is a configuration property
 * read once at construction — never a field in the message and never an HTTP endpoint, either of
 * which would be a production fault-injection surface on a service whose whole purpose is not losing
 * work.
 *
 * <p>The placeholder carries nothing that resembles hearing content. Every defendant on a court
 * register is a youth, and a stub payload that looked like a hearing would invite both a test to
 * depend on its shape and a reader to think this service had one.
 */
public class StubHearingPayloadSource implements HearingPayloadSource {

    private static final Logger LOG = LoggerFactory.getLogger(StubHearingPayloadSource.class);

    /**
     * The empty claim-check envelope this source answers with.
     *
     * <p>An <em>envelope</em>, not an arbitrary object: the pipeline reads the hearing and the share
     * instant out of what the payload source returns, and a placeholder without them is a
     * transformation failure rather than the no-op a stub is for — every message dead-lettered by a
     * pod configured exactly as it meant to be. It carries a hearing that gathered nobody and a
     * court centre with no code, so the run completes {@code no-defendants}, which is the outcome a
     * payload with no register in it earns anyway.
     *
     * <p>Nothing in it resembles hearing content. Every defendant on a court register is a youth,
     * and a stub payload that looked like a hearing would invite both a test to depend on its shape
     * and a reader to think this service had one.
     */
    private static final String PLACEHOLDER = """
            {"stub":true,"note":"no hearing payload is fetched by the stub payload source",
             "sharedTime":"1970-01-01T00:00:00Z","hearing":{"courtCentre":{}}}
            """;

    private final PayloadFailureMode failureMode;
    private final JsonNode placeholder;

    /**
     * Reads the configured failure mode and pre-parses the placeholder payload.
     *
     * @param properties   the typed settings, for the stub's failure mode
     * @param objectMapper the shared, contract-configured mapper
     */
    public StubHearingPayloadSource(
            final YotResultsDistributionProperties properties, final ObjectMapper objectMapper) {
        this.failureMode = properties.stub().payloadFailureMode();
        this.placeholder = objectMapper.readTree(PLACEHOLDER);
    }

    @Override
    public JsonNode fetch(final DistributionCommand command) {
        LOG.info("STUB payload source invoked: nothing is fetched, a placeholder is returned. "
                        + "source={} requestId={} hearingId={} hearingDay={} failureMode={}",
                command.source(), command.requestId(), command.hearingId(), command.hearingDay(),
                failureMode);
        if (failureMode == PayloadFailureMode.TRANSIENT) {
            LOG.warn("STUB payload source is configured to fail transiently. source={} requestId={}",
                    command.source(), command.requestId());
            throw new PayloadUnavailableException(ReasonCode.PAYLOAD_UNAVAILABLE);
        }
        // The same immutable tree on every call. Nothing may mutate a node it did not construct, so
        // sharing it is safe and building a fresh copy per request would be waste.
        return placeholder;
    }
}
