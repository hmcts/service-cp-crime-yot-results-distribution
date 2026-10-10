package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpErrorContext;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.fileservice.FileServicePayloadStore;
import uk.gov.hmcts.cp.yotresultsdistribution.application.DistributionPipeline;
import uk.gov.hmcts.cp.yotresultsdistribution.application.HearingPayloadSource;
import uk.gov.hmcts.cp.yotresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.yotresultsdistribution.application.NowSubscriptionsSource;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterSubmission;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterSubmissionClient;
import uk.gov.hmcts.cp.yotresultsdistribution.application.SubmissionReceipt;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.BatchAgeSweep;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.StaleBatchReleaser;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ContractViolation;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GuardDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportDeliveryReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.SweepFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.yotresultsdistribution.inbound.DistributionCommandParser;
import uk.gov.hmcts.cp.yotresultsdistribution.inbound.ServiceBusConsumerConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.inbound.YotResultsDistributionMessageListener;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.GenerationLegs;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LogStatement;
import uk.gov.hmcts.cp.yotresultsdistribution.support.NowSubscriptionFixtures;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PersonalDataMarkers;
import uk.gov.hmcts.cp.yotresultsdistribution.support.QueueHealthTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.StoreGateTestSupport;

/**
 * What this service is allowed to write down (constitution Principle VII).
 *
 * <p>Every defendant on a court register is a child. That is not a nuance of this suite, it is its
 * whole reason to exist: the register carries names, dates of birth, addresses, ethnicity, contact
 * details and free-text facts about children, and this pod's log lines are shipped to an index the
 * whole estate can read. The rules are not the same at every level, and the difference matters:
 *
 * <ul>
 *   <li><strong>Correlation is required at INFO and above.</strong> Every line about processing
 *       carries {@code source}, {@code requestId}, {@code hearingId} and {@code hearingDay}, because
 *       a line that cannot be tied to a request is a line nobody can act on.</li>
 *   <li><strong>Personal data is forbidden at INFO and above</strong> — and this suite asserts the
 *       stronger claim, that it appears at <em>no</em> level, because the level a deployed
 *       environment runs at is not this repository's decision to rely on.</li>
 *   <li><strong>Reasons are bounded codes.</strong> {@code completion_reason} and
 *       {@code failure_reason} come from the four enumerations this service owns and never from an
 *       exception's text or a fragment of a message body — free text is how a name reaches a log
 *       index without anybody writing a line that logs a name.</li>
 *   <li><strong>Secrets are forbidden at every level</strong>, and a connection string is a secret
 *       whether or not the log line calls it one.</li>
 * </ul>
 *
 * <p><strong>The register is really assembled here.</strong> The delivery path runs over the bean
 * graph {@link PipelineConfig} builds, with doubles for the four outward ports only — so the fragment
 * builder, the subscription matcher, the twelve mappers and the contract validator all get to write
 * whatever they write, about a child whose every personal field is a marker. A suite that mocked the
 * transformation would prove nothing about the twelve classes that actually hold the child's details.
 *
 * <p><strong>What the doubles cost, and where it is paid.</strong> Four doubled ports are four
 * classes that never write a line, and they are precisely the classes with a child's data in their
 * hands and a far end's text in their exception messages — the cache that holds the payload, the
 * query client that parses it, the reference-data client that is handed the recipients, and the
 * gateway that is handed progression's answer. Nothing below can fail if one of them starts quoting
 * what it read. {@code config/TelemetryPrivacyIT} is the other half: the same markers, the same
 * root-appender capture, and the live adapters against a real Redis container and real HTTP
 * contexts, over a success leg and four failure legs. The markers themselves live in
 * {@link uk.gov.hmcts.cp.yotresultsdistribution.support.PersonalDataMarkers} so the two suites cannot come to
 * sweep for different values.
 *
 * <p><strong>The delivery path is not the only way text this service did not write gets in.</strong>
 * The operations API is the other one, and the text it is handed is an operator's own typing rather
 * than a producer's message: a court house dictated over the phone, a batch identity copied out of
 * a support ticket. {@code TheOperationsSurface} below holds the seven endpoints to the rule over
 * their records and their source - a refusal names the argument and never the value, and nothing
 * the caller supplied reaches a body or a line. It replaced a group that ran the six commands and
 * swept their terminals, which increment 005 deleted with the commands themselves.
 *
 * <p>Every assertion is made against a capture of <em>everything</em>, at TRACE, including the
 * rendered text of any exception attached to a line. A stack trace reaches a log index exactly as a
 * message does, and an exception somebody else wrote is the commonest way a payload fragment or a
 * credential escapes.
 *
 * <p>The markers are deliberately implausible strings. A test looking for the word "name" would fail
 * on a field called {@code loggerName}; a test looking for a value nothing else could produce fails
 * only when that value really was written.
 */
@DisplayName("what the YOT results distribution service may write down")
class TelemetryPrivacyTest {

    /** The correlation set every processing line must carry. */
    private static final Set<String> CORRELATION =
            Set.of("source", "requestId", "hearingId", "hearingDay");

    private static final String MESSAGE_ID_MARKER = "MESSAGEIDMARKERZQX7";
    private static final String FIELD_NAME_MARKER = "FIELDNAMEMARKERZQX7";
    private static final String TRANSPORT_MARKER = "TRANSPORTMARKERZQX7";
    private static final String SETTLEMENT_MARKER = "SETTLEMENTMARKERZQX7";
    private static final String ADAPTER_MARKER = "ADAPTERMARKERZQX7";
    private static final String BODY_MARKER = "BODYMARKERZQX7";
    private static final String SECRET_MARKER = "SECRETMARKERZQX7";

    /**
     * The sharing user, as a canonical uuid the eye can pick out of a log index. It has to be a real
     * one — a marker word would be rejected by the parser and the run would never start, which would
     * prove nothing about what a run that <em>does</em> carry a user writes down.
     */
    private static final String CALLER_MARKER = "0dd0dd0d-dead-beef-cafe-facade000001";

    /** The hearing the base fixtures are built around, and the court house its centre carries. */
    private static final String HEARING_ID = "1828f356-f746-4f2d-932b-79ef2df95c80";
    private static final String OU_CODE = "B01LY00";

    /**
     * The count the statement scan has to beat before its emptiness means anything.
     *
     * <p>Not the exact number, which would make every new statement a two-line change to a test
     * that is not about counting; a floor well under the real total, which is what a scan that had
     * quietly stopped matching anything would fall below.
     */
    private static final int EVERY_LINE_THE_LEGS_WRITE = 50;

    /**
     * The count the meter-name scan has to beat, for the reason the statement floor exists.
     */
    private static final int EVERY_METER_THE_LEGS_PUBLISH = 10;

    /** The prefix every meter of this service carries, and no label value does. */
    private static final String METER_PREFIX = "yotresultsdistribution_";

    /** The status lines a label may carry, which is every one a far end can answer with. */
    private static final int MIN_STATUS = 100;

    private static final int MAX_STATUS = 599;

    private static final int MAX_DELIVERY_COUNT = 5;

    /** The shipped entry cap, stated rather than defaulted: no case here is about truncation. */
    private static final int MAX_ENTRIES = 5000;
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(4);

    /** Reads a {@code reason=} or {@code detail=} token out of a formatted line. */
    private static final Pattern REASON = Pattern.compile("\\b(?:reason|detail)=(\\S+)");

