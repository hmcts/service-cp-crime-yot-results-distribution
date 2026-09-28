package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchFailureReason;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;

/**
 * The {@code register_batch} statements, against a real Postgres.
 *
 * <p>{@link JdbcRegisterStore} owns the writes that have to move {@code processed_output} in the
 * same statement; what is left is this repository, and it is the half every other collaborator
 * reaches the batch through - the read by the identity every outcome is attributed by, the
 * the three in-flight age reads, the operations CLI's own assembly, and the whole-row write that
 * carries the facts the port's {@code mark} signatures do not. Each of its five statements is
 * round-tripped here, because nothing else will: the phases that consume them mock the store, so a
 * column dropped from one of these statements would first be noticed by a batch that could not say
 * what happened to it.
 *
 * <p><strong>The first case is the insert in exactly the state assembly leaves it in</strong>: ten
 * of the row's columns empty, two of them {@code uuid}, bound as the typed nulls the statement
 * declares. A batch that could not be written at assembly is a court centre's day that is never
 * rendered at all, and the round trip is what says the empty columns come back empty rather than
 * defaulted by the table.
 *
 * <p>The cases in {@code Moving} between them write every column the update statement names,
 * because that is the only way a dropped column is visible: the statement is a whole-row write, so
 * a column left out of it silently keeps whatever the row already held. They also walk the batch
 * through the states the diagram draws rather than jumping to the one under test, because the write
 * is a compare-and-set and the state machine is what it is set against.
 *
 * <p>Every case mints its own court centre, so the suites sharing one container share no rows;
 * {@link #mine(List)} narrows the one read that answers for the whole table.
 */
