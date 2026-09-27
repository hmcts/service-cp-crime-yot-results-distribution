package uk.gov.hmcts.cp.yotresultsdistribution.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;

/**
 * The legacy court-application builders, ported.
 *
 * <p>Twin of
 * {@code $DF/OutboundCourtRegister/CourtRegisterRequest/Mappers/ProsecutionCaseOrApplication/test/ModelObjects.js},
 * the one court-register fixture that is code rather than JSON. Four Jest cases build their court
 * applications with it rather than loading a file, because what they are about is the <em>shape</em>
 * of an application — offences reached through {@code courtApplicationCases}, through
 * {@code courtOrder}, through both, and through the court order alone — and a shape is easier to
 * state than to find in twenty kilobytes of hearing.
 *
 * <p>The class names are the legacy's, deliberately, so a Java twin reads against the Jest case it
 * ports line for line. Everything answers with a mutable {@link ObjectNode}: an application under
 * test is usually the base shape plus one field, exactly as the Jest cases assign one.
 *
 * <p><strong>Two JavaScript quirks are reproduced rather than tidied.</strong> {@code CourtOrder}
 * and {@code CourtApplicationCase} each take a single offence and wrap it in a one-element array —
 * so a case or order with two offences cannot be built the way the legacy builds one, and a twin
 * that needs two says so explicitly by setting the array itself. And the fields the JS constructors
 * merely name without assigning ({@code this.courtOrder;}, {@code this.id;}) are <em>absent</em>
 * here, not null: the mappers under test read them with truthiness tests, where absent and null
 * behave alike, but the comparator that guards this port does not treat them as the same thing.
 */
public final class ModelObjects {

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private ModelObjects() {
        // Static fixture holder.
    }

    /**
     * An empty object node, built with the service's own mapper.
     *
     * @return the node
     */
    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    /**
     * An array of the given nodes.
     *
     * @param items the elements, in order
     * @return the array
     */
    public static ArrayNode array(final JsonNode... items) {
        final ArrayNode array = MAPPER.createArrayNode();
        for (final JsonNode item : items) {
            array.add(item);
        }
        return array;
    }

    /**
     * {@code new Subject(masterDefendant)}.
     *
     * @param masterDefendant the application's subject, or {@code null} to leave it absent — the
     *                        shape the court register's eligibility gate turns on
     * @return the subject
     */
    public static ObjectNode subject(final JsonNode masterDefendant) {
        final ObjectNode subject = object();
        if (masterDefendant != null) {
            subject.set("masterDefendant", masterDefendant);
        }
        return subject;
    }

    /**
     * {@code new Applicant(prosecutingAuthority)}.
     *
     * @param prosecutingAuthority the applicant's prosecuting authority, or {@code null} for an
     *                             applicant that prosecutes nothing — the C22 exhibit
     * @return the applicant
     */
    public static ObjectNode applicant(final JsonNode prosecutingAuthority) {
        final ObjectNode applicant = object();
        if (prosecutingAuthority != null) {
            applicant.set("prosecutingAuthority", prosecutingAuthority);
        }
        return applicant;
    }

    /**
     * {@code new ProsecutingAuthority(prosecutionAuthorityId, prosecutionAuthorityCode)}.
     *
     * @param prosecutionAuthorityId   the authority's id
     * @param prosecutionAuthorityCode the authority's code
     * @return the prosecuting authority
     */
    public static ObjectNode prosecutingAuthority(
            final String prosecutionAuthorityId, final String prosecutionAuthorityCode) {
        final ObjectNode authority = object();
        authority.put("prosecutionAuthorityId", prosecutionAuthorityId);
        authority.put("prosecutionAuthorityCode", prosecutionAuthorityCode);
        return authority;
    }

    /**
     * {@code new CourtApplication(subject, applicant)}.
     *
     * <p>{@code courtOrder} and {@code courtApplicationCases} are named by the JS constructor and
     * never assigned, so they are absent here; a twin that needs either sets it.
     *
     * @param subject   the application's subject
     * @param applicant the application's applicant
     * @return the court application
     */
    public static ObjectNode courtApplication(final JsonNode subject, final JsonNode applicant) {
        final ObjectNode application = object();
        application.set("subject", subject);
        application.set("applicant", applicant);
        return application;
    }

    /**
     * {@code new CourtOrder(courtOrderOffences)} — which takes one offence and wraps it in an array.
     *
     * @param courtOrderOffence the single court-order offence
     * @return the court order
     */
    public static ObjectNode courtOrder(final JsonNode courtOrderOffence) {
        final ObjectNode order = object();
        order.set("courtOrderOffences", array(courtOrderOffence));
        return order;
    }

