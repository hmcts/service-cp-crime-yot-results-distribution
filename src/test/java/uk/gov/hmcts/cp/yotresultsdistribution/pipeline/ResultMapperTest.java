package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterResult;

/**
 * What the court decided, in the two fields the register prints.
 *
 * <p>Twins the one case of {@code $DF/…/Mappers/Result/test/ResultMapper.test.js}: a judicial
 * result's {@code resultText} carried across unchanged, and its {@code cjsCode} carried across under
 * the name {@code cjsResultCode}. Nothing else on a judicial result reaches the register through
 * here, which is why the mapper is three lines and the interesting question is the one the legacy
 * case does not ask.
 *
 * <p><strong>Both guards answer with nothing.</strong> {@code ResultMapper.js:9} tests
 * {@code judicialResults && judicialResults.length}, so an absent list and an <em>empty</em> one
 * both fall off the end of the function and answer {@code undefined} — never {@code []}. That is not
 * tidiness: every result list on the outbound contract carries {@code minItems: 1}, so an empty
 * array is a document progression refuses, and this mapper is called at three scopes (defendant,
 * case or application, offence) where an empty filtered list is the normal case rather than the
 * exception. The legacy suite exercises neither guard.
 *
 * <p>The filtering is the caller's throughout — defendant-level results on the defendant,
 * case- and application-level on the case or application, offence-level on the offence they were
 * ordered against. This maps whatever it is handed, in the order it is handed it.
 */
@DisplayName("ResultMapper")
class ResultMapperTest {

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    @Nested
    @DisplayName("Result Mapper > Should return correct values — RS1")
    class LegacyTwin {

        @Test
        @DisplayName("the result's text is carried")
        void the_results_text_is_carried() {
            assertThat(legacyResults()).hasSize(1);
            assertThat(legacyResults().get(0).resultText()).isEqualTo("Pay by date");
        }

        @Test
        @DisplayName("the CJS code is carried under the name the register gives it")
        void the_cjs_code_is_carried_under_its_register_name() {
            // `cjsCode` on the way in, `cjsResultCode` on the way out.
            assertThat(legacyResults().get(0).cjsResultCode()).isEqualTo("Fine");
        }
    }

    @Nested
    @DisplayName("what else a judicial result carries")
    class EverythingElse {

        @Test
        @DisplayName("the label sitting beside the result text is not the result text")
        void the_label_is_not_the_result_text() {
            // A judicial result carries a `label` and a `resultText`, and they differ: one names the
            // result type, the other is what the court ordered. Only the second is printed.
            final List<CourtRegisterResult> results = map("""
                    {"label": "Collection order",
                     "resultText": "collection order made",
                     "cjsCode": "cjsCode - D level"}""");

            assertThat(results.get(0).resultText()).isEqualTo("collection order made");
        }

        @Test
        @DisplayName("a result with no CJS code carries none")
        void a_result_with_no_cjs_code_carries_none() {
            assertThat(map("{\"resultText\": \"Adjourned\"}").get(0).cjsResultCode()).isNull();
        }

        @Test
        @DisplayName("a result with no text carries none")
        void a_result_with_no_text_carries_none() {
            assertThat(map("{\"cjsCode\": \"Fine\"}").get(0).resultText()).isNull();
        }

        @Test
        @DisplayName("a result carrying neither is still a result")
        void a_result_carrying_neither_is_still_a_result() {
            // The mapper does not test the fields it copies, so an unpopulated judicial result
            // becomes an entry rather than disappearing. Absent fields, present entry.
            assertThat(map("{\"judicialResultId\": \"b3303496-c090-4365-adc6-b2e6fe7a40fe\"}"))
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.resultText()).isNull();
                        assertThat(result.cjsResultCode()).isNull();
                    });
        }

        @Test
        @DisplayName("an empty string is carried as an empty string, not as nothing")
        void an_empty_string_is_carried_as_one() {
            assertThat(map("{\"resultText\": \"\", \"cjsCode\": \"\"}").get(0).resultText())
                    .isEmpty();
        }

        @Test
        @DisplayName("results come out in the order they went in")
        void results_keep_their_order() {
            assertThat(map(
                    "{\"cjsCode\": \"first\"}",
                    "{\"cjsCode\": \"second\"}",
                    "{\"cjsCode\": \"third\"}"))
                    .extracting(CourtRegisterResult::cjsResultCode)
                    .containsExactly("first", "second", "third");
        }
    }

    @Nested
    @DisplayName("nothing to map")
    class NothingToMap {

        @Test
        @DisplayName("an unanswered list of results answers nothing")
        void an_unanswered_list_answers_nothing() {
            assertThat(ResultMapper.map(null)).isNull();
        }

        @Test
        @DisplayName("an empty list of results answers nothing, not an empty list")
        void an_empty_list_answers_nothing() {
            // The guard the legacy suite never runs, and the one that keeps `results: []` — a
            // document the contract refuses, because every result list carries `minItems: 1` — off
            // the wire. It is also the normal case at all three scopes this mapper is called at.
            assertThat(ResultMapper.map(List.of())).isNull();
        }
    }

    /**
     * RS1's inline judicial result, mapped as the Jest case maps it.
     *
     * @return the mapped results
     */
    private List<CourtRegisterResult> legacyResults() {
        return map("{\"cjsCode\": \"Fine\", \"resultText\": \"Pay by date\"}");
    }

    /**
     * Maps judicial results written inline.
     *
     * @param judicialResults the results as JSON text, in order
     * @return the mapped results
     */
    private List<CourtRegisterResult> map(final String... judicialResults) {
        final List<JsonNode> nodes =
                Stream.of(judicialResults).map(mapper::readTree).toList();
        return ResultMapper.map(nodes);
    }
}
