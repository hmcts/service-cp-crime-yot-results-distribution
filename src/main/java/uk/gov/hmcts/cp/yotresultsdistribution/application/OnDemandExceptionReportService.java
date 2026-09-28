package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.batch.RunCorrelation;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryWord;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsRefusedException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportRunOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportWindow;

/**
 * The exception report an operator asks for now, over a window they name.
 *
 * <p>{@code report-exceptions}'s body, less its argument parsing and less its terminal. The 07:00
 * run is the steady state; an incident does not wait for morning, and this answers the same
 * question over a window an operator names.
 *
 * <p><strong>The same service, not a second read path.</strong> It asks
 * {@link ExceptionReportService#build} exactly as {@code ExceptionReportJob} does and delivers
 * through {@link ExceptionReportService#deliver}, so a window read at 02:00 and the morning's
 * events cannot come to disagree about what an exception is, what its age is or which kind it
 * belongs to.
 *
 * <p><strong>It reads the cutover flag nowhere</strong>, exactly as the command did not (FR-022).
 * The report is this service's account of itself and is on no cutover circuit: a pod that renders
 * nothing still says what is wrong with what it recorded. There is deliberately no
 * {@link FeatureFlagReader} on this class, and a later phase that adds one would make the report a
 * second reader of the one lever.
 *
 * <p><strong>A run, and therefore a run id.</strong> {@link RunCorrelation#under} is opened here as
 * the command opened it: an on-demand report has no delivery identifiers and never can, so it
 * carries a correlation of its own onto every line the reads and the sinks write.
 */
public class OnDemandExceptionReportService {

    /** This service's own name for the one argument this call takes. */
    public static final String SINCE = "since";

    private static final Logger LOG =
            LoggerFactory.getLogger(OnDemandExceptionReportService.class);

    /** The shorthand a support engineer types, recognised before an ISO form is assumed. */
    private static final Pattern SHORTHAND = Pattern.compile("^(\\d+)([dhms])$");

    /** How each shorthand unit is read, stated once beside the pattern that recognises it. */
    private static final Map<String, ChronoUnit> UNITS = Map.of(
            "d", ChronoUnit.DAYS,
            "h", ChronoUnit.HOURS,
            "m", ChronoUnit.MINUTES,
            "s", ChronoUnit.SECONDS);

    /** The reads, and the delivery, both shared with the 07:00 run. */
    private final ExceptionReportService reporting;

    /** Every sink on this context, which is what {@code log always, e-mail when asked} selects. */
    private final List<ExceptionReportSink> sinks;

    /** The report's schedule, which is what "since the previous scheduled report" means. */
    private final String reportCron;

    /** The zone that schedule is read in. */
    private final String reportZone;

    /** Whether the e-mail output is switched on at all on this deployment. */
    private final boolean emailOutputEnabled;

    /** The one clock the window, the snapshot and the duration are taken from. */
    private final Clock clock;

    /**
     * Creates the on-demand report over the same collaborators the scheduled run holds.
     *
     * <p>The schedule and the e-mail switch are handed in as values rather than as the properties
     * record they come from, exactly as {@link ExceptionReportService}'s are: what this service
     * needs is a cron, a zone and a boolean, and taking the record would make the application layer
     * depend on the shape of a configuration file it has no business knowing.
     *
     * @param reportService the reads and the delivery, shared with the 07:00 run
     * @param reportSinks   every sink this context holds
     * @param cron          the report's schedule, which the default window is computed from
     * @param zone          the zone that schedule is read in
     * @param emailEnabled  whether the e-mail output is switched on at all
     * @param pods          the one clock this service reads time from
     */
    public OnDemandExceptionReportService(final ExceptionReportService reportService,
            final List<ExceptionReportSink> reportSinks, final String cron, final String zone,
            final boolean emailEnabled, final Clock pods) {

        this.reporting = reportService;
        this.sinks = List.copyOf(reportSinks);
        this.reportCron = cron;
        this.reportZone = zone;
        this.emailOutputEnabled = emailEnabled;
        this.clock = pods;
    }

    /**
     * Builds the report over the window asked for and delivers it to the sinks asked for.
     *
     * @param since what the caller named the window from, or {@code null} for the default window
     * @param email whether the e-mail sink is asked as well as the log one
     * @return the report, what each sink did, the run id and the duration
     * @throws OperationsRefusedException where the window will not read, where the window has no
     *         width, where e-mail was asked for and is unavailable, or where the reads could not
     *         be taken at all
     */
    public OnDemandExceptionReport report(final String since, final boolean email) {
        return RunCorrelation.under(() -> asked(since, email));
    }

