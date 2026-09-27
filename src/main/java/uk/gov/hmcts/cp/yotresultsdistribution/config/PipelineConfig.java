package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.OutboundContractValidator;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DistributionPipeline;
import uk.gov.hmcts.cp.yotresultsdistribution.application.GroupProceedingsPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.application.HearingPayloadSource;
import uk.gov.hmcts.cp.yotresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterDocumentValidator;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterTransformer;
import uk.gov.hmcts.cp.yotresultsdistribution.inbound.DistributionCommandParser;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.Dates;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.RegisterBuilder;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.RegisterTransformationChain;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.SubscriptionMatcher;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.SubscriptionRules;

/**
 * The application core, and the one place the whole graph is assembled.
 *
 * <p>Every bean here is declared as its port type rather than as its own class, so replacing an
 * adapter is a change to one method here and to nothing else (constitution Principle V). The four
 * ports that reach outside this service are not declared here at all — each is served by a pair of
 * configurations chosen by a mode, and this file names none of them:
 *
 * <ul>
 *   <li>the hearing payload, by {@link LivePayloadConfig} or {@link StubPayloadConfig}, on
 *       {@code yotresultsdistribution.payload.mode};</li>
 *   <li>the now-subscriptions read, by {@link LiveSubscriptionsConfig} or
 *       {@link StubSubscriptionsConfig}, on {@code yotresultsdistribution.referencedata.mode};</li>
 *   <li>the submission, by {@link LiveSubmissionConfig} or {@link StubSubmissionConfig}, on the
 *       payload mode — a local stub run never fetches a hearing, so it never reaches the POST, and
 *       a second switch would give an operator two knobs for one sentence;</li>
 *   <li>the processed log, by {@link ProcessedLogConfig}.</li>
 * </ul>
 *
 * <p>Which of each pair may be chosen is not decided here either: {@link PropertiesValidator}
 * refuses a stub wherever the deployed credential source is in use, and refuses a stubbed
 * subscriptions source beside a live payload source — real hearings answered that way would every
 * one of them complete {@code no-subscriptions}, which is this flow's commonest legitimate outcome
 * and therefore indistinguishable from working.
 *
 * <p><strong>The transformation is wired as the chain, not as an absence.</strong> The pipeline's
 * short constructor builds the walking skeleton the transport phase was proven against, and a
 * skeleton assembled by <em>this</em> file is a deployed pod that settles every message having
 * produced no register at all — thirty-one fixes, twelve mappers and a contract validator, none of
 * them reachable from the application's entry point. The stages are singletons and hold nothing
 * about a run: the register builder, the subscription matcher and the contract validator are
 * stateless, the validator reading the vendored schemas once when it is built, and the run's own
 * anomaly counter is made per run by the pipeline rather than held by the chain.
 *
 * <p><strong>The pre-send contract check is wired unconditionally</strong>, and
 * {@code yotresultsdistribution.submission.validate-outbound} is a startup rule rather than a runtime switch:
 * {@link PropertiesValidator} refuses {@code false} wherever the deployed credential source is in
 * use, and no bean here reads the value. That is deliberate. C29 exists because a document
 * progression refuses is otherwise a 400 the legacy swallowed, and a wiring that could be told to
 * skip the check would put the blind spot back behind one property; a suite that needs an invalid
 * shape to reach the wire gets it from {@code ProgressionCommandGatewayTest}, over WireMock, without
 * a production bypass to reach it through.
 *
 * <p>The now-subscriptions read stays in the application layer, where the port is: the core makes
 * the call between the suppression decision and the transformation — which is where the legacy
 * orchestrator makes it — and hands the answer in, so no stage behind the transformation port can
 * reach a port of its own (Principle V). The run's cumulative deadline is unchanged and still one
 * budget across the fetch, the read and the send.
 *
 * <p>Excluded from the {@code test} profile for the same reason as the processed-log wiring: the
 * pipeline needs the guard, the guard needs a store, and that profile has none.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class PipelineConfig {

    /**
     * The bean name of the validator over the {@code add-court-register} command.
     *
     * <p>Both validators are instances of the same class and both implement the port, so every
     * injection point names the one it means. Left to type alone the container would find two
     * candidates for either question and refuse to start - which is the right refusal and the wrong
     * moment to discover which contract a stage is held to.
     */
    private static final String COMMAND_CONTRACT = "outboundContractValidator";

    /** The bean name of the validator over the frozen register document. */
    private static final String REGISTER_DOCUMENT_CONTRACT = "registerDocumentValidator";

    /**
     * The clock the run's processing deadline is measured against, and the one the queue-health
     * component ages its readings with.
     *
     * <p>Local elapsed time only. No claim decision is made from it — those compare the database's
     * {@code now()} against stored timestamps, inside the database — so this clock cannot introduce
     * the multi-node skew the data model's single-time-authority rule rules out.
     *
     * @return the clock
     */
    @Bean
    public Clock courtRegisterClock() {
        return Clock.systemUTC();
    }

    /**
     * The parser over the shared mapper, so the running service reads a body exactly as the contract
     * corpus does.
     *
     * @param objectMapper the shared, contract-configured mapper
     * @return the parser
     */
    @Bean
    public DistributionCommandParser distributionCommandParser(final ObjectMapper objectMapper) {
        return new DistributionCommandParser(objectMapper);
    }

    /**
     * The register's date handling: the three date fixes, and nothing that reads a clock.
     *
     * @return the date handling
     */
    @Bean
    public Dates registerDates() {
        return new Dates();
    }

    /**
     * Whether a hearing's group-proceedings flag suppresses its register — strictly, and counted
     * when the flag is not a boolean (fix C7).
     *
     * @param metrics the instrument surface the contract anomaly is counted on
     * @return the policy
     */
    @Bean
    public GroupProceedingsPolicy groupProceedingsPolicy(final ProcessingMetrics metrics) {
        return new GroupProceedingsPolicy(metrics);
    }

    /**
     * The fragment stage: the gather, the ordered dates, the court-extract filter and the
     * per-defendant vocabulary.
     *
     * @param dates the register's date handling
     * @return the builder
     */
    @Bean
    public RegisterBuilder registerBuilder(final Dates dates) {
        return new RegisterBuilder(dates);
    }

    /**
     * The addressing stage, over the subscription predicates.
     *
     * @return the matcher
     */
    @Bean
    public SubscriptionMatcher subscriptionMatcher() {
        return new SubscriptionMatcher(new SubscriptionRules());
    }

    /**
     * The stage that refuses a document progression would — fix C29.
     *
     * <p>Built once, and deliberately at startup: it reads the vendored schemas when it is
     * constructed and maps every {@code http://justice.gov.uk/…} identity to its vendored copy, so
     * a build that cannot assemble the contract refuses to start rather than degrading to "nothing
     * was checked", which is the blind spot C29 exists to close. Declared without a condition for
     * the same reason — see the note on {@code validate-outbound} on this class.
     *
     * @param objectMapper the shared, contract-configured mapper, so what is validated is what is
     *                     serialised
     * @return the validator
     */
    @Bean
    public OutboundContractValidator outboundContractValidator(final ObjectMapper objectMapper) {
        return new OutboundContractValidator(objectMapper);
    }

    /**
     * The contract the register is held to at the write into this service's own store.
     *
     * <p>A second instance of the same validator over the second frozen schema, and the two are not
     * interchangeable. The bean above holds an assembled register to the
     * {@code add-court-register} command, which is what a {@code progression-post} deployment sends;
     * this one holds the final register - the one carrying {@code defendantType} - to
     * {@code courtRegisterDocumentRequest.json}, which is what the batch reads back and what the PDF
     * payload is built from (constitution Principle III). Held to the command instead, every
     * register 002 records would be refused under {@code UNKNOWN_FIELD [/defendantType]}.
     *
     * <p>Declared unconditionally and built at startup for the same reason as the bean above: the
     * schemas are read when it is constructed, so a build that cannot assemble the contract refuses
     * to start rather than degrading to "nothing was checked".
     *
     * @param objectMapper the shared, contract-configured mapper, which is what serialises the
     *                     document into the store too
     * @return the validator
     */
    @Bean
    public RegisterDocumentValidator registerDocumentValidator(final ObjectMapper objectMapper) {
        return OutboundContractValidator.overTheRegisterDocument(objectMapper);
    }

    /**
     * The transformation port, served by the whole chain: build, address, assemble, hold to the
     * contract.
     *
     * <p>Pure, and a singleton because it holds nothing about a run — the reference data it matches
     * against is an argument, and so is the sink its guarded skips are counted into.
     *
     * @param builder   the fragment stage
     * @param matcher   the addressing stage
     * @param validator the stage that refuses a document progression would
     * @return the port
     */
    @Bean
    public RegisterTransformer registerTransformer(
            final RegisterBuilder builder,
            final SubscriptionMatcher matcher,
            @Qualifier(COMMAND_CONTRACT) final OutboundContractValidator validator) {
        return new RegisterTransformationChain(builder, matcher, validator);
    }

    /**
     * The use-case orchestrator, wired against ports only.
     *
     * <p><strong>The output mode is bound here and nowhere else.</strong>
     * {@code yotresultsdistribution.output} says whether the last stage records the register in this
     * service's own store or POSTs it to progression; it is read once, when the graph is assembled,
     * and handed to the pipeline as a value. Both last stages are wired, because the mode chooses
     * between them rather than replacing one - see {@link OutputMode} on why that is a build-time
     * fallback and not a second cutover lever.
     *
     * <p>The register store is taken as a provider rather than as a required bean, and only because
     * of where it is declared: it belongs to {@link ProcessedLogConfig}, beside the repositories over
     * the table it writes, so a context that assembles this configuration without that one - the
     * composition suite, which states {@code progression-post} and doubles the outward ports - still
     * starts. Every deployed pod has both, so the {@code record} arm always has its store.
     *
     * @param guard               the processed-log guard
     * @param payloadSource       where hearing payloads come from
     * @param groupProceedings    whether the hearing's flag suppresses its register
     * @param subscriptionsSource where the now-subscriptions a register is addressed with come from
     * @param dates               the register's date handling, for the subscription day
     * @param transformer         how a hearing payload and its subscriptions become a register
     * @param registerStore       this service's own register store, where one is wired
     * @param recordedDocument    the frozen register-document contract the write is held to
     * @param submissionClient    where an assembled register is sent under {@code progression-post}
     * @param metrics             the instrument surface every outcome is counted on
     * @param clock               the clock the run's deadline is measured against
     * @param properties          the typed settings, for the output mode and the processing deadline
     * @return the pipeline
     */
    // Seven ports, one policy, one date helper and three settings: the core's own dependencies,
    // each injected as the port type it is asked for. See DistributionPipeline's note on the count.
    @Bean
    public DistributionPipeline distributionPipeline(
            final IdempotencyGuard guard,
            final HearingPayloadSource payloadSource,
            final GroupProceedingsPolicy groupProceedings,
            final NowSubscriptionsSource subscriptionsSource,
            final Dates dates,
            final RegisterTransformer transformer,
            final ObjectProvider<RegisterStore> registerStore,
            @Qualifier(REGISTER_DOCUMENT_CONTRACT) final RegisterDocumentValidator recordedDocument,
            final RegisterSubmissionClient submissionClient,
            final ProcessingMetrics metrics,
            final Clock clock,
            final YotResultsDistributionProperties properties) {
        return new DistributionPipeline(
                guard, payloadSource, groupProceedings, subscriptionsSource, dates, transformer,
                properties.output(), registerStore.getIfAvailable(), recordedDocument,
                submissionClient, metrics, clock, properties.claim().processingDeadline());
    }
}
