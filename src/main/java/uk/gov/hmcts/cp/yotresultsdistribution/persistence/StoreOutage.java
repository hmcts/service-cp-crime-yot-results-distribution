package uk.gov.hmcts.cp.yotresultsdistribution.persistence;

import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreRefusedRowException;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.StoreUnavailableException;

/**
 * Where "the store went away" stops being a JDBC fact and becomes a domain one.
 *
 * <p>Every statement this package makes is made through here, so the Spring classes a dead store
 * actually produces become a single {@link StoreUnavailableException} at the boundary of the
 * package that owns the datasource. Above it, the application core and the transport adapter read
 * that signal and nothing else, and the core imports no {@code org.springframework.dao} type at all
 * (constitution Principle V). It also makes the rule single: the same three classes used to be
 * listed in the pipeline and again in the listener, which is one rule about one store written twice
 * and a drift waiting to happen.
 *
 * <p><strong>Spring's own transient/non-transient split is the wrong knife here</strong>, which is
 * why the list is named rather than taken from a supertype. The exception a dead store produces -
 * {@link DataAccessResourceFailureException}, connection acquisition included - sits on the
 * non-transient side, while a constraint violation or a broken statement is the store
 * <em>answering</em>, over a connection that plainly worked. Only the store-went-away classes may
 * stop the queue.
 *
 * <p><strong>Contention is caught first and let out unchanged, and the order is the
 * behaviour.</strong> {@link ConcurrencyFailureException} extends
 * {@link TransientDataAccessException}, so without the branch above them a deadlock or a lost race
 * for a key would be read as an outage and would stop intake - and a deadlock is the opposite of an
 * outage: it is the store answering, and it clears itself on the next delivery. Suspending the whole
 * queue for one contended row would stall every message behind it.
 *
 * <p><strong>A transaction that cannot be begun is the same outage one step earlier.</strong> Two
 * statements here take a boundary of their own rather than joining whatever the caller had open -
 * the stale-batch release takes a {@code REQUIRES_NEW} one per batch - and a store that has gone
 * away refuses at {@code getTransaction} rather than at a statement, as
 * {@link CannotCreateTransactionException}. That is a {@code org.springframework.transaction} class
 * and so outside every branch above it, and a refusal to <em>start</em> work says exactly what a
 * refusal to acquire a connection says. Without it the store's own type crosses the port into
 * {@code batch/}, where a pass may name no Spring type at all (constitution Principle V) and could
 * only catch it as a bare {@code RuntimeException}.
 *
 * <p><strong>And a transaction that cannot be committed is the same outage one step later.</strong>
 * A store lost between the release's last statement and its commit refuses at {@code doCommit}, as
 * {@link TransactionSystemException}, and a write that reached that point is ambiguous rather than
 * lost: it is retried by preference, because supersession absorbs a duplicate and nothing absorbs a
 * silent loss (design rules, idempotency and supersession).
 *
 * <p><strong>Those two and not the family, which is why they are named one at a time.</strong> The
 * rest of {@code org.springframework.transaction} is configuration or a programming fault -
 * {@link org.springframework.transaction.IllegalTransactionStateException} is a propagation this
 * code asked for and cannot have, {@link org.springframework.transaction.UnexpectedRollbackException}
 * is a transaction some participant had already marked rollback-only - and calling one of those an
 * outage would abandon the message and redeliver it into the same defect on every delivery the
 * broker allows, and write a nightly outage line about a run that was never near the store's
 * health. They fall through to the caller, where a defect is dead-lettered with a reason and said.
 *
 * <p><strong>A row the store refused is translated too, and for the other reason.</strong> It is
 * still the store answering and it still may not stop the queue, but unlike a deadlock it arrives
 * quoting the row it would not take - and on {@code register_notification} the refused key carries a
 * recipient's e-mail address. {@link #translatingWrite(String, String, Runnable)} is the form for
 * those writes: the refusal reaches the caller as {@link StoreRefusedRowException} with this
 * repository's own words, so the caller that knows what to do about a lost key still gets it and the
 * driver's detail line reaches no log index.
 *
 * <p>The phrase each call site passes is written here in this repository and names the statement,
 * never the driver's message and never a parameter: it reaches an ERROR line about a flow whose
 * every defendant is a child (constitution Principle VII). For an outage the cause is attached, so
 * the stack trace still says which statement failed; for a refused row it is deliberately not,
 * because the cause is where the row's values are.
 */
final class StoreOutage {

    private StoreOutage() {
        // Static translation only.
    }

    /**
     * Makes a statement that answers, turning a store that went away into the domain's signal.
     *
     * @param statement a bounded phrase naming what was being done, for the failure's message
     * @param call      the statement
     * @param <T>       what the statement answers with
     * @return the statement's answer
     * @throws StoreUnavailableException if the store could not be reached at all
     */
    /* default */ static <T> T translating(final String statement, final Supplier<T> call) {
        try {
            return call.get();
        } catch (ConcurrencyFailureException contention) {
            // The store answering, not the store going away: two writers met on one row and this
            // one lost. Handed on unchanged, so it is settled as the ordinary transient failure it
            // is rather than stopping the queue.
            throw contention;
        } catch (TransientDataAccessException | RecoverableDataAccessException
                | DataAccessResourceFailureException | CannotCreateTransactionException
                | TransactionSystemException gone) {
            throw new StoreUnavailableException("the store could not be reached to " + statement,
                    gone);
        }
    }

    /**
     * The same, for a statement whose answer nobody reads.
     *
     * <p>A second name rather than an overload: a lambda over a method that returns a value is
     * compatible with both a {@code Supplier} and a {@code Runnable}, so an overloaded pair would be
     * ambiguous at exactly the call sites this package is made of.
     *
     * @param statement a bounded phrase naming what was being done, for the failure's message
     * @param call      the statement
     * @throws StoreUnavailableException if the store could not be reached at all
     */
    /* default */ static void translatingUpdate(final String statement, final Runnable call) {
        translating(statement, () -> {
            call.run();
            return null;
        });
    }

    /**
     * The same, for a write whose row the store may refuse on a rule of its own.
     *
     * <p><strong>Why a refusal needs translating at all, when contention does not.</strong> A
     * constraint violation is the store answering, so it is not an outage and nothing above may
     * treat it as one - but unlike a deadlock it arrives <em>quoting the row</em>: Postgres reports
     * a unique violation with a detail line naming the columns and their values, and on
     * {@code register_notification} one of those values is a recipient's e-mail address. An
     * exception nobody translated reaches an ERROR line and a stack trace in the estate's log index,
     * which is the one place that address may never appear (constitution Principle VII). So the
     * driver's words are dropped here, with the cause, and the caller is handed the phrase this
     * repository wrote.
     *
     * <p>The refusal stays distinguishable, because it has a caller that acts on it: two mechanisms
     * can derive the same row for one batch at the same moment, and the one that loses the key reads
     * back the row that won rather than sending a second e-mail under a second identity.
     *
     * @param statement a bounded phrase naming what was being done, for an outage's message
     * @param refusal   a bounded phrase naming the write and the rule that refuses it - never the
     *                  driver's words, and never a value from the row
     * @param call      the statement
     * @throws StoreUnavailableException if the store could not be reached at all
     * @throws StoreRefusedRowException  if the store took the connection and refused the row
     */
    /* default */ static void translatingWrite(final String statement, final String refusal,
            final Runnable call) {
        translatingUpdate(statement, () -> {
            try {
                call.run();
            } catch (DataIntegrityViolationException refused) {
                throw new StoreRefusedRowException(refusal);
            }
        });
    }
}
