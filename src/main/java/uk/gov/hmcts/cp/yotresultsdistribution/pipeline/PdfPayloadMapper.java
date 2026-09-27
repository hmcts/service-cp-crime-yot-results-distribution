package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The render payload one batch is turned into, exactly as progression turned it.
 *
 * <p>A Java to Java port of {@code PROG CourtRegisterPdfPayloadGenerator} (364 lines), bug for bug:
 * the same helpers in the same order, the same DASH fallbacks, the same date formats, and C24's
 * {@code ####} sentinel substituted for a newline where progression substitutes it. What the port
 * changes is the JSON library and nothing else, {@code javax.json} for a Jackson tree, which is why
 * the signature is tree in and tree out rather than the typed model the rest of this package deals
 * in: the generator read the raw document, and a port that read a typed one would be a rewrite whose
 * output nobody could compare.
 *
 * <p>The comparison is the point. Every golden under
 * {@code src/test/resources/goldens/progression/pdf-payload/} was recorded by executing
 * progression's own class, and {@code PdfPayloadMapperTest} requires this one to be byte-identical
 * to all of them.
 *
 * <p><strong>The clock is a dependency because one field reads it.</strong> Progression's
 * {@code getAge} ({@code :323-329}) is
 * {@code Period.between(dateOfBirth, LocalDate.now()).getYears()}, and {@code age} is the only field
 * in the whole payload that is a function of when the mapping runs rather than of the document.
 * {@code LocalDate.now()} cannot be pinned from outside the JVM, so the goldens carry the date they
 * were recorded on ({@code INDEX.json} {@code ageClockDate}) and the port takes the clock it counts
 * against, which is what lets {@code PdfPayloadMapperTest} compare an age recorded on one day with
 * one computed on another.
 *
 * <p><strong>The five readers at the bottom are the port's whole translation layer.</strong>
 * {@code javax.json}'s {@code JsonObject} answers a two-argument {@code getString} with its default
 * whenever the property is absent or is not a {@code JsonString} (a JSON {@code null}, a number and
 * an object all take the default), while its one-argument {@code getString} throws on every one of
 * those, and {@code getJsonObject} / {@code getJsonArray} answer {@code null} for an absent property
 * and throw a {@link ClassCastException} for one of the wrong shape. {@link #getString(JsonNode,
 * String, String)}, {@link #getString(JsonNode, String)}, {@link #getJsonObject} and
 * {@link #getJsonArray} are those four rules over a Jackson tree, so a line of the port reads as the
 * line of the generator it stands for. The types the strict readers throw are Jackson's rather than
 * {@code javax.json}'s; which line throws, and on which document, is unchanged, and no such
 * document reaches this mapper in service, because the contract validator refuses it at the write
 * (see {@code goldens/progression/PROVENANCE.md}, "The 52 refusals").
 */
// PMD.OnlyOneReturn: every branch here is the generator's own if/else, answered where it decides;
// funnelling them through a single exit would reshape the control flow the port is reviewed
// against.
@SuppressWarnings("PMD.OnlyOneReturn")
public class PdfPayloadMapper {

    /** The array the batch's recorded documents arrive in. */
    private static final String COURT_REGISTER_DOCUMENT_REQUESTS = "courtRegisterDocumentRequests";

    private static final String ADDRESS = "address";
    private static final String PARENT_GUARDIAN = "parentGuardian";
    private static final String PLEA_DATE = "pleaDate";
    private static final String SEPARATOR = ", ";
    private static final String DASH = "-";
    private static final String PROSECUTION_COUNSELS = "prosecutionCounsels";

    /** {@code DateTimeFormats.STANDARD}, the format every date in the document is written in. */
    private static final DateTimeFormatter IN_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** {@code DateTimeFormats.DATE_SLASHED_DD_MM_YYYY}, the format the register prints. */
    private static final DateTimeFormatter OUT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final String CJS_RESULT_CODE = "cjsResultCode";
    private static final String RESULT_CODE = "resultCode";
    private static final String RESULT_TEXT = "resultText";
    private static final String RESULTS = "results";
    private static final String POSTCODE = "postCode";
    private static final String CONVICTION_DATE = "convictionDate";

    /** The property a person's name is read from, at all four places one is read. */
    private static final String NAME = "name";

    /** The property {@code getAge} counts from, and the one line 75 prints. */
    private static final String DATE_OF_BIRTH = "dateOfBirth";

    /** The five address lines, in the order every address is composed from them. */
    private static final List<String> ADDRESS_LINES = List.of("1", "2", "3", "4", "5");

    private static final String BLANK = " ";
    private static final String NEW_LINE = "\n";

    /** C24's sentinel: what the register's producer writes where the template wants a newline. */
    private static final String DESIRED_NEW_LINE = "####";

    /** The tree the payload is built with; shared and immutable. */
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** The clock {@code getAge} counts years against. */
    private final Clock clock;

    /**
     * Creates the mapper.
     *
     * @param clock the clock ages are counted against, which the goldens pin to the date they were
     *              recorded on
     */
    public PdfPayloadMapper(final Clock clock) {
        this.clock = clock;
    }

    /**
     * Maps one batch's documents to the payload systemdocgenerator renders.
     *
     * <p>The input is the shape progression's {@code CourtRegisterGenerated} event carried, which is
     * what its generator was written against: an object whose {@code courtRegisterDocumentRequests}
     * array holds the batch's recorded documents in the order they were assembled.
     *
     * <p>The header (the register date, the LJA, the court house and its address) is read from
     * <strong>one</strong> of those documents ({@code :47}'s {@code stream().findAny()}, the first
     * in practice), and the cases from all of them. A batch whose first row names a different court
     * house than the rest still prints the first row's, which is what the 127-document golden pins.
     *
     * @param courtRegisterGeneratedShapedInput the batch's documents under
     *                                          {@code courtRegisterDocumentRequests}
     * @return the payload, ready to be written to the file service
     */
    public JsonNode mapPayload(final JsonNode courtRegisterGeneratedShapedInput) {
        final ObjectNode payloadForPdf = NODES.objectNode();
        getJsonArray(courtRegisterGeneratedShapedInput, COURT_REGISTER_DOCUMENT_REQUESTS)
                .valueStream().findAny()
                .ifPresent(json -> {
                    payloadForPdf.put("registerDate",
                            formatZonedDate(getString(json, "registerDate")));
                    Optional.ofNullable(getJsonObject(json, "hearingVenue"))
                            .ifPresent(hearingVenue -> {
                                if (containsKey(hearingVenue, "ljaName")) {
                                    payloadForPdf.put("ljaName",
                                            getString(hearingVenue, "ljaName"));
                                }
                                payloadForPdf.put("courtHouse",
                                        getString(hearingVenue, "courtHouse", DASH));
                                Optional.ofNullable(getJsonObject(hearingVenue, ADDRESS)).ifPresent(
                                        address -> payloadForPdf.put("courtHouseAddress",
                                                buildCourtHouseAddress(address)));
                            });
                });

        final ArrayNode caseArray = NODES.arrayNode();
        getJsonArray(courtRegisterGeneratedShapedInput, COURT_REGISTER_DOCUMENT_REQUESTS)
                .valueStream()
                .forEach(courtRegisterDocumentRequest ->
                        getJsonArray(courtRegisterDocumentRequest, "defendants").valueStream()
                                .forEach(defendant -> getJsonArray(
                                        defendant, "prosecutionCasesOrApplications").valueStream()
                                        .toList()
                                        .forEach(pcoa -> caseArray.add(mapCase(
                                                courtRegisterDocumentRequest, defendant, pcoa)))));
        payloadForPdf.set("cases", caseArray);

        return payloadForPdf;
    }

    /**
     * One entry of {@code cases}: a defendant's case or application, as the register prints it.
     *
     * <p>The body of {@code :62-92}'s innermost {@code forEach}, lifted only so that the three
     * nested iterations above stay readable as the three nested iterations they are.
     *
     * @param courtRegisterDocumentRequest the recorded document the case was assembled from
     * @param defendant                    the defendant the case belongs to
     * @param pcoa                         the prosecution case or court application
     * @return the case
     */
    private ObjectNode mapCase(final JsonNode courtRegisterDocumentRequest,
            final JsonNode defendant, final JsonNode pcoa) {

        final ObjectNode caseJson = NODES.objectNode();
        caseJson.put("defendantType", getString(courtRegisterDocumentRequest, "defendantType", ""));
        buildNameAndAddress(defendant, caseJson);
        buildHearingDetails(defendant, caseJson);
        buildDefenceCounsel(defendant, caseJson);

        caseJson.put("postHearingCustodyStatus",
                getString(defendant, "postHearingCustodyStatus", DASH));
        caseJson.put("officerInCase", DASH);
        buildParentGuardianNameAndAddress(defendant, caseJson);
        caseJson.put(DATE_OF_BIRTH, formatDate(getString(defendant, DATE_OF_BIRTH, DASH)));
        caseJson.put("age", getAge(defendant));
        caseJson.put("gender", getString(defendant, "gender", DASH));
        caseJson.put("nationality", getString(defendant, "nationality", DASH));
        caseJson.put("aliases", buildAliases(defendant));
        caseJson.put("caseReference", getString(pcoa, "caseOrApplicationReference", DASH));
        caseJson.put("dateOfHearing",
                formatZonedDate(getString(courtRegisterDocumentRequest, "hearingDate")));
        caseJson.put("prosecutorName", getString(pcoa, "prosecutorName", DASH));
        caseJson.put("arrestSummonsNumber", getString(pcoa, "arrestSummonsNumber", DASH));
        buildProsecutionCounsel(pcoa, caseJson);
        buildDefendantResults(defendant, caseJson);
        buildCaseResults(pcoa, caseJson);
        buildOffences(pcoa, caseJson);
        buildApplication(pcoa, caseJson);
        return caseJson;
    }

    /**
     * The defendant-level results, written only where there are any.
     *
     * @param defendant the defendant
     * @param caseJson  the case being built
     */
    private void buildDefendantResults(final JsonNode defendant, final ObjectNode caseJson) {
        final ArrayNode jsonArrayBuilder = NODES.arrayNode();
        Optional.ofNullable(getJsonArray(defendant, "defendantResults")).ifPresent(results -> {
            final List<JsonNode> resultList = results.valueStream().toList();
            resultList.forEach(result -> jsonArrayBuilder.add(mapResult(result)));
        });

        if (!jsonArrayBuilder.isEmpty()) {
            caseJson.set("defendantResults", jsonArrayBuilder);
        }
    }

    /**
     * The case-level results, written only where there are any.
     *
     * @param pcoa     the prosecution case or court application
     * @param caseJson the case being built
     */
    private void buildCaseResults(final JsonNode pcoa, final ObjectNode caseJson) {
        final ArrayNode jsonArrayBuilder = NODES.arrayNode();
        Optional.ofNullable(getJsonArray(pcoa, RESULTS)).ifPresent(results ->
                results.valueStream().forEach(result -> jsonArrayBuilder.add(mapResult(result))));

        if (!jsonArrayBuilder.isEmpty()) {
            caseJson.set("caseResults", jsonArrayBuilder);
        }
    }

    /**
     * One result, at any of the three scopes results are printed at.
     *
     * @param result the recorded result
     * @return its code and its prepared text
     */
    private ObjectNode mapResult(final JsonNode result) {
        final ObjectNode resultBuilder = NODES.objectNode();
        resultBuilder.put(RESULT_CODE, getString(result, CJS_RESULT_CODE, DASH));
        resultBuilder.put(RESULT_TEXT, prepareResultText(getString(result, RESULT_TEXT, "")));
        return resultBuilder;
    }

    /**
     * The application, written whole where any one of its six fields carries anything.
     *
     * @param pcoaJson the prosecution case or court application
     * @param caseJson the case being built
     */
    private void buildApplication(final JsonNode pcoaJson, final ObjectNode caseJson) {
        final ArrayNode jsonArrayBuilder = NODES.arrayNode();

        if (isApplicationValid(pcoaJson)) {
            final ObjectNode application = NODES.objectNode();
            application.put("type", getString(pcoaJson, "applicationType", DASH));
            application.put("decision", getString(pcoaJson, "applicationDecision", DASH));
            application.put("decisionDate",
                    formatDate(getString(pcoaJson, "applicationDecisionDate", DASH)));
            application.put("response", getString(pcoaJson, "applicationResponse", DASH));
            application.put("responseDate",
                    formatDate(getString(pcoaJson, "applicationResponseDate", DASH)));
            application.put("result",
                    prepareResultText(getString(pcoaJson, "applicationResult", DASH)));
            jsonArrayBuilder.add(application);
        }

        if (!jsonArrayBuilder.isEmpty()) {
            caseJson.set("applications", jsonArrayBuilder);
        }
    }

    /**
     * The four hearing fields, dashed as a block where the defendant carries no hearing at all.
     *
     * @param defendant the defendant
     * @param caseJson  the case being built
     */
    private void buildHearingDetails(final JsonNode defendant, final ObjectNode caseJson) {

        final String jurisdiction = "jurisdiction";
        final String hearingType = "hearingType";
        final String defendantAppearanceDetails = "defendantAppearanceDetails";
        final String attendingSolicitorName = "attendingSolicitorName";
        if (containsKey(defendant, "hearing")) {
            final JsonNode defendantHearing = getJsonObject(defendant, "hearing");
            caseJson.put(jurisdiction, getString(defendantHearing, jurisdiction, DASH));
            caseJson.put(hearingType, getString(defendantHearing, hearingType, DASH));
            caseJson.put(defendantAppearanceDetails,
                    getString(defendantHearing, defendantAppearanceDetails, DASH));
            caseJson.put(attendingSolicitorName,
                    getString(defendantHearing, attendingSolicitorName, DASH));
        } else {
            caseJson.put(jurisdiction, DASH);
            caseJson.put(hearingType, DASH);
            caseJson.put(defendantAppearanceDetails, DASH);
            caseJson.put(attendingSolicitorName, DASH);
        }
    }

    /**
     * The defendant's name, and their address only where they have one.
     *
     * <p>The address is not dashed when it is absent: the six keys are simply not written, which is
     * the difference between this and {@link #buildParentGuardianNameAndAddress}.
     *
     * @param defendant the defendant
     * @param caseJson  the case being built
     */
    private void buildNameAndAddress(final JsonNode defendant, final ObjectNode caseJson) {
        caseJson.put(NAME, getString(defendant, NAME, DASH));
        Optional.ofNullable(getJsonObject(defendant, ADDRESS)).ifPresent(addressObj -> {
            ADDRESS_LINES.stream().map(a -> ADDRESS + a)
                    .forEach(a -> caseJson.put(a, getString(addressObj, a, "")));
            caseJson.put(POSTCODE, getString(addressObj, POSTCODE, ""));
        });
    }

    /**
     * The parent or guardian's name and address, or the two dashes that stand in for both.
     *
     * <p>The populated branch reads the guardian's address <strong>without a guard</strong>
     * ({@code :182-184}), so a guardian carrying none refuses the whole payload rather than that one
     * field. That is progression's line and it is kept: the frozen contract makes the address
     * required, so no recorded document can reach it.
     *
     * @param defendant the defendant
     * @param caseJson  the case being built
     */
    private void buildParentGuardianNameAndAddress(final JsonNode defendant,
            final ObjectNode caseJson) {

        if (containsKey(defendant, PARENT_GUARDIAN)) {
            final JsonNode parentGuardian = getJsonObject(defendant, PARENT_GUARDIAN);
            caseJson.put("parentGuardianName", getString(parentGuardian, NAME, DASH));
            final JsonNode addressObj = getJsonObject(parentGuardian, ADDRESS);
            ADDRESS_LINES.stream().map(a -> ADDRESS + a)
                    .forEach(a -> caseJson.put(PARENT_GUARDIAN + capitalise(a),
                            getString(addressObj, a, "")));
            caseJson.put("parentGuardianPostCode", getString(addressObj, POSTCODE, ""));
        } else {
            caseJson.put("parentGuardianName", DASH);
            caseJson.put("parentGuardianAddress1", DASH);
        }
    }

    /**
     * The prosecuting counsel's names and statuses, joined in the order the case carries them.
     *
     * @param pcoa     the prosecution case or court application
     * @param caseJson the case being built
     */
    private void buildProsecutionCounsel(final JsonNode pcoa, final ObjectNode caseJson) {
        if (containsKey(pcoa, PROSECUTION_COUNSELS)) {
            final String prosecutionCounselName =
                    getJsonArray(pcoa, PROSECUTION_COUNSELS).valueStream()
                            .map(r -> getString(r, NAME, ""))
                            .collect(Collectors.joining(SEPARATOR));
            final String prosecutionCounselStatus =
                    getJsonArray(pcoa, PROSECUTION_COUNSELS).valueStream()
                            .map(r -> getString(r, "status", ""))
                            .collect(Collectors.joining(SEPARATOR));
            caseJson.put("prosecutionCounselName", prosecutionCounselName);
            caseJson.put("prosecutionCounselStatus", prosecutionCounselStatus);
        } else {
            caseJson.put("prosecutionCounselName", DASH);
            caseJson.put("prosecutionCounselStatus", DASH);
        }
    }

    /**
     * The defence counsel's names and statuses, joined in the order the defendant carries them.
     *
     * @param defendant the defendant
     * @param caseJson  the case being built
     */
    private void buildDefenceCounsel(final JsonNode defendant, final ObjectNode caseJson) {
        if (containsKey(defendant, "defenceCounsels")) {
            final JsonNode defenceCounsels = getJsonArray(defendant, "defenceCounsels");
            caseJson.put("defenceCounselName", defenceCounsels.valueStream()
                    .map(r -> getString(r, NAME))
                    .collect(Collectors.joining(SEPARATOR)));
            caseJson.put("defenceCounselStatus", defenceCounsels.valueStream()
                    .map(r -> getString(r, "status"))
                    .collect(Collectors.joining(SEPARATOR)));
        } else {
            caseJson.put("defenceCounselName", DASH);
            caseJson.put("defenceCounselStatus", DASH);
        }
    }

    /**
     * The offences, written only where there are any.
     *
     * @param pcoaJson the prosecution case or court application
     * @param caseJson the case being built
     */
    private void buildOffences(final JsonNode pcoaJson, final ObjectNode caseJson) {
        final ArrayNode jsonArrayBuilder = NODES.arrayNode();
        Optional.ofNullable(getJsonArray(pcoaJson, "offences")).ifPresent(offences ->
                offences.valueStream().forEach(offenceJson -> {
                    final String convictionDate =
                            formatDate(getString(offenceJson, CONVICTION_DATE, DASH));
                    final ObjectNode offenceBuilder = NODES.objectNode();
                    offenceBuilder.put("offenceCode", getString(offenceJson, "offenceCode", DASH));
                    offenceBuilder.put("offenceTitle", clearUndesiredCharacters(
                            getString(offenceJson, "offenceTitle", DASH)));
                    offenceBuilder.put("wording", addNewLineIfDesired(clearUndesiredCharacters(
                            getString(offenceJson, "wording", DASH))));
                    offenceBuilder.put("allocationDecision",
                            getString(offenceJson, "allocationDecision", DASH));
                    offenceBuilder.put(CONVICTION_DATE, convictionDate);
                    offenceBuilder.put("verdictCode", getString(offenceJson, "verdictCode", DASH));
                    setResults(getJsonArray(offenceJson, RESULTS), offenceBuilder);
                    setPleaValue(offenceJson, offenceBuilder);
                    setIndicatedPleaValue(offenceJson, offenceBuilder);

                    jsonArrayBuilder.add(offenceBuilder);
                }));

        if (!jsonArrayBuilder.isEmpty()) {
            caseJson.set("offences", jsonArrayBuilder);
        }
    }

    /**
     * The offence's results.
     *
     * <p>The one result list that is written when it is empty: {@code :245-247} adds the array
     * inside the {@code ifPresent}, so an offence carrying {@code "results": []} prints
     * {@code "results": []} where a defendant or a case carrying the same prints nothing.
     *
     * @param resultsArray   the offence's results, which may be absent
     * @param offenceBuilder the offence being built
     */
    private void setResults(final JsonNode resultsArray, final ObjectNode offenceBuilder) {
        final ArrayNode jsonArrayBuilder = NODES.arrayNode();
        Optional.ofNullable(resultsArray).ifPresent(results -> {
            final List<JsonNode> resultList = results.valueStream().toList();
            resultList.forEach(result -> jsonArrayBuilder.add(mapResult(result)));
            offenceBuilder.set(RESULTS, jsonArrayBuilder);
        });
    }

    /**
     * The plea, and the date it was entered on where the offence carries one.
     *
     * @param offenceJson    the offence
     * @param offenceBuilder the offence being built
     */
    private void setPleaValue(final JsonNode offenceJson, final ObjectNode offenceBuilder) {
        final String pleaValue = "pleaValue";
        if (containsKey(offenceJson, pleaValue)) {
            final StringJoiner stringJoiner = new StringJoiner(SEPARATOR);
            stringJoiner.add(getString(offenceJson, pleaValue));
            if (containsKey(offenceJson, PLEA_DATE)) {
                stringJoiner.add(formatDate(getString(offenceJson, PLEA_DATE)));
            }
            offenceBuilder.put(pleaValue, stringJoiner.toString());
        } else {
            offenceBuilder.put(pleaValue, DASH);
        }
    }

    /**
     * The indicated plea, joined to the conviction date rather than to a plea date.
     *
     * <p>{@code :278-280} reads {@code convictionDate} here, not {@code pleaDate}. Whether that is
     * what the template wanted is not a question the port answers.
     *
     * @param offenceJson    the offence
     * @param offenceBuilder the offence being built
     */
    private void setIndicatedPleaValue(final JsonNode offenceJson,
            final ObjectNode offenceBuilder) {

        final String indicatedPleaValue = "indicatedPleaValue";
        if (containsKey(offenceJson, indicatedPleaValue)) {
            final StringJoiner stringJoiner = new StringJoiner(SEPARATOR);
            stringJoiner.add(getString(offenceJson, indicatedPleaValue));
            if (containsKey(offenceJson, CONVICTION_DATE)) {
                stringJoiner.add(formatDate(getString(offenceJson, CONVICTION_DATE)));
            }
            offenceBuilder.put(indicatedPleaValue, stringJoiner.toString());
        } else {
            offenceBuilder.put(indicatedPleaValue, DASH);
        }
    }

    /**
     * Every other name the defendant is known by, each composed with spaces and joined with commas.
     *
     * <p>The two filters do two jobs: the inner one drops the name parts the alias does not carry,
     * so a person with no middle name gets one space and not two, and the outer one drops an alias
     * that carried no part at all rather than leaving an empty entry in the list.
     *
     * @param defendant the defendant
     * @return the aliases, or a dash where the defendant is known by no other name
     */
    private String buildAliases(final JsonNode defendant) {
        if (containsKey(defendant, "aliases")) {
            return getJsonArray(defendant, "aliases").valueStream()
                    .map(a -> Stream.of(
                            getString(a, "title", ""), getString(a, "firstName", ""),
                            getString(a, "middleName", ""), getString(a, "lastName", ""))
                            // Guava's Strings.isNullOrEmpty over a two-argument getString, whose
                            // default is "" and which therefore never answers null.
                            .filter(next -> !next.isEmpty())
                            .collect(Collectors.joining(BLANK)))
                    .filter(next -> !next.isEmpty())
                    .collect(Collectors.joining(SEPARATOR));
        } else {
            return DASH;
        }
    }

    /**
     * The court house's address, on one line.
     *
     * <p>The postcode is joined to the lines rather than filtered with them, so a venue whose only
     * address field is a postcode prints a leading separator. That is the recorded output.
     *
     * @param addressObj the venue's address
     * @return the address
     */
    private String buildCourtHouseAddress(final JsonNode addressObj) {
        final String address = ADDRESS_LINES.stream()
                .map(a -> ADDRESS + a)
                .map(a -> getString(addressObj, a, ""))
                .filter(next -> !next.isEmpty())
                .collect(Collectors.joining(SEPARATOR));

        final StringJoiner stringJoiner = new StringJoiner(SEPARATOR);
        stringJoiner.add(address);
        if (containsKey(addressObj, POSTCODE)) {
            stringJoiner.add(getString(addressObj, POSTCODE));
        }
        return stringJoiner.toString();
    }

    /**
     * The defendant's age in whole years on the day the mapping runs.
     *
     * <p>The one field in the payload that reads a clock, which is why the mapper takes one.
     *
     * @param defendant the defendant
     * @return the age, or a dash where there is no date of birth to count from
     */
    private String getAge(final JsonNode defendant) {
        if (containsKey(defendant, DATE_OF_BIRTH)) {
            return String.valueOf(Period.between(
                            LocalDate.parse(getString(defendant, DATE_OF_BIRTH)),
                            LocalDate.now(clock))
                    .getYears());
        } else {
            return DASH;
        }
    }

    /**
     * A value with every run of whitespace collapsed to a single space, and its ends trimmed.
     *
     * @param input the value
     * @return the cleared value
     */
    private String clearUndesiredCharacters(final String input) {
        return input.replaceAll("\\s+", BLANK).trim();
    }

    /**
     * A value with C24's sentinel turned back into the newline it stands for.
     *
     * @param input the value
     * @return the value, with newlines where the sentinel was
     */
    private String addNewLineIfDesired(final String input) {
        return input.replaceAll(DESIRED_NEW_LINE, NEW_LINE).trim();
    }

    /**
     * Result text as the template reads it.
     *
     * <p>Three passes, and the order is the point: the newlines the result already carries are
     * turned into sentinels first, so that the whitespace collapse cannot eat them, and every
     * sentinel, the ones just written and the ones the producer put there alike, becomes a newline
     * last.
     *
     * @param resultText the recorded result text
     * @return the prepared text
     */
    private String prepareResultText(final String resultText) {
        String updateResultText = resultText.replaceAll(NEW_LINE, DESIRED_NEW_LINE).trim();
        updateResultText = clearUndesiredCharacters(updateResultText);
        return addNewLineIfDesired(updateResultText);
    }

    /**
     * Whether the case or application carries an application worth printing.
     *
     * <p>Any one of the six fields, not all of them: the recorded register's application carries its
     * type and nothing else, and a rule that asked for more would drop it.
     *
     * @param pcoaJson the prosecution case or court application
     * @return whether an application is written
     */
    private boolean isApplicationValid(final JsonNode pcoaJson) {

        final List<String> applicationFields = List.of("applicationType",
                "applicationDecision", "applicationDecisionDate", "applicationResponse",
                "applicationResponseDate", "applicationResult");
        return applicationFields.stream().anyMatch(s -> !getString(pcoaJson, s, "").isEmpty());
    }

    /**
     * A plain date, read as the document writes it and written as the register prints it.
     *
     * @param dateInString the recorded date, which may be the dash a reader defaulted to
     * @return the printed date, or a dash
     */
    private String formatDate(final String dateInString) {
        if (dateInString.isEmpty() || DASH.equals(dateInString)) {
            return DASH;
        }
        final LocalDate dateTime = LocalDate.parse(dateInString, IN_DATE_FORMATTER);
        return dateTime.format(OUT_DATE_FORMATTER);
    }

    /**
     * A zoned timestamp, printed as the UTC day it falls on.
     *
     * <p>{@code ZonedDateTimes.fromString} is {@code ZonedDateTime.parse(value)} moved to UTC, so a
     * register shared at 23:30+01:00 prints the previous day. Kept, because the goldens carry it.
     *
     * @param dateInString the recorded timestamp
     * @return the printed date
     */
    private String formatZonedDate(final String dateInString) {
        return ZonedDateTime.parse(dateInString)
                .withZoneSameInstant(ZoneOffset.UTC)
                .format(OUT_DATE_FORMATTER);
    }

    /**
     * A property read as text, with a stand-in for one that is absent or is not text.
     *
     * <p>{@code JsonObject.getString(String, String)}: the default answers for an absent property, a
     * JSON {@code null}, and a value of any other type.
     *
     * @param object       the object to read
     * @param name         the property name
     * @param defaultValue what to answer where the property is absent or is not a string
     * @return the property's text, or the default
     */
    private static String getString(final JsonNode object, final String name,
            final String defaultValue) {

        final JsonNode value = object.get(name);
        return value == null ? defaultValue : value.stringValue(defaultValue);
    }

    /**
     * A property read as text, with nothing to stand in where there is none.
     *
     * <p>{@code JsonObject.getString(String)}: an absent property and one that is not a string both
     * refuse, which is what {@code getAge} does to a defendant whose date of birth is JSON
     * {@code null}.
     *
     * @param object the object to read
     * @param name   the property name
     * @return the property's text
     */
    private static String getString(final JsonNode object, final String name) {
        return object.get(name).stringValue();
    }

    /**
     * A property read as an object, or {@code null} where the object has no such property.
     *
     * <p>{@code JsonObject.getJsonObject(String)}, cast and all: a property of any other type
     * refuses rather than answering nothing.
     *
     * @param object the object to read
     * @param name   the property name
     * @return the property's object, or {@code null}
     */
    private static ObjectNode getJsonObject(final JsonNode object, final String name) {
        return (ObjectNode) object.get(name);
    }

    /**
     * A property read as an array, or {@code null} where the object has no such property.
     *
     * <p>{@code JsonObject.getJsonArray(String)}, cast and all: a property of any other type refuses
     * rather than answering nothing.
     *
     * @param object the object to read
     * @param name   the property name
     * @return the property's array, or {@code null}
     */
    private static ArrayNode getJsonArray(final JsonNode object, final String name) {
        return (ArrayNode) object.get(name);
    }

    /**
     * Whether the object carries a property at all, JSON {@code null} included.
     *
     * <p>{@code JsonObject.containsKey(String)}, which is a question about the key and not about
     * the value under it: the difference that sends a defendant with a null date of birth into
     * {@code getAge}'s reading branch.
     *
     * @param object the object to read
     * @param name   the property name
     * @return whether the property is present
     */
    private static boolean containsKey(final JsonNode object, final String name) {
        return object.has(name);
    }

    /**
     * A value with its first letter in upper case, which is {@code StringUtils.capitalize}.
     *
     * @param value the value, never empty at the one place this is called
     * @return the capitalised value
     */
    private static String capitalise(final String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
