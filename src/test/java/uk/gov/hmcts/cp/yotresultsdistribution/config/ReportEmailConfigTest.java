package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.notificationnotify.NotificationNotifyReportMailer;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.report.EmailReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.report.LogEventReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadFileStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ReportMailer;

/**
 * What an FR-004 pod wires when the e-mail output is the half that is switched on.
 *
 * <p>The suite {@code ReportEmailConfig} should have arrived with. T068 declared a configuration and
 * recorded two gaps instead of asserting either, and the sharper of the two is the pod this suite
 * exists for: {@code yotresultsdistribution.generation.enabled=false} with
 * {@code yotresultsdistribution.report.email.enabled=true}, which is the MVP's own deployment once the Notify
 * template exists. That pod needs a {@code PayloadFileStore} - the CSV goes into the framework file
 * service before anybody is told about it - and the store was declared behind the generation half's
 * switch, so the pod could not start.
 *
 * <p>Nothing below connects to anything. The primary store's client and transaction manager are
 * doubles, exactly as {@link ReportSchedulingConfigTest} mocks them, and the file service's own pool
 * is the real Hikari one over an address nothing dials: both pools initialise lazily by design, and
 * a wiring suite that needed a database is a wiring suite that stops being run.
 *
 * <p>The validator case is here rather than in {@code ConfigurationValidationTest} because its
 * subject is the same sentence the condition is: {@code yotresultsdistribution.fileservice.url} is required
 * by <em>whichever</em> half needs the file service, and a refusal that names only the generation
 * half is the refusal that let this pod through.
 */
@DisplayName("what a pod wires when the report is e-mailed")
class ReportEmailConfigTest {

    /** The two switches, as the properties a deployment actually sets. */
    private static final String GENERATION_ENABLED = "yotresultsdistribution.generation.enabled=";

    private static final String EMAIL_ENABLED = "yotresultsdistribution.report.email.enabled=";

    /** The file service's own address; never dialled here, and required at startup by both halves. */
    private static final String FILESERVICE_URL =
            "yotresultsdistribution.fileservice.url=jdbc:postgresql://fileservice.invalid:5432/fileservice";

    /** The setting the refusal has to name, so an operator knows which value to set. */
    private static final String FILESERVICE_URL_SETTING = "yotresultsdistribution.fileservice.url";

    /** The e-mail output's own two settings, which startup refuses the switch without. */
    private static final String[] THE_EMAIL_SETTINGS = {
        "yotresultsdistribution.report.email.template-id=8f1d5c30-27b4-4f6a-9d18-0c3b7a2e5164",
        "yotresultsdistribution.report.email.recipients=cr-support@justice.gov.uk",
        "yotresultsdistribution.endpoints.notificationnotify=http://notificationnotify.internal:8080",
        "yotresultsdistribution.endpoints.system-user-id=00000000-0000-4000-8000-000000000000",
    };

    /** The log sink and the e-mail sink: two, on the pod that e-mails. */
    private static final int TWO_SINKS = 2;

