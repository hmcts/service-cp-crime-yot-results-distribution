package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which of the four states a run stops in, asked of the enum rather than of each caller.
 *
 * <p>Review gate 3's finding: the request-duration timer took any {@link RequestStatus} it was
 * handed, so a caller that handed it {@code RETRYING} would publish a sample under
 * {@code outcome=retrying} and turn the timer into a histogram of attempts. The refusal that stops
 * that has to ask something what terminal means, and the two places in this service that already
 * knew - the state machine's prose and {@code ProcessingStateService}'s branching - are not
 * askable. So the enum answers, and this is where the answer is pinned: a fifth constant added
 * without a decision about which side of the line it falls on fails here rather than silently at
 * the instrument.
 */
@DisplayName("RequestStatus")
class RequestStatusTest {

    @Test
    void completed_and_failed_are_terminal() {
        assertThat(RequestStatus.COMPLETED.isTerminal())
                .as("a run that succeeded has finished, and is never run again")
                .isTrue();
        assertThat(RequestStatus.FAILED.isTerminal())
                .as("and a parked one has finished too - terminal is not the same as final, "
                        + "because FAILED is replayable under a fresh message identity")
                .isTrue();
    }

    @Test
    void received_and_retrying_are_not_terminal() {
        assertThat(RequestStatus.RECEIVED.isTerminal())
                .as("a run in progress has not finished")
                .isFalse();
        assertThat(RequestStatus.RETRYING.isTerminal())
                .as("and a transient failure short of the delivery budget is an attempt the "
                        + "broker is going to make again, not an outcome")
                .isFalse();
    }
}
