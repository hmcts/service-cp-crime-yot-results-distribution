package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The batch state machine of data-model.md, and the boundedness of the codes that travel with it.
 *
 * <p>A batch is the only place this service records that a register was asked for, rendered by
 * another system and e-mailed to somebody. The progression leg had no such row, so a batch that
 * half-finished looked exactly like one that never started, and the operator had nothing to read.
 * The states are only worth having if a move the design never drew is refused where it is attempted:
 * a machine that permits everything records the same "somewhere in the middle" the leg did, one
 * column further on.
 *
 * <p><strong>The refusals are the test.</strong> The moves the data model draws are nine; the pairs
 * the enumeration admits are forty-nine. The other forty are the ones that would corrupt a
 * register - a batch that generated and then failed on a notification, losing a document that
 * exists; a batch reopened out of FAILED under the identity systemdocgenerator already answered
 * about; a batch re-notified out of NOTIFIED, telling a Youth Offending Team twice.
 *
 * <p>The codes carried alongside are bounded for the reason every bounded code in this service is:
 * they reach a database column, a metric label and a log index, and every defendant on the document
 * they describe is a child (constitution Principle VII). systemdocgenerator's own words about a
 * failure are kept in {@code sdg_reason}, which is not one of these.
 */
@DisplayName("Batch state")
class BatchStateTest {

    /** The moves data-model.md draws, and the only ones {@link BatchStatus} may permit. */
    private static final Map<BatchStatus, Set<BatchStatus>> DRAWN_MOVES = drawnMoves();

    /** The committed migrations, read as text because a CHECK constraint is text. */
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    /** Where the failure vocabulary is enumerated for the database, as last rewritten. */
    private static final Pattern FAILURE_REASON_CHECK =
            Pattern.compile("register_batch_failure_reason_chk\\s+CHECK");

    /** One quoted code inside an {@code IN} list. */
    private static final Pattern QUOTED_CODE = Pattern.compile("'([A-Z_]+)'");

    private static Map<BatchStatus, Set<BatchStatus>> drawnMoves() {
        final Map<BatchStatus, Set<BatchStatus>> drawn = new EnumMap<>(BatchStatus.class);
        drawn.put(BatchStatus.PENDING, EnumSet.of(
                BatchStatus.GENERATING, BatchStatus.GENERATED, BatchStatus.FAILED));
        drawn.put(BatchStatus.GENERATING, EnumSet.of(BatchStatus.GENERATED, BatchStatus.FAILED));
        drawn.put(BatchStatus.GENERATED, EnumSet.of(
                BatchStatus.NOTIFIED, BatchStatus.PARTIALLY_NOTIFIED, BatchStatus.NOTIFIED_NOBODY));
        drawn.put(BatchStatus.PARTIALLY_NOTIFIED, EnumSet.of(BatchStatus.NOTIFIED));
        drawn.put(BatchStatus.NOTIFIED, EnumSet.noneOf(BatchStatus.class));
        drawn.put(BatchStatus.NOTIFIED_NOBODY, EnumSet.noneOf(BatchStatus.class));
        drawn.put(BatchStatus.FAILED, EnumSet.noneOf(BatchStatus.class));
        return drawn;
    }

    /**
     * The values {@code register_batch_failure_reason_chk} admits once every committed migration
     * has been applied - that is, the list the <em>last</em> migration to define it enumerates.
     *
     * <p>Read from the migration text rather than from a running database so that the vocabulary
     * and its constraint can be held to each other in a unit test, which is where a constant is
     * added. {@code SchemaMigrationV2IT} asks the same question of Postgres, which is the only
     * party that can answer what a row may actually carry.
     *
     * @return the codes the constraint enumerates, in the order it enumerates them
     * @throws IOException if the migrations cannot be read
     */
    private static List<String> schemaFailureReasons() throws IOException {
        final String migrations = migrationsInVersionOrder();
        final Matcher definitions = FAILURE_REASON_CHECK.matcher(migrations);
        int definition = -1;
        while (definitions.find()) {
            definition = definitions.end();
        }
        assertThat(definition)
                .as("no migration defines register_batch_failure_reason_chk")
                .isNotNegative();

        final int list = migrations.indexOf("IN (", definition);
        assertThat(list)
                .as("the constraint does not enumerate its values with an IN list")
                .isNotNegative();
        final String enumerated = migrations.substring(list, migrations.indexOf(')', list));
        final Matcher codes = QUOTED_CODE.matcher(enumerated);

        final List<String> admitted = new ArrayList<>();
        while (codes.find()) {
            admitted.add(codes.group(1));
        }
        return admitted;
    }

