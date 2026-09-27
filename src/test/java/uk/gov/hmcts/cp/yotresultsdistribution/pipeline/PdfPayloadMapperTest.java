package uk.gov.hmcts.cp.yotresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;

/**
 * The payload systemdocgenerator renders, held to what progression's generator produced.
 *
 * <p>The oracle is not this suite's reading of the generator, it is the output of running it. T004
 * compiled {@code CourtRegisterPdfPayloadGenerator} unmodified at progression
 * {@code 79edf7cf3d}, fed it every recorded 001 document and every batch those documents group into,
 * and wrote what came back under {@code src/test/resources/goldens/progression/pdf-payload/} with
 * {@code PROVENANCE.md} and {@code INDEX.json} beside them. Every case below reads its inputs
 * through the paths {@code INDEX.json} itself names, so a re-record moves this suite with it rather
 * than around it.
 *
 * <p><strong>Byte-identical, and byte is meant literally.</strong> A golden is not a tree the
 * recorder happened to serialise: it is that tree written through the recorder's own canonical
 * writer, keys sorted, two-space indent, one trailing newline, the seven named escapes plus a
 * numeric one below a space, and nothing else escaped at all. {@link #canonical(JsonNode)} is that
 * writer again over a Jackson tree, so the comparison is between two strings and not between two
 * trees a comparator was allowed to reconcile. Key order is the one thing the sorted writer
 * forgives, because the payload is an object the template reads by name and {@code javax.json} and
 * Jackson do not agree on insertion order; every other difference, a space, an escape, a number
 * spelt another way, fails.
 *
 * <p><strong>Both shapes are asserted</strong> (FR-007): the 161 single-document goldens, which are
 * one recorded register each, and the 7 whole-batch goldens, which are what the nightly job actually
 * hands the mapper. The batch of 127 is where the generator's {@code stream().findAny()}
 * ({@code :47}) shows: four of its members carry a different register time or no LJA name at all,
 * and the payload's header comes from the first of them regardless.
 *
 * <p><strong>The clock is pinned to the day the goldens were recorded.</strong> {@code age}
 * ({@code :323-329}) is {@code Period.between(dateOfBirth, LocalDate.now()).getYears()} and is the
 * only field in the payload that reads a clock at all. {@code INDEX.json} records the date the
 * corpus was recorded on and this suite builds the mapper's clock from it, so a suite that let the
 * mapper read the wall clock would pass today and fail on the first birthday in the corpus.
 *
 * <p><strong>Fifty-two recorded documents have no golden, and that is not a gap.</strong> Every one
 * is a {@code SCHEMA_INVALID} case the generator threw on, 51 of them at
 * {@code buildParentGuardianNameAndAddress:184} and one at {@code getAge:325}. They are recorded in
 * {@code INDEX.json} as refusals with no output file, so there is nothing for this suite to be equal
 * to, and no such document can reach the mapper in service: the contract validator refuses it at the
 * write, so it is never recorded, never batched and never rendered.
 *
 * @see <a href="file:../../../../../../../../specs/002-consolidate-progression-leg/research.md">research.md</a> §6
 */
@DisplayName("PdfPayloadMapper")
class PdfPayloadMapperTest {

    /** Where T004 wrote the recorded payloads, on the test classpath. */
    private static final String GOLDENS = "/goldens/progression/";

    /** Where the recorded 001 corpus the goldens were made from lives. */
    private static final String RECORDED = "/differential/recorded/";

    /** The prefix the index's repo-relative input paths carry and the classpath does not. */
    private static final String SOURCE_ROOT = "src/test/resources";

    /** The array progression's generator reads the batch's documents out of. */
    private static final String REQUESTS = "courtRegisterDocumentRequests";

    /** What a field the mapper did not write is reported as, so absence is asserted positively. */
    private static final String ABSENT = "<absent>";

    /** Progression's fallback for a value it has nothing to put in. */
    private static final String DASH = "-";

    /** The sentinel C24 names, which the register's producer puts in and the generator takes out. */
    private static final String SENTINEL = "####";

    /** The zone the corpus was recorded in, which is the only zone this service runs in. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** The mapper the service reads payloads and stored documents with. */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /** T004's own record of what it recorded: every golden, its inputs, and the counts. */
    private static final JsonNode INDEX = read(GOLDENS + "INDEX.json");

