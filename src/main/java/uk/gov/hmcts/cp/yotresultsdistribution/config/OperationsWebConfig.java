package uk.gov.hmcts.cp.yotresultsdistribution.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.TaskScheduler;
import uk.gov.hmcts.cp.yotresultsdistribution.api.OperationsActionFilter;
import uk.gov.hmcts.cp.yotresultsdistribution.api.OperationsAuditService;
import uk.gov.hmcts.cp.yotresultsdistribution.api.OperationsContentTypeFilter;
import uk.gov.hmcts.cp.yotresultsdistribution.api.OperationsErrorAttributes;
import uk.gov.hmcts.cp.yotresultsdistribution.application.BatchListingService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OnDemandExceptionReportService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsRunLauncher;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsSupersessionService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterGenerationService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterRegenerationService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.BatchAssembler;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterNotificationRepository;

/**
 * What the operations API needs registered on the servlet container.
 *
 * <p>Two filters and the error attributes, and each is here because something about it has to be
 * stated rather than annotated. The two orders are the first; the error attributes' replacement of
 * Boot's own bean is the second - a {@code @Component} would do it, but then the reason it exists
 * would live nowhere near the filters whose refusals it renders.
 *
 * <p>The orders are the whole reason the filters are registered here rather than annotated into
 * existence, and the two are ordered for opposite reasons. {@link OperationsActionFilter} must run
 * <strong>ahead of</strong> {@code cp-auth-rules-filter} (which the library places at
 * {@code HIGHEST_PRECEDENCE + 30}) and of {@code cp-audit-filter-springboot} (fixed at
 * {@code +50}), because both of them read the action name it derives (research R11).
 * {@link OperationsContentTypeFilter} must run <strong>between</strong> them, at {@code +40}:
 * outside the audit filter, which hands a multipart request down the chain publishing neither
 * event, and inside the authorisation filter, so that an anonymous caller is answered {@code 401}
 * rather than told what this surface consumes.
 *
 * <p><strong>The switch is on the beans, not on the class.</strong>
 * {@code yotresultsdistribution.operations.enabled} is deployment shape and not a cutover lever (FR-044):
 * it decides whether this service answers the operator's seven paths at all, and a pod that does
 * not answer them has no action to name - so the filter and the listings it would call are
 * conditional. The error attributes are <em>not</em>, because a pod with the surface switched off
 * still answers a 404 to whatever an operator tried, and Boot's own body for one echoes the path
 * they typed (FR-025, FR-027). The default is <strong>on</strong>, here as on
 * {@link OperationsProperties}, so a deployment that says nothing gets the filter.
 *
 * <p>Switching it off must never cost the pod its start-up, which is why the three controllers
 * carry the same condition: a controller left scanned over a listing that is no longer contributed
 * is an {@code UnsatisfiedDependencyException} at refresh, and a switch that crashes the pod is not
 * a switch.
 */
@Configuration(proxyBeanMethods = false)
public class OperationsWebConfig {

    /** The prefix of the one switch that decides whether this service answers /operations/**. */
    private static final String OPERATIONS = "yotresultsdistribution.operations";

    /** Its name under that prefix. */
    private static final String ENABLED = "enabled";

    /** And the value that switches it on, which is also what an absent setting means. */
    private static final String ON = "true";

    /** Where the content-type guard sits: after the authorisation filter, before the audit one. */
    private static final int CONTENT_TYPE_GUARD = 40;

    /**
     * The profile the store, its repositories and the generating adapters are declared away from.
     *
     * <p>A constant because four beans here carry it and the {@code test} profile deliberately has
     * no database: a service over readers that do not exist is a context that will not refresh.
     */
    private static final String NOT_TEST = "!test";

