package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.BatchStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CompletedBy;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.NotificationStatus;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterBatch;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RegisterNotification;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreRefusedRowException;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;

/**
 * The {@code register_notification} statements, against a real Postgres.
 *
 * <p>These four statements are the whole of what this service can honestly say about an e-mail it
 * sent. The row is minted PENDING <em>before</em> the POST and settled after it, so an attempt that
 * was never answered is a row saying so rather than an absence indistinguishable from one that was
 * never made - the same discipline 001 applies to {@code processed_output.request_digest}, and the
 * reason the identity in the row is the one that goes in the path of
 * {@code POST /notifications/{notificationId}}: a retry under a fresh identity would send a Youth
 * Offending Team a second copy of a register about children.
 *
 * <p>Nothing else exercises them. The notification service and the resend CLI mock the repository,
 * so a column dropped from the insert would first be noticed by a resend that could not tell which
 * recipients had already been told.
 *
 * <p><strong>The typed nulls.</strong> {@code response_code} is empty on a row minted before its
 * POST and on a row whose POST reached no verdict at all, because a connect failure has no status
 * line. {@code sent_at} is empty on the minted row only: it is the settlement instant of every
 * terminal attempt, so the service stamps it on a refusal as readily as on an acceptance. Both
 * statements are exercised with the pair absent as well as present, because the statement has to
 * bind an absent one whichever row shape asked for it.
 *
 * <p>{@code SchemaMigrationV2IT} pins {@code UNIQUE (batch_id, email_address)} as a fact about the
 * table. It is asserted again here, through the repository's own insert, because what matters to a
 * caller is that the refusal reaches it: a second attempt at an address is a second e-mail, and a
 * repository that absorbed the refusal would turn defect fix P4 into a duplicate nobody sees. What
 * reaches the caller is the domain's own {@code StoreRefusedRowException} carrying this repository's
 * bounded words, because the driver's are about the key it refused and that key is an e-mail
 * address (constitution Principle VII).
 *
 * <p>Every case mints its own batch, so the suites sharing one container share no rows.
 */
@DisplayName("register_notification repository")
class RegisterNotificationRepositoryIT {

    /** One POST made for the row, which is what a settlement adds to the attempt total. */
    private static final int ONE_POST = 1;

