package uk.gov.hmcts.cp.yotresultsdistribution.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.RegisterGenerationJob;

/**
 * What makes a schedule happen at all on this JVM, and the lock the locked ones take.
 *
 * <p>Three declarations, and Spring permits exactly one of each per context:
 * {@code @EnableScheduling}, without which nothing reads {@code @Scheduled} off a bean;
 * {@code @EnableSchedulerLock}, without which {@code @SchedulerLock} is an annotation nothing
 * intercepts; and the one {@link LockProvider} both of those rest on. They lived in
 * {@link SchedulingConfig} until this increment, which meant they lived behind
 * {@code yotresultsdistribution.generation.enabled} - so a pod deployed without the downstream half
 * processed no schedule of any kind.
 *
 * <p><strong>Conditional on nothing but the profile.</strong> No enabled-flag condition at all,
 * and that is the decision rather than an omission. {@code IntakeAgeSweep} must
 * refresh the two intake gauges wherever the intake half runs, which includes a pod with the
 * report and the generation half both switched off - and that pod is exactly the one whose stuck
 * requests nothing else would report. A condition of generation-or-report was considered and
 * rejected for that reason: it would leave the deployment the gauges were added for without
 * {@code @Scheduled} processing, which is the failure wearing the instrument that was supposed to
 * reveal it. The cost of being unconditional is an idle scheduler on a pod that schedules nothing.
 *
 * <p>There used to be a second condition here, and it is gone with what it was about. Until
 * increment 005 a JVM could be started to run one operations command and exit, and it was kept off
 * every schedule - a process that held one would have been a second replica of the 18:00 run, the
 * 07:00 report and the two sweeps, and one that ran long enough to reach any of their hours would
 * have fired it. No such JVM exists now: an operations call is served by a pod that is already
 * running, and the 18:00 lock is what a regeneration contends for.
 *
 * <p>{@code @Profile("!test")} because the provider needs a {@code DataSource} and that profile
 * deliberately has none: the plain context-load tests must keep running with no broker, no database
 * and therefore without Docker.
 *
 * <p><strong>The lock default moves verbatim.</strong> {@code defaultLockAtMostFor} is the fallback
 * for a {@code @SchedulerLock} that states no duration of its own, and both locked methods on this
 * context state theirs - the nightly run and the morning report - so the value it carries is
 * inert. That is precisely why it is left alone rather than tidied to the report's
 * budget on the way past: changing it here would be a change to generation's lock semantics made in
 * a commit about the report's wiring.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = RegisterGenerationJob.LOCK_AT_MOST_FOR)
public class SchedulingInfrastructureConfig {

    /** The table V2 creates for the lock, named here because the provider will not guess it. */
    private static final String SHEDLOCK_TABLE = "shedlock";

    /**
     * The lock every locked schedule in this service takes, over the processed log's own store.
     *
     * <p>One provider, because two over one {@code shedlock} table is a race dressed as
     * configuration. The lock is taken on the database's clock rather than on the pods', because
     * two JVMs a few seconds apart is exactly the skew a lock is supposed to survive.
     *
     * @param dataSource the processed log's own datasource, which is where {@code shedlock} is
     * @return the JDBC lock provider, taking the lock on the database's clock
     */
    @Bean
    public LockProvider lockProvider(final DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(SHEDLOCK_TABLE)
                .usingDbTime()
                .build());
    }
}
