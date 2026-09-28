package uk.gov.hmcts.cp.yotresultsdistribution.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.support.DifferentialCorpus.RecordedCase;

/**
 * The derivation register: every way this port is allowed to differ from the legacy, keyed on the
 * {@code doc/DEFECT-FIXES.md} row that allows it.
 *
 * <p><strong>Why this exists at all.</strong> Everything recorded under
 * {@code src/test/resources/differential/recorded/} is a recording of the real Node function app and
 * is the oracle's truth. It is never regenerated and never edited — a golden file somebody adjusted
 * to agree with the port has stopped being evidence. This port, though, is deliberately not a
 * bug-for-bug one: thirty-one catalogued defects are fixed, so for those components the recording
 * carries the legacy behaviour and the port is required to produce something else, and something has
 * to reconcile the two.
 *
 * <p><strong>Registered here means registered there.</strong> Nothing belongs in this class that is
 * not a C-number in {@code doc/DEFECT-FIXES.md}, and every entry quotes its C-number into the
 * failure message so a reader of a red build is one grep from the reasoning and the sign-off. An
 * observed difference that no entry claims is not a gap in this file by default: it is a port defect
 * until a C-row says otherwise, which is the rule the differential audit enforces in both
 * directions.
 *
 * <p><strong>Two mechanisms, because there are two kinds of difference.</strong>
 *
 * <ul>
 *   <li>A <em>derivation</em> ({@link Fix}, looked up by property name and applied by
 *       {@link JsonParity}) is for a component the port re-renders: given the value the oracle
 *       recorded, it computes the value the port is now required to write, and the comparator
 *       demands exactly that. It is not an exclusion — excluding the field would make every golden
 *       assertion stop looking at it, and the next mistake in that component would sail through a
 *       green suite. The check is as strict as equality was; only the expected string moved. It can
 *       fail in a third way, and does so loudly: an oracle value the derivation does not describe is
 *       reported as a difference rather than waved through.</li>
 *   <li>A <em>claim</em> ({@link Claim}, applied by the differential audit) is for everything a
 *       value-for-value derivation cannot express — a field the legacy never wrote, a case the port
 *       drops, a document that exists on one side and not the other. Each carries a predicate that
 *       recognises the <em>signature</em> of its fix in an observed divergence, so it explains that
 *       divergence and no other. The claims are mutually exclusive by construction and the audit
 *       asserts it: a divergence two rows could explain is a register that has stopped saying which
 *       fix produced what.</li>
 * </ul>
 *
 * <p>Both halves are deliberately narrow. A predicate that said "anything at this path may differ"
 * would be an exclusion wearing a C-number, and the first regression in that component would ship
 * inside a green suite.
 *
 * <p><strong>Two oracles, so two catalogues.</strong> The three mechanisms above all read the 001
 * recordings, whose C-numbers catalogue the Node function app's defects. Increment 002 brings a
 * second oracle with it - progression's own classes, recorded under
 * {@code src/test/resources/goldens/progression/} by running them - and its defects are catalogued
 * as the {@code P} rows of the same register. So {@link #progressionLegRows()} is the same
 * mechanism over that oracle: a {@link ProgressionRow} recognises the signature of its fix in a
 * {@link GoldenDeviation}, an answer the recorded golden and this port do not agree on, and the
 * audit requires exactly one row for it. The citation rule is one rule across both catalogues -
 * every entry here opens with its register row number and that row exists in
 * {@code doc/DEFECT-FIXES.md} - which is what makes an unregistered {@code P} deviation refused
 * exactly as an unregistered {@code C} one is.
 *
 * <p><strong>Only P10 has a signature a comparison of answers can carry, and that is a finding
 * rather than an omission.</strong> P1 to P5 and P9 are batch, store and notifier rows - a batch
 * that ends {@code NOTIFIED_NOBODY}, a render whose failure is recorded, a mark scoped to its own
 * batch, a recipient union, an assembly failure that fails one batch and lets the run walk on, a
 * blank template that refuses to start - and none of them is a value in a document or an answer
 * from a rule, which is why each names its own pinning test elsewhere. P8 is MOOT and P6 and P7 are
 * RETIRED in progression's own tree, with nothing in this repository to pin. P10 is the one row
 * whose fix is an <em>answer</em>, so it is the one row a deviation from the recorded goldens can
 * be attributed to.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a>
 */
// PMD.OnlyOneReturn: a predicate reads as a list of disqualifying conditions, each answering where
// it decides; funnelling them through one exit would put the reasoning behind a variable.
@SuppressWarnings("PMD.OnlyOneReturn")
public final class RegisteredDefectFixes {

    /** {@code Europe/London} — the zone whose real rules decide the offsets below. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** How {@code DateService.getLocalDateTime} renders a time: a London wall clock, then a 'Z'. */
    private static final DateTimeFormatter LEGACY_RENDERING =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** How this port renders an instant: whole seconds, always UTC. */
    private static final DateTimeFormatter INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /** How this port renders a calendar day. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** The sentinel {@code OffenceMapper.js:17} joins a wording and its legislation with. */
    private static final String SENTINEL = "####";

    /** What JavaScript string concatenation leaves behind where a value was absent. */
    private static final String UNDEFINED = "undefined";

    /** The three renderings {@code HearingMapper} maps {@code attendanceType} to. */
    private static final List<String> APPEARANCES =
            List.of("In person", "By video link", "Not present");

    /** The component C23 moves, as the suffix of the pointer a difference is reported at. */
    private static final String VERDICT_CODE = "/verdictCode";

    /** The recorded reason the orchestrator gives for a loose-equality group-proceedings skip. */
    private static final String LOOSE_GROUP_PROCEEDINGS_SKIP = "CourtRegisterOrchestrator/index.js:22";

    /** The default P10 requires where progression's own rule cannot read the application. */
    private static final String APPLICANT_TYPE = "Applicant";

    /** How the recorder wrote a refusal progression's defendant-type rule answered with. */
    private static final String PROGRESSION_REFUSAL = "java.lang.NullPointerException";

    /** The two dereferences P10 names that a recorded golden actually reached. */
    private static final List<String> UNREADABLE_APPLICATION = List.of(
            "CourtApplicationType.getAppealFlag()",
            "CourtApplication.getRespondents()");

    /**
     * The derivations, by the property name the component reaches the wire under.
     *
     * <p>Matched by property name rather than by JSON pointer because the comparator is handed
     * fragments as well as whole documents, and the same component sits at a different depth in
     * each. Both components below occur at exactly one place in this contract, so the two readings
     * coincide; a component that occurred at several, and was fixed at only one of them, would need
     * a pointer-aware entry, and that decision belongs with the fix that first needs it.
     */
    private static final Map<String, Fix> REGISTER = Map.of(
            "registerDate",
            new Fix("C10 (BST local time labelled as UTC)",
                    RegisteredDefectFixes::registerDate),
            "wording",
            new Fix("C24 (#### sentinel and undefined residue in wording)",
                    RegisteredDefectFixes::wording));

    /**
     * The claims, in the order the audit consults them.
     *
     * <p>Order is a reading convenience and nothing more: the predicates are mutually exclusive, and
     * {@link #claimedBy} returns all of them so the audit can prove it.
     */
    private static final List<Claim> REGISTERED_CLAIMS = List.of(
            fileName(),
            verdictCode(),
            defendantPresent(),
            defendantAppearanceDetails(),
            applicationWithoutAProsecutingApplicant(),
            explicitNullOmitted(),
            registerThatReachesNobody(),
            documentTheContractRefuses(),
            matchedOnEveryDefendant(),
            groupProceedingsReadStrictly(),
            youthDefendantWhoseDetailsCannotBeResolved(),
            attendanceReadFromAnEmptyList(),
            failureSwallowedAndReportedAsSuccess(),
            matchedByAnInformantCode(),
            matchedByARouteThatIsNotTheCourtRegisterOne(),
            majorCreditorFlagThatPassesOnEmptyData(),
            ethnicityFromWhicheverDescriptionIsThere(),
            asnRecordWithoutAPerson(),
            hearingDateReadFromTheWallClock(),
            hearingDateDereferenceThatThrows(),
            subscriptionsReadForTheDayAfterTheShare());

