package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.report.LogEventReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.ExceptionReportSink;
import uk.gov.hmcts.cp.yotresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.JdbcRegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedLogProbe;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedOutputRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterNotificationRepository;

/**
 * The processed log and the guard over it.
 *
 * <p>Registered here rather than annotated as components, so the guard and its repositories stay
 * plain constructor-injected objects that a unit test builds in one line. The persistence suites
 * already do exactly that, against a Testcontainers store and no Spring at all.
 *
 * <p>Excluded from the {@code test} profile because everything in it needs a {@code DataSource}, and
 * that profile deliberately has none: the plain context-load tests must keep running with no broker,
 * no database and therefore without Docker.
 *
 * <p><strong>The batch and notification repositories are here rather than with the generation
 * half.</strong> They were declared in {@link GenerationConfig}, which is conditional on
 * {@code yotresultsdistribution.generation.enabled}, and the morning exception report reads both - so
 * {@code yotresultsdistribution.report.enabled=true} with the generation half switched off, which is FR-004's
 * deployment and the MVP's own shape, could not start. This configuration is generation-neutral and
 * already declares every other reader of the same database over the same {@link JdbcClient} and the
 * same {@link PlatformTransactionManager} those two constructors take, so it is where they belong in
 * any case. A second copy of each declared in the report's own configuration was rejected for the
 * reason a second lock provider is: two beans of one repository over one table is a race with a
 * different name, and the pair would drift.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class ProcessedLogConfig {

    /**
     * The request half of the log.
     *
     * <p>It binds the claim lease once, because that is the only setting its statements need — the
     * expiry it produces is computed by the database, not here.
     *
     * @param jdbcClient the store
     * @param properties the typed settings, for the claim lease
     * @return the repository
     */
    @Bean
    public ProcessedRequestRepository processedRequestRepository(
            final JdbcClient jdbcClient, final YotResultsDistributionProperties properties) {
        return new ProcessedRequestRepository(jdbcClient, properties.claim().lease());
    }

    /**
     * The availability question the consumer lifecycle controller and every delivery both ask.
     *
     * <p>A bean of its own rather than a method on the repository: the repository's statements are
     * the state machine, and "can this database be reached at all" is a different question asked at
     * a different moment — before a delivery is examined, and on a schedule while intake is stopped.
     *
     * @param jdbcClient the store
     * @return the probe
     */
    @Bean
    public ProcessedLogProbe processedLogProbe(final JdbcClient jdbcClient) {
        return new ProcessedLogProbe(jdbcClient);
    }

    /**
     * The output half of the log — one row per submitted command, the court register having no
     * fan-out dimension.
     *
     * <p>No lease and no other setting: its statements are keyed and conditional on state alone, and
     * every timestamp in them comes from the database.
     *
     * @param jdbcClient the store
     * @return the repository
     */
    @Bean
    public ProcessedOutputRepository processedOutputRepository(final JdbcClient jdbcClient) {
        return new ProcessedOutputRepository(jdbcClient);
    }

    /**
     * The register store, over the same log and the same client as the repositories above it.
     *
     * <p>It belongs beside them because it writes the same table: a recorded register <em>is</em> the
     * output half of the processed log, widened by V2 with the document, the hearing, the register
     * instant and the batch it is on. Declared without a condition, so a pod running the default
     * {@code yotresultsdistribution.output=record} always has the store its last stage writes through, and the
     * {@code progression-post} fallback simply never asks it for anything.
     *
     * <p>The manager is handed over rather than a template built from it, because the store needs
     * two boundaries over it and only one of them is the ordinary kind: each stale batch's release
     * is made {@code REQUIRES_NEW}, so a caller that happened to be inside a transaction cannot
     * fold every court centre's release into one. Both are over <em>this</em> data source: the
     * file-service datasource is a second one, write-only and never transacted from here, and a
     * manager bound to it would open a transaction none of the store's statements ever joins.
     *
     * @param jdbcClient         the store
     * @param transactionManager the manager over the same data source the client issues against
     * @return the register store
     */
    @Bean
    public RegisterStore registerStore(
            final JdbcClient jdbcClient, final PlatformTransactionManager transactionManager) {
        return new JdbcRegisterStore(jdbcClient, transactionManager);
    }

    /**
     * The {@code register_batch} table.
     *
     * <p>Over the register store's own client, because a batch is the store's neighbour: the two
     * write the same database, and the report and the batch-age readings read this one while the
     * store writes the other.
     *
     * <p>The transaction manager is the register store's own, so the two statements the
     * notification claim is taken in - the advisory lock and the compare-and-set - run on the
     * connection this client already joins. It is the only thing here that needs a transaction at
     * all; every other statement is one statement.
     *
     * <p>The lease is {@code yotresultsdistribution.notification.claim-lease} and not {@code stale-after}.
     * The two answer different questions: how long a batch may be awaiting its render before the
     * next run gives up on it is no bound at all on telling a generated batch's recipients, whose
     * cost is the
     * number of Youth Offending Teams it is addressed to times whatever notificationnotify makes of
     * each of them. Startup refuses a lease that cannot cover one recipient's POST cycle twice over
     * ({@link PropertiesValidator#NOTIFICATION_LEASE_MARGIN}).
     *
     * @param jdbcClient         the processed log's client, which is the register store's
     * @param transactionManager the register store's transaction manager, for the claim's two
     *                           statements
     * @param properties         the bound settings, for the notification claim's lease
     * @return the repository
     */
    @Bean
    public RegisterBatchRepository registerBatchRepository(final JdbcClient jdbcClient,
            final PlatformTransactionManager transactionManager,
            final YotResultsDistributionProperties properties) {
        return new RegisterBatchRepository(jdbcClient,
                new TransactionTemplate(transactionManager),
                properties.notification().claimLease());
    }

    /**
     * The {@code register_notification} table.
     *
     * <p>Over the same client, and beside {@link #registerBatchRepository} rather than inside the
     * store, for the reason that read is: one recipient's row is a single-table read and write that
     * the register store has no business owning, and the notifier is the only thing that touches it.
     *
     * @param jdbcClient the processed log's client, which is the register store's
     * @return the repository
     */
    @Bean
    public RegisterNotificationRepository registerNotificationRepository(
            final JdbcClient jdbcClient) {
        return new RegisterNotificationRepository(jdbcClient);
    }

    /**
     * The morning exception report: eight reads over the tables declared above it, and no writes.
     *
     * <p>Here rather than in {@link ReportSchedulingConfig} on purpose. The report is asked for by
     * two callers, and one of them exists where the other does not: the 07:00 job only where the
     * schedule is switched on, and {@code POST /operations/exception-reports} wherever the
     * operations surface is - an incident does not wait for morning. A report declared beside the
     * schedule would be a report the endpoint could not ask for. It is a read of exactly the tables
     * this configuration declares the readers for, so this is also where it reads from: the two
     * halves of the processed log, the batches, the notifications and the register store, over one
     * client and one clock.
     *
     * <p>All three thresholds it is given are the report's own settings. {@code batch-generated-within}
     * borrowed the generation half's grace period until 004 renamed it {@code stale-after}; the two
     * answer different questions now - when support should be told a render is late, and when a run
     * gives up and re-batches - so the report states its own. The generation schedule is handed in because it is
     * what "the last scheduled run left this register behind" means, and the report has no business
     * guessing it.
     *
     * @param requests      the request half of the processed log
     * @param batches       the {@code register_batch} table
     * @param notifications the {@code register_notification} table
     * @param registers     the recorded registers, through the store's own port
     * @param report        the report's own settings, for the three limits
     * @param generation    the downstream half's settings, for the schedule the window opens on
     * @param metrics       where the five kinds and each delivery are counted
     * @param clock         the one clock the snapshot and the three cut-offs are taken from
     * @return the report
     */
    @Bean
    public ExceptionReportService exceptionReportService(
            final ProcessedRequestRepository requests, final RegisterBatchRepository batches,
            final RegisterNotificationRepository notifications, final RegisterStore registers,
            final ReportProperties report, final GenerationProperties generation,
            final ProcessingMetrics metrics, final Clock clock) {

        return new ExceptionReportService(requests, batches, notifications, registers,
                report.requestTerminalWithin(), report.batchGeneratedWithin(),
                report.notifiedWithin(), report.maxEntries(), generation.cron(), generation.zone(),
                metrics, clock);
    }

    /**
     * The structured events the platform's container-log collection carries into Log Analytics.
     *
     * <p>Unconditional, beside the report itself and for the same reason: it is the sink both
     * callers always deliver to, and a context that could build a report but had nowhere to write
     * it would be a morning nobody is told about. The e-mail sink is the one that is switched, and
     * it is contributed elsewhere.
     *
     * @return the log sink
     */
    @Bean
    public ExceptionReportSink logEventReportSink() {
        return new LogEventReportSink();
    }

    /**
     * The {@code (source, requestId)} idempotency guard.
     *
     * @param repository the request half of the log
     * @param metrics    the instrument surface stale-runner rejections are counted on
     * @return the guard
     */
    @Bean
    public IdempotencyGuard idempotencyGuard(
            final ProcessedRequestRepository repository, final ProcessingMetrics metrics) {
        return new IdempotencyGuard(repository, metrics);
    }
}
