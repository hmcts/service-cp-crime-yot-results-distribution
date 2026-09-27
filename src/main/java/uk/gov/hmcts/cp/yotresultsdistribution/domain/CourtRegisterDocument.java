package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The {@code add-court-register} command this service produces: one hearing's register, assembled
 * and ready for progression.
 *
 * <p>Ports {@code OutboundCourtRegister/CourtRegisterRequest/Models/CourtRegisterAggregationRequest}
 * against the vendored, progression-owned {@code progression.add-court-register.json} — which is
 * {@code additionalProperties: false}, so this record's components are the whole of what may be
 * sent, and its required list is what must be. Seven of the nine are required; the two that are not
 * are {@code recipients}, and a register with none is never posted at all, and
 * {@code defendantType}.
 *
 * <p><strong>{@code defendantType} belongs to the register document, not to the command.</strong> It
 * is a legal, optional field of the frozen register-document schema
 * ({@code courtRegisterDocumentRequest.json:30-32}, a plain string outside the {@code required}
 * list), which is what 002 records and what the batch's PDF payload prints the defendants under; it
 * is resolved from the hearing's own court application by {@code DefendantTypeResolver}. The
 * {@code add-court-register} command above does <em>not</em> declare it, and is
 * {@code additionalProperties: false}, so a document that carries one is refused by
 * {@code OutboundContractValidator} under {@code UNKNOWN_FIELD [/defendantType]}. Which schema a
 * recorded register is held to is settled by where the type is attached:
 * {@code RegisterTransformationChain} holds the assembled document to the command exactly as 001
 * did, and attaches the type afterwards - so the contract check is still the check of a POST body,
 * and the register that leaves the chain is the one 002 records. What a {@code progression-post}
 * deployment sends is therefore the command's own fields rather than this record whole, which is the
 * retained fallback's obligation and not the chain's: {@code ProgressionRegisterSubmissionClient}
 * projects the register back to the command body before it serialises anything.
 *
 * <p>Declaring the component moves no wire byte on its own: the record is serialised
 * {@code NON_NULL}, so every register without a court application leaves the field absent exactly
 * as it did in 001.
 *
 * <p><strong>Two spellings the legacy gets wrong meet here.</strong> {@code courtCentreId} is
 * spelled the way the contract spells it, end to end (defect C26): the legacy writes
 * {@code this.courtRegisterFragment.courtCenterId} — "Center" — off a fragment that supplies
 * "Centre", so the field on every register progression has ever received is {@code undefined}, and
 * the one Jest assertion naming it compares {@code undefined} to {@code undefined}. And
 * {@code fileName} is dated, coded and unique (defect C11) rather than carrying a full ISO datetime
 * with colons in it, which is invalid on Windows and collides for two hearings at one court centre
 * in the same second.
 *
 * <p>The dates are strings, not {@code Instant}s, for the same reason the register fragment's are:
 * this is a wire document, the contract says {@code date-time}, and the whole point of defect fix
 * C10 is that what was shared is carried through unaltered rather than being re-labelled by a
 * timezone conversion on the way.
 *
 * @param registerDate  the instant the hearing's results were shared, unaltered (fix C10)
 * @param hearingDate   the sitting day the register covers
 * @param hearingId     the hearing this register is for
 * @param courtCentreId the court centre the hearing sat at, spelled correctly (fix C26)
 * @param fileName      the name progression stores the rendered register under (fix C11)
 * @param defendantType the side of the hearing's court application its defendants are on, or
 *                      {@code null} where the hearing carried no court application
 * @param hearingVenue  the court house the register was produced at
 * @param recipients    the subscribing organisations it is emailed to
 * @param defendants    the youth defendants the register covers — never empty
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourtRegisterDocument(
        String registerDate,
        String hearingDate,
        String hearingId,
        String courtCentreId,
        String fileName,
        String defendantType,
        CourtRegisterHearingVenue hearingVenue,
        List<CourtRegisterRecipient> recipients,
        List<CourtRegisterDefendant> defendants) {

    /**
     * Freezes the two lists without inventing either.
     *
     * <p>Both carry {@code minItems: 1}, and both of the mappers behind them answer with nothing
     * rather than an empty array when they have nothing to say. A {@code null} that quietly became
     * {@code []} here would turn "no recipients matched" — a legitimate, recorded outcome of this
     * flow, and one of its two most common — into a document progression rejects.
     */
    public CourtRegisterDocument {
        recipients = FrozenList.frozen(recipients);
        defendants = FrozenList.frozen(defendants);
    }
}
