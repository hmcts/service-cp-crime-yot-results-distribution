package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.OutboundContractValidator;
import uk.gov.hmcts.cp.yotresultsdistribution.application.TransformationResult;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.FailureClassification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NoRegisterReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ReasonCode;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.DifferentialCorpus;
import uk.gov.hmcts.cp.yotresultsdistribution.support.DifferentialCorpus.RecordedCase;
import uk.gov.hmcts.cp.yotresultsdistribution.support.JsonParity;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * A hearing payload becoming a register, through every stage at once.
 *
 * <p>The stages themselves are pinned one suite each — the fragment build, the addressing, the
 * twelve mappers, the contract check. What none of them can say is what happens when a stage
 * declines: each answers in its own vocabulary, an empty list here, a {@code null} there, a
 * {@code CourtRegisterDocument} that was never assembled. The legacy has the same four answers and
 * flattens all of them into one bare {@code undefined} that the orchestration reports as
 * {@code Success: true} (C33), which is why "the register reached nobody" and "the pipeline stopped
 * working" are the same number on the same dashboard today.
 *
 * <p>So this suite is about the joins. Four things it asserts and no single stage can:
 *
 * <ul>
 *   <li><strong>Each no-op keeps its own name.</strong> A hearing that gathered nobody is
 *       {@code no-defendants} (C6) — <em>not</em> {@code no-subscriptions}, which is what asking the
 *       questions in the aggregation's order would answer, because a register with no defendants
 *       matches no subscription either. A court centre nobody subscribes to is
 *       {@code no-subscriptions}. A hearing of adults alone is {@code no-youth-defendants}. And a
 *       register whose every matched subscriber was dropped is {@code no-subscriptions} again, by
 *       another route (C36) — there is nobody to distribute to, and the legacy posts it anyway.</li>
 *   <li><strong>The document is validated before anyone tries to send it</strong> (C29). An
 *       address-less child is a classified, non-transient failure carrying the pointer of the gap,
 *       where the legacy sends the document, is answered 400, swallows it (C1), and loses the whole
 *       hearing's register with no trace.</li>
 *   <li><strong>A stage that fails classifies rather than disappears.</strong> Every failure the
 *       stages can raise leaves this chain as a classified one the pipeline can act on; none of them
 *       becomes a {@code NoRegister}, which would record a failure as one of the flow's ordinary
 *       results.</li>
 *   <li><strong>It is pure.</strong> Reference data's answer is an argument, not something the chain
 *       fetches (constitution Principle V), the payload it is handed comes back unedited, and two
 *       runs over the same inputs agree — there is no clock anywhere behind it.</li>
 *   <li><strong>The register says which side of a court application it covers</strong>, and adds
 *       nothing else. Increment 002 records the document instead of posting it, and the batch's PDF
 *       payload prints its defendants under a {@code defendantType} progression used to resolve
 *       after the POST. It is resolved here, where the hearing and the assembled document meet, and
 *       it is the <em>only</em> thing 002 adds: the 001 recordings come back through the golden
 *       comparison differing exactly where they differed before.</li>
 * </ul>
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C6,
 *      C26, C29, C31, C33, C36
 */
@DisplayName("RegisterTransformationChain")
class RegisterTransformationChainTest {

    /** Every base hearing sits at this court centre, and the subscriptions below name it. */
    private static final String OU_CODE = "B01LY00";

    private static final String HEARING_ID = "1828f356-f746-4f2d-932b-79ef2df95c80";

    private static final String COURT_CENTRE_ID = "853b1ff8-fc2a-44d1-a621-0cd16419f54a";

    private static final String YOUTH = "6647df67-a065-4d07-90ba-a8daa064ecc4";

    /** The 001 recording of the base hearing whose child survives every filter. */
    private static final String SURVIVING_YOUTH_RECORDING = "base__surviving-youth-defendant";