    /**
     * Registers the action filter first in the chain, for every request.
     *
     * <p>Mapped over everything rather than over {@code /operations/*}: the filter passes an
     * unrecognised path through untouched, and a mapping would be a second place for the list of
     * this service's paths to live and to drift from the one inside the filter.
     *
     * @return the registration, ordered ahead of both estate filters
     */
    @Bean
    @ConditionalOnProperty(prefix = OPERATIONS, name = ENABLED,
            havingValue = ON, matchIfMissing = true)
    public FilterRegistrationBean<OperationsActionFilter> operationsActionFilter() {
        final FilterRegistrationBean<OperationsActionFilter> registration =
                new FilterRegistrationBean<>(new OperationsActionFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Registers the content-type guard between the two estate filters.
     *
     * <p>Order {@code HIGHEST_PRECEDENCE + 40}: after {@code cp-auth-rules-filter} at {@code +30}
     * and before {@code cp-audit-filter-springboot} at {@code +50}. Both halves of that sentence
     * are load-bearing - see {@link OperationsContentTypeFilter}.
     *
     * <p>Mapped over everything for the reason the action filter is: the filter passes an
     * unrecognised path through untouched, and a mapping would be a second place for the list of
     * this service's paths to live.
     *
     * @return the registration, ordered between the two estate filters
     */
    @Bean
    @ConditionalOnProperty(prefix = OPERATIONS, name = ENABLED,
            havingValue = ON, matchIfMissing = true)
    public FilterRegistrationBean<OperationsContentTypeFilter> operationsContentTypeFilter() {
        final FilterRegistrationBean<OperationsContentTypeFilter> registration =
                new FilterRegistrationBean<>(new OperationsContentTypeFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + CONTENT_TYPE_GUARD);
        return registration;
    }

    /**
     * The audit publisher this service supplies in the starter's place.
     *
     * <p>Two reasons, and the first is a principle. The starter's own
     * {@code AuditService.postMessageToArtemis} catches every {@code Exception}, logs it and
     * returns, so a broker outage would let an operations call succeed with no audit event -
     * which Principle VI (nothing swallowed) and Principle III(b) (every endpoint audited) both
     * refuse. The second is content: the generic filter can infer the action, the outcome, an
     * override and a superseded count from no body, so the replacement merges them in from
     * {@code OperationsAuditFacts} (FR-046).
     *
     * <p>The starter registers its own bean {@code @ConditionalOnMissingBean(AuditService.class)},
     * so this one simply takes its place and the filter uses it unchanged (research R10).
     *
     * <p><strong>Built only where the audit transport is.</strong> Every {@code audit.http.*} bean
     * the starter declares - the filter included - sits inside the {@code @AutoConfiguration} class
     * {@code cp.audit.enabled} gates, and the template this bean publishes through is one of them.
     * A pod with the transport off holds no filter to publish from and no template to publish with,
     * and contributing this would only fail its own injection.
     *
     * @param auditJmsTemplate the template the starter built against the audit broker
     * @param auditObjectMapper the starter's own mapper, so the event is spelled as it spells it
     * @param meters           where the unpublished-event counter is registered
     * @return the publisher the audit filter uses
     */
    @Bean
    @ConditionalOnProperty(prefix = "cp.audit", name = ENABLED, havingValue = ON)
    public OperationsAuditService operationsAuditService(
            @Qualifier("auditJmsTemplate") final JmsTemplate auditJmsTemplate,
            @Qualifier("auditObjectMapper") final ObjectMapper auditObjectMapper,
            final MeterRegistry meters) {

        final Counter unpublished = Counter.builder("yotresultsdistribution_operations_audit_unpublished")
                .description("Audit events this pod could not publish and did not refuse: a "
                        + "response event an answered operations call had already been given, "
                        + "and any event raised on a thread serving no operations call. Both are "
                        + "the recorded shortfall a durable outbox would close. A request event "
                        + "is refused, not counted here")
                .register(meters);
        return new OperationsAuditService(auditJmsTemplate, auditObjectMapper, unpublished);
    }

    /**
     * Replaces the body Boot writes for an error it rendered itself.
     *
     * <p>The authorisation filter refuses through {@code sendError}, which forwards to
     * {@code /error}: the 401 and the 403 are written there and not by any advice of ours. Boot's
     * default body echoes the request path, which on an unmapped path is a string the caller typed
     * (FR-025, FR-027).
     *
     * @return the bounded error body: a status, a title and a bounded reason, and nothing else
     */
    @Bean
    public OperationsErrorAttributes operationsErrorAttributes() {
        return new OperationsErrorAttributes();
    }

    /**
     * The two listings, over the three reads they are built from.
     *
     * <p>{@code @Profile(NOT_TEST)} for the reason the store and its two repositories carry it in
     * {@code ProcessedLogConfig}: that profile deliberately has no database, and a listing over
     * readers that do not exist is a context that will not refresh. The condition is stated here
     * rather than taken from there because this increment does not touch that file.
     *
     * @param batchRepository        the date's batches
     * @param notificationRepository each batch's recipient rows
     * @param registerStore          the register rows behind a batch's count and the
     *                               recorded-while-off listing
     * @return the listings the two read endpoints call
     */
    @Bean
    @Profile(NOT_TEST)
    @ConditionalOnProperty(prefix = OPERATIONS, name = ENABLED,
            havingValue = ON, matchIfMissing = true)
    public BatchListingService batchListingService(final RegisterBatchRepository batchRepository,
            final RegisterNotificationRepository notificationRepository,
            final RegisterStore registerStore) {
        return new BatchListingService(batchRepository, notificationRepository, registerStore);
    }

    /**
     * The exception report an operator asks for now, over the same reads the 07:00 run takes.
     *
     * <p>Behind the operations switch and the {@code !test} profile for the reason the listings
     * are: the reads it is built from are declared {@code !test}, and the switch that withdraws the
     * operator's paths must withdraw what serves them too.
     *
     * <p><strong>Not behind {@code yotresultsdistribution.report.enabled}.</strong> That switch decides
     * whether the 07:00 run happens, and an incident does not wait for morning: a pod with the
     * schedule off still holds the reads and the log sink, so it can still answer what is wrong
     * with what it recorded. Nor is it behind {@code yotresultsdistribution.generation.enabled}, because the
     * report is on no cutover circuit and reads the flag nowhere.
     *
     * <p>The schedule and the e-mail switch are handed in as values rather than as the properties
     * record, exactly as {@link ExceptionReportService}'s limits are: the application layer takes a
     * cron, a zone and a boolean, not the shape of a configuration file.
     *
     * @param reporting the reads and the delivery, shared with the 07:00 run
     * @param sinks     every report sink this context holds
     * @param report    the report's own settings, for the schedule and the e-mail switch
     * @param clock     the one clock the window, the snapshot and the duration are taken from
     * @return the on-demand report
     */
    @Bean
    @Profile(NOT_TEST)
    @ConditionalOnProperty(prefix = OPERATIONS, name = ENABLED,
            havingValue = ON, matchIfMissing = true)
    public OnDemandExceptionReportService onDemandExceptionReportService(
            final ExceptionReportService reporting, final List<ExceptionReportSink> sinks,
            final ReportProperties report, final Clock clock) {

        return new OnDemandExceptionReportService(reporting, sinks, report.cron(), report.zone(),
                report.email().enabled(), clock);
    }

    /**
     * The rollback, with the flag read and the two bounds it is admitted under.
     *
     * <p>It takes the one lever's reader although its command took none: supersede is the endpoint
     * that gives a period of registers up, and it is admitted only while an uncached read says the
     * legacy is what generates. The reader is contributed wherever this service runs, so this bean
     * needs no condition beyond the operations switch and the {@code !test} profile the store
     * carries.
     *
     * @param registers  the registers, through the store's own port
     * @param flag       the one lever
     * @param operations the operations settings, for how far back a rollback may reach
     * @param clock      the clock both bounds are taken against
     * @return the rollback
     */
    @Bean
    @Profile(NOT_TEST)
    @ConditionalOnProperty(prefix = OPERATIONS, name = ENABLED,
            havingValue = ON, matchIfMissing = true)
    public OperationsSupersessionService operationsSupersessionService(
            final RegisterStore registers, final FeatureFlagReader flag,
            final OperationsProperties operations, final Clock clock) {

        return new OperationsSupersessionService(registers, flag, operations.supersedeMaxAge(),
                clock);
    }

    /**
     * The two services an operations call needs the <strong>generation</strong> half for.
     *
     * <p>A nested configuration rather than two more bean methods above, because the condition is
     * a different one: {@code yotresultsdistribution.generation.enabled} decides whether this pod holds a
     * flag gate, an assembler and a requesting leg at all, and a bean over collaborators that are
     * not contributed is a context that will not refresh. A pod without them answers the two
     * endpoints {@code 501 command-not-wired} through {@code api/NotWiredController} instead,
     * which is exactly what the command they replace answered there.
     */
    @Configuration(proxyBeanMethods = false)
    @Profile(NOT_TEST)
    @ConditionalOnProperty(prefix = "yotresultsdistribution.generation", name = "enabled",
            havingValue = "true")
    public static class GenerationBackedOperations {

        /**
         * The regeneration, over the three collaborators a generation needs.
         *
         * <p>The run deadline is handed in as a value rather than as {@link GenerationProperties}
         * itself, exactly as the report's limits are: the application layer takes a duration, not
         * the shape of a configuration file.
         *
         * @param registers  where the day's batches are read, released and stamped
         * @param assembler  the grouping into one batch per court centre and register date
         * @param generation the requesting leg, asked once per batch
         * @param settings   the generation settings, for the run deadline
         * @param clock      this pod's reading of now
         * @return the regeneration the background run makes
         */
        @Bean
        @ConditionalOnProperty(prefix = OPERATIONS, name = ENABLED,
                havingValue = ON, matchIfMissing = true)
        public RegisterRegenerationService registerRegenerationService(
                final RegisterStore registers, final BatchAssembler assembler,
                final RegisterGenerationService generation, final GenerationProperties settings,
                final Clock clock) {

            return new RegisterRegenerationService(registers, assembler, generation,
                    settings.runDeadline(), clock);
        }

        /**
         * The {@code 202} hand-off, over the schedule's own lock and the schedule's own thread.
         *
         * <p>The executor is the generation scheduler's, taken by the name
         * {@link SchedulingConfig#GENERATION_SCHEDULER} publishes: a regeneration and a scheduled
         * run then cannot interleave on one pod even before the lock is considered (research R16).
         * It is adapted to a plain {@link Executor} here rather than reaching the application
         * layer as a Spring type, and the instant it is scheduled at is the scheduler's own
         * reading of now, which is immediately.
         *
         * @param regeneration the regeneration the background run makes
         * @param gate         the one lever's gate, read once per run and uncached
         * @param locks        the schedule's own lock provider
         * @param generation   the generation settings, for how long the lock is held at most
         * @param operations   the operations settings, for how long the run waits for the lock
         * @param scheduler    the generation scheduler, which is where the work happens
         * @param clock        this pod's reading of now
         * @return the launcher the generate endpoint calls
         */
        @Bean
        @ConditionalOnProperty(prefix = OPERATIONS, name = ENABLED,
                havingValue = ON, matchIfMissing = true)
        public OperationsRunLauncher operationsRunLauncher(
                final RegisterRegenerationService regeneration, final FeatureFlagGate gate,
                final LockProvider locks, final GenerationProperties generation,
                final OperationsProperties operations,
                @Qualifier(SchedulingConfig.GENERATION_SCHEDULER) final TaskScheduler scheduler,
                final Clock clock) {

            final Executor onTheGenerationThread =
                    work -> scheduler.schedule(work, scheduler.getClock().instant());
            return new OperationsRunLauncher(regeneration, gate, locks, generation.lockAtMostFor(),
                    operations.lockWait(), onTheGenerationThread, clock);
        }
    }
}
