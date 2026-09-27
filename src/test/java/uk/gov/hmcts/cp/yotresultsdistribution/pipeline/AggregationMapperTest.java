package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterDefendant;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterFragment;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.yotresultsdistribution.support.CapturedLog;
import uk.gov.hmcts.cp.yotresultsdistribution.support.LegacyFixtures;

/**
 * One hearing's register, assembled.
 *
 * <p>Twins the three cases of {@code $DF/OutboundCourtRegister/test/index.test.js} — the worst-shaped
 * suite in the corpus, and the one that decides what actually goes on the wire.
 *
 * <ul>
 *   <li><strong>O1 and O2</strong> assert that a fragment with no matched subscriptions, and one
 *       whose defendants are not youths, both return a bare {@code null}. Those are this flow's two
 *       <em>most common</em> legitimate outcomes, and the orchestration reports both — along with
 *       every other way of producing nothing — as {@code Success: true} (C33). Repointed: each one
 *       says which of the two it was, in a bounded code an operator can count, and a register that
 *       reaches nobody stops being indistinguishable from one that was delivered.</li>
 *   <li><strong>O3</strong> asserts the assembled document, and three of its assertions are broken.
 *       {@code expect(courtRegisterFragment.courtCentreId).toBe(fake.courtCenterId)} compares
 *       {@code undefined} with {@code undefined} — the mapper reads "Center" and the fixture writes
 *       "Centre", so <em>nothing anywhere</em> asserts the court centre id the document carries, and
 *       production sends {@code undefined} (C26). {@code fileName} is asserted only as
 *       {@code toContain('.pdf')}, because the fixture's court centre has no {@code code} and the
 *       real name ends {@code _undefined.pdf} — so the naming convention is unpinned (C11). And the
 *       fixture's court centre has no address, so the venue mapper's address body has never
 *       executed.</li>
 * </ul>
 *
 * <p><strong>C11.</strong> The legacy name is
 * {@code court-register_${registerDate}_${courtCentre.code}.pdf}, where {@code registerDate} is the
 * full datetime: {@code court-register_2020-06-01T11:00:00Z_B01LY00.pdf}. Colons are not legal in a
 * Windows filename, and two hearings at one court centre sharing a second collide. The fixed name is
 * the register <em>day</em>, the court-centre code and the hearing id.
 *
 * <p>Everything else O3 leaves unasserted is asserted here against a fragment and a hearing complete
 * enough to have answers: the venue's court house, local justice area and address; the recipients
 * with their templates; and the defendants, filtered to the youths the register is about.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C11,
 *      C26, C33
 */
@DisplayName("AggregationMapper")
class AggregationMapperTest {

    private static final String HEARING_ID = "1828f356-f746-4f2d-932b-79ef2df95c80";

    private static final String COURT_CENTRE_ID = "853b1ff8-fc2a-44d1-a621-0cd16419f54a";

    private static final String YOUTH_ID = "6647df67-a065-4d07-90ba-a8daa064ecc4";

    private static final String ADULT_ID = "aeb6328d-19d4-49e8-8426-290f096b81dc";

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    /** Every anomaly anything beneath the aggregation counted. */
    private final List<TransformationAnomaly> anomalies = new ArrayList<>();

    @Nested
    @DisplayName("Should return a valid outbound court register — O3")
    class TheAssembledDocument {

        @Test
        @DisplayName("the three dates and ids come across from the fragment unchanged")
        void the_dates_and_ids_come_across_unchanged() {
            assertThat(document().registerDate()).isEqualTo("2020-06-01T10:00:00Z");
            assertThat(document().hearingDate()).isEqualTo("2020-01-20T00:00:00Z");
            assertThat(document().hearingId()).isEqualTo(HEARING_ID);
        }

        @Test
        @DisplayName("the court centre id is the one the fragment carries")
        void the_court_centre_id_is_the_one_the_fragment_carries() {
            // The field nothing in the legacy corpus asserts, because the mapper reads "Center" off
            // an object that spells it "Centre" and the test compares the two absences. Production
            // sends `undefined`.
            assertThat(document().courtCentreId()).isEqualTo(COURT_CENTRE_ID);
        }