    /**
     * {@code new CourtOrderOffence(offence, caseUrn, prosecutionAuthorityRef)}.
     *
     * @param offence                  the offence itself
     * @param caseUrn                  the case URN
     * @param prosecutionAuthorityRef  the prosecuting authority's own reference
     * @return the court-order offence
     */
    public static ObjectNode courtOrderOffence(
            final JsonNode offence, final String caseUrn, final String prosecutionAuthorityRef) {
        final ObjectNode courtOrderOffence = object();
        courtOrderOffence.set("offence", offence);
        courtOrderOffence.set("prosecutionCaseIdentifier",
                caseIdentifier(caseUrn, prosecutionAuthorityRef));
        return courtOrderOffence;
    }

    /**
     * {@code new CourtApplicationCase(offences, caseUrn, prosecutionAuthorityRef)} — one offence,
     * wrapped in an array.
     *
     * @param offence                 the single offence
     * @param caseUrn                 the case URN
     * @param prosecutionAuthorityRef the prosecuting authority's own reference
     * @return the court-application case
     */
    public static ObjectNode courtApplicationCase(
            final JsonNode offence, final String caseUrn, final String prosecutionAuthorityRef) {
        final ObjectNode applicationCase = object();
        applicationCase.set("offences", array(offence));
        applicationCase.set("prosecutionCaseIdentifier",
                caseIdentifier(caseUrn, prosecutionAuthorityRef));
        return applicationCase;
    }

    /**
     * {@code new ProsecutionCase(prosecutionAuthorityId, caseUrn, prosecutionAuthorityRef)}.
     *
     * @param prosecutionAuthorityId  the authority's id
     * @param caseUrn                 the case URN
     * @param prosecutionAuthorityRef the prosecuting authority's own reference
     * @return the prosecution case, with {@code id} and {@code defendants} absent
     */
    public static ObjectNode prosecutionCase(
            final String prosecutionAuthorityId,
            final String caseUrn,
            final String prosecutionAuthorityRef) {
        final ObjectNode prosecutionCase = object();
        final ObjectNode identifier = caseIdentifier(caseUrn, prosecutionAuthorityRef);
        identifier.put("prosecutionAuthorityId", prosecutionAuthorityId);
        prosecutionCase.set("prosecutionCaseIdentifier", identifier);
        return prosecutionCase;
    }

    /**
     * {@code new MasterDefendant(masterDefendantId)} — including the empty-string person the JS
     * constructor writes, which is what makes an ASN or a name come out blank rather than absent.
     *
     * @param masterDefendantId the defendant's identity across cases and applications
     * @return the master defendant
     */
    public static ObjectNode masterDefendant(final String masterDefendantId) {
        final ObjectNode masterDefendant = object();
        masterDefendant.put("masterDefendantId", masterDefendantId);
        final ObjectNode personDefendant = object();
        personDefendant.put("firstName", "");
        personDefendant.put("middleName", "");
        personDefendant.put("lastName", "");
        personDefendant.put("dateOfBirth", "");
        personDefendant.put("nationalityCode", "");
        personDefendant.put("arrestSummonsNumber", "");
        personDefendant.set("address", object());
        masterDefendant.set("personDefendant", personDefendant);
        return masterDefendant;
    }

    /**
     * {@code new Offence(offenceCode, orderIndex, offenceTitle)}.
     *
     * @param offenceCode  the offence's CJS code
     * @param orderIndex   the offence's index within its case
     * @param offenceTitle the offence title
     * @return the offence, with {@code id} absent and empty {@code plea} and {@code verdict}
     */
    public static ObjectNode offence(
            final String offenceCode, final int orderIndex, final String offenceTitle) {
        final ObjectNode offence = object();
        offence.put("offenceCode", offenceCode);
        offence.put("orderIndex", orderIndex);
        offence.put("offenceTitle", offenceTitle);
        offence.set("plea", object());
        offence.set("verdict", object());
        return offence;
    }

    /**
     * {@code new Result(cjsCode, resultText)}.
     *
     * @param cjsCode    the result's CJS code
     * @param resultText the result's text
     * @return the judicial result
     */
    public static ObjectNode result(final String cjsCode, final String resultText) {
        final ObjectNode result = object();
        result.put("cjsCode", cjsCode);
        result.put("resultText", resultText);
        return result;
    }

    /**
     * {@code new DefendantContextBase(masterDefendantId)} — an empty result list and an identity,
     * which is all four of the shape cases need.
     *
     * @param masterDefendantId the defendant's identity across cases and applications
     * @return the context base
     */
    public static ObjectNode defendantContextBase(final String masterDefendantId) {
        final ObjectNode contextBase = object();
        contextBase.set("results", MAPPER.createArrayNode());
        contextBase.put("masterDefendantId", masterDefendantId);
        return contextBase;
    }

    /**
     * The two-field case identifier both application shapes carry.
     *
     * @param caseUrn                 the case URN
     * @param prosecutionAuthorityRef the prosecuting authority's own reference
     * @return the identifier
     */
    private static ObjectNode caseIdentifier(
            final String caseUrn, final String prosecutionAuthorityRef) {
        final ObjectNode identifier = object();
        identifier.put("caseURN", caseUrn);
        identifier.put("prosecutionAuthorityReference", prosecutionAuthorityRef);
        return identifier;
    }
}
