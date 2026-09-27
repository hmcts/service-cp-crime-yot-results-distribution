package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Holds the morning report's settings to the values the plan's configuration table documents, and
 * holds the rendering limit to a value of its own rather than to the generation half's.
 *
 * <p>The binding half of the report's configuration surface. What the settings <em>refuse</em> is
 * {@code ConfigurationValidationTest.ReportRefusals}, in the suite that owns every other startup
 * refusal this service makes; what they bind to is here, because a default nobody reads back is a
 * default that moves without anybody noticing.
 *
 * <p>{@code yotresultsdistribution.intake.gauge-refresh} is asserted here too, although it is not a
 * {@code yotresultsdistribution.report} key. It is the intake half's, deliberately: the sweep that reads it
 * runs on a pod where both other halves are switched off, so a key on a record such a pod does not
 * bind is a key it cannot read - and that is exactly the kind of fact a test has to state.
 */
class ReportPropertiesTest {

    /** The emulator connection string, the local and CI credential. */
    private static final String CONNECTION_STRING_PROPERTY =
            "yotresultsdistribution.servicebus.connection-string="
                    + "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                    + "SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";

    /** The identity the query-side payload fallback authorises with; the live source demands one. */
    private static final String PAYLOAD_IDENTITY_PROPERTY =
            "yotresultsdistribution.results.system-user-id=9f61bdbb-6f1a-4c0f-9a3d-6b8f0f1c2a44";

    private static final String PROGRESSION_ENDPOINT_PROPERTY =
            "yotresultsdistribution.progression.base-url=http://localhost:8080";

    private static final String PROGRESSION_IDENTITY_PROPERTY =
            "yotresultsdistribution.progression.system-user-id=4d3c2b1a-9e8f-4a7b-8c6d-5e4f3a2b1c09";

    private static final String REFDATA_ENDPOINT_PROPERTY =
            "yotresultsdistribution.referencedata.base-url=http://localhost:8080";

    private static final String REFDATA_IDENTITY_PROPERTY =
            "yotresultsdistribution.referencedata.system-user-id=2c7b1e64-0f4a-4f0e-9b2c-8d1a6f3e5c07";