    /** A call that spent two of its attempt budget before it settled the row. */
    private static final int TWO_POSTS = 2;

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);
    private static final Instant ASSEMBLED_AT = Instant.parse("2026-08-24T17:00:00Z");
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-24T17:00:04Z");
    private static final Instant GENERATED_AT = Instant.parse("2026-08-24T17:04:11Z");

    private static final UUID PAYLOAD_FILE_ID =
            UUID.fromString("5e08b6d1-92a7-4c33-8f10-6b4d3e79a281");
    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("3a7f1c92-6d84-4b05-9e73-1c2b8a4e07d5");
    private static final Instant SENT_AT = Instant.parse("2026-08-24T17:04:22Z");

    private static final UUID TEMPLATE_ID =
            UUID.fromString("8f2d1a05-4b6c-4e37-9d18-52a7c0e3b964");

    private static final String TEMPLATE_NAME = "cr_standard";
    private static final String OU_CODE = "B01LY00";
    private static final String COURT_HOUSE = "Lavender Hill Youth Court";

    private static final String WANDSWORTH = "wandsworth.yot@example.gov.uk";
    private static final String LAMBETH = "lambeth.yot@example.gov.uk";

    /** The team whose row was minted and never settled, which a resend is also owed. */
    private static final String MERTON = "merton.yot@example.gov.uk";

    private static final int ACCEPTED = 202;

    /** notificationnotify could not take the command; a late attempt that answered this. */
    private static final int UNAVAILABLE = 503;

    /** A second settlement instant, later than the first, so an overwrite would be visible. */
    private static final Instant LATER = Instant.parse("2026-08-24T17:09:38Z");

    /** The claim lease this suite's batch repository is built with; no case here takes a claim. */
    private static final Duration NOTIFIER_LEASE = Duration.ofMinutes(10);

    private final UUID courtCentre = UUID.randomUUID();

    private final RegisterBatchRepository batches =
            new RegisterBatchRepository(ProcessedLogTestSupport.jdbcClient(),
                    ProcessedLogTestSupport.transactions(), NOTIFIER_LEASE);

    private final RegisterNotificationRepository repository =
            new RegisterNotificationRepository(ProcessedLogTestSupport.jdbcClient());

    /** This case's batch: the parent the foreign key requires, minted per test. */
    private final UUID batchId = UUID.randomUUID();

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    @Nested
    @DisplayName("minting a recipient's row before its POST")
    class Minting {

        @Test
        void minting_a_recipient_should_read_back_the_row_the_post_will_go_out_under() {
            seededBatch();
            final RegisterNotification pending = pending(WANDSWORTH, "Wandsworth YOT");

            repository.insert(pending);

            assertThat(repository.findByBatchId(batchId))
                    .as("what was attempted is the evidence: the identity, the address and the "
                            + "template are on record before anything is sent, and the two "
                            + "settlement columns are empty because nothing has answered yet")
                    .containsExactly(pending);
        }

        @Test
        void minting_a_recipient_without_a_name_should_keep_the_row_the_subscription_offered() {
            seededBatch();
            final RegisterNotification anonymous = pending(WANDSWORTH, null);

            repository.insert(anonymous);

            assertThat(repository.findByBatchId(batchId))
                    .as("a subscription may carry an address and nothing else, and an e-mail with "
                            + "no yotsName is still an e-mail that has to be recorded")
                    .containsExactly(anonymous);
        }

        @Test
        void minting_a_second_row_for_an_address_already_told_should_be_refused() {
            seededBatch();
            repository.insert(pending(WANDSWORTH, "Wandsworth YOT"));

            assertThatThrownBy(() -> repository.insert(pending(WANDSWORTH, "Wandsworth YOT")))
                    .as("the persistence half of defect fix P4: the union is computed once at "
                            + "assembly, and the database refuses a second attempt at the same "
                            + "address for the same batch rather than sending it twice")
                    .isInstanceOf(StoreRefusedRowException.class)
                    .hasMessageContaining("register_notification_unique_recipient")
                    .hasMessageContaining(batchId.toString());
        }

        /**
         * The refusal a race actually produces, and what it may not carry.
         *
         * <p>The operator's resend and the outcome sink can reach one batch at the same time, so
         * losing this key is an ordinary night rather than a defect - and the loser reads back the
         * row that won. Postgres reports the violation with a detail line quoting the colliding
         * key's values, one of which is the recipient's address: a component that may never reach a
         * log line at INFO or above, a metric label or the estate's log index (constitution
         * Principle VII), and an unhandled exception's stack trace is all three. So what reaches the
         * caller is this repository's own bounded words - the rule that refused the row and the
         * batch it was for - and the driver's are dropped with the cause.
         */
        @Test
        void the_refusal_should_name_the_rule_and_never_the_address_it_was_refused_for() {
            seededBatch();
            repository.insert(pending(WANDSWORTH, "Wandsworth YOT"));

            assertThatThrownBy(() -> repository.insert(pending(WANDSWORTH, "Wandsworth YOT")))
                    .as("an address in an exception message is an address in every log index the "
                            + "estate ships it to, and every defendant on the register it is about "
                            + "is a child")
                    .hasMessageNotContaining(WANDSWORTH)
                    .hasMessageNotContaining("Wandsworth YOT")
                    .as("the driver's detail line quotes the key it refused, so the cause is not "
                            + "attached either - which rule said no is in the message instead")
                    .hasNoCause();
        }
    }

    @Nested
    @DisplayName("reading a batch's recipients")
    class Reading {

        @Test
        void reading_a_batchs_recipients_should_answer_in_the_order_the_addresses_read() {
            seededBatch();
            final RegisterNotification wandsworth = pending(WANDSWORTH, "Wandsworth YOT");
            final RegisterNotification lambeth = pending(LAMBETH, "Lambeth YOT");
            repository.insert(wandsworth);
            repository.insert(lambeth);

            assertThat(repository.findByBatchId(batchId))
                    .as("ordered by address rather than by insertion, so a run report and a resend "
                            + "list a person compares by eye come back the same way twice")
                    .containsExactly(lambeth, wandsworth);
        }

        @Test
        void reading_the_resendable_recipients_should_answer_with_every_row_never_accepted() {
            seededBatch();
            final RegisterNotification accepted = pending(WANDSWORTH, "Wandsworth YOT");
            final RegisterNotification refused = pending(LAMBETH, "Lambeth YOT");
            final RegisterNotification abandoned = pending(MERTON, "Merton YOT");
            repository.insert(accepted);
            repository.insert(refused);
            repository.insert(abandoned);
            repository.update(settled(accepted, NotificationStatus.ACCEPTED, ACCEPTED, SENT_AT),
                    ONE_POST);
            final RegisterNotification failed =
                    settled(refused, NotificationStatus.FAILED, null, null);
            repository.update(failed, ONE_POST);

            assertThat(repository.findUnsettledByBatchId(batchId))
                    .as("a resend attempts the recipients that were not told and nobody else, and "
                            + "a row still PENDING is one of those: the run that minted it stopped "
                            + "between the POST and the settlement, so the team is owed its e-mail "
                            + "exactly as a refused one is, while re-sending an accepted row would "
                            + "deliver the register twice")
                    .containsExactly(withAttempts(failed, ONE_POST), abandoned);
        }

        @Test
        void reading_a_batch_nobody_subscribes_to_should_answer_with_nothing() {
            seededBatch();

            assertThat(repository.findByBatchId(batchId))
                    .as("no recipients is an answer rather than an error - it is what settles the "
                            + "batch NOTIFIED_NOBODY, which is defect fix P1")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("settling a recipient on what notificationnotify answered")
    class Settling {

        @Test
        void settling_an_accepted_recipient_should_write_the_status_line_and_the_instant() {
            seededBatch();
            final RegisterNotification pending = pending(WANDSWORTH, "Wandsworth YOT");
            repository.insert(pending);
            final RegisterNotification accepted =
                    settled(pending, NotificationStatus.ACCEPTED, ACCEPTED, SENT_AT);

            assertThat(repository.update(accepted, ONE_POST))
                    .as("what the statement did is the decision, and never a read-back")
                    .isEqualTo(NotificationSettlement.APPLIED);
            assertThat(repository.findByBatchId(batchId))
                    .as("202 and nothing else is acceptance, and the row says which attempt under "
                            + "this identity it was")
                    .containsExactly(withAttempts(accepted, ONE_POST));
        }

        @Test
        void settling_a_recipient_nothing_answered_for_should_record_no_status_and_no_instant() {
            seededBatch();
            final RegisterNotification pending = pending(WANDSWORTH, "Wandsworth YOT");
            repository.insert(pending);
            final RegisterNotification failed =
                    settled(pending, NotificationStatus.FAILED, null, null);

            assertThat(repository.update(failed, ONE_POST))
                    .isEqualTo(NotificationSettlement.APPLIED);

            assertThat(repository.findByBatchId(batchId))
                    .as("the statement binds both settlement columns absent, which is the shape "
                            + "a minted row has and the shape a caller with no status line to "
                            + "record asks for; an invented status would say an attempt was "
                            + "answered when nothing answered at all")
                    .containsExactly(withAttempts(failed, ONE_POST));
        }

        /**
         * ACCEPTED is terminal at the row level, and this is the statement that makes it so - but
         * only for the settlement, never for the tally.
         *
         * <p>Two mechanisms can reach one generated batch at the same moment - the outcome sink on
         * a delivered {@code document-available} and an operator's resend - so a run can read a row
         * as unsettled, POST for it, and only then find that the other run's POST was accepted in
         * between. An unconditional settlement would write FAILED over that ACCEPTED row: the team
         * that has been told reads as untold, the batch goes back to PARTIALLY_NOTIFIED, and the
         * resend that follows sends a register about children to a team that already has it. So the
         * three settlement columns are conditional on the row not already being ACCEPTED.
         *
         * <p><strong>{@code attempts} is not.</strong> The POST was really made, and what the column
         * accumulates is the POSTs made for the row, so leaving it out omitted from the lifetime
         * total exactly the attempts made in the window the fence exists for - the window support
         * reaches for the count in. A row POSTed for by two notifiers reads as one notifier's work.
         */
        @Test
        void a_late_failure_against_an_accepted_row_should_be_tallied_and_not_settled() {
            seededBatch();
            final RegisterNotification pending = pending(WANDSWORTH, "Wandsworth YOT");
            repository.insert(pending);
            final RegisterNotification accepted =
                    settled(pending, NotificationStatus.ACCEPTED, ACCEPTED, SENT_AT);
            repository.update(accepted, ONE_POST);

            assertThat(repository.update(
                    settled(pending, NotificationStatus.FAILED, UNAVAILABLE, LATER), ONE_POST))
                    .as("a team that has been told has been told: the write that would demote its "
                            + "row is refused by the statement rather than by the caller "
                            + "remembering to check, and the answer says the POSTs were tallied "
                            + "rather than that the row was settled")
                    .isEqualTo(NotificationSettlement.ATTEMPTS_ONLY);
            assertThat(repository.findByBatchId(batchId))
                    .as("the settlement did not move - not the status, not the status line and not "
                            + "the instant - and the attempt did, because it was really made")
                    .containsExactly(withAttempts(accepted, ONE_POST + ONE_POST));
        }

        /**
         * And a late acceptance is tallied and not re-settled either, on its own answer.
         *
         * <p>The worse of the two windows: both notifiers got a 202 for one recipient, so the Youth
         * Offending Team holds two copies of a register about children. The row keeps the first
         * acceptance - its status line and the instant it was settled at are the ones this service
         * can evidence, and re-stamping them with a second sender's would make the row describe an
         * attempt whose e-mail is indistinguishable from the first - and the second POST is on the
         * total. The distinct answer is what lets the caller count the two windows apart.
         */
        @Test
        void a_late_acceptance_against_an_accepted_row_should_be_tallied_and_not_re_settled() {
            seededBatch();
            final RegisterNotification pending = pending(WANDSWORTH, "Wandsworth YOT");
            repository.insert(pending);
            final RegisterNotification accepted =
                    settled(pending, NotificationStatus.ACCEPTED, ACCEPTED, SENT_AT);
            repository.update(accepted, ONE_POST);

            assertThat(repository.update(
                    settled(pending, NotificationStatus.ACCEPTED, ACCEPTED, LATER), ONE_POST))
                    .as("an acceptance onto an accepted row is not a loss and is not a settlement "
                            + "either: the row already says what that write was going to say, so "
                            + "what the statement did is tally the POST")
                    .isEqualTo(NotificationSettlement.ATTEMPTS_ONLY);
            assertThat(repository.findByBatchId(batchId))
                    .as("the row keeps the settlement instant of the attempt this service can "
                            + "evidence, and carries both POSTs")
                    .containsExactly(withAttempts(accepted, ONE_POST + ONE_POST));
        }

        /**
         * The attempt total is the statement's arithmetic, not the caller's.
         *
         * <p>Two runs that each read the row at nought and each write an absolute total both write
         * the same number, so one run's attempts are simply lost: a row that was POSTed for four
         * times reads as two, and the count support uses to tell an exhausted budget from a route
         * that stopped reaching the command endpoint is wrong in the direction that hides work.
         * The column accumulates the POSTs made for the row, so the number added is the number this
         * call made and the total is computed where the row is.
         */
        @Test
        void two_settlements_computed_from_one_read_should_each_add_their_own_attempts() {
            seededBatch();
            final RegisterNotification pending = pending(WANDSWORTH, "Wandsworth YOT");
            repository.insert(pending);

            repository.update(
                    settled(pending, NotificationStatus.FAILED, UNAVAILABLE, SENT_AT), ONE_POST);
            repository.update(
                    settled(pending, NotificationStatus.FAILED, UNAVAILABLE, LATER), TWO_POSTS);

            assertThat(repository.findByBatchId(batchId))
                    .as("three POSTs were made for this team, and both callers read the row at "
                            + "nought: an absolute write would have recorded the second one's two "
                            + "and thrown the first one's away")
                    .extracting(RegisterNotification::attempts)
                    .containsExactly(ONE_POST + TWO_POSTS);
        }

        /**
         * The tally without the settlement, which is what a lost claim leaves owing.
         *
         * <p>A notifier whose claim is taken over between its POST and the settlement that would
         * have recorded it may not write a status: the notifier that now holds the batch is
         * deriving the same owed set from the same records, and a settlement from here would be
         * written over its work. The POST was still really made, so the row's lifetime total moves
         * and nothing else does - not the status, not the status line, not the settlement instant.
         */
        @Test
        void tallying_the_posts_a_lost_claim_made_should_move_the_total_and_nothing_else() {
            seededBatch();
            final RegisterNotification pending = pending(WANDSWORTH, "Wandsworth YOT");
            repository.insert(pending);
            final RegisterNotification failed =
                    settled(pending, NotificationStatus.FAILED, UNAVAILABLE, SENT_AT);
            repository.update(failed, ONE_POST);

            assertThat(repository.tallyAttempts(pending.notificationId(), TWO_POSTS))
                    .as("the store holds the row, so the POSTs a notifier spent before it lost the "
                            + "batch are on it")
                    .isTrue();
            assertThat(repository.findByBatchId(batchId))
                    .as("attempts and nothing else: the verdict the row carries is the one the last "
                            + "settlement wrote, because a run that has lost the claim may not say "
                            + "how an attempt ended - only that it was made")
                    .containsExactly(withAttempts(failed, ONE_POST + TWO_POSTS));
        }

        @Test
        void tallying_for_a_row_the_store_does_not_hold_should_say_there_is_no_such_row() {
            seededBatch();
            final RegisterNotification absent = pending(WANDSWORTH, "Wandsworth YOT");

            assertThat(repository.tallyAttempts(absent.notificationId(), ONE_POST))
                    .as("a row this service posted under and the store no longer holds is the same "
                            + "fault the settlement's own ABSENT names, and it is answered rather "
                            + "than written silently into nowhere")
                    .isFalse();
        }

        @Test
        void settling_a_recipient_this_service_never_minted_should_say_there_is_no_such_row() {
            seededBatch();
            final RegisterNotification absent = pending(WANDSWORTH, "Wandsworth YOT");

            assertThat(repository.update(absent, ONE_POST))
                    .as("an identity this service never sent under is a caller settling an attempt "
                            + "that was never made, and it is its own answer: a row count could not "
                            + "tell it from a row somebody else had accepted, because both changed "
                            + "nothing")
                    .isEqualTo(NotificationSettlement.ABSENT);
        }
    }

    /**
     * The batch the notification rows hang off, walked to GENERATED through its own repository.
     *
     * <p>Walked rather than inserted there. A batch enters {@code register_batch} at PENDING and is
     * moved from PENDING to GENERATING to GENERATED by compare-and-set through the state machine,
     * and a fixture that wrote the end state directly would be the one caller in the codebase for
     * which those rules did not hold - and would go on passing after a change that made them
     * unreachable for everybody else. GENERATED is the state a notification run reads its batch in,
     * which is why these cases want it.
     */
    private void seededBatch() {
        batches.insert(batchAt(BatchStatus.PENDING, null, null));
        batches.compareAndSet(batchAt(BatchStatus.GENERATING, PAYLOAD_FILE_ID, REQUESTED_AT),
                BatchStatus.PENDING);
        batches.compareAndSet(batchAt(BatchStatus.GENERATED, PAYLOAD_FILE_ID, REQUESTED_AT),
                BatchStatus.GENERATING);
    }

    /**
     * This suite's batch in one of the three states the walk above passes through.
     *
     * <p>The generated state names the mechanism that learned it, because a document exists only
     * because something reported it and the row is where which one is recorded: a GENERATED row
     * with no {@code completed_by} is contradictory rather than incomplete, and
     * {@code register_batch_completed_by_shape_chk} refuses it.
     */
    private RegisterBatch batchAt(final BatchStatus status, final UUID payloadFileId,
            final Instant requestedAt) {
        return new RegisterBatch(batchId, courtCentre, OU_CODE, COURT_HOUSE, MONDAY,
                "court-register_" + MONDAY + '_' + OU_CODE + ".pdf", payloadFileId,
                status == BatchStatus.GENERATED ? DOCUMENT_FILE_ID : null,
                status, null, null, true,
                status == BatchStatus.GENERATED ? CompletedBy.EVENT : null, ASSEMBLED_AT,
                requestedAt, status == BatchStatus.GENERATED ? GENERATED_AT : null, null, null,
                status == BatchStatus.PENDING ? 0 : 1, null, 0);
    }

    /** One recipient's row as it stands the moment before its POST is made. */
    private RegisterNotification pending(final String emailAddress, final String recipientName) {
        return new RegisterNotification(UUID.randomUUID(), batchId, emailAddress, recipientName,
                TEMPLATE_NAME, TEMPLATE_ID, NotificationStatus.PENDING, null, null, 0);
    }

    /**
     * The same row after the attempt ended, under the identity it was first attempted with.
     *
     * <p>{@code attempts} is carried through rather than incremented, exactly as
     * {@code RegisterNotifierService} carries it: how many POSTs a call made travels beside the row
     * and the total is the statement's arithmetic, so a caller that stated a total would be stating
     * one it read before another run wrote to the same column.
     */
    private static RegisterNotification settled(final RegisterNotification notification,
            final NotificationStatus status, final Integer responseCode, final Instant sentAt) {
        return new RegisterNotification(notification.notificationId(), notification.batchId(),
                notification.emailAddress(), notification.recipientName(),
                notification.templateName(), notification.templateId(), status, responseCode,
                sentAt, notification.attempts());
    }

    /** The row as the table then holds it, once the statement has added this call's POSTs. */
    private static RegisterNotification withAttempts(
            final RegisterNotification row, final int attempts) {
        return new RegisterNotification(row.notificationId(), row.batchId(), row.emailAddress(),
                row.recipientName(), row.templateName(), row.templateId(), row.status(),
                row.responseCode(), row.sentAt(), attempts);
    }
}
