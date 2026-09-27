package uk.gov.hmcts.cp.yotresultsdistribution.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code db} component, over the register store's pool and nothing else.
 *
 * <p><strong>Built here rather than auto-configured, because the auto-configuration collects every
 * pool.</strong> {@code DataSourceHealthContributorAutoConfiguration} gathers the context's
 * {@link DataSource} beans and, where it finds more than one, contributes {@code db} as a composite
 * over all of them. On a generation-enabled pod the second pool is the platform file service's, so
 * that composite reported DOWN whenever the file service was unreachable - at any hour, over a
 * database nothing touches until 18:00 - and readiness rolled a pod whose intake half was recording
 * registers perfectly well. It also asked that pool for a connection on every health poll, holding
 * a connection to another team's database open all day.
 *
 * <p>{@code defaultCandidate = false} on the file-service pool does not prevent it: the resolver
 * the auto-configuration collects through filters on {@code autowire-candidate}, which is a
 * different flag, so the pool is invisible to injection by type and visible to that collection.
 * Setting {@code autowire-candidate = false} instead would hide it from the qualified injection
 * {@link GenerationHealth} uses to build the run probe, which is the one place that pool is meant
 * to be reachable from.
 *
 * <p>So the auto-configured contributor is switched off in {@code application.yaml} and this
 * indicator takes its name, its place in the readiness group and its meaning: {@code db} is the
 * store this service cannot work without. The file service's own availability is reported by
 * {@link FileServiceRunHealthIndicator}, which asks about it only while a run is on, and that
 * division is the whole of spec FR-011.
 *
 * <p>Contributed in every profile and unconditionally, unlike the rest of the health wiring, for a
 * reason worth stating: Spring validates health-group membership at startup, and the readiness
 * group names {@code db}, so a context that had no such component would fail on a message about
 * health groups rather than about whatever was missing. A {@code @ConditionalOnBean} would not do
 * it either - a condition in an ordinary configuration is evaluated before the auto-configuration
 * that defines the pool, so it answers "no pool" on a pod that has one. The pool is therefore
 * resolved when the component is asked rather than when it is built.
 */
@Configuration(proxyBeanMethods = false)
public class StoreHealth {

    /**
     * The register store's pool, asked whether it can still hand out a connection.
     *
     * <p>The bean is <em>named</em> {@code db}, which is what makes the component {@code db}: the
     * name is what the readiness group above, every runbook and every dashboard already say. The
     * method is not, because a two-letter method name is not a name a reader of this class can use.
     *
     * <p>No pool answers DOWN rather than UP, the same judgement {@link GenerationHealth} records
     * for the file service and for a stronger reason: this service cannot record what it has done
     * without the store, so a pod that has lost it must not be sent work. The provider is read on
     * every probe, so a pool built after this component was is still the pool it reports on.
     *
     * @param dataSource the primary pool, which is the processed log's and the register store's
     * @return the component
     */
    @Bean(name = "db")
    public HealthIndicator storeHealthIndicator(final ObjectProvider<DataSource> dataSource) {
        return () -> {
            final DataSource pool = dataSource.getIfAvailable();
            return pool == null
                    ? Health.down().withDetail("store", "no-pool").build()
                    : new DataSourceHealthIndicator(pool).health();
        };
    }
}
