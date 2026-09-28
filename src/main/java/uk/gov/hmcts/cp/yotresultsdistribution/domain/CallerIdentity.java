package uk.gov.hmcts.cp.yotresultsdistribution.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a run's outbound calls are made as.
 *
 * <p>The legacy answers this once per run and never again: the hearing-resulted envelope's
 * {@code userId} becomes the orchestration's {@code cjscppuid}
 * ({@code CourtRegisterEventGridTrigger/index.js:16},
 * {@code CourtRegisterQueueTrigger/index.js:17}) and
 * {@code CourtRegisterOrchestrator/index.js:14,37,55} threads that one value into the payload read,
 * the now-subscriptions read ({@code NowsHelper/service/ReferenceDataService.js:44}) and the
 * {@code add-court-register} POST ({@code ProcessOutboundCourtRegister/index.js:19}). One identity,
 * three calls, from the user who shared the results. This type is that answer, made explicit so it
 * cannot be resolved three times and come out three ways — and so it stops being a bare string
 * called {@code cjscppuid} at the front door.
 *
 * <p><strong>Having no user is a state, not a gap.</strong> A producer build from before
 * {@code userId} was agreed sends none, and so does a replay that does not carry the original body —
 * one rebuilt by hand, or one re-sent deliberately without the field because the original user has
 * been deactivated. Those runs are made under the configured system identity, which is what
 * {@link #orSystem(String)} is for: the per-request user takes precedence where there is one, and
 * the configured identity is the fallback rather than the other way round. A replay that carries the
 * original body carries the original {@code userId} with it, and is attributed to that user like any
 * other delivery.
 *
 * <p><strong>It is never logged.</strong> A user identifier is PII-adjacent and the configured
 * identity is a secret; both leave this service in a {@code CJSCPPUID} header and nowhere else. MDC
 * carries {@code requestId}, {@code hearingId} and {@code source}.
 *
 * @param userId the user the run is attributed to, where the message named one
 */
public record CallerIdentity(Optional<UUID> userId) {

    /**
     * A run no user is named for: a message published before the field existed, or a replay that
     * does not carry the original body.
     */
    public static final CallerIdentity SYSTEM = new CallerIdentity(Optional.empty());

    /**
     * Refuses a {@code null}, which would be a second spelling of {@link #SYSTEM}.
     */
    public CallerIdentity {
        Objects.requireNonNull(userId, "userId is Optional.empty() when absent, never null");
    }

    /**
     * The identity the command was published under.
     *
     * @param command the validated request
     * @return the user it named, or {@link #SYSTEM} where it named none
     */
    // PMD.ShortMethodName: `of` is the platform's own name for a static factory, and it is the name
    // RequestFingerprint uses for the same shape of call on the same argument.
    @SuppressWarnings("PMD.ShortMethodName")
    public static CallerIdentity of(final DistributionCommand command) {
        return command.userId().isEmpty() ? SYSTEM : new CallerIdentity(command.userId());
    }

    /**
     * The value a {@code CJSCPPUID} header carries for this run.
     *
     * <p>The configured identity is passed in rather than held here because each client authorises
     * against its own: {@code yotresultsdistribution.progression.system-user-id} for the outbound
     * {@code add-court-register} POST, {@code yotresultsdistribution.results.system-user-id} for the payload
     * query fallback, {@code yotresultsdistribution.referencedata.system-user-id} for the now-subscriptions
     * read. What must not vary between them is the per-request user, and it does not — it is this
     * record, resolved from one command.
     *
     * @param systemUserId the identity configured for the client making the call
     * @return the run's user where it has one, and the configured identity otherwise
     */
    public String orSystem(final String systemUserId) {
        return userId.map(UUID::toString).orElse(systemUserId);
    }
}
