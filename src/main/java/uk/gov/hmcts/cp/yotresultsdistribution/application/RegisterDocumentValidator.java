package uk.gov.hmcts.cp.yotresultsdistribution.application;

import uk.gov.hmcts.cp.yotresultsdistribution.domain.ContractValidationException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;

/**
 * The contract the register is held to at the moment it is written into this service's own store.
 *
 * <p><strong>The document that is validated has to be the document that is stored.</strong> The
 * transformation holds the assembled register to the {@code add-court-register} command, which is
 * what a {@code progression-post} deployment sends, and then attaches {@code defendantType} - so the
 * register that reaches the store is one field larger than the register anything checked. Since
 * increment 002 that stored document <em>is</em> the contract: the nightly batch reads it back, the
 * PDF payload is built from it, and nothing between here and the render looks at it again
 * (constitution Principle III). A field nobody validated is a field nobody can rely on.
 *
 * <p>So the record arm asks this port before the write, and what it asks against is the frozen
 * register-document schema {@code courtRegisterDocumentRequest.json}, which declares
 * {@code defendantType} and is the schema that describes what the store holds. It is a port rather
 * than a class the core reaches for because the vendored schemas and the validator that applies them
 * are an adapter's business (constitution Principle V); the core owns the question, not the answer.
 */
public interface RegisterDocumentValidator {

    /**
     * Refuses a register the frozen register-document schema would.
     *
     * @param document the assembled register, as it will be stored
     * @throws ContractValidationException where the document does not satisfy the vendored schema,
     *         carrying the bounded violation and the JSON pointer of the offending field - a path,
     *         never a value, because every defendant on this register is a child
     */
    void validate(CourtRegisterDocument document);
}
