package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;

/**
 * Which data-access failures mean "the store went away", asserted where the rule now lives.
 *
 * <p>The list itself is not new and neither is the behaviour it decides. It used to be written out
 * twice, in {@code DistributionPipeline} and again in {@code YotResultsDistributionMessageListener}, and
 * {@code DistributionPipelineTest} held the three classes to the core. The core may not name a JDBC
 * type at all (constitution Principle V), so the rule moved down to the package that owns the
 * datasource and the assertion moved with it; what the core and the transport are held to now is the
 * domain signal, in their own suites.
 *
 * <p><strong>Spring's transient/non-transient split is the wrong knife, which is why the list is
 * named.</strong> The exception a dead store actually produces -
 * {@link DataAccessResourceFailureException}, connection acquisition included - sits on the
 * non-transient side, while a constraint violation is the store <em>answering</em> over a connection
 * that plainly worked. Only the first kind may stop the queue: a suspension takes the whole pod's
 * intake down, so a rule that was one class too wide would turn a poison row into an outage.
 *
 * <p>The contention case is the one that has to be caught first and is asserted for it.
 * {@link ConcurrencyFailureException} extends {@code TransientDataAccessException}, so a list read
 * in the wrong order would call a deadlock an outage - and a deadlock is the opposite of one. It
 * clears itself on the next delivery.
 */
@DisplayName("StoreOutage")
class StoreOutageTest {

    /** Stands in for whatever the driver had to say, so a leak of it would be visible. */
    private static final String DRIVER_TEXT = "DRIVERTEXTMARKERZQX7";

    private static final String STATEMENT = "record a register";

    @Nested
    @DisplayName("a store that went away")
    class WentAway {

        @Test
        @DisplayName("every way it goes away becomes the domain's own signal")
        void every_outage_becomes_a_store_unavailable_exception() {
            for (final DataAccessException outage : everyWayTheStoreCanGoAway()) {
                assertThatThrownBy(() -> StoreOutage.translating(STATEMENT, () -> {
                    throw outage;
                }))
                        .as("a %s reached the core as itself", outage.getClass().getSimpleName())
                        .isInstanceOf(StoreUnavailableException.class)
                        .hasCause(outage);
            }
        }

        @Test
        @DisplayName("the signal names the statement and none of the driver's words")
        void the_signal_names_the_statement_and_not_the_driver() {
            assertThatThrownBy(() -> StoreOutage.translating(STATEMENT, () -> {
                throw new DataAccessResourceFailureException(DRIVER_TEXT);
            }))
                    .hasMessageContaining(STATEMENT)
                    .as("the message reaches an ERROR line about a flow whose defendants are "
                            + "children, so it is this repository's words and not the driver's")
                    .hasMessageNotContaining(DRIVER_TEXT);
        }

        @Test
        @DisplayName("a transaction that cannot be begun is the store going away too")
        void a_transaction_that_cannot_be_begun_becomes_the_domains_own_signal() {
            // The stale-batch release takes a REQUIRES_NEW boundary of its own between the read of
            // the stale batches and each batch's own statement, so a store lost in that gap refuses
            // at `getTransaction` rather than at a statement - and `CannotCreateTransactionException`
            // is a `org.springframework.transaction` type, outside every branch the list above
            // names. Untranslated it crosses the port as itself, against `failAndReleaseStale`'s
            // own `@throws`, and reaches a pass in `batch/` that may name no Spring type at all
            // (constitution Principle V).
            final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any()))
                    .thenThrow(new CannotCreateTransactionException(DRIVER_TEXT));
            final TransactionTemplate boundary = new TransactionTemplate(transactions);

