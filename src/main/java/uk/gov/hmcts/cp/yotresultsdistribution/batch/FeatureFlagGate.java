package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.config.GenerationMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FlagDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Proceed;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Reason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.GateDecision.Skipped;

/**
 * The first thing a generation run does, and the only thing that lets it do anything else.
 *
 * <p>One read of {@code YotResultsDistributionService} per run, with no cache, and fail-closed: ON proceeds,
 * OFF and unreadable skip the run and count the skip under its bounded reason (constitution Cutover
 * Rule, research §3). A flag that was on an hour ago says nothing about a cutover that was rolled
 * back ten minutes ago, which is why the answer is never held between runs.
 *
 * <p>The CLI asks the same question through the same gate, and {@code --ignore-flag} is the one way
 * past it: an operator regenerating a batch by hand may need to do so while the flag is off, and the
 * override is stated in the answer so the run report can say a run went ahead that the flag would
 * have stopped.
 *
 * <p>Both the reading and the decision are counted here rather than by the caller: the
 * {@code flag_read_ok} gauge is what says whether the App Configuration store is answering at all,
 * and it must move on the read that failed as well as on the one that succeeded. The two
 * vocabularies stay apart for the same reason - the gauge says whether the store answered, the
 * skipped counter says what it answered - so a flag read as off leaves the gauge up and moves the
 * counter, and a store that could not be reached moves both.
 *
 * <p>A skipped run is written down as well as counted, because it is the one outcome that leaves no
 * other trace at all: no batch, no request, no row. Every value in that line, and in the override's
 * warning, is a bounded code from {@link Reason} or from the reading itself, so a batch, an endpoint
 * or another system's words about the store cannot reach the index by interpolation (constitution
 * Principle VII).
 */
public class FeatureFlagGate {

    private static final Logger LOG = LoggerFactory.getLogger(FeatureFlagGate.class);

    private final FeatureFlagReader reader;

    private final GenerationMetrics metrics;

    /**
     * Creates the gate over the flag reader and the instruments its answer moves.
     *
     * @param reader  the one lever's reader, which never throws
     * @param metrics the instrument surface the skip and the reading are counted on
     */
    public FeatureFlagGate(final FeatureFlagReader reader, final GenerationMetrics metrics) {
        this.reader = reader;
        this.metrics = metrics;
    }

    /**
     * Reads the flag and says whether this run may generate.
     *
     * <p>The read happens on every decision, override included: the override changes what this
     * service does, not whether App Configuration replied, and a gauge that stopped moving on the
     * runs an operator forced would go quiet exactly during a cutover.
     *
     * @param ignoreFlag whether the caller has deliberately overridden the flag, which only the CLI
     *                   may do and only by being asked to
     * @return {@code Proceed}, or {@code Skipped} carrying the bounded reason it was skipped under
     */
    public GateDecision decide(final boolean ignoreFlag) {
        final FlagDecision reading = reader.read();
        metrics.flagRead(reading);

        final GateDecision decision;
        if (reading.generates()) {
            // An override on a stack that is already cut over overrode nothing, and a run report
            // that said otherwise would make every CLI run look like one taken against the
            // platform's wishes.
            decision = new Proceed(false);
        } else if (ignoreFlag) {
            LOG.warn("A generation run went ahead over a flag that did not say on, because an "
                    + "operator overrode it. reason={} flag={}", Reason.OVERRIDDEN.code(),
                    reading.code());
            decision = new Proceed(true);
        } else {
            decision = skip(reading);
        }
        return decision;
    }

    /**
     * Fail-closed: the flag did not say on and nobody overrode it, so nothing is generated.
     *
     * <p>Counted under what the flag said rather than under what was decided, so that the six causes
     * of an unreadable flag keep their own series - an absent endpoint, a refused identity and a
     * slow store are three different things to go and fix - while the decision itself has the one
     * reason a run either starts or does not.
     */
    private GateDecision skip(final FlagDecision reading) {
        metrics.runSkipped(reading);
        final Reason reason = reading instanceof FlagDecision.Unreadable
                ? Reason.FLAG_UNREADABLE
                : Reason.FLAG_OFF;
        LOG.info("The flag did not say on, so no register was generated. reason={} flag={}",
                reason.code(), reading.code());
        return new Skipped(reason);
    }
}
