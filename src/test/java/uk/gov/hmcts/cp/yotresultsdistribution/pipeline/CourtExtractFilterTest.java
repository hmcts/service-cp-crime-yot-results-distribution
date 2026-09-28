package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterResult;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ResultLevel;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * The court-extract filter's decision table, driven directly.
 *
 * <p>The legacy Jest suite has one substantive case for this filter and three that assert an export
 * is an instance of {@code Function}. The one case is twinned below against its own fixture, copied
 * byte-identical; the three are not twinnable — a Java twin of "this is a function" is
 * tautologically true, which the constitution's TDD principle rejects on sight — and the two
 * behaviours they nominally cover, {@code getLatestOrderedDate} and {@code getHearingDate}, belong
 * to the register build and are pinned there.
 *
 * <p>The nests above the twin exist because that one fixture does not reach the whole table. It has
 * no prompt carrying {@code isAvailableForCourtExtract} as an explicit JSON {@code null}, no prompt
 * carrying neither field, and no empty {@code courtExtract} — so the golden comparison would pass
 * whether or not those branches are right. The expected behaviour for each is read from the legacy
 * source rather than invented:
 * {@code prompt.isAvailableForCourtExtract === undefined ? (prompt.courtExtract ?
 * prompt.courtExtract.toUpperCase() === 'Y' : false) : prompt.isAvailableForCourtExtract}.
 */
@DisplayName("CourtExtractFilter")
class CourtExtractFilterTest {

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("result level")
    class ResultLevelFilter {

        @Test
        @DisplayName("keeps a result that is available for court extract and unpublished")
        void keeps_an_available_unpublished_result() {
            assertThat(filterResults(
                    "{\"isAvailableForCourtExtract\":true,\"publishedForNows\":false}"))
                    .hasSize(1);
        }

        @Test
        @DisplayName("drops a result that is not available for court extract")
        void drops_a_result_that_is_not_available() {
            assertThat(filterResults(
                    "{\"isAvailableForCourtExtract\":false,\"publishedForNows\":false}"))
                    .isEmpty();
        }

        @Test
        @DisplayName("drops a result already published through the NOWs route")
        void drops_a_result_already_published_for_nows() {
            assertThat(filterResults(
                    "{\"isAvailableForCourtExtract\":true,\"publishedForNows\":true}"))
                    .isEmpty();
        }

        @Test
        @DisplayName("drops a result that does not mention court extract at all")
        void drops_a_result_with_no_court_extract_flag() {
            assertThat(filterResults("{\"publishedForNows\":false}")).isEmpty();
        }

