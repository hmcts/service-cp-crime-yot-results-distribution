package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;

/**
 * One register, and everything that has to be written down in the breath before it is posted.
 *
 * <p>A value rather than a parameter list because the parts arrive at the same moment and four of
 * them would sit next to each other as strings and maps a positional call would accept in any order.
 * It is the same shape the informant port's {@code AuthoritySubmission} has, minus the fan-out
 * dimension this flow does not have: one hearing, one register, one POST.
 *
 * <p><strong>The claim is here because the output row is fenced on it.</strong>
 * {@code ProcessedOutputRepository} does not take a key: every one of its statements selects the row
 * from {@code processed_request} under this run's {@code claim_owner} and {@code claim_token}, so a
 * runner whose claim was reclaimed while it worked cannot claim the output row, replace the digest of
 * the body the winner is about to send, or settle it underneath them. The adapter therefore cannot
 * write anything without the claim, and a submission port that did not carry it would leave the
 * fence with nothing to fence against.
 *
 * <p><strong>The OU code and the register day are here because the document does not carry them.</strong>
 * {@code processed_output} keeps both as support columns — they are what an operator searches by —
 * and neither can be recovered from the {@code add-court-register} body: the OU code appears in the
 * file name and nowhere else, and the register day would have to be re-derived from the document's
 * register date. Re-deriving it in the adapter would be a second derivation of the day C12 exists to
 * fix, so the day the recipients were read for is the day the row records, by construction.
 *
 * <p><strong>The deadline is here for the same reason the claim is.</strong> A submission is the last
 * and longest network step of a run, and the only one whose retries can spend minutes: the transport
 * behind this port takes waits between attempts, and a wait taken past the instant the run promised
 * to stop by is a POST made while the claim behind it may already have been reclaimed — which for
 * {@code add-court-register}, which appends, is a second register for the hearing. The port therefore
 * carries what is left of the run's budget as the absolute instant it ends, so the transport can
 * refuse an attempt and refuse a wait rather than discovering afterwards that it had no claim.
 *
 * @param claim             the claim this run holds; every output write is fenced on it
 * @param deadline          the instant the run promised to have stopped by; no attempt is started
 *                          and no wait is taken across it
 * @param document          the assembled {@code add-court-register} command
 * @param courtCentreOuCode the court centre's OU code, or {@code null} where the hearing carried none
 * @param registerDay       the day the register covers, as the subscriptions were read for it (C12)
 * @param caller            the identity the POST is made as
 * @param anomalies         how many of each guarded skip this register survived; empty where none
 */
public record RegisterSubmission(
        RunClaim claim,
        Instant deadline,
        CourtRegisterDocument document,
        String courtCentreOuCode,
        LocalDate registerDay,
        CallerIdentity caller,
        Map<TransformationAnomaly, Integer> anomalies) {

    /**
     * Freezes the counts, and treats absent and empty as the one statement they are.
     *
     * <p>A copy because the transformation accumulates these as it walks the hearing: a submission
     * holding that same map would describe whatever the transformation did next rather than what it
     * sent.
     */
    public RegisterSubmission {
        anomalies = anomalies == null ? Map.of() : Map.copyOf(anomalies);
    }
}
