package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.MessageListenerContainer;

/**
 * Registers the two health components the downstream half brings with it.
 *
 * <p>Deliberately not inside {@link FileServiceDataSourceConfig}, which {@link FileServiceNeeded}
 * switches off. Spring validates health-group membership at startup, so a readiness group naming a
 * contributor that a property had removed would fail the context with a message about health groups
 * rather than about the setting somebody changed - the same reason {@link IntakeStartupHealth} sits
 * outside the consumer's configuration. The file-service component therefore always exists and asks
 * for the datasource rather than requiring one; on a pod that starts no generation run it answers
 * idle for ever and probes nothing.
 *
 * <p><strong>Which pods have a pool to probe followed the condition.</strong> Since review gate 7
 * the second datasource is built wherever either half writes a file, so a report pod with the
 * e-mail output on has one - and this component finds it rather than the unconfigured answer below.
 * It still gates readiness only while a <em>generation</em> run is in progress, and such a pod
 * starts none, so nothing about readiness changes on any deployment.
 *
 * <p>The subscription's component is the other way round, and for a reason that is not symmetry: it
 * is contributed only where generation is enabled, because it is in no group and is therefore part
 * of the overall aggregate, and a component reporting a subscription an intake-only deployment was
 * never meant to hold would put a working pod DOWN. Its absence costs nothing, since a pod with no
 * subscription has no subscription state to report.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class GenerationHealth {

    /**
     * The file-service pool, asked whether it can still hand out a connection.
     *
     * <p>Spring Boot's own datasource contributor, built by hand rather than auto-configured,
     * because what the auto-configuration would contribute is not this: it collects the context's
     * datasource beans and, finding two, reports them as one composite {@code db}. That is what it
     * did until the readiness cases of T071 caught it, and {@code defaultCandidate = false} on the
     * pool did not prevent it - the flag that would is {@code autowire-candidate}, and setting that
     * would hide the pool from the qualified injection below. The auto-configured contributor is
     * switched off in {@code application.yaml} and {@link StoreHealth} contributes {@code db} over
     * the register store's pool alone, so this probe is the only thing that ever asks the file
     * service, and only while a run is on.
     *
     * <p>It is not a bean. A {@link HealthIndicator} on the context is a health component, and this
     * one would be a second file-service entry in the aggregate: polled every few seconds, holding a
     * connection to somebody else's database open all day, reporting DOWN at nine in the morning
     * over a datasource nothing will touch until six in the evening. It exists only behind
     * {@link FileServiceRunHealthIndicator}, which decides when it may be asked.
     *
     * @param fileServiceDataSource the write-only pool, by name and only where a half needs it
     * @return the probe, or one that answers DOWN where there is no pool to probe
     */
    private static HealthIndicator probe(final ObjectProvider<DataSource> fileServiceDataSource) {
        final DataSource dataSource = fileServiceDataSource.getIfAvailable();
        // The unconfigured answer is DOWN rather than UP, and it is unreachable rather than
        // pessimistic: a pod with no file-service pool needs none, so it starts no generation run,
        // and such a run is the whole of when this probe is asked anything. Answering UP would mean
        // an enabled generation that had somehow lost its datasource reported a healthy payload
        // store while failing every batch.
        return dataSource == null
                ? () -> Health.down().build()
                : new DataSourceHealthIndicator(dataSource);
    }

    /**
     * Named so that Spring's contributor naming yields {@code fileServiceRun} - the name the
     * readiness group, a probe and a runbook all use.
     *
     * @param fileServiceDataSource the write-only pool, present only where a half writes a file
     * @return the readiness contribution the nightly run switches on and off
     */
    @Bean
    public FileServiceRunHealthIndicator fileServiceRunHealthIndicator(
            @Qualifier(FileServiceDataSourceConfig.DATA_SOURCE)
            final ObjectProvider<DataSource> fileServiceDataSource) {
        return new FileServiceRunHealthIndicator(probe(fileServiceDataSource));
    }

    /**
     * Named so that Spring's contributor naming yields {@code publicEvents} - and that name appears
     * in no group, which is the whole point of it (spec FR-011).
     *
     * @param containers the listener containers, which are what knows whether the subscription is up
     * @param clock      the clock the delivery age is measured against
     * @return the subscription's own component, outside readiness
     */
    @Bean
    @ConditionalOnProperty(prefix = "yotresultsdistribution.generation", name = "enabled",
            havingValue = "true")
    public PublicEventsHealthIndicator publicEventsHealthIndicator(
            final ObjectProvider<JmsListenerEndpointRegistry> containers, final Clock clock) {
        return new PublicEventsHealthIndicator(() -> subscriptionRunning(containers), clock);
    }

    /**
     * Whether the subscription's container is running, asked of the registry every time.
     *
     * <p>The registry rather than a named container: the subscription's endpoint id belongs to the
     * listener that declares it, and a copy of that name here would be a second place to change it.
     * This service declares exactly one JMS listener, so "every container is running" and "the
     * subscription is running" are the same sentence - and a registry holding no container at all is
     * a pod that is not subscribed, which is what a stopped subscription means for outcomes.
     *
     * @param containers the registry, absent where no JMS listener is configured at all
     * @return whether the subscription is receiving
     */
    private static boolean subscriptionRunning(
            final ObjectProvider<JmsListenerEndpointRegistry> containers) {
        final JmsListenerEndpointRegistry registry = containers.getIfAvailable();
        return registry != null
                && !registry.getListenerContainers().isEmpty()
                && registry.getListenerContainers().stream()
                        .allMatch(MessageListenerContainer::isRunning);
    }
}