        @Test
        @DisplayName("drops a result whose availability flag is an explicit null")
        void drops_a_result_whose_flag_is_an_explicit_null() {
            assertThat(filterResults(
                    "{\"isAvailableForCourtExtract\":null,\"publishedForNows\":false}"))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("prompt level")
    class PromptLevelFilter {

        @Test
        @DisplayName("keeps a prompt flagged available")
        void keeps_a_prompt_flagged_available() {
            assertThat(promptsAfterFiltering("{\"isAvailableForCourtExtract\":true}")).hasSize(1);
        }

        @Test
        @DisplayName("drops a prompt flagged unavailable")
        void drops_a_prompt_flagged_unavailable() {
            assertThat(promptsAfterFiltering("{\"isAvailableForCourtExtract\":false}")).isEmpty();
        }

        @Test
        @DisplayName("drops a prompt whose flag is null rather than falling back to courtExtract")
        void drops_a_prompt_whose_flag_is_null_without_falling_back() {
            // The legacy test is `=== undefined`, which a JSON null does not satisfy: the null is
            // returned as the filter's answer and is falsy. A prompt the fallback would have kept is
            // therefore dropped, and treating null as absent would wrongly keep it — a prompt on the
            // printed register that the legacy leaves off.
            assertThat(promptsAfterFiltering(
                    "{\"isAvailableForCourtExtract\":null,\"courtExtract\":\"Y\"}"))
                    .isEmpty();
        }

        @Test
        @DisplayName("falls back to courtExtract only when the flag is absent")
        void falls_back_to_court_extract_when_the_flag_is_absent() {
            assertThat(promptsAfterFiltering("{\"courtExtract\":\"Y\"}")).hasSize(1);
        }

        @Test
        @DisplayName("accepts a lower-case courtExtract, which the legacy upper-cases")
        void accepts_a_lower_case_court_extract() {
            assertThat(promptsAfterFiltering("{\"courtExtract\":\"y\"}")).hasSize(1);
        }

        @Test
        @DisplayName("drops a prompt whose courtExtract is not Y")
        void drops_a_prompt_whose_court_extract_is_not_yes() {
            assertThat(promptsAfterFiltering("{\"courtExtract\":\"N\"}")).isEmpty();
        }

        @Test
        @DisplayName("drops a prompt whose courtExtract is empty, which is falsy")
        void drops_a_prompt_whose_court_extract_is_empty() {
            // `prompt.courtExtract ? … : false` — the empty string never reaches toUpperCase.
            assertThat(promptsAfterFiltering("{\"courtExtract\":\"\"}")).isEmpty();
        }

        @Test
        @DisplayName("drops a prompt with neither flag nor courtExtract")
        void drops_a_prompt_with_neither_flag() {
            assertThat(promptsAfterFiltering("{\"promptReference\":\"anything\"}")).isEmpty();
        }

        @Test
        @DisplayName("leaves a result with no prompts at all alone")
        void leaves_a_result_with_no_prompts_alone() {
            // `if (r.judicialResult.judicialResultPrompts)` — a result carrying no prompts is not
            // given an empty array on the way past.
            final List<RegisterResult> kept = filterResults(
                    "{\"isAvailableForCourtExtract\":true,\"publishedForNows\":false}");

            assertThat(kept).hasSize(1);
            assertThat(kept.get(0).judicialResult().get("judicialResultPrompts")).isNull();
        }
    }

    @Nested
    @DisplayName("what the filter does to the payload it was given")
    class Immutability {

        @Test
        @DisplayName("leaves the hearing payload's own prompts where they were")
        void leaves_the_payload_prompts_where_they_were() {
            // The legacy assigns the filtered array back onto the judicial result it was handed,
            // editing the hearing payload; only the Durable Functions serialisation boundary between
            // activities keeps that from being seen by the next stage. A Java pipeline passes
            // references, and the tree belongs to the producer, so the filter must derive rather
            // than edit — otherwise the outbound mappers see a payload with prompts already removed.
            final JsonNode judicialResult = mapper.readTree(
                    "{\"isAvailableForCourtExtract\":true,\"publishedForNows\":false,"
                            + "\"judicialResultPrompts\":[{\"courtExtract\":\"Y\"},"
                            + "{\"courtExtract\":\"N\"}]}");

            final DefendantContext defendant = new DefendantContext();
            defendant.addResults(List.of(result(judicialResult)));
            CourtExtractFilter.apply(List.of(defendant));

            assertThat(defendant.results().get(0).judicialResult().get("judicialResultPrompts"))
                    .hasSize(1);
            assertThat(judicialResult.get("judicialResultPrompts"))
                    .as("the tree the filter was handed is the producer's, not this service's")
                    .hasSize(2);
        }

        @Test
        @DisplayName("filters every gathered defendant, not only the first")
        void filters_every_gathered_defendant() {
            final DefendantContext kept = new DefendantContext();
            kept.addResults(List.of(result(
                    "{\"isAvailableForCourtExtract\":true,\"publishedForNows\":false}")));
            final DefendantContext dropped = new DefendantContext();
            dropped.addResults(List.of(result(
                    "{\"isAvailableForCourtExtract\":false,\"publishedForNows\":false}")));

            CourtExtractFilter.apply(List.of(kept, dropped));

            assertThat(kept.results()).hasSize(1);
            assertThat(dropped.results()).isEmpty();
        }
    }

    /**
     * The JUnit twin of the legacy {@code RegisterFragmentService} Jest suite.
     *
     * <p>That suite declares four cases; this is the one that asserts a behaviour. The fixture is
     * copied byte-identical from {@code NowsHelper/service/test/}, and every value asserted here is
     * the Jest case's own.
     */
    @Nested
    @DisplayName("RegisterFragmentService — legacy Jest twins")
    class LegacyJestTwins {

        @Test
        @DisplayName("should filter results and prompts for court extract based on "
                + "isAvailableForCourtExtract, publishedForNows and courtExtract flags")
        void should_filter_results_and_prompts_for_court_extract() {
            final DefendantContext defendant = new DefendantContext();
            for (final JsonNode judicialResult : LegacyFixtures.read(
                    "judicial-results-for-court-extract.json")) {
                defendant.addResults(List.of(result(judicialResult)));
            }

            CourtExtractFilter.apply(List.of(defendant));

            final List<RegisterResult> kept = defendant.results();
            assertThat(kept).hasSize(3);
            assertThat(kept).allSatisfy(result -> {
                assertThat(truthy(result.judicialResult(), "isAvailableForCourtExtract")).isTrue();
                assertThat(truthy(result.judicialResult(), "publishedForNows")).isFalse();
            });

            assertThat(prompts(kept.get(0))).hasSize(1);
            assertThat(courtExtract(kept.get(0), 0)).isEqualTo("Y");

            assertThat(prompts(kept.get(1))).hasSize(1);
            assertThat(courtExtract(kept.get(1), 0)).isEqualTo("y");

            assertThat(prompts(kept.get(2))).hasSize(3);
            assertThat(courtExtract(kept.get(2), 0)).isNull();
            assertThat(truthy(prompts(kept.get(2)).get(0), "isAvailableForCourtExtract")).isTrue();
            assertThat(courtExtract(kept.get(2), 1)).isEqualTo("Y");
            assertThat(courtExtract(kept.get(2), 2)).isEqualTo("Y");
        }

        /**
         * The prompts a surviving result still carries.
         *
         * @param result the surviving result
         * @return its prompts
         */
        private JsonNode prompts(final RegisterResult result) {
            return result.judicialResult().get("judicialResultPrompts");
        }

        /**
         * The {@code courtExtract} value of one surviving prompt.
         *
         * @param result the surviving result
         * @param index  the prompt's position
         * @return the value, or {@code null} where the prompt carries none
         */
        private String courtExtract(final RegisterResult result, final int index) {
            final JsonNode value = prompts(result).get(index).get("courtExtract");
            return value == null || value.isNull() ? null : value.stringValue();
        }
    }

    /**
     * Whether a field would satisfy {@code if (parent.field)} in JavaScript.
     *
     * @param node  the object to read
     * @param field the field name
     * @return whether the field is truthy
     */
    private static boolean truthy(final JsonNode node, final String field) {
        final JsonNode value = node.get(field);
        final boolean truthy;
        if (value == null || value.isNull() || value.isMissingNode()) {
            truthy = false;
        } else if (value.isBoolean()) {
            truthy = value.booleanValue();
        } else if (value.isString()) {
            truthy = !value.stringValue().isEmpty();
        } else {
            truthy = true;
        }
        return truthy;
    }

    /**
     * Runs the filter over a single result and returns what survived.
     *
     * @param judicialResult the judicial result as JSON text
     * @return the surviving results
     */
    private List<RegisterResult> filterResults(final String judicialResult) {
        final DefendantContext defendant = new DefendantContext();
        defendant.addResults(List.of(result(judicialResult)));
        CourtExtractFilter.apply(List.of(defendant));
        return defendant.results();
    }

    /**
     * Runs the filter over a result carrying one prompt, and returns the prompts that survived.
     *
     * @param prompt the prompt as JSON text
     * @return the surviving prompts
     */
    private JsonNode promptsAfterFiltering(final String prompt) {
        final List<RegisterResult> kept = filterResults(
                "{\"isAvailableForCourtExtract\":true,\"publishedForNows\":false,"
                        + "\"judicialResultPrompts\":[" + prompt + "]}");
        assertThat(kept).as("the surrounding result must survive for the prompts to be observable")
                .hasSize(1);
        return kept.get(0).judicialResult().get("judicialResultPrompts");
    }

    /**
     * Wraps a judicial result as the kind of gathered result the filter operates on.
     *
     * @param judicialResult the judicial result as JSON text
     * @return the gathered result
     */
    private RegisterResult result(final String judicialResult) {
        return result(mapper.readTree(judicialResult));
    }

    /**
     * Wraps an already-parsed judicial result as the kind of gathered result the filter operates on.
     *
     * @param judicialResult the judicial result
     * @return the gathered result
     */
    private RegisterResult result(final JsonNode judicialResult) {
        return new RegisterResult(null, null, null, null, ResultLevel.OFFENCE, null,
                judicialResult, null, null);
    }
}
