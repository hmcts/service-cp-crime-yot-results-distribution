package uk.gov.hmcts.cp.yotresultsdistribution.batch;

import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.AssembledBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchAssembly;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtCentreDay;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RecordedFlagState;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterRecord;

/**
 * The night's recorded registers, grouped into the documents that will be rendered from them.
 *
 * <p>Assembly is one decision made twice over: which registers belong together, and whether the key
 * they belong to may be rendered again tonight. The first is the unit the whole downstream half is
 * built on - one batch is one PDF, one render request and one set of Youth Offending Teams told
 * once - so a grouping that is one key too wide sends a court centre a document full of another
 * court centre's children, and one key too narrow sends the same day twice.
 *
 * <p><strong>What is asserted here and what is asserted elsewhere.</strong> The store decides what
 * "active" means: RECORDED, unsuperseded, unbatched and recorded while the flag was ON, which is
 * the predicate {@code idx_output_active_unbatched} carries and {@code RegisterStoreIT} pins. This
 * class does not widen it. What it does do is keep the one exclusion that survives the read -
 * {@link RecordedFlagState} travels on the record, so a caller that assembled rows it read some
 * other way (the operations CLI's own sweep) still cannot batch a register the legacy has already
 * generated and sent (research §12, FR-015). Supersession has no such second chance: a record does
 * not carry it, so a superseded row is one the store never answered with, and that is where it is
 * pinned.
 *
 * <p><strong>The supplementary rule (design Q27).</strong> A hearing re-shared after its key's
 * batch has been <em>sent</em> cannot join that batch - the document is rendered and the recipients
 * have been told - so once every earlier batch for the key is terminal the rows become a batch of
 * their own, naming the batch they follow and counting up from its index. While any earlier batch is
 * still in flight they wait, because the partial unique index admits one PENDING / GENERATING /
 * GENERATED batch per key. A key left waiting is <em>reported</em> rather than dropped: a register
 * that silently produced no batch is the same silence the progression leg left behind when a
 * generation failed, one step earlier in the flow.
 *
 * <p><strong>A FAILED predecessor is not one of the batches a supplement follows.</strong> Q27
 * separates the four terminal states into two answers, and so does the V2 index comment: "a FAILED
 * batch may be re-assembled (new batch_id, rows re-stamped), and a NOTIFIED / PARTIALLY_NOTIFIED /
 * NOTIFIED_NOBODY one may be followed by a supplementary batch". Both endings free the key, and only
 * the second of them produced a document. So a day whose only batch failed is re-assembled as that
 * day's first document - index 0, following nothing, under the register's own file name - and a
 * failed supplement is re-assembled at the index it failed at rather than one past it. Calling the
 * re-assembly a supplement would name it {@code -supplementary-1} in the file-service metadata
 * systemdocgenerator reads, for a first document nobody ever received.
 *
 * <p>Nothing here reaches a recipient or a defendant. The values the assembler decides from are a
 * court centre, a date, a file name and a flag state, and the keys it reports as deferred are the
 * first two of those - bounded enough to count in a run report and to name in a log line
 * (constitution Principle VII).
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("assembling the night's registers")
class BatchAssemblerTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING = "T043 implements the assembler; this is its red run";

    /** What a refused seam is read as, so a missing answer fails as an assertion and not an NPE. */
    private static final BatchAssembly NOTHING = new BatchAssembly(List.of(), List.of());

    private static final UUID LEEDS = UUID.fromString("00000000-0000-4000-8000-00000000010e");
    private static final UUID SHEFFIELD = UUID.fromString("00000000-0000-4000-8000-000000000135");

    private static final LocalDate REGISTER_DAY = LocalDate.of(2026, 8, 20);
    private static final LocalDate THE_DAY_AFTER = REGISTER_DAY.plusDays(1);

    private static final CourtCentreDay LEEDS_DAY = new CourtCentreDay(LEEDS, REGISTER_DAY);
    private static final CourtCentreDay LEEDS_NEXT_DAY = new CourtCentreDay(LEEDS, THE_DAY_AFTER);
    private static final CourtCentreDay SHEFFIELD_DAY =
            new CourtCentreDay(SHEFFIELD, REGISTER_DAY);

    private static final String FIRST_FILE = "court-register_2026-08-20_B01LY_first.pdf";
    private static final String SECOND_FILE = "court-register_2026-08-20_B01LY_second.pdf";
    private static final String OTHER_CENTRE_FILE = "court-register_2026-08-20_B02SH_first.pdf";

    /**
     * The file name the Q27 decision is written against, so the rule is pinned on its own example.
     *
     * <p>data-model.md names {@code yotresultsdistribution_2026-08-20.json} and the supplement it becomes,
     * and a rule stated on one example and tested on another is a rule with two readings.
     */
    private static final String DESIGNS_FILE = "yotresultsdistribution_2026-08-20.json";
    private static final String DESIGNS_FIRST_SUPPLEMENT =
            "yotresultsdistribution_2026-08-20-supplementary-1.json";

    private static final String OU_CODE = "B01LY";
    private static final String COURT_HOUSE = "Leeds Youth Court";
    private static final Instant HEARING_TIME = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant REGISTER_TIME = Instant.parse("2026-08-20T16:30:00Z");
    private static final Instant EARLIER_RUN = Instant.parse("2026-08-20T18:00:00Z");

    private final BatchAssembler assembler = new BatchAssembler();

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Puts one night's registers in front of the assembler and asks it to group them.
     *
     * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} so that the
     * seam's refusal is recorded as an assertion rather than ending the case, and the answer it did
     * not produce is then read as {@link #NOTHING} - so every case's own assertions run, and the
     * red run is the assertion each of them makes rather than the exception one of them met.
     *
     * @param active          the registers the store answered with, in the order it answered
     * @param existing        every batch already recorded for the keys those registers fall under
     * @param systemGenerated whether the nightly schedule asked, rather than an operator
     * @return what the assembler decided, or an empty assembly where the seam refused
     */
    private BatchAssembly assemble(final List<RegisterRecord> active,
            final List<RegisterBatch> existing, final boolean systemGenerated) {
        final AtomicReference<BatchAssembly> answered = new AtomicReference<>();
        softly.assertThatCode(
                        () -> answered.set(assembler.assemble(active, existing, systemGenerated)))
                .as(PENDING)
                .doesNotThrowAnyException();
        softly.assertThat(answered.get()).as(PENDING).isNotNull();
        return answered.get() == null ? NOTHING : answered.get();
    }

    /** A nightly run over a key nothing has been rendered for yet, which is the ordinary night. */
    private BatchAssembly assemble(final List<RegisterRecord> active) {
        return assemble(active, List.of(), true);
    }

    /** A nightly run over keys this service has rendered before. */
    private BatchAssembly assemble(final List<RegisterRecord> active,
            final List<RegisterBatch> existing) {
        return assemble(active, existing, true);
    }

    /** One recorded register for the given key, recorded while the flag said ON. */
    private static RegisterRecord recorded(final CourtCentreDay key, final String fileName) {
        return recorded(key, fileName, RecordedFlagState.ON);
    }

    /** One recorded register, as the batch half reads a {@code processed_output} row back. */
    private static RegisterRecord recorded(final CourtCentreDay key, final String fileName,
            final RecordedFlagState flagState) {
        final UUID hearingId = UUID.randomUUID();
        return new RegisterRecord(UUID.randomUUID(), hearingId, HEARING_TIME, key, REGISTER_TIME,
                fileName, null, flagState, document(key, hearingId, fileName));
    }

    /**
     * The stored document, carrying only what assembly can see of it.
     *
     * <p>No recipients and no defendants: which Youth Offending Teams are told is
     * {@code RecipientSet}'s question (T054) and what the document says is the payload mapper's
     * (T031), and a fixture that carried either would let this suite pass while grouping on
     * something it has no business reading.
     */
    private static CourtRegisterDocument document(final CourtCentreDay key, final UUID hearingId,
            final String fileName) {
        return new CourtRegisterDocument(key.registerDate().toString(), HEARING_TIME.toString(),
                hearingId.toString(), key.courtCentreId().toString(), fileName, null,
                new CourtRegisterHearingVenue("West Yorkshire", COURT_HOUSE, null), null, null);
    }

    /** A batch this service already recorded for a key, in the state it reached. */
    private static RegisterBatch earlier(final CourtCentreDay key, final BatchStatus status,
            final String fileName, final UUID supplementOf, final int supplementIndex) {
        final boolean inFlight = status == BatchStatus.PENDING || status == BatchStatus.GENERATING;
        final boolean failed = status == BatchStatus.FAILED;
        return new RegisterBatch(UUID.randomUUID(), key.courtCentreId(), OU_CODE, COURT_HOUSE,
                key.registerDate(), fileName, UUID.randomUUID(),
                inFlight || failed ? null : UUID.randomUUID(), status,
                failed ? BatchFailureReason.GENERATION_FAILED : null,
                failed ? "the template was unavailable" : null, true,
                inFlight ? null : CompletedBy.EVENT, EARLIER_RUN, EARLIER_RUN,
                inFlight || failed ? null : EARLIER_RUN, null, failed ? EARLIER_RUN : null, 1,
                supplementOf, supplementIndex);
    }

    /** A day's first batch for the key, which follows nothing. */
    private static RegisterBatch earlier(final CourtCentreDay key, final BatchStatus status) {
        return earlier(key, status, FIRST_FILE, null, 0);
    }

    /**
     * One batch per court centre and register day, which is the unit everything downstream is
     * addressed to.
     */
    @Nested
    @DisplayName("grouping the registers")
    class Grouping {

        @Test
        void registers_for_two_court_centres_should_be_assembled_into_two_batches() {
            final BatchAssembly assembly = assemble(List.of(
                    recorded(LEEDS_DAY, FIRST_FILE),
                    recorded(SHEFFIELD_DAY, OTHER_CENTRE_FILE)));

            softly.assertThat(assembly.batches())
                    .as("one batch is one PDF sent to one court centre's Youth Offending Teams; a "
                            + "batch spanning two centres is each centre's children listed in the "
                            + "other's document")
                    .extracting(batch -> batch.batch().key())
                    .containsExactly(LEEDS_DAY, SHEFFIELD_DAY);
        }

        @Test
        void registers_for_two_register_days_should_be_assembled_into_two_batches() {
            final BatchAssembly assembly = assemble(List.of(
                    recorded(LEEDS_DAY, FIRST_FILE),
                    recorded(LEEDS_NEXT_DAY, SECOND_FILE)));

            softly.assertThat(assembly.batches())
                    .as("the day is half the key: a court centre's Monday and its Tuesday are two "
                            + "registers, and grouping them together is the day nobody receives")
                    .extracting(batch -> batch.batch().key())
                    .containsExactly(LEEDS_DAY, LEEDS_NEXT_DAY);
        }

        @Test
        void every_register_of_one_key_should_be_in_that_keys_batch() {
            final RegisterRecord first = recorded(LEEDS_DAY, FIRST_FILE);
            final RegisterRecord elsewhere = recorded(SHEFFIELD_DAY, OTHER_CENTRE_FILE);
            final RegisterRecord second = recorded(LEEDS_DAY, SECOND_FILE);

            final BatchAssembly assembly = assemble(List.of(first, elsewhere, second));

            softly.assertThat(assembly.batches())
                    .as("the registers travel with the batch because the grouping cannot be "
                            + "re-derived from it: the store stamps this batch onto exactly these "
                            + "rows and the payload is rendered from exactly these rows, and a "
                            + "membership worked out twice is a document that can disagree with "
                            + "the rows it was stamped onto")
                    .extracting(batch -> batch.batch().key(), AssembledBatch::records)
                    .containsExactly(
                            tuple(LEEDS_DAY, List.of(first, second)),
                            tuple(SHEFFIELD_DAY, List.of(elsewhere)));
        }

        @Test
        void the_batches_should_follow_the_order_the_registers_were_recorded_in() {
            final BatchAssembly assembly = assemble(List.of(
                    recorded(SHEFFIELD_DAY, OTHER_CENTRE_FILE),
                    recorded(LEEDS_DAY, FIRST_FILE),
                    recorded(LEEDS_DAY, SECOND_FILE)));

            softly.assertThat(assembly.batches())
                    .as("the run requests batches one at a time against a deadline, so which of "
                            + "them a deadline cuts off has to be decided by the registers' own "
                            + "order rather than by whatever a hash map answered with")
                    .extracting(batch -> batch.batch().key())
                    .containsExactly(SHEFFIELD_DAY, LEEDS_DAY);
        }

        @Test
        void a_night_with_no_active_registers_should_produce_no_batches_and_defer_nothing() {
            final BatchAssembly assembly = assemble(List.of());

            softly.assertThat(assembly)
                    .as("a night on which nothing was recorded is not a night on which something "
                            + "was left undone, and the run report has to be able to tell them "
                            + "apart")
                    .isEqualTo(NOTHING);
        }
    }

    /**
     * What the batch row says about itself the moment it is assembled, before anything has been
     * asked of the renderer.
     */
    @Nested
    @DisplayName("what an assembled batch says")
    class WhatTheBatchSays {

        @Test
        void a_batch_should_be_named_by_its_first_registers_file_name() {
            final BatchAssembly assembly = assemble(List.of(
                    recorded(LEEDS_DAY, FIRST_FILE),
                    recorded(LEEDS_DAY, SECOND_FILE)));

            softly.assertThat(assembly.batches())
                    .as("progression named the document after the first register in the group, "
                            + "and the name is what support and the file service both find the "
                            + "document by")
                    .extracting(batch -> batch.batch().fileName())
                    .containsExactly(FIRST_FILE);
        }

        @Test
        void the_name_should_come_from_the_first_register_the_batch_actually_holds() {
            final BatchAssembly assembly = assemble(List.of(
                    recorded(LEEDS_DAY, OTHER_CENTRE_FILE, RecordedFlagState.OFF),
                    recorded(LEEDS_DAY, FIRST_FILE)));

            softly.assertThat(assembly.batches())
                    .as("first means first of the registers assembled, not first of the rows "
                            + "read: a document named after a register the legacy generated is a "
                            + "document nobody can match to either system's output")
                    .extracting(batch -> batch.batch().fileName())
                    .containsExactly(FIRST_FILE);
        }

        @Test
        void a_batch_should_carry_its_identity_before_anything_is_asked_of_the_renderer() {
            final BatchAssembly assembly = assemble(List.of(
                    recorded(LEEDS_DAY, FIRST_FILE),
                    recorded(SHEFFIELD_DAY, OTHER_CENTRE_FILE)));

            softly.assertThat(assembly.batches())
                    .as("the identity travels as systemdocgenerator's sourceCorrelationId and is "
                            + "the only thing that correlates a public event back to the rows the "
                            + "document was built from; two batches sharing one would each be "
                            + "completed by the other's event")
                    .extracting(batch -> batch.batch().batchId())
                    .hasSize(2)
                    .doesNotContainNull()
                    .doesNotHaveDuplicates();
        }

        @Test
        void a_batch_should_be_assembled_at_the_state_a_batch_starts_from() {
            final BatchAssembly assembly = assemble(List.of(recorded(LEEDS_DAY, FIRST_FILE)));

            softly.assertThat(assembly.batches())
                    .as("PENDING is a batch nothing has been asked of the renderer for; a batch "
                            + "assembled further along the machine carries stamps for events that "
                            + "never happened")
                    .extracting(batch -> batch.batch().status())
                    .containsExactly(BatchStatus.PENDING);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void a_batch_should_record_which_trigger_asked_for_it(final boolean systemGenerated) {
            final BatchAssembly assembly =
                    assemble(List.of(recorded(LEEDS_DAY, FIRST_FILE)), List.of(), systemGenerated);

            softly.assertThat(assembly.batches())
                    .as("progression's own flag: the schedule and the operations CLI both "
                            + "assemble, and a night an operator stood in for has to be readable "
                            + "as one afterwards")
                    .extracting(batch -> batch.batch().systemGenerated())
                    .containsExactly(systemGenerated);
        }
    }

    /**
     * The registers that are read but not assembled, which is the cutover working in the direction
     * nobody watches.
     */
    @Nested
    @DisplayName("registers this run leaves where they are")
    class LeftWhereTheyAre {

        @ParameterizedTest
        @EnumSource(value = RecordedFlagState.class, names = {"OFF", "UNKNOWN"})
        void a_register_recorded_while_the_flag_was_not_on_should_not_be_assembled(
                final RecordedFlagState flagState) {

            final RegisterRecord assembled = recorded(LEEDS_DAY, FIRST_FILE);
            final RegisterRecord legacys = recorded(LEEDS_DAY, SECOND_FILE, flagState);

            final BatchAssembly assembly = assemble(List.of(assembled, legacys));

            softly.assertThat(assembly.batches())
                    .as("a register recorded while the legacy was generating may already have "
                            + "been sent by it, and batching it sends the same document to the "
                            + "same Youth Offending Team twice (FR-015, research §12); UNKNOWN is "
                            + "treated as off for the same reason, because nobody could say whose "
                            + "turn it was")
                    .extracting(batch -> batch.batch().key(), AssembledBatch::records)
                    .containsExactly(tuple(LEEDS_DAY, List.of(assembled)));
        }

        @Test
        void a_key_recorded_entirely_while_the_flag_was_off_should_produce_nothing() {
            final BatchAssembly assembly = assemble(List.of(
                    recorded(LEEDS_DAY, FIRST_FILE, RecordedFlagState.OFF),
                    recorded(LEEDS_DAY, SECOND_FILE, RecordedFlagState.UNKNOWN)));

            softly.assertThat(assembly)
                    .as("those rows belong to the legacy, which has already generated that day: "
                            + "they are not a key waiting on this service, and reporting them as "
                            + "deferred would put a day nobody owes a document on the list of the "
                            + "days that are owed one")
                    .isEqualTo(NOTHING);
        }
    }

    /**
     * A hearing re-shared after its day was rendered, which is design Q27 and the one shape in
     * which a key legitimately has two documents.
     */
    @Nested
    @DisplayName("a register that arrived after its day was rendered")
    class Supplementary {

        @Test
        void a_key_that_has_never_been_rendered_should_be_assembled_following_nothing() {
            final BatchAssembly assembly = assemble(
                    List.of(recorded(LEEDS_DAY, FIRST_FILE)),
                    List.of(earlier(SHEFFIELD_DAY, BatchStatus.NOTIFIED)));

            softly.assertThat(assembly.batches())
                    .as("the supplementary link is per key: another court centre's finished "
                            + "document says nothing about this one, and a first batch that "
                            + "claimed to follow it would name a document for a different set of "
                            + "children")
                    .extracting(batch -> batch.batch().supplementOf(),
                            batch -> batch.batch().supplementIndex(),
                            batch -> batch.batch().fileName())
                    .containsExactly(tuple(null, 0, FIRST_FILE));
        }

        @ParameterizedTest
        @EnumSource(value = BatchStatus.class,
                names = {"NOTIFIED", "PARTIALLY_NOTIFIED", "NOTIFIED_NOBODY"})
        void a_key_whose_earlier_batch_has_finished_should_be_assembled_as_a_supplement(
                final BatchStatus terminal) {

            final RegisterBatch finished = earlier(LEEDS_DAY, terminal);

            final BatchAssembly assembly =
                    assemble(List.of(recorded(LEEDS_DAY, SECOND_FILE)), List.of(finished));

            softly.assertThat(assembly.batches())
                    .as("once every earlier batch for the key is terminal the day may be rendered "
                            + "again, and the second document names the first so a day with two "
                            + "of them stays ordered and attributable rather than looking like a "
                            + "duplicate somebody has to investigate")
                    .extracting(batch -> batch.batch().supplementOf(),
                            batch -> batch.batch().supplementIndex())
                    .containsExactly(tuple(finished.batchId(), 1));
        }

        /**
         * The other half of Q27's sentence, which the three states above are only one half of.
         *
         * <p>A FAILED batch produced no document and told nobody, so the rows it held are still the
         * day's first register rather than an addition to one. data-model.md and the V2 index
         * comment both say so - a FAILED batch is re-assembled, a notified one is followed - and
         * the file name is where the difference is visible from outside: this is what
         * systemdocgenerator's metadata row records and what support finds the document by.
         */
        @Test
        void a_key_whose_only_earlier_batch_failed_should_be_re_assembled_not_supplemented() {
            final RegisterBatch failed =
                    earlier(LEEDS_DAY, BatchStatus.FAILED, DESIGNS_FILE, null, 0);

            final BatchAssembly assembly =
                    assemble(List.of(recorded(LEEDS_DAY, DESIGNS_FILE)), List.of(failed));

            softly.assertThat(assembly.batches())
                    .as("a supplement is an addition to a document a Youth Offending Team already "
                            + "has; a batch that failed PAYLOAD_STORE_UNAVAILABLE rendered nothing "
                            + "and sent nothing, so the next run's batch is the day's first "
                            + "document and is named as one")
                    .extracting(batch -> batch.batch().supplementOf(),
                            batch -> batch.batch().supplementIndex(),
                            batch -> batch.batch().fileName())
                    .containsExactly(tuple(null, 0, DESIGNS_FILE));
        }

        /**
         * And a supplement that failed is re-assembled where it failed, not one place further on.
         *
         * <p>The index counts the documents a day has, and a failed supplement is not one of them:
         * bumping it would leave the day with a {@code -supplementary-1} nobody received and a
         * {@code -supplementary-2} that is really its first supplement.
         */
        @Test
        void a_failed_supplement_should_be_re_assembled_at_the_index_it_failed_at() {
            final RegisterBatch sent =
                    earlier(LEEDS_DAY, BatchStatus.NOTIFIED, DESIGNS_FILE, null, 0);
            final RegisterBatch failedSupplement = earlier(LEEDS_DAY, BatchStatus.FAILED,
                    DESIGNS_FIRST_SUPPLEMENT, sent.batchId(), 1);

            final BatchAssembly assembly = assemble(
                    List.of(recorded(LEEDS_DAY, DESIGNS_FILE)), List.of(sent, failedSupplement));

            softly.assertThat(assembly.batches())
                    .as("the day has had exactly one document, so the batch that follows it is "
                            + "still its first supplement and still follows the batch that was "
                            + "sent")
                    .extracting(batch -> batch.batch().supplementOf(),
                            batch -> batch.batch().supplementIndex(),
                            batch -> batch.batch().fileName())
                    .containsExactly(tuple(sent.batchId(), 1, DESIGNS_FIRST_SUPPLEMENT));
        }

        @Test
        void a_supplements_name_should_carry_its_index_before_the_extension() {
            final RegisterBatch finished =
                    earlier(LEEDS_DAY, BatchStatus.NOTIFIED, DESIGNS_FILE, null, 0);

            final BatchAssembly assembly =
                    assemble(List.of(recorded(LEEDS_DAY, DESIGNS_FILE)), List.of(finished));

            softly.assertThat(assembly.batches())
                    .as("data-model.md's own example: yotresultsdistribution_2026-08-20.json is followed "
                            + "by yotresultsdistribution_2026-08-20-supplementary-1.json, so the two "
                            + "documents of one day are told apart by a reader who has only their "
                            + "names")
                    .extracting(batch -> batch.batch().fileName())
                    .containsExactly(DESIGNS_FIRST_SUPPLEMENT);
        }

        @Test
        void a_second_supplement_should_count_up_and_still_be_named_from_the_first_register() {
            final RegisterBatch first =
                    earlier(LEEDS_DAY, BatchStatus.NOTIFIED, DESIGNS_FILE, null, 0);
            final RegisterBatch supplement = earlier(LEEDS_DAY, BatchStatus.NOTIFIED_NOBODY,
                    DESIGNS_FIRST_SUPPLEMENT, first.batchId(), 1);

            final BatchAssembly assembly = assemble(
                    List.of(recorded(LEEDS_DAY, DESIGNS_FILE)), List.of(first, supplement));

            softly.assertThat(assembly.batches())
                    .as("the index counts up from the highest already recorded and the name is "
                            + "built from the register's own file name, not from the last "
                            + "supplement's - a name built from a name accumulates the suffix and "
                            + "the third document of a day is called -supplementary-1-"
                            + "supplementary-2")
                    .extracting(batch -> batch.batch().supplementOf(),
                            batch -> batch.batch().supplementIndex(),
                            batch -> batch.batch().fileName())
                    .containsExactly(tuple(supplement.batchId(), 2,
                            "yotresultsdistribution_2026-08-20-supplementary-2.json"));
        }

        @Test
        void the_order_the_earlier_batches_are_read_in_should_not_decide_which_one_is_followed() {
            final RegisterBatch first =
                    earlier(LEEDS_DAY, BatchStatus.NOTIFIED, DESIGNS_FILE, null, 0);
            final RegisterBatch supplement = earlier(LEEDS_DAY, BatchStatus.NOTIFIED_NOBODY,
                    DESIGNS_FIRST_SUPPLEMENT, first.batchId(), 1);

            final BatchAssembly assembly = assemble(
                    List.of(recorded(LEEDS_DAY, DESIGNS_FILE)), List.of(supplement, first));

            softly.assertThat(assembly.batches())
                    .as("which batch a supplement follows is decided by the index the batches "
                            + "carry and not by the order a query answered in, or the same night "
                            + "assembles differently depending on how the rows came back")
                    .extracting(batch -> batch.batch().supplementOf(),
                            batch -> batch.batch().supplementIndex())
                    .containsExactly(tuple(supplement.batchId(), 2));
        }

        @ParameterizedTest
        @EnumSource(value = BatchStatus.class, names = {"PENDING", "GENERATING", "GENERATED"})
        void a_key_with_a_batch_still_in_flight_should_be_left_for_a_later_run(
                final BatchStatus inFlight) {

            final BatchAssembly assembly = assemble(
                    List.of(recorded(LEEDS_DAY, SECOND_FILE)),
                    List.of(earlier(LEEDS_DAY, inFlight)));

            softly.assertThat(assembly.batches())
                    .as("the schema admits one in-flight batch per key, and a second live batch "
                            + "for a day still owed a render request, a render outcome or an "
                            + "e-mail is the write the partial unique index refuses")
                    .isEmpty();
            softly.assertThat(assembly.deferred())
                    .as("and the key is reported rather than dropped: a register that produced no "
                            + "batch and no word about why is the silence the progression leg "
                            + "left behind, one step earlier in the flow")
                    .containsExactly(LEEDS_DAY);
        }

        @Test
        void a_key_left_waiting_should_not_hold_up_the_rest_of_the_night() {
            final RegisterRecord waiting = recorded(LEEDS_DAY, SECOND_FILE);
            final RegisterRecord ready = recorded(SHEFFIELD_DAY, OTHER_CENTRE_FILE);

            final BatchAssembly assembly = assemble(List.of(waiting, ready),
                    List.of(earlier(LEEDS_DAY, BatchStatus.GENERATING)));

            softly.assertThat(assembly.batches())
                    .as("per-key isolation, as the run has per-batch isolation: one court centre "
                            + "waiting on a document that has not come back must not cost the "
                            + "estate the rest of the night's registers")
                    .extracting(batch -> batch.batch().key(), AssembledBatch::records)
                    .containsExactly(tuple(SHEFFIELD_DAY, List.of(ready)));
            softly.assertThat(assembly.deferred())
                    .as("and the one that waited is the only one reported")
                    .containsExactly(LEEDS_DAY);
        }
    }
}
