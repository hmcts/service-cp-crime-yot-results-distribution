package uk.gov.hmcts.cp.yotresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.search.RequiredSearch;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportDeliveryReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportWindow;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterNotificationRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PersonalDataMarkers;

/**
 * One built report handed to the sinks its caller chose.
 *
 * <p>The two outputs answer the same question to two audiences and must not be able to take each
 * other down. So the sinks come from the caller - the 07:00 run delivers to every sink on the
 * context, the command delivers to the log sink always and adds the e-mail sink only under
 * {@code --email} - and the service folds what they answer rather than stopping at the first one
 * that could not.
 *
 * <p><strong>Nothing is retried and nothing leaves.</strong> A report is not a message: it is
 * regenerated in full by the next run or on demand, so a retry would re-send a list support is
 * about to receive again anyway. A sink that refuses is therefore classified into a
 * {@link DeliveryOutcome} with a bounded {@link ReportDeliveryReason}, counted, and said once at
 * WARN - naming the caught failure by class, because its message belongs to whatever library raised
 * it and is exactly where an address or a connection string turns up.
 */
@DisplayName("a report handed to its sinks")
class ExceptionReportDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T06:00:00Z");

    private static final String RUN_ID = "run-4b19c7e0";

    private static final ExceptionReport REPORT = ExceptionReport.whole(RUN_ID,
            new ReportWindow(NOW.minus(Duration.ofDays(1)), NOW), NOW, List.of());

    /** Three recipients, one refused, is how the e-mail sink answers a partial delivery. */
    private static final int ACCEPTED = 2;

    private static final int REFUSED = 1;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ExceptionReportService service;

    @BeforeEach
    void aServiceThatOnlyHasToDeliver() {
        service = new ExceptionReportService(
                mock(ProcessedRequestRepository.class),
                mock(RegisterBatchRepository.class),
                mock(RegisterNotificationRepository.class),
                mock(RegisterStore.class),
                Duration.ofMinutes(30), Duration.ofMinutes(10), Duration.ofMinutes(15), 5000,
                "0 0 18 * * MON-FRI", "Europe/London",
                new ProcessingMetrics(registry), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * Who is asked, and what comes back when they all take it.
     */
    @Nested
    @DisplayName("the sinks the caller chose")
    class WhoIsAsked {

        @Test
        void the_sinks_delivered_to_are_the_ones_the_caller_passed() {
            final RecordingSink log = RecordingSink.taking(ReportSinkName.LOG);
            final RecordingSink email = RecordingSink.taking(ReportSinkName.EMAIL);

            final List<DeliveryOutcome> outcomes = service.deliver(REPORT, List.of(log, email));

            assertThat(outcomes)
                    .as("the caller chooses: the 07:00 run delivers to every sink on the context, "
                            + "and the command adds the e-mail sink only under --email")
                    .extracting(DeliveryOutcome::sink)
                    .containsExactly(ReportSinkName.LOG, ReportSinkName.EMAIL);
            assertThat(log.delivered).containsExactly(REPORT);
            assertThat(email.delivered).containsExactly(REPORT);
        }

        @Test
        void delivering_to_the_log_sink_alone_answers_one_outcome() {
            final List<DeliveryOutcome> outcomes =
                    service.deliver(REPORT, List.of(RecordingSink.taking(ReportSinkName.LOG)));

            assertThat(outcomes)
                    .as("a command run without --email asks one sink, and a run line that claimed "
                            + "anything about the other would be claiming an outcome nobody had")
                    .singleElement()
                    .isEqualTo(DeliveryOutcome.delivered(ReportSinkName.LOG));
        }

        @Test
        void one_of_two_sinks_delivered_is_partially_delivered() {
            final RecordingSink log = RecordingSink.taking(ReportSinkName.LOG);
            final ExceptionReportSink email = answering(ReportSinkName.EMAIL,
                    new DeliveryOutcome(ReportSinkName.EMAIL, DeliveryStatus.PARTIALLY_DELIVERED,
                            ReportDeliveryReason.SEND_REFUSED, ACCEPTED, REFUSED));

            final List<DeliveryOutcome> outcomes = service.deliver(REPORT, List.of(log, email));

            assertThat(outcomes)
                    .as("a report that reached one of its two audiences is partially delivered "
                            + "and not a failure, and the per-recipient counts are the sink's own "
                            + "answer carried through rather than a number this service invents")
                    .containsExactly(
                            DeliveryOutcome.delivered(ReportSinkName.LOG),
                            new DeliveryOutcome(ReportSinkName.EMAIL,
                                    DeliveryStatus.PARTIALLY_DELIVERED,
                                    ReportDeliveryReason.SEND_REFUSED, ACCEPTED, REFUSED));
        }
    }

    /**
     * What a sink that could not deliver costs: a classification, a count and one line.
     */
    @Nested
    @DisplayName("a sink that could not deliver")
    class WhenASinkRefuses {

        @Test
        void a_sink_that_fails_is_recorded_with_a_bounded_reason_and_the_other_sink_still_runs() {
            final RecordingSink email = RecordingSink.taking(ReportSinkName.EMAIL);

            final List<DeliveryOutcome> outcomes = service.deliver(REPORT,
                    List.of(throwing(ReportSinkName.LOG), email));

            assertThat(outcomes)
                    .as("the two outputs answer the same question to two audiences and must not "
                            + "be able to take each other down (FR-007)")
                    .hasSize(2);
            assertThat(outcomes.getFirst())
                    .satisfies(outcome -> {
                        assertThat(outcome.sink()).isEqualTo(ReportSinkName.LOG);
                        assertThat(outcome.status()).isEqualTo(DeliveryStatus.NOT_DELIVERED);
                        assertThat(outcome.reason())
                                .as("a bounded code and never a sentence: a reason an operator "
                                        + "pastes into a ticket has to have a fixed meaning")
                                .isEqualTo(ReportDeliveryReason.LOG_WRITE_FAILED);
                    });
            assertThat(email.delivered)
                    .as("and the sink behind the one that refused is still asked")
                    .containsExactly(REPORT);
        }

        @Test
        void both_sinks_failing_is_not_delivered_and_still_raises_nothing_out_of_the_service() {
            final List<DeliveryOutcome> outcomes = new ArrayList<>();

            assertThatCode(() -> outcomes.addAll(service.deliver(REPORT,
                    List.of(throwing(ReportSinkName.LOG), throwing(ReportSinkName.EMAIL)))))
                    .as("a delivery that failed is a recorded state, not an exception: the run "
                            + "line and the counter are how it is seen, and a throw here would "
                            + "lose the outcomes of every sink already asked")
                    .doesNotThrowAnyException();
            assertThat(outcomes)
                    .extracting(DeliveryOutcome::status)
                    .containsExactly(DeliveryStatus.NOT_DELIVERED, DeliveryStatus.NOT_DELIVERED);
        }

        @Test
        void a_failed_sink_is_never_retried() {
            final CountingSink refusing = new CountingSink(ReportSinkName.EMAIL);

            service.deliver(REPORT, List.of(refusing));

            assertThat(refusing.asked)
                    .as("a report is regenerated in full by the next run or on demand, so a retry "
                            + "would re-send a list support is about to receive again anyway")
                    .isEqualTo(1);
        }

        @Test
        void a_sink_whose_name_cannot_be_read_is_still_recorded_and_the_others_still_run() {
            final RecordingSink log = RecordingSink.taking(ReportSinkName.LOG);
            final ExceptionReportSink email = new BreakingSink(ReportSinkName.EMAIL);

            final List<DeliveryOutcome> outcomes = new ArrayList<>();
            assertThatCode(() -> outcomes.addAll(service.deliver(REPORT, List.of(email, log))))
                    .as("a sink names itself off the thing it delivers through, so the sink that "
                            + "has just broken is exactly the one that may not be able to answer "
                            + "its own name any more. Asking it inside the catch makes the one "
                            + "delivery nobody planned for the one that escapes - taking the "
                            + "outcomes of every sink already asked with it")
                    .doesNotThrowAnyException();

            assertThat(outcomes)
                    .as("its name was readable when it was asked, which is when it is read")
                    .containsExactly(
                            new DeliveryOutcome(ReportSinkName.EMAIL,
                                    DeliveryStatus.NOT_DELIVERED, ReportDeliveryReason.SEND_FAILED,
                                    0, 0),
                            DeliveryOutcome.delivered(ReportSinkName.LOG));
            assertThat(log.delivered)
                    .as("and the sink behind it is still asked (FR-007)")
                    .containsExactly(REPORT);
        }

        @Test
        void a_caught_failure_is_named_by_class_and_never_by_message() {
            try (CapturedLog log = CapturedLog.capturing(ExceptionReportService.class)) {
                service.deliver(REPORT, List.of(throwing(ReportSinkName.EMAIL)));

                assertThat(log.renderings())
                        .as("a sink that refuses is said once, so the absorption is visible")
                        .isNotEmpty();
                assertThat(log.renderings())
                        .as("a message belongs to whatever library raised it, and is exactly "
                                + "where an address or a connection string turns up")
                        .noneMatch(line -> line.contains(PersonalDataMarkers.RECIPIENT_EMAIL));
                assertThat(log.renderings())
                        .as("what is written down is the class of what refused, which is what a "
                                + "support engineer needs and all of what they may have")
                        .anyMatch(line -> line.contains(IllegalStateException.class.getName()));
            }
        }
    }

    /**
     * What the instruments say about a delivery, whichever way it went.
     */
    @Nested
    @DisplayName("what the deliveries counter records")
    class WhatIsCounted {

        @Test
        void every_delivery_outcome_is_counted_on_the_deliveries_counter() {
            service.deliver(REPORT, List.of(
                    RecordingSink.taking(ReportSinkName.LOG),
                    throwing(ReportSinkName.EMAIL)));

            assertThat(counted(ReportSinkName.LOG, DeliveryStatus.DELIVERED))
                    .as("a path that drops something moves a counter; so does one that does not")
                    .isEqualTo(1.0);
            assertThat(counted(ReportSinkName.EMAIL, DeliveryStatus.NOT_DELIVERED))
                    .as("and an absorbed refusal is counted, which is what makes it visible "
                            + "without reading the log index")
                    .isEqualTo(1.0);
        }

        private double counted(final ReportSinkName sink, final DeliveryStatus outcome) {
            final RequiredSearch search = registry.get(ProcessingMetrics.EXCEPTION_REPORT_DELIVERIES)
                    .tag("sink", label(sink))
                    .tag(ProcessingMetrics.OUTCOME_TAG, label(outcome));
            return search.counter().count();
        }

        private String label(final Enum<?> value) {
            return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** A sink that takes whatever it is given, and remembers what that was. */
    private static final class RecordingSink implements ExceptionReportSink {

        private final ReportSinkName sinkName;

        private final List<ExceptionReport> delivered = new ArrayList<>();

        private RecordingSink(final ReportSinkName sinkName) {
            this.sinkName = sinkName;
        }

        static RecordingSink taking(final ReportSinkName name) {
            return new RecordingSink(name);
        }

        @Override
        public ReportSinkName name() {
            return sinkName;
        }

        @Override
        public DeliveryOutcome deliver(final ExceptionReport report) {
            delivered.add(report);
            return DeliveryOutcome.delivered(sinkName);
        }
    }

    /** A sink that refuses, and counts how many times it was asked to. */
    private static final class CountingSink implements ExceptionReportSink {

        private final ReportSinkName sinkName;

        private int asked;

        private CountingSink(final ReportSinkName sinkName) {
            this.sinkName = sinkName;
        }

        @Override
        public ReportSinkName name() {
            return sinkName;
        }

        @Override
        public DeliveryOutcome deliver(final ExceptionReport report) {
            asked++;
            throw new IllegalStateException(
                    "the far end refused " + PersonalDataMarkers.RECIPIENT_EMAIL);
        }
    }

    /**
     * A sink that answers its name until it breaks, and cannot answer it afterwards.
     *
     * <p>Which is what a sink whose name comes from the client it delivers through looks like once
     * that client is gone: it named itself when it was asked, and the thing that would name it now
     * is the thing that just failed.
     */
    private static final class BreakingSink implements ExceptionReportSink {

        private final ReportSinkName sinkName;

        private boolean broken;

        BreakingSink(final ReportSinkName sinkName) {
            this.sinkName = sinkName;
        }

        @Override
        public ReportSinkName name() {
            if (broken) {
                throw new IllegalStateException(
                        "the client this sink names itself from has been closed");
            }
            return sinkName;
        }

        @Override
        public DeliveryOutcome deliver(final ExceptionReport report) {
            broken = true;
            throw new IllegalStateException(
                    "the far end refused " + PersonalDataMarkers.RECIPIENT_EMAIL);
        }
    }

    /**
     * A sink that breaks, with a recipient's address in the message it broke with.
     *
     * @param name which sink it is
     * @return the sink
     */
    private static ExceptionReportSink throwing(final ReportSinkName name) {
        return new CountingSink(name);
    }

    /**
     * A sink that answers an outcome of its own rather than the plain delivered one.
     *
     * @param name    which sink it is
     * @param outcome what it answers
     * @return the sink
     */
    private static ExceptionReportSink answering(final ReportSinkName name,
            final DeliveryOutcome outcome) {
        return new ExceptionReportSink() {

            @Override
            public ReportSinkName name() {
                return name;
            }

            @Override
            public DeliveryOutcome deliver(final ExceptionReport report) {
                return outcome;
            }
        };
    }
}
