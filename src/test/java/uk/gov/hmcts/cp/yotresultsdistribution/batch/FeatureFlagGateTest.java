package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision.UnreadableReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Proceed;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Reason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Skipped;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * The one lever, as the run that obeys it sees it.
 *
 * <p>{@code AppConfigurationFlagReaderTest} says what the store's answers mean; this says what the
 * service does about them, and the two are separate because only one of them can be got wrong
 * without anybody noticing. A reader that mis-parses is a run that stops. A gate that proceeds on a
 * flag that did not say ON is a night on which this service and the legacy both generate, and every
 * Youth Offending Team receives the same register twice - the one failure in this flow that reaches
 * a child's family rather than a dashboard.
 *
 * <p>So the shape asserted here is fail-closed and has no fourth branch: ON proceeds, OFF skips,
 * unreadable skips, and the only way past a flag that did not say ON is an operator saying so out
 * loud through {@code --ignore-flag}. Unreadable is deliberately not a special case - it is treated
 * exactly as off, because "nobody could tell me whether the legacy is still generating" and "the
 * legacy is still generating" call for the same restraint (research §3, constitution Cutover Rule).
 *
 * <p>Three things are asserted alongside the decision, because a skipped night leaves no other
 * trace at all:
 *
 * <ul>
 *   <li>the <strong>skipped counter</strong>, labelled with what the flag said rather than with
 *       what the gate decided. The two vocabularies differ on purpose: the decision has one
 *       {@link Reason#FLAG_UNREADABLE}, because a run either starts or does not, while the counter
 *       keeps all six unreadable causes apart, because an absent endpoint, a refused identity and a
 *       slow store are three different things to go and fix;</li>
 *   <li>the <strong>{@code flag_read_ok} gauge</strong>, which says whether App Configuration is
 *       answering at all and therefore stays up on a flag read as off - off is an answer - and goes
 *       down only where nothing could be read. It moves on an overridden run too: the override
 *       changes what this service does, not whether the store replied;</li>
 *   <li>the <strong>override warning</strong>, which is the only line a night that should not have
 *       run leaves behind.</li>
 * </ul>
 *
 * <p>Nothing here reaches a register, a recipient or a defendant, so the privacy claim is not that
 * the gate omits personal data but that it has none to omit. What keeps that true is the last case:
 * every value the gate writes into a line is a bounded code from one of the two vocabularies, so an
 * implementer who later wants to log a batch, an endpoint or another system's words has to widen
 * the vocabulary deliberately rather than by interpolation (constitution Principle VII).
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the flag gate")
class FeatureFlagGateTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING = "T030 implements the flag gate; this is its red run";

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double ABSENT = -1;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GenerationMetrics metrics = new GenerationMetrics(registry);
    private final FeatureFlagReader reader = mock(FeatureFlagReader.class);
    private final FeatureFlagGate gate = new FeatureFlagGate(reader, metrics);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Puts one reading in front of the gate and asks it to decide.
     *
     * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} so that the
     * seam's refusal is recorded as an assertion rather than ending the case, and the decision it
     * did not produce is then asserted on as {@code null} - the red run is the decision and the
     * green run is the same assertions unchanged.
     *
     * @param reading    what the store answered this time
     * @param ignoreFlag whether an operator overrode the flag deliberately
     * @return what the gate decided, or {@code null} where the seam refused
     */
    private GateDecision decide(final FlagDecision reading, final boolean ignoreFlag) {
        when(reader.read()).thenReturn(reading);
        final AtomicReference<GateDecision> decided = new AtomicReference<>();
        softly.assertThatCode(() -> decided.set(gate.decide(ignoreFlag)))
                .as(PENDING)
                .doesNotThrowAnyException();
        return decided.get();
    }

    private double skipped(final String reason) {
        final Counter counter = registry.find(GenerationMetrics.GENERATION_SKIPPED)
                .tag(GenerationMetrics.REASON_TAG, reason)
                .counter();
        return counter == null ? ABSENT : counter.count();
    }

    private double flagReadOk() {
        final Gauge gauge = registry.find(GenerationMetrics.FLAG_READ_OK).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    private static List<String> warnings(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * The flag said this service generates.
     */
    @Nested
    @DisplayName("a flag read as on")
    class ReadAsOn {

        @Test
        void a_flag_read_as_on_should_let_the_run_proceed() {
            final GateDecision decision = decide(FlagDecision.ON, false);

            softly.assertThat(decision)
                    .as("the only reading that starts a night on its own, and the override is "
                            + "false because nothing was overridden: an operator standing in for a "
                            + "cutover and a cutover are not the same night")
                    .isEqualTo(new Proceed(false));
        }

        @Test
        void a_run_the_flag_allowed_should_not_be_counted_as_skipped() {
            decide(FlagDecision.ON, false);

            softly.assertThat(registry.find(GenerationMetrics.GENERATION_SKIPPED).counter())
                    .as("the skipped counter is what a night that generated nothing is read by; a "
                            + "series that also moves on the nights that did generate answers "
                            + "nothing")
                    .isNull();
        }

        @Test
        void a_readable_flag_should_leave_the_read_gauge_up() {
            decide(FlagDecision.ON, false);

            softly.assertThat(flagReadOk())
                    .as("the store answered, which is the whole of this gauge's question")
                    .isEqualTo(1);
        }
    }

    /**
     * The flag said the legacy generates, which is the cutover working in the other direction.
     */
    @Nested
    @DisplayName("a flag read as off")
    class ReadAsOff {

        @Test
        void a_flag_read_as_off_should_skip_the_run() {
            final GateDecision decision = decide(FlagDecision.OFF, false);

            softly.assertThat(decision)
                    .as("one lever: the flag decides which implementation generates, and this "
                            + "service does nothing at all while it is the legacy's turn")
                    .isEqualTo(new Skipped(Reason.FLAG_OFF));
        }

        @Test
        void a_skipped_run_should_be_counted_under_what_the_flag_said() {
            decide(FlagDecision.OFF, false);

            softly.assertThat(skipped(FlagDecision.OFF.code()))
                    .as("a night nobody generated on looks identical from outside to a scheduler "
                            + "that never fired; this series is the difference")
                    .isEqualTo(1);
        }

        @Test
        void an_off_flag_should_leave_the_read_gauge_up_because_it_is_an_answer() {
            decide(FlagDecision.OFF, false);

            softly.assertThat(flagReadOk())
                    .as("the gauge reports whether App Configuration answers, not what it "
                            + "answered; off is an answer and the skipped counter is where the two "
                            + "are told apart")
                    .isEqualTo(1);
        }
    }

    /**
     * Nobody could say whose turn it was, which this service treats exactly as the legacy's.
     */
    @Nested
    @DisplayName("a flag that could not be read")
    class Unreadable {

        @Test
        void an_unreadable_flag_should_skip_the_run_under_one_bounded_reason() {
            final GateDecision decision =
                    decide(new FlagDecision.Unreadable(UnreadableReason.TIMED_OUT), false);

            softly.assertThat(decision)
                    .as("fail-closed: a store that did not answer is not evidence that the legacy "
                            + "stopped, and generating twice is the one failure that reaches a "
                            + "child's family")
                    .isEqualTo(new Skipped(Reason.FLAG_UNREADABLE));
        }

        @ParameterizedTest
        @EnumSource(UnreadableReason.class)
        void every_unreadable_cause_should_skip_the_run_and_keep_its_own_series(
                final UnreadableReason cause) {

            final GateDecision decision = decide(new FlagDecision.Unreadable(cause), false);

            softly.assertThat(decision)
                    .as("six causes, one decision: a run either starts or does not, and which "
                            + "outage stopped it is the counter's question rather than the gate's")
                    .isEqualTo(new Skipped(Reason.FLAG_UNREADABLE));
            softly.assertThat(skipped(cause.code()))
                    .as("and the counter keeps them apart, because an absent endpoint, a refused "
                            + "identity and a slow store are three different things to go and fix")
                    .isEqualTo(1);
        }

        @Test
        void an_unreadable_flag_should_not_be_counted_as_an_off_one() {
            decide(new FlagDecision.Unreadable(UnreadableReason.ACCESS_DENIED), false);

            softly.assertThat(skipped(FlagDecision.OFF.code()))
                    .as("an outage this service rode out fail-closed is not the cutover working, "
                            + "and one series carrying both would say it was")
                    .isEqualTo(ABSENT);
        }

        @Test
        void an_unreadable_flag_should_lower_the_read_gauge() {
            decide(new FlagDecision.Unreadable(UnreadableReason.CALL_FAILED), false);

            softly.assertThat(flagReadOk())
                    .as("the only reading that means the store itself needs looking at")
                    .isZero();
        }
    }

    /**
     * The one way past a flag that did not say ON, and the only night that leaves a warning.
     */
    @Nested
    @DisplayName("an operator who overrode the flag")
    class Overridden {

        @Test
        void an_override_should_let_a_run_proceed_over_a_flag_that_is_off() {
            final GateDecision decision = decide(FlagDecision.OFF, true);

            softly.assertThat(decision)
                    .as("an operator regenerating a batch by hand may need to do so before the "
                            + "flag is turned on, and the run report has to be able to say a night "
                            + "went ahead that the flag would have stopped")
                    .isEqualTo(new Proceed(true));
        }

        @Test
        void an_override_should_let_a_run_proceed_over_a_flag_that_could_not_be_read() {
            final GateDecision decision =
                    decide(new FlagDecision.Unreadable(UnreadableReason.NOT_CONFIGURED), true);

            softly.assertThat(decision)
                    .as("the CLI is the escape hatch for exactly this: a stack with no App "
                            + "Configuration endpoint would otherwise have no way to generate at all")
                    .isEqualTo(new Proceed(true));
            softly.assertThat(flagReadOk())
                    .as("the override changes what this service does, not whether the store "
                            + "replied, so the gauge still reports the read that failed")
                    .isZero();
        }

        @Test
        void an_overridden_run_should_not_be_counted_as_a_skipped_one() {
            decide(FlagDecision.OFF, true);

            softly.assertThat(registry.find(GenerationMetrics.GENERATION_SKIPPED).counter())
                    .as("the run went ahead; counting it as skipped would hide the night most "
                            + "worth seeing under the label of the nights that did nothing")
                    .isNull();
        }

        @Test
        void an_override_over_a_flag_that_is_on_should_not_claim_one_was_needed() {
            try (CapturedLog log = CapturedLog.capturing(FeatureFlagGate.class)) {
                final GateDecision decision = decide(FlagDecision.ON, true);

                softly.assertThat(decision)
                        .as("--ignore-flag on a stack that is already cut over overrides nothing, "
                                + "and a run report that said otherwise would make every CLI run "
                                + "look like one taken against the platform's wishes")
                        .isEqualTo(new Proceed(false));
                softly.assertThat(warnings(log))
                        .as("nothing was overridden, so there is nothing to warn about")
                        .isEmpty();
            }
        }

        @Test
        void an_overridden_run_should_be_warned_about_under_its_bounded_reason() {
            try (CapturedLog log = CapturedLog.capturing(FeatureFlagGate.class)) {
                decide(FlagDecision.OFF, true);

                softly.assertThat(warnings(log))
                        .as("WARN and not INFO: this is the one night on which this service "
                                + "generated while the flag said the legacy was, and it is the "
                                + "only trace of it")
                        .hasSize(1);
                softly.assertThat(String.join(System.lineSeparator(), warnings(log)))
                        .as("named by the bounded reason the run report carries, so the log and "
                                + "the report agree on what happened")
                        .contains(Reason.OVERRIDDEN.code());
            }
        }
    }

    /**
     * When the flag is asked, which is once per decision and never from a memory of the last one.
     */
    @Nested
    @DisplayName("reading the flag")
    class Reading {

        @Test
        void the_flag_should_be_read_once_per_decision_and_never_cached() {
            when(reader.read()).thenReturn(FlagDecision.ON);

            softly.assertThatCode(() -> {
                gate.decide(false);
                gate.decide(false);
            }).as(PENDING).doesNotThrowAnyException();

            verify(reader, times(2)).read();
        }

        @Test
        void an_override_should_still_read_the_flag_so_the_gauge_can_move() {
            decide(FlagDecision.OFF, true);

            verify(reader, times(1)).read();
        }
    }

    /**
     * What the gate is allowed to write down.
     *
     * <p>The gate never sees a register, a recipient or a defendant, so the claim is not that it
     * omits personal data but that it holds none. This is what keeps that true: every value it
     * interpolates into a line is a bounded code from one of the two vocabularies, so logging a
     * batch, an endpoint or another system's words about the store is a change somebody has to make
     * on purpose (constitution Principle VII).
     */
    @Nested
    @DisplayName("what the gate writes down")
    class WhatItWritesDown {

        @Test
        void every_value_the_gate_logs_should_be_a_bounded_code() {
            final List<String> logged = new ArrayList<>();

            try (CapturedLog log = CapturedLog.capturing(FeatureFlagGate.class)) {
                decide(FlagDecision.ON, false);
                decide(FlagDecision.OFF, false);
                decide(new FlagDecision.Unreadable(UnreadableReason.MALFORMED), false);
                decide(FlagDecision.OFF, true);

                log.events().stream()
                        .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                        .map(ILoggingEvent::getArgumentArray)
                        .filter(Objects::nonNull)
                        .flatMap(Stream::of)
                        .map(String::valueOf)
                        .forEach(logged::add);
            }

            softly.assertThat(logged)
                    .as("bounded codes and nothing else: a log line reaches an index the whole "
                            + "estate reads and is kept for a year, and every defendant on the "
                            + "register this night was about is a child")
                    .isSubsetOf(boundedVocabulary());
        }

        private List<String> boundedVocabulary() {
            return Stream.concat(
                            Stream.of(Reason.values())
                                    .flatMap(reason -> Stream.of(reason.code(), reason.name())),
                            Stream.concat(
                                    Stream.of(FlagDecision.ON.code(), FlagDecision.OFF.code()),
                                    Stream.of(UnreadableReason.values())
                                            .flatMap(cause ->
                                                    Stream.of(cause.code(), cause.name()))))
                    .toList();
        }
    }
}
