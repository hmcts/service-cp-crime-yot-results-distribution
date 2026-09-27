package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DeliveryStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionEntry;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionKind;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ExceptionReport;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailedNotification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.LastScheduledRun;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedRequestSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedRegisterSummary;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportDeliveryReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportSinkName;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReportWindow;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.yotresultsdistribution.persistence.RegisterNotificationRepository;

/**
 * What went wrong, asked of the store and answered as one report.
 *
 * <p>Eight reads and no writes (FR-014). Three of them are bounded by the window - at
 * <strong>both</strong> ends, the start inclusive and the end exclusive - so a report is a statement
 * about a closed period rather than a growing ledger and consecutive reports cannot both name the
 * same row; the two late kinds deliberately are not bounded by it,
 * because a request stuck since Friday is late on Monday morning whether or not it arrived over the
 * weekend, and a window filter would make the longest-running problem the first one to disappear.
 *
 * <p><strong>Every age is the one its own statement computed.</strong> Each of the four projections
 * carries an {@code age_seconds} measured by the database from the column that stage is timed off,
 * and nothing here subtracts a stored timestamp from a reading of this JVM's clock - that is the
 * cross-clock comparison V1's single-time-authority rule forbids. The clock is read once, for the
 * snapshot and for the three cut-offs the reads are asked about, which are boundaries this caller
 * chose rather than comparisons between two clocks.
 *
 * <p>The <strong>run id is an argument</strong> rather than something this class reads for itself.
 * Whoever opened the correlation - the 07:00 job, or the operations command - knows it, so
 * {@code build(window, runId)} takes it and the application layer imports no MDC. That is what
 * makes FR-011 true on the command's path, where nothing else would have opened a run at all.
 *
 * <p>Delivery is the other half, and it is deliberately not the same method. The run delivers to
 * every sink on the context; the command delivers to the log sink always and the e-mail sink only
 * under {@code --email}. One method that fanned out to whatever was wired could not express the
 * second without this class learning what a command-line flag is.
 */
public class ExceptionReportService {

    private static final Logger LOG = LoggerFactory.getLogger(ExceptionReportService.class);

