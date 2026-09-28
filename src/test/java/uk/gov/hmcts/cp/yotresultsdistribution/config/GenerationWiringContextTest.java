package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.fileservice.FileServicePayloadStore;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.publicevents.DocumentEventListener;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub.StubDocumentRenderer;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub.StubPayloadFileStore;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.systemdocgenerator.SystemDocGeneratorClient;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentOutcomeSink;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentRenderer;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadFileStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterGenerationService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterStore;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.BatchAssembler;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.ExceptionReportJob;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.StaleBatchReleaser;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.PdfPayloadMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.WorkloadIdentityStub;

/**
 * The downstream half in a context that is actually a deployment's.
 *
 * <p>Every class Phase 5 landed is reachable from a unit test and none of them was reachable from
 * Spring. There was no {@code @Component} on and no {@code @Bean} constructing
 * {@code DocumentOutcomeSinkImpl}, {@link RegisterGenerationService},
 * {@link BatchAssembler}, {@link PdfPayloadMapper}, {@link FileServicePayloadStore} or
 * {@link SystemDocGeneratorClient}; the only {@code PayloadFileStore} and {@code DocumentRenderer}
 * beans in the whole context were {@link StubGenerationConfig}'s stand-ins. So
 * {@code PublicEventsConfig.documentEventListener} found no sink and returned {@code null} - no
 * {@code @JmsListener}, no durable subscription - and {@code SchedulingConfig.registerGenerationJob}
 * found no gate, assembler or service and returned {@code null} - nothing scheduled. A
 * pod deployed with {@code yotresultsdistribution.generation.enabled=true} was inert, and the only trace of
 * it was two WARN lines.
 *
 * <p>Nothing below is a unit assertion restated. Each case is a bean this context either holds or
 * does not, which is the one property the whole of Phase 5's own suites cannot see and the one that
 * decides whether a register is ever generated.
 *
 * <p>The last case is constitution Principle V read as a context question: with generation enabled,
 * the stubs must not be what a deployment resolves. The four modes are LIVE here for that reason,
 * and {@link PropertiesValidator} refuses STUB outright wherever generation is enabled - so the
 * assertion is that what the context resolved is the real adapter and not merely that something
 * resolved.
 *
 * <p>The identity variables the workload-identity credential is built from are supplied by
 * {@link WorkloadIdentityStub}, because this context is a deployed pod's and a deployed pod is given
 * all three by the AKS webhook. Nothing here reads a flag, opens a connection to App Configuration,
 * or touches either database: both pools initialise lazily and the schedule's first fire is hours
 * away.
 */
@ExtendWith(WorkloadIdentityStub.class)
@SpringBootTest(properties = {
    GenerationWiringContextTest.GENERATION_ENABLED,
    GenerationWiringContextTest.SDG_MODE,
    GenerationWiringContextTest.NN_MODE,
    GenerationWiringContextTest.FILESERVICE_MODE,
    GenerationWiringContextTest.FLAG_MODE,
    GenerationWiringContextTest.FILESERVICE_URL,
    GenerationWiringContextTest.FLAG_ENDPOINT,
    GenerationWiringContextTest.FLAG_LABEL,
    GenerationWiringContextTest.SDG_ENDPOINT,
    GenerationWiringContextTest.NN_ENDPOINT,
    GenerationWiringContextTest.SYSTEM_USER_ID,
    GenerationWiringContextTest.TEMPLATE_ID,
    GenerationWiringContextTest.PAYLOAD_MODE,
    GenerationWiringContextTest.REFDATA_MODE,
    GenerationWiringContextTest.CONSUMER_DISABLED,
    GenerationWiringContextTest.BROKER_URL,
    GenerationWiringContextTest.EMBEDDED_BROKER,
    GenerationWiringContextTest.EMBEDDED_TOPIC})
@DisplayName("the downstream half on a generating pod")
class GenerationWiringContextTest {

    /**
     * The settings a generating pod is deployed with, named so that every nested
     * {@code @SpringBootTest} below differs from this context by exactly what its case is about.
     *
     * <p>A nested {@code @SpringBootTest} does not inherit the enclosing one's {@code properties},
     * so a pair would otherwise be two different deployments and whatever it asserted would be
     * attributable to whichever setting had been left out.
     */
    static final String GENERATION_ENABLED = "yotresultsdistribution.generation.enabled=true";

