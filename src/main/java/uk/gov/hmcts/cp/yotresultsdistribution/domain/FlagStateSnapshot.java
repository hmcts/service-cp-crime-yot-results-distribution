package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The last thing the intake side learned about the one lever, and when it learned it.
 *
 * <p>What a recording stamps {@code processed_output.recorded_flag_state} from (research §12). The
 * listener reads the flag with the same reader the nightly job uses when a command arrives and the
 * last read has aged past its window, and hands the answer down with the command; the recording
 * itself never waits on a read, because a register that has been built is worth more than the flag
 * state it is labelled with, and a read nobody made is exactly what {@link RecordedFlagState#UNKNOWN}
 * says.
 *
 * <p>A snapshot is a reading with an age, which is why the instant is carried beside the decision
 * rather than the state being computed when the read happened: a reading taken during a command
 * that is still being processed may have gone stale before the row is written, and the row must say
 * what was true when it was written.
 *
 * @param decision what the flag said when it was last read
 * @param readAt   when that read happened, against which the reading's age is measured
 */
public record FlagStateSnapshot(FlagDecision decision, Instant readAt) {

    /**
     * How long a reading is allowed to speak for a command that arrives after it (research §12).
     *
     * <p>A minute is short enough that a cutover rolled back during it mislabels at most a minute of
     * arrivals, and long enough that a stack taking a command every few seconds asks App
     * Configuration once a minute rather than once a command. The window is inclusive: a reading
     * exactly a minute old is a reading of the last minute.
     */
    public static final Duration WINDOW = Duration.ofSeconds(60);

    /**
     * What a row recorded now says about the flag.
     *
     * <p>Two of the three answers are {@code UNKNOWN}, and deliberately so. A read that produced no
     * answer says nothing about the lever, and neither does one taken before the window: both are
     * kept out of automatic batching, and neither is {@code OFF}, which is the positive statement
     * that the flag was read and the legacy is generating.
     *
     * @param now the instant the recording is happening at, which is what the reading's age is
     *            measured against
     * @return {@code ON} or {@code OFF} where the reading is fresh enough to stand for it, and
     *         {@code UNKNOWN} where it is not or where the flag could not be read at all
     */
    public RecordedFlagState stateFor(final Instant now) {
        final RecordedFlagState state;
        if (agedOutAt(now) || decision instanceof FlagDecision.Unreadable) {
            state = RecordedFlagState.UNKNOWN;
        } else if (decision.generates()) {
            state = RecordedFlagState.ON;
        } else {
            state = RecordedFlagState.OFF;
        }
        return state;
    }

    /**
     * Whether this reading is too old to speak for a row written at the given instant.
     *
     * <p>The comparison is against the end of the window rather than against an elapsed duration, so
     * a reading taken exactly {@link #WINDOW} ago is inside it: an inclusive window and an exclusive
     * one differ by a millisecond, and the boundary is stated once, here.
     */
    private boolean agedOutAt(final Instant now) {
        return readAt.plus(WINDOW).isBefore(now);
    }
}