    /** The inputs behind every golden, by identifier, read out of the index once. */
    private static final Map<String, List<String>> SOURCES = recordedSources();

    /** The first character the canonical writer does not spell out as a numeric escape. */
    private static final char FIRST_PRINTABLE = 0x20;

    /** The recorded register whose two cases exercise most of the generator's branches. */
    private static final String BASE = "base__surviving-youth-defendant";

    /** The 127-document batch, whose members disagree about the header the payload gets. */
    private static final String BIG_BATCH = "batch__853b1ff8-fc2a-44d1-a621-0cd16419f54a__2020-06-01";

    private final PdfPayloadMapper mapper = new PdfPayloadMapper(recordingClock());

    @Nested
    @DisplayName("every golden T004 recorded")
    class EveryRecordedGolden {

        @ParameterizedTest(name = "{0}")
        @MethodSource("uk.gov.hmcts.cp.yotresultsdistribution.pipeline.PdfPayloadMapperTest#documentGoldens")
        @DisplayName("is reproduced byte for byte from the document it was recorded from")
        void every_recorded_document_is_mapped_byte_for_byte(final String goldenId) {
            assertThat(canonicalOf(goldenId)).isEqualTo(goldenText(goldenId));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("uk.gov.hmcts.cp.yotresultsdistribution.pipeline.PdfPayloadMapperTest#batchGoldens")
        @DisplayName("is reproduced byte for byte from the batch it was recorded from")
        void every_recorded_batch_is_mapped_byte_for_byte(final String goldenId) {
            assertThat(canonicalOf(goldenId)).isEqualTo(goldenText(goldenId));
        }

        @Test
        @DisplayName("is one of the goldens above, and the index says how many that is")
        void every_golden_the_index_names_is_asserted() {
            // A re-record that writes a golden nobody reads would leave this suite passing while
            // covering less, which is the one failure mode a parameterised suite cannot show.
            final JsonNode counts = INDEX.get("counts");

            assertThat(documentGoldens().count())
                    .isEqualTo(counts.get("pdfPayloadDocumentGoldens").intValue());
            assertThat(batchGoldens().count())
                    .isEqualTo(counts.get("pdfPayloadBatchGoldens").intValue());
        }
    }

    /**
     * The header of a batch whose members do not agree, which no recorded golden exercises.
     *
     * <p>progression's generator reads the header fields from {@code stream().findAny()} and this
     * port reproduces that, deliberately and by name in the service's design rules on behaviours
     * kept. The
     * 127-member golden was thought to pin it and does not: all six base fixtures behind it carry
     * the same court centre, the same LJA and the same address, so whichever member is picked the
     * header is identical and the recording says nothing about which one was.
     *
     * <p>So the input here is <strong>authored, not recorded</strong> - the same reason
     * {@code defendant-type/synthetic/} exists. It is a characterisation: the behaviour is what it
     * already was, and this states it so that a batch whose members disagree has an answer written
     * down. Two things follow from having it. The behaviour becomes visible if anyone later argues
     * the header should be the union, or the earliest hearing's, rather than arbitrary; and a
     * change from a sequential stream to anything unordered would fail here rather than silently
     * printing a different court house on a night nobody was watching.
     *
     * <p><strong>[A]</strong> - green on introduction, with no red run available, because it
     * describes behaviour the mapper already had.
     */
    @Nested
    @DisplayName("a batch whose members disagree")
    class TheHeaderOfAMixedBatch {

        @Test
        @DisplayName("[A] takes its header from the first member, and says so rather than merging")
        void the_header_comes_from_the_first_member_and_not_from_any_other() {
            final JsonNode mixed = aBatchOf(documentFor(BASE), aDocumentHeldAt("Leeds Youth Court",
                    "West Yorkshire", "1 The Headrow, Leeds, LS1 6DD"));

            final JsonNode payload = mapper.mapPayload(mixed);

            assertThat(payload.get("courtHouse").stringValue())
                    .as("the first member's court house, not the second's and not both: a batch "
                            + "is one court centre day, so members that disagree are a reference "
                            + "data change between two recordings rather than two venues")
                    .isEqualTo(documentFor(BASE).at("/hearingVenue/courtHouse").stringValue());
            assertThat(payload.get("courtHouse").stringValue())
                    .as("and emphatically not the second member's, which is what a union or a "
                            + "last-wins read would have produced")
                    .isNotEqualTo("Leeds Youth Court");
        }

