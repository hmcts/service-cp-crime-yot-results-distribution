package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RequestFingerprint;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ProcessedLogTestSupport;

/**
 * The submission half of the processed log, against a real Postgres.
 *
 * <p>These statements are what makes an at-least-once delivery safe to submit from. The property
 * under test throughout is the affected-row count, exactly as it is for the request-level
 * repository: a claim that affects no rows means this hearing's register has already gone and must
 * not go again, and no amount of reading the row afterwards would make that decision safe under two
 * concurrent deliveries.
 *
 * <p>Where the informant service fans a request out across prosecuting authorities, the court
 * register produces exactly one document per hearing. There is no fan-out dimension to key on, so
 * the request itself is the key and the database enforces it: {@code UNIQUE (source, request_id)},
 * asserted here as the thing that would refuse a second register for one hearing rather than as a
 * line in a migration nobody re-reads.
 *
 * <p>Every case seeds its own {@code processed_request} parent, because {@code processed_output}
 * carries a foreign key to it. That is the schema saying what the design rules say: an output row is
 * evidence about a request, and evidence with nothing to be about is not evidence.
 */
@DisplayName("processed_output repository")
class ProcessedOutputRepositoryIT {

    private static final Duration LEASE = Duration.ofMinutes(5);

    /** The single row a fixture update is expected to touch. */
    private static final int ONE_ROW = 1;

    private static final UUID COURT_CENTRE = UUID.fromString("2f4a1c66-9d1e-4d3b-9a55-7c1a0f6b8e21");
    private static final String OU_CODE = "B01LY";
    private static final LocalDate REGISTER_DATE = LocalDate.of(2026, 8, 20);
    private static final String FILE_NAME = "court-register-B01LY-20260820.pdf";

    private static final String DIGEST =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final String OTHER_DIGEST =
            "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";

    /** Bounded reason-code counts, exactly as C19/C20/C27 count them: an enumeration and numbers. */
    private static final Map<TransformationAnomaly, Integer> ANOMALIES = Map.of(
            TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT, 1,
            TransformationAnomaly.LETTER_DELIVERY_DROPPED, 2);

    /** How the column holds them: sorted by code, so one set of counts has one representation. */
    private static final String ANOMALY_SUMMARY =
            "letter-delivery-dropped:2,unresolvable-youth-defendant:1";

    private static final int ACCEPTED = 202;
    private static final int REFUSED = 400;

    /** The state a register this service recorded is in, and the digest such a row carries. */
    private static final String RECORDED = "RECORDED";
    private static final String RECORDED_DIGEST =
            "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae";

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    private static ProcessedOutputRepository repository() {
        return new ProcessedOutputRepository(ProcessedLogTestSupport.jdbcClient());
    }

    /**
     * Seeds a request row, holding the claim, so the output row has the parent its foreign key
     * requires and the run claim its writes are fenced on.
     */
    private static RunClaim seededRequest() {
        final DistributionCommand command = ProcessedLogTestSupport.command();
        final RunClaim claim = new RunClaim(
                command.source(), command.requestId(), "runner-1", UUID.randomUUID(), "msg-1");
        ProcessedLogTestSupport.repository(LEASE)
                .insertNew(command, RequestFingerprint.of(command), claim);
        return claim;
    }

    private static ProcessedOutputClaim claimFor(final UUID outputId, final String digest) {
        return claimFor(outputId, digest, Map.of());
    }

    private static ProcessedOutputClaim claimFor(
            final UUID outputId,
            final String digest,
            final Map<TransformationAnomaly, Integer> anomalies) {
        return new ProcessedOutputClaim(
                outputId, COURT_CENTRE, OU_CODE, REGISTER_DATE, FILE_NAME, digest, anomalies);
    }

    @Nested
    @DisplayName("claiming the request before the POST")
    class Claiming {

