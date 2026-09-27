package uk.gov.hmcts.cp.yotresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;

/**
 * The one payload field that can stop a register being produced, read strictly.
 *
 * <p>The legacy orchestrator's guard is
 * {@code isGroupProceedings == null || isGroupProceedings == false}, and everything that is neither
 * of those suppresses the register. Under JavaScript's loose equality the string {@code "false"} is
 * one of them: a producer that serialised the flag as text would suppress every register it
 * published, and every one of those runs would report success. No test in the legacy repo touches
 * the field, and no court-register fixture carries it — the shapes below are constructed from the
 * source.
 *
 * <p><strong>Defect fix C7 has two halves and this is the first.</strong> Here: only a JSON boolean
 * {@code true} suppresses, and any non-boolean value is a contract anomaly that decides nothing,
 * logs a WARN and moves a counter. The second half — that the suppression is <em>recorded</em>, as
 * {@code COMPLETED, completion_reason = group-proceedings}, where the legacy records nothing — is
 * asserted in {@code DistributionPipelineTest}.
 *
 * <p>Two of the cases below change behaviour against the legacy and are marked as such; the rest
 * pin behaviour that is already right and would otherwise be free to move.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> row C7
 */
@DisplayName("GroupProceedingsPolicy")
class GroupProceedingsPolicyTest {

    /** Returned when a meter is absent, so a missing count fails as an assertion. */
    private static final double ABSENT = -1;