    /**
     * The progression-leg rows, in the order the audit consults them.
     *
     * <p>The {@code P} half of the register, read the same way the {@code C} half is: one row or
     * the deviation is a port defect. One entry, because P10 is the only one of the ten rows whose
     * fix is an answer rather than a batch state, a mark or a startup refusal - the class javadoc
     * reconciles the other nine - and a table of one is still the mechanism, because the mechanism
     * is what refuses the second entry nobody registered.
     */
    private static final List<ProgressionRow> PROGRESSION_LEG_ROWS = List.of(
            applicationProgressionsOwnRuleCannotRead());

    private RegisteredDefectFixes() {
    }

    /**
     * The defect fix registered against a property name, if any.
     *
     * @param propertyName the property name as it reaches the wire
     * @return the fix, or {@code null} when the property is compared by equality
     */
    public static Fix forProperty(final String propertyName) {
        return REGISTER.get(propertyName);
    }

    /**
     * Every registered claim, for a report that wants to name the rows the audit rests on.
     *
     * @return the claims
     */
    public static List<Claim> claims() {
        return REGISTERED_CLAIMS;
    }

    /**
     * Every registered progression-leg row, for the citation check that reads both catalogues.
     *
     * @return the rows
     */
    public static List<ProgressionRow> progressionLegRows() {
        return PROGRESSION_LEG_ROWS;
    }

    /**
     * The claims that explain an observed divergence.
     *
     * <p>Returns all of them rather than the first, because "exactly one row explains this" is an
     * assertion the audit makes and not an assumption it is entitled to.
     *
     * @param divergence the observed divergence
     * @return the claims whose predicate recognises it, empty where none does
     */
    public static List<Claim> claimedBy(final Divergence divergence) {
        return REGISTERED_CLAIMS.stream().filter(claim -> claim.explains(divergence)).toList();
    }

    /**
     * The progression-leg rows that explain a deviation from a recorded golden.
     *
     * <p>All of them rather than the first, for the same reason {@link #claimedBy(Divergence)}
     * answers all of them: "exactly one row explains this" is an assertion the audit makes and not
     * an assumption it is entitled to.
     *
     * @param deviation the observed deviation
     * @return the rows whose predicate recognises it, empty where none does
     */
    public static List<ProgressionRow> claimedBy(final GoldenDeviation deviation) {
        return PROGRESSION_LEG_ROWS.stream().filter(row -> row.explains(deviation)).toList();
    }

    // --- the derivations -------------------------------------------------------------------------

    /**
     * The {@code registerDate} derivation — {@code doc/DEFECT-FIXES.md} row C10.
     *
     * <p>The oracle records {@code DateService.getLocalDateTime}'s output: a {@code Europe/London}
     * wall-clock date-time with the character {@code Z} appended, whatever the real offset was, so a
     * 10:00 UTC share reaches the wire as {@code 11:00:00Z} for half the year. The port carries the
     * instant it was given. The derivation therefore reads the wall clock straight back out of the
     * oracle's own string, asks the {@code Europe/London} rules which offsets that wall clock had on
     * that date, and renders the instants they name.
     *
     * <p><strong>It derives, it does not re-run the port.</strong> The wall clock comes from the
     * recording rather than from {@link uk.gov.hmcts.cp.yotresultsdistribution.pipeline.Dates}, so a fault in
     * this port's own parsing cannot cancel itself out.
     *
     * <p><strong>The repeated autumn hour permits two answers, and honestly so.</strong> London
     * repeats 01:00–02:00 once a year and the oracle's rendering — a wall clock plus a meaningless
     * {@code Z} — genuinely does not record which of the two it was. Both offsets the zone allows
     * are accepted, which still fixes the digits exactly and still rejects every other offset. The
     * corpus's {@code shared-time__dst-fall-back} case lands in that hour, so this is not a
     * hypothetical allowance.
     *
     * @param oracleValue the value in the recording
     * @return the permitted renderings, or empty when the oracle's value is undescribed
     */
    private static List<String> registerDate(final String oracleValue) {
        return instantsOf(oracleValue).stream().map(INSTANT::format).toList();
    }

    /**
     * The {@code wording} derivation — {@code doc/DEFECT-FIXES.md} row C24.
     *
     * <p>{@code OffenceMapper.js:17} writes {@code wording + '####' + offenceLegislation}, so an
     * offence with no legislation reaches progression as {@code "…####undefined"} and progression's
     * own PDF generator substitutes the sentinel for a newline at render time. The port joins the
     * two with a real newline and omits the legislation line entirely when there is none, which
     * renders identically for the populated case and loses the {@code undefined} residue for the
     * absent one.
     *
     * <p>So the derivation splits the recording at its own sentinel and re-joins the halves the way
     * the port is required to. A recording carrying no sentinel is not a value this fix describes —
     * the legacy writes one unconditionally — and is reported rather than passed.
     *
     * @param oracleValue the value in the recording
     * @return the permitted rendering, or empty when the oracle's value is undescribed
     */
    private static List<String> wording(final String oracleValue) {
        if (oracleValue == null) {
            return List.of();
        }
        final int sentinel = oracleValue.indexOf(SENTINEL);
        if (sentinel < 0) {
            return List.of();
        }
        final String wording = present(oracleValue.substring(0, sentinel));
        final String legislation = present(oracleValue.substring(sentinel + SENTINEL.length()));
        if (wording.isEmpty() && legislation.isEmpty()) {
            return List.of();
        }
        if (wording.isEmpty()) {
            return List.of(legislation);
        }
        if (legislation.isEmpty()) {
            return List.of(wording);
        }
        return List.of(wording + "\n" + legislation);
    }

    /**
     * One half of a sentinel-joined value, with JavaScript's residue for an absent one removed.
     *
     * @param half the half as the recording carries it
     * @return the half, or the empty string where it was never there
     */
    private static String present(final String half) {
        return UNDEFINED.equals(half) ? "" : half;
    }

    /**
     * The instants a {@code getLocalDateTime} rendering could name — the shared half of C10.
     *
     * @param oracleValue the value in the recording
     * @return the instants, empty where the value is not a rendering this fix describes
     */
    private static List<LocalDateTime> instantsOf(final String oracleValue) {
        if (oracleValue == null || !oracleValue.endsWith("Z")) {
            return List.of();
        }
        final LocalDateTime wallClock;
        try {
            wallClock = LocalDateTime.parse(
                    oracleValue.substring(0, oracleValue.length() - 1), LEGACY_RENDERING);
        } catch (DateTimeParseException notTheLegacyRendering) {
            return List.of();
        }
        final List<LocalDateTime> instants = new ArrayList<>(2);
        for (final ZoneOffset offset : LONDON.getRules().getValidOffsets(wallClock)) {
            instants.add(LocalDateTime.ofInstant(wallClock.toInstant(offset), ZoneOffset.UTC));
        }
        return instants;
    }

    // --- the claims ------------------------------------------------------------------------------

    /**
     * C11 — the file name carries colons and is not unique.
     *
     * @return the claim
     */
    private static Claim fileName() {
        return new Claim("C11 (filename carries colons and is not unique)",
                "court-register_{registerDate}_{code}.pdf embeds a full datetime, so the name "
                        + "carries colons Windows refuses and two hearings at one centre in the same "
                        + "second collide. The port writes the corrected register day, the code and "
                        + "the hearing id — and the day is derived here from the recording's own "
                        + "instant through C10, so a name that is right about the code and wrong "
                        + "about the day is still a difference.",
                divergence -> {
                    if (!(divergence instanceof Divergence.Field field)
                            || !"/fileName".equals(field.path())) {
                        return false;
                    }
                    final String oracle = string(field.oracleValue());
                    final String port = string(field.portValue());
                    if (oracle == null || port == null
                            || !oracle.startsWith("court-register_") || !oracle.endsWith(".pdf")) {
                        return false;
                    }
                    final String body = oracle.substring("court-register_".length(),
                            oracle.length() - ".pdf".length());
                    final int lastPart = body.lastIndexOf('_');
                    if (lastPart < 0) {
                        return false;
                    }
                    final String courtCentreCode = body.substring(lastPart + 1);
                    final String hearingId = divergence.recorded().hearingId().toString();
                    return instantsOf(body.substring(0, lastPart)).stream()
                            .map(instant -> "court-register_" + DAY.format(instant) + '_'
                                    + courtCentreCode + '_' + hearingId + ".pdf")
                            .anyMatch(port::equals);
                });
    }