    /** The 001 recording of the base hearing C22's non-prosecuting applicant is on. */
    private static final String NON_PROSECUTING_AUTHORITY_RECORDING =
            "base__non-prosecuting-authority-application";

    /**
     * Where each 001 recording already differs from this port, and the whole of where it does.
     *
     * <p>Four rows between them, every one of them a {@code doc/DEFECT-FIXES.md} claim the
     * differential audit reconciles: {@code fileName} is C11, the legacy datetime name with the
     * colons Windows refuses; the two {@code /hearing/} fields are C9, where an attendance day is
     * compared against a register datetime and so never matches, leaving the appearance column
     * empty; {@code verdictCode} is C23, prose in a field the platform model gives a code; and the
     * dropped {@code prosecutionCasesOrApplications} entry is C22, the defence-initiated
     * application the legacy admits to a register of the court's prosecutions.
     *
     * <p>Two components the fixes also move are deliberately <em>not</em> here, and their absence
     * is the point: {@code registerDate} (C10) and the offence {@code wording} (C24) are registered
     * as <em>derivations</em>, so {@link JsonParity} re-renders the recorded value and demands
     * exactly it rather than reporting a difference. A derivation that stopped deriving would
     * appear in this list, which is what makes the list a statement about the whole document.
     */
    private static final Map<String, List<String>> RECONCILED_IN_001 = Map.of(
            SURVIVING_YOUTH_RECORDING,
            List.of("/fileName",
                    "/defendants/0/hearing/defendantPresent",
                    "/defendants/0/hearing/defendantAppearanceDetails",
                    "/defendants/0/prosecutionCasesOrApplications/0/offences/0/verdictCode"),
            NON_PROSECUTING_AUTHORITY_RECORDING,
            List.of("/fileName",
                    "/defendants/0/hearing/defendantPresent",
                    "/defendants/0/hearing/defendantAppearanceDetails",
                    "/defendants/0/prosecutionCasesOrApplications",
                    "/defendants/0/prosecutionCasesOrApplications/0/offences/0/verdictCode"));

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    /** Every anomaly anything beneath the chain counted. */
    private final List<TransformationAnomaly> anomalies = new ArrayList<>();

    private final RegisterTransformationChain chain = new RegisterTransformationChain(
            new RegisterBuilder(new Dates()),
            new SubscriptionMatcher(new SubscriptionRules()),
            new OutboundContractValidator(JacksonConfig.contractObjectMapper()));

    @Nested
    @DisplayName("a hearing that becomes a register")
    class OnePassEndToEnd {

        @Test
        @DisplayName("a child, a court centre and a subscriber make one addressed document")
        void a_child_and_a_subscriber_make_one_addressed_document() {
            final CourtRegisterDocument document = register(
                    transform(survivingYouth(), answering(youthSubscription())));

            assertThat(document.hearingId()).isEqualTo(HEARING_ID);
            assertThat(document.courtCentreId()).isEqualTo(COURT_CENTRE_ID);
            assertThat(document.registerDate()).isEqualTo("2020-06-01T10:00:00Z");
            assertThat(document.hearingDate()).isEqualTo("2020-01-20T00:00:00Z");
            assertThat(document.fileName())
                    .isEqualTo("court-register_2020-06-01_B01LY00_" + HEARING_ID + ".pdf");
        }

        @Test
        @DisplayName("addressed to the subscription that matched, and to nobody else")
        void addressed_to_the_subscription_that_matched() {
            final CourtRegisterDocument document = register(
                    transform(survivingYouth(), answering(youthSubscription(), elsewhere())));

            assertThat(document.recipients())
                    .extracting(CourtRegisterRecipient::emailAddress1)
                    .containsExactly("yos.southwest@example.gov.uk");
            assertThat(document.recipients())
                    .extracting(CourtRegisterRecipient::emailTemplateName)
                    .containsExactly("cr_youth");
        }

