package uk.gov.hmcts.cp.yotresultsdistribution.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub.StubNowSubscriptionsSource;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NowSubscriptionsSource;

/**
 * The now-subscriptions source that asks reference data nothing, kept for local runs and for the
 * suites that address no register.
 *
 * <p>The sibling of {@link StubPayloadConfig}, chosen the same way and refused the same way: it
 * contributes a bean only when {@code yotresultsdistribution.referencedata.mode} says {@code STUB}, so an
 * environment that says nothing gets the real adapter, and {@link PropertiesValidator} refuses
 * {@code STUB} wherever the deployed credential source is in use — and alongside a live payload
 * source, which is the one combination where an empty answer could be mistaken for reference data
 * having been asked.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.referencedata", name = "mode", havingValue = "STUB")
public class StubSubscriptionsConfig {

    /**
     * The now-subscriptions port, served by the logging empty answer.
     *
     * @param objectMapper the shared, contract-configured mapper
     * @return the port
     */
    @Bean
    public NowSubscriptionsSource nowSubscriptionsSource(final ObjectMapper objectMapper) {
        return new StubNowSubscriptionsSource(objectMapper);
    }
}