    /**
     * The retired safety net's binary name, spelled rather than imported.
     *
     * <p>A case that imported the type would be deleted with it, which is the one thing an
     * assertion about a deletion may not be.
     */
    private static final String RECONCILER =
            "uk.gov.hmcts.cp.yotresultsdistribution.batch.GenerationReconciler";

    /** Where the {@code batch} package's own sources are, for the lock sweep. */
    private static final Path BATCH_SOURCES = Path.of("src", "main", "java", "uk", "gov", "hmcts",
            "cp", "yotresultsdistribution", "batch");

    /** The same package, as a binary name prefix. */
    private static final String BATCH_PACKAGE = "uk.gov.hmcts.cp.yotresultsdistribution.batch.";

    /** The one sub-package the sweep leaves out: a command holds no scheduler and takes no lock. */
    private static final Path CLI_SOURCES = BATCH_SOURCES.resolve("cli");

    /** The extension a source of it carries, named so the sweep carries no literal. */
    private static final String JAVA = ".java";

    static final String SDG_MODE = "yotresultsdistribution.generation.sdg-mode=LIVE";

    static final String NN_MODE = "yotresultsdistribution.generation.nn-mode=LIVE";

    static final String FILESERVICE_MODE = "yotresultsdistribution.generation.fileservice-mode=LIVE";

    static final String FLAG_MODE = "yotresultsdistribution.generation.flag-mode=LIVE";

    static final String FILESERVICE_URL =
            "yotresultsdistribution.fileservice.url=jdbc:postgresql://fileservice.internal:5432/fileservice";

    static final String FLAG_ENDPOINT = "yotresultsdistribution.feature.endpoint=https://appconfig.internal";

    static final String FLAG_LABEL = "yotresultsdistribution.feature.label=ste86";

    static final String SDG_ENDPOINT =
            "yotresultsdistribution.endpoints.systemdocgenerator=http://systemdocgenerator.internal:8080";

    static final String NN_ENDPOINT =
            "yotresultsdistribution.endpoints.notificationnotify=http://notificationnotify.internal:8080";

    static final String SYSTEM_USER_ID =
            "yotresultsdistribution.endpoints.system-user-id=00000000-0000-4000-8000-000000000000";

    static final String TEMPLATE_ID =
            "yotresultsdistribution.email.templates.cr_standard=5c9a0e21-3d47-4f18-9b62-0a71c4e8d530";

    static final String PAYLOAD_MODE = "yotresultsdistribution.payload.mode=STUB";

    static final String REFDATA_MODE = "yotresultsdistribution.referencedata.mode=STUB";

    /** Intake is a different half and needs a broker this suite has no business standing up. */
    static final String CONSUMER_DISABLED = "yotresultsdistribution.consumer.enabled=false";

    static final String BROKER_URL = "spring.artemis.broker-url=tcp://localhost:61616";

    static final String EMBEDDED_BROKER = "spring.artemis.embedded.enabled=true";

    static final String EMBEDDED_TOPIC = "spring.artemis.embedded.queues=public.event";

    private final ApplicationContext context;

    @Autowired
    GenerationWiringContextTest(final ApplicationContext context) {
        this.context = context;
    }

    @Test
    @DisplayName("holds the listener the durable subscription delivers to")
    void the_context_should_hold_a_document_event_listener() {
        assertThat(context.getBeanProvider(DocumentEventListener.class).getIfAvailable())
                .as("the bean is contributed only where an outcome has somewhere to be applied, so "
                        + "a null here is a pod holding no subscription at all: every "
                        + "document-available systemdocgenerator publishes for this service is "
                        + "delivered to nobody, and every batch waits until the next run "
                        + "gives up on it")
                .isNotNull();
    }

    @Test
    @DisplayName("holds the sink an outcome is applied through")
    void the_context_should_hold_an_outcome_sink() {
        assertThat(context.getBeanProvider(DocumentOutcomeSink.class).getIfAvailable())
                .as("the one port an outcome is applied through, and it is what turns an "
                        + "announcement into batch state")
                .isNotNull();
    }