        @Test
        @DisplayName("carrying the youth alone, out of a hearing whose first defendant is an adult")
        void carrying_the_youth_alone() {
            // C31 and the youth filter in one pass: the adult decides neither who the register is
            // addressed to nor who is on it.
            final CourtRegisterDocument document = register(
                    transform(adultFirstYouthSecond(), answering(youthSubscription())));

            assertThat(document.defendants())
                    .extracting(CourtRegisterDefendant::masterDefendantId)
                    .containsExactly(YOUTH);
        }

        @Test
        @DisplayName("nothing was skipped on the way, so nothing was counted")
        void nothing_was_skipped_on_the_way() {
            transform(survivingYouth(), answering(youthSubscription()));

            assertThat(anomalies).isEmpty();
        }
    }

    @Nested
    @DisplayName("a no-op at each stage names its own reason (C6, C33, C36)")
    class EachNoOpNamesItself {

        @Test
        @DisplayName("a hearing that gathered nobody is no-defendants, not no-subscriptions")
        void a_hearing_that_gathered_nobody_is_no_defendants() {
            // C6 at chain level, which is the row's remaining half. The guard the legacy writes
            // (`SetCourtRegister/index.js:35-38`) can never fire, so an empty list flows on as
            // `registerDefendants: []` and the run reports success having done nothing. Asking the
            // subscription question first — the aggregation's own order — would answer
            // `no-subscriptions` here too, because a register with no defendants satisfies no
            // subscription: two different states, one number.
            final TransformationResult result =
                    transform(gatheringNobody(), answering(youthSubscription()));

            assertThat(reason(result)).isEqualTo(NoRegisterReason.NO_DEFENDANTS);
            assertThat(reason(result)).isNotEqualTo(NoRegisterReason.NO_SUBSCRIPTIONS);
        }

        @Test
        @DisplayName("reference data answering none in force is no-subscriptions")
        void nothing_in_force_is_no_subscriptions() {
            assertThat(reason(transform(survivingYouth(), answering())))
                    .isEqualTo(NoRegisterReason.NO_SUBSCRIPTIONS);
        }

        @Test
        @DisplayName("and so is a subscription that asked for another court house")
        void another_court_house_is_no_subscriptions() {
            assertThat(reason(transform(survivingYouth(), answering(elsewhere()))))
                    .isEqualTo(NoRegisterReason.NO_SUBSCRIPTIONS);
        }

        @Test
        @DisplayName("a hearing of adults alone is no-youth-defendants")
        void a_hearing_of_adults_alone_is_no_youth_defendants() {
            assertThat(reason(transform(adultsOnly(), answering(adultSubscription()))))
                    .isEqualTo(NoRegisterReason.NO_YOUTH_DEFENDANTS);
        }

        @Test
        @DisplayName("a register whose every subscriber was dropped is no-subscriptions (C36)")
        void every_subscriber_dropped_is_no_subscriptions() {
            // The legacy posts this document with `recipients: undefined`. Progression stores it,
            // renders the PDF at 18:00, and emits a notification nothing subscribes to, so the
            // register sticks at GENERATED forever, visible to nobody. There is nobody to distribute
            // to, which is what `no-subscriptions` says.
            final TransformationResult result =
                    transform(survivingYouth(), answering(byFirstClassPost()));

            assertThat(reason(result)).isEqualTo(NoRegisterReason.NO_SUBSCRIPTIONS);
        }

        @Test
        @DisplayName("and the drop that emptied it is counted, not merely absorbed")
        void the_drop_that_emptied_it_is_counted() {
            transform(survivingYouth(), answering(byFirstClassPost()));

            assertThat(anomalies).containsExactly(TransformationAnomaly.LETTER_DELIVERY_DROPPED);
        }
    }

    @Nested
    @DisplayName("a document the frozen contract would refuse (C29)")
    class RefusedBeforeItIsSent {

