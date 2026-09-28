package uk.gov.hmcts.cp.yotresultsdistribution.adapter.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.node.JsonNodeFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DocumentRenderer;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadFileStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.PayloadMetadata;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifier;
import uk.gov.hmcts.cp.yotresultsdistribution.config.FileServiceConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.config.StubGenerationConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.config.YotResultsDistributionProperties;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RenderRequest;

/**
 * [A] The four downstream stand-ins, characterised.
 *
 * <p><strong>A characterisation, and it says so.</strong> T018 landed these four classes and
 * {@code StubGenerationConfig} with no test in front of them, which is the Phase 2 TDD gap this
 * suite closes. It closes it by pinning what they already do rather than by driving a change: each
 * of the four makes a deliberate choice about what a stand-in may and may not claim, argued in its
 * own javadoc against constitution Principle V, and a test written afterwards cannot pretend to
 * have specified those choices. What it can do - and what nothing else does - is make them
 * breakable: the disposition of the flag stub, the two halves of the renderer stub's pairing, and
 * the difference between the stub that accepts and the stub that refuses are each one edit away
 * from a pod that quietly tells nobody, or invents a document, while its metrics say the run
 * succeeded.
 *
 * <p>The one behaviour that is a stated requirement rather than an inherited choice is T018's own
 * "flag ON by default, overridable", and it is asserted through {@code StubGenerationConfig},
 * because the default lives on the properties record and the mapping onto {@link FlagDecision}
 * lives in the configuration - not in the reader, which answers whatever it was constructed with.
 */
@DisplayName("the generation stubs")
class StubGenerationAdaptersTest {

    private static final UUID BATCH_ID = UUID.fromString("6b0d5a1f-4c8e-4a92-8f31-2d7c6e05b114");
    private static final UUID PAYLOAD_FILE_ID =
            UUID.fromString("5e08b6d1-92a7-4c33-8f10-6b4d3e79a281");
    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("3a7f1c92-6d84-4b05-9e73-1c2b8a4e07d5");
    private static final UUID NOTIFICATION_ID =
            UUID.fromString("0c6a4f18-b573-4d29-8e04-95f2a7c31b6e");
    private static final UUID TEMPLATE_ID =
            UUID.fromString("5c9a0e21-3d47-4f18-9b62-0a71c4e8d530");

    private static final String FLAG_MODE_STUB = "yotresultsdistribution.generation.flag-mode=STUB";
    private static final String SDG_MODE_STUB = "yotresultsdistribution.generation.sdg-mode=STUB";
    private static final String NN_MODE_STUB = "yotresultsdistribution.generation.nn-mode=STUB";
    private static final String FILESERVICE_MODE_STUB =
            "yotresultsdistribution.generation.fileservice-mode=STUB";

    /** The generation half's own switch, which is one of the two the file service is behind. */
    private static final String GENERATION_ENABLED = "yotresultsdistribution.generation.enabled=true";

