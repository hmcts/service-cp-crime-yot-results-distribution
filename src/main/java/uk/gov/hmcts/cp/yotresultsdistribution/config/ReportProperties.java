package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The settings the morning exception report runs under.
 *
 * <p>Bound from the {@code yotresultsdistribution.report} keys. Defaults live here as well as in
 * {@code application.yaml} for the reason 001's and 002's do: the values are visible to the code
 * that depends on them, and a missing configuration file cannot silently change when support is
 * told what went wrong overnight.
 *
 * <p><strong>{@code enabled} is a master switch and not a cutover lever.</strong> The report reads
 * and writes nothing the cutover decides and never reads the {@code YotResultsDistributionService} flag; it
 * runs on a pod whose generation half is switched off, which is the whole point of it being its own
 * switch.
 *
 * <p>There is deliberately <strong>no {@code window} setting</strong>. The run's window begins at
 * the previous occurrence of {@link #cron} in {@link #zone}, so a Monday run reads back to Friday
 * and every FAILED request, batch and notification lands in exactly one report. A duration beside a
 * schedule is one fact written twice, and the morning the two disagree is the morning something
 * falls in the gap.
 *
 * <p>Every refusal this record's values can earn lives in {@link PropertiesValidator}, because each
 * of them is a relationship - between a duration and the fixed run budget, or between the e-mail
 * switch and the two settings it makes required.
 *
 * @param enabled                  master switch for the 07:00 job and its own scheduler
 * @param cron                     the schedule, in Spring's six-field dialect
 * @param zone                     the zone the cron is read in; {@code Europe/London} unless
 *                                 acknowledged otherwise
 * @param zoneOverrideAcknowledged the deliberate acknowledgement that permits another zone
 * @param lockAtMostFor            how long the report's ShedLock lock is held, which must cover
 *                                 {@link PropertiesValidator#REPORT_RUN_BUDGET} plus
 *                                 {@link PropertiesValidator#SCHEDULER_LOCK_MARGIN}
 * @param requestTerminalWithin    how long a request may stay RECEIVED or RETRYING before it is
 *                                 reported late
 * @param notifiedWithin           how long a batch may stay GENERATED without being notified before
 *                                 it is reported late
 * @param batchGeneratedWithin     how long a batch may stay PENDING or GENERATING before it is
 *                                 reported late. Its own value since 004: it borrowed the
 *                                 generation half's grace period until that setting became
 *                                 {@code stale-after} and lengthened, and the two answer different
 *                                 questions - when support should be told a render is late, and
 *                                 when a run gives up on a batch and re-batches its registers
 * @param maxEntries               how many of the two <strong>late</strong> kinds one report may
 *                                 carry. Over it the oldest are kept and the rest are counted as
 *                                 dropped; the three failure kinds are never capped, because a
 *                                 failure a window dropped is a failure no window would ever report
 *                                 again. The counts stay whole, so a truncated morning still reads
 *                                 as the bad one it was. Refused at zero: a report that carries no
 *                                 late entry at all looks exactly like a quiet night
 * @param email                    the e-mail output, switchable on its own
 */
@ConfigurationProperties(prefix = "yotresultsdistribution.report")
public record ReportProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("0 0 7 * * MON-FRI") String cron,
        @DefaultValue(GenerationProperties.COURTS_ZONE) String zone,
        @DefaultValue("false") boolean zoneOverrideAcknowledged,
        @DefaultValue("15m") Duration lockAtMostFor,
        @DefaultValue("30m") Duration requestTerminalWithin,
        @DefaultValue("15m") Duration notifiedWithin,
        @DefaultValue("10m") Duration batchGeneratedWithin,
        @DefaultValue("5000") int maxEntries,
        @DefaultValue Email email) {

    /**
     * The e-mail output, and the two settings it makes required.
     *
     * <p>Switchable independently of the Log Analytics output: a stack whose notificationnotify
     * template has not been provided yet still writes the events, and the report is read either
     * way.
     *
     * @param enabled    whether the report is also e-mailed
     * @param templateId the notificationnotify template the report is sent under, a UUID; required
     *                   and shape-checked at startup once {@link #enabled} is true
     * @param recipients the addresses the report goes to, comma-separated in the source and never a
     *                   value in this repository - they are people's addresses, and they arrive from
     *                   Key Vault through the CSI driver
     */
    public record Email(
            @DefaultValue("false") boolean enabled,
            String templateId,
            List<String> recipients) {

        /** Freezes the list, and treats an unconfigured one as none rather than as absent. */
        public Email {
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
        }
    }
}