        /**
         * The recorded document with its venue replaced, so the batch has two members that disagree.
         *
         * @param courtHouse the court house this member names
         * @param lja        the local justice area this member names
         * @param address    the first address line this member names
         * @return the document, in the shape the generator reads
         */
        private JsonNode aDocumentHeldAt(final String courtHouse, final String lja,
                final String address) {

            final ObjectNode document = (ObjectNode) documentFor(BASE).deepCopy();
            final ObjectNode venue = (ObjectNode) document.get("hearingVenue");
            venue.put("courtHouse", courtHouse);
            venue.put("ljaName", lja);
            ((ObjectNode) venue.get("address")).put("address1", address);
            return document;
        }

        /**
         * The documents as one batch, in the envelope shape the generator was written against.
         *
         * @param documents the batch's members, in the order they were assembled
         * @return the envelope
         */
        private JsonNode aBatchOf(final JsonNode... documents) {
            final ArrayNode requests = MAPPER.createArrayNode();
            for (final JsonNode document : documents) {
                requests.add(document);
            }
            final ObjectNode envelope = MAPPER.createObjectNode();
            envelope.set(REQUESTS, requests);
            return envelope;
        }
    }

    @Nested
    @DisplayName("C24's sentinel")
    class TheSentinel {

        @Test
        @DisplayName("becomes a newline, and only where the producer put one")
        void sentinel_is_substituted_exactly_as_progression_did() {
            // doc/DEFECT-FIXES.md C24: the legacy joins wording to legislation with a literal
            // "####" and progression substitutes it at :336. The port keeps progression's half of
            // that convention bug for bug, because the payload is the OEE_Layout5 contract and a
            // template reading a real newline is what the recorded output says it gets.
            final String recorded = recordedWording();

            assertThat(recorded)
                    .as("the recorded document is the one that carries the sentinel")
                    .contains(SENTINEL)
                    .doesNotContain("\n");
            assertThat(valueAt(BASE, "/cases/0/offences/0/wording"))
                    .isEqualTo(recorded.replace(SENTINEL, "\n"));
        }

        /**
         * The wording as the recorded document carries it, sentinel and all.
         *
         * @return the offence wording that was fed to the generator
         */
        private String recordedWording() {
            return documentFor(BASE)
                    .at("/defendants/0/prosecutionCasesOrApplications/0/offences/0/wording")
                    .stringValue();
        }
    }

    @Nested
    @DisplayName("the DASH fallbacks")
    class TheDashFallbacks {

        @Test
        @DisplayName("give a defendant with no parent guardian a name and one address line, both dashes")
        void an_absent_parent_guardian_is_a_name_and_one_address_line() {
            // :186-189, the else branch, which writes two keys where the populated branch writes
            // seven. Asserting the key set rather than the two values is what makes the shape of
            // the fallback the thing under test: an implementation that filled in the other five
            // address lines with dashes would satisfy both values and still not be progression.
            final String goldenId = "mut__surviving-youth-defendant__drop-optional-field"
                    + "__prosecutioncases-defendants-associatedpersons";

            assertThat(keysAt(goldenId, "/cases/0", "parentGuardian"))
                    .isEqualTo("parentGuardianAddress1, parentGuardianName");
            assertThat(valueAt(goldenId, "/cases/0/parentGuardianName")).isEqualTo(DASH);
            assertThat(valueAt(goldenId, "/cases/0/parentGuardianAddress1")).isEqualTo(DASH);
        }