    /** And the morning report's e-mail output, which is the other. */
    private static final String EMAIL_ENABLED = "yotresultsdistribution.report.email.enabled=true";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubGenerationTestConfiguration.class);

    /** The file service's own configuration, which the stand-in moved to at review gate 7. */
    private final ApplicationContextRunner fileService = new ApplicationContextRunner()
            .withUserConfiguration(FileServiceTestConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(YotResultsDistributionProperties.class)
    @Import(StubGenerationConfig.class)
    static class StubGenerationTestConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(YotResultsDistributionProperties.class)
    @Import(FileServiceConfig.class)
    static class FileServiceTestConfiguration {
    }

    /**
     * The stand-in whose answer decides whether a night runs at all.
     */
    @Nested
    @DisplayName("the feature-flag reader")
    class FlagReader {

        @Test
        void the_stubbed_reader_should_answer_on_where_nothing_configures_it() {
            runner.withPropertyValues(FLAG_MODE_STUB).run(context -> assertThat(
                    context.getBean(FeatureFlagReader.class).read())
                    .as("the opposite of the live reader's fail-closed disposition, and safe only "
                            + "because startup refuses this mode wherever the deployed credential "
                            + "source is in use: a stub answering OFF would skip every local run "
                            + "before it began and leave the job untestable")
                    .isEqualTo(FlagDecision.ON));
        }

        @Test
        void the_stubbed_reader_should_answer_off_where_a_test_wants_the_skip_path() {
            runner.withPropertyValues(FLAG_MODE_STUB, "yotresultsdistribution.stub.flag-answer=OFF")
                    .run(context -> assertThat(context.getBean(FeatureFlagReader.class).read())
                            .as("every answer the live reader can give is reachable through the "
                                    + "setting, so the skip paths are exercised deliberately "
                                    + "rather than by taking a store away")
                            .isEqualTo(FlagDecision.OFF));
        }

        @Test
        void the_stubbed_reader_should_answer_unreadable_as_not_configured_and_no_other_cause() {
            runner.withPropertyValues(FLAG_MODE_STUB, "yotresultsdistribution.stub.flag-answer=UNREADABLE")
                    .run(context -> assertThat(context.getBean(FeatureFlagReader.class).read())
                            .as("NOT_CONFIGURED is the only one of the six causes true of a pod "
                                    + "with no App Configuration endpoint; a claimed timeout would "
                                    + "put a cause into the skipped counter that no store gave")
                            .isEqualTo(new FlagDecision.Unreadable(
                                    FlagDecision.UnreadableReason.NOT_CONFIGURED)));
        }

        @Test
        void the_reader_should_answer_the_same_thing_on_every_read() {
            final FeatureFlagReader reader = new StubFeatureFlagReader(FlagDecision.OFF);

            assertThat(reader.read())
                    .as("configuration read once at construction, never a field in a message and "
                            + "never an endpoint: nothing outside the deployment decides whether a "
                            + "night's registers are generated")
                    .isEqualTo(reader.read())
                    .isEqualTo(FlagDecision.OFF);
        }
    }

    /**
     * The stand-in that accepts a render request and never invents a document.
     */
    @Nested
    @DisplayName("the document renderer")
    class Renderer {

        @Test
        void the_stubbed_renderer_should_accept_a_request_so_a_batch_can_reach_generating() {
            final DocumentRenderer renderer = new StubDocumentRenderer();

            assertThatCode(() -> renderer.requestRender(
                    new RenderRequest(PAYLOAD_FILE_ID, BATCH_ID, "OEE_Layout5", "pdf",
                            "yotresultsdistribution"),
                    CallerIdentity.SYSTEM))
                    .as("GENERATING says a render was asked for, not that a document exists, so "
                            + "accepting claims nothing - and it is the only way the assembler, "
                            + "the payload leg and the run deadline are reachable at all")
                    .doesNotThrowAnyException();
        }

        @Test
        void the_stubbed_renderer_should_declare_no_way_of_inventing_a_document() {
            assertThat(Arrays.stream(StubDocumentRenderer.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .toList())
                    .as("a minted document id would be a GENERATED batch carrying a PDF nobody "
                            + "rendered - the silent success this service exists to end (C1, C33) "
                            + "- and a stubbed file-service leg would attach it to a real e-mail; "
                            + "the stand-in has no second call to invent one in")
                    .extracting(Method::getName)
                    .containsExactly("requestRender");
        }
    }

    /**
     * The stand-in that sends nothing, and refuses rather than pretending it did.
     */
    @Nested
    @DisplayName("the register notifier")
    class Notifier {

        @Test
        void the_stubbed_notifier_should_refuse_rather_than_record_a_recipient_as_told() {
            final RegisterNotifier notifier = new StubRegisterNotifier();
            final RegisterNotification notification = new RegisterNotification(NOTIFICATION_ID,
                    BATCH_ID, "yot@example.gov.uk", "Wandsworth Youth Offending Team",
                    "cr_standard", TEMPLATE_ID, NotificationStatus.PENDING, null, null, 0);

            assertThatThrownBy(() ->
                    notifier.send(notification, DOCUMENT_FILE_ID, CallerIdentity.SYSTEM))
                    .as("a stub is allowed to do nothing; it is not allowed to say it did "
                            + "something, and ACCEPTED here would record a Youth Offending Team as "
                            + "told about a register nobody sent it")
                    .isInstanceOf(NotificationFailedException.class)
                    .extracting(thrown -> ((NotificationFailedException) thrown).classification())
                    .as("non-transient, because no resend against a stub will ever answer "
                            + "differently, and the batch ends NOTIFIED_NOBODY on the tally rather "
                            + "than retrying for ever")
                    .isEqualTo(FailureClassification.NON_TRANSIENT);
        }
    }

    /**
     * The stand-in that writes nothing, and accepts rather than refusing.
     */
    @Nested
    @DisplayName("the payload file store")
    class PayloadStore {

        @Test
        void the_stubbed_store_should_accept_so_the_batch_reaches_the_step_after_it() {
            final PayloadFileStore store = new StubPayloadFileStore();

            assertThatCode(() -> store.store(PAYLOAD_FILE_ID, JsonNodeFactory.instance.objectNode(),
                    new PayloadMetadata("court-register_2026-08-24_B01LY00.pdf", "pdf",
                            "OEE_Layout5", 1, 2048)))
                    .as("the opposite of the notifier's choice, and deliberately: a refusal here "
                            + "fails the batch PAYLOAD_STORE_UNAVAILABLE before anything "
                            + "downstream is asked, so the state machine these suites are about "
                            + "would never leave its first state. Accepting claims nothing to "
                            + "anybody - no e-mail is sent and no document is invented")
                    .doesNotThrowAnyException();
        }
    }

    /**
     * What every one of the four is selected by, which is the property and never a profile alone.
     *
     * <p>Three of them are selected by a mode key and nothing else. The file service's stand-in is
     * selected by the same key and from a different
     * configuration. It moved to {@link FileServiceConfig} with the live one when review gate 7
     * found that the morning report writes a file too, so the mode alone is no longer enough to
     * contribute it: a pod that writes no file at all builds neither adapter, whatever the mode
     * says. The cases below are three ports and then that one, rather than four together, because
     * they are now answered by two conditions and a case that ran them as one would say which
     * without being able to show it.
     */
    @Nested
    @DisplayName("selection")
    class Selection {

        @Test
        void a_deployment_that_says_nothing_should_get_no_stub_at_all() {
            runner.run(context -> assertThat(context)
                    .as("a bean is contributed only where the mode says STUB, so an environment "
                            + "that says nothing gets the real adapter rather than a stand-in it "
                            + "never asked for")
                    .doesNotHaveBean(FeatureFlagReader.class)
                    .doesNotHaveBean(DocumentRenderer.class)
                    .doesNotHaveBean(RegisterNotifier.class));
        }

        @Test
        void each_of_the_three_should_carry_its_own_condition() {
            runner.withPropertyValues(SDG_MODE_STUB).run(context -> assertThat(context)
                    .as("each downstream has its own mode key, so a suite can stub "
                            + "systemdocgenerator and keep the notifier live")
                    .hasSingleBean(DocumentRenderer.class)
                    .doesNotHaveBean(FeatureFlagReader.class)
                    .doesNotHaveBean(RegisterNotifier.class));
        }

        @Test
        void all_three_modes_together_should_contribute_all_three_stubs() {
            runner.withPropertyValues(FLAG_MODE_STUB, SDG_MODE_STUB, NN_MODE_STUB)
                    .run(context -> assertThat(context)
                            .hasSingleBean(FeatureFlagReader.class)
                            .hasSingleBean(DocumentRenderer.class)
                            .hasSingleBean(RegisterNotifier.class));
        }

        @Test
        void the_file_services_stand_in_is_the_same_mode_key_behind_whichever_half_writes_a_file() {
            fileService.withPropertyValues(FILESERVICE_MODE_STUB, GENERATION_ENABLED)
                    .run(context -> assertThat(context)
                            .as("the key did not move with the bean: one file service, so renaming "
                                    + "the setting would be a change to every chart to say the "
                                    + "same thing")
                            .hasSingleBean(PayloadFileStore.class)
                            .getBean(PayloadFileStore.class)
                            .isInstanceOf(StubPayloadFileStore.class));

            fileService.withPropertyValues(FILESERVICE_MODE_STUB, EMAIL_ENABLED)
                    .run(context -> assertThat(context)
                            .as("and the morning report's local run gets the same no-op, which is "
                                    + "the whole of why the bean is behind an either-half condition")
                            .hasSingleBean(PayloadFileStore.class));

            fileService.withPropertyValues(FILESERVICE_MODE_STUB)
                    .run(context -> assertThat(context)
                            .as("a pod that writes no file builds neither adapter, whatever the "
                                    + "mode says: a stand-in nothing would call is a bean an "
                                    + "operator has to be told to ignore")
                            .doesNotHaveBean(PayloadFileStore.class));
        }
    }
}
