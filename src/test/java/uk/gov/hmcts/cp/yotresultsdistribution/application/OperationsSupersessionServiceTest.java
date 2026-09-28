package uk.gov.hmcts.cp.yotresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtCentreDay;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedRegisterSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * The rollback lever, and the three guards its command did not have.
 *
 * <p>Every one of them was confirmed by the design owner on 2026-09-19 and is a requirement:
 * admitted only while the flag says OFF, with no override and fail-closed on unreadable; two
 * bounds on the instant; and a dry run that answers the count and changes nothing. What is
 * asserted below is that each of them <strong>stops the write</strong>, not merely that it
 * answers - a guard that refuses after the registers are gone is not a guard.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the rollback that gives a period of registers up")
class OperationsSupersessionServiceTest {

    /** The moment every call in this suite is made at. */
    private static final Instant NOW = Instant.parse("2026-09-15T09:30:00Z");

    /** A bound inside the age this endpoint may reach back over. */
    private static final Instant YESTERDAY = NOW.minus(Duration.ofDays(1));

    /** How far back a rollback may reach, which a deployment sets. */
    private static final Duration MAX_AGE = Duration.ofDays(30);

    private static final int SUPERSEDED = 47;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private RegisterStore registers;

    @Mock
    private FeatureFlagReader flag;

    /** The service, over the store and the lever the cases stub. */
    private OperationsSupersessionService service() {
        return new OperationsSupersessionService(registers, flag, MAX_AGE, clock);
    }

    /** One recorded register, carrying the shared instant the dry run bounds on. */
    private static RegisterRecord recordedAt(final Instant registerTime) {
        return new RegisterRecord(UUID.randomUUID(), UUID.randomUUID(), registerTime,
                new CourtCentreDay(UUID.randomUUID(),
                        YESTERDAY.atZone(ZoneOffset.UTC).toLocalDate()),
                registerTime, "file.pdf", "YOUTH", RecordedFlagState.OFF, null);
    }

    /** One active unbatched register before the bound, as the report's projection carries it. */
    private static RecordedRegisterSummary summaryAt(final Instant registerTime) {
        return new RecordedRegisterSummary(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                YESTERDAY.atZone(ZoneOffset.UTC).toLocalDate(), registerTime, 3600L);
    }

    @Nested
    @DisplayName("while the flag says OFF")
    class AdmittedWhileTheLegacyGenerates {

        @Test
        @DisplayName("the period is given up and the count answered")
        void it_should_supersede_the_period_and_answer_the_count() {
            when(flag.read()).thenReturn(new FlagDecision.Disabled());
            when(registers.supersedeSharedBefore(YESTERDAY)).thenReturn(SUPERSEDED);

            final Supersession answered = service().supersede(YESTERDAY, false);

            assertThat(answered)
                    .isEqualTo(new Supersession(SUPERSEDED, YESTERDAY, false));
            verify(flag, times(1))
                    .read();
        }

        @Test
        @DisplayName("a dry run answers the count and supersedes nothing")
        void a_dry_run_should_read_the_count_and_write_nothing() {
            when(flag.read()).thenReturn(new FlagDecision.Disabled());
            when(registers.recordedUnbatchedBefore(YESTERDAY))
                    .thenReturn(List.of(summaryAt(YESTERDAY.minusSeconds(60)),
                            summaryAt(YESTERDAY.minusSeconds(120))));
            when(registers.recordedWhileOff()).thenReturn(List.of(
                    recordedAt(YESTERDAY.minusSeconds(30)),
                    recordedAt(NOW.minusSeconds(10))));

            final Supersession answered = service().supersede(YESTERDAY, true);

            assertThat(answered.superseded())
                    .as("the two reads whose predicates together are the write's, and the one "
                            + "recorded after the bound is outside it")
                    .isEqualTo(3);
            assertThat(answered.dryRun()).isTrue();
            verify(registers, never()).supersedeSharedBefore(any());
        }

        @Test
        @DisplayName("a store that will not answer is supersession-failed, not a count")
        void a_store_that_refused_should_refuse_rather_than_report_a_rollback() {
            when(flag.read()).thenReturn(new FlagDecision.Disabled());
            when(registers.supersedeSharedBefore(YESTERDAY)).thenThrow(
                    new StoreUnavailableException("supersede the registers shared before",
                            new IllegalStateException("the connection went away")));

            assertThatThrownBy(() -> service().supersede(YESTERDAY, false))
                    .isInstanceOfSatisfying(OperationsRefusedException.class, refused -> {
                        assertThat(refused.reason())
                                .isEqualTo(OperationsReason.SUPERSESSION_FAILED);
                        assertThat(refused.getMessage())
                                .doesNotContain("the connection went away");
                    });
        }
    }

    @Nested
    @DisplayName("while the flag does not say OFF")
    class RefusedWhileThisServiceIsLive {