    /**
     * C23 — {@code verdictCode} carries a prose description.
     *
     * @return the claim
     */
    private static Claim verdictCode() {
        return new Claim("C23 (verdictCode carries a prose description)",
                "OffenceMapper.js:20 writes verdict.verdictType.description into a field named "
                        + "verdictCode, so the field is prose where the platform verdict model has a "
                        + "code — and absent altogether where the verdict type carries no "
                        + "description, which is what the corpus recorded. The port writes "
                        + "verdictType.verdictCode, falling back to its categoryType. The claim "
                        + "derives that value from the recorded verdict object itself — the offence "
                        + "the difference was reported at is resolved back to the payload offence it "
                        + "was mapped from, by offence code and order index — and requires the port "
                        + "to have written exactly it. A code-shaped value the payload's own verdict "
                        + "does not name is still a difference, and so is a case whose offence "
                        + "resolves to no verdict, or to two that disagree.",
                divergence -> {
                    if (!(divergence instanceof Divergence.Field field)
                            || !field.path().endsWith(VERDICT_CODE)) {
                        return false;
                    }
                    final JsonNode verdictType = verdictTypeBehind(field);
                    final String owed = verdictCodeOwed(verdictType);
                    return owed != null
                            && owed.equals(string(field.portValue()))
                            && isTheDescriptionOrNothing(field.oracleValue(), verdictType);
                });
    }

    /**
     * C9 — {@code defendantPresent} can never be true.
     *
     * @return the claim
     */
    private static Claim defendantPresent() {
        return new Claim("C9 (attendance date can never match)",
                "HearingMapper.js:14 compares an attendance day (a date) with the register date (a "
                        + "datetime), which are never equal in production, so defendantPresent is "
                        + "false on every register the legacy has ever produced. The port matches "
                        + "attendance against any day one of the defendant's gathered results was "
                        + "ordered on, so the field becomes real data. The claim holds the "
                        + "recording to the false it can only ever be — a recorded true would mean "
                        + "the oracle is not the flow this row describes.",
                divergence -> divergence instanceof Divergence.Field field
                        && field.path().endsWith("/hearing/defendantPresent")
                        && isFalse(field.oracleValue())
                        && field.portValue() != null && field.portValue().isBoolean());
    }

    /**
     * C9 — {@code defendantAppearanceDetails} is never written.
     *
     * @return the claim
     */
    private static Claim defendantAppearanceDetails() {
        return new Claim("C9 (attendance date can never match)",
                "The sibling of the field above: because the comparison at HearingMapper.js:14 can "
                        + "never match, the appearance details it guards are never written and the "
                        + "PDF's appearance column renders its dash fallback. The port writes one of "
                        + "the three attendanceType renderings, so the claim requires the recording "
                        + "to carry nothing and the port to carry one of exactly those three.",
                divergence -> divergence instanceof Divergence.Field field
                        && field.path().endsWith("/hearing/defendantAppearanceDetails")
                        && field.oracleValue() == null
                        && APPEARANCES.contains(string(field.portValue())));
    }

    /**
     * C22 — non-prosecuting-authority applications reach the register.
     *
     * @return the claim
     */
    private static Claim applicationWithoutAProsecutingApplicant() {
        return new Claim("C22 (non-prosecuting-authority applications reach the register)",
                "ProsecutionCaseOrApplicationMapper.js:64-66 implements only the subject half of "
                        + "the eligibility its own comment states, so a defence-initiated "
                        + "application reaches the register. The port requires an applicant that "
                        + "prosecutes, in the context builder and in the mapper alike. The claim "
                        + "checks the drop against the hearing itself: every entry the port left "
                        + "out must be a court application whose applicant carries no "
                        + "prosecutingAuthority, so a case dropped for any other reason is not "
                        + "explained here.",
                divergence -> {
                    if (!(divergence instanceof Divergence.Field field)
                            || !field.path().endsWith("/prosecutionCasesOrApplications")) {
                        return false;
                    }
                    final JsonNode oracle = field.oracleValue();
                    final JsonNode port = field.portValue();
                    if (oracle == null || port == null || !oracle.isArray() || !port.isArray()
                            || port.size() >= oracle.size()) {
                        return false;
                    }
                    return droppedEntries(oracle, port).stream()
                            .allMatch(dropped -> isDefenceInitiated(dropped,
                                    divergence.recorded().hearing()));
                });
    }

    /**
     * C26 — a field the legacy writes as an explicit {@code null}.
     *
     * @return the claim
     */
    private static Claim explicitNullOmitted() {
        return new Claim("C26 (mapper/model drift)",
                "The legacy's mappers copy whatever the payload holds, so a field the producer sent "
                        + "as an explicit JSON null reaches the wire as null — which the frozen "
                        + "contract refuses, which is why every recording this claim covers is "
                        + "classified SCHEMA_INVALID at exactly this pointer. The port's typed "
                        + "records cannot express present-and-null: an absent value is an absent "
                        + "field. The claim requires the recording's own violation list to name the "
                        + "pointer, so it covers only the shapes the contract was already refusing "
                        + "and never a field the port has simply lost.",
                divergence -> divergence instanceof Divergence.Field field
                        && field.oracleValue() != null && field.oracleValue().isNull()
                        && field.portValue() == null
                        && divergence.recorded().violationPointers().contains(field.path()));
    }

    /**
     * C36 — recipient-less registers submitted to nobody.
     *
     * @return the claim
     */
    private static Claim registerThatReachesNobody() {
        return new Claim("C36 (recipient-less registers submitted to nobody)",
                "When every matched subscription is dropped by the recipient predicate — no usable "
                        + "email, letter delivery only, forDistribution false (C27 counts each) — "
                        + "OutboundCourtRegister/index.js:28-40 posts the document with recipients "
                        + "undefined. Progression stores it, renders the PDF and emits a "
                        + "notification nothing subscribes to, so the register sticks at GENERATED "
                        + "forever. The port does not submit it: the run completes no-subscriptions, "
                        + "there being nobody to distribute to.",
                divergence -> divergence instanceof Divergence.Outcome
                        && divergence.recorded().producedDocument()
                        && !hasRecipients(divergence.recorded().expected())
                        && divergence.port().isNoRegister("no-subscriptions"));
    }

    /**
     * C29 — a missing required field silently loses the register.
     *
     * @return the claim
     */
    private static Claim documentTheContractRefuses() {
        return new Claim("C29 (missing required address silently loses the register)",
                "A document the frozen add-court-register contract refuses is answered 400 by "
                        + "progression, and C1's catch swallows it: the whole hearing's register is "
                        + "lost with no trace. The port validates before sending, so the same "
                        + "document is an explicit, dead-lettered OUTBOUND_CONTRACT_VIOLATION. The "
                        + "claim is not satisfied by any refusal: the pointer the port names must be "
                        + "one the recorder's own validator named, or an extension of one — ajv "
                        + "reports a required failure against the object, this port appends the "
                        + "missing property — so a port refusing the right document for the wrong "
                        + "reason is still a difference.",
                divergence -> divergence instanceof Divergence.Outcome
                        && RecordedCase.SCHEMA_INVALID.equals(
                                divergence.recorded().contractStatus())
                        && divergence.port().refusedByTheContract()
                        && divergence.recorded().violationPointers().stream()
                                .anyMatch(pointer ->
                                        divergence.port().failurePointer().startsWith(pointer)));
    }

    /**
     * C31 — only the first defendant's vocabulary is matched.
     *
     * @return the claim
     */
    private static Claim matchedOnEveryDefendant() {
        return new Claim("C31 (only the first defendant's vocabulary is matched)",
                "CourtRegisterSubscriptions/index.js:49 matches on registerDefendants[0].vocabulary "
                        + "alone, and the list is not pre-filtered to youths, so an adult-first "
                        + "hearing has its youth register matched against adult vocabulary and "
                        + "reaches nobody. The port asks every register defendant. The claim "
                        + "therefore requires the recording's own counts to carry that signature — "
                        + "a register built for two or more defendants that matched no subscription "
                        + "at all — and the port to have addressed one, whether it then sent the "
                        + "register or had it refused by the contract (C29).",
                divergence -> divergence instanceof Divergence.Outcome
                        && divergence.recorded().matchedSubscriptions().orElse(-1) == 0
                        && divergence.recorded().registerDefendants().orElse(0) >= 2
                        && divergence.port().addressedSomebody());
    }

