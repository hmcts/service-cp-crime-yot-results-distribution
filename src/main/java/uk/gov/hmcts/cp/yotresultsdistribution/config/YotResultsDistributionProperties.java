package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every setting this service owns, bound once and typed.
 *
 * <p>Defaults live here rather than only in {@code application.yaml}, so the values are visible to
 * the code that depends on them and a missing configuration file cannot silently change behaviour.
 * The credential and endpoint settings are the deliberate exception: none of them has a default,
 * because a service that invents a broker address, a command API or an identity is a service that
 * can talk to the wrong one.
 *
 * @param output        what the pipeline does with an assembled register - record it here, or POST
 *                      it to progression
 * @param consumer      whether intake runs at all
 * @param intake        the intake half's own settings, read where neither other half runs
 * @param servicebus    broker connection and consumer settings
 * @param claim         the single-runner claim's timings
 * @param notification  the notifying leg's own claim timing
 * @param store         processed-log store probing
 * @param stub          test-only control over the stub adapters
 * @param payload       where the hearing payload is read from
 * @param results       the results context's query API, the payload fallback only
 * @param referencedata the reference-data context the register's recipients are looked up in
 * @param progression   the progression context the court register is POSTed to
 * @param submission    what is checked before a register is sent
 * @param fileservice   the framework file service's database the render payload is written to
 * @param endpoints     the internal hosts of the two downstreams the nightly run calls
 * @param email         the notificationnotify templates the register is sent with
 */
