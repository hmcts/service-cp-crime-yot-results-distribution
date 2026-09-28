package uk.gov.hmcts.cp.yotresultsdistribution.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub.StubRegisterSubmissionClient;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterSubmissionClient;

/**
 * The submission client for a pipeline that cannot build a register to send.
 *
 * <p>Chosen by the <strong>payload</strong> mode rather than by one of its own, because that is the
 * discriminator this service already reasons with: {@link PropertiesValidator} asks progression for
 * an endpoint and an identity only when the payload source is LIVE, on the grounds that "a local
 * stub run never fetches a hearing, so it never reaches the POST at all". The same sentence is why
 * this bean is enough for such a run, and inventing a second mode property to say it again would
 * give an operator two switches for one decision.
 *
 * <p>The stub refuses rather than acknowledging, so the sentence above is checked rather than
 * trusted: a register that somehow reached it fails the run loudly instead of being recorded as
 * delivered to nobody.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.payload", name = "mode", havingValue = "STUB")
public class StubSubmissionConfig {

    /**
     * The submission port, served by the refusing stub.
     *
     * @return the port
     */
    @Bean
    public RegisterSubmissionClient registerSubmissionClient() {
        return new StubRegisterSubmissionClient();
    }
}
