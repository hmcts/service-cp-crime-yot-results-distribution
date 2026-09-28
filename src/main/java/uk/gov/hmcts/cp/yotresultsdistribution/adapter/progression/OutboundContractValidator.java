package uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterDocumentValidator;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ContractValidationException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ContractViolation;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;

/**
 * Holds an assembled register to the contract progression published, before it is sent.
 *
 * <p>The {@code add-court-register} command and its nested {@code courtRegisterDocument/*} schemas
 * are progression-owned, frozen and {@code additionalProperties: false}. They are vendored into
 * {@code src/main/resources/contracts/progression/} at {@code criminal-court-public-model}
 * 17.103.13 — the version progression compiles — and they are <strong>main</strong> resources rather
 * than test ones precisely because this check runs in production, on every document, before the
 * POST.
 *
 * <p><strong>Defect C29 is what this class exists for.</strong> The schemas require
 * {@code courtRegisterDefendant.address}, {@code courtRegisterParentGuardian.address} and
 * {@code courtRegisterAddress.address1}. The legacy address mapper answers nothing for an absent
 * address, so a child with no address on the payload produces a document progression answers 400
 * to — and the legacy swallows that 400 (C1), so the whole hearing's register is lost with no trace
 * anywhere. Validating first turns a silent loss into an explicit, bounded, replayable failure.
 *
 * <p>The failure is a {@link ContractValidationException} carrying a bounded
 * {@link ContractViolation} and the JSON pointer of the offending field — never the field's
 * <em>value</em>, which is a child's address, and never a raw validator message, which may quote the
 * document it choked on. Both would reach the dead-letter description and the log index.
 *
 * <p>It is also C26's authority: the record family is honest about the wire only if the wire agrees,
 * and a fully-populated document validating against a closed schema is what proves that every field
 * the records declare is a field progression accepts.
 *
 * <p><strong>Two instances, one for each frozen contract, and which one is which is decided by where
 * the register is going.</strong> The transformation holds an assembled register to the
 * {@code add-court-register} command, which is what a {@code progression-post} deployment sends; the
 * record arm holds the <em>final</em> register - the one carrying {@code defendantType} - to
 * {@code courtRegisterDocumentRequest.json} before it is written into this service's own store,
 * because since increment 002 that stored document is the contract the batch and the PDF payload
 * read back (constitution Principle III). Both are frozen, both are
 * {@code additionalProperties: false}, and the second is reached through
 * {@link #overTheRegisterDocument}. Everything below this line is common to both: the same vendored
 * copies, the same resolution with no network in it, the same bounded refusal.
 */
public final class OutboundContractValidator implements RegisterDocumentValidator {

    /** Where the vendored contract lives on the classpath. */
    private static final String CONTRACT_ROOT = "contracts/progression/";

    /** The command this service POSTs, and therefore the schema a POST body is held to. */
    private static final String COMMAND_SCHEMA =
            CONTRACT_ROOT + "progression.add-court-register.json";

    /**
     * The register document itself, and therefore the schema a <em>stored</em> register is held to.
     *
     * <p>The same family, one field wider: it declares {@code defendantType} and
     * {@code courtApplicationId}, which the command does not, and requires exactly what the command
     * requires. That difference is the whole reason there are two instances of this class - the
     * document 002 records is the document plus the defendant type, so holding it to the command
     * would refuse it under {@code UNKNOWN_FIELD [/defendantType]} and holding it to nothing would
     * store a field no contract had ever been applied to.
     */
    private static final String REGISTER_DOCUMENT_SCHEMA =
            CONTRACT_ROOT + "courtRegisterDocumentRequest.json";