    @Test
    @DisplayName("holds the nightly run, with everything it asks in order")
    void the_context_should_hold_the_generation_job() {
        assertThat(context.getBeanProvider(RegisterGenerationJob.class).getIfAvailable())
                .as("a null here is a schedule nobody wired to anything: 18:00 comes and goes and "
                        + "the only trace is the WARN line the configuration wrote at startup")
                .isNotNull();
        assertThat(context.getBeanNamesForType(BatchAssembler.class))
                .as("the grouping the run hands its active registers to")
                .isNotEmpty();
        assertThat(context.getBeanNamesForType(RegisterGenerationService.class))
                .as("the requesting leg the run asks once per batch")
                .isNotEmpty();
        assertThat(context.getBeanNamesForType(PdfPayloadMapper.class))
                .as("progression's payload generator, which the requesting leg maps every batch "
                        + "through")
                .isNotEmpty();
    }

    /**
     * The retired safety net, asserted gone rather than asserted unused.
     *
     * <p>Two halves, because a bean nobody fires and a class nobody has is not the same claim and
     * only the second one keeps. The bean is what a context would fire; the class is what a merge
     * could bring back with a timer on it again, which is the shape the increment removed: a sweep
     * that reached a stale batch before the run's own pass did, and failed it under a reason that
     * does not give its registers back.
     *
     * <p>Stated over the bean <em>names</em> and a class <em>name</em> rather than over the type,
     * because a case that imported the type could not outlive it - and the assertion has to be one
     * that still compiles the day after the deletion, or it goes with it.
     */
    @Test
    @DisplayName("holds no reconciler, and neither does any other context, the class being gone")
    void no_context_holds_a_generation_reconciler() {
        assertThat(context.getBeanDefinitionNames())
                .as("a bean of it is a class something can call, and the run stopped calling it at "
                        + "T016: what would be left is an object a later wiring change could put a "
                        + "schedule back on")
                .noneSatisfy(name -> assertThat(name).containsIgnoringCase("reconciler"));
        assertThatThrownBy(() -> Class.forName(RECONCILER))
                .as("and the class itself, because a type on the classpath is a type a merge can "
                        + "wire up again with nothing to catch it (FR-007)")
                .isInstanceOf(ClassNotFoundException.class);
    }

    /**
     * One ShedLock name in the whole of {@code batch}, beside the morning report's.
     *
     * <p>A sweep over the package's sources rather than a list of the classes somebody remembered:
     * the claim is about every locked method the generation half can carry, and a case naming two
     * classes would say nothing about a third arriving beside them. FR-007 leaves the generation
     * half exactly one schedule, so it leaves it exactly one lock, and the report's is the only
     * other lock this service takes.
     *
     * <p>{@code IntakeAgeSweep} deliberately holds none, which is why the expected set is two and
     * not three: a gauge describes the JVM that publishes it, so every replica takes its own
     * readings and an alert aggregates them.
     */
    @Test
    @DisplayName("the generation half carries exactly one scheduler lock")
    void the_generation_half_carries_exactly_one_scheduler_lock() throws IOException {
        assertThat(schedulerLockNames())
                .as("the run's own, and the morning report's; a third is a second thing holding a "
                        + "lock over batches the run is the only decider of")
                .containsExactlyInAnyOrder(RegisterGenerationJob.LOCK_NAME,
                        ExceptionReportJob.LOCK_NAME);
    }

    /**
     * Every {@code @SchedulerLock} name declared anywhere in the {@code batch} package.
     *
     * <p>The classes are found from the sources rather than from a scan of the classpath, which is
     * what makes this a claim about the package as it is written: a class added to it is in the
     * sweep the moment it is saved, and a class deleted from it leaves nothing behind for the sweep
     * to keep asserting about.
     *
     * <p><strong>It walks, and it looks inside.</strong> The sources are read recursively and every
     * nested type is collected with its enclosing one, because a lock is a lock wherever it is
     * written: a second schedule declared on a nested class, or in a sub-package somebody adds, is
     * exactly the arrangement FR-007 says this half may not have, and a sweep that read only the
     * top-level type of each file in one directory would let it through.
     *
     * @return the lock names, in no particular order
     * @throws IOException if the package's sources cannot be read
     */
    private static List<String> schedulerLockNames() throws IOException {
        try (Stream<Path> sources = Files.walk(BATCH_SOURCES)) {
            return sources.filter(source -> !source.startsWith(CLI_SOURCES))
                    .filter(source -> source.getFileName().toString().endsWith(JAVA))
                    .map(GenerationWiringContextTest::loaded)
                    .flatMap(GenerationWiringContextTest::withNested)
                    .flatMap(declaring -> Stream.of(declaring.getDeclaredMethods()))
                    .map(method -> method.getAnnotation(SchedulerLock.class))
                    .filter(lock -> lock != null)
                    .map(SchedulerLock::name)
                    .toList();
        }
    }