        @Test
        @DisplayName("the venue names the court house and its local justice area")
        void the_venue_names_the_court_house_and_its_lja() {
            assertThat(document().hearingVenue().courtHouse())
                    .isEqualTo("Lavender Hill Magistrates' Court");
            assertThat(document().hearingVenue().ljaName())
                    .isEqualTo("South West London Magistrates' Court");
        }

        @Test
        @DisplayName("the venue carries the court's address, which no legacy case reaches")
        void the_venue_carries_the_courts_address() {
            // Every court-register fixture but one has a court centre of `id` and `lja` alone, so
            // the venue mapper's address body has never executed anywhere.
            assertThat(document().hearingVenue().address().address1())
                    .isEqualTo("176A Lavender Hill");
            assertThat(document().hearingVenue().address().postCode()).isEqualTo("SW11 1JU");
        }

        @Test
        @DisplayName("the recipients are the matched subscriptions that could be emailed")
        void the_recipients_are_the_matched_subscriptions() {
            assertThat(document().recipients())
                    .extracting(CourtRegisterRecipient::recipientName,
                            CourtRegisterRecipient::emailTemplateName)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple(
                                    "Youth Offending Service - South West London", "cr_youth"),
                            org.assertj.core.api.Assertions.tuple(
                                    "Lavender Hill Youth Panel", "cr_standard"));
        }

