package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.inbound.DistributionCommandParser;

/**
 * Holds the production parser and the committed draft-07 schema to each other.
 *
 * <p>"The tests keep them in step" only means something if the tests actually compare the two, so
 * every corpus case — valid and invalid alike — is run through both, and the test asserts they agree
 * on accept or reject. A case the schema accepts and the parser rejects, or the reverse, is a
 * failure rather than a curiosity.
 *
 * <p>The corpus is deliberately weighted towards the boundaries where a hand-written parser and a
 * schema most easily drift: well-formed but non-existent dates, non-canonical identifiers,
 * offset-bearing against {@code Z} instants, empty strings and nulls, and one unknown field on an
 * otherwise valid body.
 *
 * <p>Required-field presence, the two enumerations and the closedness of the contract are asserted
 * <em>from the schema document itself</em>, so adding a field to the schema without teaching the
 * parser about it fails the build.
 */
class DistributionCommandSchemaCorpusTest {

    private static final String SCHEMA_RESOURCE = "contracts/distribution-command.schema.json";

    /**
     * The identifier this service's inbound contract is published under.
     *
     * <p>Named explicitly because the schema is a clone of the informant register's and the one
     * thing a clone must not keep is the original's identity: two services answering to one
     * {@code $id} is how a consumer ends up validating court-register traffic against the informant
     * contract, or the reverse.
     */
    private static final String SCHEMA_ID =
            "https://hmcts.gov.uk/cp/crime/court-register/contracts/distribution-command.schema.json";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();
    private static final DistributionCommandParser PARSER = new DistributionCommandParser(MAPPER);

    private static final String CANONICAL_REQUEST_ID = "\"3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8\"";
    private static final String CANONICAL_HEARING_ID = "\"11111111-2222-4333-8444-555555555555\"";
    private static final String CANONICAL_USER_ID = "\"0b7a5c2e-4d19-4a6b-8c30-9e1f5d7b2a48\"";

    /**
     * The one declared property the contract does not require.
     *
     * <p>Named once, here, so every assertion below that has to treat it differently says why in the
     * same terms: a message may legitimately carry no user — a replay that does not carry the
     * original body names none, and neither does a producer build from before the field existed — so
     * absent is valid and the service falls back to its configured system identity.
     */
    private static final String OPTIONAL_FIELD = "userId";

    /** The six the contract requires of every message. */
    private static final List<String> REQUIRED_FIELDS = List.of(
            "source", "requestId", "hearingId", "hearingDay", "sharedTime", "eventType");

    /** The seven the contract declares: the six required, plus the optional sharing user. */
    private static final List<String> AGREED_FIELDS = List.of(
            "source", "requestId", "hearingId", "hearingDay", "sharedTime", "eventType",
            OPTIONAL_FIELD);

    private static String cachedSchemaText;
    private static JsonNode cachedSchemaDocument;
    private static Schema cachedSchema;

    // --- fixture plumbing ------------------------------------------------------------------