    /**
     * C7 — the group-proceedings skip is loosely typed.
     *
     * @return the claim
     */
    private static Claim groupProceedingsReadStrictly() {
        return new Claim("C7 (group-proceedings skip is silent and loosely typed)",
                "CourtRegisterOrchestrator/index.js:21-23 proceeds only on null or false under "
                        + "JavaScript's loose equality, so every other value suppresses the register "
                        + "with no record — the string \"false\" included. The port suppresses on "
                        + "the JSON boolean true and nothing else, and counts anything that is not a "
                        + "boolean as a contract anomaly. The claim requires the recording to have "
                        + "skipped through that very line, on a value that is not a boolean, so a "
                        + "hearing that really is group proceedings is not explained here — and it "
                        + "requires the port to have reached the ending the row governs: the "
                        + "register the skip was suppressing, assembled, or refused by the frozen "
                        + "contract (C29) where the payload was already missing a required field. "
                        + "Not suppressing is not on its own the fix; a port that proceeded and then "
                        + "lost the register for some other reason is not explained here.",
                divergence -> divergence instanceof Divergence.Outcome
                        && "skipped-group-proceedings".equals(divergence.recorded().outcome())
                        && divergence.recorded().noDocumentReason()
                                .startsWith(LOOSE_GROUP_PROCEEDINGS_SKIP)
                        && isNotABoolean(
                                divergence.recorded().hearing().get("isGroupProceedings"))
                        && divergence.port().addressedSomebody());
    }

    /**
     * C19 — the youth mapper dies on non-person defendants.
     *
     * @return the claim
     */
    private static Claim youthDefendantWhoseDetailsCannotBeResolved() {
        return new Claim("C19 (youth mapper dies on non-person defendants)",
                "YouthDefendantMapper.js:32,34 dereferences personDefendant.personDetails with no "
                        + "legal-entity fallback, the TypeError is swallowed at "
                        + "OutboundCourtRegister/index.js:62-64, and the whole hearing's register is "
                        + "silently lost — which is exactly the swallowed error this claim matches "
                        + "on. The port omits the defendant it cannot resolve, counts it, and lets "
                        + "the register survive for the rest; where nothing survives, the document "
                        + "it assembles is refused by the contract (C29) rather than lost.",
                divergence -> divergence instanceof Divergence.Outcome
                        && swallowed(divergence.recorded(), "reading 'personDetails'")
                        && divergence.port().addressedSomebody());
    }

    /**
     * C8 — the attendance lookup throws on an empty list.
     *
     * @return the claim
     */
    private static Claim attendanceReadFromAnEmptyList() {
        return new Claim("C8 (attendance lookup assigns instead of comparing)",
                "HearingMapper.js:13 uses an assignment where it means a comparison, so find always "
                        + "answers element 0 — and on an empty defendantAttendance that is "
                        + "undefined, whose attendanceDays dereference throws and takes the register "
                        + "with it. The port selects by equality against the mapped defendant's ids "
                        + "and answers defendantPresent false for an absent or empty list, so the "
                        + "register survives.",
                divergence -> divergence instanceof Divergence.Outcome
                        && swallowed(divergence.recorded(), "reading 'attendanceDays'")
                        && divergence.port().result() == PortResult.REGISTER);
    }

    /**
     * C2 — a failure swallowed and reported as success.
     *
     * @return the claim
     */
    private static Claim failureSwallowedAndReportedAsSuccess() {
        return new Claim("C2 (orchestration reports success on failure)",
                "Two recorded shapes, one defect: a reference-data outage that "
                        + "ReferenceDataService.js catches and answers nothing for, and a shared "
                        + "time the date service cannot read, whose RangeError cascades into a "
                        + "second TypeError one activity later. Both end in Success: true with no "
                        + "register and no record, which is the row's own sentence — every code path "
                        + "must end in a recorded terminal state, and no failure handling may fail "
                        + "unrecorded (C13: never a swallowed secondary throw; C3: which "
                        + "classification a refused read earns). The port reaches a classified "
                        + "failure, or never runs the transformation at all because the read raises "
                        + "in the adapter, where ReferenceDataNowSubscriptionsClientTest and "
                        + "DistributionPipelineTest pin it.",
                divergence -> divergence instanceof Divergence.Outcome
                        && (swallowed(divergence.recorded(), "Invalid time value")
                                || divergence.recorded().subscriptionsNeverAnswered())
                        && divergence.port().classifiedOrNeverRun());
    }

    /**
     * C4 — the court-centre code is compared against informant codes.
     *
     * @return the claim
     */
    private static Claim matchedByAnInformantCode() {
        return new Claim("C4 (court-centre code compared against informant codes)",
                "CourtRegisterSubscriptions/index.js:51 feeds the same ouCode — the court centre's "
                        + "own code — to matchCourtHouse and to matchProsecutor "
                        + "(SubscriptionsService.js:48-54, `informantCode ===`). A subscription that "
                        + "asked for a different court house therefore matches anyway when its "
                        + "informant code happens to equal a court-centre code, and a recipient "
                        + "nobody subscribed receives a child's register. The port evaluates the "
                        + "informant arm not at all. The claim requires the recorded reference-data "
                        + "body to carry exactly that coincidence — no court-register entry covering "
                        + "the hearing's court house, and at least one whose informantCode is that "
                        + "court house — so a register lost for any other reason is not explained "
                        + "here.",
                divergence -> divergence instanceof Divergence.Outcome
                        && divergence.port().isNoRegister("no-subscriptions")
                        && divergence.recorded().producedDocument()
                        && !coversTheCourtHouse(divergence.recorded())
                        && matchesTheCourtHouseAsAnInformant(divergence.recorded()));
    }

    /**
     * C5 — there is no court-register branch in the matcher.
     *
     * @return the claim
     */
    private static Claim matchedByARouteThatIsNotTheCourtRegisterOne() {
        return new Claim("C5 (no court-register branch in the matcher)",
                "SubscriptionsService.getSubscriptions has no arm keyed on "
                        + "isCourtRegisterSubscription, so a court-register subscription that does "
                        + "not cover the court house can still reach the register through the NOWs "
                        + "arm (line 29, via matchSubscriptionRules) or the prison-register arm "
                        + "(line 39) — accidental routes that survive the upstream filter because "
                        + "the entry also carries the court-register flag. The port matches a "
                        + "court-register subscription through selectedCourtHouses and the "
                        + "vocabulary predicates and through nothing else. The claim requires the "
                        + "recorded body to carry one of those two flags on an entry that covers "
                        + "another court house, and no informant-code coincidence, which is C4's "
                        + "route and not this one.",
                divergence -> divergence instanceof Divergence.Outcome
                        && divergence.port().isNoRegister("no-subscriptions")
                        && divergence.recorded().producedDocument()
                        && !coversTheCourtHouse(divergence.recorded())
                        && !matchesTheCourtHouseAsAnInformant(divergence.recorded())
                        && carriesAnAccidentalRoute(divergence.recorded()));
    }

    /**
     * C30 — major-creditor flags match inconsistently on empty data.
     *
     * @return the claim
     */
    private static Claim majorCreditorFlagThatPassesOnEmptyData() {
        return new Claim("C30 (major-creditor flags match inconsistently on empty data)",
                "VocabularyService.js:329-334 leaves the register's two major-creditor lists empty "
                        + "forever, and SubscriptionsService.js:295-297 then ends matchMajorCreditor "
                        + "with `anyMajorCreditor && (prosecutorMajorCreditor != null || "
                        + "nonProsecutorMajorCreditor != null)`. An empty array is not null, so a "
                        + "subscription asking for any major creditor matches a court register that "
                        + "names none — while the two specific flags, tested with `.length > 0` on "
                        + "the same empty lists, cannot match at all. The port requires a non-empty "
                        + "list from all three, so the register is not produced. The claim requires "
                        + "the court-house route to be open — this is a vocabulary answer and not a "
                        + "route, which is what separates it from C4 and C5 — and an entry covering "
                        + "the hearing's court house to carry anyMajorCreditor.",
                divergence -> divergence instanceof Divergence.Outcome
                        && divergence.port().isNoRegister("no-subscriptions")
                        && divergence.recorded().producedDocument()
                        && coversTheCourtHouse(divergence.recorded())
                        && asksForAnyMajorCreditor(divergence.recorded()));
    }