    /**
     * The command's {@code asked}, less the argument parsing and the terminal.
     *
     * <p>The order is the command's and is load-bearing: e-mail switched off is refused
     * <strong>before</strong> a window is computed, because that refusal is a deploy to fix and
     * has nothing to do with what was typed; and the missing-sink refusal comes after the window,
     * because it is about what this context holds rather than about the request.
     */
    private OnDemandExceptionReport asked(final String since, final boolean email) {
        if (email && !emailOutputEnabled) {
            LOG.warn("An on-demand exception report asked for the e-mail output, which is switched "
                    + "off on this deployment. reason={}",
                    OperationsReason.EMAIL_OUTPUT_DISABLED.wire());
            throw new OperationsRefusedException(OperationsReason.EMAIL_OUTPUT_DISABLED);
        }
        final Instant startedAt = clock.instant();
        final ReportWindow window = windowFrom(since, startedAt);
        final Optional<ExceptionReportSink> emailSink = sinkNamed(ReportSinkName.EMAIL);
        if (email && emailSink.isEmpty()) {
            LOG.warn("An on-demand exception report asked for the e-mail output, which is on and "
                    + "has no sink behind it. reason={}",
                    OperationsReason.EMAIL_OUTPUT_NOT_WIRED.wire());
            throw new OperationsRefusedException(OperationsReason.EMAIL_OUTPUT_NOT_WIRED);
        }
        final List<ExceptionReportSink> asked = new ArrayList<>();
        sinkNamed(ReportSinkName.LOG).ifPresent(asked::add);
        if (email) {
            emailSink.ifPresent(asked::add);
        }
        return reported(window, asked, email, startedAt);
    }

    /**
     * The window, from what the caller named or from the previous scheduled report.
     *
     * <p>The two failures are told apart deliberately. A window the caller typed that will not read
     * is <strong>their</strong> argument and is named, never quoted; a default window that cannot
     * be computed is this service failing to produce a report, and telling an operator that their
     * {@code since} was unreadable would send them looking for an argument they never gave.
     */
    // PMD.AvoidCatchingGenericException: every reader a window goes through refuses in its own type
    // - DateTimeParseException from the two parsers, IllegalArgumentException from the rules below
    // them and from ReportWindow itself, DateTimeException from arithmetic on an absurd count - and
    // all of them mean the one thing a caller can act on: this is not a window. Nothing is
    // swallowed; the refusal names the argument and the class that refused it, and writes down
    // neither the message nor the token (constitution Principle VII).
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    private ReportWindow windowFrom(final String since, final Instant startedAt) {
        if (since == null || since.isBlank()) {
            try {
                return ReportWindow.sinceLastScheduledRun(reportCron, reportZone, startedAt);
            } catch (RuntimeException notAWindow) {
                throw couldNotBuild(notAWindow);
            }
        }
        try {
            return new ReportWindow(readBack(since, startedAt), startedAt);
        } catch (RuntimeException notAWindow) {
            LOG.warn("An on-demand exception report named a window that will not read. "
                    + "argument={} cause={}", SINCE, notAWindow.getClass().getName());
            throw new OperationsRefusedException(OperationsReason.UNREADABLE_ARGUMENT, SINCE,
                    notAWindow);
        }
    }

