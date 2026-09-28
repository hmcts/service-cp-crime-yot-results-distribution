package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPause;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentOutcomeSink;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentOutcomeSinkImpl;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentRenderer;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadFileStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterGenerationService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifier;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifierService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.BatchAssembler;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterNotificationRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.PdfPayloadMapper;

/**
 * The downstream half's own classes, put on the context that is going to run them.
 *
 * <p>Everything here is this service's, with no transport of its own: the grouping, the ported
 * payload generator, the one code path an outcome takes, and the requesting leg. The
 * adapters they speak through are chosen separately - {@link LiveGenerationConfig} where a
 * deployment means it, {@link StubGenerationConfig} where a local run does not - which is why the
 * two are two files: what a batch <em>is</em> does not change with the mode, and a configuration
 * that declared both would make the core conditional on a transport setting.
 *
 * <p>Declared as beans rather than annotated as components, exactly as {@link ProcessedLogConfig}'s
 * are and for the same reason: every one of these is a plain constructor-injected object that its
 * own suite builds in one line, and it stays that way.
 *
 * <p><strong>Only where the downstream half is deployed, and only outside the {@code test}
 * profile.</strong> The whole of it is conditional on {@code yotresultsdistribution.generation.enabled}, so
 * an intake-only pod builds none of it; and it needs the register store, which needs a
 * {@code DataSource}, which the {@code test} profile deliberately has none of.
 *
 * <p><strong>Which is why the two repositories are no longer here.</strong>
 * {@code registerBatchRepository} and {@code registerNotificationRepository} were declared in this
 * file, so "an intake-only pod builds none of it" took them with it - and the morning exception
 * report reads both, on a pod that generates nothing. They are in {@link ProcessedLogConfig} now,
 * beside the other readers of the same database and over the same client and transaction manager.
 * Nothing about either class changed; only where its bean is declared. What is left here is what
 * is generation-only, and every constructor below still takes them exactly as it did.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "yotresultsdistribution.generation", name = "enabled", havingValue = "true")
public class GenerationConfig {

    /** The setting the register e-mail's template id arrives on, named by its own refusal. */
    private static final String EMAIL_TEMPLATE = "yotresultsdistribution.email.templates.cr_standard";

    /**
     * The one lever's gate: the flag read, and what a run may do about the answer.
     *
     * <p>Beside the classes it gates rather than beside the reader it asks, because which adapter
     * answers the flag is a mode decision and what a run does with the answer is not: the gate reads
     * OFF and UNREADABLE the same way whichever of the two readers replied (constitution Cutover
     * Rule).
     *
     * @param reader  the flag port, LIVE or STUB as the mode chose
     * @param metrics where a skipped run is counted, by the reason it was skipped
     * @return the gate every run asks first
     */
    @Bean
    public FeatureFlagGate featureFlagGate(
            final FeatureFlagReader reader, final GenerationMetrics metrics) {
        return new FeatureFlagGate(reader, metrics);
    }

    /**
     * The grouping of a night's registers into one batch per court centre and register date.
     *
     * @return the assembler, which decides and reads nothing
     */
    @Bean
    public BatchAssembler batchAssembler() {
        return new BatchAssembler();
    }

    /**
     * progression's payload generator, ported.
     *
     * <p>The clock is the service's own, the same bean the pipeline and the run measure by: the
     * payload carries a generated-at stamp, and a generator reading a different now would date a
     * document differently from the batch row that names it.
     *
     * @param clock this pod's reading of now
     * @return the mapper
     */
    @Bean
    public PdfPayloadMapper pdfPayloadMapper(final Clock clock) {
        return new PdfPayloadMapper(clock);
    }

    /**
     * The last leg of a batch: its recipients, their rows and their e-mails.
     *
     * <p>Contributed here rather than beside the adapter it sends through, because what a batch owes
     * its Youth Offending Teams does not change with the transport: {@link LiveNotificationConfig}
     * and {@link StubGenerationConfig} choose which {@code RegisterNotifier} answers, and this is
     * the object that mints the rows, keeps the tally and settles the batch either way.
     *
     * <p><strong>The template id is resolved here, once.</strong> That is the whole of defect fix
     * P9: the legacy resolved it per recipient and, finding it blank, logged one line and moved on.
     * {@link PropertiesValidator} has already refused a blank or malformed value in LIVE mode, and
     * LIVE is the only mode this configuration can be reached under - STUB is refused outright
     * wherever {@code yotresultsdistribution.generation.enabled} is true - so the parse below is a second
     * statement of a rule that has already been enforced, kept because a bean is not entitled to
     * assume the order beans are built in.
     *
     * <p><strong>The retry policy is the same object the generation leg is given</strong>, built
     * from the same five settings: {@code yotresultsdistribution.endpoints.max-attempts} and the two
     * back-off bounds are the transport the systemdocgenerator and notificationnotify clients share,
     * so the taxonomy is stated once (defect fix C3) and only the loop belongs to whoever holds the
     * budget an attempt is spent out of.
     *
     * @param store         where the batch's registers are read and the batch is settled
     * @param batches       the {@code register_batch} read that gives the generated document's id
     * @param notifications the {@code register_notification} rows
     * @param notifier      notificationnotify, LIVE or STUB as the mode chose
     * @param metrics       where each recipient and each terminal batch state is counted
     * @param properties    the bound settings, for the {@code cr_standard} template id and the
     *                      shared transport
     * @param clock         the run's own reading of now, which is what {@code sent_at} records
     * @return the notifying leg
     */
    @Bean
    public RegisterNotifierService registerNotifierService(final RegisterStore store,
            final RegisterBatchRepository batches,
            final RegisterNotificationRepository notifications, final RegisterNotifier notifier,
            final GenerationMetrics metrics, final YotResultsDistributionProperties properties,
            final Clock clock) {

        return new RegisterNotifierService(store, batches, notifications, notifier, metrics,
                crStandardTemplate(properties), sharedRetryPolicy(properties),
                (RetryPause) Thread::sleep, clock);
    }

    /**
     * The one code path a rendering outcome takes, and there is one way an outcome arrives.
     *
     * @param store    where the batch and its rows are moved
     * @param batches  the {@code register_batch} table, read to correlate an outcome to a batch
     * @param metrics  where an outcome no batch takes is counted, by the reason it was not taken
     * @param notifier the notifying leg, asked immediately after the mark that records the document
     * @return the port
     */
    @Bean
    public DocumentOutcomeSink documentOutcomeSink(final RegisterStore store,
            final RegisterBatchRepository batches, final GenerationMetrics metrics,
            final RegisterNotifierService notifier) {
        return new DocumentOutcomeSinkImpl(store, batches, metrics, notifier);
    }

    /**
     * One batch, from the payload to the render request.
     *
     * <p>The retry policy is the shared one, built from the settings the two downstream clients
     * share: the taxonomy is stated once (defect fix C3) and only the loop lives here, because the
     * budget an attempt is measured against is the run's knowledge and not systemdocgenerator's.
     *
     * @param store        where the batch's registers are read and its progress written
     * @param mapper       progression's payload generator, ported
     * @param fileStore    the framework file service the payload is written to
     * @param renderer     systemdocgenerator, behind the port that names no status code
     * @param objectMapper the shared mapper, which decides what the payload's bytes are
     * @param properties   the endpoints' shared transport settings, for the retry policy
     * @param metrics      the downstream half's instruments
     * @param clock        the run's own reading of now
     * @return the requesting leg
     */
    @Bean
    public RegisterGenerationService registerGenerationService(final RegisterStore store,
            final PdfPayloadMapper mapper, final PayloadFileStore fileStore,
            final DocumentRenderer renderer, final ObjectMapper objectMapper,
            final YotResultsDistributionProperties properties, final GenerationMetrics metrics,
            final Clock clock) {

        return new RegisterGenerationService(store, mapper, fileStore, renderer, objectMapper,
                sharedRetryPolicy(properties), (RetryPause) Thread::sleep, metrics, clock);
    }

    /**
     * The one retry policy, built from the five settings the two downstream clients share.
     *
     * <p>Built here for both legs rather than once per leg, which is what defect fix C3 asks of it:
     * the statuses worth asking again, the back-off between two attempts and what one attempt can
     * cost are one opinion, and two constructions of the same record are two places that opinion
     * can drift.
     *
     * @param properties the bound settings, for the endpoints' shared transport
     * @return the policy both legs spend their attempts against
     */
    private static RetryPolicy sharedRetryPolicy(final YotResultsDistributionProperties properties) {
        final YotResultsDistributionProperties.Endpoints endpoints = properties.endpoints();
        return new RetryPolicy(endpoints.maxAttempts(), endpoints.initialBackoff(),
                endpoints.maxBackoff(),
                endpoints.connectTimeout().plus(endpoints.readTimeout()));
    }

    /**
     * The {@code cr_standard} template id as the environment configured it.
     *
     * <p>A refusal rather than a default, and it names the setting: a register e-mail sent under no
     * template is the silent non-delivery defect fix P9 catalogues, and a service that invented an
     * id would turn it into a refusal from notificationnotify for every recipient of every batch
     * instead.
     *
     * @param properties the bound settings
     * @return the template every register is sent under
     */
    private static UUID crStandardTemplate(final YotResultsDistributionProperties properties) {
        final String configured = properties.email().templates().crStandard();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(EMAIL_TEMPLATE
                    + " must be the notificationnotify template the register is sent under (P9)");
        }
        try {
            return UUID.fromString(configured);
        } catch (IllegalArgumentException notAnIdentity) {
            throw new IllegalStateException(EMAIL_TEMPLATE + " must be a UUID (P9)", notAnIdentity);
        }
    }
}