    /**
     * C25 — ethnicity is written only when both descriptions are present.
     *
     * @return the claim
     */
    private static Claim ethnicityFromWhicheverDescriptionIsThere() {
        return new Claim("C25 (ethnicity only when both descriptions present)",
                "YouthDefendantMapper.js:70-74 returns an ethnicity only when the observed AND the "
                        + "self-defined description are both present, which makes the `||` on line "
                        + "72 unreachable and drops the ethnicity of a child who stated one and had "
                        + "no observation recorded. The port writes the observed description when "
                        + "there is one and the self-defined description otherwise. This adds "
                        + "ethnicity data to registers that previously carried none, which is why "
                        + "the row gates on information governance and not only on business "
                        + "sign-off. The claim requires the recording to carry nothing at this "
                        + "field and the port's value to be a description the payload itself holds "
                        + "as its only one — so a port that invented an ethnicity, or copied the "
                        + "wrong one of two, is still a difference.",
                divergence -> divergence instanceof Divergence.Field field
                        && field.path().endsWith("/ethnicity")
                        && field.oracleValue() == null
                        && isTheOnlyDescriptionOnRecord(
                                divergence.recorded().hearing(), string(field.portValue())));
    }

    /**
     * C21 — the ASN derivation dies on records with no person.
     *
     * @return the claim
     */
    private static Claim asnRecordWithoutAPerson() {
        return new Claim("C21 (ASN derivation dies on legal-entity records)",
                "ProsecutionCaseOrApplicationMapper.js:46-55 derives the ASN by filtering the "
                        + "case's defendants to the register defendant's own masterDefendantId and "
                        + "then reading `d.personDefendant.arrestSummonsNumber` with no "
                        + "`d.personDefendant &&` guard, so a matching record that carries no person "
                        + "block throws and OutboundCourtRegister's catch loses the whole hearing's "
                        + "register. The port's guard is the informant twin's: a record without a "
                        + "person contributes no ASN and causes no throw. The claim matches on the "
                        + "recorded dereference itself, so it explains that swallowed TypeError and "
                        + "no other one.",
                divergence -> divergence instanceof Divergence.Outcome
                        && swallowed(divergence.recorded(), "reading 'arrestSummonsNumber'")
                        && divergence.port().addressedSomebody());
    }

    /**
     * C35 — the hearing date is stamped from the wall clock.
     *
     * @return the claim
     */
    private static Claim hearingDateReadFromTheWallClock() {
        return new Claim("C35 (hearing date stamped from the wall clock)",
                "RegisterFragmentService.js:46-55 has two legs that end at "
                        + "`dateService.getLocalDateTime(undefined)`, and `moment.tz(undefined, "
                        + "zone)` is the current time: a hearing whose gathered results name no "
                        + "ordered date, and a sitting record carrying no sittingDay on the day the "
                        + "results were ordered, both stamp hearingDate with whenever the function "
                        + "app happened to run. The port is clock-free: no ordered date means no "
                        + "hearingDate at all, and a sitting record naming no day matches nothing, "
                        + "so the date falls back to the ordered one. The claim proves the recorded "
                        + "value IS the clock rather than assuming it — the recording is read back "
                        + "as a London wall clock through C10's own rendering and must name the "
                        + "instant the corpus was built at — and requires the port not to have "
                        + "written that instant. It covers two shapes, because the row's leg (a) "
                        + "produces two: where the port has an ordered date to fall back to the "
                        + "difference is the field, and where it has none the document it assembles "
                        + "carries no hearingDate at all and the frozen contract refuses it at that "
                        + "very pointer — which is what the row says leg (a) must do, in preference "
                        + "to accepting a clock reading.",
                divergence -> {
                    if (divergence instanceof Divergence.Field field) {
                        return "/hearingDate".equals(field.path())
                                && isTheCorpusClock(field.oracleValue(), divergence.recorded())
                                && !isTheCorpusClock(field.portValue(), divergence.recorded());
                    }
                    return divergence instanceof Divergence.Outcome
                            && divergence.recorded().producedDocument()
                            && isTheCorpusClock(
                                    divergence.recorded().expected().get("hearingDate"),
                                    divergence.recorded())
                            && divergence.port().refusedByTheContract()
                            && "/hearingDate".equals(divergence.port().failurePointer());
                });
    }

    /**
     * C35 — the same two dereferences, in the shapes where they throw.
     *
     * @return the claim
     */
    private static Claim hearingDateDereferenceThatThrows() {
        return new Claim("C35 (hearing date stamped from the wall clock)",
                "The other half of the same row. `if (hearingObj.hearingDays)` is a truthiness test "
                        + "and the callback dereferences `hearingDay.sittingDay`, so a hearingDays "
                        + "that is a truthy non-array (`.find is not a function`) and a hearingDays "
                        + "carrying a null element both throw inside SetCourtRegister, which "
                        + "catches, logs and discards — the whole hearing's register lost with no "
                        + "trace. The port classifies both as a transformation failure and "
                        + "dead-letters. The claim matches the two recorded dereferences by name.",
                divergence -> divergence instanceof Divergence.Outcome
                        && (swallowed(divergence.recorded(), "hearingDays.find is not a function")
                                || swallowed(divergence.recorded(), "reading 'sittingDay'"))
                        && divergence.port().result() == PortResult.FAILED);
    }

    /**
     * C12 — an evening share reads the next day's subscription set.
     *
     * @return the claim
     */
    private static Claim subscriptionsReadForTheDayAfterTheShare() {
        return new Claim("C12 (evening shares read the next day's subscriptions)",
                "ReferenceDataService.js:38 computes the reference-data day as `new "
                        + "Date(registerDate).toISOString().slice(0,10)`, and registerDate has "
                        + "already been relabelled by C10 — so a hearing shared between 23:00 and "
                        + "midnight UTC in BST asks for the NEXT day's subscription set and is "
                        + "addressed by whoever was subscribed then. The port asks for the UTC day "
                        + "of the shared time. This is the one effect of C10 that never reaches the "
                        + "document: a register addressed from the wrong day's set looks entirely "
                        + "ordinary, which is why the recorder captured the whole GET and why the "
                        + "audit compares the day on the wire rather than a value in the output. "
                        + "The claim computes both days from the case's own shared time — the port "
                        + "owes its UTC day, the legacy asked for its Europe/London one — so it "
                        + "explains that relabelling and not any other day difference. A shared time "
                        + "carrying no offset names no instant to convert, the two readings coincide, "
                        + "and a difference there would be a port defect rather than this row.",
                divergence -> {
                    if (!(divergence instanceof Divergence.ReferenceDataDay day)) {
                        return false;
                    }
                    final Instant shared = instantOf(day.recorded().sharedTime().orElse(null));
                    return shared != null
                            && LocalDate.ofInstant(shared, ZoneOffset.UTC).toString()
                                    .equals(day.portDay())
                            && LocalDate.ofInstant(shared, LONDON).toString()
                                    .equals(day.oracleDay());
                });
    }

    // --- the progression-leg rows ----------------------------------------------------------------

    /**
     * P10 - the defendant-type rule throws on shapes the contract permits.
     *
     * @return the row
     */
    private static ProgressionRow applicationProgressionsOwnRuleCannotRead() {
        return new ProgressionRow("P10 (defendant-type resolution throws on permitted shapes)",
                "CourtRegisterHandler.getDefendantType:139 unboxes getAppealFlag() and :144 "
                        + "dereferences getRespondents(), neither of which courtApplication.json "
                        + "makes required, so a hearing that is in contract kills progression's "
                        + "command and every child on the register loses their entry. The recorder "
                        + "captured both as thrown stacks rather than answers, which is why the "
                        + "goldens for those two shapes carry a threw and no defendantType at all. "
                        + "This port reads an absent flag as not set and an absent respondent list "
                        + "as no respondents, so each answers the rule's own default. The row is "
                        + "claimed against the recorded refusal itself - it must be the "
                        + "NullPointerException the recorder wrote, at one of the two dereferences "
                        + "the row names - and against the answer, which must be Applicant and "
                        + "nothing else: a golden that recorded an answer is not this row's, and "
                        + "neither is a port that answered something progression's rule would have "
                        + "reached had it survived. The third shape P10 specifies, a respondent "
                        + "carrying no masterDefendant, reaches no recorded golden and is pinned "
                        + "from a synthesised hearing in DefendantTypeResolverTest instead.",
                deviation -> deviation.recordedAnswer() == null
                        && deviation.recordedRefusal() != null
                        && deviation.recordedRefusal().startsWith(PROGRESSION_REFUSAL)
                        && UNREADABLE_APPLICATION.stream()
                                .anyMatch(deviation.recordedRefusal()::contains)
                        && APPLICANT_TYPE.equals(deviation.portAnswer()));
    }