    /** The bounded code a non-boolean flag is counted under. */
    private static final String ANOMALY = "non-boolean-group-proceedings";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);

    private final GroupProceedingsPolicy policy = new GroupProceedingsPolicy(metrics);

    private final DistributionCommand command = new DistributionCommand(
            "RESULTS",
            UUID.fromString("9f1b8e2a-5c34-4a7d-9b1e-2f6a0d3c5e71"),
            UUID.fromString("1828f356-f746-4f2d-932b-79ef2df95c80"),
            LocalDate.parse("2020-06-01"),
            Instant.parse("2020-06-01T10:00:00Z"),
            "Hearing_Resulted");

    /** The business rule itself, which ports unchanged. */
    @Nested
    @DisplayName("the business rule")
    class BusinessRule {

        @Test
        @DisplayName("suppresses the register for a hearing flagged as group proceedings")
        void suppresses_the_register_for_group_proceedings() {
            assertThat(policy.suppresses(command, hearingFlagged("true"))).isTrue();
        }

        @Test
        @DisplayName("produces a register for a hearing flagged false")
        void produces_a_register_for_a_hearing_flagged_false() {
            assertThat(policy.suppresses(command, hearingFlagged("false"))).isFalse();
        }

        @Test
        @DisplayName("produces a register when the flag is an explicit null")
        void produces_a_register_when_the_flag_is_null() {
            // `== null` is true for both `null` and `undefined` in JavaScript, so the legacy proceeds
            // here too. Pinned rather than assumed: strict evaluation could easily have made a null
            // the anomaly case, and it is not — a null flag is a hearing that is not group
            // proceedings.
            assertThat(policy.suppresses(command, hearingFlagged("null"))).isFalse();
        }

        @Test
        @DisplayName("produces a register when the hearing does not mention the flag at all")
        void produces_a_register_when_the_flag_is_absent() {
            // Which is every court-register fixture in the legacy repo, and the overwhelming
            // majority of live hearings.
            assertThat(policy.suppresses(command, mapper.readTree("{\"id\":\"hearing-1\"}")))
                    .isFalse();
        }
    }

    /**
     * The fix. A value that is not a boolean is a contract anomaly and decides nothing — where the
     * legacy lets any truthy one suppress the register outright.
     */
    @Nested
    @DisplayName("a flag that is not a boolean (C7)")
    class NonBoolean {

        @Test
        @DisplayName("produces a register for the string \"false\", which the legacy suppresses")
        void produces_a_register_for_the_string_false() {
            // The sharpest case in the file. `"false" == false` is *true* in JavaScript, so this one
            // is the value the legacy handles correctly by accident — until you notice that
            // `"true"`, `"FALSE"` and `"no"` all suppress. The fix stops the flag's type deciding
            // anything at all.
            assertThat(policy.suppresses(command, hearingFlagged("\"false\""))).isFalse();
        }

        @Test
        @DisplayName("produces a register for the string \"true\", which the legacy suppresses")
        void produces_a_register_for_the_string_true() {
            assertThat(policy.suppresses(command, hearingFlagged("\"true\""))).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"\"true\"", "\"false\"", "\"\"", "1", "0", "[]", "{}"})
        @DisplayName("never suppresses a register on a value that is not a boolean")
        void never_suppresses_on_a_value_that_is_not_a_boolean(final String flag) {
            assertThat(policy.suppresses(command, hearingFlagged(flag))).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"\"true\"", "\"false\"", "\"\"", "1", "0", "[]", "{}"})
        @DisplayName("counts every non-boolean value as a contract anomaly")
        void counts_every_non_boolean_value(final String flag) {
            policy.suppresses(command, hearingFlagged(flag));

            assertThat(anomalies()).isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {"true", "false", "null"})
        @DisplayName("counts nothing for a flag the contract allows")
        void counts_nothing_for_a_flag_the_contract_allows(final String flag) {
            policy.suppresses(command, hearingFlagged(flag));

            assertThat(anomalies()).isEqualTo(ABSENT);
        }

        @Test
        @DisplayName("counts nothing when the hearing does not mention the flag")
        void counts_nothing_when_the_flag_is_absent() {
            policy.suppresses(command, mapper.readTree("{\"id\":\"hearing-1\"}"));

            assertThat(anomalies()).isEqualTo(ABSENT);
        }
    }

    /**
     * What the anomaly says. A counter tells an operator that a producer is sending the wrong shape;
     * only a log line tells them which hearing to go and look at.
     */
    @Nested
    @DisplayName("what a non-boolean flag is reported as")
    class Reporting {

        @Test
        @DisplayName("warns once, naming the field and the type it arrived as")
        void warns_once_naming_the_field_and_the_type() {
            try (CapturedLog log = CapturedLog.capturing(GroupProceedingsPolicy.class)) {
                policy.suppresses(command, hearingFlagged("\"false\""));

                assertThat(warnings(log)).hasSize(1);
                assertThat(warnings(log).get(0)).contains("isGroupProceedings").contains("String");
            }
        }

        @Test
        @DisplayName("carries the request and hearing the anomaly was met on")
        void carries_the_request_and_the_hearing() {
            try (CapturedLog log = CapturedLog.capturing(GroupProceedingsPolicy.class)) {
                policy.suppresses(command, hearingFlagged("1"));

                assertThat(warnings(log).get(0))
                        .contains(command.requestId().toString())
                        .contains(command.hearingId().toString());
            }
        }

        @Test
        @DisplayName("never repeats the value it was sent")
        void never_repeats_the_value_it_was_sent() {
            // The flag arriving as a string is a producer defect, and the next one may arrive
            // carrying something that is not a flag at all. A log line naming the type says
            // everything an operator needs; a log line naming the value is an unbounded field from
            // the payload in the estate's log index, on a flow whose every defendant is a child.
            try (CapturedLog log = CapturedLog.capturing(GroupProceedingsPolicy.class)) {
                policy.suppresses(command, hearingFlagged("\"Regina v the defendants named below\""));

                assertThat(warnings(log)).hasSize(1);
                assertThat(warnings(log).get(0)).doesNotContain("Regina");
            }
        }

        @Test
        @DisplayName("says nothing at all for a flag the contract allows")
        void says_nothing_for_a_flag_the_contract_allows() {
            try (CapturedLog log = CapturedLog.capturing(GroupProceedingsPolicy.class)) {
                policy.suppresses(command, hearingFlagged("true"));
                policy.suppresses(command, hearingFlagged("false"));
                policy.suppresses(command, hearingFlagged("null"));

                assertThat(warnings(log)).isEmpty();
            }
        }
    }

    /**
     * A hearing carrying the given raw JSON value as its group-proceedings flag.
     *
     * @param flag the raw JSON value
     * @return the hearing
     */
    private JsonNode hearingFlagged(final String flag) {
        return mapper.readTree(
                "{\"id\":\"hearing-1\",\"isGroupProceedings\":%s}".formatted(flag));
    }

    /**
     * How many contract anomalies have been counted.
     *
     * @return the count, or {@link #ABSENT} where the series does not exist
     */
    private double anomalies() {
        final Counter counter = registry.find(ProcessingMetrics.TRANSFORMATION_ANOMALIES)
                .tag(ProcessingMetrics.REASON_TAG, ANOMALY)
                .counter();
        return counter == null ? ABSENT : counter.count();
    }

    /**
     * Everything the policy warned about.
     *
     * @param log the capture
     * @return the WARN lines
     */
    private List<String> warnings(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