    /**
     * One class of the {@code batch} package, from the file that declares it.
     *
     * @param source the source file, which this repository names after its one public type
     * @return the loaded class
     */
    private static Class<?> loaded(final Path source) {
        final String simple = source.getFileName().toString();
        final String relative = BATCH_SOURCES.relativize(source.getParent()).toString();
        final String subPackage = relative.isEmpty() ? ""
                : relative.replace(source.getFileSystem().getSeparator(), ".") + ".";
        final String binary = BATCH_PACKAGE + subPackage
                + simple.substring(0, simple.length() - JAVA.length());
        try {
            return Class.forName(binary);
        } catch (ClassNotFoundException notCompiled) {
            throw new AssertionError(binary
                    + " is a source in the batch package that no class answers to", notCompiled);
        }
    }

    /**
     * A class and every type declared inside it, however deeply.
     *
     * @param declaring the class read off a source file
     * @return that class and its nested types
     */
    private static Stream<Class<?>> withNested(final Class<?> declaring) {
        return Stream.concat(Stream.of(declaring),
                Stream.of(declaring.getDeclaredClasses())
                        .flatMap(GenerationWiringContextTest::withNested));
    }


    /**
     * The run's first act has to be a bean for the run to be given one.
     *
     * <p>Nothing else on the context constructs it: the job is contributed by
     * {@link SchedulingConfig} over the collaborators it asks in order, and a pass that no
     * configuration declared would leave the nightly run with nothing to call - every batch whose
     * outcome went missing sitting in flight, and its court centre day passed over at every
     * subsequent run, which is the failure this increment exists to end.
     */
    @Test
    @DisplayName("holds the pass the run gives back stale batches through")
    void a_generation_enabled_context_holds_a_stale_batch_releaser() {
        assertThat(context.getBeanNamesForType(StaleBatchReleaser.class))
                .as("a null here is a nightly run whose first act does nothing: a batch nobody "
                        + "heard an outcome about stays in flight, and its court centre gets no "
                        + "document night after night until a person notices")
                .isNotEmpty();
    }

    /**
     * <strong>[A]</strong> The pass takes what it measures by and not the record it came from.
     *
     * <p>Green on introduction - it states the shape Phase 3 landed - and asserted here because
     * the wiring is where that shape is easiest to lose: a constructor handed the whole
     * {@code GenerationProperties} would let the pass read any setting the deployment carries,
     * including the two that are deployment shape rather than cutover levers, and the class would
     * stop being testable on two durations.
     */
    @Test
    @DisplayName("[A] the pass takes the two durations and not the whole record")
    void the_releaser_takes_the_two_durations_and_not_the_whole_record() {
        assertThat(StaleBatchReleaser.class.getDeclaredConstructors()[0].getParameterTypes())
                .as("the store it releases through, where the numbers are counted, the two cutoffs "
                        + "and the clock they are measured back from - and no settings record")
                .containsExactly(RegisterStore.class, GenerationMetrics.class, Duration.class,
                        Duration.class, Clock.class);
    }

    /**
     * <strong>[A]</strong> And the half-wired context the completeness check is written for.
     *
     * <p>Characterisation: the branch exists and behaves this way already. It is asserted because
     * nothing asserted it — every case above holds a context that is complete, so the WARN line and
     * the {@code null} it accompanies were reachable from no test at all, and the check's own list
     * of collaborators is exactly the sort of thing a later wiring change edits without noticing.
     *
     * <p>Driven by calling the {@code @Bean} method rather than by standing up a context missing a
     * bean: the pass is declared on this same configuration, so a context that holds the
     * configuration holds the pass, and there is no set of properties that produces the incomplete
     * case. The method is the unit; the providers are what a context hands it.
     */
    @Nested
    @DisplayName("a context the downstream half is only half on")
    class AnIncompleteContext {

