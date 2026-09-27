package uk.gov.hmcts.cp.yotresultsdistribution.application;

import java.time.LocalDate;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CallerIdentity;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReferenceDataUnavailableException;

/**
 * Where the now-subscriptions a register is addressed with come from.
 *
 * <p>The second of the four ports the core owns. The adapter behind it is the reference-data
 * context's subscriptions query; nothing here names a client or a transport.
 *
 * <p>The answer is returned as a canonical tree for the same reason the hearing payload is: it is
 * reference data's shape, not this service's, and a subscription carries fields this service does
 * not read and must not discard (constitution Principle IV).
 *
 * <p><strong>An empty answer and no answer are different things</strong>, and this signature is
 * where that distinction starts. Reference data answering "no subscriptions in force" completes the
 * run as {@code no-subscriptions}; reference data not answering at all throws, and the run is
 * retried. The legacy conflates the two into one silent success.
 */
public interface NowSubscriptionsSource {

    /**
     * The subscriptions in force on the day a register is addressed for.
     *
     * @param registerDay the day the results were shared — the {@code on=} day, keyed to the share
     *                    instant rather than to a relabelled local time (defect fix C12)
     * @param caller      the identity the read is made as
     * @return reference data's answer, as a canonical tree; an answer carrying no subscriptions is
     *     still an answer
     * @throws ReferenceDataUnavailableException if reference data could not be read; transient where
     *     another delivery could answer, and non-transient where reference data understood the read
     *     and declined it
     */
    JsonNode subscriptionsOn(LocalDate registerDay, CallerIdentity caller)
            throws ReferenceDataUnavailableException;
}