    /** Every migration's text, concatenated in the order Flyway applies them. */
    private static String migrationsInVersionOrder() throws IOException {
        try (Stream<Path> migrations = Files.list(MIGRATIONS)) {
            return migrations
                    .filter(migration -> migration.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(BatchStateTest::versionOf))
                    .map(BatchStateTest::textOf)
                    .collect(Collectors.joining("\n"));
        }
    }

    /** The numeric version of {@code V<n>__<description>.sql}. */
    private static int versionOf(final Path migration) {
        final String name = migration.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }

    /** One migration's text; an unreadable migration is a failure to report, not one to absorb. */
    private static String textOf(final Path migration) {
        try {
            return Files.readString(migration);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + migration.getFileName(), unreadable);
        }
    }

    /** The unreadable answer once per bounded cause, which is every unreadable answer there is. */
    private static List<FlagDecision> unreadableDecisions() {
        return Arrays.stream(FlagDecision.UnreadableReason.values())
                .map(FlagDecision.Unreadable::new)
                .map(FlagDecision.class::cast)
                .toList();
    }

    /** Every answer the flag reader can give, which is what the bounded code set is asserted over. */
    private static List<FlagDecision> everyDecision() {
        final List<FlagDecision> decisions = new ArrayList<>(unreadableDecisions());
        decisions.add(FlagDecision.ON);
        decisions.add(FlagDecision.OFF);
        return decisions;
    }

    @Nested
    @DisplayName("the batch state machine")
    class Transitions {

        /**
         * The nine arrows of the data-model diagram, one case each, named as the diagram names
         * them so a change to the design and a change to this list are the same edit.
         */
        @ParameterizedTest(name = "{0} -> {1} ({2})")
        @CsvSource({
            "PENDING,            GENERATING,         payload stored and the render request accepted",
            "PENDING,            GENERATED,          a document was announced for a batch whose markRequested never landed",
            "PENDING,            FAILED,             the payload store was unavailable or the request refused",
            "GENERATING,         GENERATED,          document-available on the public event",
            "GENERATING,         FAILED,             generation-failed or the next run stopped waiting",
            "GENERATED,          NOTIFIED,           every recipient was accepted",
            "GENERATED,          PARTIALLY_NOTIFIED, some recipients were not",
            "GENERATED,          NOTIFIED_NOBODY,    there were no recipients at all",
            "PARTIALLY_NOTIFIED, NOTIFIED,           the CLI resent the failed recipients"})
        void a_move_the_data_model_draws_should_be_permitted(
                final BatchStatus from, final BatchStatus to, final String because) {
            assertThat(from.canTransitionTo(to))
                    .as("data-model.md draws %s -> %s: %s", from, to, because)
                    .isTrue();
        }

        /**
         * Asserted over the whole product rather than case by case, so a state added later is
         * refused everywhere by default and has to be drawn into the diagram to be permitted. The
         * failure lists every undrawn move that was allowed, which is the list to work through.
         */
        @Test
        @DisplayName("every move the data model does not draw is refused")
        void a_move_the_data_model_does_not_draw_should_be_refused() {
            final List<String> permittedButUndrawn = new ArrayList<>();
            for (final BatchStatus from : BatchStatus.values()) {
                for (final BatchStatus to : BatchStatus.values()) {
                    if (!DRAWN_MOVES.get(from).contains(to) && from.canTransitionTo(to)) {
                        permittedButUndrawn.add(from + " -> " + to);
                    }
                }
            }

            assertThat(permittedButUndrawn)
                    .as("a state machine that permits an undrawn move is a comment, not a machine")
                    .isEmpty();
        }

        /**
         * A duplicate {@code document-available} is settled by recognising the batch is already
         * where the event would put it, not by moving it there again. Permitting the self-move would
         * make the second event rewrite {@code generated_at} and re-flip the batch's rows, so the
         * timestamps would describe the redelivery rather than the generation.
         */
        @ParameterizedTest
        @EnumSource(BatchStatus.class)
        void a_state_should_refuse_a_move_to_itself(final BatchStatus state) {
            assertThat(state.canTransitionTo(state))
                    .as("%s is where the batch already is; that is not a transition", state)
                    .isFalse();
        }

        @ParameterizedTest
        @EnumSource(value = BatchStatus.class, names = {"NOTIFIED", "NOTIFIED_NOBODY", "FAILED"})
        void a_terminal_state_should_refuse_every_move(final BatchStatus terminal) {
            final List<BatchStatus> moves = new ArrayList<>();
            for (final BatchStatus next : BatchStatus.values()) {
                if (terminal.canTransitionTo(next)) {
                    moves.add(next);
                }
            }

            assertThat(moves).as("%s is terminal", terminal).isEmpty();
        }

        /**
         * PARTIALLY_NOTIFIED is the one ending that is not terminal: the recipients that failed are
         * resendable under the identity they were minted with, and the batch reaches NOTIFIED when
         * the last of them is accepted.
         */
        @Test
        void a_partially_notified_batch_should_still_reach_notified_on_a_resend() {
            assertThat(BatchStatus.PARTIALLY_NOTIFIED.canTransitionTo(BatchStatus.NOTIFIED))
                    .isTrue();
        }

        /**
         * A failed batch is re-assembled under a <em>new</em> {@code batch_id}, with its rows
         * re-stamped (data-model.md, and the partial unique constraint that admits it). Moving this
         * row back to PENDING would leave systemdocgenerator's verdict about the old identity
         * attached to a batch that is being rendered again, and the event for the retry would
         * correlate to a batch already carrying a failure.
         */
        @Test
        void a_failed_batch_should_not_be_reopened_because_re_assembly_mints_a_new_batch() {
            assertThat(BatchStatus.FAILED.canTransitionTo(BatchStatus.PENDING))
                    .as("re-assembly writes a new batch row; it does not revive this one")
                    .isFalse();
            assertThat(BatchStatus.FAILED.canTransitionTo(BatchStatus.GENERATING)).isFalse();
        }

        /**
         * The one refusal with a document behind it. Once the PDF exists, no notification outcome
         * may take the batch to FAILED: the failure reasons are all about a document that never
         * arrived, and a batch marked FAILED is one the next run re-assembles, so a notify problem
         * would be answered by rendering and sending the register a second time.
         */
        @Test
        void a_generated_batch_should_not_fail_because_a_notification_did() {
            assertThat(BatchStatus.GENERATED.canTransitionTo(BatchStatus.FAILED))
                    .as("PARTIALLY_NOTIFIED and NOTIFIED_NOBODY are how notification ends badly")
                    .isFalse();
        }

        @Test
        void a_batch_should_not_skip_the_state_that_proves_the_document_exists() {
            assertThat(BatchStatus.PENDING.canTransitionTo(BatchStatus.NOTIFIED)).isFalse();
            assertThat(BatchStatus.GENERATING.canTransitionTo(BatchStatus.NOTIFIED)).isFalse();
        }

        /**
         * The one arrow out of PENDING that is not this service moving the batch itself.
         *
         * <p>PENDING to GENERATED is not a batch skipping GENERATING for convenience: it is the
         * batch whose render request systemdocgenerator accepted and whose {@code markRequested}
         * never landed - the pod died in the moment between the 202 and the mark, or the store
         * blipped on it - so the document was rendered against a row that still says nobody asked.
         * The announcement finds it by the payload id the row does carry, and refusing the move
         * would mean throwing away a document that exists rather than sending it.
         */
        @Test
        void a_document_found_for_a_batch_whose_request_was_never_recorded_should_be_applicable() {
            assertThat(BatchStatus.PENDING.canTransitionTo(BatchStatus.GENERATED))
                    .as("the render was asked for and the mark was not; the outcome that arrives "
                            + "is what settles the two, and it has to be able to")
                    .isTrue();
        }

        /**
         * A move to nowhere is a caller that lost the state it meant to write, which is not a
         * refusal to answer false to: false would be recorded as "the machine forbade it" and the
         * caller's bug would be indistinguishable from a design rule.
         */
        @Test
        void a_move_to_no_state_at_all_should_be_refused_explicitly() {
            assertThatThrownBy(() -> BatchStatus.PENDING.canTransitionTo(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("next state");
        }
    }

    @Nested
    @DisplayName("the codes that reach a column")
    class BoundedCodes {

        /**
         * The constant names are the values of the V2 check constraint on
         * {@code register_batch.status}, so this list and the migration are one statement made
         * twice; a rename here without a migration is a batch nobody can write.
         */
        @Test
        void the_batch_states_should_be_exactly_the_seven_the_schema_enumerates() {
            assertThat(BatchStatus.values())
                    .extracting(Enum::name)
                    .containsExactlyInAnyOrder(
                            "PENDING",
                            "GENERATING",
                            "GENERATED",
                            "NOTIFIED",
                            "PARTIALLY_NOTIFIED",
                            "NOTIFIED_NOBODY",
                            "FAILED");
        }

        /**
         * Six reasons, each a different investigation. The renderer's own {@code reason} is not
         * one of them: it is another system's text about a document whose every defendant is a
         * child, and it is kept in {@code sdg_reason} where the batches counter cannot label a
         * series with it.
         *
         * <p><strong>The enumeration and {@code register_batch_failure_reason_chk} are held to each
         * other, in both directions.</strong> A constant the constraint does not admit is a batch
         * the store cannot write at the moment it is trying to record a failure; a value the
         * constraint admits and the enumeration does not is a row {@code valueOf} throws on when the
         * 07:00 report reads it. Both halves are the same statement made in two places, and the
         * hand-transcribed list this case used to carry could agree with neither - so the
         * constraint's own text is read, from the migrations as they stand, rather than copied. That
         * is what makes a migration that changes the vocabulary provably complete here, and it is
         * why this case goes red between a constant landing or leaving and its migration landing.
         * It is red for exactly that reason between the two constants being removed from the
         * enumeration and V7 narrowing the constraint they are still admitted by.
         */
        @Test
        void the_failure_reasons_should_be_exactly_the_six_the_schema_enumerates()
                throws IOException {
            assertThat(BatchFailureReason.values())
                    .extracting(Enum::name)
                    .containsExactlyInAnyOrder(
                            "PAYLOAD_STORE_UNAVAILABLE",
                            "RENDER_REQUEST_FAILED",
                            "RENDER_REQUEST_REJECTED",
                            "GENERATION_FAILED",
                            "ASSEMBLY_FAILED",
                            "NOT_COMPLETED_BY_NEXT_RUN");
            assertThat(schemaFailureReasons())
                    .as("the values register_batch_failure_reason_chk admits after every committed "
                            + "migration, against the constants that reach that column")
                    .containsExactlyInAnyOrder(
                            Arrays.stream(BatchFailureReason.values())
                                    .map(Enum::name)
                                    .toArray(String[]::new));
        }

        /**
         * One mechanism, and the type is kept anyway.
         *
         * <p>There were two: EVENT, the {@code public.event} listener, and RECONCILER, the
         * grace-period pass that asked systemdocgenerator what had become of a render. Nothing asks
         * any more, so no outcome can be attributed to that mechanism, and a constant nothing can
         * write is a value {@code register_batch.completed_by} would still admit and nobody could
         * explain - support reading a row that names a component this repository no longer has.
         *
         * <p>{@code CompletedBy} does not collapse into a boolean with the second constant gone. It
         * is an argument before it is a column, carried through {@code DocumentOutcomeSink} into the
         * store's marks, and a second completion mechanism is exactly the kind of thing that comes
         * back - a delivery callback, a supplementary render, a platform event this service does not
         * consume yet. A boolean would have to be widened at every call site to admit one.
         *
         * <p>The database's own half of this is {@code SchemaMigrationV2IT}, which asks Postgres
         * what {@code register_batch_completed_by_chk} admits: this constraint carries no IN list
         * once V7 has narrowed it to a single equality, so it is not one the migration text can be
         * read for here the way the failure reasons are.
         */
        @Test
        void completed_by_has_one_constant() {
            assertThat(CompletedBy.values())
                    .extracting(Enum::name)
                    .as("the mechanisms that can report a batch's outcome, and the reconciler is "
                            + "not one of them any more")
                    .containsExactly("EVENT");
        }

        /**
         * There is no DELIVERED. notificationnotify's delivery events are out of scope for this
         * increment, so the furthest this service can honestly say is that the command was
         * accepted, and a status that claimed more would be read by support as proof of an e-mail
         * nobody here watched arrive.
         */
        @Test
        void the_notification_statuses_should_be_exactly_the_three_the_schema_enumerates() {
            assertThat(NotificationStatus.values())
                    .extracting(Enum::name)
                    .containsExactlyInAnyOrder("PENDING", "ACCEPTED", "FAILED");
        }

        @Test
        void the_recorded_flag_states_should_be_exactly_the_three_the_schema_enumerates() {
            assertThat(RecordedFlagState.values())
                    .extracting(Enum::name)
                    .containsExactlyInAnyOrder("ON", "OFF", "UNKNOWN");
        }

        /**
         * Every stored code is a code: upper snake case, no spaces and no punctuation a column or a
         * metric label would have to be escaped for. Asserted over all four enumerations at once
         * because the property is about what may be written, not about any one of them.
         */
        @Test
        @DisplayName("no stored code is free text")
        void every_stored_code_should_be_upper_snake_case() {
            final List<Enum<?>> stored = new ArrayList<>();
            stored.addAll(List.of(BatchStatus.values()));
            stored.addAll(List.of(BatchFailureReason.values()));
            stored.addAll(List.of(NotificationStatus.values()));
            stored.addAll(List.of(RecordedFlagState.values()));

            assertThat(stored)
                    .extracting(Enum::name)
                    .allSatisfy(name -> assertThat(name).matches("[A-Z][A-Z_]*"));
        }
    }

    @Nested
    @DisplayName("the flag decision")
    class FlagDecisions {

        @Test
        void the_decision_should_admit_exactly_three_answers() {
            assertThat(FlagDecision.class.getPermittedSubclasses())
                    .as("a fourth answer is a fourth thing the run has to decide what to do about")
                    .containsExactlyInAnyOrder(
                            FlagDecision.Enabled.class,
                            FlagDecision.Disabled.class,
                            FlagDecision.Unreadable.class);
        }

        /**
         * The boundedness asserted where it is established: the component types. A decision with a
         * {@code String} component would put an SDK message, a URL or a store's response body one
         * assignment away from the skipped counter's {@code reason} label, and no validation of that
         * string afterwards would be as strong as a type that admits none.
         */
        @Test
        @DisplayName("no answer can carry free text, because no component is free text")
        void every_answer_should_be_a_record_whose_components_are_bounded() {
            for (final Class<?> answer : FlagDecision.class.getPermittedSubclasses()) {
                assertThat(answer.isRecord()).as("%s is a record", answer.getSimpleName()).isTrue();
                for (final RecordComponent component : answer.getRecordComponents()) {
                    assertThat(component.getType().isEnum())
                            .as("%s.%s is a bounded enumeration",
                                    answer.getSimpleName(), component.getName())
                            .isTrue();
                }
            }
        }

        @Test
        void only_a_flag_that_was_read_and_is_on_should_let_a_run_generate() {
            assertThat(FlagDecision.ON.generates()).isTrue();
            assertThat(FlagDecision.OFF.generates()).isFalse();
            assertThat(unreadableDecisions())
                    .as("fail-closed: a flag that was not read is not permission to generate")
                    .allSatisfy(decision -> assertThat(decision.generates()).isFalse());
        }

        @Test
        void the_codes_should_be_exactly_the_two_read_answers_and_the_six_causes() {
            assertThat(everyDecision())
                    .extracting(FlagDecision::code)
                    .containsExactlyInAnyOrder(
                            "on",
                            "off",
                            "unreadable-not-configured",
                            "unreadable-not-found",
                            "unreadable-access-denied",
                            "unreadable-timed-out",
                            "unreadable-malformed",
                            "unreadable-call-failed");
        }

        @Test
        void every_code_should_be_shaped_like_a_metric_label() {
            assertThat(everyDecision())
                    .extracting(FlagDecision::code)
                    .allSatisfy(code -> assertThat(code).matches("[a-z][a-z-]*"));
        }

        /**
         * An unreadable decision that cannot say why is a second spelling of OFF carrying none of
         * its certainty: the run would skip either way, and the operator would have no way to tell a
         * deliberate cutover from an App Configuration outage.
         */
        @Test
        void an_unreadable_decision_without_a_cause_should_be_refused() {
            assertThatThrownBy(() -> new FlagDecision.Unreadable(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("bounded cause");
        }
    }
}