    /** Every bounded code this service is permitted to write as a reason. */
    private static final Set<String> BOUNDED_REASONS = Stream.of(
                    Arrays.stream(ReasonCode.values()).map(ReasonCode::code),
                    Arrays.stream(CompletionReason.values()).map(CompletionReason::value),
                    Arrays.stream(TransformationAnomaly.values()).map(TransformationAnomaly::value),
                    Arrays.stream(DeadLetterReason.values()).map(DeadLetterReason::label),
                    // The generation leg's own vocabulary, carried by name because the enum has no
                    // separate wire form: a batch's failure is written down as the constant.
                    Arrays.stream(BatchFailureReason.values()).map(Enum::name),
                    // The gate's three, plus the fourth the job holds privately for the run it did
                    // not stop. The run line's whole field grammar is pinned separately by
                    // RegisterGenerationJobTest.BOUNDED_FIELDS_ONLY; this set is only about which
                    // words may sit in a reason slot.
                    Arrays.stream(GateDecision.Reason.values()).map(GateDecision.Reason::code),
                    // The report's own delivery vocabulary, for the same reason the batch's is
                    // here by name: the e-mail sink writes the constant into a reason slot, and a
                    // delivery that could not be made is exactly the line somebody alerts on.
                    Arrays.stream(ReportDeliveryReason.values()).map(Enum::name),
                    // The sweep's two, by name for the same reason. This is the service's one
                    // absorbed refusal, so the reason beside it is the only evidence it leaves -
                    // and an outage of theirs and a bug of ours have to stay tellable apart, which
                    // is why there are two of them and why both belong in the vocabulary.
                    Arrays.stream(SweepFailureReason.values()).map(Enum::name),
                    // The operations API's own closed set, in its wire spelling and in that one
                    // alone. A reason slot carries wire() wherever it is written - in a
                    // ProblemDetail and in the log line beside it - so a line and the body
                    // answered with it grep as the same code, and admitting the constant as a
                    // second spelling would be the sweep widened to fit the code. The whole enum
                    // rather than the codes logged today, because the set is closed by
                    // OperationsProblem's table and a code added to it is a code an endpoint may
                    // then refuse under.
                    Arrays.stream(OperationsReason.values()).map(OperationsReason::wire),
                    Stream.of("flag-on"))
            .flatMap(codes -> codes)
            .collect(Collectors.toUnmodifiableSet());

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    private final UUID requestId = UUID.fromString("3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8");

    private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
    private final HearingPayloadSource payloadSource = mock(HearingPayloadSource.class);
    private final NowSubscriptionsSource subscriptionsSource = mock(NowSubscriptionsSource.class);
    private final RegisterSubmissionClient submissionClient = mock(RegisterSubmissionClient.class);