    // --- reading the trees -----------------------------------------------------------------------

    /**
     * A node's text, where it has any.
     *
     * @param node the node; may be {@code null}
     * @return the text, or {@code null}
     */
    private static String string(final JsonNode node) {
        return node == null || !node.isString() ? null : node.stringValue();
    }

    /**
     * Whether a node is the JSON boolean {@code false}.
     *
     * @param node the node; may be {@code null}
     * @return whether it is false
     */
    private static boolean isFalse(final JsonNode node) {
        return node != null && node.isBoolean() && !node.booleanValue();
    }

    /**
     * Whether a node is missing or is an explicit JSON null, which this payload spells the same
     * absence two ways with.
     *
     * @param node the node; may be {@code null}
     * @return whether nothing is there
     */
    private static boolean isAbsent(final JsonNode node) {
        return node == null || node.isNull();
    }

    /**
     * Whether a node is present and is not a JSON boolean.
     *
     * @param node the node; may be {@code null}
     * @return whether it is present and not a boolean
     */
    private static boolean isNotABoolean(final JsonNode node) {
        return node != null && !node.isNull() && !node.isBoolean();
    }

    /**
     * The verdict type the offence a {@code verdictCode} difference was reported at was mapped from.
     *
     * <p>The difference carries a JSON pointer into the recorded <em>document</em>, and the value the
     * port owes lives in the <em>payload</em>, so the two have to be joined. The pointer's parent is
     * the recorded offence; the payload offence it was mapped from is the one carrying the same
     * offence code and order index. Resolving to nothing, or to two verdict types that disagree, is
     * answered as nothing — a difference this claim cannot attribute exactly is one it does not
     * attribute at all.
     *
     * @param field the observed difference
     * @return the verdict type, or {@code null} where the offence resolves to none or to several
     */
    private static JsonNode verdictTypeBehind(final Divergence.Field field) {
        final String path = field.path();
        final JsonNode recordedOffence = field.recorded().expected()
                .at(path.substring(0, path.length() - VERDICT_CODE.length()));
        if (!recordedOffence.isObject()) {
            return null;
        }
        JsonNode resolved = null;
        for (final JsonNode candidate : offencesOf(field.recorded().hearing())) {
            if (!namesTheSameOffence(recordedOffence, candidate)) {
                continue;
            }
            final JsonNode verdictType = child(child(candidate, "verdict"), "verdictType");
            if (resolved != null && !resolved.equals(verdictType)) {
                return null;
            }
            resolved = verdictType;
        }
        return resolved;
    }

    /**
     * The code {@code OffenceMapper.verdictCode} owes for a verdict type — C23's own derivation.
     *
     * @param verdictType the payload's verdict type; may be {@code null}
     * @return the verdict code, its category type where there is no code, or {@code null}
     */
    private static String verdictCodeOwed(final JsonNode verdictType) {
        final String code = string(child(verdictType, "verdictCode"));
        return code == null ? string(child(verdictType, "categoryType")) : code;
    }

    /**
     * Whether the recording carries at this field what the legacy's own mapper would have written:
     * the verdict type's description, or nothing at all where it has none.
     *
     * @param oracleValue the value in the recording; may be {@code null}
     * @param verdictType the payload's verdict type; may be {@code null}
     * @return whether the recording is the shape this row describes
     */
    private static boolean isTheDescriptionOrNothing(
            final JsonNode oracleValue, final JsonNode verdictType) {

        final String description = string(child(verdictType, "description"));
        return isAbsent(oracleValue)
                ? description == null
                : description != null && description.equals(string(oracleValue));
    }

    /**
     * Whether a recorded register offence and a payload offence are the same offence.
     *
     * <p>The register carries no offence id, so identity is the pair the mapper copies straight
     * through: the offence code and the order index. Both are compared as the trees hold them, so an
     * offence a mutation operator emptied matches only another that was emptied the same way.
     *
     * @param recordedOffence the offence in the recorded document
     * @param payloadOffence  the offence in the hearing payload
     * @return whether they are the same offence
     */
    private static boolean namesTheSameOffence(
            final JsonNode recordedOffence, final JsonNode payloadOffence) {

        return sameComponent(recordedOffence, payloadOffence, "offenceCode")
                && sameComponent(recordedOffence, payloadOffence, "orderIndex");
    }

    /**
     * Whether two objects carry the same value at a property, absence included.
     *
     * @param left  the first object
     * @param right the second object
     * @param name  the property name
     * @return whether the two agree there
     */
    private static boolean sameComponent(
            final JsonNode left, final JsonNode right, final String name) {

        final JsonNode leftValue = child(left, name);
        final JsonNode rightValue = child(right, name);
        return leftValue == null ? rightValue == null : leftValue.equals(rightValue);
    }

    /**
     * Every offence a hearing payload carries, wherever it carries it.
     *
     * <p>The register gathers offences from prosecution cases and from court applications alike, so
     * the lookup has to walk the whole payload rather than one branch of it.
     *
     * @param hearing the recorded hearing
     * @return the offences, empty where the payload has none
     */
    private static List<JsonNode> offencesOf(final JsonNode hearing) {
        final List<JsonNode> offences = new ArrayList<>();
        gatherOffences(hearing, offences);
        return offences;
    }

    /**
     * Collects the offences under a node.
     *
     * @param node     the node to walk
     * @param offences the offences found so far
     */
    private static void gatherOffences(final JsonNode node, final List<JsonNode> offences) {
        if (node.isArray()) {
            node.forEach(entry -> gatherOffences(entry, offences));
        } else if (node.isObject()) {
            for (final JsonNode offence : array(node, "offences")) {
                if (offence.isObject()) {
                    offences.add(offence);
                }
            }
            node.propertyStream().forEach(property ->
                    gatherOffences(property.getValue(), offences));
        }
    }

    /**
     * A node's property, treating an explicit JSON null as the absence it spells.
     *
     * @param node the node; may be {@code null}
     * @param name the property name
     * @return the value, or {@code null} where nothing is there
     */
    private static JsonNode child(final JsonNode node, final String name) {
        final JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? null : value;
    }

    /**
     * The instant a shared time names, where it names one.
     *
     * <p>Read here rather than through {@link uk.gov.hmcts.cp.yotresultsdistribution.pipeline.Dates} for the
     * same reason C10's derivation reads the recording rather than the port: a fault in this port's
     * own parsing must not be able to cancel itself out. A value carrying no offset names no instant
     * at all, and is answered as {@code null}.
     *
     * @param sharedTime the recorded shared time; may be {@code null}
     * @return the instant, or {@code null} where the value names none
     */
    private static Instant instantOf(final String sharedTime) {
        if (sharedTime == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(sharedTime).toInstant();
        } catch (DateTimeParseException namesNoInstant) {
            return null;
        }
    }

    /**
     * Whether the legacy recorded a swallowed error carrying some text.
     *
     * @param recorded the recorded case
     * @param text     the text to look for
     * @return whether any swallowed error carries it
     */
    private static boolean swallowed(final RecordedCase recorded, final String text) {
        return recorded.swallowedErrors().stream().anyMatch(error -> error.contains(text));
    }

    /**
     * Whether any court-register subscription in the recorded answer covers the hearing's court
     * house — the one arm the port matches through.
     *
     * @param recorded the recorded case
     * @return whether the court-house route is open
     */
    private static boolean coversTheCourtHouse(final RecordedCase recorded) {
        final String ouCode = recorded.ouCode();
        return ouCode != null && recorded.courtRegisterSubscriptions().stream()
                .anyMatch(subscription -> contains(subscription.get("selectedCourtHouses"), ouCode));
    }