    /**
     * The committed schema's text.
     *
     * <p>Read on demand rather than in a static initialiser so that an absent contract fails as the
     * assertion it is — "the contract schema must be committed" — rather than as an initialiser
     * error that says nothing about which artefact is missing.
     */
    private static String schemaText() {
        if (cachedSchemaText == null) {
            try (InputStream stream = Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream(SCHEMA_RESOURCE)) {
                assertThat(stream)
                        .as("the inbound contract schema must be committed at %s", SCHEMA_RESOURCE)
                        .isNotNull();
                cachedSchemaText = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
        return cachedSchemaText;
    }

    private static JsonNode schemaDocument() {
        if (cachedSchemaDocument == null) {
            cachedSchemaDocument = MAPPER.readTree(schemaText());
        }
        return cachedSchemaDocument;
    }

    private static Schema schema() {
        if (cachedSchema == null) {
            final SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                    // Formats are assertions here, not annotations: `uuid`, `date` and `date-time`
                    // must actually be checked or the corpus proves nothing about format drift.
                    .formatAssertionsEnabled(Boolean.TRUE)
                    .build();
            final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                    SpecificationVersion.DRAFT_7,
                    builder -> builder.schemaRegistryConfig(config));
            cachedSchema = registry.getSchema(schemaText());
        }
        return cachedSchema;
    }

    /**
     * Renders a body from field name to raw JSON text, so a case can supply a null, a number or an
     * empty string as easily as a well-formed value.
     */
    private static String render(final Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(entry -> "  \"" + entry.getKey() + "\": " + entry.getValue())
                .collect(Collectors.joining(",\n", "{\n", "\n}"));
    }

    private static Map<String, String> canonicalFields() {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("source", "\"RESULTS\"");
        fields.put("requestId", CANONICAL_REQUEST_ID);
        fields.put("hearingId", CANONICAL_HEARING_ID);
        fields.put("hearingDay", "\"2026-08-20\"");
        fields.put("sharedTime", "\"2026-08-20T09:00:00Z\"");
        fields.put("eventType", "\"Hearing_Resulted\"");
        return fields;
    }

    private static String bodyWith(final String field, final String rawValue) {
        final Map<String, String> fields = canonicalFields();
        fields.put(field, rawValue);
        return render(fields);
    }

    private static String bodyWithout(final String field) {
        final Map<String, String> fields = canonicalFields();
        fields.remove(field);
        return render(fields);
    }

    private static boolean parserAccepts(final String body) {
        boolean accepted;
        try {
            PARSER.parse(body);
            accepted = true;
        } catch (ContractValidationException rejected) {
            accepted = false;
        }
        return accepted;
    }

    private static boolean schemaAccepts(final String body) {
        Optional<JsonNode> parsed;
        try {
            parsed = Optional.of(MAPPER.readTree(body));
        } catch (JacksonException malformed) {
            // A body the parser cannot even read is a rejection on both sides.
            parsed = Optional.empty();
        }
        return parsed.filter(node -> schema().validate(node).isEmpty()).isPresent();
    }

    private static void assertAgreement(final String body, final boolean expectedAccepted) {
        final boolean schemaVerdict = schemaAccepts(body);
        final boolean parserVerdict = parserAccepts(body);

        assertThat(parserVerdict)
                .as("parser and schema must agree (parser=%s, schema=%s) for body:%n%s",
                        parserVerdict, schemaVerdict, body)
                .isEqualTo(schemaVerdict);
        assertThat(parserVerdict)
                .as("expected the corpus case to be %s:%n%s",
                        expectedAccepted ? "accepted" : "rejected", body)
                .isEqualTo(expectedAccepted);
    }

    // --- the corpus ------------------------------------------------------------------------

    private record CorpusCase(String name, String body, boolean accepted) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static CorpusCase accepted(final String name, final String body) {
        return new CorpusCase(name, body, true);
    }

    private static CorpusCase rejected(final String name, final String body) {
        return new CorpusCase(name, body, false);
    }

    static Stream<CorpusCase> corpus() {
        return Stream.of(
                // --- accepted -----------------------------------------------------------
                accepted("canonical body", render(canonicalFields())),
                accepted("uppercase-hex identifiers",
                        render(canonicalFields()).toUpperCase(Locale.ROOT)
                                .replace("\"SOURCE\"", "\"source\"")
                                .replace("\"REQUESTID\"", "\"requestId\"")
                                .replace("\"HEARINGID\"", "\"hearingId\"")
                                .replace("\"HEARINGDAY\"", "\"hearingDay\"")
                                .replace("\"SHAREDTIME\"", "\"sharedTime\"")
                                .replace("\"EVENTTYPE\"", "\"eventType\"")
                                .replace("\"HEARING_RESULTED\"", "\"Hearing_Resulted\"")),
                accepted("instant bearing a positive offset",
                        bodyWith("sharedTime", "\"2026-08-20T10:00:00+01:00\"")),
                accepted("instant bearing a negative offset",
                        bodyWith("sharedTime", "\"2026-08-20T04:00:00-05:00\"")),
                accepted("instant with fractional seconds",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00.123456Z\"")),
                accepted("instant with nanosecond precision",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00.123456789Z\"")),
                accepted("zero offset written out rather than as Z",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00+00:00\"")),
                // RFC 3339 permits the lower-case forms, and so must this parser: rejecting them
                // would be a new divergence from the schema, not a tightening.
                accepted("lower-case date-time separator",
                        bodyWith("sharedTime", "\"2026-08-20t09:00:00Z\"")),
                accepted("lower-case zulu designator",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00z\"")),
                accepted("leap day in a leap year", bodyWith("hearingDay", "\"2024-02-29\"")),

                // --- accepted: the optional user, present and absent ---------------------
                // The canonical body above is already the absent case, which is what the transition
                // window and every replayed message look like. Both shapes are contract-valid, and a
                // producer that starts sending the field must not need this consumer redeployed.
                accepted("body carrying the sharing user", bodyWith(OPTIONAL_FIELD, CANONICAL_USER_ID)),
                accepted("body carrying an uppercase-hex user",
                        bodyWith(OPTIONAL_FIELD, CANONICAL_USER_ID.toUpperCase(Locale.ROOT))),

                // --- rejected: a user that could not be an identity ----------------------
                // Optional means "may be absent", never "may be anything". A value that is not an
                // identity must not be sent as one, so it dead-letters like any other violation.
                rejected("user that is not a UUID", bodyWith(OPTIONAL_FIELD, "\"not-a-uuid\"")),
                rejected("braced user", bodyWith(OPTIONAL_FIELD,
                        "\"{0b7a5c2e-4d19-4a6b-8c30-9e1f5d7b2a48}\"")),
                rejected("unhyphenated user",
                        bodyWith(OPTIONAL_FIELD, "\"0b7a5c2e4d194a6b8c309e1f5d7b2a48\"")),
                rejected("empty user", bodyWith(OPTIONAL_FIELD, "\"\"")),
                // An explicit null is not the same as saying nothing: the property is present and
                // typed `string`, so the schema refuses it and so must the parser. Absence is the
                // only way to say "no user".
                rejected("null user", bodyWith(OPTIONAL_FIELD, "null")),
                rejected("user as a number", bodyWith(OPTIONAL_FIELD, "42")),

                // --- rejected: dates that do not exist ----------------------------------
                rejected("day beyond the month's length", bodyWith("hearingDay", "\"2026-02-30\"")),
                rejected("month beyond twelve", bodyWith("hearingDay", "\"2026-13-01\"")),
                rejected("leap day in a common year", bodyWith("hearingDay", "\"2025-02-29\"")),
                rejected("date in a non-ISO layout", bodyWith("hearingDay", "\"20/08/2026\"")),
                // Java's ISO date parser accepts a signed, expanded year; RFC 3339 does not, so the
                // schema rejects both of these and the parser must too.
                rejected("date with an expanded positive year", bodyWith("hearingDay", "\"+12026-08-20\"")),
                rejected("date with a signed negative year", bodyWith("hearingDay", "\"-0001-08-20\"")),
                rejected("date with an unpadded month", bodyWith("hearingDay", "\"2026-8-20\"")),

                // --- rejected: non-canonical identifiers --------------------------------
                rejected("braced identifier",
                        bodyWith("requestId", "\"{3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8}\"")),
                rejected("unhyphenated identifier",
                        bodyWith("hearingId", "\"1111111122224333844455555555 5555\"".replace(" ", ""))),
                rejected("identifier that is not a UUID at all",
                        bodyWith("requestId", "\"not-a-uuid\"")),

                // --- rejected: instants -------------------------------------------------
                rejected("instant with no offset at all",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00\"")),
                rejected("instant that is not a date-time", bodyWith("sharedTime", "\"yesterday\"")),
                // Java's ISO offset parser is looser than RFC 3339 in four separate ways. Each has
                // its own case, because each is a way a producer's clock library could drift out of
                // contract without anything noticing.
                rejected("offset carrying seconds",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00+01:00:30\"")),
                rejected("instant with no seconds", bodyWith("sharedTime", "\"2026-08-20T09:00Z\"")),
                rejected("instant with an expanded year",
                        bodyWith("sharedTime", "\"+12026-08-20T09:00:00Z\"")),
                rejected("negative zero offset",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00-00:00\"")),
                rejected("fractional part with no digits",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:00.Z\"")),
                rejected("offset with no colon", bodyWith("sharedTime", "\"2026-08-20T09:00:00+0100\"")),
                // A second numbered 60 is grammatical in RFC 3339 only where a leap second was
                // actually inserted, and unrepresentable in java.time wherever it appears: there is
                // no Instant for a 61st second, so accepting one would mean inventing a value to
                // store. The schema's pattern therefore excludes :60 outright rather than deferring
                // to a leap-second table, which is a moving dependency on the contract's hot path —
                // and both sides refuse all four of these together.
                rejected("second 60 on an ordinary day",
                        bodyWith("sharedTime", "\"2026-08-20T09:00:60Z\"")),
                rejected("second 60 at a plausible but unannounced leap instant",
                        bodyWith("sharedTime", "\"2026-06-30T23:59:60Z\"")),
                rejected("second 60 at the verified leap instant of 31 December 2016",
                        bodyWith("sharedTime", "\"2016-12-31T23:59:60Z\"")),
                rejected("second 60 at the verified leap instant of 30 June 2015",
                        bodyWith("sharedTime", "\"2015-06-30T23:59:60Z\"")),
                // RFC 3339's grammar is date-time = full-date "T" full-time. The space form appears
                // only in the readability note of section 5.6, which *permits* an application to
                // accept it; the schema states the ABNF and this service's producer serialises with
                // Instant.toString(), which never emits it. All three separator forms are refused.
                rejected("space separator with no offset",
                        bodyWith("sharedTime", "\"2026-08-20 09:00:00\"")),
                rejected("space separator with a zulu designator",
                        bodyWith("sharedTime", "\"2026-08-20 09:00:00Z\"")),
                rejected("space separator with a numeric offset",
                        bodyWith("sharedTime", "\"2026-08-20 09:00:00+01:00\"")),

                // --- rejected: empties, nulls and wrong types ---------------------------
                rejected("empty source", bodyWith("source", "\"\"")),
                rejected("empty requestId", bodyWith("requestId", "\"\"")),
                rejected("null source", bodyWith("source", "null")),
                rejected("null requestId", bodyWith("requestId", "null")),
                rejected("source as a number", bodyWith("source", "42")),
                rejected("hearingDay as a number", bodyWith("hearingDay", "20260820")),

                // --- rejected: enumerations and closedness ------------------------------
                rejected("source outside the agreed enumeration", bodyWith("source", "\"SJP\"")),
                // The court register has no SJP leg at all, so Hearing_Resulted is the whole
                // enumeration and an SJP-shaped event is a producer defect, not a variant.
                rejected("eventType outside the agreed enumeration",
                        bodyWith("eventType", "\"SJP_Resulted\"")),
                rejected("one unknown field on an otherwise valid body",
                        render(withExtraField("courtCentreId", "\"abc\""))),

                // --- rejected: not an object at all -------------------------------------
                rejected("body that is not JSON", "this is not json"),
                rejected("body that is a JSON array", "[]"),
                rejected("body that is a JSON string", "\"hello\""));
    }

    private static Map<String, String> withExtraField(final String name, final String rawValue) {
        final Map<String, String> fields = canonicalFields();
        fields.put(name, rawValue);
        return fields;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    @DisplayName("parser and schema agree on every boundary case")
    void parser_and_schema_should_agree(final CorpusCase corpusCase) {
        assertAgreement(corpusCase.body(), corpusCase.accepted());
    }

    /**
     * The agreement is total: there is no exemption list.
     *
     * <p>There used to be one. Four forms — the two space-separated date-times of RFC 3339's
     * readability note, and the two verified leap seconds — were accepted by the reference validator
     * and refused by the parser, and the divergence was written down rather than closed. That is a
     * contradiction the corpus cannot hold: it asserts the schema and the parser agree, and a
     * documented exemption is the schema and the parser disagreeing with a note attached. Whichever
     * of the two a future reader consults, one of them is wrong about what this service accepts.
     *
     * <p>The schema is the authority, this service owns it, and no producer is live yet — so the
     * schema was narrowed to say what the parser already meant: {@code sharedTime} carries an
     * RFC 3339 {@code pattern} alongside its {@code date-time} format, admitting only the
     * {@code T}-separated grammar and excluding a 61st second, and {@code hearingDay} carries the
     * {@code full-date} pattern. The four forms are now ordinary rejected corpus cases above, and
     * the strict agreement assertion covers every case there is.
     */
    @Test
    @DisplayName("the schema states the date-time grammar it means, rather than deferring it")
    void the_schema_should_constrain_the_instant_with_the_grammar_the_parser_enforces() {
        assertThat(schemaDocument().get("properties").get("sharedTime").get("pattern"))
                .as("draft-07's date-time format is as wide as whichever validator reads it; the "
                        + "pattern is how the contract document itself states the grammar")
                .isNotNull();
        assertThat(schemaDocument().get("properties").get("hearingDay").get("pattern")).isNotNull();
    }

    // --- assertions taken mechanically from the schema document -----------------------------

    private static List<String> requiredFieldNames() {
        final List<String> names = new ArrayList<>();
        schemaDocument().get("required").forEach(node -> names.add(node.stringValue()));
        return names;
    }

    private static List<String> enumValuesOf(final String field) {
        final List<String> values = new ArrayList<>();
        schemaDocument().get("properties").get(field).get("enum")
                .forEach(node -> values.add(node.stringValue()));
        return values;
    }

    private static List<String> declaredPropertyNames() {
        return List.copyOf(schemaDocument().get("properties").propertyNames());
    }

    static Stream<String> requiredFields() {
        return requiredFieldNames().stream();
    }

    static Stream<String> declaredProperties() {
        return declaredPropertyNames().stream();
    }

    @Test
    @DisplayName("the schema is this service's own, under the court-register identifier")
    void the_schema_should_be_published_under_the_court_register_identifier() {
        // The contract is cloned from the informant register's. Keeping its $id would leave two
        // services claiming one identity, which is how traffic ends up validated against the wrong
        // contract by a consumer that resolves the reference rather than the file.
        assertThat(schemaDocument().get("$schema").stringValue())
                .isEqualTo("http://json-schema.org/draft-07/schema#");
        assertThat(schemaDocument().get("$id").stringValue()).isEqualTo(SCHEMA_ID);
        assertThat(schemaDocument().get("description").stringValue())
                .as("the description names the queue the contract governs")
                .contains("yotresultsdistribution.requests");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requiredFields")
    @DisplayName("every field the schema requires is required by the parser too")
    void a_body_missing_a_required_field_should_be_rejected_by_both(final String field) {
        assertAgreement(bodyWithout(field), false);
    }

    @Test
    @DisplayName("the schema requires exactly the six agreed fields")
    void the_schema_should_require_the_six_agreed_fields() {
        assertThat(requiredFieldNames()).containsExactlyInAnyOrder(REQUIRED_FIELDS.toArray(new String[0]));
    }

    @Test
    @DisplayName("the schema declares exactly the agreed fields, and no eighth")
    void the_schema_should_declare_no_property_beyond_the_agreed_set() {
        // Without this, a property added to the schema would sail through: the required-field
        // assertions would not notice an optional one, and the corpus only knows the unknown-field
        // names it was written with. The parser would reject a body carrying it while the schema
        // accepted one — a silent divergence, which is the exact failure this suite exists to stop.
        assertThat(declaredPropertyNames()).containsExactlyInAnyOrder(AGREED_FIELDS.toArray(new String[0]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("declaredProperties")
    @DisplayName("every property the schema declares is required by it, bar the one agreed optional")
    void a_declared_property_should_also_be_required(final String field) {
        // The exemption is a list of one and is written down as such. An optional property is a
        // property the parser could reject while the schema accepted it, so each one has to be
        // taught to the parser deliberately — which is what the absence cases below assert.
        if (!OPTIONAL_FIELD.equals(field)) {
            assertThat(requiredFieldNames())
                    .as("an optional property is a property the parser would reject and the schema "
                            + "accept, unless it is the agreed optional one")
                    .contains(field);
        }
    }

    @Test
    @DisplayName("the one optional property is the agreed one, and it really is optional")
    void the_optional_property_should_be_the_agreed_one_and_should_be_accepted_either_way() {
        assertThat(declaredPropertyNames())
                .as("the optional property must actually be declared, or nothing below tests it")
                .contains(OPTIONAL_FIELD);
        assertThat(requiredFieldNames())
                .as("the sharing user is optional: a replay without the original body and a "
                        + "transition-window producer carry none, and a required field would "
                        + "dead-letter both")
                .doesNotContain(OPTIONAL_FIELD);

        assertAgreement(bodyWithout(OPTIONAL_FIELD), true);
        assertAgreement(bodyWith(OPTIONAL_FIELD, CANONICAL_USER_ID), true);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("declaredProperties")
    @DisplayName("every declared property is exercised through both validators")
    void a_declared_property_holding_a_wrong_typed_value_should_be_rejected_by_both(final String field) {
        // Drives each declared property through both validators by name rather than by a
        // hand-written list, so a property added to the schema is exercised the moment it appears.
        // A wrong-typed value is refused whatever the property; an absent one is refused only where
        // the contract requires the property.
        assertAgreement(bodyWith(field, "{}"), false);
        assertAgreement(bodyWithout(field), !REQUIRED_FIELDS.contains(field));
    }

    @Test
    @DisplayName("the parser accepts every source the schema enumerates")
    void every_enumerated_source_should_be_accepted_by_both() {
        assertThat(enumValuesOf("source")).isNotEmpty();
        enumValuesOf("source").forEach(value -> assertAgreement(bodyWith("source", "\"" + value + "\""), true));
    }

    @Test
    @DisplayName("the parser accepts every eventType the schema enumerates")
    void every_enumerated_event_type_should_be_accepted_by_both() {
        // Hearing_Resulted and nothing else: the court register has no SJP leg.
        assertThat(enumValuesOf("eventType")).containsExactly("Hearing_Resulted");
        enumValuesOf("eventType")
                .forEach(value -> assertAgreement(bodyWith("eventType", "\"" + value + "\""), true));
    }

    @Test
    @DisplayName("the contract is closed, and the parser enforces the closure")
    void the_schema_should_be_closed_and_the_parser_should_agree() {
        assertThat(schemaDocument().get("additionalProperties").booleanValue()).isFalse();
        assertAgreement(render(withExtraField("somethingNew", "\"value\"")), false);
    }
}