        @Test
        void claiming_a_fresh_request_should_write_a_pending_row_carrying_the_digest() {
            final RunClaim run = seededRequest();
            final UUID outputId = UUID.randomUUID();

            final boolean claimed = repository().claimPending(run, claimFor(outputId, DIGEST));

            assertThat(claimed).isTrue();
            final Row row = requireRow(run);
            assertThat(row.outputId()).isEqualTo(outputId);
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.requestDigest())
                    .as("the digest of the bytes about to be sent is written before the POST, so an "
                            + "ambiguous outcome still leaves evidence of what was attempted")
                    .isEqualTo(DIGEST);
            assertThat(row.responseCode())
                    .as("nothing has answered yet")
                    .isNull();
        }

        @Test
        void claiming_should_record_what_the_register_was_assembled_for() {
            final RunClaim run = seededRequest();

            repository().claimPending(run, claimFor(UUID.randomUUID(), DIGEST));

            final Row row = requireRow(run);
            assertThat(row.courtCentreId()).isEqualTo(COURT_CENTRE);
            assertThat(row.courtCentreOuCode()).isEqualTo(OU_CODE);
            assertThat(row.registerDate()).isEqualTo(REGISTER_DATE);
            assertThat(row.fileName()).isEqualTo(FILE_NAME);
        }

        /**
         * The hearing payload may carry no {@code hearing.courtCentre.code}, and a register is still
         * produced for it. The column is nullable for that reason and the claim has to be able to
         * say so, rather than inventing a placeholder support would later read as a real OU code.
         */
        @Test
        void claiming_should_accept_a_court_centre_with_no_ou_code() {
            final RunClaim run = seededRequest();

            final boolean claimed = repository().claimPending(run, new ProcessedOutputClaim(
                    UUID.randomUUID(), COURT_CENTRE, null, REGISTER_DATE, FILE_NAME, DIGEST,
                    null));

            assertThat(claimed).isTrue();
            assertThat(requireRow(run).courtCentreOuCode()).isNull();
        }

        /**
         * The replay skip, stated as a predicate rather than as a branch in Java.
         *
         * <p>{@code add-court-register} appends an event and a row on every POST, so a redelivery or
         * a support replay that re-sent an accepted register would leave two registers for one
         * hearing. Progression's generation sweep absorbs the duplicate, but the extra row persists,
         * so the log refuses the second send outright.
         */
        @Test
        void claiming_should_be_refused_once_the_register_is_posted() {
            final RunClaim run = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(run, claimFor(UUID.randomUUID(), DIGEST));
            repository.recordPosted(run, ACCEPTED);

            final boolean claimed =
                    repository.claimPending(run, claimFor(UUID.randomUUID(), OTHER_DIGEST));

            assertThat(claimed).isFalse();
            final Row row = requireRow(run);
            assertThat(row.status()).isEqualTo("POSTED");
            assertThat(row.requestDigest())
                    .as("a refused claim must not overwrite what was actually sent")
                    .isEqualTo(DIGEST);
            assertThat(row.responseCode()).isEqualTo(ACCEPTED);
        }