    /**
     * Whether a court-register subscription's informant code is the hearing's own court house —
     * C4's coincidence.
     *
     * @param recorded the recorded case
     * @return whether the informant arm would match
     */
    private static boolean matchesTheCourtHouseAsAnInformant(final RecordedCase recorded) {
        final String ouCode = recorded.ouCode();
        return ouCode != null && recorded.courtRegisterSubscriptions().stream()
                .anyMatch(subscription -> ouCode.equals(string(subscription.get("informantCode"))));
    }

    /**
     * Whether a court-register subscription also carries one of the flags that give it a second way
     * into the matcher — C5's accidental routes.
     *
     * @param recorded the recorded case
     * @return whether an accidental route is open
     */
    private static boolean carriesAnAccidentalRoute(final RecordedCase recorded) {
        return recorded.courtRegisterSubscriptions().stream().anyMatch(subscription ->
                isTrue(subscription.get("isPrisonCourtRegisterSubscription"))
                        || isTrue(subscription.get("isNowSubscription"))
                        || isTrue(subscription.get("isEDTSubscription")));
    }

    /**
     * Whether a subscription covering the hearing's court house asks for any major creditor.
     *
     * @param recorded the recorded case
     * @return whether C30's vacuous flag is set
     */
    private static boolean asksForAnyMajorCreditor(final RecordedCase recorded) {
        final String ouCode = recorded.ouCode();
        return ouCode != null && recorded.courtRegisterSubscriptions().stream()
                .filter(subscription -> contains(subscription.get("selectedCourtHouses"), ouCode))
                .anyMatch(subscription -> {
                    final JsonNode vocabulary = subscription.get("subscriptionVocabulary");
                    return vocabulary != null && isTrue(vocabulary.get("anyMajorCreditor"));
                });
    }

