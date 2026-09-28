package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The attribution mapping, pinned constant by constant.
 *
 * <p><strong>[A]</strong>, and it was two mappings until the run report stopped needing the second.
 * {@code wasRenderRequested()} classified every reason by whether the render had been asked for,
 * and it could not: RENDER_REQUEST_FAILED is what a request that was made and answered nothing ends
 * under and what a batch no attempt could be started for ends under, so the run report counted a
 * request this service never made. That fact is carried on {@code BatchOutcome} now, set where the
 * call is made, and the predicate and the three cases that classified it are gone rather than left
 * here as a second answer to a question one place owns.
 *
 * <p>{@link BatchFailureReason#isGeneratorAttributed()} is the one place that says which endings
 * were reported by a mechanism outside this service, and two enforcements read it:
 * {@code JdbcRegisterStore.markFailed} refuses a mark that disagrees with it, and
 * {@code register_batch_completed_by_shape_chk} names the same reason for the writers that do not
 * go through the store. A constant classified the wrong way is therefore not one wrong answer but
 * a batch that cannot be written at all, or one written crediting a decision nobody made.
 *
 * <p><strong>The tables below are the specification and the enumeration is checked against
 * them</strong>, rather than the methods being read twice. Both are keyed by the constant's
 * <em>name</em>, which is what reaches {@code register_batch.failure_reason}, a metric label and the
 * run report - and which lets a reason be specified here before it exists, so that the red run of
 * the task that adds it is a failing assertion about the vocabulary rather than a compile error.
 * Every constant is offered by {@link EnumSource}, so a reason added to the enumeration and not
 * classified in both tables fails this suite - which is the only moment at which somebody is still
 * deciding what it means. Left to the implementation alone a new constant would simply answer false
 * to the one question that has a method, and nobody would have answered the other at all.
 */
@DisplayName("batch failure reason")
class BatchFailureReasonTest {

    /** The stale-batch pass's ending, named as a string because that is what the column holds. */
    private static final String STALE_RELEASE = "NOT_COMPLETED_BY_NEXT_RUN";

    /**
     * Which endings somebody outside this service reported, written out one by one.
     *
     * <p>GENERATION_FAILED is the only one left, and it is systemdocgenerator's own verdict about
     * the render: it arrived because the renderer said so, so the row names the mechanism that
     * carried it. The other five are this service's own verdict about a render it could not ask
     * for, could not hear about, or stopped waiting for - the payload was never stored, the request
     * was never delivered, it was refused, the batch could not be assembled at all, or the next run
     * began and the answer still had not come - and nobody outside this service was ever in a
     * position to answer.
     *
     * <p>There were two until the grace-period reconciler went. GENERATION_TIMED_OUT was its
     * verdict about the renderer's silence, and it is a verdict nothing takes any more: a batch
     * nothing was learned about is now failed NOT_COMPLETED_BY_NEXT_RUN by the next run, which is
     * this service deciding for itself and therefore attributed to nobody.
     */
    private static final Map<String, Boolean> ATTRIBUTION = Map.of(
            "PAYLOAD_STORE_UNAVAILABLE", false,
            "RENDER_REQUEST_FAILED", false,
            "RENDER_REQUEST_REJECTED", false,
            "GENERATION_FAILED", true,
            "ASSEMBLY_FAILED", false,
            STALE_RELEASE, false);

    /**
     * Which endings give their registers back to the next run, written out one by one.
     *
     * <p>data-model.md's "releases rows" column. A batch that ends under one of these leaves its
     * registers unbatched and RECORDED, so the next run re-assembles them; a batch that ends under
     * one of the others leaves them where they are, because a document either exists or was refused
     * for a reason re-rendering would meet again. The distinction is the difference between a
     * register that is late and a register that is lost, which is the whole subject of this
     * increment.
     *
     * <p>{@code JdbcRegisterStore.RELEASING_REASONS} is the one place that acts on it, and the store
     * is held to this table where the release can be observed - {@code RegisterStoreIT}, over a real
     * batch and its rows. Recorded here because this is where a new constant is classified, and a
     * constant nobody classified would join the four that keep their rows by saying nothing.
     */
    private static final Map<String, Boolean> RELEASES_ROWS = Map.of(
            "PAYLOAD_STORE_UNAVAILABLE", true,
            "RENDER_REQUEST_FAILED", false,
            "RENDER_REQUEST_REJECTED", false,
            "GENERATION_FAILED", false,
            "ASSEMBLY_FAILED", true,
            STALE_RELEASE, true);

    /** The reason of that name, empty while the vocabulary does not have one. */
    private static Optional<BatchFailureReason> reasonNamed(final String name) {
        return Arrays.stream(BatchFailureReason.values())
                .filter(reason -> reason.name().equals(name))
                .findFirst();
    }

    @ParameterizedTest
    @EnumSource(BatchFailureReason.class)
    void every_reason_should_be_classified_the_way_the_attribution_table_says(
            final BatchFailureReason reason) {
        assertThat(ATTRIBUTION)
                .as("a reason nobody has classified is a reason the store and the check "
                        + "constraint would each answer for on their own")
                .containsKey(reason.name());
        assertThat(reason.isGeneratorAttributed())
                .as("%s: whether the ending was reported by a mechanism outside this service, "
                        + "which is what decides whether the row may name one", reason)
                .isEqualTo(ATTRIBUTION.get(reason.name()));
    }

    @Test
    void the_attribution_table_should_classify_every_reason_and_no_others() {
        assertThat(ATTRIBUTION.keySet())
                .as("the other direction: a reason removed from the enumeration leaves a "
                        + "classification here for an ending that can no longer happen")
                .containsExactlyInAnyOrderElementsOf(names());
    }

    @Test
    void the_release_table_should_classify_every_reason_and_no_others() {
        assertThat(RELEASES_ROWS.keySet())
                .as("both directions again, and for the costlier of the two columns: an ending "
                        + "nobody classified keeps its registers, and keeping them is how a "
                        + "register is lost rather than late")
                .containsExactlyInAnyOrderElementsOf(names());
    }

    /**
     * One ending, not two, and the narrowing is the point rather than a consequence.
     *
     * <p>The second was GENERATION_TIMED_OUT, the grace-period reconciler's verdict about the
     * renderer's silence. Nothing goes and asks any more, so nothing learns that verdict, and an
     * attributed reason nothing can produce is a row {@code register_batch_completed_by_shape_chk}
     * would still admit - a FAILED batch naming a mechanism that no longer exists.
     *
     * <p>Asked of {@link BatchFailureReason#isGeneratorAttributed()} over the whole enumeration
     * rather than of the table above, because the method is what the store and the constraint each
     * enforce and the table is only this suite's specification of it. Stated as "these and no
     * others" rather than as a probe of the one constant, since what makes the retirement complete
     * is that there is nobody else left answering true.
     */
    @Test
    void only_generation_failed_is_generator_attributed() {
        assertThat(Arrays.stream(BatchFailureReason.values())
                        .filter(BatchFailureReason::isGeneratorAttributed)
                        .map(Enum::name))
                .as("one of the six, and it is the renderer's own verdict; the other five are this "
                        + "service answering for itself")
                .containsExactly("GENERATION_FAILED");
        assertThat(ATTRIBUTION.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey))
                .as("and the specification above says the same, so neither can drift alone")
                .containsExactly("GENERATION_FAILED");
    }

    /**
     * An ending somebody outside this service reported is an ending about a render that happened,
     * so there is nothing to give back: the document exists, or the renderer said why it does not.
     * The releasing endings are this service's own, every one of them, and asserting the implication
     * rather than the two lists separately is what makes a future constant classified true in both
     * tables fail here instead of silently re-rendering a day somebody has already been sent.
     */
    @Test
    @DisplayName("no ending somebody else reported gives its registers back")
    void an_attributed_reason_should_never_release_its_rows() {
        for (final BatchFailureReason reason : BatchFailureReason.values()) {
            if (reason.isGeneratorAttributed()) {
                assertThat(RELEASES_ROWS.get(reason.name()))
                        .as("%s was reported about a render that happened", reason)
                        .isFalse();
            }
        }
    }

    /**
     * The bounded set itself, named rather than counted, because the names are the values of
     * {@code register_batch_failure_reason_chk} and a rename here without a migration is a batch
     * nobody can write. {@code BatchStateTest} holds the two to each other in both directions;
     * this case is the enumeration's own half of that statement.
     */
    @Test
    void the_six_reasons_are_the_bounded_set() {
        assertThat(names())
                .containsExactlyInAnyOrder(
                        "PAYLOAD_STORE_UNAVAILABLE",
                        "RENDER_REQUEST_FAILED",
                        "RENDER_REQUEST_REJECTED",
                        "GENERATION_FAILED",
                        "ASSEMBLY_FAILED",
                        STALE_RELEASE);
    }

    /**
     * And the one that left, named so that its return would be noticed.
     *
     * <p>The reason is the grace-period reconciler's, and the reconciler is gone: nothing asks
     * systemdocgenerator what became of a render, so nothing can conclude that it timed out. A
     * batch nothing is learned about is failed {@link BatchFailureReason#NOT_COMPLETED_BY_NEXT_RUN}
     * by the next scheduled run instead, which is a different claim made by a different mechanism -
     * this service stopped waiting, rather than somebody outside it having answered.
     *
     * <p>Asserted by name over {@code values()} rather than by referring to the constant, because a
     * case that referred to it could not compile once it had gone and would therefore be deleted
     * with its subject. This one outlives the deletion, which is the only way an absence is pinned.
     */
    @Test
    void generation_timed_out_is_no_longer_a_reason() {
        assertThat(reasonNamed("GENERATION_TIMED_OUT"))
                .as("the reconciler's verdict about the renderer's silence, and there is no "
                        + "reconciler to reach it")
                .isEmpty();
    }

    /**
     * The stale-batch pass is this service deciding for itself that it has waited long enough. No
     * event arrived, no query was made and no mechanism outside this service reported anything, so
     * the row names none - and {@code register_batch_completed_by_shape_chk} refuses one that does.
     */
    @Test
    void not_completed_by_next_run_is_not_generator_attributed() {
        assertThat(reasonNamed(STALE_RELEASE))
                .as("the reason the stale-batch pass writes")
                .get()
                .satisfies(reason -> assertThat(reason.isGeneratorAttributed())
                        .as("nobody outside this service was asked and nobody answered")
                        .isFalse());
    }

    /**
     * And the point of the ending: the registers go back. A batch released without its rows being
     * released is a court centre's day stamped to a terminal batch that no later run can see, which
     * is the lost register this increment exists to end.
     */
    @Test
    void not_completed_by_next_run_releases_its_rows() {
        assertThat(releasingReasons())
                .as("the endings that give their registers back for the next run to re-assemble, "
                        + "and the stale-batch pass's is the third of them")
                .containsExactlyInAnyOrder(
                        "PAYLOAD_STORE_UNAVAILABLE", "ASSEMBLY_FAILED", STALE_RELEASE);
    }

    /** The names of the reasons that exist and that the release table classifies as releasing. */
    private static List<String> releasingReasons() {
        return names().stream()
                .filter(name -> Boolean.TRUE.equals(RELEASES_ROWS.get(name)))
                .toList();
    }

    private static List<String> names() {
        return Arrays.stream(BatchFailureReason.values()).map(Enum::name).toList();
    }
}