        @Test
        @DisplayName("stand in for a court house the venue does not name, and drop an absent LJA")
        void an_unnamed_court_house_is_a_dash_and_an_absent_lja_is_nothing_at_all() {
            // :51-54, where the two header fields are not treated alike: ljaName is written only
            // when the venue has one, and courtHouse is always written, dashed when it has none.
            final String unnamed = "mut__surviving-youth-defendant__drop-optional-field"
                    + "__courtcentre-name";
            final String noLja = "mut__surviving-youth-defendant__drop-optional-field"
                    + "__courtcentre-lja";

            assertThat(valueAt(unnamed, "/courtHouse")).isEqualTo(DASH);
            assertThat(valueAt(noLja, "/ljaName")).isEqualTo(ABSENT);
            assertThat(valueAt(noLja, "/courtHouse"))
                    .isEqualTo("Lavender Hill Magistrates' Court");
        }

        @Test
        @DisplayName("fill in the officer in the case, who is a dash for every register there is")
        void the_officer_in_the_case_is_always_a_dash() {
            // :73. Not a fallback at all: a constant the template expects and the register never
            // carries, and the one field whose value cannot be traced to any input.
            assertThat(valueAt(BASE, "/cases/0/officerInCase")).isEqualTo(DASH);
            assertThat(valueAt(BASE, "/cases/1/officerInCase")).isEqualTo(DASH);
        }
    }

    @Nested
    @DisplayName("the date formats")
    class TheDateFormats {

        @Test
        @DisplayName("read the document's ISO dates and write the register's slashed ones")
        void dates_are_read_iso_and_written_slashed() {
            // Two formatters and two readers: formatZonedDate (:361-363) takes the register and
            // hearing timestamps, which are zoned, and formatDate (:353-359) takes the plain dates
            // a defendant and an offence carry. Both write dd/MM/yyyy.
            assertThat(valueAt(BASE, "/registerDate")).isEqualTo("01/06/2020");
            assertThat(valueAt(BASE, "/cases/0/dateOfHearing")).isEqualTo("20/01/2020");
            assertThat(valueAt(BASE, "/cases/0/dateOfBirth")).isEqualTo("17/04/2008");
            assertThat(valueAt(BASE, "/cases/0/offences/0/convictionDate")).isEqualTo("14/11/2019");
        }

        @Test
        @DisplayName("leave a date there was none of as the dash it arrived as")
        void a_date_that_is_a_dash_is_left_a_dash() {
            // :354, the guard that keeps LocalDate.parse away from the DASH the reader defaulted
            // to. The whole fallback is two hops: getString(name, DASH) then formatDate(DASH).
            final String noDateOfBirth = "mut__surviving-youth-defendant__drop-optional-field"
                    + "__prosecutioncases-defendants-persondefendant-persondetails-dateofbirth";

            assertThat(valueAt(noDateOfBirth, "/cases/0/dateOfBirth")).isEqualTo(DASH);
        }
    }

    @Nested
    @DisplayName("the age")
    class TheAge {

        @Test
        @DisplayName("is whole years between the date of birth and the day the mapping runs")
        void age_is_counted_in_whole_years_against_the_pinned_clock() {
            // The recorded age is a reading of 2026-09-05, which is why the mapper takes a clock:
            // a defendant born on 2008-04-17 is 18 on the day the corpus was recorded and 19 on
            // the day after their next birthday, and only one of those is in the golden.
            assertThat(valueAt(BASE, "/cases/0/age")).isEqualTo("18");
        }

        @Test
        @DisplayName("is a dash where the defendant has no date of birth to count from")
        void an_absent_date_of_birth_has_no_age() {
            // :326-327. The generator asks containsKey and answers DASH rather than zero, which is
            // the difference between "we do not know" and "born today".
            final String noDateOfBirth = "mut__surviving-youth-defendant__drop-optional-field"
                    + "__prosecutioncases-defendants-persondefendant-persondetails-dateofbirth";

            assertThat(valueAt(noDateOfBirth, "/cases/0/age")).isEqualTo(DASH);
        }
    }

    @Nested
    @DisplayName("the aliases")
    class TheAliases {

        @Test
        @DisplayName("are each name joined by spaces and the names joined by commas")
        void aliases_join_their_parts_with_spaces_and_each_other_with_commas() {
            // :293-306. Two separators doing two jobs, and empty parts dropped rather than left as
            // the double space that joining them blindly would produce.
            final String duplicated = "mut__surviving-youth-defendant__duplicate-array-entry"
                    + "__prosecutioncases-defendants-aliases";

            assertThat(valueAt(BASE, "/cases/0/aliases")).isEqualTo("Mr John Duncan Smith");
            assertThat(valueAt(duplicated, "/cases/0/aliases"))
                    .isEqualTo("Mr John Duncan Smith, Mr John Duncan Smith");
        }

