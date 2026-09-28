package uk.gov.hmcts.cp.yotresultsdistribution.domain;

/**
 * Every bounded code an operations endpoint may refuse or fail under, and its wire spelling.
 *
 * <p>An enum rather than a string for the reason every other reason code in this service is one:
 * a runbook greps for these, a dashboard partitions on them, and a code composed at a throw site
 * is a code that drifts. It is also what makes "the status map is closed" assertable -
 * {@code api/OperationsExceptionHandler} maps <strong>every</strong> constant here to a status, and
 * a constant added without one fails that suite rather than reaching a caller as an unexplained
 * {@code 500}.
 *
 * <p><strong>The spellings are deliberately two.</strong> The codes the six commands already
 * printed keep their own hyphenated characters, because a runbook step that greps
 * {@code flag-unreadable} keeps working; the rules this increment adds are named in the
 * data model's own convention. Normalising either set would gain nothing and would silently break
 * the other's readers (data-model, "The reason codes").
 *
 * <p>Nothing here is ever composed from a caller's input, a store's words or an exception message
 * (constitution Principle VII): the whole point of a closed set is that a response can carry a
 * reason without carrying anything that came from outside this service.
 */
public enum OperationsReason {

    /** The cutover flag says the legacy generates, and no override was given. */
    FLAG_OFF("flag-off"),

    /** The cutover flag could not be read, which fails closed everywhere it is read. */
    FLAG_UNREADABLE("flag-unreadable"),

    /** The flag was read and deliberately overridden, which is recorded rather than refused. */
    OVERRIDDEN("overridden"),

    /** A required argument was not given, and this service defaults none of them. */
    MISSING_ARGUMENT("missing-argument"),

    /** An argument was given and will not read as what it has to be. */
    UNREADABLE_ARGUMENT("unreadable-argument"),

    /** The batch had already been settled, so this call changed nothing. */
    SETTLED("settled"),

    /** Another notifier holds the claim on this batch. */
    ALREADY_NOTIFYING("already-notifying"),

    /** The claim was lost part-way, so the call tried and could not finish. */
    CLAIM_LOST("claim-lost"),

    /** Some recipients were left unsettled, so the call tried and could not finish. */
    INCOMPLETE("incomplete"),

    /** The generation run could not be made. */
    GENERATION_FAILED("generation-failed"),

    /** The resend could not be made. */
    RESEND_FAILED("resend-failed"),

    /** The rows could not be read, so there is no listing to give. */
    LISTING_FAILED("listing-failed"),

    /** The period could not be given up, so this service still claims it. */
    SUPERSESSION_FAILED("supersession-failed"),

    /** The reads could not be taken, so no report was composed from the ones that could. */
    REPORT_NOT_BUILT("report-not-built"),

    /** The report was built and a sink did not take it. */
    REPORT_NOT_DELIVERED("report-not-delivered"),

    /** The e-mail output is switched off on this deployment, so it cannot be asked for. */
    EMAIL_OUTPUT_DISABLED("email-output-disabled"),

    /** The e-mail output is on and no sink was contributed behind it. */
    EMAIL_OUTPUT_NOT_WIRED("email-output-not-wired"),

    /** A court centre day whose batch is still in flight, withheld from this run. */
    KEY_IN_FLIGHT("key-in-flight"),

    /** A register outside the bound the caller named, withheld from this run. */
    OUTSIDE_THE_BOUND("outside-the-bound"),

    /** The half of the service this action belongs to is not switched on for this pod. */
    COMMAND_NOT_WIRED("command-not-wired"),

    /** The background run could not take the register-generation lock. */
    SCHEDULE_RUNNING("SCHEDULE_RUNNING"),

    /** Supersede, refused because this service is the live implementation. */
    FLAG_ON("FLAG_ON"),

    /** An override was asked for without the one batch it is permitted to cover. */
    OVERRIDE_REQUIRES_BATCH("OVERRIDE_REQUIRES_BATCH"),

    /** A supersede bound that has not happened yet. */
    SUPERSEDE_INSTANT_IN_FUTURE("SUPERSEDE_INSTANT_IN_FUTURE"),

    /** A supersede bound older than the configured age this endpoint may reach back over. */
    SUPERSEDE_INSTANT_TOO_OLD("SUPERSEDE_INSTANT_TOO_OLD"),

    /** A well-formed identifier that names no batch. */
    UNKNOWN_BATCH("UNKNOWN_BATCH"),

    /** This service's own store would not answer. */
    STORE_UNAVAILABLE("STORE_UNAVAILABLE"),

    /** A consumed platform contract refused the call. */
    DOWNSTREAM_REFUSED("DOWNSTREAM_REFUSED"),

    /** A consumed platform contract did not answer at all. */
    DOWNSTREAM_UNAVAILABLE("DOWNSTREAM_UNAVAILABLE"),

    /**
     * A failure this service did not classify, which is the only thing a {@code 500} may be.
     *
     * <p>The bounded code an unclassified defect is answered under, so that what came of the call
     * is on the wire, in the log and on the audit event rather than left to the container. A
     * {@code 500} this service can explain is a 409, a 503 or a 502 it failed to classify
     * (FR-023): this one is deliberately the code for what nobody could explain, and one appearing
     * on a dashboard is a defect to be classified rather than a refusal to be read.
     *
     * <p>It exists because the audit trail needs it. {@code AuditFilter.doFilterInternal} has no
     * {@code try}/{@code finally} around the chain, so an exception that leaves the dispatcher
     * leaves a request event with no response event beside it and no counter moved - FR-046 says
     * the event carries the outcome, and an outcome nobody wrote down is the silence this service
     * exists to end.
     */
    UNEXPECTED("UNEXPECTED"),

    /**
     * A body in a format no endpoint on this surface takes, refused before anything reads it.
     *
     * <p>Not a nicety about content negotiation. {@code cp-audit-filter-springboot} 1.0.5 hands a
     * request whose {@code Content-Type} begins {@code multipart/} straight down the chain and
     * publishes neither its request event nor its response event, so an endpoint reachable with
     * one is an endpoint reachable unaudited - which Principle III(b) makes a condition of the
     * endpoint existing at all. The refusal is taken by {@code api/OperationsContentTypeFilter},
     * outside the audit filter because by the time that one has decided to skip there is nothing
     * left to refuse, and inside the authorisation filter because who may act is decided before
     * what they may send - an anonymous caller is answered {@code 401}, not this.
     */
    UNSUPPORTED_CONTENT_TYPE("UNSUPPORTED_CONTENT_TYPE"),

    /**
     * The call could not be audited, so it was refused rather than made.
     *
     * <p>The one refusal on this surface that is about the surface's own conditions rather than
     * about the action: constitution Principle III(b) makes being audited a condition of an
     * operations endpoint existing, so a call whose request event could not be published is a call
     * that must not proceed as though it had been (plan, Complexity Tracking).
     */
    AUDIT_UNAVAILABLE("AUDIT_UNAVAILABLE");

    /** What the code is spelled as on the wire, in a response and in a runbook step. */
    private final String spelling;

    /**
     * Binds a constant to the spelling a caller and a runbook see.
     *
     * @param wireSpelling the code as it is written in a response
     */
    OperationsReason(final String wireSpelling) {
        this.spelling = wireSpelling;
    }

    /**
     * The code as a response carries it.
     *
     * @return the wire spelling, which is never composed and never derived from an input
     */
    public String wire() {
        return spelling;
    }
}
