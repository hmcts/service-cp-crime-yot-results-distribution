package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunReport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.GeneratedRegisters;
import uk.gov.hmcts.cp.yotresultsdistribution.support.GenerationStackSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;

/**
 * The one lever, asserted where it is actually pulled: a whole service, a real App Configuration
 * read, and a night's registers waiting to be generated (T053).
 *
 * <p>{@code FeatureFlagGateTest} pins what a decision means and {@code AppConfigurationFlagReaderTest}
 * pins how each answer is read. Neither can say what this suite says, which is that the two are
 * wired to each other and to a run: that a pod deployed with generation enabled reads the store its
 * settings name, under the key and label they name, and that the answer decides whether anything is
 * asked of systemdocgenerator at all.
 *
 * <p>Three nights, and the middle one is the one the fail-closed rule exists for. A flag that says
 * OFF is the cutover working; a store that cannot answer is an outage this service rides out; and
 * both must leave the night's registers exactly where they were, because a register batched under a
 * flag that did not say ON is a second copy of a day the legacy has already sent.
 *
 * <p><strong>An acceptance suite (tasks.md [A]).</strong> Nothing here is driven test-first: it
 * records what the assembled service does.
 */
@DisplayName("the flag gate, end to end")
class FlagGateEndToEndIT {

    private static final LocalDate REGISTER_DAY = LocalDate.parse("2026-08-20");

    private static final Instant REGISTER_TIME = Instant.parse("2026-08-20T16:30:00Z");

    /**
     * This case's registers, at a court centre nobody else holds a row for.
     *
     * <p>Minted per case rather than per suite, so a case reads only its own rows and no case has to
     * empty a table another is using.
     */
    private final GeneratedRegisters registers =
            new GeneratedRegisters(service, UUID.randomUUID());

    private static GenerationStackSupport stack;

    private static ConfigurableApplicationContext service;

    @BeforeAll
    static void startTheWholeStack() {
        PostgresTestSupport.applyFlyway();
        ProcessedLogTestSupport.dataSource();
        stack = GenerationStackSupport.start();
        service = GenerationStackSupport.startService(stack.settings());
    }

    @AfterAll
    static void stopTheWholeStack() {
        service.close();
        stack.close();
    }

    @BeforeEach
    void oneRegisterWaitingForTonight() {
        stack.reset();
        registers.record(UUID.randomUUID(), REGISTER_DAY, REGISTER_TIME);
    }

    @Test
    @DisplayName("a flag that says OFF leaves the night's registers exactly where they were")
    void a_flag_that_says_off_should_skip_the_run_and_request_nothing() {
        stack.flagIs(false);

        final RunReport report = run();

        assertThat(report.gateDecision())
                .as("the cutover working: the legacy is generating tonight and this service is not")
                .isEqualTo(new GateDecision.Skipped(GateDecision.Reason.FLAG_OFF));
        assertThat(stack.renderRequests())
                .as("not one request reached systemdocgenerator - which the stack would have "
                        + "answered 202, so a run that ignored the flag would look like a quiet "
                        + "night in every other reading")
                .isEmpty();
        assertThat(registers.statuses())
                .as("and the registers are untouched, unbatched and waiting: a row stamped under a "
                        + "flag that did not say ON is a second copy of a day the legacy has sent")
                .containsExactly("RECORDED");
        assertThat(registers.batches()).isEmpty();
    }

    @Test
    @DisplayName("a store that cannot answer skips the run under its own reason, not OFF")
    void a_flag_nobody_could_read_should_skip_the_run_and_say_which_of_the_two_it_was() {
        stack.flagIsUnreadable();

        final RunReport report = run();

        assertThat(report.gateDecision())
                .as("fail-closed and off are the same restraint and not the same night: one is the "
                        + "cutover working, the other is an outage this service rode out, and only "
                        + "the second is somebody's to fix")
                .isEqualTo(new GateDecision.Skipped(GateDecision.Reason.FLAG_UNREADABLE));
        assertThat(stack.renderRequests()).isEmpty();
        assertThat(registers.statuses()).containsExactly("RECORDED");
    }

    @Test
    @DisplayName("a flag that says ON generates the night, and systemdocgenerator is asked")
    void a_flag_that_says_on_should_proceed_and_request_the_render() {
        stack.flagIs(true);

        final RunReport report = run();

        assertThat(report.gateDecision())
                .as("the reading the whole downstream half waits for")
                .isEqualTo(new GateDecision.Proceed(false));
        assertThat(registers.batches())
                .as("one batch for this court centre and day")
                .hasSize(1);
        assertThat(requestsNaming(registers.batches().getFirst()))
                .as("asked for over the endpoint the deployment configured, correlated on the batch "
                        + "identity - the shared store holds other suites' registers too, so what "
                        + "is counted here is this night's own batch and not the traffic")
                .hasSize(1)
                .first(as(InstanceOfAssertFactories.STRING))
                .contains("\"templateIdentifier\":\"OEE_Layout5\"")
                .contains("\"originatingSource\":\"YotResultsDistributionService\"");
        assertThat(registers.batchStatuses())
                .as("GENERATING says a render was asked for and not that a document exists")
                .containsExactly(BatchStatus.GENERATING.name());
    }

    /**
     * The render requests this stack received that name one batch.
     *
     * @param batchId the batch the render was requested for
     * @return the bodies naming it, in arrival order
     */
    private static List<String> requestsNaming(final UUID batchId) {
        return stack.renderRequests().stream()
                .filter(body -> body.contains(batchId.toString()))
                .toList();
    }

    /**
     * Runs the night through the bean the schedule would have fired.
     *
     * @return what the run reported
     */
    private static RunReport run() {
        return service.getBean(RegisterGenerationJob.class).run();
    }

}