        @Test
        @DisplayName("are a dash where the defendant is known by no other name")
        void a_defendant_with_no_aliases_is_a_dash() {
            final String noAliases = "mut__surviving-youth-defendant__drop-optional-field"
                    + "__prosecutioncases-defendants-aliases";

            assertThat(valueAt(noAliases, "/cases/0/aliases")).isEqualTo(DASH);
        }
    }

    @Nested
    @DisplayName("the counsel")
    class TheCounsel {

        @Test
        @DisplayName("are named and statused in the order the register carries them")
        void counsel_names_and_statuses_are_joined_in_the_order_they_arrived() {
            // :192-221. Names and statuses are collected in two passes over the same array, so the
            // nth status belongs to the nth name and a counsel with one and not the other shifts
            // the pairing rather than breaking it. Prosecution counsel is per case-or-application
            // and defence counsel per defendant, which is why the two cases below differ in one
            // and not the other.
            final String twoCounsels = "mut__surviving-youth-defendant__duplicate-array-entry"
                    + "__defencecounsels";

            assertThat(valueAt(BASE, "/cases/0/defenceCounselName"))
                    .isEqualTo("James Benjamin Simpson");
            assertThat(valueAt(BASE, "/cases/0/defenceCounselStatus")).isEqualTo("Junior QC");
            assertThat(valueAt(twoCounsels, "/cases/0/defenceCounselName"))
                    .isEqualTo("James Benjamin Simpson, James Benjamin Simpson");
            assertThat(valueAt(twoCounsels, "/cases/0/defenceCounselStatus"))
                    .isEqualTo("Junior QC, Junior QC");
            assertThat(valueAt(BASE, "/cases/1/prosecutionCounselName"))
                    .isEqualTo("David Kieran Walsh");
            assertThat(valueAt(BASE, "/cases/1/prosecutionCounselStatus")).isEqualTo("Leading QC");
        }

        @Test
        @DisplayName("are dashes where nobody appeared for that side")
        void an_absent_counsel_is_a_dash_on_both_fields() {
            // :204-206 and :217-219. The prosecution case in the base register has no prosecution
            // counsel and the all-youth register's first defendant has no defence counsel, so one
            // golden each says the else branch writes both fields rather than leaving them out.
            final String noDefence = "mut__adult-first-youth-second__youth-presence__all-youth";

            assertThat(valueAt(BASE, "/cases/0/prosecutionCounselName")).isEqualTo(DASH);
            assertThat(valueAt(BASE, "/cases/0/prosecutionCounselStatus")).isEqualTo(DASH);
            assertThat(valueAt(noDefence, "/cases/0/defenceCounselName")).isEqualTo(DASH);
            assertThat(valueAt(noDefence, "/cases/0/defenceCounselStatus")).isEqualTo(DASH);
        }
    }

    @Nested
    @DisplayName("the application")
    class TheApplicationValidity {

        @Test
        @DisplayName("is written whole when any one of its six fields is set")
        void one_populated_field_is_enough_for_an_application() {
            // isApplicationValid (:345-351) is anyMatch, not allMatch, and buildApplication writes
            // all six fields regardless, so the recorded register's application carries its type
            // and five dashes. A validity rule that asked for more would drop the only field the
            // register has.
            assertThat(valueAt(BASE, "/cases/1/applications/0/type")).isEqualTo("sample type");
            assertThat(keysAt(BASE, "/cases/1/applications/0", ""))
                    .isEqualTo("decision, decisionDate, response, responseDate, result, type");
            assertThat(valueAt(BASE, "/cases/1/applications/0/decision")).isEqualTo(DASH);
            assertThat(valueAt(BASE, "/cases/1/applications/0/decisionDate")).isEqualTo(DASH);
            assertThat(valueAt(BASE, "/cases/1/applications/0/response")).isEqualTo(DASH);
            assertThat(valueAt(BASE, "/cases/1/applications/0/responseDate")).isEqualTo(DASH);
            assertThat(valueAt(BASE, "/cases/1/applications/0/result")).isEqualTo(DASH);
        }