        @Test
        @DisplayName("[A] schedules no run, and the line names the collaborator that was missing")
        void a_context_without_the_pass_should_schedule_no_run_and_name_it() {
            try (CapturedLog log = CapturedLog.capturing(SchedulingConfig.class)) {
                final RegisterGenerationJob job = new SchedulingConfig().registerGenerationJob(
                        holding(FeatureFlagGate.class, mock(FeatureFlagGate.class)),
                        holding(RegisterStore.class, mock(RegisterStore.class)),
                        holding(BatchAssembler.class, mock(BatchAssembler.class)),
                        holding(RegisterGenerationService.class,
                                mock(RegisterGenerationService.class)),
                        holdingNothing(StaleBatchReleaser.class),
                        // Read only on the branch that constructs the run, which this is not.
                        null, null, null, null);

                assertThat(job)
                        .as("a run whose first act would be a call to nothing is not a run: it "
                                + "would report a quiet night every night while every batch whose "
                                + "outcome went missing stayed in flight")
                        .isNull();
                assertThat(log.messages())
                        .as("and the one trace a deployment gets has to say which collaborator was "
                                + "missing, or an inert pod is indistinguishable from a quiet one")
                        .anyMatch(line -> line.contains("releaser=false"))
                        .allSatisfy(line -> assertThat(line)
                                .as("gate, store, assembler and service were all present, so the "
                                        + "line must not accuse them")
                                .doesNotContain("gate=false", "store=false", "assembler=false",
                                        "service=false"));
            }
        }

        @Test
        @DisplayName("[A] the check no longer names the reconciler")
        void the_completeness_check_should_not_name_the_reconciler() {
            try (CapturedLog log = CapturedLog.capturing(SchedulingConfig.class)) {
                new SchedulingConfig().registerGenerationJob(
                        holdingNothing(FeatureFlagGate.class),
                        holdingNothing(RegisterStore.class),
                        holdingNothing(BatchAssembler.class),
                        holdingNothing(RegisterGenerationService.class),
                        holdingNothing(StaleBatchReleaser.class),
                        null, null, null, null);

                assertThat(log.messages())
                        .as("the run stopped asking for a reconciler at T016, and a line still "
                                + "naming one would describe a collaborator the job does not have")
                        .isNotEmpty()
                        .allSatisfy(line -> assertThat(line).doesNotContain("reconciler"));
            }
        }

        /**
         * A provider answering with one bean, as a context holding it would.
         *
         * @param <T>  the collaborator's type
         * @param type the type the configuration asks for
         * @param bean the bean it is given
         * @return a provider over a factory holding exactly that bean
         */
        private <T> ObjectProvider<T> holding(final Class<T> type, final T bean) {
            final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
            factory.registerSingleton(type.getName(), bean);
            return factory.getBeanProvider(type);
        }

        /**
         * A provider answering with nothing, as a context missing the bean does.
         *
         * @param <T>  the collaborator's type
         * @param type the type the configuration asks for
         * @return a provider over an empty factory
         */
        private <T> ObjectProvider<T> holdingNothing(final Class<T> type) {
            return new DefaultListableBeanFactory().getBeanProvider(type);
        }
    }

    @Test
    @DisplayName("resolves the live renderer and the live payload store, not the stand-ins")
    void the_downstream_ports_should_resolve_to_the_live_adapters() {
        assertThat(context.getBean(DocumentRenderer.class))
                .as("systemdocgenerator, over the endpoint the deployment configured; the stub "
                        + "accepts every request and invents no document, so a pod resolving it "
                        + "would report a successful run every night and render nothing")
                .isInstanceOf(SystemDocGeneratorClient.class)
                .isNotInstanceOf(StubDocumentRenderer.class);
        assertThat(context.getBean(PayloadFileStore.class))
                .as("the framework file service, over the second datasource; the stub logs and "
                        + "returns, so systemdocgenerator would be asked to render a payload that "
                        + "was never stored")
                .isInstanceOf(FileServicePayloadStore.class)
                .isNotInstanceOf(StubPayloadFileStore.class);
    }

