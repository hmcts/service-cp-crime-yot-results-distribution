package uk.gov.hmcts.cp.yotresultsdistribution.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub.StubDocumentRenderer;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub.StubFeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub.StubRegisterNotifier;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentRenderer;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifier;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;

/**
 * The downstream half's stand-ins, kept for local runs and for the suites that address no downstream.
 *
 * <p>One configuration for three ports rather than three configurations of one, which is the
 * opposite of the choice {@link StubPayloadConfig} and {@link StubSubscriptionsConfig} make - and
 * the reason is that these three are not chosen together. Each downstream has its own mode key, so a
 * suite can stub systemdocgenerator and keep the notifier live, and each bean below carries its own
 * condition; grouping them costs nothing because the alternative is three files whose only
 * difference is one property name. What they do share is a reason for existing: the container suites
 * whose subject is the batch state machine have no interest in a broker or an App Configuration
 * store, and the compose stack answers with WireMock when the subject <em>is</em> the downstream.
 *
 * <p><strong>The file service's stand-in is no longer among them.</strong> It moved to
 * {@link FileServiceConfig} with the live one, behind {@link FileServiceNeeded}, because the morning
 * report writes a file too and a local run of that half wants the same no-op. The mode key is
 * unchanged - {@code yotresultsdistribution.generation.fileservice-mode} is still where STUB is asked for.
 *
 * <p>Never the default, and not selectable where the service is deployed. A bean is contributed only
 * where the mode says {@code STUB}, so an environment that says nothing gets the real adapter, and
 * {@link PropertiesValidator} refuses {@code STUB} outright wherever the deployed credential source
 * is in use - a stub reachable in production is exactly the pod that skips every night, or tells a
 * Youth Offending Team nothing, while its metrics say the run succeeded (constitution Principle V).
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class StubGenerationConfig {

    /**
     * The prefix all four mode keys live under, named once because it is one prefix and not four.
     */
    private static final String GENERATION = "yotresultsdistribution.generation";

    /** The value each mode key must hold for its stub to be contributed. */
    private static final String STUB = "STUB";

    /**
     * The unreadable answer, which is {@code NOT_CONFIGURED} and not one of the other five causes.
     *
     * <p>That cause is the only one of them true of a pod with no App Configuration endpoint: a stub
     * claiming a timeout or a refused identity would put into the skipped counter a cause no store
     * ever gave.
     */
    private static final FlagDecision UNREADABLE_ANSWER =
            new FlagDecision.Unreadable(FlagDecision.UnreadableReason.NOT_CONFIGURED);

    /**
     * The renderer port, served by the stub that accepts a request and invents no document.
     *
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = GENERATION, name = "sdg-mode", havingValue = STUB)
    public DocumentRenderer documentRenderer() {
        return new StubDocumentRenderer();
    }

    /**
     * The notifier port, served by the refusing stub.
     *
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = GENERATION, name = "nn-mode", havingValue = STUB)
    public RegisterNotifier registerNotifier() {
        return new StubRegisterNotifier();
    }

    /**
     * The flag port, served by the reader that answers the configured decision and asks nobody.
     *
     * @param properties the typed settings, for the stub's configured answer
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = GENERATION, name = "flag-mode", havingValue = STUB)
    public FeatureFlagReader featureFlagReader(final YotResultsDistributionProperties properties) {
        return new StubFeatureFlagReader(decisionFor(properties.stub().flagAnswer()));
    }

    /**
     * Maps the configured answer onto the decision the port speaks in.
     *
     * @param answer the configured answer
     * @return the decision the reader returns on every read
     */
    private static FlagDecision decisionFor(final StubFlagAnswer answer) {
        return switch (answer) {
            case ON -> FlagDecision.ON;
            case OFF -> FlagDecision.OFF;
            case UNREADABLE -> UNREADABLE_ANSWER;
        };
    }
}