        @Test
        @DisplayName("the flag ON refuses FLAG_ON and the store is never touched")
        void the_flag_on_should_refuse_and_never_reach_the_store() {
            when(flag.read()).thenReturn(new FlagDecision.Enabled());

            assertThatThrownBy(() -> service().supersede(YESTERDAY, false))
                    .isInstanceOfSatisfying(OperationsRefusedException.class, refused ->
                            assertThat(refused.reason()).isEqualTo(OperationsReason.FLAG_ON));

            verifyNoInteractions(registers);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(FlagDecision.UnreadableReason.class)
        @DisplayName("a flag that cannot be read fails closed, whatever stopped it being read")
        void an_unreadable_flag_should_fail_closed(
                final FlagDecision.UnreadableReason cause) {

            when(flag.read()).thenReturn(new FlagDecision.Unreadable(cause));

            assertThatThrownBy(() -> service().supersede(YESTERDAY, false))
                    .isInstanceOfSatisfying(OperationsRefusedException.class, refused ->
                            assertThat(refused.reason())
                                    .isEqualTo(OperationsReason.FLAG_UNREADABLE));

            verifyNoInteractions(registers);
        }

        @Test
        @DisplayName("both refusal lines spell the code the ProblemDetail will carry")
        void the_refusal_lines_should_carry_the_wire_spelling() {
            final List<String> onLines;
            try (CapturedLog log = CapturedLog.capturing(OperationsSupersessionService.class)) {
                when(flag.read()).thenReturn(new FlagDecision.Enabled());
                assertThatThrownBy(() -> service().supersede(YESTERDAY, false))
                        .isInstanceOf(OperationsRefusedException.class);
                onLines = log.renderings();
            }

            final List<String> unreadableLines;
            try (CapturedLog log = CapturedLog.capturing(OperationsSupersessionService.class)) {
                when(flag.read()).thenReturn(
                        new FlagDecision.Unreadable(FlagDecision.UnreadableReason.TIMED_OUT));
                assertThatThrownBy(() -> service().supersede(YESTERDAY, false))
                        .isInstanceOf(OperationsRefusedException.class);
                unreadableLines = log.renderings();
            }

            assertThat(onLines)
                    .as("a reason slot carries the wire spelling wherever it is written, so a "
                            + "line and the body answered beside it grep as the same code")
                    .anyMatch(line ->
                            line.contains("reason=" + OperationsReason.FLAG_ON.wire()));
            assertThat(unreadableLines)
                    .as("and this one differs from its constant, which is what makes the rule "
                            + "worth pinning rather than worth assuming")
                    .anyMatch(line ->
                            line.contains("reason=" + OperationsReason.FLAG_UNREADABLE.wire()));
        }

        @Test
        @DisplayName("a dry run is refused by the flag too - it is the same lever")
        void a_dry_run_should_be_admitted_by_the_same_flag() {
            when(flag.read()).thenReturn(new FlagDecision.Enabled());

            assertThatThrownBy(() -> service().supersede(YESTERDAY, true))
                    .isInstanceOf(OperationsRefusedException.class);

            verifyNoInteractions(registers);
        }
    }

    @Nested
    @DisplayName("the bounds on the instant")
    class TheTwoBounds {

        @Test
        @DisplayName("an absent instant is refused and is never defaulted")
        void an_absent_instant_should_be_refused() {
            assertThatThrownBy(() -> service().supersede(null, false))
                    .isInstanceOfSatisfying(OperationsRefusedException.class, refused -> {
                        assertThat(refused.reason())
                                .isEqualTo(OperationsReason.MISSING_ARGUMENT);
                        assertThat(refused.argument()).isEqualTo("sharedBefore");
                    });

            verifyNoInteractions(registers, flag);
        }

        @Test
        @DisplayName("an instant that has not happened yet is refused before the flag is read")
        void an_instant_in_the_future_should_be_refused() {
            assertThatThrownBy(() -> service().supersede(NOW.plusSeconds(1), false))
                    .isInstanceOfSatisfying(OperationsRefusedException.class, refused ->
                            assertThat(refused.reason())
                                    .isEqualTo(OperationsReason.SUPERSEDE_INSTANT_IN_FUTURE));

            verifyNoInteractions(registers, flag);
        }

        @Test
        @DisplayName("an instant older than the configured bound is refused")
        void an_instant_older_than_the_bound_should_be_refused() {
            assertThatThrownBy(() -> service()
                    .supersede(NOW.minus(MAX_AGE).minusSeconds(1), false))
                    .isInstanceOfSatisfying(OperationsRefusedException.class, refused ->
                            assertThat(refused.reason())
                                    .isEqualTo(OperationsReason.SUPERSEDE_INSTANT_TOO_OLD));

            verifyNoInteractions(registers, flag);
        }

        @Test
        @DisplayName("an instant exactly at the bound is admitted")
        void an_instant_at_the_bound_should_be_admitted() {
            when(flag.read()).thenReturn(new FlagDecision.Disabled());
            when(registers.supersedeSharedBefore(NOW.minus(MAX_AGE))).thenReturn(0);

            assertThat(service().supersede(NOW.minus(MAX_AGE), false).superseded()).isZero();
        }
    }
}