@ConfigurationProperties(prefix = "yotresultsdistribution")
// PMD.AvoidDuplicateLiterals: the repeated literal is a `@DefaultValue`, which must be a compile-
// time constant on the annotation itself. Four unrelated ten-second defaults happen to agree today;
// naming one constant for them would tie four independent settings together, which is the opposite
// of what a per-setting default is for.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public record YotResultsDistributionProperties(
        @DefaultValue("RECORD") OutputMode output,
        @DefaultValue Consumer consumer,
        @DefaultValue Intake intake,
        @DefaultValue Servicebus servicebus,
        @DefaultValue Claim claim,
        @DefaultValue Notification notification,
        @DefaultValue Store store,
        @DefaultValue Stub stub,
        @DefaultValue Payload payload,
        @DefaultValue Results results,
        @DefaultValue Referencedata referencedata,
        @DefaultValue Progression progression,
        @DefaultValue Submission submission,
        @DefaultValue Fileservice fileservice,
        @DefaultValue Endpoints endpoints,
        @DefaultValue Email email) {

    /**
     * Master switch for the Service Bus consumer.
     *
     * @param enabled master switch for starting the processor at all; false in the test profile
     */
    public record Consumer(@DefaultValue("true") boolean enabled) {
    }

    /**
     * The intake half's own settings, bound where neither of the other two halves is switched on.
     *
     * <p>Its own block rather than a member of {@code yotresultsdistribution.report}, because the sweep that
     * reads it belongs to the intake half: it refreshes the two intake gauges in every JVM that is
     * not a command, whichever halves are enabled, so a pod that binds neither the generation record
     * nor the report one must still be able to read this.
     *
     * @param gaugeRefresh how often the intake gauges are refreshed, and therefore the longest an
     *                     alert on them can lag the thing it is about
     */
    public record Intake(@DefaultValue("10m") Duration gaugeRefresh) {
    }

    /**
     * Connection, settlement and health settings for the inbound queue.
     *
     * @param connectionString         local and CI only, emulator connection string
     * @param namespace                deployed only, fully qualified namespace for workload identity
     * @param queueName                the inbound queue
     * @param maxConcurrentCalls       processor concurrency
     * @param maxDeliveryCount         mirrors the broker queue setting; recognises the final delivery
     * @param maxAutoLockRenewDuration must outlive any legitimate run
     * @param healthStaleness          age past which an unresolved error with no traffic stops being
     *                                 reported as an outage
     * @param redeliveryBackoff        how long a delivery is held before it is handed back, indexed
     *                                 by the broker's zero-based delivery count (the last entry
     *                                 serves every count beyond the list); the queue redelivers an
     *                                 abandoned message at once, so without it a short downstream
     *                                 outage spends the whole delivery budget in seconds
     */
    public record Servicebus(
            String connectionString,
            String namespace,
            @DefaultValue("yotresultsdistribution.requests") String queueName,
            @DefaultValue("2") int maxConcurrentCalls,
            @DefaultValue("5") int maxDeliveryCount,
            @DefaultValue("5m") Duration maxAutoLockRenewDuration,
            @DefaultValue("60s") Duration healthStaleness,
            List<Duration> redeliveryBackoff) {
    }

    /**
     * Claim timing: how long a claim lives and how long a run may take inside it.
     *
     * @param lease              claim expiry, written as {@code now() + lease}
     * @param processingDeadline enforced run bound, strictly shorter than the lease
     */
    public record Claim(
            @DefaultValue("5m") Duration lease,
            @DefaultValue("4m") Duration processingDeadline) {
    }

    /**
     * The notifying leg's claim timing, which is neither the intake half's nor the generation
     * half's.
     *
     * <p>A separate setting because it bounds different work. {@link Claim#lease} bounds one
     * hearing's pipeline run, and the generation half's {@code stale-after} says how long a batch
     * may be awaiting its render before the next run gives up on it - neither is an answer to "how
     * long can telling one batch's recipients take", which depends on how many Youth Offending Teams the batch is
     * addressed to and on how patient notificationnotify is being tonight. A lease that runs out
     * under the notifier holding it puts a second notifier into the cycle, and a Youth Offending
     * Team is sent a register about children twice.
     *
     * <p>Renewed before every POST the cycle makes - the retries of one recipient included - and
     * before every settlement, so the number here bounds one recipient's retry cycle rather than the
     * whole batch. Startup holds it to twice the longest single POST cycle over the shared
     * {@code yotresultsdistribution.endpoints.*} transport, which is 98s at the shipped values
     * ({@code PropertiesValidator.validateTheNotificationClaimOutlastsOnePostCycle}).
     *
     * @param claimLease how long a notification claim stays live before another notifier may take
     *                   it over, measured from the last renewal
     */
    public record Notification(@DefaultValue("15m") Duration claimLease) {
    }

    /**
     * Processed-log availability probing.
     *
     * @param probeInterval store-health probe interval, driving start and resume
     */
    public record Store(@DefaultValue("10s") Duration probeInterval) {
    }

    /**
     * Stub adapter behaviour, for the test and local profiles only.
     *
     * <p>Both settings are here rather than beside the modes they belong to, because neither is a
     * mode: {@code payload.mode} and {@code generation.flag-mode} say which adapter is deployed,
     * which is a question an operator must be able to answer, and these two say how the stand-in
     * behaves once one is, which is a question only a test asks.
     *
     * @param payloadFailureMode the simulated payload failure; test and local profiles only
     * @param flagAnswer         what the stub feature-flag reader answers; ON unless a test wants a
     *                           skip path
     */
    public record Stub(
            @DefaultValue("NONE") PayloadFailureMode payloadFailureMode,
            @DefaultValue("ON") StubFlagAnswer flagAnswer) {
    }

    /**
     * Where the hearing payload comes from, and how each source is reached.
     *
     * @param mode     the adapter serving the payload port
     * @param redis    the payload cache
     * @param fallback the query-side read used when the cache has nothing
     */
    public record Payload(
            @DefaultValue("LIVE") PayloadSourceMode mode,
            @DefaultValue Redis redis,
            @DefaultValue Fallback fallback) {
    }

    /**
     * The hearing payload cache.
     *
     * <p>The address is a LOCAL development default; deployed environments override it, and the key
     * is mounted from Key Vault. TLS is off by default because that is what a developer's local
     * server speaks, and on in every deployed environment — with certificates verified, which the
     * function app disables and fix C15 restores.
     *
     * @param host           cache host
     * @param port           cache port
     * @param password       cache access key; a secret, and therefore without a default
     * @param ssl            whether to connect over TLS, with certificates verified
     * @param keyPrefix      the payload prefix the producer writes under; {@code INT_} for this flow
     * @param connectTimeout how long to wait for a connection
     * @param commandTimeout how long to wait for a command to answer
     */
    public record Redis(
            @DefaultValue("localhost") String host,
            @DefaultValue("6379") int port,
            String password,
            @DefaultValue("false") boolean ssl,
            @DefaultValue("INT_") String keyPrefix,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("5s") Duration commandTimeout) {
    }

    /**
     * The query-side payload read.
     *
     * <p>The attempt count is the function app's {@code DEFAULT_PUBLISH_RETRY_COUNT} default; the
     * retry <em>policy</em> is the corrected one (fix C3), and it is the same policy the other two
     * clients read — three keys with the same names and the same meanings everywhere, because there
     * is one {@code RetryPolicy} object behind them. The legacy's fixed
     * {@code DEFAULT_PUBLISH_RETRY_INTERVAL} is gone: a wait that never grows hammers a service that
     * is already unwell.
     *
     * @param maxAttempts    total attempts including the first
     * @param initialBackoff the first wait between retryable attempts; doubled each time
     * @param maxBackoff     the ceiling on any wait, a server-supplied {@code Retry-After} included
     * @param connectTimeout how long to wait for a connection
     * @param readTimeout    how long to wait for a response
     */
    public record Fallback(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("1s") Duration initialBackoff,
            @DefaultValue("2s") Duration maxBackoff,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {
    }

    /**
     * The results context's query API: the payload fallback, and nothing else.
     *
     * <p>Unlike the informant service, this context is <em>not</em> where the register is sent — the
     * court register is POSTed to progression ({@link Progression}). What remains here is the
     * {@code hearingDetails/internal} query the cache misses to, so the block carries an endpoint
     * and an identity and no retry policy: the fetch's retry settings belong to the fetch, in
     * {@link Fallback}.
     *
     * @param baseUrl      scheme, host and port of the results context, no path
     * @param systemUserId the {@code CJSCPPUID} identity; a secret, never logged
     */
    public record Results(String baseUrl, String systemUserId) {
    }

    /**
     * The reference-data context: the query API the register's recipients are looked up in.
     *
     * <p>Its own block rather than a member of {@link Results}, because it is a different deployment
     * behind a different internal mesh host. {@code baseUrl} and {@code systemUserId} carry no
     * default for the reason given on the record above; the local development value in
     * {@code application.yaml} is the reference-data query API's own declared {@code baseUri}, and
     * the identity there falls back to this service's own — the function app threads a single
     * {@code cjscppuid} through both calls ({@code ReferenceDataService.js:44}), so an environment
     * that mounts one identity is not asked for a second.
     *
     * <p>{@code headers} exists because {@code CJSCPPUID} is documented and the authorisation scheme
     * is not: reference data's access-control rules require the caller to be in a named user group,
     * so whatever the mesh turns out to need can be supplied without a code change and nothing is
     * invented in the meantime.
     *
     * @param mode           the adapter serving the subscriptions port
     * @param baseUrl        scheme, host and port of the reference-data context, no path
     * @param systemUserId   the {@code CJSCPPUID} identity; a secret, never logged
     * @param headers        any further headers the mesh requires, name to value
     * @param maxAttempts    total attempts including the first
     * @param initialBackoff the first wait between retryable attempts; doubled each time
     * @param maxBackoff     the ceiling on any wait, a server-supplied {@code Retry-After} included
     * @param connectTimeout how long to wait for a connection
     * @param readTimeout    how long to wait for a response once connected
     */
    public record Referencedata(
            @DefaultValue("LIVE") SubscriptionsSourceMode mode,
            String baseUrl,
            String systemUserId,
            Map<String, String> headers,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("1s") Duration initialBackoff,
            @DefaultValue("2s") Duration maxBackoff,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {

        /** Freezes the header map, and treats an unconfigured one as none rather than as absent. */
        public Referencedata {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /**
     * The progression context: the command API the court register is POSTed to.
     *
     * <p>One POST per hearing, of {@code progression.add-court-register}, and success is {@code 202}
     * and nothing else. The retry numbers are this service's own: the contract documents that
     * back-off happens, not how much of it, so they are configuration rather than constants. What
     * they are a policy <em>for</em> is fix C1 and fix C3 — connect and read failures, 5xx, 429 and
     * 408 are retried, any other 4xx is a refusal that no redelivery will change, and a
     * {@code Retry-After} is honoured in delta-seconds only and bounded by {@code maxBackoff} so a
     * hostile or mistaken header cannot park a run past its claim.
     *
     * @param baseUrl        scheme, host and port of the progression context, no path
     * @param systemUserId   the {@code CJSCPPUID} identity for the POST; a secret, never logged
     * @param headers        any further headers the mesh requires, name to value
     * @param maxAttempts    total POST attempts, the first included
     * @param initialBackoff the first wait between retryable attempts; doubled each time
     * @param maxBackoff     the ceiling on any wait, a server-supplied {@code Retry-After} included
     * @param connectTimeout how long to wait for the connection
     * @param readTimeout    how long to wait for the response once connected
     */
    public record Progression(
            String baseUrl,
            String systemUserId,
            Map<String, String> headers,
            @DefaultValue("4") int maxAttempts,
            @DefaultValue("500ms") Duration initialBackoff,
            @DefaultValue("5s") Duration maxBackoff,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {

        /** Freezes the header map, and treats an unconfigured one as none rather than as absent. */
        public Progression {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /**
     * What is checked before a register leaves this service.
     *
     * @param validateOutbound whether every document is validated against the vendored progression
     *                         schemas before the POST (fix C29); never false where the service is
     *                         deployed
     */
    public record Submission(@DefaultValue("true") boolean validateOutbound) {
    }

    /**
     * The framework file service's own database, which this service writes the render payload into.
     *
     * <p>A second datasource, and a write-only one: systemdocgenerator renders a payload that is
     * already in the file service and takes its id, so the payload is inserted there and never read
     * back. Bindings rather than values for the same reason the broker and the two command APIs are
     * - the URL and the credentials arrive from Key Vault through the CSI driver, and an empty
     * default is what lets startup refuse a deployment that enabled generation without them.
     *
     * @param url      the JDBC URL of the stack's file-service database
     * @param username the write identity; a secret, never logged
     * @param password the write identity's password; a secret, never logged
     */
    public record Fileservice(String url, String username, String password) {
    }

    /**
     * The internal mesh hosts of the two downstreams the nightly run calls.
     *
     * <p>Hosts only. Each client appends its own contract path - systemdocgenerator's
     * {@code generate-document} command, notificationnotify's
     * {@code send-email-notification} - so a path written here would be appended to rather than
     * replaced. Neither has a default: an endpoint this service invents is an endpoint a deployment
     * can forget to set and still start.
     *
     * <p>The identity and the transport settings are shared by the two clients, because they are one
     * caller: the nightly run asks systemdocgenerator to render a batch and then notificationnotify
     * to e-mail it, under one {@code CJSCPPUID} and inside one run. The defaults are the estate's -
     * the same three attempts, the same waits and the same two timeouts the reference-data client
     * ships with - so a deployment states an endpoint and inherits the rest.
     *
     * @param systemdocgenerator scheme, host and port of systemdocgenerator, no path
     * @param notificationnotify scheme, host and port of notificationnotify, no path
     * @param systemUserId       the {@code CJSCPPUID} both calls are made under; a secret, never
     *                           logged
     * @param maxAttempts        total attempts including the first
     * @param initialBackoff     the first wait between retryable attempts; doubled each time
     * @param maxBackoff         the ceiling on any wait, a server-supplied {@code Retry-After}
     *                           included
     * @param connectTimeout     how long to wait for a connection
     * @param readTimeout        how long to wait for a response once connected
     */
    public record Endpoints(
            String systemdocgenerator,
            String notificationnotify,
            String systemUserId,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("1s") Duration initialBackoff,
            @DefaultValue("2s") Duration maxBackoff,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {
    }

    /**
     * What the register e-mail is sent with.
     *
     * @param templates the notificationnotify templates, by name
     */
    public record Email(@DefaultValue Templates templates) {
    }

    /**
     * The notificationnotify templates this service sends under, by the name the environment
     * configures them with.
     *
     * <p>Validated for shape at startup rather than at send time, which is defect fix P9: the
     * legacy resolved the id per recipient and, finding it blank, logged a line and moved on - a
     * night's registers unsent, with nothing recording that they were not. A deployment that cannot
     * start is a deployment that gets fixed.
     *
     * @param crStandard the standard court-register template, a UUID; required in LIVE mode
     */
    public record Templates(String crStandard) {
    }
}