    @Test
    @DisplayName("resolves no stand-in at all where generation is enabled")
    void no_generation_stub_should_be_reachable_on_a_generating_pod() {
        assertThat(context.getBeanNamesForType(StubDocumentRenderer.class))
                .as("constitution Principle V: a stub reachable in a production profile is the pod "
                        + "that skips every night while its metrics say the run succeeded")
                .isEmpty();
        assertThat(context.getBeanNamesForType(StubPayloadFileStore.class))
                .as("and the same for the payload store")
                .isEmpty();
    }

    /**
     * Which identity {@link LiveFeatureFlagConfig} authorises the flag read with, and what each one
     * can therefore read.
     *
     * <p>{@code yotresultsdistribution.feature.credential} chooses between the two, and the choice is
     * invisible in the bean: both modes contribute the same {@code AppConfigurationFlagReader} over
     * the same {@link FeatureFlagProperties}, and only the credential inside it differs. What
     * distinguishes them is what each can read, so that is what is asserted - one endpoint, one
     * stub, two modes, two outcomes.
     *
     * <p><strong>A bearer token is only ever sent over TLS.</strong> Azure's own
     * {@code BearerTokenAuthenticationPolicy} refuses a request whose URL is not {@code https},
     * before any socket is opened, which is exactly why {@code local-test} exists: a WireMock stand
     * -in for App Configuration speaks plain HTTP, so a pod's workload identity cannot read one at
     * all and the local loop had nothing but the STUB reader to fall back on. {@code local-test}
     * authorises with a fixed, published HMAC identity instead - the same shape
     * {@code GenerationStackConfiguration} and {@code AppConfigurationFlagReaderTest} already read
     * through - so the real reader, the real SDK client and the real fail-closed parsing are all
     * exercised against the compose stub.
     *
     * <p>The environment is a {@link MockEnvironment} rather than the process's own, so which of the
     * three projected variables a case holds is the case's own statement and not
     * {@link WorkloadIdentityStub}'s.
     */
    @Nested
    @DisplayName("the credential the flag read is authorised with")
    class FlagCredential {

        /** The pod's own client id, projected by the AKS workload-identity webhook. */
        private static final String CLIENT_ID = "AZURE_CLIENT_ID";

        /** The directory that identity lives in, projected by the same webhook. */
        private static final String TENANT_ID = "AZURE_TENANT_ID";

        /** Where the projected federated token is mounted. */
        private static final String TOKEN_FILE = "AZURE_FEDERATED_TOKEN_FILE";

        /** The key the flag is read under, as every reader of this one lever spells it. */
        private static final String FLAG_KEY = ".appconfig.featureflag/YotResultsDistributionService";

        /** The label the compose stub answers under. */
        private static final String FLAG_LABEL = "LOCAL";

        /** Any {@code kv} read, whatever key and label the reader asks under. */
        private static final String ANY_KEY_PATH = "/kv/.*";

        /** The media type App Configuration answers a key-value read with. */
        private static final String KV_MEDIA_TYPE =
                "application/vnd.microsoft.appconfig.kv+json";

        /** App Configuration's feature-flag JSON, as the vendored value schema declares it. */
        private static final String FLAG_ON = "{\\\"id\\\":\\\"YotResultsDistributionService\\\","
                + "\\\"enabled\\\":true,\\\"conditions\\\":{\\\"client_filters\\\":[]}}";

        /** The store's answer for the flag, switched on. */
        private static final String SETTING_ON = "{\"key\":\"" + FLAG_KEY + "\",\"label\":\""
                + FLAG_LABEL + "\",\"content_type\":"
                + "\"application/vnd.microsoft.appconfig.ff+json\",\"value\":\"" + FLAG_ON
                + "\",\"tags\":{},\"locked\":false,\"etag\":\"cr-local\"}";

        private WireMockServer store;

        @BeforeEach
        void storeAnsweringTheFlagSwitchedOn() {
            store = new WireMockServer(wireMockConfig().dynamicPort());
            store.start();
            store.stubFor(get(urlPathMatching(ANY_KEY_PATH)).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", KV_MEDIA_TYPE)
                    .withBody(SETTING_ON)));
        }