    /**
     * Whether a value is the only ethnicity description the payload records for anybody.
     *
     * <p>The signature of C25 rather than a licence to write anything at this field: the value the
     * port wrote must be a description the hearing holds, and the hearing must hold no other one for
     * that person — which is the shape the legacy's two-sided guard drops.
     *
     * @param hearing the recorded hearing
     * @param value   the value the port wrote
     * @return whether the payload accounts for it
     */
    private static boolean isTheOnlyDescriptionOnRecord(final JsonNode hearing, final String value) {
        if (value == null) {
            return false;
        }
        for (final JsonNode ethnicity : ethnicities(hearing)) {
            final String observed = string(ethnicity.get("observedEthnicityDescription"));
            final String selfDefined = string(ethnicity.get("selfDefinedEthnicityDescription"));
            final boolean onlyOne = observed == null ^ selfDefined == null;
            if (onlyOne && value.equals(observed == null ? selfDefined : observed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every ethnicity block a hearing carries.
     *
     * @param hearing the recorded hearing
     * @return the blocks, empty where none is recorded
     */
    private static List<JsonNode> ethnicities(final JsonNode hearing) {
        final List<JsonNode> found = new ArrayList<>();
        for (final JsonNode prosecutionCase : array(hearing, "prosecutionCases")) {
            for (final JsonNode defendant : array(prosecutionCase, "defendants")) {
                final JsonNode person = defendant.get("personDefendant");
                final JsonNode details = person == null ? null : person.get("personDetails");
                final JsonNode ethnicity = details == null ? null : details.get("ethnicity");
                if (ethnicity != null && ethnicity.isObject()) {
                    found.add(ethnicity);
                }
            }
        }
        return found;
    }

    /**
     * Whether a value is the wall clock the corpus was recorded at, read through C10's rendering.
     *
     * <p>{@code getLocalDateTime} formats a {@code Europe/London} wall clock and appends a literal
     * {@code Z}, so the clock reading a C35 leg leaves behind is the corpus's own clock pin written
     * that way. Reading it back through {@link #instantsOf} and comparing instants proves the
     * recorded value <em>is</em> the clock rather than assuming it from the case's name.
     *
     * @param value    the value to test; may be {@code null}
     * @param recorded the recorded case, which carries the pin
     * @return whether the value names the instant the corpus was built at
     */
    private static boolean isTheCorpusClock(final JsonNode value, final RecordedCase recorded) {
        final String pin = string(recorded.params().get("clockPinIso"));
        final String rendered = string(value);
        if (pin == null || rendered == null) {
            return false;
        }
        final LocalDateTime pinned = LocalDateTime.ofInstant(Instant.parse(pin), ZoneOffset.UTC)
                .withNano(0);
        return instantsOf(rendered).contains(pinned);
    }

    /**
     * Whether an array node contains a string.
     *
     * @param node  the node; may be {@code null}
     * @param value the value to look for
     * @return whether it is there
     */
    private static boolean contains(final JsonNode node, final String value) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (final JsonNode entry : node) {
            if (value.equals(string(entry))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a node is the JSON boolean {@code true}.
     *
     * @param node the node; may be {@code null}
     * @return whether it is true
     */
    private static boolean isTrue(final JsonNode node) {
        return node != null && node.isBoolean() && node.booleanValue();
    }

    /**
     * One of a node's arrays, as a list.
     *
     * @param node the node; may be {@code null}
     * @param name the property name
     * @return the entries, empty where the property is absent or not an array
     */
    private static List<JsonNode> array(final JsonNode node, final String name) {
        final JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.isArray()) {
            return List.of();
        }
        final List<JsonNode> entries = new ArrayList<>();
        value.forEach(entries::add);
        return entries;
    }

    /**
     * Whether a recorded document names anybody to send it to.
     *
     * @param document the recorded document
     * @return whether it carries at least one recipient
     */
    private static boolean hasRecipients(final JsonNode document) {
        final JsonNode recipients = document.get("recipients");
        return recipients != null && recipients.isArray() && !recipients.isEmpty();
    }

    /**
     * The entries of a recorded list the port did not carry, by their reference.
     *
     * @param oracle the recorded list
     * @param port   the port's list
     * @return the recorded entries the port left out
     */
    private static List<JsonNode> droppedEntries(final JsonNode oracle, final JsonNode port) {
        final List<String> kept = new ArrayList<>();
        port.forEach(entry -> kept.add(string(entry.get("caseOrApplicationReference"))));
        final List<JsonNode> dropped = new ArrayList<>();
        oracle.forEach(entry -> {
            if (!kept.contains(string(entry.get("caseOrApplicationReference")))) {
                dropped.add(entry);
            }
        });
        return dropped;
    }

    /**
     * Whether a dropped register entry is a court application the hearing shows was not brought by
     * a prosecuting authority.
     *
     * @param dropped the entry the port left out
     * @param hearing the hearing it was gathered from
     * @return whether the hearing accounts for the drop under C22
     */
    private static boolean isDefenceInitiated(final JsonNode dropped, final JsonNode hearing) {
        final String applicationId = string(dropped.get("courtApplicationId"));
        if (applicationId == null) {
            return false;
        }
        final JsonNode applications = hearing.get("courtApplications");
        if (applications == null || !applications.isArray()) {
            return false;
        }
        for (final JsonNode application : applications) {
            if (applicationId.equals(string(application.get("id")))) {
                final JsonNode applicant = application.get("applicant");
                // An explicit JSON null is an absent applicant here, and the corpus records both
                // shapes: `drop-optional-field` removes the block and `null-field` nulls it. The
                // eligibility gate reads them the same way, so this claim has to as well.
                return isAbsent(applicant) || isAbsent(applicant.get("prosecutingAuthority"));
            }
        }
        return false;
    }

    // --- the vocabulary an observed divergence is described in -----------------------------------

    /**
     * One registered component and the derivation that reconciles the port with the oracle.
     *
     * @param reference  the {@code doc/DEFECT-FIXES.md} C-number, quoted into every failure message
     * @param derivation the renderings the port may produce, given the value the oracle recorded;
     *                   empty when the oracle's value is not one this fix describes
     */
    public record Fix(String reference, Function<String, List<String>> derivation) {

        /**
         * The renderings the port may produce for a value the oracle recorded.
         *
         * @param oracleValue the value in the golden file
         * @return the permitted renderings, or empty when the oracle's value is undescribed
         */
        public List<String> permittedFor(final String oracleValue) {
            return derivation.apply(oracleValue);
        }
    }

    /**
     * One registered claim: a difference class, the row that authorises it, and the predicate that
     * recognises it.
     *
     * @param reference the {@code doc/DEFECT-FIXES.md} C-number, quoted into every failure message
     * @param rationale what the row says, in enough detail to read a red build without opening it
     * @param predicate whether an observed divergence carries this fix's signature
     */
    public record Claim(String reference, String rationale, Predicate<Divergence> predicate) {

        /**
         * Whether this claim explains an observed divergence.
         *
         * @param divergence the divergence
         * @return whether the predicate recognises it
         */
        public boolean explains(final Divergence divergence) {
            return predicate.test(divergence);
        }
    }

    /**
     * One registered progression-leg row: a deviation class, the {@code P} row that authorises it,
     * and the predicate that recognises it.
     *
     * <p>The {@link Claim} of the second oracle, and deliberately a separate type rather than a
     * fourth {@link Divergence} shape: a {@link Divergence} carries a {@link RecordedCase} and a
     * {@link PortOutcome} because it is always about one 001 recording put through this port, and a
     * deviation from a progression golden is about neither. Widening that interface to admit a
     * shape with no recorded case and no port outcome would make both accessors answer
     * {@code null} for one variant, which every existing claim would then have to be read against.
     *
     * @param reference the {@code doc/DEFECT-FIXES.md} P-number, quoted into every failure message
     * @param rationale what the row says, in enough detail to read a red build without opening it
     * @param predicate whether an observed deviation carries this fix's signature
     */
    public record ProgressionRow(
            String reference, String rationale, Predicate<GoldenDeviation> predicate) {

        /**
         * Whether this row explains an observed deviation.
         *
         * @param deviation the deviation
         * @return whether the predicate recognises it
         */
        public boolean explains(final GoldenDeviation deviation) {
            return predicate.test(deviation);
        }
    }

    /**
     * An answer a recorded progression golden and this port do not agree on.
     *
     * <p>Three readings rather than two, because the recorder wrote two different kinds of
     * non-answer and they mean opposite things. A golden carrying an answer is an oracle for it. A
     * golden carrying no answer <em>and</em> a {@code threw} is an oracle for progression having
     * refused - which is what P10 is about - and is not an absence at all.
     *
     * @param goldenId       the golden, as {@code INDEX.json} names it
     * @param recordedAnswer what progression answered, or {@code null} where it answered nothing
     * @param recordedRefusal the stack the recorder wrote instead of an answer, or {@code null}
     * @param portAnswer     what this port answers, or {@code null} where it answers nothing
     */
    public record GoldenDeviation(
            String goldenId,
            String recordedAnswer,
            String recordedRefusal,
            String portAnswer) {
    }

    /**
     * Something the port did that the legacy did not, or the other way about.
     *
     * <p>Two shapes, because there are two ways a port can differ: it can write a different value
     * into a document both sides produced, or it can produce a different thing altogether.
     */
    public sealed interface Divergence {

        /**
         * The recorded case the divergence was observed in.
         *
         * @return the case
         */
        RecordedCase recorded();

        /**
         * What the port did with that case's inputs.
         *
         * @return the outcome
         */
        PortOutcome port();

        /**
         * A component of the document that differs.
         *
         * @param recorded    the recorded case
         * @param port        what the port did
         * @param path        the JSON pointer of the component
         * @param oracleValue what the recording carries there, or {@code null} where it carries
         *                    nothing
         * @param portValue   what the port wrote there, or {@code null} where it wrote nothing
         */
        record Field(
                RecordedCase recorded,
                PortOutcome port,
                String path,
                JsonNode oracleValue,
                JsonNode portValue) implements Divergence {
        }

        /**
         * The run itself ended differently.
         *
         * @param recorded the recorded case
         * @param port     what the port did
         */
        record Outcome(RecordedCase recorded, PortOutcome port) implements Divergence {
        }

        /**
         * The two runs asked reference data for different days' subscriptions.
         *
         * <p>Its own shape rather than a field of the document, because it is not in the document:
         * the day is a query parameter of the {@code now-subscriptions} GET, the recorder captured
         * the whole request, and a register addressed from the wrong day's subscription set is
         * otherwise indistinguishable from a correct one.
         *
         * @param recorded  the recorded case
         * @param port      what the port did
         * @param oracleDay the day the legacy asked for
         * @param portDay   the day this port asks for
         */
        record ReferenceDataDay(
                RecordedCase recorded,
                PortOutcome port,
                String oracleDay,
                String portDay) implements Divergence {
        }
    }

    /** The five things the port can do with a recorded case's inputs. */
    public enum PortResult {

        /** It assembled a register the contract accepts. */
        REGISTER,

        /** It completed with one of the bounded no-register reasons. */
        NO_REGISTER,

        /** The group-proceedings policy suppressed the register before the chain ran. */
        SUPPRESSED,

        /** A stage raised a classified transformation failure. */
        FAILED,

        /** The transformation was never reached, because the inputs never arrived. */
        NOT_TRANSFORMED
    }

    /**
     * What the port did with one recorded case's inputs.
     *
     * @param result         which of the five
     * @param document       the assembled register, or {@code null}
     * @param reason         the bounded no-register reason, or {@code null}
     * @param failureCode    the bounded failure reason code, or {@code null}
     * @param failurePointer the JSON pointer a contract refusal named, or the empty string
     */
    public record PortOutcome(
            PortResult result,
            JsonNode document,
            String reason,
            String failureCode,
            String failurePointer) {

        /** The reason code a document the frozen contract refuses is classified under. */
        private static final String CONTRACT_VIOLATION = "OUTBOUND_CONTRACT_VIOLATION";

        /**
         * Whether the port completed with a particular no-register reason.
         *
         * @param expected the bounded reason
         * @return whether it did
         */
        public boolean isNoRegister(final String expected) {
            return result == PortResult.NO_REGISTER && expected.equals(reason);
        }

        /**
         * Whether the port refused a document its own contract check would not pass.
         *
         * @return whether it did
         */
        public boolean refusedByTheContract() {
            return result == PortResult.FAILED && CONTRACT_VIOLATION.equals(failureCode);
        }

        /**
         * Whether the port matched at least one subscription — which it did if it produced a
         * register, and also if the register it produced was refused by the contract, because the
         * chain assembles nothing it has not first addressed.
         *
         * @return whether it addressed somebody
         */
        public boolean addressedSomebody() {
            return result == PortResult.REGISTER || refusedByTheContract();
        }

        /**
         * Whether the group-proceedings policy suppressed the register.
         *
         * @return whether it did
         */
        public boolean suppressed() {
            return result == PortResult.SUPPRESSED;
        }

        /**
         * Whether the port reached a classified failure, or never ran the transformation because
         * the inputs it needed were never read.
         *
         * @return whether it did
         */
        public boolean classifiedOrNeverRun() {
            return result == PortResult.FAILED || result == PortResult.NOT_TRANSFORMED;
        }

        /**
         * A short rendering for a report or a failure message.
         *
         * @return what the port did, in one line
         */
        public String describe() {
            return switch (result) {
                case REGISTER -> "assembled a register";
                case NO_REGISTER -> "completed with no register: " + reason;
                case SUPPRESSED -> "suppressed the register as group proceedings";
                case FAILED -> "failed: " + failureCode
                        + (failurePointer.isEmpty() ? "" : " at " + failurePointer);
                case NOT_TRANSFORMED -> "never ran the transformation: " + reason;
            };
        }

        /**
         * The outcome of a case whose inputs the port never received.
         *
         * @param why why they never arrived
         * @return the outcome
         */
        public static PortOutcome notTransformed(final String why) {
            return new PortOutcome(PortResult.NOT_TRANSFORMED, null, why, null, "");
        }
    }

    /**
     * Every claim that explains a divergence, or nothing.
     *
     * <p>A convenience over {@link #claimedBy} for callers that have already established there is at
     * most one.
     *
     * @param divergence the observed divergence
     * @return the single claim, or empty
     */
    public static Optional<Claim> claiming(final Divergence divergence) {
        final List<Claim> claims = claimedBy(divergence);
        return claims.size() == 1 ? Optional.of(claims.get(0)) : Optional.empty();
    }
}
