package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * The file-service datasource in readiness, but only for the few seconds a night it is in use.
 *
 * <p>The rule is neither of the two obvious ones. "Never in readiness" would leave a run that is
 * about to fail every batch {@code PAYLOAD_STORE_UNAVAILABLE} looking like a healthy pod, and a
 * replica the platform keeps sending work to. "Always in readiness" would roll the pods over a
 * database this service does not own, does not read, and will not touch again until 18:00 - taking
 * the intake half, which was working perfectly, down with it.
 *
 * <p>So the component is <strong>in</strong> the readiness group and stays quiet outside a run. Both
 * halves are asserted here, because either one alone is a rule that does nothing: membership without
 * the run condition is the second bad answer, and the run condition without membership is the first.
 * Membership is read from the shipped {@code application.yaml}, since a readiness group is
 * configuration and the only way to lose it is to leave a name off that line.
 *
 * <p>The third claim is what a health poll costs. This service opens the file-service pool for a few
 * seconds a night; a probe on every poll would hold a connection to somebody else's database open
 * all day to answer a question whose answer cannot matter until the evening. Idle, the component
 * answers without asking - and says that it did not ask, because "UP" and "UP, unverified" are
 * different claims and only one of them is true at 09:00.
 *
 * <p>Every case reaches the indicator through {@link #answer()} rather than calling
 * {@code health()} directly. A health check that throws is worse than one that answers DOWN - it
 * takes the endpoint that would have reported the problem down with it - so "it answers at all" is
 * the first assertion of every case rather than an assumption underneath them. The same reason a
 * probe that throws must be read as DOWN rather than propagated.
 */
@DisplayName("the file-service datasource's health component")
class FileServiceRunHealthIndicatorTest {

    /**
     * The component's name in the health endpoint: Spring derives it from the bean name with the
     * {@code HealthIndicator} suffix removed, so this is the name the readiness group has to carry
     * for the run condition to gate anything at all.
     */
    private static final String COMPONENT = "fileServiceRun";

    /** The two components the readiness group has named since 001. */
    private static final String STORE_COMPONENT = "db";
    private static final String STARTUP_COMPONENT = "intakeStartup";

    /** The subscription's component, which must never join it (spec FR-011). */
    private static final String BROKER_COMPONENT = "publicEvents";

    /** The shipped configuration, read as text: what the group names is what the file says. */
    private static final Path APPLICATION_YAML =
            Path.of("src", "main", "resources", "application.yaml");

    private final CountingProbe probe = new CountingProbe();

    private final FileServiceRunHealthIndicator indicator =
            new FileServiceRunHealthIndicator(probe);

    // --- between runs ---------------------------------------------------------------------------

    @Test
    @DisplayName("between runs an unreachable file service is not this pod's problem")
    void should_report_up_between_runs_however_the_file_service_is() {
        probe.answers(Health.down().build());

        assertThat(answer().getStatus())
                .as("a file service that is unreachable at 09:00 is not a pod that rolls: the "
                        + "intake half is still recording registers, which is the half that must "
                        + "not stop")
                .isEqualTo(Status.UP);
        assertThat(answer().getDetails()).containsEntry("run", "idle");
    }

    @Test
    @DisplayName("between runs nothing is asked of the file service at all")
    void should_not_probe_the_file_service_between_runs() {
        answer();
        answer();

        assertThat(probe.calls())
                .as("a probe on every health poll would hold a connection to somebody else's "
                        + "database open all day for an answer that cannot matter until 18:00")
                .isZero();
        assertThat(answer().getDetails())
                .as("and it says so, because UP and UP-unverified are different claims")
                .containsEntry("fileservice", "not-probed");
    }

    // --- during a run ---------------------------------------------------------------------------

    @Test
    @DisplayName("during a run an unreachable file service takes readiness down")
    void should_report_down_while_a_run_is_in_progress_and_the_file_service_is_unreachable() {
        probe.answers(Health.down().build());
        indicator.recordRunStarted();

        assertThat(answer().getStatus())
                .as("a run that cannot store a payload is a run about to fail every batch "
                        + "PAYLOAD_STORE_UNAVAILABLE, and a replica the platform should stop "
                        + "sending work to")
                .isEqualTo(Status.DOWN);
        assertThat(answer().getDetails())
                .containsEntry("run", "in-progress")
                .containsEntry("fileservice", Status.DOWN.getCode());
    }

    @Test
    @DisplayName("during a run a file service that answers leaves readiness alone")
    void should_report_up_while_a_run_is_in_progress_and_the_file_service_answers() {
        indicator.recordRunStarted();

        assertThat(answer().getStatus()).isEqualTo(Status.UP);
        assertThat(answer().getDetails())
                .containsEntry("run", "in-progress")
                .containsEntry("fileservice", Status.UP.getCode());
        assertThat(probe.calls())
                .as("during a run the question is asked, and asked freshly each time")
                .isPositive();
    }

    @Test
    @DisplayName("a probe that throws is DOWN, not an endpoint that throws")
    void should_read_a_probe_that_throws_as_down() {
        probe.throwsUp();
        indicator.recordRunStarted();

        assertThat(answer().getStatus())
                .as("a datasource contributor is entitled to throw at a pool that cannot connect; "
                        + "a health endpoint is not entitled to pass it on")
                .isEqualTo(Status.DOWN);
        assertThat(answer().getDetails())
                .as("reported by what it is, never by what the driver said: a connection failure's "
                        + "message carries hosts, databases and sometimes credentials")
                .containsEntry("fileservice", Status.DOWN.getCode());
    }

    // --- the end of a run -----------------------------------------------------------------------

    @Test
    @DisplayName("when the run ends the file service stops gating readiness, however the run ended")
    void should_stop_gating_readiness_once_the_run_has_ended() {
        probe.answers(Health.down().build());
        indicator.recordRunStarted();
        assertThat(answer().getStatus()).isEqualTo(Status.DOWN);

        indicator.recordRunEnded();

        assertThat(answer().getStatus())
                .as("a flag left set by a run that threw would gate readiness on a database "
                        + "nothing is using until somebody restarts the pod")
                .isEqualTo(Status.UP);
        assertThat(answer().getDetails()).containsEntry("run", "idle");
    }

    // --- what it gates --------------------------------------------------------------------------

    @Test
    @DisplayName("the readiness group names it, or the run condition gates nothing")
    void should_be_in_the_readiness_group_so_a_run_can_gate_it() throws IOException {
        assertThat(readinessGroup())
                .as("the component decides when the file service matters; the group is what lets "
                        + "that decision reach readiness at all")
                .contains(COMPONENT, STORE_COMPONENT, STARTUP_COMPONENT)
                .doesNotContain(BROKER_COMPONENT);
    }

    @Test
    @DisplayName("nothing in the details identifies a batch, a court centre or a database")
    void should_report_only_bounded_words() {
        indicator.recordRunStarted();

        assertThat(answer().getDetails())
                .as("a health endpoint is scraped and indexed like any other surface "
                        + "(constitution Principle VII)")
                .containsOnlyKeys("run", "fileservice");
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * The indicator's answer, obtained through an assertion.
     *
     * <p>A component that throws takes the health endpoint with it, which is the one thing a health
     * check may never do, so every case states that first and reads the details second.
     *
     * @return what the indicator answered
     */
    private Health answer() {
        final AtomicReference<Health> answered = new AtomicReference<>();
        assertThatCode(() -> answered.set(indicator.health()))
                .as("a health check answers; one that throws takes down the endpoint that would "
                        + "have reported the problem")
                .doesNotThrowAnyException();
        return answered.get();
    }

    /**
     * The components the shipped readiness group names.
     *
     * <p>Read as text rather than bound as properties, for the reason {@code TelemetryPrivacyTest}
     * reads the logging block that way: the claim is about what the file <em>ships</em>, and a
     * binder would hand back the merged view of every source, including whatever the harness set.
     *
     * @return the names on the group's {@code include} line
     */
    private static List<String> readinessGroup() throws IOException {
        final String yaml = Files.readString(APPLICATION_YAML);
        final int group = yaml.indexOf("\n        readiness:");
        assertThat(group).as("application.yaml declares a readiness group").isNotNegative();
        final String include = yaml.substring(group).lines()
                .map(String::strip)
                .filter(line -> line.startsWith("include:"))
                .findFirst()
                .orElse("");
        assertThat(include).as("the readiness group names its members").startsWith("include:");
        return Arrays.stream(include.substring("include:".length()).split(","))
                .map(String::strip)
                .toList();
    }

    /**
     * The file-service datasource's contributor, which counts what it is asked.
     *
     * <p>Counting is half the suite: "does not gate readiness between runs" and "is not consulted
     * between runs" are different claims, and only the second one is about what a health poll costs
     * a database this service does not own.
     */
    private static final class CountingProbe implements HealthIndicator {

        private final AtomicInteger probes = new AtomicInteger();
        private final AtomicReference<Health> answer = new AtomicReference<>(Health.up().build());
        private final AtomicBoolean throwing = new AtomicBoolean();

        private void answers(final Health health) {
            answer.set(health);
        }

        private void throwsUp() {
            throwing.set(true);
        }

        private int calls() {
            return probes.get();
        }

        @Override
        public Health health() {
            probes.incrementAndGet();
            if (throwing.get()) {
                throw new IllegalStateException("the pool has no connection to give");
            }
            return answer.get();
        }
    }
}