    /**
     * Every identity the vendored schemas answer to, mapped to where the vendored copy sits.
     *
     * <p>The {@code $ref}s inside the contract are absolute {@code http://justice.gov.uk/…} IRIs,
     * which is how progression's own build resolves them out of the
     * {@code criminal-court-public-model} artefact. Nothing here may go to the network to find one:
     * a validation that silently degraded to "could not fetch the schema, so nothing was checked"
     * would reinstate exactly the blind spot C29 exists to close, and a validation that reached the
     * internet on the hot path would be worse. So every identity is listed, and an identity that is
     * not listed fails loudly when the schema is assembled at construction rather than quietly at
     * validation time.
     */
    private static final Map<String, String> VENDORED_SCHEMAS = Map.ofEntries(
            vendored("core/courts/courtRegisterDocument/courtRegisterAddress.json"),
            vendored("core/courts/courtRegisterDocument/courtRegisterCaseOrApplication.json"),
            vendored("core/courts/courtRegisterDocument/courtRegisterCounsel.json"),
            vendored("core/courts/courtRegisterDocument/courtRegisterDefendant.json"),
            vendored("core/courts/courtRegisterDocument/courtRegisterDefendantAlias.json"),
            vendored("core/courts/courtRegisterDocument/courtRegisterDocumentRequest.json"),
            vendored("core/courts/courtRegisterDocument/courtRegisterHearing.json"),
            vendored("core/courts/courtRegisterDocument/courtRegisterHearingVenue.json"),
            vendored("core/courts/courtRegisterDocument/courtRegisterOffence.json"),
            vendored("core/courts/courtRegisterDocument/courtRegisterParentGuardian.json"),
            vendored("core/courts/courtRegisterDocument/courtRegisterRecipient.json"),
            vendored("core/courts/courtRegisterDocument/courtRegisterResult.json"),
            vendored("core/courts/courtsDefinitions.json"),
            vendored("domain/core/common/definitions.json"));

    /**
     * How a broken schema rule is named to the rest of the service.
     *
     * <p>{@code minItems} is an {@link ContractViolation#INVALID_FORMAT} rather than a
     * {@link ContractViolation#MISSING_FIELD}: this register's contract puts {@code minItems: 1} on
     * every list it declares, so an empty array is a field that is present and wrong, where
     * {@code MISSING_FIELD} is defined as absent, null or an empty <em>string</em>. The distinction
     * matters downstream — an absent recipients list is a register with no recipients, which the
     * command accepts, and an empty one is a document it refuses.
     */
    private static final Map<String, ContractViolation> VIOLATION_BY_KEYWORD = Map.of(
            "required", ContractViolation.MISSING_FIELD,
            "additionalProperties", ContractViolation.UNKNOWN_FIELD,
            "enum", ContractViolation.INVALID_ENUM_VALUE,
            "const", ContractViolation.INVALID_ENUM_VALUE);

    /** Renders the typed document to the tree the schema is applied to. */
    private final ObjectMapper json;

    /** The frozen contract, assembled once. */
    private final Schema contract;

    /**
     * Creates the validator over the {@code add-court-register} command.
     *
     * <p>The command is what a {@code progression-post} deployment sends and what the transformation
     * holds every assembled register to, so it stays the constructor: an instance asked for without
     * a schema is the one 001 asked for.
     *
     * @param objectMapper the service's contract mapper, which is what serialises the document on
     *                     the way out too — validating with a different one would validate a
     *                     document nobody sends
     */
    public OutboundContractValidator(final ObjectMapper objectMapper) {
        this(objectMapper, COMMAND_SCHEMA);
    }

    /**
     * Creates the validator over one of the two frozen schemas.
     *
     * @param objectMapper the service's contract mapper
     * @param schema       the classpath resource the document is held to
     */
    private OutboundContractValidator(final ObjectMapper objectMapper, final String schema) {
        this.json = objectMapper;
        this.contract = assembleContract(schema);
    }

    /**
     * Creates the validator over the frozen register document, for the write into the store.
     *
     * <p>A second instance rather than a widened schema. The two contracts are both frozen and both
     * real: what goes to progression must satisfy the command, and what is written into this
     * service's store must satisfy the document the batch will read back. They differ by the fields
     * the command does not declare, so one validator could only be built by relaxing the check that
     * catches a POST body carrying one of them.
     *
     * @param objectMapper the service's contract mapper, which is what serialises the document into
     *                     the store too
     * @return a validator over {@code courtRegisterDocumentRequest.json}
     */
    public static OutboundContractValidator overTheRegisterDocument(
            final ObjectMapper objectMapper) {
        return new OutboundContractValidator(objectMapper, REGISTER_DOCUMENT_SCHEMA);
    }