        @Test
        void claiming_should_be_admitted_again_after_a_failure_so_the_failed_work_repeats() {
            final RunClaim run = seededRequest();
            final ProcessedOutputRepository repository = repository();
            final UUID firstId = UUID.randomUUID();
            repository.claimPending(run, claimFor(firstId, DIGEST));
            repository.recordFailed(run, REFUSED);

            final boolean claimed =
                    repository.claimPending(run, claimFor(UUID.randomUUID(), OTHER_DIGEST));

            assertThat(claimed).isTrue();
            final Row row = requireRow(run);
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.outputId())
                    .as("the row keeps the identity it was first written under")
                    .isEqualTo(firstId);
            assertThat(row.requestDigest())
                    .as("the digest describes the body about to be sent, so a re-claim replaces it")
                    .isEqualTo(OTHER_DIGEST);
        }
    }

    /**
     * The anomaly summary: a register that survived with a part missing, and said which part.
     *
     * <p>The legacy pipeline either threw and lost the whole hearing (C19, C20) or dropped a
     * recipient without a word (C27). The fixes keep the register and record the skip as a bounded
     * reason-code count — codes and numbers only, because every defendant on this document is a
     * child and free text is how a name reaches a support query.
     *
     * <p>The counts are a {@code Map<TransformationAnomaly, Integer>} by the time they reach the
     * claim, so the column is the only place they are ever a string. Rendering them is therefore
     * this adapter's job and is asserted here: the same counts must always produce the same
     * characters, whatever order the transformation happened to meet them in, or two identical
     * registers compare unequal in a reconciliation nobody can then trust.
     */
    @Nested
    @DisplayName("the anomaly summary")
    class Anomalies {

        @Test
        void counted_anomalies_should_be_written_as_bounded_code_and_count_pairs() {
            final RunClaim run = seededRequest();

            repository().claimPending(run, claimFor(UUID.randomUUID(), DIGEST, ANOMALIES));

            assertThat(requireRow(run).anomalySummary()).isEqualTo(ANOMALY_SUMMARY);
        }

        /**
         * Read back into the counts it was written from. Nothing in production parses this column —
         * it is written for a human and for reconciliation — so the inverse lives here, which is the
         * only place that can show the rendering loses nothing.
         */
        @Test
        void the_written_summary_should_read_back_as_the_counts_it_was_written_from() {
            final RunClaim run = seededRequest();

            repository().claimPending(run, claimFor(UUID.randomUUID(), DIGEST, ANOMALIES));

            assertThat(parseSummary(requireRow(run).anomalySummary())).isEqualTo(ANOMALIES);
        }

        /**
         * Determinism, stated against the thing that could break it: the iteration order of the map
         * the transformation accumulated the counts in.
         */
        @Test
        void the_same_counts_should_be_written_identically_whatever_order_they_were_counted_in() {
            final RunClaim first = seededRequest();
            final RunClaim second = seededRequest();
            final Map<TransformationAnomaly, Integer> countedOneWay = new LinkedHashMap<>();
            countedOneWay.put(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT, 1);
            countedOneWay.put(TransformationAnomaly.LETTER_DELIVERY_DROPPED, 2);
            final Map<TransformationAnomaly, Integer> countedTheOther = new LinkedHashMap<>();
            countedTheOther.put(TransformationAnomaly.LETTER_DELIVERY_DROPPED, 2);
            countedTheOther.put(TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT, 1);

            repository().claimPending(first, claimFor(UUID.randomUUID(), DIGEST, countedOneWay));
            repository().claimPending(second, claimFor(UUID.randomUUID(), DIGEST, countedTheOther));

            assertThat(requireRow(first).anomalySummary())
                    .as("one set of counts has one representation, or two identical registers "
                            + "compare unequal")
                    .isEqualTo(requireRow(second).anomalySummary())
                    .isEqualTo(ANOMALY_SUMMARY);
        }

        @Test
        void every_anomaly_the_enumeration_declares_should_survive_the_column() {
            final RunClaim run = seededRequest();
            final Map<TransformationAnomaly, Integer> all = Stream.of(TransformationAnomaly.values())
                    .collect(Collectors.toMap(anomaly -> anomaly, anomaly -> 1));

            repository().claimPending(run, claimFor(UUID.randomUUID(), DIGEST, all));

            assertThat(parseSummary(requireRow(run).anomalySummary())).isEqualTo(all);
        }

        @Test
        void a_register_with_nothing_skipped_should_leave_the_summary_empty() {
            final RunClaim run = seededRequest();

            repository().claimPending(run, claimFor(UUID.randomUUID(), DIGEST));

            assertThat(requireRow(run).anomalySummary())
                    .as("no anomalies is an absent summary, not the string 'none'")
                    .isNull();
        }

        @Test
        void a_re_claim_should_replace_the_summary_with_the_one_it_is_about_to_send() {
            final RunClaim run = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(run, claimFor(UUID.randomUUID(), DIGEST, ANOMALIES));
            repository.recordFailed(run, REFUSED);

            repository.claimPending(run, claimFor(UUID.randomUUID(), OTHER_DIGEST,
                    Map.of(TransformationAnomaly.RECIPIENT_MISSING_EMAIL, 1)));

            assertThat(requireRow(run).anomalySummary()).isEqualTo("recipient-missing-email:1");
        }

        @Test
        void a_failed_post_should_keep_the_summary_of_what_it_tried_to_send() {
            final RunClaim run = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(run, claimFor(UUID.randomUUID(), DIGEST, ANOMALIES));

            repository.recordFailed(run, REFUSED);

            assertThat(requireRow(run).anomalySummary()).isEqualTo(ANOMALY_SUMMARY);
        }
    }

    /** The inverse of the adapter's rendering, so a round trip can be asserted rather than assumed. */
    private static Map<TransformationAnomaly, Integer> parseSummary(final String summary) {
        return Stream.of(summary.split(","))
                .map(pair -> pair.split(":"))
                .collect(Collectors.toMap(
                        pair -> codeFor(pair[0]), pair -> Integer.valueOf(pair[1])));
    }

    private static TransformationAnomaly codeFor(final String code) {
        return Stream.of(TransformationAnomaly.values())
                .filter(anomaly -> anomaly.value().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "the column holds a code the enumeration does not declare: " + code));
    }

    @Nested
    @DisplayName("recording the outcome of the POST")
    class Recording {

        @Test
        void recording_a_post_should_move_the_row_to_posted_with_the_status_progression_answered() {
            final RunClaim run = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(run, claimFor(UUID.randomUUID(), DIGEST));
            ageUpdatedAt(run);
            final Instant aged = requireRow(run).updatedAt();

            final boolean recorded =
                    repository.recordPosted(run, ACCEPTED);

            assertThat(recorded).isTrue();
            final Row row = requireRow(run);
            assertThat(row.status()).isEqualTo("POSTED");
            assertThat(row.responseCode())
                    .as("202 and nothing else is success, and the number is what support reads")
                    .isEqualTo(ACCEPTED);
            assertThat(row.updatedAt()).isAfter(aged);
        }

        @Test
        void recording_a_failure_should_move_the_row_to_failed_and_keep_the_digest() {
            final RunClaim run = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(run, claimFor(UUID.randomUUID(), DIGEST));

            final boolean recorded =
                    repository.recordFailed(run, REFUSED);

            assertThat(recorded).isTrue();
            final Row row = requireRow(run);
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.responseCode()).isEqualTo(REFUSED);
            assertThat(row.requestDigest())
                    .as("what was attempted is still the reconciliation evidence — and it is worth "
                            + "more after a failure than after a success")
                    .isEqualTo(DIGEST);
        }

        /**
         * A connect failure or a timeout has no status line to record. The row still has to move,
         * because the alternative is a PENDING row nobody can distinguish from a run still in
         * flight — and an ambiguous POST is retried, so the evidence of the attempt is the only
         * thing that says a duplicate is possible.
         */
        @Test
        void recording_a_failure_with_no_answer_should_leave_the_response_code_empty() {
            final RunClaim run = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(run, claimFor(UUID.randomUUID(), DIGEST));

            final boolean recorded =
                    repository.recordFailed(run, null);

            assertThat(recorded).isTrue();
            final Row row = requireRow(run);
            assertThat(row.status()).isEqualTo("FAILED");
            assertThat(row.responseCode()).isNull();
            assertThat(row.requestDigest()).isEqualTo(DIGEST);
        }

        /**
         * The case that makes the predicate worth having.
         *
         * <p>Two deliveries of a request can overlap: a runner whose claim was reclaimed while it
         * worked is still running, and its POST can finish after the winner's. If its late failure
         * could move a POSTED register back to FAILED, the next delivery would re-claim it and POST
         * a second, non-idempotent {@code add-court-register} — a duplicate register created by the
         * log that exists to prevent one.
         */
        @Test
        void a_late_failure_should_never_move_a_register_out_of_posted() {
            final RunClaim run = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(run, claimFor(UUID.randomUUID(), DIGEST));
            repository.recordPosted(run, ACCEPTED);

            final boolean recorded =
                    repository.recordFailed(run, REFUSED);

            assertThat(recorded)
                    .as("the loser of an overlap is told its write affected nothing")
                    .isFalse();
            final Row row = requireRow(run);
            assertThat(row.status()).isEqualTo("POSTED");
            assertThat(row.responseCode()).isEqualTo(ACCEPTED);
        }

        @Test
        void recording_a_post_twice_should_leave_the_row_posted_and_affect_nothing_the_second_time() {
            final RunClaim run = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(run, claimFor(UUID.randomUUID(), DIGEST));
            repository.recordPosted(run, ACCEPTED);

            final boolean again =
                    repository.recordPosted(run, ACCEPTED);

            assertThat(again).isFalse();
            assertThat(requireRow(run).status()).isEqualTo("POSTED");
        }

        @Test
        void recording_a_post_after_a_failure_should_be_admitted_so_the_success_is_the_last_word() {
            final RunClaim run = seededRequest();
            final ProcessedOutputRepository repository = repository();
            repository.claimPending(run, claimFor(UUID.randomUUID(), DIGEST));
            repository.recordFailed(run, REFUSED);

            final boolean recorded =
                    repository.recordPosted(run, ACCEPTED);

            assertThat(recorded)
                    .as("a register that did go must end POSTED, or it would be sent again")
                    .isTrue();
            assertThat(requireRow(run).status()).isEqualTo("POSTED");
        }

        @Test
        void recording_an_outcome_for_an_unclaimed_request_should_affect_nothing() {
            final RunClaim run = seededRequest();

            final boolean recorded =
                    repository().recordPosted(run, ACCEPTED);

            assertThat(recorded).isFalse();
            assertThat(row(run)).isEmpty();
        }
    }

    /**
     * One register per hearing, enforced by the database rather than by the code that writes it.
     *
     * <p>The repository's conditional upsert could never attempt a second row, which is exactly why
     * the constraint is worth asserting directly: it is what would refuse the row if some later
     * caller — a support fix, a migration, a second submission path — tried to write one.
     */
    @Nested
    @DisplayName("one output per request")
    class Uniqueness {

        @Test
        void a_second_output_for_one_request_should_be_refused_by_the_database() {
            final RunClaim run = seededRequest();
            repository().claimPending(run, claimFor(UUID.randomUUID(), DIGEST));

            assertThatThrownBy(() -> insertSecondOutput(run))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("processed_output_unique_request");
        }

        /**
         * The second row, offered in full so that the uniqueness constraint is what refuses it.
         *
         * <p>The register-store columns V2 adds are carried here as the recorder would write them,
         * so that nothing but {@code processed_output_unique_request} can be what refuses the row
         * and the assertion above cannot pass for the wrong reason. What they mean is pinned in
         * {@code SchemaMigrationV2IT} rather than repeated here.
         */
        private void insertSecondOutput(final RunClaim run) {
            ProcessedLogTestSupport.jdbcClient()
                    .sql("""
                            INSERT INTO processed_output (
                                output_id, source, request_id, court_centre_id, register_date,
                                file_name, status, document, hearing_id, hearing_date,
                                register_time, recorded_flag_state)
                            VALUES (
                                :outputId, :source, :requestId, :courtCentreId, :registerDate,
                                :fileName, 'PENDING',
                                CAST('{"documentType": "CourtRegister"}' AS jsonb), :hearingId,
                                TIMESTAMPTZ '2026-08-20T09:00:00Z',
                                TIMESTAMPTZ '2026-08-20T17:00:00Z', 'UNKNOWN')
                            """)
                    .param("outputId", UUID.randomUUID())
                    .param("source", run.source())
                    .param("requestId", run.requestId())
                    .param("courtCentreId", COURT_CENTRE)
                    .param("registerDate", REGISTER_DATE)
                    .param("fileName", FILE_NAME)
                    .param("hearingId", UUID.randomUUID())
                    .update();
        }
    }

    /**
     * The rows this repository shares a table with and may not write.
     *
     * <p>Since V2 {@code processed_output} holds two kinds of row. These three statements write the
     * one increment 001 wrote - what was POSTed to progression, PENDING then POSTED or FAILED - and
     * the register store writes the other, RECORDED through GENERATED to NOTIFIED, with SUPERSEDED
     * beside them. They are in one table because a command has at most one outcome, not because
     * either half may edit the other's rows.
     *
     * <p>The predicates were {@code status <> 'POSTED'}, which is every state except one: a
     * redelivery arriving in {@code progression-post} mode against a database whose registers are
     * being recorded would re-claim a RECORDED row back to PENDING, replace the digest of the
     * document with the digest of a POST body, and settle it POSTED or FAILED. The register would
     * then be neither: gone from {@code activeUnbatched}, still stamped into a batch that was built
     * from it, and carrying a digest that matches nothing.
     */
    @Nested
    @DisplayName("the register rows these statements may not touch")
    class RegisterRows {

        @Test
        void claiming_should_be_refused_on_a_register_this_service_recorded() {
            final RunClaim recorded = seededRegisterRow(null);
            final RunClaim batched = seededRegisterRow(seededBatch());

            assertThat(repository().claimPending(recorded, claimFor(UUID.randomUUID(), DIGEST)))
                    .as("a recorded register is not a POST that has not settled")
                    .isFalse();
            assertThat(repository().claimPending(batched, claimFor(UUID.randomUUID(), DIGEST)))
                    .as("and a register already in a batch is on its way to a PDF")
                    .isFalse();

            assertThat(requireRow(recorded)).extracting(Row::status, Row::requestDigest)
                    .containsExactly(RECORDED, RECORDED_DIGEST);
            assertThat(requireRow(batched)).extracting(Row::status, Row::requestDigest)
                    .containsExactly(RECORDED, RECORDED_DIGEST);
        }

        @Test
        void recording_a_post_should_leave_a_recorded_register_exactly_as_it_was() {
            final RunClaim recorded = seededRegisterRow(null);
            final RunClaim batched = seededRegisterRow(seededBatch());

            assertThat(repository().recordPosted(recorded, ACCEPTED)).isFalse();
            assertThat(repository().recordPosted(batched, ACCEPTED)).isFalse();

            assertThat(requireRow(recorded)).extracting(Row::status, Row::responseCode)
                    .containsExactly(RECORDED, null);
            assertThat(requireRow(batched)).extracting(Row::status, Row::responseCode)
                    .containsExactly(RECORDED, null);
        }

        @Test
        void recording_a_failure_should_leave_a_recorded_register_exactly_as_it_was() {
            final RunClaim recorded = seededRegisterRow(null);
            final RunClaim batched = seededRegisterRow(seededBatch());

            assertThat(repository().recordFailed(recorded, REFUSED)).isFalse();
            assertThat(repository().recordFailed(batched, REFUSED)).isFalse();

            assertThat(requireRow(recorded)).extracting(Row::status, Row::responseCode)
                    .containsExactly(RECORDED, null);
            assertThat(requireRow(batched)).extracting(Row::status, Row::responseCode)
                    .containsExactly(RECORDED, null);
        }
    }

    /** A request whose output row is a recorded register, optionally already stamped with a batch. */
    private static RunClaim seededRegisterRow(final UUID batchId) {
        final RunClaim run = seededRequest();
        ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        INSERT INTO processed_output (
                            output_id, source, request_id, court_centre_id, register_date,
                            file_name, status, request_digest, document, hearing_id, hearing_date,
                            register_time, recorded_flag_state, batch_id)
                        VALUES (
                            :outputId, :source, :requestId, :courtCentreId, :registerDate,
                            :fileName, 'RECORDED', :digest,
                            CAST('{"documentType": "CourtRegister"}' AS jsonb), :hearingId,
                            TIMESTAMPTZ '2026-08-20T09:00:00Z',
                            TIMESTAMPTZ '2026-08-20T17:00:00Z', 'ON', :batchId)
                        """)
                .param("outputId", UUID.randomUUID())
                .param("source", run.source())
                .param("requestId", run.requestId())
                .param("courtCentreId", COURT_CENTRE)
                .param("registerDate", REGISTER_DATE)
                .param("fileName", FILE_NAME)
                .param("digest", RECORDED_DIGEST)
                .param("hearingId", UUID.randomUUID())
                .param("batchId", batchId, Types.OTHER)
                .update();
        return run;
    }

    /**
     * A batch to stamp a register with, on a court centre of its own.
     *
     * <p>Its own court centre because {@code idx_register_batch_live_key} admits one unfailed batch
     * per court centre and register day, and these cases want several.
     */
    private static UUID seededBatch() {
        final UUID batchId = UUID.randomUUID();
        ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        INSERT INTO register_batch (
                            batch_id, court_centre_id, register_date, file_name, status,
                            system_generated)
                        VALUES (
                            :batchId, :courtCentreId, :registerDate, :fileName, 'PENDING', true)
                        """)
                .param("batchId", batchId)
                .param("courtCentreId", UUID.randomUUID())
                .param("registerDate", REGISTER_DATE)
                .param("fileName", FILE_NAME)
                .update();
        return batchId;
    }

    private record Row(
            UUID outputId,
            UUID courtCentreId,
            String courtCentreOuCode,
            LocalDate registerDate,
            String fileName,
            String status,
            Integer responseCode,
            String requestDigest,
            String anomalySummary,
            Instant createdAt,
            Instant updatedAt) {
    }

    private static Optional<Row> row(final RunClaim run) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT output_id, court_centre_id, court_centre_ou_code, register_date,
                               file_name, status, response_code, request_digest, anomaly_summary,
                               created_at, updated_at
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", run.source())
                .param("requestId", run.requestId())
                .query((rs, rowNumber) -> new Row(
                        rs.getObject("output_id", UUID.class),
                        rs.getObject("court_centre_id", UUID.class),
                        rs.getString("court_centre_ou_code"),
                        rs.getObject("register_date", LocalDate.class),
                        rs.getString("file_name"),
                        rs.getString("status"),
                        rs.getObject("response_code", Integer.class),
                        rs.getString("request_digest"),
                        rs.getString("anomaly_summary"),
                        instant(rs.getObject("created_at", OffsetDateTime.class)),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .optional();
    }

    private static Row requireRow(final RunClaim run) {
        return row(run).orElseThrow(() -> new IllegalStateException(
                "no processed_output row for " + run.source() + "/" + run.requestId()));
    }

    /**
     * Seeds {@code updated_at} into the past by the database's own clock, so "the write moved the
     * timestamp on" can be asserted strictly rather than against a value written moments earlier.
     */
    private static void ageUpdatedAt(final RunClaim run) {
        final int aged = ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        UPDATE processed_output
                           SET updated_at = now() - interval '1 hour'
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", run.source())
                .param("requestId", run.requestId())
                .update();
        if (aged != ONE_ROW) {
            throw new IllegalStateException("expected one output row to age, aged " + aged);
        }
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
