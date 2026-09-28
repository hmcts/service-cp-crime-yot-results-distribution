package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One recorded register, as the batch half of the service reads it back.
 *
 * <p>A view of a {@code processed_output} row and deliberately not the row: the intake half writes
 * that row through {@link ProcessedOutputClaim} and knows about claims and digests, while the batch
 * half only ever needs the hearing, the key it groups by, the document it renders and the flag state
 * that decides whether it may. Two records rather than one shared mutable one keeps each half unable
 * to write the other's columns.
 *
 * <p>The recipients are not a component. They live in the document, which is the thing that was
 * validated and stored, and a second copy here would be a second truth that a re-read could
 * disagree with; {@link #recipients()} lifts them out instead.
 *
 * @param outputId      the {@code processed_output} row's identity
 * @param hearingId     the hearing the register was built from
 * @param hearingDate   the hearing's own date, as recorded
 * @param key           the court centre and register day this record is batched under
 * @param registerTime  the document's register instant, which decides which of two re-shares is the
 *                      later
 * @param fileName      the file name the document was built under
 * @param defendantType {@code Applicant} / {@code Appellant} / {@code Respondent}, or {@code null}
 *                      where the hearing carried no court application
 * @param flagState     the cutover flag as it stood when this register was recorded
 * @param document      the validated register document exactly as it was stored
 */
public record RegisterRecord(
        UUID outputId,
        UUID hearingId,
        Instant hearingDate,
        CourtCentreDay key,
        Instant registerTime,
        String fileName,
        String defendantType,
        RecordedFlagState flagState,
        CourtRegisterDocument document) {

    /**
     * Who this record's register is addressed to.
     *
     * <p>Empty and absent are the same statement here, which they are not in the document: the
     * document distinguishes them because progression's schemas do, and the recipient union does not
     * care - a record that matched no subscription contributes nobody either way.
     *
     * @return the recorded recipients, or an empty list where the register matched nobody
     */
    public List<CourtRegisterRecipient> recipients() {
        final List<CourtRegisterRecipient> recorded = document.recipients();
        return recorded == null ? List.of() : recorded;
    }
}