        @Test
        @DisplayName("the defendants are the register's youths, mapped")
        void the_defendants_are_the_registers_youths() {
            assertThat(document().defendants())
                    .extracting(CourtRegisterDefendant::name)
                    .containsExactly("Fred Duncan Smith");
        }
    }

    @Nested
    @DisplayName("what the file is called (C11)")
    class FileName {

        @Test
        @DisplayName("the register day, the court centre code and the hearing id")
        void file_name_is_dated_coded_and_unique() {
            // Fails against the legacy, which writes
            // `court-register_2020-06-01T11:00:00Z_B01LY00.pdf` — the full instant, colons and all,
            // and nothing that tells two hearings at one court centre apart.
            assertThat(document().fileName())
                    .isEqualTo("court-register_2020-06-01_B01LY00_" + HEARING_ID + ".pdf")
                    .doesNotContain(":");
        }

        @Test
        @DisplayName("and no colons, which a Windows file system will not take")
        void and_no_colons() {
            assertThat(document().fileName()).doesNotContain(":");
        }

        @Test
        @DisplayName("two hearings at one court centre in one second do not collide")
        void two_hearings_in_one_second_do_not_collide() {
            // The other half of C11, and the reason the hearing id is in the name at all: the legacy
            // name is the register instant and the court centre, both of which two simultaneous
            // hearings share.
            final RegisterFragment second = withHearingId("2f5c81a0-7b3e-4d69-9a12-c4e80d6b3f57");

            assertThat(document().fileName()).isNotEqualTo(
                    AggregationMapper.map(second, subscriptions(), hearing(), anomalies::add)
                            .fileName());
        }

        @Test
        @DisplayName("an evening share is filed under the day it was shared, not London's next one")
        void an_evening_share_is_filed_under_the_day_it_was_shared() {
            // The differential audit found this at
            // mut__surviving-youth-defendant__shared-time__bst-2300-utc. C10's row says in as many
            // words what a BST evening share moves: "stored values, the filename day and the
            // refdata lookup day (C12) all move". The register date moved and the file-name day did
            // not, because the name was built through Dates.localDate — the London calendar day,
            // which is the right answer for the sitting-day match it was written for and the wrong
            // one here. So a register whose own registerDate says 1 June was filed under 2 June,
            // and under a different day from the subscription set it was addressed by.
            assertThat(fileNameOfShareAt("2020-06-01T23:00:00Z"))
                    .isEqualTo("court-register_2020-06-01_B01LY00_" + HEARING_ID + ".pdf");
        }

        @Test
        @DisplayName("and the day it is filed under is the day it was addressed on")
        void the_day_it_is_filed_under_is_the_day_it_was_addressed_on() {
            // The claim behind the case above, made directly: C12 keys the subscription set to the
            // UTC day of the share, so a file name keyed to any other day names a register by a day
            // nothing else about it agrees with.
            final String sharedTime = "2020-06-01T23:00:00Z";

            assertThat(fileNameOfShareAt(sharedTime))
                    .contains("_" + new Dates().subscriptionDay(sharedTime) + "_");
        }

        @Test
        @DisplayName("a share on the far side of midnight UTC is filed under its own day")
        void a_share_on_the_far_side_of_midnight_is_filed_under_its_own_day() {
            // The other direction, which the current reading gets right and which must stay right:
            // 00:00Z in BST is 01:00 in London on the same date, so both readings answer 1 June.
            assertThat(fileNameOfShareAt("2020-06-01T00:00:00Z"))
                    .isEqualTo("court-register_2020-06-01_B01LY00_" + HEARING_ID + ".pdf");
        }

        /**
         * The file name of a register shared at an instant.
         *
         * @param sharedTime the instant the results were shared, as the fragment carries it
         * @return the file name
         */
        private String fileNameOfShareAt(final String sharedTime) {
            final RegisterFragment original = fragment();
            final RegisterFragment shared = new RegisterFragment(
                    original.courtCentreId(), sharedTime, original.hearingDate(),
                    original.hearingId(), original.registerDefendants(),
                    original.courtCentreOUCode());

            return AggregationMapper.map(shared, subscriptions(), hearing(), anomalies::add)
                    .fileName();
        }

        @Test
        @DisplayName("the court centre code is the hearing's, not the fragment's OU code")
        void the_code_is_the_hearings() {
            // The legacy reads `hearingObj.courtCentre.code`; the fragment carries its own
            // `courtCentreOUCode`, gathered from the same place. They agree in every fixture, so
            // nothing has ever said which one the name is built from. Pinned as the legacy has it.
            final ObjectNode hearing = hearing();
            ((ObjectNode) hearing.get("courtCentre")).put("code", "C99XY00");

            assertThat(AggregationMapper.map(fragment(), subscriptions(), hearing, anomalies::add)
                    .fileName()).contains("C99XY00");
        }
    }

    @Nested
    @DisplayName("only the youths are on it")
    class YouthOnly {

        @Test
        @DisplayName("an adult on the same fragment is not on the register")
        void an_adult_on_the_same_fragment_is_not_on_it() {
            final CourtRegisterDocument document = AggregationMapper.map(
                    adultAndYouth(),
                    subscriptions(),
                    hearingOf("hearing-with-adult-first-youth-second.json"),
                    anomalies::add);

            assertThat(document.defendants())
                    .extracting(CourtRegisterDefendant::masterDefendantId)
                    .containsExactly(YOUTH_ID);
        }

        @Test
        @DisplayName("a fragment of adults alone produces no register at all — O2")
        void a_fragment_of_adults_alone_produces_nothing() {
            assertThat(AggregationMapper.map(
                    adultsOnly(),
                    subscriptions(),
                    hearingOf("hearing-with-adult-first-youth-second.json"),
                    anomalies::add)).isNull();
        }
    }

    @Nested
    @DisplayName("the two ways a register comes to nothing (C33)")
    class NoOpOutcomes {

        @Test
        @DisplayName("no subscriptions matched the court centre — O1")
        void no_subscriptions_matched() {
            assertThat(AggregationMapper.map(fragment(), List.of(), hearing(), anomalies::add))
                    .isNull();
        }

        @Test
        @DisplayName("no subscriptions were offered at all")
        void no_subscriptions_were_offered() {
            assertThat(AggregationMapper.map(fragment(), null, hearing(), anomalies::add)).isNull();
        }

        @Test
        @DisplayName("every matched subscription was dropped before it became a recipient")
        void every_matched_subscription_was_dropped() {
            // A register whose only subscriber asks for post reaches nobody, and there is nothing to
            // send. Counted as a drop, and named as the same outcome as no subscription at all.
            assertThat(AggregationMapper.map(
                    fragment(), List.of(letterSubscription()), hearing(), anomalies::add)).isNull();
            assertThat(anomalies).contains(TransformationAnomaly.LETTER_DELIVERY_DROPPED);
        }

        @Test
        @DisplayName("the subscription outcome says so, in the bounded code it is counted under")
        void the_subscription_outcome_says_so() {
            try (CapturedLog log = CapturedLog.capturing(AggregationMapper.class)) {
                AggregationMapper.map(fragment(), List.of(), hearing(), anomalies::add);

                assertThat(messages(log)).anySatisfy(message ->
                        assertThat(message).contains(CompletionReason.NO_SUBSCRIPTIONS.value()));
            }
        }

        @Test
        @DisplayName("and the youth outcome says which of the two it was, not the same thing twice")
        void the_youth_outcome_says_which_it_was() {
            // The whole of C33 in one assertion: today both return a bare `null` into a handler that
            // reports success, so the flow's two commonest results are one undifferentiated number.
            try (CapturedLog log = CapturedLog.capturing(AggregationMapper.class)) {
                AggregationMapper.map(
                        adultsOnly(),
                        subscriptions(),
                        hearingOf("hearing-with-adult-first-youth-second.json"),
                        anomalies::add);

                assertThat(messages(log))
                        .anySatisfy(message -> assertThat(message)
                                .contains(CompletionReason.NO_YOUTH_DEFENDANTS.value()))
                        .noneSatisfy(message -> assertThat(message)
                                .contains(CompletionReason.NO_SUBSCRIPTIONS.value()));
            }
        }

        @Test
        @DisplayName("the subscription question is asked before the youth question")
        void subscriptions_are_asked_about_first() {
            // A fragment with neither is one outcome, not two, and it is the one the legacy's own
            // order gives: `index.js:17` before `:22`.
            try (CapturedLog log = CapturedLog.capturing(AggregationMapper.class)) {
                AggregationMapper.map(adultsOnly(), List.of(),
                        hearingOf("hearing-with-adult-first-youth-second.json"), anomalies::add);

                assertThat(messages(log))
                        .anySatisfy(message -> assertThat(message)
                                .contains(CompletionReason.NO_SUBSCRIPTIONS.value()))
                        .noneSatisfy(message -> assertThat(message)
                                .contains(CompletionReason.NO_YOUTH_DEFENDANTS.value()));
            }
        }

        @Test
        @DisplayName("and says it with the hearing it is about and no child's name")
        void it_says_it_without_a_childs_name() {
            try (CapturedLog log = CapturedLog.capturing(AggregationMapper.class)) {
                AggregationMapper.map(fragment(), List.of(), hearing(), anomalies::add);

                assertThat(messages(log)).allSatisfy(message ->
                        assertThat(message).doesNotContain("Fred").doesNotContain("Smith"));
            }
        }
    }

    @Nested
    @DisplayName("what the mappers beneath it counted")
    class AnomaliesFromBelow {

        @Test
        @DisplayName("a defendant nothing on the hearing resolves is counted through the assembly")
        void an_unresolvable_defendant_is_counted_through() {
            // The recorder is handed down: a skip four mappers deep still reaches
            // `processed_output.anomaly_summary`.
            AggregationMapper.map(
                    withDefendants(youthNamed("no-such-master-defendant"), youthNamed(YOUTH_ID)),
                    subscriptions(),
                    hearing(),
                    anomalies::add);

            assertThat(anomalies)
                    .containsExactly(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);
        }

        @Test
        @DisplayName("and the register is still assembled for the children who resolved")
        void the_register_is_still_assembled() {
            final CourtRegisterDocument document = AggregationMapper.map(
                    withDefendants(youthNamed("no-such-master-defendant"), youthNamed(YOUTH_ID)),
                    subscriptions(),
                    hearing(),
                    anomalies::add);

            assertThat(document.defendants()).hasSize(1);
        }

        @Test
        @DisplayName("an assembly with nothing to report counts nothing")
        void an_assembly_with_nothing_to_report_counts_nothing() {
            document();

            assertThat(anomalies).isEmpty();
        }
    }

    /**
     * The register the complete fragment, its matched subscriptions and the surviving-youth hearing
     * assemble into.
     *
     * @return the assembled document
     */
    private CourtRegisterDocument document() {
        return AggregationMapper.map(fragment(), subscriptions(), hearing(), anomalies::add);
    }

    /**
     * The rebuilt complete fragment: a real court centre id and OU code, the three dates, and one
     * youth defendant with a gathered defendant-level result.
     *
     * @return the fragment
     */
    private RegisterFragment fragment() {
        return mapper.treeToValue(completeFragment(), RegisterFragment.class);
    }

    /**
     * The same fragment under a different hearing id.
     *
     * @param hearingId the hearing id
     * @return the fragment
     */
    private RegisterFragment withHearingId(final String hearingId) {
        final RegisterFragment fragment = fragment();
        return new RegisterFragment(
                fragment.courtCentreId(), fragment.registerDate(), fragment.hearingDate(),
                hearingId, fragment.registerDefendants(), fragment.courtCentreOUCode());
    }

    /**
     * The same fragment carrying exactly the defendants given.
     *
     * @param registerDefendants the gathered defendants, in fragment order
     * @return the fragment
     */
    private RegisterFragment withDefendants(final RegisterDefendant... registerDefendants) {
        final RegisterFragment fragment = fragment();
        return new RegisterFragment(
                fragment.courtCentreId(), fragment.registerDate(), fragment.hearingDate(),
                fragment.hearingId(), List.of(registerDefendants), fragment.courtCentreOUCode());
    }

    /** A fragment carrying the adult ahead of the youth, as the adult-first hearing gathers. */
    private RegisterFragment adultAndYouth() {
        return withDefendants(adult(), youthNamed(YOUTH_ID));
    }

    /** A fragment carrying the adult alone. */
    private RegisterFragment adultsOnly() {
        return withDefendants(adult());
    }

    /**
     * The two matched subscriptions the complete fragment carries.
     *
     * @return the subscriptions, in fragment order
     */
    private List<JsonNode> subscriptions() {
        return List.copyOf(completeFragment().get("matchedSubscriptions").valueStream().toList());
    }

    /** A matched subscription asking for the post, which this channel cannot serve. */
    private ObjectNode letterSubscription() {
        final ObjectNode subscription = mapper.createObjectNode();
        subscription.put("firstClassLetterDelivery", true);
        subscription.put("emailDelivery", false);
        subscription.put("forDistribution", true);
        subscription.set("recipient",
                mapper.createObjectNode().put("organisationName", "Lavender Hill Youth Panel"));
        return subscription;
    }

    private JsonNode completeFragment() {
        return LegacyFixtures.readRebuilt(
                "outboundcourtregister/court-register-fragment-complete.json");
    }

    /** The authored hearing whose child survives every filter, as an editable copy. */
    private ObjectNode hearing() {
        return hearingOf("hearing-with-surviving-youth-defendant.json");
    }

    /**
     * One base payload's hearing, deep-copied so a case can change it without changing the fixture
     * for the next one.
     *
     * @param fixture the file name below {@code fixtures/base/}
     * @return the hearing
     */
    private ObjectNode hearingOf(final String fixture) {
        return (ObjectNode) LegacyFixtures.readBase(fixture).get("hearing").deepCopy();
    }

    /**
     * A gathered youth defendant under the given identity.
     *
     * @param masterDefendantId the identity to gather by
     * @return the gathered defendant
     */
    private RegisterDefendant youthNamed(final String masterDefendantId) {
        return new RegisterDefendant(
                List.of(masterDefendantId), List.of(),
                List.of("c10e3b71-6a6d-45ef-9b62-34df4d54971a"), List.of(),
                masterDefendantId, true, "2020-01-20", null);
    }

    /** The adult who is on the hearing and is not what the register is for. */
    private RegisterDefendant adult() {
        return new RegisterDefendant(
                List.of(ADULT_ID), List.of(),
                List.of("c10e3b71-6a6d-45ef-9b62-34df4d54971a"), List.of(),
                ADULT_ID, false, "2020-01-20", null);
    }

    /**
     * Every line the capture holds, at any level.
     *
     * @param log the capture
     * @return the messages
     */
    private static List<String> messages(final CapturedLog log) {
        return log.events().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