            assertThatThrownBy(() -> StoreOutage.translating(STATEMENT,
                    () -> boundary.execute(own -> "released")))
                    .as("a store that will not begin a transaction is a store that went away, and "
                            + "the pass reads that signal and no other")
                    .isInstanceOf(StoreUnavailableException.class)
                    .hasMessageContaining(STATEMENT)
                    .hasMessageNotContaining(DRIVER_TEXT)
                    .hasCauseInstanceOf(CannotCreateTransactionException.class);
        }

        @Test
        @DisplayName("a transaction that could not be committed is the same outage one step later")
        void a_transaction_that_cannot_be_committed_becomes_the_domains_own_signal() {
            // The other end of the same boundary. `DataSourceTransactionManager` reports a commit
            // the driver would not take as `TransactionSystemException`, so a store lost between
            // the release's last statement and its commit refuses here rather than at a statement.
            // An ambiguous write is retried by preference (design rules: prefer a duplicate that
            // supersession absorbs over a loss that is silent), and the retry is what reading this
            // as the store's own signal buys.
            assertThatThrownBy(() -> StoreOutage.translating(STATEMENT, () -> {
                throw new TransactionSystemException(DRIVER_TEXT);
            }))
                    .isInstanceOf(StoreUnavailableException.class)
                    .hasMessageContaining(STATEMENT)
                    .hasMessageNotContaining(DRIVER_TEXT);
        }

        @Test
        @DisplayName("a statement whose answer nobody reads is translated the same way")
        void an_update_is_translated_the_same_way() {
            assertThatThrownBy(() -> StoreOutage.translatingUpdate(STATEMENT, () -> {
                throw new DataAccessResourceFailureException("connection refused");
            }))
                    .isInstanceOf(StoreUnavailableException.class);
        }

        /**
         * Every way the store can go away underneath a run.
         *
         * @return one outage of each kind
         */
        private List<DataAccessException> everyWayTheStoreCanGoAway() {
            return List.of(
                    new DataAccessResourceFailureException("the store cannot be reached"),
                    new RecoverableDataAccessException("the store dropped the connection"),
                    new TransientDataAccessResourceException("the store timed out"));
        }
    }

    @Nested
    @DisplayName("a store that answered")
    class Answered {

        /**
         * Caught above the outage classes and let out unchanged, which is the whole reason the order
         * of the branches is behaviour rather than style: contention is a subclass of one of them.
         */
        @Test
        @DisplayName("contention is the store answering, and is handed on as it came")
        void contention_is_handed_on_unchanged() {
            final ConcurrencyFailureException contention =
                    new CannotAcquireLockException("two writers met on one row");

            assertThatThrownBy(() -> StoreOutage.translating(STATEMENT, () -> {
                throw contention;
            }))
                    .as("suspending intake for a fault that clears itself on the next delivery "
                            + "would stall every message behind it")
                    .isSameAs(contention);
        }

        @Test
        @DisplayName("a refused row is the store answering too")
        void a_constraint_violation_is_handed_on_unchanged() {
            final DataIntegrityViolationException refused =
                    new DuplicateKeyException("the key is already taken");

            assertThatThrownBy(() -> StoreOutage.translating(STATEMENT, () -> {
                throw refused;
            }))
                    .isSameAs(refused);
        }

        /**
         * The rest of the {@code TransactionException} family, which is not an outage at all.
         *
         * <p>Two of that family say the store would not do the work -
         * {@link CannotCreateTransactionException} and {@link TransactionSystemException}, asserted
         * above - and they are named one at a time for exactly this reason: the rest of it is
         * configuration or a programming fault. {@link IllegalTransactionStateException} is a
         * propagation this code asked for and cannot have, and
         * {@link UnexpectedRollbackException} is a transaction some participant had already marked
         * rollback-only. Neither is a store that went away, and reading them as one would abandon
         * the message and redeliver it into the same defect five times on the intake leg, and write
         * a nightly outage line about a run that was never near the store's health. They belong to
         * the arm above them, which dead-letters with a reason and says what it was.
         */
        @Test
        @DisplayName("a transaction fault that is not the store going away travels as it was thrown")
        void a_transaction_usage_fault_is_handed_on_unchanged() {
            for (final TransactionException fault : everyTransactionFaultThatIsNotAnOutage()) {
                assertThatThrownBy(() -> StoreOutage.translating(STATEMENT, () -> {
                    throw fault;
                }))
                        .as("a %s reached the core as an outage", fault.getClass().getSimpleName())
                        .isSameAs(fault);
            }
        }

        /**
         * The transaction failures that are this service's own fault rather than the store's.
         *
         * @return one of each
         */
        private List<TransactionException> everyTransactionFaultThatIsNotAnOutage() {
            return List.of(
                    new IllegalTransactionStateException("no existing transaction to join"),
                    new UnexpectedRollbackException("the transaction was marked rollback-only"));
        }

        @Test
        @DisplayName("a failure that is not the store's at all travels as it was thrown")
        void an_unrelated_failure_is_handed_on_unchanged() {
            final IllegalStateException unrelated = new IllegalStateException("a defect");

            assertThatThrownBy(() -> StoreOutage.translating(STATEMENT, () -> {
                throw unrelated;
            }))
                    .isSameAs(unrelated);
        }

        @Test
        @DisplayName("a statement that worked answers with what it answered")
        void a_statement_that_worked_answers_normally() {
            assertThat(StoreOutage.translating(STATEMENT, () -> "recorded")).isEqualTo("recorded");
        }
    }
}
