package uk.gov.hmcts.cp.yotresultsdistribution.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.fileservice.FileServicePayloadStore;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub.StubPayloadFileStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadFileStore;

/**
 * The one port both halves write a file through, chosen by the one mode key that names it.
 *
 * <p>A file in the framework file service is what the two outward legs have in common: the nightly
 * run stores a render payload and hands systemdocgenerator its id, and the morning report stores a
 * CSV and hands notificationnotify its id. So the port's two adapters belong behind
 * {@link FileServiceNeeded} rather than behind the generation half's switch - a report pod with the
 * e-mail output on and no {@code PayloadFileStore} is a pod that cannot start, and that is what a
 * bean declared on {@link LiveGenerationConfig} made it.
 *
 * <p><strong>The mode key does not move with them.</strong> {@code yotresultsdistribution.generation
 * .fileservice-mode} is still where a deployment says LIVE or STUB, because it is one file service
 * and renaming the setting would be a change to every chart and every stack file to say the same
 * thing. What changed is which pods read it, not what it is called.
 *
 * <p>The two beans are the ones {@link LiveGenerationConfig} and {@link StubGenerationConfig}
 * declared, moved verbatim; nothing generation-only followed them. The renderer, the notifier and
 * the flag reader are still chosen where they were, because a pod that e-mails a report renders
 * nothing, notifies no Youth Offending Team and reads no cutover flag.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@Conditional(FileServiceNeeded.class)
public class FileServiceConfig {

    /** The prefix the mode key lives under. */
    private static final String GENERATION = "yotresultsdistribution.generation";

    /** The value the mode key holds where the real adapter is wanted, and the default. */
    private static final String LIVE = "LIVE";

    /** The value it holds where the logging no-op is wanted; never in a deployed environment. */
    private static final String STUB = "STUB";

    /**
     * The payload-store port, served by the two inserts into the framework file service.
     *
     * <p>Over the second datasource by name. It is {@code defaultCandidate = false} precisely so
     * that nothing finds it by type, so this is the one place that asks for it - and asking for it
     * here rather than anywhere else is what keeps the processed log's own client the one everything
     * else injects.
     *
     * @param jdbcClient the file-service client, by name
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = GENERATION, name = "fileservice-mode", havingValue = LIVE,
            matchIfMissing = true)
    public PayloadFileStore payloadFileStore(
            @Qualifier(FileServiceDataSourceConfig.JDBC_CLIENT) final JdbcClient jdbcClient) {
        return new FileServicePayloadStore(jdbcClient);
    }

    /**
     * The same port, served by the logging no-op, for a local run that stands up no file service.
     *
     * <p>Never the default and never reachable where the service is deployed:
     * {@link PropertiesValidator} refuses {@code STUB} outright wherever the deployed credential
     * source is in use, because a stub reachable in production is the pod whose metrics say the run
     * succeeded and whose attachment was never written (constitution Principle V).
     *
     * @return the port
     */
    @Bean
    @ConditionalOnProperty(prefix = GENERATION, name = "fileservice-mode", havingValue = STUB)
    public PayloadFileStore stubPayloadFileStore() {
        return new StubPayloadFileStore();
    }
}