        @Test
        @DisplayName("is left out altogether when none of them is")
        void a_prosecution_case_carries_no_application_at_all() {
            // :144-147: an empty applications array is not written, so the prosecution case in the
            // same register has no such key. The reference is what says the case is the right one.
            assertThat(valueAt(BASE, "/cases/0/caseReference")).isEqualTo("TFL4359536");
            assertThat(valueAt(BASE, "/cases/0/applications")).isEqualTo(ABSENT);
        }
    }

    @Nested
    @DisplayName("a whole batch")
    class AWholeBatch {

        @Test
        @DisplayName("takes its header from the first document and its cases from all of them")
        void a_batch_header_comes_from_the_first_document_alone() {
            // :47's stream().findAny(), which is the first element in practice. Four of this
            // batch's 127 members disagree with the first one - three carry no LJA name at all and
            // one a register time ten hours earlier - and none of that reaches the payload, whose
            // header is the first member's and whose 263 cases are every member's.
            assertThat(valueAt(BIG_BATCH, "/registerDate")).isEqualTo("01/06/2020");
            assertThat(valueAt(BIG_BATCH, "/ljaName"))
                    .isEqualTo("South West London Magistrates' Court");
            assertThat(valueAt(BIG_BATCH, "/courtHouseAddress"))
                    .isEqualTo("176A Lavender Hill, London, SW11 1JU");
            assertThat(caseCountOf(BIG_BATCH)).isEqualTo("263");
        }
    }

    /**
     * Every document golden, by the identifier {@code INDEX.json} gives it.
     *
     * <p>The 52 refusals carry no golden and are not here: there is no output to be equal to.
     *
     * @return the identifiers
     */
    static Stream<String> documentGoldens() {
        return goldenIds("pdfPayloadDocuments");
    }

    /**
     * Every batch golden, by the identifier {@code INDEX.json} gives it.
     *
     * @return the identifiers
     */
    static Stream<String> batchGoldens() {
        return goldenIds("pdfPayloadBatches");
    }

    /**
     * The identifiers of the goldens one index section actually wrote a file for.
     *
     * @param section the index section to read
     * @return the identifiers
     */
    private static Stream<String> goldenIds(final String section) {
        return INDEX.get(section).valueStream()
                .filter(entry -> !entry.get("golden").isNull())
                .map(entry -> entry.get("goldenId").stringValue());
    }

    /**
     * The clock the goldens were recorded against.
     *
     * <p>Midday rather than midnight, so that a mapper reading the date through some other zone
     * still reads the day the ages were counted on rather than the one either side of it.
     *
     * @return a clock fixed to the recording date in Europe/London
     */
    private static Clock recordingClock() {
        final LocalDate recorded = LocalDate.parse(INDEX.get("ageClockDate").stringValue());
        return Clock.fixed(recorded.atTime(12, 0).atZone(LONDON).toInstant(), LONDON);
    }

    /**
     * One field of one mapped payload, or the refusal that came instead of a payload.
     *
     * @param goldenId the golden naming the input to map
     * @param pointer  the JSON pointer to the field
     * @return the field's text, {@link #ABSENT} where the mapping wrote none, or the refusal
     */
    private String valueAt(final String goldenId, final String pointer) {
        return answerFor(goldenId, payload -> text(payload.at(pointer)));
    }

    /**
     * The field names one mapped object carries under a prefix, in name order, or the refusal.
     *
     * @param goldenId the golden naming the input to map
     * @param pointer  the JSON pointer to the object
     * @param prefix   the prefix the names must start with, empty for all of them
     * @return the names joined by commas, or the refusal
     */
    private String keysAt(final String goldenId, final String pointer, final String prefix) {
        return answerFor(goldenId, payload -> payload.at(pointer).propertyNames().stream()
                .filter(name -> name.startsWith(prefix))
                .sorted()
                .collect(Collectors.joining(", ")));
    }

    /**
     * How many cases one mapped payload carries, as text so a refusal can take its place.
     *
     * @param goldenId the golden naming the input to map
     * @return the count, or the refusal
     */
    private String caseCountOf(final String goldenId) {
        return answerFor(goldenId, payload -> String.valueOf(payload.get("cases").size()));
    }