@DisplayName("register_batch repository")
class RegisterBatchRepositoryIT {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 8, 25);

    private static final Instant ASSEMBLED_AT = Instant.parse("2026-08-24T17:00:00Z");
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-24T17:00:04Z");
    private static final Instant GENERATED_AT = Instant.parse("2026-08-24T17:04:11Z");
    private static final Instant NOTIFIED_AT = Instant.parse("2026-08-24T17:04:19Z");
    private static final Instant FAILED_AT = Instant.parse("2026-08-24T17:09:31Z");

    /** The cutoff the three in-flight reads answer behind: older than this is late. */
    private static final Instant AGE_CUTOFF = Instant.parse("2026-08-24T17:30:00Z");

    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("3a7f1c92-6d84-4b05-9e73-1c2b8a4e07d5");

    private static final String OU_CODE = "B01LY00";
    private static final String COURT_HOUSE = "Lavender Hill Youth Court";

    /**
     * A second court house of the same date, sorting after the first.
     *
     * <p>A person reading a national date reads it a court house at a time, so what the listing is
     * ordered by has to be visible: two names that sort the other way round from the order the rows
     * are written in.
     */
    private static final String OTHER_COURT_HOUSE = "Wimbledon Youth Court";

    /**
     * Two batches of one court house, minted rather than random, so the tie-break is knowable.
     *
     * <p>The identity breaks the tie a date's several batches for one court house would otherwise
     * leave to the planner, and a case whose identities were random could not say which of them
     * should come first.
     */
    private static final UUID EARLIER_IDENTITY =
            UUID.fromString("0a1c4e73-2b58-4d96-8f20-6c7b3d915e48");
    private static final UUID LATER_IDENTITY =
            UUID.fromString("f0b62d84-51ae-4c37-9b18-2e5d7a4c0f19");

    /**
     * Two more of them, for the case about the batch that cannot say where it sat.
     *
     * <p>Their own rather than the pair above, because a batch identity is this table's primary key
     * and the several cases here share one container. The unnamed court house takes the earlier of
     * the two deliberately: a read that had fallen back to the tie-break alone would then answer it
     * first, which is the other way round from where the rule puts it.
     */
    private static final UUID NO_COURT_HOUSE_IDENTITY =
            UUID.fromString("1b3d5f07-4a29-4e68-9c15-8d0e2f7a6b34");

    private static final UUID A_COURT_HOUSE_IDENTITY =
            UUID.fromString("9e5c7a12-6b40-4f83-a27d-3f1b8c04d95e");

    /** A date this suite writes no batch for, so a listing of it is a listing of nothing. */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 8, 26);

    /** systemdocgenerator's own words, kept for support and never logged at INFO. */
    private static final String SDG_REASON = "template OEE_Layout5 rendered no pages";

    /** The same words, from a renderer that said far more of them than the column holds. */
    private static final String OVERSIZED_SDG_REASON = (SDG_REASON + "; ").repeat(30);

    /** This case's court centre: minted per test so no case can read another's rows. */
    private final UUID courtCentre = UUID.randomUUID();

    /**
     * How long a notification claim stays live in these cases.
     *
     * <p>The deployed value is {@code yotresultsdistribution.notification.claim-lease}, fifteen minutes; a
     * suite that waited that long to see a claim expire would be a suite nobody runs, so the lease
     * is short here and the expiry case waits it out.
     */
    private static final Duration NOTIFIER_LEASE = Duration.ofMillis(250);

    /**
     * How long the renewal case waits before renewing, so the moved stamp is visible.
     *
     * <p>Well inside the lease above - a renewal is not a takeover - and long enough that
     * {@code now()} has really moved on between the claim and the renewal.
     */
    private static final Duration RENEWAL_GAP = Duration.ofMillis(30);

    /**
     * This case's payload ids, minted per test for the same reason as the court centre.
     *
     * <p>The overdue reads below answer for the whole table, so a payload id shared between cases -
     * or with another suite - would make a case fail on how many batches the container held rather
     * than on what the statement does.
     */
    private final UUID payloadFileId = UUID.randomUUID();
    private final UUID secondPayloadFileId = UUID.randomUUID();

    private final RegisterBatchRepository repository =
            new RegisterBatchRepository(ProcessedLogTestSupport.jdbcClient(),
                    ProcessedLogTestSupport.transactions(), NOTIFIER_LEASE);

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    @Nested
    @DisplayName("writing a batch the caller assembled itself")
    class Inserting {

        @Test
        void inserting_an_assembled_batch_should_read_back_exactly_what_the_caller_decided() {
            final RegisterBatch assembled = assembled(MONDAY);

            repository.insert(assembled);

            assertThat(repository.findById(assembled.batchId()))
                    .as("the CLI's assembly says system_generated is false and the schedule's says "
                            + "true, and a row that defaulted either would lose the only record of "
                            + "whether a person or a timer started the run")
                    .contains(assembled);
        }

        @Test
        void inserting_a_batch_the_operations_cli_assembled_should_record_that_a_person_started_it() {
            final RegisterBatch byHand = new RegisterBatch(UUID.randomUUID(), courtCentre, OU_CODE,
                    COURT_HOUSE, MONDAY, fileName(MONDAY), null, null, BatchStatus.PENDING, null,
                    null, false, null, ASSEMBLED_AT, null, null, null, null, 0, null, 0);

            repository.insert(byHand);

            assertThat(repository.findById(byHand.batchId()).map(RegisterBatch::systemGenerated))
                    .as("progression's own flag, and the CLI is the only writer that sets it false")
                    .contains(false);
        }

        @Test
        void reading_a_batch_this_service_never_recorded_should_answer_that_it_has_none() {
            assertThat(repository.findById(UUID.randomUUID()))
                    .as("an event correlating on a batch nobody assembled is unattributable, and "
                            + "an empty answer is what lets the listener say so")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the batches whose outcome is overdue")
    class Overdue {

        @Test
        void generating_since_should_answer_with_the_overdue_batches_oldest_first() {
            final RegisterBatch first = requested(MONDAY, payloadFileId, REQUESTED_AT);
            final RegisterBatch second = requested(TUESDAY, secondPayloadFileId,
                    REQUESTED_AT.plusSeconds(90));

            assertThat(mine(repository.generatingSince(AGE_CUTOFF)))
                    .as("oldest first, so a caller that can take only some of them takes the "
                            + "ones that have been waiting longest, which are the registers "
                            + "already missing")
                    .containsExactly(first, second);
        }

        @Test
        void generating_since_should_exclude_a_batch_inside_the_cutoff() {
            requested(MONDAY, payloadFileId, AGE_CUTOFF.plusSeconds(1));

            assertThat(mine(repository.generatingSince(AGE_CUTOFF)))
                    .as("a render asked for a moment ago is a render systemdocgenerator is still "
                            + "allowed to be working on, and a reading that counted it would say "
                            + "an ordinary night was late")
                    .isEmpty();
        }

        @Test
        void generating_since_should_exclude_a_batch_that_was_never_requested() {
            repository.insert(assembled(MONDAY));

            assertThat(mine(repository.generatingSince(AGE_CUTOFF)))
                    .as("a PENDING batch is waiting for this service, not for systemdocgenerator, "
                            + "and reading it here would age a render nobody requested")
                    .isEmpty();
        }
    }

    /**
     * The batches that never reached the renderer, which the overdue read above cannot see.
     *
     * <p>A batch whose payload id was minted and whose {@code markRequested} never landed stays
     * PENDING with its rows stamped, and neither of the other two reads can see it:
     * {@code generatingSince} reads GENERATING, the stamped rows are outside
     * {@code activeUnbatched}, and the live-key index defers every later re-share of that key
     * behind it. This read is what publishes its age, and the payload id is what makes it
     * answerable at all.
     */
    @Nested
    @DisplayName("the batches that never reached the renderer")
    class NeverRequested {

        @Test
        void pending_since_should_answer_with_the_stale_pending_batches_oldest_first() {
            final RegisterBatch first = minted(MONDAY, payloadFileId, ASSEMBLED_AT);
            final RegisterBatch second =
                    minted(TUESDAY, secondPayloadFileId, ASSEMBLED_AT.plusSeconds(90));

            assertThat(pendingSince(AGE_CUTOFF))
                    .as("oldest first, for the same reason the overdue read is: the ones that have "
                            + "been stuck longest are the registers already missing")
                    .containsExactly(first, second);
        }

        @Test
        void pending_since_should_exclude_a_batch_assembled_inside_the_cutoff() {
            minted(MONDAY, payloadFileId, AGE_CUTOFF.plusSeconds(1));

            assertThat(pendingSince(AGE_CUTOFF))
                    .as("a batch assembled a moment ago is a batch the run is still working "
                            + "through, not one it left behind")
                    .isEmpty();
        }

        @Test
        void pending_since_should_exclude_a_batch_whose_payload_was_never_minted() {
            repository.insert(assembled(MONDAY));

            assertThat(pendingSince(AGE_CUTOFF))
                    .as("this read is about a batch that minted a payload and never sent it; a "
                            + "batch that minted none never reached that state at all")
                    .isEmpty();
        }

        @Test
        void pending_since_should_exclude_a_batch_that_did_reach_the_renderer() {
            requested(MONDAY, payloadFileId, REQUESTED_AT);

            assertThat(pendingSince(AGE_CUTOFF))
                    .as("a GENERATING batch is the overdue read's, and a batch counted by two "
                            + "readings is one late batch reported as two")
                    .isEmpty();
        }
    }

    /**
     * The batches holding a document nobody was told about, which neither read above can see.
     *
     * <p>Notification follows the mark that records the document, in one step of one code path. A
     * store that went away in between, or a listener session that rolled the delivery back after
     * that mark had committed, leaves the batch at GENERATED with rows that were never settled - and
     * {@code generatingSince} reads GENERATING while {@code pendingSince} reads PENDING, so nothing
     * moves for it. This read is what publishes its age.
     */
    @Nested
    @DisplayName("the batches holding a document nobody was told about")
    class Parked {

        @Test
        void generated_since_should_answer_with_the_parked_batches_oldest_first() {
            final RegisterBatch first = parked(MONDAY, payloadFileId, GENERATED_AT);
            final RegisterBatch second =
                    parked(TUESDAY, secondPayloadFileId, GENERATED_AT.plusSeconds(90));

            assertThat(generatedSince(AGE_CUTOFF))
                    .as("oldest first, for the same reason the other two reads are: the ones that "
                            + "have held a document longest are the registers already missing")
                    .containsExactly(first, second);
        }

        @Test
        void generated_since_should_exclude_a_batch_generated_inside_the_cutoff() {
            parked(MONDAY, payloadFileId, AGE_CUTOFF.plusSeconds(1));

            assertThat(generatedSince(AGE_CUTOFF))
                    .as("a batch whose document arrived a moment ago is a batch the notifying leg "
                            + "is still working through, not one it left behind")
                    .isEmpty();
        }

        @Test
        void generated_since_should_exclude_a_batch_whose_recipients_were_told() {
            final RegisterBatch told = parked(MONDAY, payloadFileId, GENERATED_AT);
            repository.compareAndSet(new RegisterBatch(told.batchId(), courtCentre, OU_CODE,
                    COURT_HOUSE, MONDAY, fileName(MONDAY), payloadFileId, DOCUMENT_FILE_ID,
                    BatchStatus.NOTIFIED, null, null, true, CompletedBy.EVENT, ASSEMBLED_AT,
                    REQUESTED_AT, GENERATED_AT, NOTIFIED_AT, null, 1, null, 0),
                    BatchStatus.GENERATED);

            assertThat(generatedSince(AGE_CUTOFF))
                    .as("a batch that reached a notified state is a batch nothing is owed about, "
                            + "and a reading that kept it would never come back down")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("moving a batch from the state it was read in")
    class Moving {

        @Test
        void moving_a_batch_to_notified_should_write_every_column_the_outcome_settled() {
            final RegisterBatch assembled = assembled(MONDAY);
            repository.insert(assembled);
            final RegisterBatch requested = generating(assembled, payloadFileId, REQUESTED_AT);
            repository.compareAndSet(requested, BatchStatus.PENDING);
            final RegisterBatch generated = generated(assembled);
            repository.compareAndSet(generated, BatchStatus.GENERATING);
            final RegisterBatch notified = new RegisterBatch(assembled.batchId(), courtCentre,
                    OU_CODE, COURT_HOUSE, MONDAY, fileName(MONDAY), payloadFileId,
                    DOCUMENT_FILE_ID, BatchStatus.NOTIFIED, null, null, true,
                    CompletedBy.EVENT, ASSEMBLED_AT, REQUESTED_AT, GENERATED_AT,
                    NOTIFIED_AT, null, 1, null, 0);

            final boolean moved = repository.compareAndSet(notified, BatchStatus.GENERATED);

            assertThat(moved)
                    .as("whether a row changed is the decision, and never a read-back")
                    .isTrue();
            assertThat(repository.findById(assembled.batchId()))
                    .as("a caller that read a batch, decided about it and wrote it back cannot "
                            + "leave half of its decision behind - completed_by above all, which "
                            + "is the only record of which mechanism learned the outcome")
                    .contains(notified);
        }

        @Test
        void moving_a_batch_to_failed_should_keep_this_services_code_and_the_renderers_words_apart() {
            final RegisterBatch assembled = assembled(MONDAY);
            repository.insert(assembled);
            final RegisterBatch failed = new RegisterBatch(assembled.batchId(), courtCentre,
                    OU_CODE, COURT_HOUSE, MONDAY, fileName(MONDAY), payloadFileId, null,
                    BatchStatus.FAILED, BatchFailureReason.GENERATION_FAILED, SDG_REASON, true,
                    CompletedBy.EVENT, ASSEMBLED_AT, REQUESTED_AT, null, null,
                    FAILED_AT, 2, null, 0);

            assertThat(repository.compareAndSet(failed, BatchStatus.PENDING)).isTrue();

            assertThat(repository.findById(assembled.batchId()))
                    .as("the bounded code is what the batches counter labels its outcome with and "
                            + "what the run report prints; the free text is what a person reads, "
                            + "and the two are separate columns so the second can stay out of logs")
                    .contains(failed);
        }

        /**
         * How long systemdocgenerator's message is is its decision, and the column's bound is this
         * service's. The batch applies the bound as it is built, so a caller cannot carry a reason
         * the row will refuse - which would fail the write that was recording why the batch failed.
         */
        @Test
        void an_oversized_renderer_reason_should_be_bounded_by_the_batch_that_carries_it() {
            final RegisterBatch assembled = assembled(MONDAY);
            repository.insert(assembled);
            final RegisterBatch failed = new RegisterBatch(assembled.batchId(), courtCentre,
                    OU_CODE, COURT_HOUSE, MONDAY, fileName(MONDAY), payloadFileId, null,
                    BatchStatus.FAILED, BatchFailureReason.GENERATION_FAILED, OVERSIZED_SDG_REASON,
                    true, CompletedBy.EVENT, ASSEMBLED_AT, REQUESTED_AT, null,
                    null, FAILED_AT, 2, null, 0);

            assertThat(repository.compareAndSet(failed, BatchStatus.PENDING))
                    .as("the row the renderer's verbosity would otherwise have refused")
                    .isTrue();

            assertThat(repository.findById(assembled.batchId()).map(RegisterBatch::sdgReason))
                    .as("bounded, and saying so: a reader who cannot see the rest must be able to "
                            + "tell that there is a rest")
                    .hasValueSatisfying(reason -> assertThat(reason)
                            .hasSize(RegisterBatch.REASON_LIMIT)
                            .startsWith(SDG_REASON)
                            .endsWith(" [truncated]"));
        }

        @Test
        void moving_a_batch_this_service_never_recorded_should_change_nothing() {
            final RegisterBatch absent = generating(assembled(MONDAY), payloadFileId, REQUESTED_AT);

            assertThat(repository.compareAndSet(absent, BatchStatus.PENDING))
                    .as("no row changed is a batch that is not there, which is a caller acting on "
                            + "a correlation this service never minted")
                    .isFalse();
        }

        /**
         * FAILED is terminal, and the diagram's {@code FAILED -> PENDING} is the re-assembly CLI
         * minting a <em>new</em> batch identity rather than reviving this one.
         *
         * <p>A whole-row write with no expected state would revive it: systemdocgenerator's verdict
         * about the old identity stays attached to a batch that is being rendered again, and the
         * failure that was reported to an operator quietly stops being true.
         */
        @Test
        void reviving_a_failed_batch_should_be_refused_by_the_state_machine() {
            final RegisterBatch assembled = assembled(MONDAY);
            repository.insert(assembled);
            final RegisterBatch failed = new RegisterBatch(assembled.batchId(), courtCentre,
                    OU_CODE, COURT_HOUSE, MONDAY, fileName(MONDAY), payloadFileId, null,
                    BatchStatus.FAILED, BatchFailureReason.GENERATION_FAILED, SDG_REASON, true,
                    CompletedBy.EVENT, ASSEMBLED_AT, REQUESTED_AT, null, null,
                    FAILED_AT, 2, null, 0);
            repository.compareAndSet(failed, BatchStatus.PENDING);
            final RegisterBatch revived = generating(assembled, payloadFileId, REQUESTED_AT);

            assertThatThrownBy(() -> repository.compareAndSet(revived, BatchStatus.FAILED))
                    .as("the move is refused where it is attempted, not discovered afterwards "
                            + "from a row that already changed")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FAILED")
                    .hasMessageContaining("GENERATING");
            assertThat(repository.findById(assembled.batchId()))
                    .as("and the batch is still the failure it was")
                    .contains(failed);
        }

        /**
         * The other half: a move the machine draws, made against a state the batch has left.
         */
        @Test
        void a_stale_expected_status_should_change_nothing_and_say_so() {
            final RegisterBatch assembled = assembled(MONDAY);
            repository.insert(assembled);
            final RegisterBatch requested = generating(assembled, payloadFileId, REQUESTED_AT);
            repository.compareAndSet(requested, BatchStatus.PENDING);
            final RegisterBatch again = generating(assembled, secondPayloadFileId,
                    REQUESTED_AT.plusSeconds(90));

            assertThat(repository.compareAndSet(again, BatchStatus.PENDING))
                    .as("a second run that still believes the batch is PENDING changes nothing, "
                            + "and is told so rather than being left to assume it requested a "
                            + "render that another run had already requested")
                    .isFalse();
            assertThat(repository.findById(assembled.batchId()))
                    .as("the payload the first run actually stored is still the batch's")
                    .contains(requested);
        }

        /**
         * The move that leaves the batch still waiting, and what it must not carry.
         *
         * <p>GENERATING is a batch whose render was asked for and whose answer has not come back,
         * so nothing has completed it. A whole-row write that carried an attribution into it would
         * say the answer had already arrived, and the next run's stale-batch pass would be reading
         * a row that claims to have been reported on already.
         */
        @Test
        void moving_a_batch_to_generating_with_an_attribution_should_be_refused() {
            final RegisterBatch assembled = assembled(MONDAY);
            repository.insert(assembled);
            final RegisterBatch attributed = new RegisterBatch(assembled.batchId(), courtCentre,
                    OU_CODE, COURT_HOUSE, MONDAY, fileName(MONDAY), payloadFileId, null,
                    BatchStatus.GENERATING, null, null, true, CompletedBy.EVENT, ASSEMBLED_AT,
                    REQUESTED_AT, null, null, null, 1, null, 0);

            assertThatThrownBy(() -> repository.compareAndSet(attributed, BatchStatus.PENDING))
                    .as("the render was asked for and no answer has come back, so there is no "
                            + "outcome for a mechanism to have learned")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("GENERATING")
                    .hasMessageContaining("EVENT");
            assertThat(repository.findById(assembled.batchId()))
                    .as("and the batch is exactly where the insert left it")
                    .contains(assembled);
        }
    }

    @Nested
    @DisplayName("a batch enters this table at the beginning")
    class Insertion {

        /**
         * Every writer of this statement is assembling: the operations CLI, and the re-assembly of a
         * FAILED batch under a fresh identity. Both start at PENDING, and a row inserted anywhere
         * else in the machine is a batch that skipped the states it should have been moved through
         * - and whose earlier stamps therefore describe events that never happened.
         */
        @Test
        void inserting_a_batch_anywhere_but_pending_should_be_refused() {
            final RegisterBatch midway =
                    generating(assembled(MONDAY), payloadFileId, REQUESTED_AT);

            assertThatThrownBy(() -> repository.insert(midway))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("GENERATING");
            assertThat(repository.findById(midway.batchId()))
                    .as("and nothing is written")
                    .isEmpty();
        }

        /**
         * A batch is assembled, not completed, so the row that starts it names no mechanism.
         *
         * <p>{@code completed_by} says which mechanism learned the outcome, and at PENDING there is
         * no outcome: nothing has been asked of the renderer, so nothing can have announced one. A
         * row inserted with one credits a decision nobody made, and leaves the only column that
         * records which mechanism delivered an outcome naming one for an outcome nobody delivered.
         */
        @Test
        void inserting_a_batch_that_already_names_a_mechanism_should_be_refused() {
            final RegisterBatch attributed = new RegisterBatch(UUID.randomUUID(), courtCentre,
                    OU_CODE, COURT_HOUSE, MONDAY, fileName(MONDAY), null, null, BatchStatus.PENDING,
                    null, null, true, CompletedBy.EVENT, ASSEMBLED_AT, null, null, null, null, 0,
                    null, 0);

            assertThatThrownBy(() -> repository.insert(attributed))
                    .as("nothing has been asked of the renderer, so nothing can have reported "
                            + "back about it")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PENDING")
                    .hasMessageContaining("EVENT");
            assertThat(repository.findById(attributed.batchId()))
                    .as("and nothing is written")
                    .isEmpty();
        }
    }

    /**
     * The notification claim, which is what makes the notifying leg single-runner.
     *
     * <p>Notification is reachable by two mechanisms over one batch - the outcome sink on a
     * delivered {@code document-available} and an operator's resend - and both derive the same owed
     * set from the same records, so without a claim both POST for every recipient and both then
     * settle the rows and the batch. The serialisation is Postgres's and is asserted here: an
     * advisory lock on the batch id and a compare-and-set on the two claim columns, taken together
     * in one short transaction so that two attempts that arrived together are two attempts one
     * after the other rather than two reads of the same row.
     *
     * <p>The claim is not part of {@link RegisterBatch}, so these cases read the columns directly.
     * That is the point rather than an inconvenience: what a batch <em>is</em> does not include who
     * is currently telling its recipients, and a claim on the domain record would be a component
     * every caller of the whole-row compare-and-set could overwrite.
     */
    @Nested
    @DisplayName("the notification claim")
    class Claiming {

        @Test
        void the_first_notifier_to_ask_should_take_the_claim() {
            final RegisterBatch generated = walkedToGenerated(inserted(MONDAY));
            final UUID token = UUID.randomUUID();

            assertThat(repository.claimForNotification(generated.batchId(), token))
                    .as("the batch is unclaimed, so the notifier that asked may tell its "
                            + "recipients")
                    .isEqualTo(NotificationClaim.CLAIMED);
            assertThat(claimHolderOf(generated.batchId()))
                    .as("and the claim is on the row under this notifier's own token, which is "
                            + "what a release and a takeover are both fenced on")
                    .isEqualTo(token);
        }

        @Test
        void a_second_notifier_should_be_refused_while_the_first_one_holds_the_claim() {
            final RegisterBatch generated = walkedToGenerated(inserted(MONDAY));
            final UUID first = UUID.randomUUID();
            final UUID second = UUID.randomUUID();
            repository.claimForNotification(generated.batchId(), first);

            assertThat(repository.claimForNotification(generated.batchId(), second))
                    .as("one notifier per batch at a time: the second POST under a row's own "
                            + "identity is a second e-mail about the same children to the same "
                            + "team, and this answer says the batch is being told rather than "
                            + "that there is no batch")
                    .isEqualTo(NotificationClaim.ALREADY_CLAIMED);
            assertThat(claimHolderOf(generated.batchId()))
                    .as("and the refusal changed nothing, so the claim is still the first "
                            + "notifier's to release")
                    .isEqualTo(first);
        }

        @Test
        void releasing_the_claim_should_let_a_later_notifier_take_the_batch_up() {
            final RegisterBatch generated = walkedToGenerated(inserted(MONDAY));
            final UUID first = UUID.randomUUID();
            repository.claimForNotification(generated.batchId(), first);

            assertThat(repository.releaseNotificationClaim(generated.batchId(), first))
                    .as("the notifier that finished gives the claim back")
                    .isTrue();
            assertThat(claimHolderOf(generated.batchId()))
                    .as("and the row holds neither half of it, because the two are one fact")
                    .isNull();
            assertThat(repository.claimForNotification(generated.batchId(), UUID.randomUUID()))
                    .as("so an operator's resend of a PARTIALLY_NOTIFIED batch can have it")
                    .isEqualTo(NotificationClaim.CLAIMED);
        }

        /**
         * A notifier whose claim was reclaimed under it releases nothing.
         *
         * <p>What it would be giving back is the claim the notifier that took over is relying on,
         * and the run that has to be told the lease expired is the one whose result was just
         * written - not the one that has finished.
         */
        @Test
        void releasing_under_a_token_that_is_not_the_holders_should_change_nothing() {
            final RegisterBatch generated = walkedToGenerated(inserted(MONDAY));
            final UUID holder = UUID.randomUUID();
            repository.claimForNotification(generated.batchId(), holder);

            assertThat(repository.releaseNotificationClaim(
                    generated.batchId(), UUID.randomUUID()))
                    .isFalse();
            assertThat(claimHolderOf(generated.batchId()))
                    .as("the holder's claim is untouched by somebody else's release")
                    .isEqualTo(holder);
        }

        /**
         * And the lease, which is the answer to a pod that died holding a claim.
         *
         * <p>A claim nothing can ever take is a batch no resend and no later run could pick
         * up. The expiry is decided by the database comparing its own {@code now()} against the
         * stored instant, never by a JVM clock reading, which is why this case waits rather than
         * writing an old timestamp: what it exercises is the statement's own predicate.
         */
        @Test
        void a_claim_past_its_lease_should_be_taken_over() throws InterruptedException {
            final RegisterBatch generated = walkedToGenerated(inserted(MONDAY));
            final UUID died = UUID.randomUUID();
            final UUID recovering = UUID.randomUUID();
            repository.claimForNotification(generated.batchId(), died);

            Thread.sleep(NOTIFIER_LEASE.plusMillis(150).toMillis());

            assertThat(repository.claimForNotification(generated.batchId(), recovering))
                    .as("the pod that held this claim is gone, and the batch still has Youth "
                            + "Offending Teams it owes a register")
                    .isEqualTo(NotificationClaim.CLAIMED);
            assertThat(claimHolderOf(generated.batchId()))
                    .as("and the claim is now the recovering notifier's, so the dead one's "
                            + "release would change nothing")
                    .isEqualTo(recovering);
        }

        /**
         * The claim kept alive by the notifier that holds it, and by nobody else.
         *
         * <p>A lease is a bound on work whose length the lease cannot know: how long telling one
         * batch's recipients takes depends on how many Youth Offending Teams the batch is addressed
         * to and on how patient notificationnotify is being tonight. So the notifier renews before
         * every POST and before every write about one, and the lease bounds one recipient's turn
         * rather than the whole batch.
         *
         * <p>Fenced on the token for the reason the release is: a renewal keyed on the batch alone
         * would let a notifier whose claim had already been taken over extend the claim of the
         * notifier that took it, and carry on posting believing the batch was still its own.
         */
        @Test
        void renewing_the_claim_should_extend_it_only_for_the_notifier_that_holds_it()
                throws InterruptedException {
            final RegisterBatch generated = walkedToGenerated(inserted(MONDAY));
            final UUID holder = UUID.randomUUID();
            repository.claimForNotification(generated.batchId(), holder);
            final OffsetDateTime taken = claimedSinceOf(generated.batchId());
            Thread.sleep(RENEWAL_GAP.toMillis());

            assertThat(repository.renewNotificationClaim(generated.batchId(), holder))
                    .as("the notifier that holds the claim is still the one telling this batch's "
                            + "recipients, and the lease has to cover the POST it is about to make")
                    .isTrue();
            assertThat(claimedSinceOf(generated.batchId()))
                    .as("and the lease now runs from this moment rather than from the moment the "
                            + "claim was taken, which is what makes it a bound on one recipient's "
                            + "turn instead of on a whole batch of them")
                    .isAfter(taken);

            final OffsetDateTime renewed = claimedSinceOf(generated.batchId());
            assertThat(repository.renewNotificationClaim(
                    generated.batchId(), UUID.randomUUID()))
                    .as("and a notifier that does not hold it is told so by the same statement "
                            + "that would have extended it")
                    .isFalse();
            assertThat(claimedSinceOf(generated.batchId()))
                    .as("having changed nothing: a renewal under somebody else's token would "
                            + "extend the claim of the notifier that holds the batch")
                    .isEqualTo(renewed);
        }

        /**
         * And the takeover is what makes the fence matter rather than a formality.
         *
         * <p>The notifier that lost the claim is still inside its cycle, holding a token the row no
         * longer carries. Its next renewal is refused, which is how it learns to stop: a renewal it
         * was granted would extend the lease of the notifier that took the batch over while the old
         * one went on posting under it, which is two notifiers telling one batch's Youth Offending
         * Teams - the state the claim exists to prevent.
         */
        @Test
        void a_renewal_after_a_takeover_should_change_nothing_for_the_old_token()
                throws InterruptedException {
            final RegisterBatch generated = walkedToGenerated(inserted(MONDAY));
            final UUID lapsed = UUID.randomUUID();
            final UUID recovering = UUID.randomUUID();
            repository.claimForNotification(generated.batchId(), lapsed);
            Thread.sleep(NOTIFIER_LEASE.plusMillis(150).toMillis());
            repository.claimForNotification(generated.batchId(), recovering);
            final OffsetDateTime takenOver = claimedSinceOf(generated.batchId());

            assertThat(repository.renewNotificationClaim(generated.batchId(), lapsed))
                    .as("the batch is not this notifier's any more, and it is still in the middle "
                            + "of its cycle: this answer is how it finds out")
                    .isFalse();
            assertThat(claimHolderOf(generated.batchId()))
                    .as("the claim is the recovering notifier's")
                    .isEqualTo(recovering);
            assertThat(claimedSinceOf(generated.batchId()))
                    .as("and its lease was not extended by the notifier it took the batch from")
                    .isEqualTo(takenOver);
        }

        /**
         * A batch this store never assembled is not a batch somebody else is telling.
         *
         * <p>Nought rows changed carried both readings and the caller had to pick one before it
         * could know which: contention, which is an ordinary night, or a caller acting on a
         * correlation nothing was ever assembled under, which is the not-found failure. Counting
         * the second as the first put a lost correlation into the reading a claim nobody can take
         * is chased by, and reported a batch that does not exist as a batch somebody is busy with.
         *
         * <p>The existence read is made inside the same short transaction, under the same advisory
         * lock, so the two answers are two readings of one moment rather than of two.
         */
        @Test
        void claiming_a_batch_this_store_never_assembled_should_say_there_is_no_such_batch() {
            assertThat(repository.claimForNotification(UUID.randomUUID(), UUID.randomUUID()))
                    .as("no notifier is telling this batch's recipients, because there are no "
                            + "recipients and no batch: this is the caller's correlation being "
                            + "wrong and not a claim it lost")
                    .isEqualTo(NotificationClaim.ABSENT);
        }
    }

    /**
     * The listing behind {@code list-batches --date D}, which is the one read here asked by a date.
     *
     * <p>A support call is about a date, and the identities of that date's batches are exactly what
     * the caller is asking to be told - so this is the only read in this class not asked by the
     * identity every outcome is attributed by, and the only one whose <em>order</em> is part of what
     * it answers.
     *
     * <p>Court house then identity, and the order is the statement's rather than the listing's:
     * output a person compares between two runs has to be stable, and a planner free to choose would
     * reorder a date's several batches for one court house between one run and the next.
     */
    @Nested
    @DisplayName("the batches of one register date")
    class DateListing {

        @Test
        void a_dates_batches_should_come_back_court_house_then_identity() {
            // Written in none of the orders the read must answer in: the later court house first,
            // and the later identity of the earlier court house before it.
            final RegisterBatch otherHouse = failed(UUID.randomUUID(), OTHER_COURT_HOUSE);
            final RegisterBatch later = failed(LATER_IDENTITY, COURT_HOUSE);
            final RegisterBatch earlier = listed(EARLIER_IDENTITY, COURT_HOUSE);

            assertThat(findByRegisterDate(MONDAY))
                    .as("a person reads a national date a court house at a time, and the identity "
                            + "breaks the tie a date's several batches for one court house would "
                            + "otherwise leave to the planner")
                    .containsExactly(earlier, later, otherHouse);
        }

        /**
         * <strong>[A] characterisation.</strong> The read already answers this way and this case
         * states it rather than driving it: {@code ORDER BY court_house} is ascending, and
         * ascending in Postgres is NULLS LAST. What was missing is that the rule the statement's
         * javadoc, this nest's and data-model.md all state was held down by nothing at all - every
         * case here seeded a named court house - so a rewrite to {@code NULLS FIRST} would have
         * inverted it and stayed green. Green on introduction; non-vacuity is the mutation quoted
         * in this commit.
         */
        @Test
        void a_batch_that_cannot_say_where_it_sat_should_come_last() {
            final RegisterBatch nowhere = failed(NO_COURT_HOUSE_IDENTITY, null);
            final RegisterBatch somewhere = listed(A_COURT_HOUSE_IDENTITY, COURT_HOUSE);

            assertThat(findByRegisterDate(MONDAY))
                    .as("`court_house` is nullable and descriptive - it is copied from the first "
                            + "record's hearing venue, and a document that named none leaves it "
                            + "unset - so a listing read by court house has to put that row "
                            + "somewhere and says where: last, after every row that can say. Its "
                            + "identity is the earlier of the two, so a read that fell back to the "
                            + "tie-break alone would answer it first")
                    .containsExactly(somewhere, nowhere);
        }

        @Test
        void another_dates_batches_should_not_be_among_them() {
            final RegisterBatch monday = listed(UUID.randomUUID(), COURT_HOUSE);
            repository.insert(assembled(TUESDAY));

            assertThat(findByRegisterDate(MONDAY))
                    .as("the operator asked about one date; the next day's batch listed beside it "
                            + "is a state they would act on for a register nobody was asking about")
                    .containsExactly(monday);
        }

        @Test
        void a_date_this_service_assembled_nothing_for_should_be_listed_as_holding_nothing() {
            listed(UUID.randomUUID(), COURT_HOUSE);

            assertThat(findByRegisterDate(WEDNESDAY))
                    .as("a date nothing was assembled for holds nothing to list, and the command "
                            + "says so rather than falling back to whatever the table does hold")
                    .isEmpty();
        }
    }

    /**
     * One batch of this case's court centre for the Monday, inserted and left at PENDING.
     *
     * @param batchId    the identity the listing's tie-break is decided on
     * @param courtHouse the court house the listing is ordered by
     * @return the batch as the listing should return it
     */
    private RegisterBatch listed(final UUID batchId, final String courtHouse) {
        final RegisterBatch pending = new RegisterBatch(batchId, courtCentre, OU_CODE, courtHouse,
                MONDAY, fileName(MONDAY), null, null, BatchStatus.PENDING, null, null, true, null,
                ASSEMBLED_AT, null, null, null, null, 0, null, 0);
        repository.insert(pending);
        return pending;
    }

    /**
     * The same batch failed under a reason nobody outside this service reported.
     *
     * <p>Terminal, so the date may hold more than one of them: {@code idx_register_batch_live_key}
     * admits a single PENDING, GENERATING or GENERATED batch per court centre and date, and a
     * listing of one batch could not say what it is ordered by.
     *
     * @param batchId    the identity the listing's tie-break is decided on
     * @param courtHouse the court house the listing is ordered by
     * @return the batch as the listing should return it
     */
    private RegisterBatch failed(final UUID batchId, final String courtHouse) {
        final RegisterBatch pending = listed(batchId, courtHouse);
        final RegisterBatch failed = new RegisterBatch(batchId, courtCentre, OU_CODE, courtHouse,
                MONDAY, pending.fileName(), null, null, BatchStatus.FAILED,
                BatchFailureReason.RENDER_REQUEST_REJECTED, null, true, null, ASSEMBLED_AT, null,
                null, null, FAILED_AT, 0, null, 0);
        repository.compareAndSet(failed, BatchStatus.PENDING);
        return failed;
    }

    /**
     * The date listing, narrowed to the case that asked.
     *
     * <p>Made through {@code assertThatCode} for the reason {@link #pendingSince(Instant)} is: a
     * seam's refusal is recorded as a failing assertion rather than as the exception it is.
     *
     * @param registerDate the register date whose batches are wanted
     * @return this case's batches for that date, in the order the statement answered
     */
    private List<RegisterBatch> findByRegisterDate(final LocalDate registerDate) {
        final AtomicReference<List<RegisterBatch>> answered = new AtomicReference<>(List.of());
        assertThatCode(() -> answered.set(repository.findByRegisterDate(registerDate)))
                .as("the operations CLI's listing implements this read; this is its red run")
                .doesNotThrowAnyException();
        return mine(answered.get());
    }

    /** A batch inserted and left at PENDING, for the cases that then walk it forward. */
    private RegisterBatch inserted(final LocalDate registerDate) {
        final RegisterBatch assembled = assembled(registerDate);
        repository.insert(assembled);
        return assembled;
    }

    /**
     * The same batch walked to GENERATED through the state machine, which is where it is notified
     * from.
     *
     * <p>Walked rather than written there: a fixture that inserted the end state would be the one
     * caller for which the compare-and-set rules did not hold.
     */
    private RegisterBatch walkedToGenerated(final RegisterBatch assembled) {
        final RegisterBatch generating = generating(assembled, payloadFileId, REQUESTED_AT);
        repository.compareAndSet(generating, BatchStatus.PENDING);
        final RegisterBatch document = generated(generating);
        repository.compareAndSet(document, BatchStatus.GENERATING);
        return document;
    }

    /**
     * When the batch's claim last started running, read off the column the domain has not got.
     *
     * <p>Read rather than computed: the lease is compared by the database against its own
     * {@code now()}, so what a renewal has to be shown to move is the stamp the database wrote.
     *
     * @param batchId the batch
     * @return the instant the claim's lease currently runs from, or {@code null} where nobody holds
     *     one
     */
    private static OffsetDateTime claimedSinceOf(final UUID batchId) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("SELECT notifying_since FROM register_batch WHERE batch_id = :batchId")
                .param("batchId", batchId)
                .query(OffsetDateTime.class)
                .optional()
                .orElse(null);
    }

    /** Who holds the batch's notification claim, read off the two columns the domain has not got. */
    private static UUID claimHolderOf(final UUID batchId) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT notifier_token
                          FROM register_batch
                         WHERE batch_id = :batchId AND notifying_since IS NOT NULL
                        """)
                .param("batchId", batchId)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    /** A batch in the state assembly leaves it in: a key, a file name, and ten empty columns. */
    private RegisterBatch assembled(final LocalDate registerDate) {
        return new RegisterBatch(UUID.randomUUID(), courtCentre, OU_CODE, COURT_HOUSE, registerDate,
                fileName(registerDate), null, null, BatchStatus.PENDING, null, null, true, null,
                ASSEMBLED_AT, null, null, null, null, 0, null, 0);
    }

    /** The same batch after systemdocgenerator accepted its render request. */
    private RegisterBatch generating(final RegisterBatch batch, final UUID payloadFileId,
            final Instant requestedAt) {
        return new RegisterBatch(batch.batchId(), courtCentre, OU_CODE, COURT_HOUSE,
                batch.registerDate(), batch.fileName(), payloadFileId, null, BatchStatus.GENERATING,
                null, null, true, null, ASSEMBLED_AT, requestedAt, null, null, null, 1, null, 0);
    }

    /** The same batch again once systemdocgenerator's document exists. */
    private RegisterBatch generated(final RegisterBatch batch) {
        return new RegisterBatch(batch.batchId(), courtCentre, OU_CODE, COURT_HOUSE,
                batch.registerDate(), batch.fileName(), payloadFileId, DOCUMENT_FILE_ID,
                BatchStatus.GENERATED, null, null, true, CompletedBy.EVENT,
                ASSEMBLED_AT, REQUESTED_AT, GENERATED_AT, null, null, 1, null, 0);
    }

    /**
     * An inserted batch whose payload id was minted and whose render request was never recorded.
     *
     * <p>The mint is a statement of {@link JdbcRegisterStore}'s rather than this repository's, and
     * it is not a transition, so it is written here directly: a compare-and-set from PENDING to
     * PENDING is a move to itself and the state machine refuses it.
     *
     * @param registerDate the day the batch is for, which with the court centre is its key
     * @param payload      the payload id the run minted before the file-service write
     * @param assembledAt  when the batch was stamped onto its rows
     * @return the batch as the stale-PENDING read should return it
     */
    private RegisterBatch minted(final LocalDate registerDate, final UUID payload,
            final Instant assembledAt) {
        final RegisterBatch assembled = new RegisterBatch(UUID.randomUUID(), courtCentre, OU_CODE,
                COURT_HOUSE, registerDate, fileName(registerDate), null, null, BatchStatus.PENDING,
                null, null, true, null, assembledAt, null, null, null, null, 0, null, 0);
        repository.insert(assembled);
        ProcessedLogTestSupport.jdbcClient()
                .sql("UPDATE register_batch SET payload_file_id = :payloadFileId "
                        + "WHERE batch_id = :batchId")
                .param("payloadFileId", payload)
                .param("batchId", assembled.batchId())
                .update();
        return new RegisterBatch(assembled.batchId(), courtCentre, OU_CODE, COURT_HOUSE,
                registerDate, assembled.fileName(), payload, null, BatchStatus.PENDING, null, null,
                true, null, assembledAt, null, null, null, null, 0, null, 0);
    }

    /**
     * The stale-PENDING read, narrowed to the case that asked.
     *
     * <p>Made through {@code assertThatCode} so that a seam's refusal is recorded as a failing
     * assertion rather than as the exception it is, which is what the red-run convention asks of a
     * case written against a seam.
     *
     * @param cutoff the far edge of the age window: older than this is late
     * @return this case's stale PENDING batches, oldest first
     */
    private List<RegisterBatch> pendingSince(final Instant cutoff) {
        final AtomicReference<List<RegisterBatch>> answered = new AtomicReference<>(List.of());
        assertThatCode(() -> answered.set(repository.pendingSince(cutoff)))
                .as("the stale-PENDING sweep implements this read; this is its red run")
                .doesNotThrowAnyException();
        return mine(answered.get());
    }

    /**
     * An inserted batch walked to GENERATED and left there, which is the parked shape.
     *
     * <p>Walked rather than written, exactly as the notification suite's fixture is: a batch enters
     * this table at PENDING and is moved by compare-and-set through the state machine, and a fixture
     * that wrote GENERATED directly would be the one caller for which those rules did not hold.
     *
     * @param registerDate the day the batch is for, which with the court centre is its key
     * @param payload      the payload the render was asked for
     * @param generatedAt  when its document arrived, which is what the age is measured from
     * @return the batch as the parked read should return it
     */
    private RegisterBatch parked(final LocalDate registerDate, final UUID payload,
            final Instant generatedAt) {
        final RegisterBatch requested = requested(registerDate, payload, REQUESTED_AT);
        final RegisterBatch parked = new RegisterBatch(requested.batchId(), courtCentre, OU_CODE,
                COURT_HOUSE, registerDate, requested.fileName(), payload, DOCUMENT_FILE_ID,
                BatchStatus.GENERATED, null, null, true, CompletedBy.EVENT, ASSEMBLED_AT,
                REQUESTED_AT, generatedAt, null, null, 1, null, 0);
        repository.compareAndSet(parked, BatchStatus.GENERATING);
        return parked;
    }

    /**
     * The parked read, narrowed to the case that asked.
     *
     * <p>Made through {@code assertThatCode} for the reason {@link #pendingSince(Instant)} is: a
     * seam's refusal is recorded as a failing assertion rather than as the exception it is.
     *
     * @param cutoff the far edge of the age window: older than this is late
     * @return this case's parked batches, oldest first
     */
    private List<RegisterBatch> generatedSince(final Instant cutoff) {
        final AtomicReference<List<RegisterBatch>> answered = new AtomicReference<>(List.of());
        assertThatCode(() -> answered.set(repository.generatedSince(cutoff)))
                .as("the parked-at-GENERATED reading implements this read; this is its red run")
                .doesNotThrowAnyException();
        return mine(answered.get());
    }

    /** An inserted batch already GENERATING, which is the state the overdue read answers on. */
    private RegisterBatch requested(final LocalDate registerDate, final UUID payloadFileId,
            final Instant requestedAt) {
        final RegisterBatch assembled = assembled(registerDate);
        repository.insert(assembled);
        final RegisterBatch requested = generating(assembled, payloadFileId, requestedAt);
        repository.compareAndSet(requested, BatchStatus.PENDING);
        return requested;
    }

    /**
     * The overdue read, narrowed to the case that asked.
     *
     * <p>The read answers over the whole table, because a reading of the service's in-flight work
     * is about the whole service; the suites sharing one container are not, so the case that asked
     * is the only one that may be asserted on.
     */
    private List<RegisterBatch> mine(final List<RegisterBatch> batches) {
        return batches.stream()
                .filter(batch -> courtCentre.equals(batch.courtCentreId()))
                .toList();
    }

    private static String fileName(final LocalDate registerDate) {
        return "court-register_" + registerDate + '_' + OU_CODE + ".pdf";
    }
}
