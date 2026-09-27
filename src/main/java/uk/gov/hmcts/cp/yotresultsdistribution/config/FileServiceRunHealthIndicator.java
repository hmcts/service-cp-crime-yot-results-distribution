package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * Whether the file service is reachable, which matters only while a run is in progress.
 *
 * <p>The second datasource is used for a few seconds a night and not at all for the rest of it, so a
 * file service that is unreachable at 09:00 is not a pod that should roll: the intake half is still
 * recording registers, which is the half that must not stop. It gates readiness only while a
 * generation run is actually in progress, when an unreachable payload store means the run about to
 * fail every batch PAYLOAD_STORE_UNAVAILABLE.
 *
 * <p>That is a narrower rule than "in the readiness group" and a wider one than "never", and it is
 * stated here rather than by the group's membership because the condition is a fact about the run
 * rather than about the configuration. The component <em>is</em> in the readiness group - it has to
 * be, or it could gate nothing - and it is this class that keeps the group quiet for the twenty-three
 * hours a day the datasource is not in use.
 *
 * <p><strong>Nothing is asked between runs.</strong> The probe is a query on a pool this service
 * opens for a few seconds a night, and a health poll every few seconds would keep a connection to
 * somebody else's database alive all day to answer a question whose answer cannot matter until
 * 18:00. Idle, the component answers UP and says it did not ask.
 *
 * <p>The details are two, and both are bounded words:
 *
 * <ul>
 *   <li>{@code run} - {@code in-progress} or {@code idle};</li>
 *   <li>{@code fileservice} - the probe's status while a run is in progress, {@code not-probed}
 *       otherwise.</li>
 * </ul>
 *
 * <p>It is annotated with nothing and is registered by {@link GenerationHealth}, which contributes it
 * whether or not generation is enabled: Spring validates health-group membership at startup, so a
 * readiness group naming a contributor a property had removed would fail the context with a message
 * about health groups rather than about the setting somebody changed. On a pod that starts no
 * generation run it answers idle for ever - asking nothing of a datasource that does not exist on an
 * intake-only pod, and nothing of the one a report pod's e-mail output brought with it either.
 */
public class FileServiceRunHealthIndicator implements HealthIndicator, RunProgress {

    private static final Logger LOG = LoggerFactory.getLogger(FileServiceRunHealthIndicator.class);

    /** What the details call the run while one is under way. */
    private static final String IN_PROGRESS = "in-progress";

    /** What the details call the run for the twenty-three hours a day there is not one. */
    private static final String IDLE = "idle";

    /** What the details say about a file service nothing has been asked of. */
    private static final String NOT_PROBED = "not-probed";

    /**
     * The file-service datasource's own contributor, asked only while a run is in progress.
     *
     * <p>A {@link HealthIndicator} rather than the datasource, because "can this pool answer a
     * query" is a question Spring Boot's own datasource contributor already answers, and this class
     * has nothing to add to it beyond when it may be asked.
     */
    private final HealthIndicator fileServiceProbe;

    /** Whether a generation run is in progress; the whole of when the probe may be asked. */
    private final AtomicBoolean runInProgress = new AtomicBoolean();

    /**
     * Creates the indicator.
     *
     * @param fileServiceProbe the file-service datasource's own contributor
     */
    public FileServiceRunHealthIndicator(final HealthIndicator fileServiceProbe) {
        this.fileServiceProbe = fileServiceProbe;
    }

    /**
     * Records that a generation run has started, from which point the file service gates readiness.
     */
    @Override
    public void recordRunStarted() {
        runInProgress.set(true);
    }

    /**
     * Records that a generation run has ended, however it ended.
     *
     * <p>However it ended is the point: a run that threw leaves the file service exactly as
     * uninteresting to readiness as one that completed, and a flag left set by a failure would gate
     * readiness on a database nothing is using until the pod restarts.
     */
    @Override
    public void recordRunEnded() {
        runInProgress.set(false);
    }

    /**
     * Idle, or whatever the file service answered while this run is going on.
     *
     * @return the file service's bearing on readiness, which is none unless a run is in progress
     */
    @Override
    public Health health() {
        final Health answer;
        if (runInProgress.get()) {
            final Status status = probed();
            answer = (Status.UP.equals(status) ? Health.up() : Health.down())
                    .withDetail("run", IN_PROGRESS)
                    .withDetail("fileservice", status.getCode())
                    .build();
        } else {
            answer = Health.up()
                    .withDetail("run", IDLE)
                    .withDetail("fileservice", NOT_PROBED)
                    .build();
        }
        return answer;
    }

    /**
     * What the datasource's contributor says, with a throw read as DOWN.
     *
     * <p>A contributor is entitled to throw at a pool that cannot hand out a connection; a health
     * endpoint is not entitled to pass that on, because a component that throws takes down the very
     * endpoint that would have reported the problem. The refusal is reported by what it is, never by
     * what the driver said: a connection failure's message carries hosts, database names and
     * sometimes credentials, so the type is logged and the message is not.
     *
     * @return UP, or DOWN however the probe failed to say so
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    // Deliberately total. Whatever a pool with no connection to give raises - a driver's own
    // unchecked exception, a Hikari timeout, an unwrapped SQL failure - the answer is DOWN and the
    // health endpoint has to survive it, because a component that throws takes with it the endpoint
    // that would have reported the problem.
    private Status probed() {
        Status status;
        try {
            status = fileServiceProbe.health().getStatus();
        } catch (RuntimeException refusal) {
            LOG.warn("File-service health probe refused during a run. type={}",
                    refusal.getClass().getName());
            status = Status.DOWN;
        }
        return status;
    }
}