    /**
     * The command's {@code reported}, less the table and the run line.
     *
     * <p>"Not every sink took it" is carried back as the run's outcome rather than thrown: the
     * reads happened, the report exists and the rest is a resend. Only a report that could not be
     * built at all leaves as a refusal.
     */
    // PMD.AvoidCatchingGenericException: a read that would not answer arrives as the store's own
    // unchecked type and a projection that has drifted from its table as IllegalArgumentException;
    // both mean the same thing here - this call could not produce a report. Nothing is swallowed:
    // the line below says it happened and the refusal leaves under a bounded code.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private OnDemandExceptionReport reported(final ReportWindow window,
            final List<ExceptionReportSink> asked, final boolean email, final Instant startedAt) {

        final String runId = RunCorrelation.current();
        try {
            final ExceptionReport report = reporting.build(window, runId);
            final List<DeliveryOutcome> delivered = reporting.deliver(report, asked);
            return new OnDemandExceptionReport(runId, report, delivered,
                    words(delivered, email), ReportRunOutcome.from(delivered),
                    Duration.between(startedAt, clock.instant()).toMillis());
        } catch (RuntimeException notBuilt) {
            throw couldNotBuild(notBuilt);
        }
    }

    /**
     * The bounded word each of the two sinks gets, whether it was asked or not.
     *
     * @param delivered what each asked sink answered
     * @param email     whether the e-mail sink was asked
     * @return one word per sink
     */
    private Map<ReportSinkName, String> words(final List<DeliveryOutcome> delivered,
            final boolean email) {

        final Map<ReportSinkName, String> said = new EnumMap<>(ReportSinkName.class);
        said.put(ReportSinkName.LOG, said(ReportSinkName.LOG, delivered, true));
        said.put(ReportSinkName.EMAIL, said(ReportSinkName.EMAIL, delivered, email));
        return said;
    }

    /**
     * One sink's word, over what this context holds and what this call asked.
     *
     * @param sink      which sink
     * @param delivered what each asked sink answered
     * @param asked     whether this call asked it
     * @return the bounded word
     */
    private String said(final ReportSinkName sink, final List<DeliveryOutcome> delivered,
            final boolean asked) {

        final boolean here = sinkNamed(sink).isPresent();
        return DeliveryWord.said(sink, delivered, here, here && asked);
    }

    /**
     * The one failure that is not a refusal about an argument: no report at all.
     *
     * @param notBuilt what refused, named by class and never by message
     * @return the refusal to throw
     */
    private static OperationsRefusedException couldNotBuild(final RuntimeException notBuilt) {
        LOG.error("The exception report an operator asked for could not be produced, so nothing "
                + "was answered about what is wrong. cause={}", notBuilt.getClass().getName());
        return new OperationsRefusedException(OperationsReason.REPORT_NOT_BUILT, null,
                Map.of(), notBuilt);
    }

    /**
     * The sink of one name, where this context holds one.
     *
     * @param name which sink
     * @return the sink, or empty
     */
    private Optional<ExceptionReportSink> sinkNamed(final ReportSinkName name) {
        return sinks.stream().filter(sink -> sink.name() == name).findFirst();
    }

    /**
     * A window start from the three forms an operator may type.
     *
     * @param typed what the caller named
     * @param now   the instant the run started, which the relative forms are taken back from
     * @return the instant the window opens at
     */
    // PMD.OnlyOneReturn: three forms tried in a stated order, each answered where it parses; one
    // exit would need a sentinel standing in for "not this form", which is the null this avoids.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static Instant readBack(final String typed, final Instant now) {
        final Optional<Instant> instant = asInstant(typed);
        if (instant.isPresent()) {
            return instant.get();
        }
        final Optional<Duration> duration = asDuration(typed);
        if (duration.isPresent()) {
            return now.minus(positive(duration.get()));
        }
        final Matcher shorthand = SHORTHAND.matcher(typed);
        if (!shorthand.matches()) {
            throw new IllegalArgumentException(
                    "a window is an instant, an ISO-8601 duration or <n>d, <n>h, <n>m or <n>s");
        }
        return now.minus(positive(Duration.of(Long.parseLong(shorthand.group(1)),
                UNITS.get(shorthand.group(2)))));
    }

    /**
     * The typed window as an ISO instant, where it is one.
     *
     * @param typed what the caller named
     * @return the instant, or empty where this is not that form
     */
    private static Optional<Instant> asInstant(final String typed) {
        Optional<Instant> read;
        try {
            read = Optional.of(Instant.parse(typed));
        } catch (DateTimeParseException notAnInstant) {
            read = Optional.empty();
        }
        return read;
    }

    /**
     * The typed window as an ISO-8601 duration, where it is one.
     *
     * @param typed what the caller named
     * @return the duration, or empty where this is not that form
     */
    private static Optional<Duration> asDuration(final String typed) {
        Optional<Duration> read;
        try {
            read = Optional.of(Duration.parse(typed));
        } catch (DateTimeParseException notADuration) {
            read = Optional.empty();
        }
        return read;
    }

    /**
     * A width a window can have.
     *
     * @param read the width the caller named
     * @return the same width
     */
    private static Duration positive(final Duration read) {
        if (read.isZero() || read.isNegative()) {
            throw new IllegalArgumentException(
                    "a window has to have width, and a negative one has not happened yet");
        }
        return read;
    }
}