    /**
     * Refuses a document the frozen contract would.
     *
     * @param document the assembled register
     * @throws ContractValidationException where the document does not satisfy the vendored schemas,
     *         carrying the bounded violation and the JSON pointer of the offending field
     */
    @Override
    public void validate(final CourtRegisterDocument document) {
        final JsonNode wire = json.valueToTree(document);
        final List<Error> refusals = contract.validate(wire);
        if (!refusals.isEmpty()) {
            // The document can break several rules at once and a bounded reason names one, so which
            // one is named is sorted by pointer rather than left to the order the validator happened
            // to walk the tree in: the same document always produces the same reason, and the
            // shortest pointer of a nested pair — the outer, more fundamental failure — sorts first.
            final Error refusal = refusals.stream()
                    .min(Comparator.comparing(OutboundContractValidator::pointerOf))
                    .orElseThrow();
            throw new ContractValidationException(violationOf(refusal), pointerOf(refusal));
        }
    }

    /**
     * Where on the document the rule was broken, as a JSON pointer.
     *
     * <p>A {@code required} failure is reported by the validator against the object that should have
     * carried the field, with the field's name alongside, so the name is appended here to give the
     * pointer a reader can follow straight to the gap. Every other keyword already points at the
     * value it refused.
     *
     * <p>It is a path and never a value. This string reaches the dead-letter description and the log
     * index, and every defendant on this register is a child.
     */
    private static String pointerOf(final Error refusal) {
        final String property = refusal.getProperty();
        return property == null
                ? refusal.getInstanceLocation().toString()
                : refusal.getInstanceLocation().append(property).toString();
    }

    /** The bounded name for the schema keyword the document fell foul of. */
    private static ContractViolation violationOf(final Error refusal) {
        return VIOLATION_BY_KEYWORD.getOrDefault(
                refusal.getKeyword(), ContractViolation.INVALID_FORMAT);
    }

    /** One vendored schema, as the identity it publishes mapped to the copy this repo holds. */
    private static Map.Entry<String, String> vendored(final String publishedPath) {
        final String fileName = publishedPath.substring(publishedPath.lastIndexOf('/') + 1);
        return Map.entry(
                "http://justice.gov.uk/" + publishedPath, "classpath:" + CONTRACT_ROOT + fileName);
    }

    /**
     * Reads the command schema and wires its references to the vendored copies.
     *
     * <p>Formats are assertions rather than annotations, which is draft-04's own reading of them and
     * progression's: a {@code date-time} that is not one, or an email address that is not one, is a
     * document progression refuses, so it has to be a document this refuses first.
     *
     * @param schema the classpath resource of the root schema, which is one of the two above; the
     *               references inside either of them resolve to the same vendored copies
     * @return the assembled contract
     */
    private Schema assembleContract(final String schema) {
        final SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(Boolean.TRUE)
                // JSON pointers, so a bounded reason can name the field the way the document is
                // addressed rather than in a syntax of the validator's own.
                .pathType(PathType.JSON_POINTER)
                // Resolve every reference now, at construction, so a contract this build cannot
                // assemble refuses to start rather than failing one register at a time.
                .preloadSchema(true)
                .build();
        final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_4,
                builder -> builder
                        .schemaRegistryConfig(config)
                        .schemaIdResolvers(resolvers -> resolvers.mappings(VENDORED_SCHEMAS)));
        return registry.getSchema(resourceText(schema));
    }

    /** The text of a committed classpath resource, or a failure that names which one is missing. */
    private static String resourceText(final String resource) {
        try (InputStream stream =
                     OutboundContractValidator.class.getResourceAsStream("/" + resource)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "the vendored progression contract must be committed at " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
