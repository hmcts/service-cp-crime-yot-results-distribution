package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterFragment;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;

/**
 * Assembles one hearing's register from its fragment, its matched subscriptions and its payload.
 *
 * <p>Ports {@code OutboundCourtRegister/index.js:16-40}: the youth filter, the three dates and the
 * ids carried across from the fragment, the file name, and the three mappers that produce the venue,
 * the recipients and the defendants.
 *
 * <p><strong>Not one of the twelve mappers</strong> — it is what calls them. The terminal states its
 * three {@code null}s become are the transformation chain's to name (T056); what this method owes
 * the chain is an answer that says <em>which</em> outcome it was.
 *
 * <p>Four fixes meet in this one method.
 *
 * <ul>
 *   <li><strong>C33</strong> — the two early returns, no matched subscriptions and no youth
 *       defendants, are this flow's two most common outcomes and both surface in the legacy as a
 *       bare {@code null} the orchestration reports as {@code Success: true}. Each names its own
 *       {@link CompletionReason} here, so the two are countable apart.</li>
 *   <li><strong>C36</strong> — a register whose every matched recipient was dropped is not
 *       assembled at all. The legacy posts it with {@code recipients: undefined}; progression stores
 *       it, renders the PDF at 18:00, and then emits a notification nothing subscribes to, so the
 *       register sticks at GENERATED forever, visible to nobody. There is nobody to distribute to,
 *       which is the {@code no-subscriptions} outcome by another route.</li>
 *   <li><strong>C26</strong> — {@code courtCentreId} is read under the spelling the fragment
 *       actually uses, where the legacy reads {@code courtCenterId} off an object carrying
 *       "Centre" and sends {@code undefined} on every register progression has ever received.</li>
 *   <li><strong>C11</strong> — the file name is the register day, the court centre code and the
 *       hearing id. The legacy embeds the full instant, so the name carries colons a Windows file
 *       system will not take, and two hearings at one court centre sharing a second collide.</li>
 * </ul>
 *
 * <p>The court centre code in that name is the <strong>hearing's</strong>
 * {@code courtCentre.code}, not the fragment's {@code courtCentreOUCode}. They are gathered from the
 * same place and agree in every fixture, so nothing could ever have distinguished them; the legacy
 * reads the hearing's, and so does this.
 *
 * <p>Pure, like everything else in the transformation: no clock, and the anomaly recorder is the
 * caller's (constitution Principle V).
 */
// PMD.OnlyOneReturn: the three no-op answers are three distinct outcomes, and naming each where it
// is reached is the whole of C33 and C36 — a single exit would put them back behind one `null`.
@SuppressWarnings("PMD.OnlyOneReturn")
public final class AggregationMapper {

    private static final Logger LOG = LoggerFactory.getLogger(AggregationMapper.class);

    /** The register's date handling; pure, and the only thing here that reads a date. */
    private static final Dates DATES = new Dates();

    private AggregationMapper() {
    }

    /**
     * Assembles the document.
     *
     * @param fragment             the hearing's register fragment
     * @param matchedSubscriptions the subscriptions matching it
     * @param hearing              the hearing payload
     * @param anomalies            where every guarded skip beneath this is counted
     * @return the assembled document, or {@code null} where the register has no youth defendant or
     *         no recipient — outcomes the caller names rather than swallows
     */
    public static CourtRegisterDocument map(
            final RegisterFragment fragment,
            final List<JsonNode> matchedSubscriptions,
            final JsonNode hearing,
            final Consumer<TransformationAnomaly> anomalies) {

        if (matchedSubscriptions == null || matchedSubscriptions.isEmpty()) {
            LOG.info("No subscription matched the court centre, so the register has no audience. "
                            + "hearingId={} reason={}",
                    fragment.hearingId(), CompletionReason.NO_SUBSCRIPTIONS.value());
            return null;
        }

        final List<RegisterDefendant> youthDefendants = fragment.registerDefendants().stream()
                .filter(RegisterDefendant::youthDefendant)
                .toList();
        if (youthDefendants.isEmpty()) {
            LOG.info("The hearing gathered no youth defendant, so there is no register to build. "
                            + "hearingId={} reason={}",
                    fragment.hearingId(), CompletionReason.NO_YOUTH_DEFENDANTS.value());
            return null;
        }

        final List<CourtRegisterRecipient> recipients =
                new RecipientMapper(anomalies).map(matchedSubscriptions);
        if (recipients == null) {
            // C36. Each drop has already been counted and warned about by the recipient mapper; what
            // this line adds is that the run as a whole reached nobody.
            LOG.info("Every matched subscription was dropped, so the register reaches nobody and is "
                            + "not submitted. hearingId={} reason={}",
                    fragment.hearingId(), CompletionReason.NO_SUBSCRIPTIONS.value());
            return null;
        }

        final List<CourtRegisterDefendant> defendants =
                new YouthDefendantMapper(anomalies).map(youthDefendants, hearing);

        // Counts, and nothing about who is on the register: TelemetryPrivacyTest holds this line to
        // that, and it is the line the seeded violation was seeded into (T068), because "who was on
        // the register" is what somebody debugging the wrong register reaches for first.
        LOG.info("Outbound court register assembled. hearingId={} defendants={} recipients={}",
                fragment.hearingId(), defendants.size(), recipients.size());

        return new CourtRegisterDocument(
                fragment.registerDate(),
                fragment.hearingDate(),
                fragment.hearingId(),
                fragment.courtCentreId(),
                fileName(fragment, hearing),
                // The defendant type is resolved from the hearing's court application by the chain
                // above this mapper, which is where the hearing and the assembled document meet;
                // T023 sets it there.
                null,
                HearingVenueMapper.map(hearing),
                recipients,
                defendants);
    }

    /**
     * The name progression stores the rendered register under — defect fix C11.
     *
     * <p>The day is the <em>UTC</em> day of the register instant, which is the day the register was
     * addressed on (C12) and the day its own {@code registerDate} falls on. Reading it as a London
     * calendar day instead files a 23:00 BST share under tomorrow, which is the legacy's answer and
     * is what C10's row says must move.
     *
     * @param fragment the hearing's register fragment
     * @param hearing  the hearing payload, which carries the court centre's code
     * @return the file name
     */
    private static String fileName(final RegisterFragment fragment, final JsonNode hearing) {
        return "court-register_"
                + DATES.registerDay(fragment.registerDate())
                + '_' + Json.text(Json.at(hearing, "courtCentre"), "code")
                + '_' + fragment.hearingId()
                + ".pdf";
    }
}