    private final RunClaim claim =
            new RunClaim("RESULTS", requestId, "runner-1", UUID.randomUUID(), "msg-1");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PipelineConfig.class, PrivacyTestConfiguration.class)
            .withBean(ObjectMapper.class, JacksonConfig::contractObjectMapper)
            .withBean(IdempotencyGuard.class, () -> guard)
            .withBean(HearingPayloadSource.class, () -> payloadSource)
            .withBean(NowSubscriptionsSource.class, () -> subscriptionsSource)
            .withBean(RegisterSubmissionClient.class, () -> submissionClient);

    /**
     * The settings and the instrument surface the wiring under test consumes and does not own.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(YotResultsDistributionProperties.class)
    static class PrivacyTestConfiguration {

        /**
         * The metrics component, a {@code @Component} in the running service and not
         * component-scanned here.
         *
         * @return the instrument surface
         */
        @Bean
        @ConditionalOnMissingBean
        ProcessingMetrics processingMetrics() {
            return new ProcessingMetrics(new SimpleMeterRegistry());
        }
    }

    // --- a real register, about a child made entirely of markers --------------------------------

    @Nested
    @DisplayName("a hearing that produces a register")
    class ProducingARegister {

        @Test
        @DisplayName("no line, at any level, carries anything that identifies the child")
        void should_never_write_a_child_s_details_at_any_level() {
            admitOneRun();
            when(payloadSource.fetch(any(DistributionCommand.class))).thenReturn(markedHearing());
            answerWith(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));
            when(submissionClient.submit(any(RegisterSubmission.class)))
                    .thenReturn(new SubmissionReceipt(202, true));

            runner.run(context -> {
                try (CapturedLog log = CapturedLog.everything()) {
                    listenerOver(context.getBean(DistributionPipeline.class))
                            .onMessage(deliveryOf(validBody()));

                    assertThat(processingLines(log))
                            .as("a run that logged nothing would satisfy the assertion below "
                                    + "vacuously")
                            .isNotEmpty();
                    for (final String marker : PersonalDataMarkers.PERSONAL) {
                        assertThat(log.renderings())
                                .as("a child's own details reached the log index: %s", marker)
                                .noneMatch(line -> line.contains(marker));
                    }
                }
            });
        }

        @Test
        @DisplayName("every processing line at INFO and above carries the correlation identifiers")
        void should_correlate_every_processing_line() {
            admitOneRun();
            when(payloadSource.fetch(any(DistributionCommand.class))).thenReturn(markedHearing());
            answerWith(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));
            when(submissionClient.submit(any(RegisterSubmission.class)))
                    .thenReturn(new SubmissionReceipt(202, true));

            runner.run(context -> {
                try (CapturedLog log = CapturedLog.everything()) {
                    listenerOver(context.getBean(DistributionPipeline.class))
                            .onMessage(deliveryOf(validBody()));

                    final List<ILoggingEvent> lines = processingLines(log);
                    assertThat(lines).isNotEmpty();
                    for (final ILoggingEvent line : lines) {
                        assertThat(line.getMDCPropertyMap())
                                .as("uncorrelated line: %s", line.getFormattedMessage())
                                .containsKeys(CORRELATION.toArray(String[]::new));
                    }
                }
            });
        }

        @Test
        @DisplayName("every reason written out is one of this service's bounded codes")
        void should_report_only_bounded_reason_codes() {
            admitOneRun();
            // Two runs, because the vocabulary is used by both halves of the state machine: a
            // register that goes, and a register the frozen contract refuses.
            answerWith(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));
            when(submissionClient.submit(any(RegisterSubmission.class)))
                    .thenReturn(new SubmissionReceipt(202, true));

            runner.run(context -> {
                final YotResultsDistributionMessageListener listener =
                        listenerOver(context.getBean(DistributionPipeline.class));
                try (CapturedLog log = CapturedLog.everything()) {
                    when(payloadSource.fetch(any(DistributionCommand.class)))
                            .thenReturn(markedHearing());
                    listener.onMessage(deliveryOf(validBody()));

                    when(payloadSource.fetch(any(DistributionCommand.class)))
                            .thenReturn(payload("hearing-with-address-less-youth-and-parent.json"));
                    listener.onMessage(deliveryOf(validBody()));

                    final List<String> reasons = reasonsIn(log);
                    assertThat(reasons)
                            .as("a run that named no reason would satisfy the assertion below "
                                    + "vacuously")
                            .isNotEmpty();
                    assertThat(reasons)
                            .as("a reason outside the bounded vocabulary is free text, and free "
                                    + "text from this pipeline is made of somebody's hearing")
                            .allMatch(BOUNDED_REASONS::contains);
                }
            });
        }
    }

    /**
     * The one diagnostic this service writes that is derived from the document itself.
     *
     * <p>C29 refuses a register the frozen contract would refuse, and the run is recorded FAILED
     * under the bounded {@code OUTBOUND_CONTRACT_VIOLATION}. That code says a document was refused
     * and never says by what, which leaves support re-deriving the field by hand from a hearing they
     * are not allowed to read — so the JSON pointer of the offending field is written to the log,
     * and written only to the log (the design's contract section says so in those words).
     *
     * <p>It is safe because of what a pointer is made of: the instance location, plus the missing
     * property's name for a {@code required} failure, and both are schema vocabulary. The document
     * is serialised from this repository's own records, so every property name in it is one this
     * repository wrote. This suite is where "never a value" stops being a claim in a comment — the
     * hearing it runs is a child made entirely of markers, and the pointer is written about them.
     */
    @Nested
    @DisplayName("a register the frozen contract refuses")
    class RefusedByTheContract {

        /** The formatted refusal line, which is the only place the pointer exists. */
        private static final String REFUSAL =
                "The assembled register does not satisfy the progression contract";

        @Test
        @DisplayName("names the field's path, and nothing the field contained")
        void should_write_the_offending_path_and_nothing_from_the_document() {
            admitOneRun();
            // The C29 shape: a youth with no address at all, which the vendored schemas require.
            when(payloadSource.fetch(any(DistributionCommand.class))).thenReturn(
                    PersonalDataMarkers.marked(
                            payload("hearing-with-address-less-youth-and-parent.json")));
            answerWith(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));

            runner.run(context -> {
                try (CapturedLog log = CapturedLog.everything()) {
                    listenerOver(context.getBean(DistributionPipeline.class))
                            .onMessage(deliveryOf(validBody()));

                    assertThat(log.messages().stream().filter(line -> line.startsWith(REFUSAL)))
                            .as("a refusal that said nothing would leave support with a hearing to "
                                    + "re-derive by hand")
                            .singleElement()
                            .satisfies(refusal -> assertThat(refusal)
                                    .contains("violation=" + ContractViolation.MISSING_FIELD)
                                    .containsPattern("path=/\\S+"));
                    for (final String marker : PersonalDataMarkers.PERSONAL) {
                        assertThat(log.renderings())
                                .as("the pointer is a path; the child it points at is not in it: %s",
                                        marker)
                                .noneMatch(line -> line.contains(marker));
                    }
                }
            });
        }
    }

    // --- everything the outside world chooses the text of ----------------------------------------

    @Nested
    @DisplayName("text somebody else wrote")
    class SomebodyElsesText {

        @Test
        @DisplayName("a message body is never quoted back, whether it validates or not")
        void should_never_log_the_message_body() {
            final String unparseable = "{ this is not json " + BODY_MARKER;
            final String unknownField = bodyWithExtraField("extra", BODY_MARKER);

            runner.run(context -> {
                final YotResultsDistributionMessageListener listener =
                        listenerOver(context.getBean(DistributionPipeline.class));
                try (CapturedLog log = CapturedLog.everything()) {
                    listener.onMessage(deliveryOf(unparseable));
                    listener.onMessage(deliveryOf(unknownField));

                    assertThat(log.renderings())
                            .as("a rejection says what rule was broken, never what the producer "
                                    + "sent")
                            .noneMatch(line -> line.contains(BODY_MARKER));
                }
            });
        }

        @Test
        @DisplayName("a producer-chosen field name is reported as a placeholder, never as itself")
        void should_never_log_the_name_of_an_unknown_field() {
            final String unknownField = bodyWithExtraField(FIELD_NAME_MARKER, "anything");

            runner.run(context -> {
                try (CapturedLog log = CapturedLog.everything()) {
                    listenerOver(context.getBean(DistributionPipeline.class))
                            .onMessage(deliveryOf(unknownField));

                    assertThat(log.renderings())
                            .as("a name that looks harmless is still a name somebody else chose")
                            .noneMatch(line -> line.contains(FIELD_NAME_MARKER));
                }
            });
        }

        @Test
        @DisplayName("a broker-chosen message identity is never written out")
        void should_never_log_a_message_identity_the_producer_chose() {
            runner.run(context -> {
                final DistributionPipeline pipeline = context.getBean(DistributionPipeline.class);
                try (CapturedLog log = CapturedLog.everything()) {
                    // Two paths that would be tempted to quote it: a body that cannot validate, and
                    // a store that is not there to check the body against.
                    listenerOver(pipeline).onMessage(
                            deliveryOf("{ not json", "RESULTS:" + MESSAGE_ID_MARKER));
                    listenerWithNoStore(pipeline).onMessage(
                            deliveryOf(validBody(), "RESULTS:" + MESSAGE_ID_MARKER));

                    assertThat(log.renderings())
                            .as("the identity is the producer's text, and it would land in the log "
                                    + "index verbatim")
                            .noneMatch(line -> line.contains(MESSAGE_ID_MARKER));
                }
            });
        }

        @Test
        @DisplayName("the user a run is attributed to is not written at any level")
        void should_never_log_the_caller_a_run_is_attributed_to() {
            admitOneRun();
            when(payloadSource.fetch(any(DistributionCommand.class))).thenReturn(markedHearing());
            answerWith(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));
            when(submissionClient.submit(any(RegisterSubmission.class)))
                    .thenReturn(new SubmissionReceipt(202, true));

            runner.run(context -> {
                try (CapturedLog log = CapturedLog.everything()) {
                    listenerOver(context.getBean(DistributionPipeline.class))
                            .onMessage(deliveryOf(bodyNamingTheSharingUser()));

                    assertThat(processingLines(log))
                            .as("the run has to have happened for its silence to mean anything")
                            .isNotEmpty();
                    assertThat(log.renderings())
                            .as("the identity has one destination — a CJSCPPUID header — and the "
                                    + "log is the third way out")
                            .noneMatch(line -> line.contains(CALLER_MARKER));
                }
            });
        }

        @Test
        @DisplayName("a transport fault is reported by its condition, never by its words")
        void should_never_log_the_text_of_a_transport_failure() {
            try (CapturedLog log = CapturedLog.everything()) {
                QueueHealthTestSupport.unwatched().recordProcessorError(
                        "RECEIVE",
                        "yotresultsdistribution.requests",
                        new AmqpException(true, AmqpErrorCondition.CONNECTION_FORCED,
                                "the broker said " + TRANSPORT_MARKER,
                                new AmqpErrorContext("sbemulatorns")));

                assertThat(log.renderings())
                        .as("a transport fault's message is written by the far end, not by us")
                        .noneMatch(line -> line.contains(TRANSPORT_MARKER));
            }
        }

        @Test
        @DisplayName("a settlement the broker refuses is reported by its operation, never by its "
                + "words")
        void should_never_log_the_text_of_a_refused_settlement() {
            admitOneRun();
            when(payloadSource.fetch(any(DistributionCommand.class))).thenReturn(markedHearing());
            answerWith(NowSubscriptionFixtures.youthCourtRegisterSubscription(OU_CODE));
            when(submissionClient.submit(any(RegisterSubmission.class)))
                    .thenReturn(new SubmissionReceipt(202, true));

            final ServiceBusReceivedMessageContext refusing = deliveryOf(validBody());
            doThrow(new IllegalStateException("the broker said " + SETTLEMENT_MARKER))
                    .when(refusing).complete();

            runner.run(context -> {
                try (CapturedLog log = CapturedLog.everything()) {
                    listenerOver(context.getBean(DistributionPipeline.class)).onMessage(refusing);

                    assertThat(log.renderings())
                            .noneMatch(line -> line.contains(SETTLEMENT_MARKER));
                }
            });
        }

        @Test
        @DisplayName("a payload adapter that fails is reported by its type only")
        void should_never_log_the_text_of_a_payload_adapter_failure() {
            admitOneRun();
            // A real cache or query client routinely quotes the key it was asked for and, on a parse
            // failure, the bytes it choked on — which is the payload, arriving by the back door.
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenThrow(new IllegalStateException(
                            "failed reading INT_" + ADAPTER_MARKER));

            runner.run(context -> {
                try (CapturedLog log = CapturedLog.everything()) {
                    listenerOver(context.getBean(DistributionPipeline.class))
                            .onMessage(deliveryOf(validBody()));

                    assertThat(log.renderings())
                            .noneMatch(line -> line.contains(ADAPTER_MARKER));
                }
            });
        }
    }

    // --- the batch and the notification legs -----------------------------------------------------

    /**
     * What the downstream half may write down about who a register goes to.
     *
     * <p>The groups above are about the delivery path, whose personal data is the child's. This one
     * is about everything after it, and the data is somebody else's: a night's registers are
     * batched, rendered by systemdocgenerator and then <strong>e-mailed to named people at named
     * Youth Offending Teams</strong>. Three values arrive with that, and none of them is a
     * defendant's:
     *
     * <ul>
     *   <li><strong>a recipient's e-mail address</strong>, which reference data supplies, the
     *       document carries, {@code register_notification} keys on and the notify command's body
     *       is addressed to - forbidden at every level, exactly as a child's own details are;</li>
     *   <li><strong>a recipient's name</strong>, which the template greets by name and the same row
     *       holds beside the address - forbidden at every level for the same reason;</li>
     *   <li><strong>systemdocgenerator's own {@code reason}</strong>, which is free text another
     *       service wrote about a document whose every defendant is a child. This one is the graded
     *       rule rather than the flat one: it is deliberately kept - carried to the
     *       {@code sdg_reason} column, which is a column and not a log index - so it is forbidden
     *       at INFO and above and in every metric label, and permitted at DEBUG, where two
     *       statements say so in their own comments.</li>
     * </ul>
     *
     * <p>And the positive half, because a rule that forbade everything would be satisfied by a leg
     * that wrote nothing at all and left support with a night of e-mails and no way to ask about
     * one: <strong>a batch id, a notification id and a bounded reason code are written, at INFO and
     * above.</strong> Those three are what a resend is asked for by - {@code notify-register
     * --batch} takes the first of them - and none of them names a person.
     *
     * <p><strong>The sweep is over the statements rather than over a list of cases.</strong> Every
     * line the two legs can write is enumerated out of the eight sources that write one, and the
     * last case below insists the drive above reached <em>each</em> of them: a statement added to
     * any of those classes later is inside this claim from the moment it is written, rather than
     * inside it if somebody remembered to add a case. The classes are named because the two legs
     * are not a package - the run and the stale-batch pass are in {@code batch}, the two services in
     * {@code application}, the renderer's client, the notifier's client and the topic listener in
     * three {@code adapter} packages - and the ninth,
     * {@link uk.gov.hmcts.cp.yotresultsdistribution.adapter.fileservice.FileServicePayloadStore}, is in the
     * list for the opposite reason: it holds a night's payload and writes no line at all, which is
     * a claim of its own and is asserted as one.
     *
     * <p><strong>[A]</strong>: every case in this group is a characterisation of behaviour the two
     * legs already had when it was written, so each records a passing run rather than a red one.
     * What makes it more than a rubber stamp is stated where each case's own reason is, and was
     * shown by mutation before it was committed: a line given the recipient's address, a
     * {@code sdg_reason} line raised from DEBUG to INFO, and a second statement in one of the swept
     * classes repeating a pattern already written there - each failing exactly one case and each
     * reverted.
     *
     * <p>What the drive doubles is the store and its two repositories, and nothing else: the two
     * outward HTTP clients are the real ones over a real socket, the mapper that turns a batch into
     * a payload is the real one, and the metrics are registered on a real registry which the last
     * two cases read back. The clients matter most - they are the classes with the address in their
     * hands and a far end's status line in their exceptions.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("a register generated, and the teams told about it")
    class TheGenerationAndNotificationLegs {

        /** Every line the drive wrote, at every level, with any attached exception rendered. */
        private final List<ILoggingEvent> written = new ArrayList<>();

        /** The instruments the drive moved, on the registry the service would export from. */
        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

        /** Whether the drive handed systemdocgenerator's own words to the store, which keeps them. */
        private boolean carriedTheGeneratorsWords;

        @BeforeAll
        void driveTheTwoLegs() throws Exception {
            try (CapturedLog log = CapturedLog.everything();
                    GenerationLegs legs = GenerationLegs.overMarkedRecipients(meters)) {
                legs.driveEverything();
                carriedTheGeneratorsWords = legs.generatorWordsReachedTheStore();
                written.addAll(log.events());
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("uk.gov.hmcts.cp.yotresultsdistribution.config.TelemetryPrivacyTest"
                + "#everythingARecipientIsIdentifiedBy")
        @DisplayName("[A] writes down nothing that identifies a recipient, at any level")
        void should_never_write_down_anything_that_identifies_a_recipient(
                final String what, final String marker) {

            assertThat(renderings())
                    .as("the drive has to have written something for its silence to mean anything")
                    .isNotEmpty();
            assertThat(renderings())
                    .as("%s reached the log index, and the index is read by the whole estate", what)
                    .noneMatch(line -> line.contains(marker));
        }

        /**
         * The bounded-reason rule, over the two legs rather than over the delivery path alone.
         *
         * <p>{@code reason=} and {@code detail=} are parsed slots: an index filter and an alert
         * query key on them, so what goes in one has to come from a vocabulary and not from a
         * sentence. The delivery path has been swept for this since T070; the legs were outside it
         * for no better reason than that {@code GenerationLegs} did not exist yet, and a rule
         * enforced on one half of a service is a rule that will be broken on the other.
         *
         * <p>It catches nothing today that item 8 does not already name - there are exactly two
         * such slots on the legs, and the run report's own is pinned by
         * {@code RegisterGenerationJobTest.BOUNDED_FIELDS_ONLY}. Its value is the next one somebody
         * writes.
         *
         * <p><strong>INFO and above, which is the scope Principle VII governs and not a
         * convenience.</strong> The rule this sweep enforces is about the estate's index, which is
         * what INFO and above reaches; a slot below it is a diagnostic. Nothing writes the
         * generator's own words into one any more - the retired query client's DEBUG line was the
         * one place they were allowed, and it went with the query - but the scope is stated in
         * terms of the rule rather than of what happens to exist.
         */
        @Test
        @DisplayName("writes a bounded code into every reason slot, on both legs")
        void should_write_a_bounded_code_into_every_reason_slot() {
            final List<String> reasons = reasonsIn(written.stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                    .toList());

            assertThat(reasons)
                    .as("a drive that named no reason at all would satisfy the assertion below "
                            + "vacuously, and this drive reaches the payload store's own failure")
                    .isNotEmpty();
            assertThat(reasons)
                    .as("a reason outside the bounded vocabulary is free text in a slot something "
                            + "parses; the batch's own reason is already a bounded code and is "
                            + "what belongs there")
                    .allMatch(BOUNDED_REASONS::contains);
        }

        @Test
        @DisplayName("[A] keeps systemdocgenerator's own words out of INFO and above")
        void should_keep_the_generator_s_own_words_below_info() {
            assertThat(renderedAtOrAbove(Level.INFO))
                    .as("a run that logged nothing above DEBUG would satisfy this vacuously")
                    .isNotEmpty();
            assertThat(renderedAtOrAbove(Level.INFO))
                    .as("the generator's reason is free text about a document whose every "
                            + "defendant is a child; it goes to sdg_reason, not to the index")
                    .noneMatch(line -> line.contains(PersonalDataMarkers.GENERATOR_REASON));
            assertThat(renderings())
                    .as("and now the query is gone they are in no line at any level: the one place "
                            + "they were allowed was the retired client's own DEBUG slot, and what "
                            + "keeps them is sdg_reason, which is a column")
                    .noneMatch(line -> line.contains(PersonalDataMarkers.GENERATOR_REASON));
            assertThat(carriedTheGeneratorsWords)
                    .as("a sweep that found them nowhere would be passing because the drive never "
                            + "carried any, so the proof is taken where they land")
                    .isTrue();
        }

        @Test
        @DisplayName("[A] and does say which batch, which notification and for what bounded reason")
        void should_still_say_which_batch_and_which_notification_and_why() {
            final List<String> reportable = renderedAtOrAbove(Level.INFO);

            assertThat(reportable)
                    .as("the batch id is what an operator resends by - notify-register --batch "
                            + "- so a leg that named no batch would leave a night unrecoverable")
                    .anyMatch(line -> line.contains(GenerationLegs.BATCH_ID.toString()));
            assertThat(reportable)
                    .as("the notification id is the identity one recipient's e-mail was asked for "
                            + "under, and the only way to ask notificationnotify what became of it")
                    .anyMatch(line -> line.contains(GenerationLegs.NOTIFICATION_ID.toString()));
            assertThat(reportable)
                    .as("and a failure names the bounded code it was failed under, which is what "
                            + "the register's own column holds")
                    .anyMatch(line -> line.contains(
                            BatchFailureReason.RENDER_REQUEST_FAILED.name()));
        }

        @Test
        @DisplayName("[A] and puts nothing that identifies anybody in a meter's name or label")
        void should_never_label_a_series_with_anything_that_identifies_anybody() {
            final List<String> series = meters.getMeters().stream()
                    .map(meter -> meter.getId().getName() + " "
                            + meter.getId().getTags().stream()
                                    .map(tag -> tag.getKey() + "=" + tag.getValue())
                                    .collect(Collectors.joining(",")))
                    .toList();

            assertThat(series)
                    .as("a leg that published no series at all would satisfy this vacuously")
                    .isNotEmpty();
            for (final String marker : GenerationLegs.NOTHING_A_SERIES_MAY_CARRY) {
                assertThat(series)
                        .as("a label is a series, and a series is kept for as long as the estate "
                                + "keeps metrics: %s", marker)
                        .noneMatch(line -> line.contains(marker));
            }
            assertThat(series)
                    .as("a batch id is not personal data and is still not a label - one series per "
                            + "batch is a cardinality explosion the class's own javadoc refuses")
                    .noneMatch(line -> line.contains(GenerationLegs.BATCH_ID.toString()));
        }

        @Test
        @DisplayName("[A] and labels every series it publishes with a bounded code")
        void should_label_every_series_with_a_bounded_code() {
            final Set<String> bounded = boundedLabelVocabulary();
            final List<String> labels = meters.getMeters().stream()
                    .flatMap(meter -> meter.getId().getTags().stream())
                    .map(Tag::getValue)
                    .toList();

            assertThat(labels)
                    .as("a leg that labelled no series would satisfy this vacuously")
                    .isNotEmpty();
            assertThat(labels)
                    .as("a label outside the bounded vocabulary is free text, and free text down "
                            + "here is somebody's address or another service's prose")
                    .allMatch(bounded::contains);
            assertThat(labels)
                    .as("and the bounded codes really are there, so the sweep is not passing "
                            + "because nothing was labelled with a reason at all")
                    .contains(GenerationMetrics.CLAIM_LOST);
        }

        /**
         * The other half of the label claim: that the drive reached every series there is.
         *
         * <p>A sweep over the labels on a registry is only as wide as the meters something put
         * there, so this is the meters' version of the statement scan below - the names are read
         * off {@link GenerationMetrics}, and a meter declared later is inside the claim above from
         * the moment its name is.
         *
         * <p><strong>The one meter that used to be exempted no longer is.</strong>
         * {@code yotresultsdistribution_generation_latency} was a timer no production code recorded, so no
         * arrangement of this leg could put a reading on it, and this case named it as the single
         * unmoved meter so that whoever wired the timer up would be told to fold it into the drive.
         * It is wired now: the outcome sink times a render's round trip off the row it has just
         * settled, which is the one path an outcome reaches this service by. So the exception is
         * gone and the claim is the plain one - the drive moves every meter
         * {@link GenerationMetrics} declares, and the label sweep above passes over all of them.
         */
        @Test
        @DisplayName("[A] and the drive above moved every meter the downstream half can publish")
        void should_have_moved_every_meter_the_downstream_half_can_publish() {
            final Set<String> published = meters.getMeters().stream()
                    .map(meter -> meter.getId().getName())
                    .collect(Collectors.toSet());

            assertThat(declaredMeterNames())
                    .as("a scan that found no meter name would make this cover nothing")
                    .hasSizeGreaterThan(EVERY_METER_THE_LEGS_PUBLISH);
            assertThat(declaredMeterNames().stream()
                            .filter(name -> !published.contains(name))
                            .toList())
                    .as("a meter the drive never moved is a series whose labels nothing above "
                            + "swept; each one needs a case in GenerationLegs.driveEverything")
                    .isEmpty();
        }

        /**
         * The sweep proper, and the two preconditions without which it covers nothing.
         *
         * <p>A declaration is matched to a captured event by logger and message pattern, so the
         * first thing asserted after the scan's floor is that no two declarations share that key.
         * Where two do, the event from whichever the drive reached satisfies <em>both</em>, and the
         * reach assertion below is met without the second statement ever having been written to -
         * so a line added beside one that already writes that wording would be outside every claim
         * in this group from the moment it was written, with this suite still green. That is the
         * one failure the enumeration exists to prevent, and it is asserted here rather than in a
         * case of its own precisely so that it cannot be true while this case reports green.
         */
        @Test
        @DisplayName("[A] and the drive above reached every line the two legs can write")
        void should_have_reached_every_line_the_two_legs_can_write() throws Exception {
            final List<LogStatement> declared = LogStatement.everyOneIn(GenerationLegs.THE_LEGS);
            final Set<String> reached = written.stream()
                    .map(event -> event.getLoggerName() + "|" + event.getMessage())
                    .collect(Collectors.toSet());

            assertThat(declared)
                    .as("a scan that found no statement would make the sweep above cover nothing")
                    .hasSizeGreaterThan(EVERY_LINE_THE_LEGS_WRITE);
            assertThat(GenerationLegs.THE_REPORT)
                    .as("the report's classes are inside the enumeration now, and a class that "
                            + "declares no statement contributes nothing for the reach assertion "
                            + "below to cover - it is widened past in silence, and the sweep says "
                            + "it covered them while covering nothing of theirs")
                    .isNotEmpty()
                    .allSatisfy(reporting -> assertThat(declared.stream()
                                    .filter(statement ->
                                            reporting.getName().equals(statement.loggerName()))
                                    .toList())
                            .as("the statements %s declares; asked of each class rather than of "
                                    + "the two together, because one of them writing two lines "
                                    + "satisfies a claim about the pair while the other is "
                                    + "covered by nothing", reporting.getSimpleName())
                            .isNotEmpty());
            assertThat(LogStatement.keyCollisionsIn(declared))
                    .as("two statements one key cannot tell apart: the event from either satisfies "
                            + "both declarations, so the assertion below is met without the second "
                            + "of them being reached at all; give one its own wording")
                    .isEmpty();
            assertThat(declared.stream()
                            .filter(statement -> !reached.contains(statement.key()))
                            .map(LogStatement::where)
                            .toList())
                    .as("a statement the drive never reached is a statement outside every claim "
                            + "above; each needs a case in GenerationLegs.driveEverything")
                    .isEmpty();
        }

        /**
         * Which classes the enumeration names, asked of the two this increment added.
         *
         * <p>Every claim in this group is bounded by {@link GenerationLegs#THE_LEGS}: a line
         * written by a class the list does not name is outside the reach assertion, outside the
         * label sweep and outside the statement scan, with this suite green. So the list itself is
         * asserted, and asserted the way the report's four classes are - by name <em>and</em> by
         * the statements each brought with it, because a class added to the list that declares no
         * line widens the enumeration without widening a single claim.
         *
         * <p><strong>And the one that is no longer there.</strong> The retired reconciler took the
         * three in-flight age readings and the grace-period sweep with it (FR-006); what replaced
         * them is the run's first act and a sweep of its own, and a list that still named the
         * deleted class would not compile while a list that named neither of the new ones would
         * quietly cover nothing they write. The absence is asserted by name rather than by type
         * for exactly that reason: a type that does not exist cannot be written down here at all.
         */
        @Test
        @DisplayName("[A] and the enumeration names 004's two classes, with the lines they write")
        void should_enumerate_the_release_pass_and_the_batch_age_sweep() throws Exception {
            assertThat(GenerationLegs.THE_LEGS)
                    .as("the two classes this increment added to the generation half; a line "
                            + "either of them writes is inside every claim above only while the "
                            + "list names it")
                    .contains(StaleBatchReleaser.class, BatchAgeSweep.class);
            assertThat(GenerationLegs.THE_LEGS.stream().map(Class::getSimpleName).toList())
                    .as("and the mechanism they replaced is named nowhere: the grace-period "
                            + "reconciler is gone, and a sweep that still expected its lines would "
                            + "be describing a night this service no longer has")
                    .doesNotContain("GenerationReconciler");
            assertThat(List.of(StaleBatchReleaser.class, BatchAgeSweep.class))
                    .allSatisfy(added -> assertThat(LogStatement.everyOneIn(List.of(added)))
                            .as("the statements %s declares; asked of each class rather than of "
                                    + "the pair, because one of them writing three lines satisfies "
                                    + "a claim about both while the other is covered by nothing",
                                    added.getSimpleName())
                            .isNotEmpty());
        }

        /**
         * The claim that makes one whole class of leak unrepeatable, over the production sources.
         *
         * <p>Five separate statements wrote a bounded reason and then attached the throwable as
         * well, each found by a review rather than by a test, and the fifth arrived in the fix for
         * the first. A rendered exception carries the message of whoever raised it - a driver, a
         * pool, an HTTP client, the broker - so the bounded reason beside it buys nothing: what
         * reaches the log index is a library's words about this service's data, which on a store
         * or transport failure is where a connection string or a fragment of a statement appears.
         *
         * <p>So the shape is refused rather than the instances pinned, and refused across all of
         * {@code src/main/java} rather than the swept legs alone, because the operations commands
         * write to the same index. <strong>Nothing may be attached, this service's own exceptions
         * included.</strong> Two narrower rules were tried and each was defeated within one
         * review: a list of this service's types let three wrappers through, since a cause chain
         * renders recursively, and deriving the answer from the constructors fell to
         * {@code initCause}, which every exception inherits. A line carries the class of what was
         * caught, and a cause belongs at DEBUG where the principle allows it.
         */
        @Test
        @DisplayName("no line anywhere attaches a throwable, whoever raised it")
        void should_attach_no_throwable_to_any_line() throws Exception {
            assertThat(LogStatement.attachmentsInProductionSources())
                    .as("each of these renders the message of whatever it caught, and no rule "
                            + "about which exceptions are safe survived contact with a review; "
                            + "name the class and drop the throwable")
                    .isEmpty();
        }

        @Test
        @DisplayName("[A] and the payload store writes no line at all, so its words are its own")
        void should_leave_the_payload_store_writing_nothing() throws Exception {
            assertThat(LogStatement.everyOneIn(List.of(FileServicePayloadStore.class)))
                    .as("the store's failures reach a log line as another class's reason=, and its "
                            + "messages are bounded phrases written in this repository for that; a "
                            + "line of its own would be a second, unasserted way out")
                    .isEmpty();
        }

        private List<String> renderings() {
            return written.stream().map(CapturedLog::rendering).toList();
        }

        private List<String> renderedAtOrAbove(final Level level) {
            return written.stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(level))
                    .map(CapturedLog::rendering)
                    .toList();
        }
    }

    /**
     * Every meter name {@link GenerationMetrics} declares, read off the class rather than listed.
     *
     * <p>The names are its own {@code public static final String} constants and they are the
     * alerting surface the estate fires on, so the set is taken from the class: a meter added to
     * the downstream half is then inside the claim above from the moment its name is declared.
     *
     * @return every declared meter name
     */
    private static Set<String> declaredMeterNames() {
        return Arrays.stream(GenerationMetrics.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.getType() == String.class)
                .map(TelemetryPrivacyTest::valueOf)
                .filter(value -> value.startsWith(METER_PREFIX))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Every value a label of the downstream half is permitted to carry.
     *
     * <p>Three sources and no fourth: the bounded reason constants
     * {@link GenerationMetrics} declares itself, the enumerations whose constants it turns into
     * codes, and a status line, which is three digits. Anything else on a label is free text, and
     * free text on this leg is a recipient's address or another service's prose about a document
     * whose every defendant is a child.
     *
     * <p>{@link SweepFailureReason} joined the second source at 004, when the batch-age sweep
     * gave the generation half an absorbed refusal of its own. It is the same enumeration the
     * intake sweep's counter is labelled from - one vocabulary for one kind of refusal - and the
     * two codes exist precisely so that an outage of theirs cannot hide inside a bug of ours.
     *
     * @return the bounded vocabulary
     */
    private static Set<String> boundedLabelVocabulary() {
        return Stream.of(
                        Arrays.stream(GenerationMetrics.class.getDeclaredFields())
                                .filter(field -> Modifier.isStatic(field.getModifiers()))
                                .filter(field -> field.getType() == String.class)
                                .map(TelemetryPrivacyTest::valueOf)
                                .filter(value -> !value.startsWith(METER_PREFIX)),
                        Arrays.stream(BatchStatus.values()).map(TelemetryPrivacyTest::code),
                        Arrays.stream(NotificationStatus.values())
                                .map(TelemetryPrivacyTest::code),
                        Arrays.stream(SweepFailureReason.values())
                                .map(TelemetryPrivacyTest::code),
                        Stream.concat(
                                        Stream.of(new FlagDecision.Enabled(),
                                                new FlagDecision.Disabled()),
                                        Arrays.stream(FlagDecision.UnreadableReason.values())
                                                .map(FlagDecision.Unreadable::new))
                                .map(FlagDecision::code),
                        Arrays.stream(GateDecision.Reason.values()).map(GateDecision.Reason::code),
                        IntStream.rangeClosed(MIN_STATUS, MAX_STATUS).mapToObj(String::valueOf))
                .flatMap(values -> values)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The label a state is counted under, as {@link GenerationMetrics} derives it. */
    private static String code(final Enum<?> state) {
        return state.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** One declared constant's value, which a test-only read cannot be refused. */
    private static String valueOf(final Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException unreadable) {
            throw new IllegalStateException(
                    "a public constant of GenerationMetrics could not be read", unreadable);
        }
    }

    /**
     * Everything reference data and a subscription say about who a register is going to.
     *
     * @return each value, beside what it is
     */
    static Stream<Arguments> everythingARecipientIsIdentifiedBy() {
        return Stream.of(
                arguments("a recipient's e-mail address", PersonalDataMarkers.RECIPIENT_EMAIL),
                arguments("a recipient's name", PersonalDataMarkers.RECIPIENT_ORGANISATION));
    }

    // --- secrets ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a connection string is never written out, not even while it is being used")
    void should_never_log_the_broker_credential() {
        final YotResultsDistributionProperties properties = credentialledWith(
                "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                        + "SharedAccessKey=" + SECRET_MARKER + ";UseDevelopmentEmulator=true;");

        runner.run(context -> {
            try (CapturedLog log = CapturedLog.everything()) {
                // Building the client is where the settings are read and announced. Nothing
                // connects: the processor client is lazy, so this exercises the logging and not the
                // broker.
                new ServiceBusConsumerConfig()
                        .courtRegisterProcessorClient(
                                properties,
                                listenerOver(context.getBean(DistributionPipeline.class)),
                                QueueHealthTestSupport.unwatched())
                        .close();

                assertThat(log.renderings())
                        .as("the startup line names which credential source was chosen, never the "
                                + "credential")
                        .noneMatch(line -> line.contains(SECRET_MARKER));
            }
        });
    }

    // --- the surface an operator reads -----------------------------------------------------------

    /**
     * The sweep, extended past log statements to the one thing this service now writes to a caller.
     *
     * <p>Every other case in this suite is about a log line or a metric label. Since increment 005
     * there is a third telemetry surface - an HTTP response - and Principle VII governs it exactly
     * as it governs the other two: bounded codes, counts and identifiers, no defendant detail, no
     * unmasked recipient address, no exception message and nothing the caller supplied (FR-025,
     * FR-026).
     *
     * <p>Read off the source and off the records themselves rather than by driving a request. A
     * case per endpoint proves what that endpoint answered for the inputs it was given; these
     * prove what any endpoint <em>could</em> answer, which is the claim a sweep exists to make.
     */
    @Nested
    @DisplayName("what an operations response may carry")
    class TheOperationsSurface {

        /** Where the response and request records live. */
        private static final Path DTO = Path.of("src", "main", "java", "uk", "gov", "hmcts", "cp",
                "yotresultsdistribution", "api", "dto");

        /** The inbound adapter itself: the controllers, the advice, the filter and the facts. */
        private static final Path API = Path.of("src", "main", "java", "uk", "gov", "hmcts", "cp",
                "yotresultsdistribution", "api");

        /**
         * Every type a bounded field may be.
         *
         * <p>A closed list rather than a rule about what is forbidden, for the reason the reason
         * codes are an enum: a response record gaining a {@code CourtRegisterDocument}, a
         * {@code RegisterRecord} or a {@code JsonNode} is how a defendant reaches a caller, and
         * the way to catch that is to say what is allowed rather than to guess at what is not.
         */
        private static final Set<Class<?>> BOUNDED = Set.of(
                String.class, int.class, long.class, boolean.class, Integer.class, Long.class,
                Boolean.class, UUID.class, LocalDate.class, java.time.Instant.class,
                List.class, Map.class);

        /**
         * Field names that would be a person, whatever their type.
         *
         * <p>Matched as whole words against the component's name, lower-cased. {@code fileName} and
         * {@code courtHouse} are not among them and must not be: the register's file name is a
         * bounded composition of a date, an OU code and a hearing id, and a court house is a
         * building.
         */
        private static final List<String> A_PERSON = List.of("defendant", "person", "guardian",
                "dateofbirth", "nino", "ethnic", "statementoffacts", "firstname", "lastname",
                "surname", "postcode");

        /**
         * Every record the API declares, request and response alike.
         *
         * @return the record classes under {@code api/dto}, nested ones included
         * @throws Exception where the directory cannot be read
         */
        private List<Class<?>> theApiRecords() throws Exception {
            final List<Class<?>> records = new ArrayList<>();
            try (Stream<Path> sources = Files.walk(DTO)) {
                for (final Path source : sources
                        .filter(path -> path.toString().endsWith(".java")).toList()) {

                    final String simple = source.getFileName().toString().replace(".java", "");
                    final Class<?> declared = Class.forName(
                            "uk.gov.hmcts.cp.yotresultsdistribution.api.dto." + simple);
                    records.add(declared);
                    records.addAll(Arrays.asList(declared.getDeclaredClasses()));
                }
            }
            return records.stream().filter(Class::isRecord).toList();
        }

        @Test
        @DisplayName("every field of every request and response record is a bounded type")
        void no_api_record_should_carry_a_type_this_service_keeps_out_of_telemetry()
                throws Exception {

            final List<String> unbounded = new ArrayList<>();
            for (final Class<?> declared : theApiRecords()) {
                for (final RecordComponent component : declared.getRecordComponents()) {
                    final Class<?> type = component.getType();
                    if (!BOUNDED.contains(type) && !type.isEnum()
                            && !type.getName().startsWith(
                                    "uk.gov.hmcts.cp.yotresultsdistribution.api.dto.")) {
                        unbounded.add(declared.getSimpleName() + '.' + component.getName()
                                + " : " + type.getName());
                    }
                }
            }

            assertThat(unbounded)
                    .as("a response record gaining a register document, a register row or a "
                            + "JsonNode is how a defendant reaches a caller - so what may be on "
                            + "one is a closed list, exactly as the reason codes are")
                    .isEmpty();
        }

        @Test
        @DisplayName("and no field of one is named after a person")
        void no_api_record_should_name_a_field_after_a_person() throws Exception {
            final List<String> named = new ArrayList<>();
            for (final Class<?> declared : theApiRecords()) {
                for (final RecordComponent component : declared.getRecordComponents()) {
                    final String lowered = component.getName().toLowerCase(Locale.ROOT);
                    if (A_PERSON.stream().anyMatch(lowered::contains)) {
                        named.add(declared.getSimpleName() + '.' + component.getName());
                    }
                }
            }

            assertThat(named)
                    .as("every defendant on this register is a child, and a field that names one "
                            + "is a field somebody will populate")
                    .isEmpty();
        }

        @Test
        @DisplayName("the ProblemDetail is never given a detail")
        void no_refusal_should_carry_an_exceptions_or_a_stores_own_words() throws Exception {
            assertThat(sourcesUnder(API))
                    .as("`detail` is the one field of RFC 9457 that invites free text, and free "
                            + "text is where a connection string or a fragment of a statement "
                            + "turns up - so it is never populated at all (data-model, Common)")
                    .noneMatch(source -> source.contains("setDetail("));
        }

        @Test
        @DisplayName("the caller's identity reaches no line of the inbound adapter")
        void the_callers_identity_should_belong_to_the_audit_event_and_nowhere_else()
                throws Exception {

            assertThat(sourcesUnder(API))
                    .as("the CJSCPPUID belongs in the audit event, which is the one place this "
                            + "service names a caller on purpose (FR-038) - a class that does not "
                            + "hold the value cannot log it")
                    .noneMatch(source -> source.contains("CJSCPPUID"));
        }

        @Test
        @DisplayName("and the address masking has exactly one home")
        void the_api_should_not_carry_a_masking_rule_of_its_own() throws Exception {
            assertThat(sourcesUnder(API))
                    .as("`list-batches` masked by one rule and the endpoint masks by the same one, "
                            + "in BatchListingService; a second rule in the adapter is how the two "
                            + "come to disagree, and the weaker of them is the one that ships")
                    .noneMatch(source -> source.contains("\"***\""));
        }

        /**
         * Every Java source under a directory, as text.
         *
         * @param root where to read from
         * @return the sources
         * @throws Exception where the tree cannot be read
         */
        private List<String> sourcesUnder(final Path root) throws Exception {
            try (Stream<Path> sources = Files.walk(root)) {
                final List<String> text = new ArrayList<>();
                for (final Path source : sources
                        .filter(path -> path.toString().endsWith(".java")).toList()) {
                    text.add(Files.readString(source));
                }
                return text;
            }
        }
    }

    // --- the configuration that decides what reaches the index -----------------------------------

    @Nested
    @DisplayName("the shipped logging configuration")
    class ShippedConfiguration {

        /**
         * The one shipped configuration, which since increment 005 is the only one there is.
         *
         * <p>There were two until the CLI was removed: {@code logback-cli.xml} was the one a
         * command's JVM started under, differing from the pod's in one line so that a command's
         * report had stdout to itself. No JVM starts under it now - an operations call is served by
         * a pod already running - so the file is gone and every claim here is about the pod's.
         */
        @ParameterizedTest
        @ValueSource(strings = "logback.xml")
        @DisplayName("emits the MDC, without which the correlation fields are thrown away")
        void should_ship_a_logging_configuration_that_carries_the_correlation_fields(
                final String configuration) throws Exception {
            final String logback = Files.readString(
                    Path.of("src", "main", "resources", configuration));

            assertThat(logback)
                    .as("without the MDC provider the identifiers are put in place and then thrown "
                            + "away, and every line above becomes uncorrelated")
                    .contains("<mdc/>");
            assertThat(logback)
                    .as("and a level below INFO here is how the whole-payload rules above stop "
                            + "being true, whichever of the two a JVM starts under")
                    .doesNotContain("DEBUG")
                    .doesNotContain("TRACE");
        }

        /**
         * The provider without which the report's events are a sentence again.
         *
         * <p>{@code LogEventReportSink} writes both of its events through
         * {@code StructuredArguments.value(...)}, and an encoder with no {@code <arguments/>}
         * provider renders those values into the message text and emits no fields at all - so
         * every saved query would need {@code parse()}, which is precisely what SC-003 forbids.
         *
         * <p><strong>Read as XML, not as characters.</strong> A search for the text
         * {@code <arguments/>} is satisfied by the tag inside an XML comment, which is the one
         * shape a well-meant edit actually takes - somebody commenting a provider out to quieten a
         * local run - and it is equally satisfied by the tag sitting anywhere else in the file,
         * where logback would never read it as a provider at all. So the file is parsed and the
         * question asked of the encoder's own provider list.
         *
         * @param configuration the shipped file being read
         * @throws Exception where the file cannot be read or parsed at all
         */
        @ParameterizedTest
        @ValueSource(strings = "logback.xml")
        @DisplayName("emits the structured arguments, without which the report's fields are prose")
        void the_logback_file_declares_the_arguments_provider(final String configuration)
                throws Exception {
            assertThat(encoderProvidersOf(configuration))
                    .as("without the arguments provider every field of both report events is "
                            + "rendered into the message and every query needs parse(); a "
                            + "commented-out tag is not a provider, and neither is one outside "
                            + "the encoder's list")
                    .contains("arguments");
        }

        /**
         * Every provider the shipped file declares, by element name, inside an encoder.
         *
         * @param configuration the shipped file being read
         * @return the provider element names, in the order the encoder lists them
         * @throws Exception where the file cannot be read or parsed
         */
        private List<String> encoderProvidersOf(final String configuration) throws Exception {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            final Document parsed = factory.newDocumentBuilder()
                    .parse(Path.of("src", "main", "resources", configuration).toFile());

            final List<String> providers = new ArrayList<>();
            final NodeList lists = parsed.getElementsByTagName("providers");
            for (int list = 0; list < lists.getLength(); list++) {
                final Node declared = lists.item(list);
                if (!"encoder".equals(declared.getParentNode().getNodeName())) {
                    continue;
                }
                final NodeList children = declared.getChildNodes();
                for (int child = 0; child < children.getLength(); child++) {
                    final Node provider = children.item(child);
                    if (provider.getNodeType() == Node.ELEMENT_NODE) {
                        providers.add(provider.getNodeName());
                    }
                }
            }
            return providers;
        }

        @Test
        @DisplayName("turns no logger below INFO, so no deployed pod writes a payload dump")
        void should_ship_no_logger_below_info() throws Exception {
            final String logging = loggingSectionOf(Files.readString(
                    Path.of("src", "main", "resources", "application.yaml")));

            assertThat(logging)
                    .as("the whole-payload rules above assume a deployed pod runs at INFO; a "
                            + "shipped DEBUG level is how that assumption stops being true")
                    .doesNotContain("DEBUG")
                    .doesNotContain("TRACE");
            assertThat(logging).contains("root: INFO");
        }

        /**
         * The flag client is built with no retries, and azure-core's {@code RetryPolicy} then
         * writes "Retry attempts have been exhausted" at INFO after <em>every</em> response, a
         * {@code 200} included, because zero attempts left is exhausted by its arithmetic, and the
         * recorded-flag renewal turns that into a steady stream of lines announcing a failure that
         * did not happen. A failed attempt is still written: the policy writes its exception at
         * ERROR, and the reader writes its own bounded WARN, once per decision. The level is the
         * class's logger, so every azure-core HTTP pipeline in the JVM - the identity one included -
         * loses its INFO retry lines with it; the comment beside the setting says what that costs.
         */
        @Test
        @DisplayName("holds the SDK's retry policy at WARN, so a successful flag read is not a "
                + "line claiming retries were exhausted")
        void should_hold_the_sdk_retry_policy_at_warn() throws Exception {
            final String logging = loggingSectionOf(Files.readString(
                    Path.of("src", "main", "resources", "application.yaml")));

            assertThat(logging).contains("com.azure.core.http.policy.RetryPolicy: WARN");
        }

        /**
         * The settings in the shipped {@code logging:} block, from its key to the end of the
         * document, with the comments taken out.
         *
         * <p>Read as text rather than bound as properties on purpose: what is being asserted is what
         * the file <em>ships</em>, and a binder would hand back the merged view of every source,
         * including whatever the test harness itself set.
         *
         * <p>Comments are dropped because the claim is about configured levels. A block that
         * documents why nothing here may be set to DEBUG is the opposite of the block this refuses,
         * and a matcher that could not tell the two apart would punish the file for explaining
         * itself.
         */
        private String loggingSectionOf(final String applicationYaml) {
            final int start = applicationYaml.indexOf("\nlogging:");
            assertThat(start).as("application.yaml declares a logging section").isNotNegative();
            return applicationYaml.substring(start).lines()
                    .filter(line -> !line.strip().startsWith("#"))
                    .collect(Collectors.joining("\n"));
        }
    }

    // --- fixtures --------------------------------------------------------------------------------

    /**
     * The base hearing, with every field that identifies its child replaced by a marker.
     *
     * <p>The marking is {@link PersonalDataMarkers}', because {@code config/TelemetryPrivacyIT}
     * sweeps for the same values through the live adapters: two copies of this method are how one
     * of the two suites comes to look for a field the other has stopped setting.
     *
     * @return the claim-check envelope the payload source answers with
     */
    private JsonNode markedHearing() {
        return PersonalDataMarkers.marked(payload("hearing-with-surviving-youth-defendant.json"));
    }

    /**
     * One base payload, deep-copied so a case cannot change it for the next one.
     *
     * @param fixture the file name below {@code fixtures/base/}
     * @return the claim-check envelope
     */
    private JsonNode payload(final String fixture) {
        return LegacyFixtures.readBase(fixture).deepCopy();
    }

    /** The guard admits the run and accepts whatever the run reports. */
    private void admitOneRun() {
        when(guard.admit(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenReturn(new GuardDecision.Run(claim));
        when(guard.recordCompletion(any(RunClaim.class), any(CompletionReason.class)))
                .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
        when(guard.recordTransientFailure(any(RunClaim.class), any(ReasonCode.class)))
                .thenAnswer(call -> new GuardDecision.Abandon(call.getArgument(1)));
        when(guard.recordNonTransientFailure(any(RunClaim.class), any(ReasonCode.class)))
                .thenAnswer(call -> new GuardDecision.DeadLetter(
                        DeadLetterReason.NON_TRANSIENT, call.getArgument(1)));
    }

    /**
     * Reference data's answer for the register's day.
     *
     * @param subscriptions the subscriptions in force
     */
    private void answerWith(final JsonNode... subscriptions) {
        when(subscriptionsSource.subscriptionsOn(any(LocalDate.class), any(CallerIdentity.class)))
                .thenReturn(NowSubscriptionFixtures.answerOf(subscriptions));
    }

    /** The whole delivery path, over the pipeline the configuration under test assembled. */
    private YotResultsDistributionMessageListener listenerOver(final DistributionPipeline pipeline) {
        return new YotResultsDistributionMessageListener(
                new DistributionCommandParser(mapper),
                pipeline,
                new ProcessingMetrics(new SimpleMeterRegistry()),
                QueueHealthTestSupport.unwatched(),
                StoreGateTestSupport.open(),
                MAX_DELIVERY_COUNT);
    }

    /** The same listener, over a store that is not there. */
    private YotResultsDistributionMessageListener listenerWithNoStore(final DistributionPipeline pipeline) {
        return new YotResultsDistributionMessageListener(
                new DistributionCommandParser(mapper),
                pipeline,
                new ProcessingMetrics(new SimpleMeterRegistry()),
                QueueHealthTestSupport.unwatched(),
                StoreGateTestSupport.closed(),
                MAX_DELIVERY_COUNT);
    }

    private String validBody() {
        return """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "2020-01-20",
                  "sharedTime": "2020-06-01T10:00:00Z",
                  "eventType": "Hearing_Resulted"
                }
                """.formatted(requestId, HEARING_ID);
    }

    private String bodyNamingTheSharingUser() {
        return """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "2020-01-20",
                  "sharedTime": "2020-06-01T10:00:00Z",
                  "eventType": "Hearing_Resulted",
                  "userId": "%s"
                }
                """.formatted(requestId, HEARING_ID, CALLER_MARKER);
    }

    private String bodyWithExtraField(final String name, final String value) {
        return """
                {
                  "source": "RESULTS",
                  "requestId": "%s",
                  "hearingId": "%s",
                  "hearingDay": "2020-01-20",
                  "sharedTime": "2020-06-01T10:00:00Z",
                  "eventType": "Hearing_Resulted",
                  "%s": "%s"
                }
                """.formatted(requestId, HEARING_ID, name, value);
    }

    private static ServiceBusReceivedMessageContext deliveryOf(final String body) {
        return deliveryOf(body, "RESULTS:" + UUID.randomUUID());
    }

    private static ServiceBusReceivedMessageContext deliveryOf(
            final String body, final String messageId) {
        final ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(message.getBody()).thenReturn(BinaryData.fromString(body));
        when(message.getMessageId()).thenReturn(messageId);
        when(message.getLockToken()).thenReturn(UUID.randomUUID().toString());
        when(message.getDeliveryCount()).thenReturn(0L);

        final ServiceBusReceivedMessageContext context =
                mock(ServiceBusReceivedMessageContext.class);
        when(context.getMessage()).thenReturn(message);
        return context;
    }

    /** Every line this service wrote at INFO or above. */
    private static List<ILoggingEvent> processingLines(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> event.getLoggerName()
                        .startsWith("uk.gov.hmcts.cp.yotresultsdistribution"))
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                .toList();
    }

    /** Every {@code reason=} and {@code detail=} value this service wrote. */
    private static List<String> reasonsIn(final List<ILoggingEvent> events) {
        return events.stream()
                .filter(event -> event.getLoggerName()
                        .startsWith("uk.gov.hmcts.cp.yotresultsdistribution"))
                .map(ILoggingEvent::getFormattedMessage)
                .flatMap(line -> {
                    final Matcher matcher = REASON.matcher(line);
                    return matcher.results().map(result -> result.group(1));
                })
                .toList();
    }

    /** Reads the {@code reason=} and {@code detail=} tokens out of a delivery-path capture. */
    private static List<String> reasonsIn(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> event.getLoggerName()
                        .startsWith("uk.gov.hmcts.cp.yotresultsdistribution"))
                .map(ILoggingEvent::getFormattedMessage)
                .flatMap(line -> {
                    final Matcher matcher = REASON.matcher(line);
                    return matcher.results().map(result -> result.group(1));
                })
                .toList();
    }

    /** The whole settings object, carrying a broker credential nobody may write down. */
    private static YotResultsDistributionProperties credentialledWith(final String connectionString) {
        return new YotResultsDistributionProperties(
                OutputMode.RECORD,
                new YotResultsDistributionProperties.Consumer(true),
                new YotResultsDistributionProperties.Intake(Duration.ofMinutes(10)),
                new YotResultsDistributionProperties.Servicebus(
                        connectionString, null, "yotresultsdistribution.requests", 2, MAX_DELIVERY_COUNT,
                        Duration.ofMinutes(5), Duration.ofSeconds(60),
                        List.of(Duration.ofSeconds(15))),
                new YotResultsDistributionProperties.Claim(Duration.ofMinutes(5), RUN_DEADLINE),
                new YotResultsDistributionProperties.Notification(Duration.ofMinutes(15)),
                new YotResultsDistributionProperties.Store(Duration.ofSeconds(10)),
                new YotResultsDistributionProperties.Stub(PayloadFailureMode.NONE, StubFlagAnswer.ON),
                new YotResultsDistributionProperties.Payload(
                        PayloadSourceMode.STUB,
                        new YotResultsDistributionProperties.Redis("localhost", 6379, null, false,
                                "INT_", Duration.ofSeconds(5), Duration.ofSeconds(5)),
                        new YotResultsDistributionProperties.Fallback(3, Duration.ofSeconds(1),
                                Duration.ofSeconds(2), Duration.ofSeconds(5),
                                Duration.ofSeconds(10))),
                new YotResultsDistributionProperties.Results("http://localhost:8080", null),
                new YotResultsDistributionProperties.Referencedata(
                        SubscriptionsSourceMode.STUB, "http://localhost:8080", null, null, 3,
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
                        Duration.ofSeconds(10)),
                new YotResultsDistributionProperties.Progression(
                        "http://localhost:8080", null, null, 4, Duration.ofMillis(500),
                        Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(10)),
                new YotResultsDistributionProperties.Submission(true),
                // The downstream half is absent from this suite's subject: it logs nothing here,
                // and the credential these cases are about is the broker's.
                new YotResultsDistributionProperties.Fileservice(null, null, null),
                new YotResultsDistributionProperties.Endpoints(null, null, null, 3, Duration.ofSeconds(1),
                        Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10)),
                new YotResultsDistributionProperties.Email(
                        new YotResultsDistributionProperties.Templates(null)));
    }
}