    /**
     * One mapped payload, written the way the recorder wrote the goldens, or the refusal.
     *
     * @param goldenId the golden naming the input to map
     * @return the canonical text of the payload, or the refusal
     */
    private String canonicalOf(final String goldenId) {
        return answerFor(goldenId, PdfPayloadMapperTest::canonical);
    }

    /**
     * What the mapper says about one recorded input, with a refusal carried into the value rather
     * than out of the test.
     *
     * <p>The red-run convention asks a test task to run against the seam already on the branch and
     * to record a failing assertion, and the seam T042 replaces refuses with an
     * {@link UnsupportedOperationException}. Carrying the refusal into the answer makes that red run
     * a failing comparison naming what was thrown, and it keeps saying something afterwards: a port
     * that throws where the generator produced a payload fails the same assertion, with the
     * exception's type and message in the failure rather than swallowed.
     *
     * @param goldenId the golden naming the input to map
     * @param question what to ask of the payload
     * @return the answer, or the refusal that came instead
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    // Which type a refusal arrives as is the mapper's business and not this suite's: what the
    // assertion needs is the news that a payload did not come, whichever type carried it.
    private String answerFor(final String goldenId, final Function<JsonNode, String> question) {
        final JsonNode input = inputFor(goldenId);
        String answer;
        try {
            answer = question.apply(mapper.mapPayload(input));
        } catch (RuntimeException refused) {
            answer = refused.getClass().getSimpleName() + ": " + refused.getMessage();
        }
        return answer;
    }

    /**
     * The input one golden was recorded from, in the shape the generator reads.
     *
     * <p>A document golden was recorded from a single register wrapped in a one-element array; a
     * batch golden from its members' registers in the order {@code INDEX.json} lists them, which is
     * the order the recorder grouped them in.
     *
     * @param goldenId the golden's identifier
     * @return the {@code courtRegisterDocumentRequests} envelope
     */
    private static JsonNode inputFor(final String goldenId) {
        final ArrayNode requests = MAPPER.createArrayNode();
        for (final String source : sourcesOf(goldenId)) {
            requests.add(read(source));
        }
        final ObjectNode envelope = MAPPER.createObjectNode();
        envelope.set(REQUESTS, requests);
        return envelope;
    }

    /**
     * The single register a document golden was recorded from.
     *
     * @param goldenId the golden's identifier
     * @return the recorded register
     */
    private static JsonNode documentFor(final String goldenId) {
        return read(sourcesOf(goldenId).get(0));
    }

    /**
     * The classpath resources holding the registers one golden was recorded from.
     *
     * @param goldenId the golden's identifier
     * @return one resource for a document golden, one per member for a batch golden
     */
    private static List<String> sourcesOf(final String goldenId) {
        final List<String> sources = SOURCES.get(goldenId);
        if (sources == null) {
            throw new IllegalStateException("the index names no golden " + goldenId);
        }
        return sources;
    }

    /**
     * What each golden was recorded from, read out of the index once.
     *
     * <p>A document golden names its own recorded register; a batch golden names its members, whose
     * registers are the corpus's own {@code expected.json} files, in the order the recorder grouped
     * them.
     *
     * @return the inputs behind every golden, by identifier
     */
    private static Map<String, List<String>> recordedSources() {
        final Map<String, List<String>> sources = new LinkedHashMap<>();
        for (final JsonNode entry : INDEX.get("pdfPayloadDocuments")) {
            if (!entry.get("golden").isNull()) {
                sources.put(entry.get("goldenId").stringValue(),
                        List.of(classpath(entry.get("source").stringValue())));
            }
        }
        for (final JsonNode entry : INDEX.get("pdfPayloadBatches")) {
            if (!entry.get("golden").isNull()) {
                sources.put(entry.get("goldenId").stringValue(), entry.get("members").valueStream()
                        .map(member -> RECORDED + member.stringValue() + "/expected.json")
                        .toList());
            }
        }
        return Map.copyOf(sources);
    }

    /**
     * One golden exactly as it sits on disk, trailing newline included.
     *
     * @param goldenId the golden's identifier
     * @return the file's text
     */
    private static String goldenText(final String goldenId) {
        return contentsOf(GOLDENS + "pdf-payload/" + goldenId + ".json");
    }

