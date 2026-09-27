package uk.gov.hmcts.cp.yotresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The account the pass reads, and the two things it may never be handed.
 *
 * <p>The pass's first act is to walk both of these lists, and a run's first act is the pass. A port
 * implementation that answered with a missing list - a stub, a test double, an adapter written
 * later - would therefore end the night inside the pass, reported as an unexpected failure rather
 * than as the store's own signal, and a night that failed there is a night no court centre got a
 * document from. The refusal belongs where the record is built, which is the one place that knows
 * it is wrong.
 *
 * <p>Both lists are copied for the same reason they are refused: this record is read after the
 * statement that produced it has moved on, and a caller holding the adapter's own list could see it
 * change underneath the line it is writing about it.
 */
@DisplayName("StaleReleaseOutcome")
class StaleReleaseOutcomeTest {

    private static final ReleasedBatch RELEASED = new ReleasedBatch(UUID.randomUUID(),
            UUID.randomUUID(), LocalDate.of(2026, 9, 21), 2);

    @Test
    @DisplayName("an account with a missing list is refused where it is built")
    void a_missing_list_is_refused() {
        assertThatThrownBy(() -> new StaleReleaseOutcome(null, List.of()))
                .as("the pass walks the released list first, so a null here is the run's own "
                        + "ending rather than one batch's")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StaleReleaseOutcome(List.of(), null))
                .as("and the contended list is walked immediately after it")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the lists it answers with are its own")
    void the_lists_are_copied() {
        final List<ReleasedBatch> released = new ArrayList<>(List.of(RELEASED));
        final List<UUID> contended = new ArrayList<>(List.of(UUID.randomUUID()));

        final StaleReleaseOutcome outcome = new StaleReleaseOutcome(released, contended);
        released.clear();
        contended.clear();

        assertThat(outcome.released())
                .as("the account is read after the statements that produced it have moved on, so "
                        + "it holds what it was given rather than a view of somebody's working list")
                .containsExactly(RELEASED);
        assertThat(outcome.contended()).hasSize(1);
    }
}
