package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An operations action that was refused or could not be finished, named by its bounded code.
 *
 * <p>This is how an application service says "no" to the operations API without knowing that an
 * API is what asked it. It carries a {@link OperationsReason} and nothing else that came from
 * outside this service: the status is chosen by {@code api/OperationsExceptionHandler} from the
 * reason alone, so the one status map is a table rather than a decision taken again at each throw
 * site.
 *
 * <p><strong>The argument is named, never quoted.</strong> {@link #argument()} is this service's
 * own name for the field that would not do ({@code date}, {@code batchId}, {@code sharedBefore},
 * {@code since}) - a fixed token from this repository's own vocabulary. The value the caller typed
 * is not held here, is not logged, and cannot therefore be echoed back (constitution Principle
 * VII, FR-024).
 *
 * <p>{@link #properties()} carries the bounded extras one refusal needs and the others do not - a
 * partial tally, or which sink took the report. Every value in it is a count, an identifier or a
 * bounded code by the same rule.
 *
 * <p>The message is a fixed phrase written here, never a store's or a far end's words, because it
 * reaches a log line about a flow whose every defendant is a child. It never reaches a response.
 */
public class OperationsRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The bounded code, which is the whole of what a caller is told. */
    private final OperationsReason refusedUnder;

    /** This service's own name for the offending argument, or {@code null}. */
    private final String offendingArgument;

    /** Bounded extras this refusal carries, empty where it has none. */
    private final Map<String, Object> carriedProperties;

    /**
     * Creates a refusal that names no argument and carries no extras.
     *
     * @param refusedUnder the bounded code
     */
    public OperationsRefusedException(final OperationsReason refusedUnder) {
        this(refusedUnder, null, Map.of(), null);
    }

    /**
     * Creates a refusal over one named argument.
     *
     * @param refusedUnder  the bounded code
     * @param offending     this service's own name for the argument, never the value
     * @param refusedBy     what refused to read it, attached for its class name alone
     */
    public OperationsRefusedException(final OperationsReason refusedUnder, final String offending,
            final Throwable refusedBy) {
        this(refusedUnder, offending, Map.of(), refusedBy);
    }

    /**
     * Creates a refusal carrying bounded extras.
     *
     * @param refusedUnder the bounded code
     * @param carried      counts, identifiers and bounded codes the refusal is read with
     */
    public OperationsRefusedException(final OperationsReason refusedUnder,
            final Map<String, Object> carried) {
        this(refusedUnder, null, carried, null);
    }

    /**
     * Creates a refusal in full.
     *
     * @param refusedUnder the bounded code
     * @param offending    this service's own name for the argument, or {@code null}
     * @param carried      counts, identifiers and bounded codes the refusal is read with
     * @param refusedBy    what refused, attached for its class name alone, or {@code null}
     */
    public OperationsRefusedException(final OperationsReason refusedUnder, final String offending,
            final Map<String, Object> carried, final Throwable refusedBy) {

        super("an operations action was refused under a bounded code", refusedBy);
        this.refusedUnder = Objects.requireNonNull(refusedUnder,
                "a refusal is told under a bounded code, and an absent one is a silent refusal");
        this.offendingArgument = offending;
        this.carriedProperties = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(carried,
                        "a refusal carries bounded extras or none, never an absent map")));
    }

    /**
     * The bounded code this action was refused under.
     *
     * @return the reason
     */
    public OperationsReason reason() {
        return refusedUnder;
    }

    /**
     * This service's own name for the offending argument.
     *
     * @return the argument's name, or {@code null} where the refusal is not about one
     */
    public String argument() {
        return offendingArgument;
    }

    /**
     * The bounded extras this refusal is read with.
     *
     * @return counts, identifiers and bounded codes, empty where there are none
     */
    public Map<String, Object> properties() {
        return carriedProperties;
    }
}
