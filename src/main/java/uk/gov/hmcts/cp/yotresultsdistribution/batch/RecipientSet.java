package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;

/**
 * Who one batch's document is e-mailed to.
 *
 * <p>The de-duplicated union of every record's recipients across the batch, keyed on
 * {@code emailAddress1}, with the name taken from the address's first occurrence. One address, one
 * e-mail, one {@code register_notification} row: the same Youth Offending Team on ten hearings is
 * told once, and the row that says so is the evidence it was told.
 *
 * <p><strong>Defect fix P4.</strong> The progression leg keeps only the first non-empty recipient
 * list it sees for a court centre ({@code CourtCentreAggregate.java:71-83}) and every later record's
 * recipients are discarded, so a subscription that exists on the day's second hearing and not on its
 * first is never e-mailed at all. Subscriptions are keyed on the court centre, so the union usually
 * equals that first list and the deviation only bites where subscriptions differ within a court
 * centre - which is exactly the case the legacy loses silently. The union is content-affecting, so
 * the register row carries the sign-off-before-cutover marker (research §9).
 *
 * <p>The order is the order the addresses were first seen, so a batch read twice produces the same
 * recipients in the same order, and a run report a person compares by eye reads the same way twice.
 *
 * <p><strong>Two recorded addresses are one recipient only when the strings are equal</strong>, as
 * progression. The legacy compares addresses nowhere at all - it keeps a list whole - so there is no
 * case-folding behaviour to port, and folding case here would be an uncatalogued content change in
 * the direction that loses an e-mail: two subscriptions whose reference data spells one mailbox
 * differently are two rows in {@code register_notification} and two teams who each get the register,
 * not one team chosen by whichever spelling was recorded first. Nothing is trimmed or re-written
 * either, because the recipient mapper already trimmed the address and dropped the matched
 * subscription that carried none (C29).
 *
 * <p>Nothing here reaches a log. An address and a recipient name are the two components that never
 * appear at INFO or above (constitution Principle VII); this class hands them to the notification
 * rows, which are the only place that may hold them.
 */
public final class RecipientSet {

    private RecipientSet() {
        // Function holder.
    }

    /**
     * The batch's recipients, de-duplicated by address.
     *
     * <p>{@link RegisterRecord#recipients()} is what is read from each record rather than the
     * document's own list, because empty and absent are the same statement to a union and the
     * document distinguishes them only because progression's schemas do.
     *
     * <p>The first occurrence of an address wins whole rather than component by component: the
     * template decides which e-mail notificationnotify renders, so a recipient whose name came from
     * one record and whose template came from another would be a message no record ever described.
     *
     * @param records the batch's registers, in the order the batch holds them
     * @return one recipient per distinct {@code emailAddress1}, named from its first occurrence, in
     *     the order the addresses were first seen; empty where no record matched anybody, which is
     *     the batch that ends NOTIFIED_NOBODY (defect fix P1)
     */
    public static List<CourtRegisterRecipient> unionOf(final List<RegisterRecord> records) {
        final Map<String, CourtRegisterRecipient> byAddress = new LinkedHashMap<>();
        for (final RegisterRecord record : records) {
            for (final CourtRegisterRecipient recipient : record.recipients()) {
                byAddress.putIfAbsent(recipient.emailAddress1(), recipient);
            }
        }
        return List.copyOf(byAddress.values());
    }
}