        @Test
        @DisplayName("a child with no address is a classified failure, not a 400 nobody sees")
        void a_child_with_no_address_is_a_classified_failure() {
            assertThatThrownBy(() -> transform(addressLessYouth(), answering(youthSubscription())))
                    .asInstanceOf(InstanceOfAssertFactories.type(
                            TransformationFailedException.class))
                    .satisfies(refused -> {
                        assertThat(refused.classification())
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(refused.reason())
                                .isEqualTo(ReasonCode.OUTBOUND_CONTRACT_VIOLATION);
                    });
        }

        @Test
        @DisplayName("saying where the gap was, and nothing about the child it was for")
        void saying_where_the_gap_was_and_nothing_about_the_child() {
            assertThatThrownBy(() -> transform(addressLessYouth(), answering(youthSubscription())))
                    .hasMessageContaining("/defendants/0/address")
                    .hasMessageNotContaining("Fred Duncan Smith")
                    .hasMessageNotContaining("2008-04-17");
        }

        @Test
        @DisplayName("and a register the contract accepts is not refused")
        void a_register_the_contract_accepts_is_not_refused() {
            assertThatCode(() -> transform(survivingYouth(), answering(youthSubscription())))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("a stage that fails classifies, and is never swallowed")
    class FailuresClassify {

        @Test
        @DisplayName("an ordered date nothing can parse")
        void an_ordered_date_nothing_can_parse() {
            // C13's shape reaching the chain: the legacy's catch calls `this.context.log` with
            // `this` unbound, so the catch itself throws and the whole chain is swallowed upstream
            // into `Success: true`.
            assertThatThrownBy(() -> transform(orderedOn("20/01/2020"),
                    answering(youthSubscription())))
                    .asInstanceOf(InstanceOfAssertFactories.type(
                            TransformationFailedException.class))
                    .satisfies(failed -> {
                        assertThat(failed.classification())
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failed.reason()).isEqualTo(ReasonCode.TRANSFORMATION_FAILED);
                    });
        }

        @Test
        @DisplayName("an envelope carrying no hearing")
        void an_envelope_carrying_no_hearing() {
            final JsonNode envelope = mapper.createObjectNode()
                    .put("sharedTime", "2020-06-01T10:00:00Z");

            assertThatThrownBy(() -> transform(envelope, answering(youthSubscription())))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("an envelope carrying no shared time")
        void an_envelope_carrying_no_shared_time() {
            // Every date on the register is derived from it, so a claim-check envelope without one
            // is unusable — and unusable in the same way on every redelivery, which is what makes it
            // non-transient rather than something to hand back four more times.
            final ObjectNode envelope = mapper.createObjectNode();
            envelope.set("hearing", survivingYouth().get("hearing"));

            assertThatThrownBy(() -> transform(envelope, answering(youthSubscription())))
                    .isInstanceOf(TransformationFailedException.class);
        }

        @Test
        @DisplayName("and a run that failed skipped nothing, so it counted nothing")
        void a_run_that_failed_counted_nothing() {
            // A failure and an anomaly are different things to tell an operator. An anomaly is a
            // register that went out with a part missing; this run produced no register at all, so
            // counting it as a skip would inflate the series C19, C20 and C27 are read on.
            assertThatThrownBy(() -> transform(addressLessYouth(), answering(youthSubscription())))
                    .isInstanceOf(TransformationFailedException.class);

            assertThat(anomalies).isEmpty();
        }
    }

    @Nested
    @DisplayName("the side of the court application the register covers")
    class TheDefendantType {

        @Test
        @DisplayName("an appeal the child is the appellant on is recorded as Appellant")
        void an_appeal_the_child_is_the_appellant_on_is_recorded_as_appellant() {
            // The rule progression applies after the POST, applied here instead: an application
            // whose applicant is a master defendant and whose type carries both the appeal and the
            // applicant-appellant flags puts its defendants on the appellant side
            // (`CourtRegisterHandler.java:131-153`, recorded as the `synthetic__appellant` golden).
            final CourtRegisterDocument document = register(
                    transform(anAppealTheChildIsTheAppellantOn(), answering(youthSubscription())));

            assertThat(document.defendantType()).isEqualTo("Appellant");
        }

        @Test
        @DisplayName("and a register naming no court application carries no type at all")
        void a_register_naming_no_court_application_carries_no_type() {
            // Not the empty string progression leaves (`CourtRegisterHandler:84` writes
            // `StringUtils.EMPTY` when there is no application to read): the field is optional in
            // the frozen register-document schema and the record is serialised NON_NULL, so a
            // hearing without one leaves it absent exactly as 001 did.
            final CourtRegisterDocument document = register(
                    transform(survivingYouth(), answering(youthSubscription())));

            assertThat(document.defendantType()).isNull();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {SURVIVING_YOUTH_RECORDING, NON_PROSECUTING_AUTHORITY_RECORDING})
        @DisplayName("and the 001 recordings are otherwise byte-identical")
        void the_001_recordings_are_otherwise_byte_identical(final String caseId) {
            final RecordedCase recorded = DifferentialCorpus.load(caseId);
            final ObjectNode ported = (ObjectNode) mapper.valueToTree(
                    register(transform(recorded.payload(), recorded.subscriptions())));

            // The golden comparison the differential audit runs, with the one field this increment
            // adds taken out of the tree first: everything else has to land where 001 left it. The
            // paths below are the defect-fix rows those two recordings already reconcile, and a
            // path arriving that is not one of them is 002 having moved something it was not asked
            // to move.
            final JsonNode addedByThisIncrement = ported.remove("defendantType");
            assertThat(JsonParity.differences(recorded.expected(), ported))
                    .extracting(JsonParity.Difference::path)
                    .containsExactlyInAnyOrderElementsOf(RECONCILED_IN_001.get(caseId));

            // Neither recording's first defendant names a court application on its first
            // case-or-application entry, which is the only one progression's `getCourtApplicationId`
            // (`:226-235`) reads, so both T004 goldens record the empty string and this port writes
            // nothing: for the 001 corpus the recorded register is the posted one, byte for byte.
            assertThat(addedByThisIncrement)
                    .describedAs("%s names no court application, so no type is added", caseId)
                    .isNull();
        }
    }

    @Nested
    @DisplayName("pure, by contract")
    class PureByContract {

        @Test
        @DisplayName("the payload it was handed comes back exactly as it went in")
        void the_payload_comes_back_as_it_went_in() {
            // The legacy hands one mutable hearing object down the chain and is saved only by the
            // Durable Functions serialisation boundary between activities; C8's attendance lookup
            // assigns into it. Java passes references, so the stages derive rather than edit.
            final JsonNode payload = survivingYouth();
            final JsonNode pristine = payload.deepCopy();

            transform(payload, answering(youthSubscription()));

            assertThat(payload).isEqualTo(pristine);
        }

        @Test
        @DisplayName("and the answer reference data gave is not edited either")
        void the_reference_data_answer_is_not_edited() {
            // `CourtRegisterSubscriptions/index.js:49` writes the register's vocabulary onto the
            // subscription object it was handed.
            final JsonNode answer = answering(youthSubscription());
            final JsonNode pristine = answer.deepCopy();

            transform(survivingYouth(), answer);

            assertThat(answer).isEqualTo(pristine);
        }

        @Test
        @DisplayName("two runs over the same hearing produce the same register")
        void two_runs_produce_the_same_register() {
            // No clock behind any stage: C35's two wall-clock legs are what a second run would
            // otherwise disagree with the first about.
            final CourtRegisterDocument first =
                    register(transform(survivingYouth(), answering(youthSubscription())));
            final CourtRegisterDocument second =
                    register(transform(survivingYouth(), answering(youthSubscription())));

            assertThat(first).isEqualTo(second);
        }
    }

    // --- driving the chain -------------------------------------------------------------------

    /**
     * Runs the chain over one claim-check envelope and one reference-data answer.
     *
     * @param payload       the claim-check envelope, {@code {hearing, sharedTime}}
     * @param subscriptions reference data's answer for the register's day, already read
     * @return what the transformation produced
     */
    private TransformationResult transform(final JsonNode payload, final JsonNode subscriptions) {
        return chain.transform(command(), payload, subscriptions, anomalies::add);
    }

    /**
     * The register a result produced, failing the assertion if it produced none.
     *
     * @param result the transformation's answer
     * @return the assembled document
     */
    private static CourtRegisterDocument register(final TransformationResult result) {
        assertThat(result).isInstanceOf(TransformationResult.Register.class);
        return ((TransformationResult.Register) result).document();
    }

    /**
     * The reason a result gave for producing nothing, failing the assertion if it produced a
     * register.
     *
     * @param result the transformation's answer
     * @return the bounded reason
     */
    private static NoRegisterReason reason(final TransformationResult result) {
        assertThat(result).isInstanceOf(TransformationResult.NoRegister.class);
        return ((TransformationResult.NoRegister) result).reason();
    }

    /** The validated request the run was admitted under. */
    private static DistributionCommand command() {
        return new DistributionCommand(
                "RESULTS",
                UUID.fromString("3f4a2b1c-5d6e-4f70-8912-a3b4c5d6e7f8"),
                UUID.fromString(HEARING_ID),
                LocalDate.parse("2020-01-20"),
                Instant.parse("2020-06-01T10:00:00Z"),
                "Hearing_Resulted");
    }

    // --- the hearings ------------------------------------------------------------------------

    /** The authored payload whose child survives every filter. */
    private JsonNode survivingYouth() {
        return payload("hearing-with-surviving-youth-defendant.json");
    }

    /** The same hearing with the adult gathered ahead of the youth. */
    private JsonNode adultFirstYouthSecond() {
        return payload("hearing-with-adult-first-youth-second.json");
    }

    /** The hearing whose one defendant is not youth-flagged. */
    private JsonNode adultsOnly() {
        return payload("hearing-with-complete-court-centre.json");
    }

    /** Two children, the first of whom has no address at all. */
    private JsonNode addressLessYouth() {
        return payload("hearing-with-address-less-youth-and-parent.json");
    }

    /**
     * A hearing that gathers nobody: no cases, no applications, and no defendant-level results to
     * gather them by.
     *
     * @return the payload
     */
    private JsonNode gatheringNobody() {
        final JsonNode payload = survivingYouth();
        final ObjectNode hearing = (ObjectNode) payload.get("hearing");
        hearing.set("prosecutionCases", mapper.createArrayNode());
        hearing.set("courtApplications", mapper.createArrayNode());
        hearing.set("defendantJudicialResults", mapper.createArrayNode());
        return payload;
    }

    /**
     * The surviving-youth hearing rebuilt as an appeal the child is the appellant on.
     *
     * <p>Three edits, and each of them is what the rule reads. The prosecution case goes, because
     * the court application has to be the <em>first</em> case-or-application the register's first
     * defendant carries: {@code getCourtApplicationId} ({@code CourtRegisterHandler.java:226-235})
     * reads element zero and nothing else, which is why all six base hearings resolve to no
     * application at all and why not one of them can reach a type. The applicant gains the master
     * defendant the register covers, and the application type gains the appeal and
     * applicant-appellant flags - the branch the {@code synthetic__appellant} golden was recorded
     * against, no base fixture carrying either flag.
     *
     * @return the payload
     */
    private JsonNode anAppealTheChildIsTheAppellantOn() {
        final JsonNode payload = survivingYouth();
        final ObjectNode hearing = (ObjectNode) payload.get("hearing");
        hearing.set("prosecutionCases", mapper.createArrayNode());

        final ObjectNode application = (ObjectNode) hearing.get("courtApplications").get(0);
        final ObjectNode type = (ObjectNode) application.get("type");
        type.put("appealFlag", true);
        type.put("applicantAppellantFlag", true);
        ((ObjectNode) application.get("applicant"))
                .putObject("masterDefendant")
                .put("masterDefendantId", YOUTH);
        return payload;
    }

    /**
     * The surviving-youth hearing with every ordered date rewritten.
     *
     * @param orderedDate the date, in whatever form the case is about
     * @return the payload
     */
    private JsonNode orderedOn(final String orderedDate) {
        return mapper.readTree(mapper.writeValueAsString(survivingYouth())
                .replace("\"orderedDate\":\"2020-01-20\"",
                        "\"orderedDate\":\"" + orderedDate + "\""));
    }

    /**
     * One base payload, deep-copied so a case can change it without changing it for the next one.
     *
     * @param fixture the file name below {@code fixtures/base/}
     * @return the claim-check envelope
     */
    private JsonNode payload(final String fixture) {
        return LegacyFixtures.readBase(fixture).deepCopy();
    }

    // --- the subscriptions -------------------------------------------------------------------

    /**
     * Reference data's answer, carrying the subscriptions in force on the register's day.
     *
     * @param subscriptions the subscriptions in force
     * @return the answer, as the chain receives it
     */
    private JsonNode answering(final JsonNode... subscriptions) {
        final ArrayNode inForce = mapper.createArrayNode();
        for (final JsonNode subscription : subscriptions) {
            inForce.add(subscription);
        }
        return mapper.createObjectNode().set("nowSubscriptions", inForce);
    }

    /** A court-register subscription for this court centre, keyed on youth defendants. */
    private ObjectNode youthSubscription() {
        return courtRegisterSubscription("youthDefendant", OU_CODE);
    }

    /** The same, keyed on adults, so the adults-only hearing is addressed to somebody. */
    private ObjectNode adultSubscription() {
        return courtRegisterSubscription("adultDefendant", OU_CODE);
    }

    /** A subscription that wants youth registers, from a court centre this is not. */
    private ObjectNode elsewhere() {
        return courtRegisterSubscription("youthDefendant", "SOMEWHERE_ELSE");
    }

    /**
     * A matching subscriber who asked for the post <em>instead of</em> email, which this channel
     * cannot serve.
     *
     * <p>Both halves matter. `RecipientMapper.js:13` reads `emailDelivery && forDistribution &&
     * recipient` and never looks at the letter flags at all, so a subscriber who asked for both is
     * emailed and is not dropped — raising the letter flag alone would leave this hearing with a
     * recipient and this case asserting nothing.
     */
    private ObjectNode byFirstClassPost() {
        final ObjectNode subscription = youthSubscription();
        subscription.put("emailDelivery", false);
        subscription.put("firstClassLetterDelivery", true);
        return subscription;
    }

    /**
     * A court-register subscription for one court centre, keyed on one defendant flag, with the
     * appearance, hearing and ignore flags the kernel's own court-register case sets.
     *
     * @param defendantFlag the defendant flag it is keyed on
     * @param courtHouse    the court house it selected
     * @return the subscription, as a mutable copy
     */
    private ObjectNode courtRegisterSubscription(
            final String defendantFlag, final String courtHouse) {

        final ObjectNode subscription =
                (ObjectNode) LegacyFixtures.read("Subscriptions.json").get(0).deepCopy();
        subscription.put("isNowSubscription", false);
        subscription.put("isCourtRegisterSubscription", true);
        subscription.put("emailTemplateName", "cr_youth");
        subscription.putArray("selectedCourtHouses").add(courtHouse);

        final ObjectNode vocabulary = (ObjectNode) subscription.get("subscriptionVocabulary");
        vocabulary.setAll((ObjectNode) mapper.readTree("""
            {"anyAppearance":true,"anyCourtHearing":true,"ignoreCustody":true,
             "ignoreResults":true}"""));
        vocabulary.put(defendantFlag, true);

        final ObjectNode recipient = (ObjectNode) subscription.get("recipient");
        recipient.put("organisationName", "Youth Offending Service - South West London");
        recipient.put("emailAddress1", "yos.southwest@example.gov.uk");
        return subscription;
    }
}