    /**
     * The classpath resource behind one of the repo-relative paths the index names.
     *
     * @param recordedPath the path as the index records it
     * @return the resource path
     */
    private static String classpath(final String recordedPath) {
        if (!recordedPath.startsWith(SOURCE_ROOT)) {
            throw new IllegalStateException("the index names an input outside the test resources: "
                    + recordedPath);
        }
        return recordedPath.substring(SOURCE_ROOT.length());
    }

    /**
     * One field's text, where a field the mapping did not write is named rather than null.
     *
     * @param value the field, which may be missing
     * @return the text, or {@link #ABSENT}
     */
    private static String text(final JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull()
                ? ABSENT : value.stringValue();
    }

    /**
     * The canonical text of one payload, written the way the recorder wrote the goldens.
     *
     * <p>Sorted keys, two-space indent, one trailing newline. This is the recorder's own writer
     * (T004's {@code GoldenRecorder.write}) over a Jackson tree rather than a {@code javax.json}
     * one, which is why a golden can be compared as a string: neither side was written by a JSON
     * library's own formatter, so neither side's formatter is part of the comparison.
     *
     * @param value the payload
     * @return its canonical text
     */
    private static String canonical(final JsonNode value) {
        final StringBuilder out = new StringBuilder();
        write(value, out, "");
        out.append('\n');
        return out.toString();
    }

    /**
     * Writes one value canonically.
     *
     * @param value  the value
     * @param out    where to write it
     * @param indent the indent this value sits at
     */
    private static void write(final JsonNode value, final StringBuilder out, final String indent) {
        if (value.isObject()) {
            writeObject(value, out, indent);
        } else if (value.isArray()) {
            writeArray(value, out, indent);
        } else if (value.isString()) {
            quote(value.stringValue(), out);
        } else {
            out.append(value.toString());
        }
    }

    /**
     * Writes one object canonically, its keys in natural order.
     *
     * @param object the object
     * @param out    where to write it
     * @param indent the indent the object sits at
     */
    private static void writeObject(final JsonNode object, final StringBuilder out,
            final String indent) {
        if (object.isEmpty()) {
            out.append("{}");
            return;
        }
        final String inner = indent + "  ";
        final List<String> keys = object.propertyNames().stream().sorted().toList();
        out.append("{\n");
        for (int i = 0; i < keys.size(); i++) {
            out.append(inner);
            quote(keys.get(i), out);
            out.append(": ");
            write(object.get(keys.get(i)), out, inner);
            if (i < keys.size() - 1) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append(indent).append('}');
    }

    /**
     * Writes one array canonically, in the order it carries.
     *
     * @param array  the array
     * @param out    where to write it
     * @param indent the indent the array sits at
     */
    private static void writeArray(final JsonNode array, final StringBuilder out,
            final String indent) {
        if (array.isEmpty()) {
            out.append("[]");
            return;
        }
        final String inner = indent + "  ";
        out.append("[\n");
        for (int i = 0; i < array.size(); i++) {
            out.append(inner);
            write(array.get(i), out, inner);
            if (i < array.size() - 1) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append(indent).append(']');
    }

    /**
     * Writes one string canonically: the seven named escapes, a four-digit numeric escape for
     * anything below a space, and every other character as itself.
     *
     * @param value the string
     * @param out   where to write it
     */
    private static void quote(final String value, final StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char next = value.charAt(i);
            switch (next) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (next < FIRST_PRINTABLE) {
                        out.append(String.format("\\u%04x", (int) next));
                    } else {
                        out.append(next);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * Reads one JSON file from the test classpath through the service's own contract mapper.
     *
     * @param resource the absolute resource path
     * @return the parsed tree
     */
    private static JsonNode read(final String resource) {
        return MAPPER.readTree(contentsOf(resource));
    }

    /**
     * Reads one file from the test classpath exactly as it was written.
     *
     * @param resource the absolute resource path
     * @return the file's text
     */
    private static String contentsOf(final String resource) {
        try (InputStream stream = PdfPayloadMapperTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException cannotRead) {
            throw new IllegalStateException("could not read " + resource, cannotRead);
        }
    }
}
