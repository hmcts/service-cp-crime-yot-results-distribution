package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPause;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.ProgressionCommandGateway;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.ProgressionRegisterSubmissionClient;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedOutputRepository;

/**
 * The real submission leg: the {@code add-court-register} transport, and the processed-log writes
 * around it.
 *
 * <p>Chosen by the <strong>payload</strong> mode, which is how {@link StubSubmissionConfig} is
 * chosen too — one switch for one decision. {@link PropertiesValidator} already asks progression for
 * an endpoint and an identity only when the payload source is LIVE, on the grounds that a local stub
 * run never fetches a hearing and so never reaches the POST; declaring a second mode property would
 * give an operator two switches for the same sentence.
 *
 * <p>Excluded from the {@code test} profile alongside the rest of the pipeline wiring, which has no
 * store for the row this leg claims before it sends.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.payload", name = "mode", havingValue = "LIVE",
        matchIfMissing = true)
public class LiveSubmissionConfig {

    /**
     * The {@code add-court-register} transport, carrying the whole retry policy.
     *
     * <p>Both timeouts are set deliberately, as they are on the other two clients: this POST is spent
     * inside the run's claim, and a request with no read timeout can outlive the claim — which for
     * this flow means a second runner posting a second register for the same hearing, because
     * {@code add-court-register} appends.
     *
     * <p>The wait between attempts is {@link Thread#sleep(java.time.Duration)}, injected rather than
     * called, so a suite can prove the back-off policy without living through it.
     *
     * <p>The clock is the run's, and it is the same bean the pipeline measures its own deadline with:
     * a transport bounding a run against a different reading of now would be a second answer to one
     * question.
     *
     * @param properties the bound settings
     * @param clock      elapsed-time source for the caller's deadline
     * @return the transport
     */
    @Bean
    public ProgressionCommandGateway progressionCommandGateway(
            final YotResultsDistributionProperties properties, final Clock clock) {
        final YotResultsDistributionProperties.Progression progression = properties.progression();
        return new ProgressionCommandGateway(
                RestClient.builder()
                        .baseUrl(progression.baseUrl())
                        .requestFactory(requestFactory(progression))
                        .build(),
                progression.systemUserId(),
                progression.headers(),
                new RetryPolicy(progression.maxAttempts(), progression.initialBackoff(),
                        progression.maxBackoff(),
                        progression.connectTimeout().plus(progression.readTimeout())),
                (RetryPause) Thread::sleep,
                clock);
    }

    /**
     * The submission port: claim the row, POST, record the outcome.
     *
     * @param outputs      the {@code processed_output} statements, each fenced on the run's claim
     * @param gateway      the transport
     * @param objectMapper the shared mapper, so what is sent is serialised exactly as everything else
     * @return the port
     */
    @Bean
    public RegisterSubmissionClient registerSubmissionClient(
            final ProcessedOutputRepository outputs, final ProgressionCommandGateway gateway,
            final ObjectMapper objectMapper) {
        return new ProgressionRegisterSubmissionClient(outputs, gateway, objectMapper);
    }

    /**
     * A request factory with both timeouts set.
     *
     * <p>The simple factory rather than a pooled client, for the reason the other two clients use it:
     * this is one POST per hearing that produced a register, and a pooled client would hold
     * background threads for the lifetime of every context.
     */
    private static ClientHttpRequestFactory requestFactory(
            final YotResultsDistributionProperties.Progression progression) {
        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(progression.connectTimeout());
        factory.setReadTimeout(progression.readTimeout());
        return factory;
    }
}