        @AfterEach
        void stopTheStore() {
            store.stop();
        }

        @Test
        @DisplayName("local-test reads a plain-HTTP App Configuration stub, and asks for no pod")
        void the_local_test_credential_should_read_the_compose_stub() {
            final FeatureFlagReader reader = new LiveFeatureFlagConfig().featureFlagReader(
                    flagAt(store.baseUrl(), FeatureFlagProperties.Credential.LOCAL_TEST),
                    new MockEnvironment());

            assertThat(reader.read())
                    .as("the whole point of the mode: the real reader, over the real SDK client, "
                            + "against the stub the compose loop and the container smoke run "
                            + "against - and on a laptop, which holds none of the three variables "
                            + "the webhook projects")
                    .isEqualTo(FlagDecision.ON);
            assertThat(store.findAll(getRequestedFor(urlPathMatching(ANY_KEY_PATH))))
                    .as("and the store was actually asked, so the reading is a read and not a "
                            + "default")
                    .isNotEmpty();
        }

        /**
         * <strong>[A]</strong> A characterisation of behaviour that already exists: the deployed
         * credential is unchanged by the new setting, and Azure's refusal to send a bearer token
         * over plain HTTP is the whole reason a second mode was needed. Green on introduction.
         */
        @Test
        @DisplayName("workload-identity cannot read one at all, which is why the mode exists")
        void the_workload_identity_credential_should_not_reach_a_plain_http_store() {
            final FeatureFlagReader reader = new LiveFeatureFlagConfig().featureFlagReader(
                    flagAt(store.baseUrl(), FeatureFlagProperties.Credential.WORKLOAD_IDENTITY),
                    podHoldingEveryProjectedVariable());

            assertThat(reader.read())
                    .as("a bearer credential is refused on a URL that is not https, so the same "
                            + "endpoint the mode above reads is unreadable on this one - and the "
                            + "run would skip, fail-closed, exactly as it does on a store outage")
                    .isInstanceOf(FlagDecision.Unreadable.class);
            assertThat(store.findAll(getRequestedFor(urlPathMatching(ANY_KEY_PATH))))
                    .as("refused before the socket, not by the store: nothing was asked")
                    .isEmpty();
        }

        /**
         * <strong>[A]</strong> A characterisation of behaviour that already exists, pinned here
         * because a new mode beside it is exactly how a refusal gets softened by accident: the
         * deployed credential still refuses a pod holding two of the three. Green on introduction.
         */
        @Test
        @DisplayName("workload-identity still refuses to start on a pod missing a projected "
                + "variable")
        void the_workload_identity_credential_should_still_refuse_an_incomplete_pod() {
            final MockEnvironment incomplete = podHoldingEveryProjectedVariable();
            incomplete.setProperty(TOKEN_FILE, "");

            assertThatThrownBy(() -> new LiveFeatureFlagConfig().featureFlagReader(
                    flagAt(store.baseUrl(), FeatureFlagProperties.Credential.WORKLOAD_IDENTITY),
                    incomplete))
                    .as("unchanged by the new mode: a deployed pod that had lost one of the three "
                            + "would skip every night on an unreadable flag and look like a store "
                            + "outage, so it does not start")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(TOKEN_FILE);
        }

        /** The flag settings, pointed at this case's store and asked for under one credential. */
        private FeatureFlagProperties flagAt(
                final String endpoint, final FeatureFlagProperties.Credential credential) {
            return new FeatureFlagProperties(
                    endpoint, FLAG_KEY, FLAG_LABEL, Duration.ofSeconds(5), credential);
        }

        /** An environment holding exactly what the AKS webhook projects, and nothing else. */
        private MockEnvironment podHoldingEveryProjectedVariable() {
            final MockEnvironment pod = new MockEnvironment();
            pod.setProperty(CLIENT_ID, "8f2c1d47-0b93-4e5a-9c31-6d0a7b4e2f18");
            pod.setProperty(TENANT_ID, "531ff96d-0ae9-462a-8d2d-bec7c0b42082");
            pod.setProperty(TOKEN_FILE, "/var/run/secrets/azure/tokens/azure-identity-token");
            return pod;
        }
    }
}