    /** And one where the output is off, which is the shipped shape until Notify has a template. */
    private static final int ONE_SINK = 1;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProcessedLogConfig.class, FileServiceDataSourceConfig.class,
                    FileServiceConfig.class, ReportEmailConfig.class,
                    EmailWiringTestConfiguration.class)
            .withPropertyValues(FILESERVICE_URL)
            .withPropertyValues(THE_EMAIL_SETTINGS);

    /** The refusals' own runner: the bound records and the validator, and no wiring at all. */
    private final ApplicationContextRunner refusals = new ApplicationContextRunner()
            .withUserConfiguration(RefusalsTestConfiguration.class)
            .withPropertyValues("yotresultsdistribution.servicebus.connection-string="
                    + "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                    + "SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;",
                    "yotresultsdistribution.results.system-user-id="
                            + "9f61bdbb-6f1a-4c0f-9a3d-6b8f0f1c2a44",
                    "yotresultsdistribution.progression.base-url=http://localhost:8080",
                    "yotresultsdistribution.progression.system-user-id="
                            + "4d3c2b1a-9e8f-4a7b-8c6d-5e4f3a2b1c09",
                    "yotresultsdistribution.referencedata.base-url=http://localhost:8080",
                    "yotresultsdistribution.referencedata.system-user-id="
                            + "2c7b1e64-0f4a-4f0e-9b2c-8d1a6f3e5c07")
            .withPropertyValues(THE_EMAIL_SETTINGS);

    /**
     * Everything the configurations under assertion ask of the world outside them.
     *
     * <p>Doubles for the processed log's own client and transaction manager; the real metrics
     * facade, the real clock type, the real mapper and the real settings records, because those are
     * what the configurations read values out of. No {@code PayloadFileStore} double: which
     * configuration contributes that one is the whole subject here.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({YotResultsDistributionProperties.class, GenerationProperties.class,
        ReportProperties.class})
    static class EmailWiringTestConfiguration {

        @Bean
        JdbcClient jdbcClient() {
            return mock(JdbcClient.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        Clock courtRegisterClock() {
            return Clock.fixed(Instant.parse("2026-09-15T07:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ProcessingMetrics processingMetrics(final MeterRegistry registry) {
            return new ProcessingMetrics(registry);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JacksonConfig.contractObjectMapper();
        }
    }

    /** The bound records and the validator, which is the whole of what a refusal case needs. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({YotResultsDistributionProperties.class, GenerationProperties.class,
        FeatureFlagProperties.class, ReportProperties.class})
    @Import(PropertiesValidator.class)
    static class RefusalsTestConfiguration {
    }

    @Test
    @DisplayName("a pod that generates nothing and e-mails the report starts, and holds both sinks")
    void a_pod_with_generation_off_and_email_on_starts_and_holds_two_sinks() {
        podWith(false, true).run(context -> {
            assertThat(context)
                    .as("FR-004's deployment with the second output on: the CSV goes into the "
                            + "framework file service before anybody is told about it, so this pod "
                            + "needs a PayloadFileStore - and the only one declared was behind the "
                            + "generation half's switch, which makes this a pod that cannot start")
                    .hasNotFailed();
            assertThat(context.getBeanNamesForType(PayloadFileStore.class))
                    .as("one store, contributed by whichever half needs it rather than by the half "
                            + "that happened to want it first")
                    .hasSize(1);
            assertThat(context.getBeanNamesForType(ExceptionReportSink.class))
                    .as("the log sink and the e-mail sink, which is what "
                            + "delivered_email=delivered can be said about at all")
                    .hasSize(TWO_SINKS);
            assertThat(context.getBeansOfType(ExceptionReportSink.class).values())
                    .hasAtLeastOneElementOfType(LogEventReportSink.class)
                    .hasAtLeastOneElementOfType(EmailReportSink.class);
            assertThat(context.containsBean(FileServiceDataSourceConfig.DATA_SOURCE))
                    .as("and the second datasource is there to write through, which is the other "
                            + "half of the same relocation: the pool was behind the generation "
                            + "half's switch as well as the store over it")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("the same pod with the output off holds the log sink alone")
    void a_pod_with_email_off_holds_one_sink() {
        podWith(false, false).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(ExceptionReportSink.class))
                    .as("the shipped shape until notificationnotify provides the template: one "
                            + "sink, and the run's line says delivered_email=disabled rather than "
                            + "skipped, because nothing was decided by anybody")
                    .hasSize(ONE_SINK);
            assertThat(context.getBeanNamesForType(ReportMailer.class))
                    .as("and no mailer, because a client holding an endpoint for a call nobody "
                            + "makes is a client an operator has to be told to ignore")
                    .isEmpty();
            assertThat(context.getBeanNamesForType(PayloadFileStore.class))
                    .as("nor a store: neither half needs the file service on this pod")
                    .isEmpty();
        });
    }

    @Test
    @DisplayName("and with it on holds the mailer and the e-mail sink, one of each")
    void a_pod_with_email_on_holds_the_mailer_and_the_email_sink_as_singletons() {
        podWith(false, true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(ReportMailer.class))
                    .as("one mailer: two clients over one endpoint is two sets of timeouts to "
                            + "change on the morning notificationnotify moves")
                    .hasSize(1);
            assertThat(context.getBean(ReportMailer.class))
                    .isInstanceOf(NotificationNotifyReportMailer.class);
            assertThat(context.getBeansOfType(ExceptionReportSink.class).values()
                    .stream().filter(EmailReportSink.class::isInstance).toList())
                    .as("and one e-mail sink, because a second would send support the morning "
                            + "twice under two file ids")
                    .hasSize(1);
        });
    }

    @Test
    @DisplayName("a generating pod holds the same one store, over the same second datasource")
    void the_generating_pods_store_is_unchanged() {
        podWith(true, false).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(PayloadFileStore.class))
                    .as("nothing generation-only moves: the run still writes its render payload "
                            + "through the same port, and which configuration declares it is not "
                            + "something a batch can tell")
                    .hasSize(1);
            assertThat(context.containsBean(FileServiceDataSourceConfig.DATA_SOURCE))
                    .as("and over the same pool, reached by name so that nothing finds it by type")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("the file-service URL is required by whichever half needs the file service")
    void the_file_service_url_is_required_whenever_either_half_needs_it() {
        refusals.withPropertyValues(GENERATION_ENABLED + false, EMAIL_ENABLED + true).run(
                context -> {
                    assertThat(context)
                            .as("the e-mail output writes a CSV into the file service before it "
                                    + "asks notificationnotify to attach one, so a blank URL is a "
                                    + "morning that fails at 07:00 on a pod that started clean - "
                                    + "the rule was the generation half's alone")
                            .hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining(FILESERVICE_URL_SETTING)
                            .hasMessageContaining("yotresultsdistribution.report.email.enabled");
                });

        refusals.withPropertyValues(GENERATION_ENABLED + false, EMAIL_ENABLED + false).run(
                context -> assertThat(context)
                        .as("and it is required of neither half on a pod that writes no file at "
                                + "all: a setting demanded of a deployment that cannot use it is a "
                                + "deploy that fails for no reason")
                        .hasNotFailed());
    }

    /**
     * The runner for one pod's pair of switches.
     *
     * @param generation whether the downstream half is switched on
     * @param email      whether the report's e-mail output is
     * @return the runner, ready to run one context
     */
    private ApplicationContextRunner podWith(final boolean generation, final boolean email) {
        return runner.withPropertyValues(
                GENERATION_ENABLED + generation, EMAIL_ENABLED + email);
    }
}