    /**
     * Oldest first, and then the same order twice.
     *
     * <p>Age is what an operator reads down, and it settles almost every pair. What it does not
     * settle is two things that went wrong at the same moment - two batches of one night, a stage
     * and a failure timed off the same column - and a sort that stopped at the age would leave
     * those in whatever order the eight reads happened to be made in. That is an order this class
     * chose by accident: the never-batched registers are read after the dead batches, so a
     * stranded register would sort below a failed batch of the same age for no reason anybody
     * could state, and two mornings of the same report could not be diffed against each other.
     *
     * <p>So the kind breaks the tie first, in the order the enumeration declares - which reads
     * down the pipeline, intake before batches before notifications - and then the identifier the
     * entry is named by, which is the only thing left that is the entry's own.
     */
    private static final Comparator<ExceptionEntry> OLDEST_FIRST =
            Comparator.comparingLong(ExceptionEntry::ageSeconds).reversed()
                    .thenComparing(ExceptionEntry::kind)
                    .thenComparing(ExceptionReportService::identifierOf,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * The four stages a BATCH_LATE entry can be stuck at, as bounded codes.
     *
     * <p>A closed set of four, written here because the kind is one and the stage is what an
     * operator has to act on: a batch waiting to be asked about is a different morning's work from
     * one systemdocgenerator was asked about and never answered. They are codes rather than
     * sentences for the reason every reason on this report is - {@code reason} is a parsed slot,
     * and what goes in one comes from a vocabulary.
     */
    private static final String AWAITING_BATCH = "awaiting-batch";

    private static final String AWAITING_RENDER_REQUEST = "awaiting-render-request";

    private static final String AWAITING_RENDER = "awaiting-render";

    private static final String AWAITING_NOTIFICATION = "awaiting-notification";

    /** What a register that no batch was ever assembled for is, as its own state's name. */
    private static final String RECORDED = "RECORDED";

    private final ProcessedRequestRepository requests;

    private final RegisterBatchRepository batches;

    private final RegisterNotificationRepository notifications;

    private final RegisterStore registers;

    private final Duration requestTerminalWithin;

    private final Duration batchGeneratedWithin;

    private final Duration notifiedWithin;

    /** How many exceptions one report may carry before the rest are dropped and counted. */
    private final int maxEntries;

    private final String generationCron;

    private final String generationZone;

    private final ProcessingMetrics metrics;

    private final Clock clock;

    /**
     * Holds the four readers, the three limits, the generation schedule and the clock.
     *
     * @param requests              the intake half's processed log
     * @param batches               the downstream half's batches
     * @param notifications         the e-mails the batches were told by
     * @param registers             the recorded registers, through the store's own port
     * @param requestTerminalWithin how long a request may stay unfinished before it is late
     * @param batchGeneratedWithin  how long a batch may stay unrendered before it is late
     * @param notifiedWithin        how long a rendered batch may go untold before it is late
     * @param maxEntries            how many exceptions one report may carry
     * @param generationCron        the generation schedule, which decides what "left behind" means
     * @param generationZone        the zone that schedule is read in
     * @param metrics               the instrument facade
     * @param clock                 the one clock the snapshot is taken from
     */
    public ExceptionReportService(
            final ProcessedRequestRepository requests,
            final RegisterBatchRepository batches,
            final RegisterNotificationRepository notifications,
            final RegisterStore registers,
            final Duration requestTerminalWithin,
            final Duration batchGeneratedWithin,
            final Duration notifiedWithin,
            final int maxEntries,
            final String generationCron,
            final String generationZone,
            final ProcessingMetrics metrics,
            final Clock clock) {
        this.requests = requests;
        this.batches = batches;
        this.notifications = notifications;
        this.registers = registers;
        this.requestTerminalWithin = requestTerminalWithin;
        this.batchGeneratedWithin = batchGeneratedWithin;
        this.notifiedWithin = notifiedWithin;
        this.maxEntries = maxEntries;
        this.generationCron = generationCron;
        this.generationZone = generationZone;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Everything wrong at one moment, over one window, oldest first.
     *
     * <p><strong>One request is one problem.</strong> The two intake predicates are meant to
     * partition on status - {@code FAILED} is terminal and {@code RECEIVED} and {@code RETRYING}
     * are not - but they are two statements taken a moment apart against a log the pipeline is
     * still writing to, so a request that fails between them comes back from both, and so does one
     * whose status column disagrees with itself. The failure wins: a parked request is not going to
     * finish on its own, and it is the outcome an operator acts on (FR-013). The fold is here
     * rather than in the statements because no single statement can see the other's answer.
     *
     * <p>A read that cannot be taken <strong>leaves</strong>, and no partial report is composed
     * from the reads that could. The one absorbed refusal on this path is a sink's
     * ({@link #deliver}): a sink that refuses has a built report to be classified against, while a
     * read that refuses leaves an all-clear about the half nobody could see.
     *
     * @param window what was asked for
     * @param runId  the correlation the caller opened
     * @return the report, stamped with the run id it was given
     */
    public ExceptionReport build(final ReportWindow window, final String runId) {
        final Instant snapshotAt = clock.instant();
        final List<ExceptionEntry> entries = new ArrayList<>();

        final Set<String> reported = new HashSet<>();
        for (final ProcessedRequestSummary failed
                : requests.failedBetween(window.from(), window.to())) {
            reported.add(identityOf(failed));
            entries.add(intake(ExceptionKind.REQUEST_FAILED, failed, failed.failureReason()));
        }
        for (final ProcessedRequestSummary late
                : requests.nonTerminalOlderThan(snapshotAt.minus(requestTerminalWithin))) {
            if (reported.add(identityOf(late))) {
                entries.add(intake(ExceptionKind.REQUEST_LATE, late, null));
            }
        }
        for (final BatchException late : batches.latePending(snapshotAt.minus(batchGeneratedWithin))) {
            entries.add(batch(ExceptionKind.BATCH_LATE, late, AWAITING_RENDER_REQUEST));
        }
        for (final BatchException late
                : batches.lateGenerating(snapshotAt.minus(batchGeneratedWithin))) {
            entries.add(batch(ExceptionKind.BATCH_LATE, late, AWAITING_RENDER));
        }
        for (final BatchException late : batches.lateGenerated(snapshotAt.minus(notifiedWithin))) {
            entries.add(batch(ExceptionKind.BATCH_LATE, late, AWAITING_NOTIFICATION));
        }
        for (final BatchException dead
                : batches.failedBetween(window.from(), window.to())) {
            entries.add(batch(kindOf(dead), dead, nameOf(dead)));
        }
        for (final RecordedRegisterSummary stranded : registers.recordedUnbatchedBefore(
                LastScheduledRun.before(generationCron, generationZone, snapshotAt))) {
            entries.add(unbatched(stranded));
        }
        for (final FailedNotification refused
                : notifications.failedBetween(window.from(), window.to())) {
            entries.add(notification(refused));
        }

        entries.sort(OLDEST_FIRST);
        final ExceptionReport report = capped(runId, window, snapshotAt, entries);
        report.counts().forEach(metrics::exceptionsReported);
        return report;
    }

    /**
     * The report, with the recurring kinds capped and every failure carried whatever the count.
     *
     * <p>A morning can be arbitrarily bad, and the log sink writes one event per entry: without a
     * ceiling a single outage is a write that outlives the run's own lock, and the events of the
     * morning anybody actually needed are behind the fifty thousand nobody read.
     *
     * <p><strong>The ceiling bounds the two late kinds and nothing else.</strong> They are read
     * against a cut-off rather than a window, so an entry this run leaves out is read again by the
     * next one, older - dropping it costs a morning's place in a list and nothing more. The three
     * failure kinds are read over a half-open window aligned to the schedule, so each row belongs
     * to exactly one run's window and no run ever reads that window again: a failure dropped here
     * would be a failure no output states anywhere, ever, and it would be dropped on the longest
     * morning of the year, which is the morning it mattered. A cap that can do that is the silent
     * loss this whole feature exists to end, so it is not allowed to do it -
     * {@link ExceptionKind#recursEveryRun()} is where the two are told apart.
     *
     * <p><strong>The counts are taken before the cap and never after it.</strong> A count that
     * shrank with the list would make the worst morning of the year read as a quieter one, which is
     * the exact reading this feature exists to make impossible. So the summary's five numbers are
     * what the reads found and the {@code truncated} number says how many <em>late</em> entries no
     * output wrote: a reader who finds fewer events than the counts imply is told how many are
     * missing rather than left to wonder whether a sink broke.
     *
     * <p>Oldest first is the half kept, because the oldest exception has been wrong longest and is
     * where a support engineer starts. The tail is not lost: it is the next run's, and it is
     * counted here.
     *
     * @param runId      the correlation the caller opened
     * @param window     what was asked for
     * @param snapshotAt when the reads were taken
     * @param entries    everything found, already sorted oldest first
     * @return the report, truncated where it had to be
     */
    private ExceptionReport capped(final String runId, final ReportWindow window,
            final Instant snapshotAt, final List<ExceptionEntry> entries) {

        final List<ExceptionEntry> kept = new ArrayList<>(entries.size());
        int recurring = 0;
        int dropped = 0;
        for (final ExceptionEntry entry : entries) {
            if (entry.kind().recursEveryRun()) {
                if (recurring < maxEntries) {
                    recurring++;
                    kept.add(entry);
                } else {
                    dropped++;
                }
            } else {
                kept.add(entry);
            }
        }

        final ExceptionReport report;
        if (dropped == 0) {
            report = ExceptionReport.whole(runId, window, snapshotAt, kept);
        } else {
            LOG.warn("The morning report found more of the recurring kinds than one report carries, "
                            + "so the oldest were kept and the rest are the next run's. Every "
                            + "failure is carried whatever the count. run_id={} late_kept={} "
                            + "late_dropped={}",
                    runId, maxEntries, dropped);
            report = new ExceptionReport(runId, window, snapshotAt, kept, dropped,
                    ExceptionReport.countsOf(entries));
        }
        return report;
    }

    /**
     * Hands one report to the sinks the caller chose, and says how each of them went.
     *
     * <p>Every sink is asked, whatever the one before it answered: the two outputs answer the same
     * question to two audiences and must not be able to take each other down (FR-007). Nothing is
     * retried - a report is regenerated in full by the next run or on demand, so a retry would
     * re-send a list support is about to receive again anyway - and nothing leaves, because a throw
     * here would lose the outcomes of every sink already asked.
     *
     * @param report the report to deliver
     * @param sinks  the sinks this caller delivers to
     * @return one outcome per sink, in the order they were asked
     */
    public List<DeliveryOutcome> deliver(final ExceptionReport report,
            final Collection<ExceptionReportSink> sinks) {
        final List<DeliveryOutcome> outcomes = new ArrayList<>(sinks.size());
        for (final ExceptionReportSink sink : sinks) {
            final DeliveryOutcome outcome = askedOf(sink, report);
            metrics.exceptionReportDelivery(outcome.sink(), outcome.status());
            outcomes.add(outcome);
        }
        return List.copyOf(outcomes);
    }

    /**
     * One sink's answer, or the classification of a sink that could not give one.
     *
     * <p>The port's contract is that a sink answers rather than throws, so a throw reaching here is
     * a broken sink and not a refused delivery. It is caught only to classify: the outcome is
     * recorded against the sink's own name with the bounded code for its write having failed, and
     * the line names the caught failure by <strong>class</strong>, because its message belongs to
     * whatever library raised it and is exactly where an address or a connection string turns up.
     *
     * <p><strong>The name is read once, before the sink is asked.</strong> A sink names itself off
     * the thing it delivers through, so the sink that has just broken is exactly the one that may
     * no longer be able to answer. Read inside the catch, that second question makes the one
     * delivery nobody planned for the one that leaves this class - taking with it the outcomes of
     * every sink already asked, which is the loss the catch exists to prevent. Read here it is
     * asked while the sink is still whole, and the classification always has a name to carry.
     *
     * @param sink   the sink to ask
     * @param report the report to hand it
     * @return how it went
     */
    // PMD.AvoidCatchingGenericException and PMD.OnlyOneReturn: as stated in this method's javadoc
    // - the port's contract is that a sink answers rather than throws, so a throw reaching here is
    // any broken sink and the catch cannot be narrower than that; and both exits answer the same
    // question, one in the sink's own words and one in this service's.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    private DeliveryOutcome askedOf(final ExceptionReportSink sink, final ExceptionReport report) {
        final ReportSinkName named = sink.name();
        try {
            return sink.deliver(report);
        } catch (RuntimeException broken) {
            LOG.warn("The exception report could not be handed to the {} sink, which broke with a "
                            + "{}. The report stands and every other sink is still asked.",
                    named, broken.getClass().getName());
            return new DeliveryOutcome(named, DeliveryStatus.NOT_DELIVERED,
                    brokenSinkReason(named), 0, 0);
        }
    }

    /**
     * The bounded code for a sink that broke rather than answered, one per sink.
     *
     * <p>A switch expression so that a third sink cannot be added without deciding what its own
     * breaking is called.
     *
     * @param sink which sink broke
     * @return the bounded reason
     */
    private static ReportDeliveryReason brokenSinkReason(final ReportSinkName sink) {
        return switch (sink) {
            case LOG -> ReportDeliveryReason.LOG_WRITE_FAILED;
            case EMAIL -> ReportDeliveryReason.SEND_FAILED;
        };
    }

    /**
     * The request one intake row is about, as the key the two reads are folded on.
     *
     * @param summary the projection the statement answered
     * @return the request's identity
     */
    private static String identityOf(final ProcessedRequestSummary summary) {
        return summary.source() + ":" + summary.requestId();
    }

    /**
     * The most specific identifier the entry carries, or nothing where it carries none.
     *
     * <p>Most specific rather than first present: a refused notification names its own row and its
     * batch, and ordering it by the batch would put every refusal of one batch in an order the
     * rows themselves do not have.
     *
     * @param entry one thing wrong
     * @return the identifier it is named by, as text, or {@code null}
     */
    private static String identifierOf(final ExceptionEntry entry) {
        final Object named = Stream.of(entry.requestId(), entry.notificationId(), entry.batchId(),
                        entry.hearingId())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return named == null ? null : named.toString();
    }

    /**
     * One of the two intake kinds, which carry the request's own identifiers and nothing else.
     *
     * @param kind    which of the two
     * @param summary the projection the statement answered
     * @param reason  the bounded reason, where the kind has one
     * @return the entry
     */
    private static ExceptionEntry intake(final ExceptionKind kind,
            final ProcessedRequestSummary summary, final String reason) {
        return new ExceptionEntry(kind, summary.source(), summary.requestId(), summary.hearingId(),
                summary.hearingDay(), null, null, null, null, summary.status().name(),
                summary.attempts(), reason, summary.ageSeconds());
    }

    /**
     * A batch, late at one of its three stages or dead at the end of them.
     *
     * @param kind   which of the two
     * @param batch  the projection the statement answered
     * @param reason the overdue stage, or the batch's own bounded failure reason
     * @return the entry
     */
    private static ExceptionEntry batch(final ExceptionKind kind, final BatchException batch,
            final String reason) {
        return new ExceptionEntry(kind, null, null, null, null, batch.batchId(), null,
                batch.courtCentreId(), batch.registerDate(), batch.status().name(), null, reason,
                batch.ageSeconds());
    }

    /**
     * A recorded register the most recent scheduled generation run left where it was.
     *
     * <p>It carries no batch id, because the absence of one is what is wrong with it.
     *
     * @param register the projection the statement answered
     * @return the entry
     */
    private static ExceptionEntry unbatched(final RecordedRegisterSummary register) {
        return new ExceptionEntry(ExceptionKind.BATCH_LATE, null, null, register.hearingId(), null,
                null, null, register.courtCentreId(), register.registerDate(), RECORDED, null,
                AWAITING_BATCH, register.ageSeconds());
    }

    /**
     * One Youth Offending Team's e-mail that was refused or never answered.
     *
     * <p>Named by its notification id and its batch, and by no address: no read this feature makes
     * selects one, so there is nothing here to mask and nothing to forget to mask.
     *
     * @param refused the projection the statement answered
     * @return the entry
     */
    private static ExceptionEntry notification(final FailedNotification refused) {
        return new ExceptionEntry(ExceptionKind.NOTIFICATION_FAILED, null, null, null, null,
                refused.batchId(), refused.notificationId(), refused.courtCentreId(),
                refused.registerDate(), refused.status().name(), refused.attempts(),
                responseCodeOf(refused), refused.ageSeconds());
    }

    /** The response code as its bounded three digits, or nothing where nobody answered. */
    private static String responseCodeOf(final FailedNotification refused) {
        return refused.responseCode() == null ? null : String.valueOf(refused.responseCode());
    }

    /** A dead batch's own bounded failure reason, and never systemdocgenerator's words. */
    private static String nameOf(final BatchException dead) {
        return dead.failureReason() == null ? null : dead.failureReason().name();
    }

    /**
     * Which kind one FAILED batch is, decided by the reason on its own row.
     *
     * <p>One read answers for both, because one read is what the store holds: a batch a run gave
     * up on is FAILED exactly as a batch systemdocgenerator refused is, and the only thing that
     * tells them apart is the bounded reason. So the split is made here, at the one place a FAILED
     * batch becomes an entry, rather than by a second statement that would have to keep the same
     * window and the same order as this one.
     *
     * <p><strong>{@link ExceptionKind#BATCH_RELEASED} is informational and
     * {@link ExceptionKind#BATCH_FAILED} is not</strong> (FR-019). A released batch's registers
     * were given back by the same run that failed it and were rendered that night, so it is
     * reported because support should know a court centre needed two attempts - not because
     * anything is owed. Reported as a failure it would send somebody after a document that
     * exists, and the morning a genuinely refused render was on the same page it would be one
     * entry harder to see.
     *
     * @param dead the projection the failed-batches read answered with
     * @return the kind its reason makes it
     */
    private static ExceptionKind kindOf(final BatchException dead) {
        return dead.failureReason() == BatchFailureReason.NOT_COMPLETED_BY_NEXT_RUN
                ? ExceptionKind.BATCH_RELEASED
                : ExceptionKind.BATCH_FAILED;
    }
}