    /** The key user story 5 scenario 3 is about: one threshold, changed in one environment. */
    private static final String THRESHOLD = "yotresultsdistribution.report.request-terminal-within=";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertiesTestConfiguration.class)
                    .withPropertyValues(CONNECTION_STRING_PROPERTY, PAYLOAD_IDENTITY_PROPERTY,
                            PROGRESSION_ENDPOINT_PROPERTY, PROGRESSION_IDENTITY_PROPERTY,
                            REFDATA_ENDPOINT_PROPERTY, REFDATA_IDENTITY_PROPERTY);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({YotResultsDistributionProperties.class, GenerationProperties.class,
        FeatureFlagProperties.class, ReportProperties.class})
    @Import(PropertiesValidator.class)
    static class PropertiesTestConfiguration {
    }

    @Test
    @DisplayName("the entry cap is five thousand unless a deployment says otherwise")
    void max_entries_defaults_to_5000() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context.getBean(ReportProperties.class).maxEntries())
                    .as("a default in the record as well as in the yaml, for the reason every"
                            + " other one here is: a missing configuration file cannot silently"
                            + " change how much of a bad morning support is shown")
                    .isEqualTo(5000);
        });

        runner.withPropertyValues("yotresultsdistribution.report.max-entries=250").run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context.getBean(ReportProperties.class).maxEntries())
                    .as("and a deployment's own ceiling is its own, which is what makes the cap a"
                            + " setting rather than a constant")
                    .isEqualTo(250);
        });
    }

    @Test
    @DisplayName("the report ships the defaults the plan's configuration table documents")
    void report_defaults_are_the_documented_ones() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            final ReportProperties report = context.getBean(ReportProperties.class);

            assertThat(report.enabled())
                    .as("off locally, so a developer's run writes nobody a report")
                    .isFalse();
            assertThat(report.cron()).isEqualTo("0 0 7 * * MON-FRI");
            assertThat(report.zone()).isEqualTo("Europe/London");
            assertThat(report.zoneOverrideAcknowledged()).isFalse();
            assertThat(report.lockAtMostFor()).isEqualTo(Duration.ofMinutes(15));
            assertThat(report.requestTerminalWithin()).isEqualTo(Duration.ofMinutes(30));
            assertThat(report.notifiedWithin()).isEqualTo(Duration.ofMinutes(15));
            assertThat(report.batchGeneratedWithin())
                    .as("its own value since 004, because the generation half's threshold it used"
                            + " to borrow now answers a different question")
                    .isEqualTo(Duration.ofMinutes(10));
            assertThat(report.maxEntries())
                    .as("the entry cap: five thousand exceptions is far past the morning anybody"
                            + " reads one by one, and a report with no ceiling is one bad night"
                            + " away from a line-per-entry write that outlives its own lock")
                    .isEqualTo(5000);
            assertThat(report.email().enabled())
                    .as("false until the notificationnotify template is provided")
                    .isFalse();
            assertThat(report.email().templateId()).isNull();
            assertThat(report.email().recipients()).isEmpty();

            assertThat(context.getBean(YotResultsDistributionProperties.class).intake().gaugeRefresh())
                    .as("the sweep's own interval, on the intake record a pod with both other"
                            + " halves off still binds")
                    .isEqualTo(Duration.ofMinutes(10));
        });
    }

    /**
     * FR-014, and the case that would otherwise have silently tripled this threshold.
     *
     * <p>The rendering limit had no default of its own and resolved from the generation half's grace
     * period, which 004 renames and lengthens from ten minutes to thirty. Had the resolution been
     * left in place, the 07:00 report would have started calling a batch late after thirty minutes
     * instead of ten as a side effect of an increment about something else. The two durations answer
     * different questions now - "when should support be told a render is late" and "when does a run
     * give up and re-batch" - so the report keeps ten minutes of its own, and the context this is
     * asserted over has the generation half set to thirty so that a threshold that followed it would
     * be visible here rather than anywhere else.
     */
    @Test
    @DisplayName("the rendering limit is ten minutes of the report's own, whatever the run waits")
    void batch_generated_within_defaults_to_ten_minutes_without_reading_the_generation_half() {
        runner.withPropertyValues("yotresultsdistribution.generation.stale-after=30m").run(context -> {
            assertThat(context).hasNotFailed();
            final GenerationProperties generation = context.getBean(GenerationProperties.class);

            assertThat(context.getBean(ReportProperties.class).batchGeneratedWithin())
                    .as("ten minutes, which is what the report has always used, and not the"
                            + " thirty the run now waits before it gives up on a batch")
                    .isEqualTo(Duration.ofMinutes(10))
                    .isNotEqualTo(generation.staleAfter());
        });
    }

    /**
     * A deployment that states its own rendering limit gets its own. The default in the record is a
     * default and not a constant, which is what makes the threshold per environment.
     */
    @Test
    @DisplayName("an explicitly set rendering limit is the deployment's own")
    void an_explicit_batch_generated_within_is_honoured() {
        runner.withPropertyValues("yotresultsdistribution.report.batch-generated-within=20m")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context.getBean(ReportProperties.class).batchGeneratedWithin())
                            .isEqualTo(Duration.ofMinutes(20));
                });
    }

    /**
     * SC-005, user story 5 scenario 3: a threshold is per environment and takes effect on the next
     * run, without a release.
     *
     * <p>Asserted rather than asserted about. Two contexts differing in exactly one property yield
     * two resolved thresholds that differ and nothing else that does - which is what "changed in
     * that environment alone" means when it is a claim a test can fail on.
     */
    @Test
    @DisplayName("a threshold changed in one environment changes that environment and nothing else")
    void a_changed_threshold_takes_effect_in_that_environment_alone() {
        final ReportProperties shipped = boundWith(THRESHOLD + "30m");
        final ReportProperties widened = boundWith(THRESHOLD + "90m");

        assertThat(shipped.requestTerminalWithin()).isEqualTo(Duration.ofMinutes(30));
        assertThat(widened.requestTerminalWithin()).isEqualTo(Duration.ofMinutes(90));
        assertThat(widened).usingRecursiveComparison()
                .ignoringFields("requestTerminalWithin")
                .as("one setting moved, and the schedule, the lock, the other two limits and the"
                        + " e-mail output did not")
                .isEqualTo(shipped);
    }

    /** Binds the report record in a context carrying the given extra properties. */
    private ReportProperties boundWith(final String... properties) {
        final AtomicReference<ReportProperties> bound = new AtomicReference<>();
        runner.withPropertyValues(properties).run(context -> {
            assertThat(context).hasNotFailed();
            bound.set(context.getBean(ReportProperties.class));
        });
        return bound.get();
    }
}
