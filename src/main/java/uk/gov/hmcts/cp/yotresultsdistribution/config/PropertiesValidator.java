package uk.gov.hmcts.cp.yotresultsdistribution.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Refuses to let the application start on a configuration that cannot be operated safely.
 *
 * <p>Everything checked here fails quietly in production and loudly at startup, so startup is where
 * it is made to fail: a run that can outlive its claim, a broker lock that can expire mid-run, an
 * ambiguous credential source, a payload source that cannot fetch anything, a now-subscriptions
 * source that cannot reach reference data, a submission that has nowhere to post the register or
 * nobody to post it as, any of those three network steps whose own worst case outlasts the run it
 * happens inside, and the pre-send contract validation switched off where the service is deployed.
 *
 * <p>These are the rules a healthy-looking pod hides. A live source with no identity, a fallback
 * with no attempts, a cache with no address and a submission with no endpoint all produce a service
 * that consumes normally, settles nothing usefully, and dead-letters every hearing it is given —
 * while readiness, liveness and the queue's own metrics say the deployment succeeded. Silence of
 * exactly that kind is what this service was commissioned to end, so it is not permitted to start.
 *
 * <p>The downstream half is held to the same standard, and its failures are quieter still: a
 * schedule read in the wrong zone, a lock that expires before the run it locks is allowed to end, a
 * notification claim whose lease cannot cover one recipient's POST cycle, a run with no payload
 * store, no flag, no renderer or no notifier, a generation half with no broker to hear an outcome
 * from, a stub reachable where registers are really produced, the published local flag pair anywhere
 * a real flag is read, and a blank or malformed e-mail template id (fix P9). None of them is
 * discovered before 18:00, and by then the night's registers are already not going out
 * (research §11).
 */
@Component
// The properties records are registered here, explicitly, rather than left to a scan: without them
// the packaged application starts no context at all ("No qualifying bean of type
// YotResultsDistributionProperties"), which the container smoke finds and no JUnit suite does.
@EnableConfigurationProperties({YotResultsDistributionProperties.class, GenerationProperties.class,
    FeatureFlagProperties.class, ReportProperties.class, OperationsProperties.class})
public class PropertiesValidator implements InitializingBean {

    /**
     * The one thing this class says rather than refuses, and it is said once, at start-up.
     *
     * <p>Every other rule here ends a pod that cannot be operated safely. The audit path has a
     * state that is neither safe nor refusable - the operator's own choice to serve the operations
     * API with nothing publishing - and a state nobody is told about is the silence this service
     * exists to end.
     */
    private static final Logger LOG = LoggerFactory.getLogger(PropertiesValidator.class);

    /**
     * The fixed margin between the longest legitimate run and the broker's lock renewal, so the lock
     * is never the thing that ends a run.
     */
    public static final Duration RENEWAL_MARGIN = Duration.ofSeconds(30);

    /**
     * What the run needs on top of its three network steps: the guard's admission and outcome
     * writes, and the transformation between the two reads.
     *
     * <p>Fixed rather than configured, because none of it is an environment's choice. A budget that
     * leaves the rest of the run nothing is the shape that overruns in production and looks correct
     * in review.
     */
    public static final Duration RUN_OVERHEAD_MARGIN = Duration.ofSeconds(30);

    /**
     * The fixed margin the nightly run's ShedLock lock has to outlast its run deadline by.
     *
     * <p>Fixed rather than configured, for the reason the margin above is: a lock that expires the
     * instant the deadline does is a lock the last batch of a run races, and there is no
     * environment for which that is the right answer. Ten minutes is what the shipped
     * {@code lock-at-most-for} is longer than the shipped {@code run-deadline} by.
     */
    public static final Duration SCHEDULER_LOCK_MARGIN = Duration.ofMinutes(10);

    /**
     * The fixed bound on how long one exception-report run may take.
     *
     * <p>Fixed rather than configured, for the reason {@link #SCHEDULER_LOCK_MARGIN} is: it is not an
     * environment's choice how long reading five bounded lists and handing them to two sinks may
     * take, and the only thing an operator can do with a number like this is make it large enough to
     * hide the run that has stopped answering.
     *
     * <p>The shipped fifteen-minute lock is exactly this plus {@link #SCHEDULER_LOCK_MARGIN}, which
     * is the same margin generation's 60m + 10m = 70m already uses. A second constant of the same
     * value under a second name is a margin that can drift from itself.
     */
    public static final Duration REPORT_RUN_BUDGET = Duration.ofMinutes(5);

    /**
     * The factor the notification claim's lease has to exceed one recipient's POST cycle by.
     *
     * <p>Fixed rather than configured, for the reason {@link #SCHEDULER_LOCK_MARGIN} is: a lease
     * that expires the instant the longest single POST cycle does is a lease that recipient races,
     * and there is no environment for which that is the right answer. Doubling rather than a fixed
     * duration, because what it is margin against scales with the transport it is measured from - a
     * deployment that gives notificationnotify five minutes to answer has made every one of its POST
     * cycles longer, and a flat margin would be swallowed by the first of them.
     */
    public static final long NOTIFICATION_LEASE_MARGIN = 2;

    /**
     * How many cache reads one payload fetch makes, and therefore how many of them the run's time
     * budget has to cover.
     *
     * <p>Two: the dated key and the legacy undated twin, which the cached payload adapter reads in
     * turn before the query side is asked at all. A budget that counts only the query side licences
     * a fetch that overruns the deadline by everything the cache cost.
     */
    private static final int CACHE_READS_PER_FETCH = 2;

    /** The clients read the same keys, so each suffix is named once. */
    private static final String INITIAL_BACKOFF_SUFFIX = ".initial-backoff";
    private static final String MAX_BACKOFF_SUFFIX = ".max-backoff";
    private static final String MAX_ATTEMPTS_SUFFIX = ".max-attempts";
    private static final String READ_TIMEOUT_SUFFIX = ".read-timeout";
    private static final String CONNECT_TIMEOUT_SUFFIX = ".connect-timeout";

    private static final String LEASE = "yotresultsdistribution.claim.lease";
    private static final String PROCESSING_DEADLINE = "yotresultsdistribution.claim.processing-deadline";
    private static final String RENEW_DURATION =
            "yotresultsdistribution.servicebus.max-auto-lock-renew-duration";
    private static final String CONNECTION_STRING = "yotresultsdistribution.servicebus.connection-string";
    private static final String NAMESPACE = "yotresultsdistribution.servicebus.namespace";
    private static final String PAYLOAD = "yotresultsdistribution.payload";
    private static final String PAYLOAD_MODE = PAYLOAD + ".mode";
    private static final String SYSTEM_USER_ID = "yotresultsdistribution.results.system-user-id";
    private static final String FALLBACK = PAYLOAD + ".fallback";
    private static final String FALLBACK_MAX_ATTEMPTS = FALLBACK + MAX_ATTEMPTS_SUFFIX;
    private static final String REDIS = PAYLOAD + ".redis";
    private static final String REFDATA = "yotresultsdistribution.referencedata";
    private static final String SUBSCRIPTIONS_MODE = REFDATA + ".mode";
    private static final String REFDATA_BASE_URL = REFDATA + ".base-url";
    private static final String REFDATA_SYSTEM_USER_ID = REFDATA + ".system-user-id";
    private static final String REFDATA_MAX_ATTEMPTS = REFDATA + MAX_ATTEMPTS_SUFFIX;
    private static final String PROGRESSION = "yotresultsdistribution.progression";
    private static final String PROGRESSION_BASE_URL = PROGRESSION + ".base-url";
    private static final String PROGRESSION_SYSTEM_USER_ID = PROGRESSION + ".system-user-id";
    private static final String PROGRESSION_MAX_ATTEMPTS = PROGRESSION + MAX_ATTEMPTS_SUFFIX;

    private static final String VALIDATE_OUTBOUND = "yotresultsdistribution.submission.validate-outbound";

    private static final String GENERATION = "yotresultsdistribution.generation";
    private static final String GENERATION_ENABLED = GENERATION + ".enabled";
    private static final String RUN_DEADLINE = GENERATION + ".run-deadline";
    private static final String LOCK_AT_MOST_FOR = GENERATION + ".lock-at-most-for";
    private static final String GENERATION_STALE_AFTER = GENERATION + ".stale-after";
    private static final String GENERATION_BATCH_AGE_REFRESH = GENERATION + ".batch-age-refresh";
    private static final String NN_MODE = GENERATION + ".nn-mode";
    private static final String FILESERVICE_URL = "yotresultsdistribution.fileservice.url";
    private static final String FEATURE_CONNECTION_STRING =
            "yotresultsdistribution.feature.connection-string";
    private static final String FEATURE_LABEL = "yotresultsdistribution.feature.label";
    private static final String ENDPOINTS = "yotresultsdistribution.endpoints";
    private static final String ENDPOINTS_MAX_ATTEMPTS = ENDPOINTS + MAX_ATTEMPTS_SUFFIX;
    private static final String ENDPOINTS_CONNECT_TIMEOUT = ENDPOINTS + CONNECT_TIMEOUT_SUFFIX;
    private static final String ENDPOINTS_READ_TIMEOUT = ENDPOINTS + READ_TIMEOUT_SUFFIX;
    private static final String NOTIFICATION_CLAIM_LEASE =
            "yotresultsdistribution.notification.claim-lease";
    private static final String SDG_ENDPOINT = "yotresultsdistribution.endpoints.systemdocgenerator";
    private static final String NN_ENDPOINT = "yotresultsdistribution.endpoints.notificationnotify";
    private static final String ENDPOINTS_SYSTEM_USER_ID = ENDPOINTS + ".system-user-id";
    private static final String EMAIL_TEMPLATE = "yotresultsdistribution.email.templates.cr_standard";

    /**
     * The sweep's own interval, on the intake half rather than under {@code yotresultsdistribution.report}.
     *
     * <p>The sweep that reads it runs in every service JVM, including one with the report and the
     * generation half both switched off - which binds neither of their records, and could not read
     * a key that lived on one of them.
     */
    private static final String INTAKE_GAUGE_REFRESH = "yotresultsdistribution.intake.gauge-refresh";

    private static final String REPORT = "yotresultsdistribution.report";
    private static final String REPORT_CRON = REPORT + ".cron";
    private static final String REPORT_ZONE = REPORT + ".zone";
    private static final String REPORT_ZONE_OVERRIDE_ACKNOWLEDGED =
            REPORT + ".zone-override-acknowledged";
    private static final String REPORT_LOCK_AT_MOST_FOR = REPORT + ".lock-at-most-for";
    private static final String REPORT_REQUEST_TERMINAL_WITHIN = REPORT + ".request-terminal-within";
    private static final String REPORT_BATCH_GENERATED_WITHIN = REPORT + ".batch-generated-within";
    private static final String REPORT_NOTIFIED_WITHIN = REPORT + ".notified-within";
    private static final String REPORT_MAX_ENTRIES = REPORT + ".max-entries";
    private static final String REPORT_EMAIL_ENABLED = REPORT + ".email.enabled";
    private static final String REPORT_EMAIL_TEMPLATE = REPORT + ".email.template-id";
    private static final String REPORT_EMAIL_RECIPIENTS = REPORT + ".email.recipients";

    /** The least a report may carry and still be a report rather than a quiet morning. */
    private static final int ONE_EXCEPTION = 1;

    /** The hour the report's schedule is a wall-clock requirement in, for the zone refusal. */
    private static final String REPORT_HOUR = "07:00";

    /** The operations API's own settings, by the name each one is spelled with in a refusal. */
    private static final String OPERATIONS = "yotresultsdistribution.operations";
    private static final String SUPERSEDE_MAX_AGE = OPERATIONS + ".supersede-max-age";
    private static final String LOCK_WAIT = OPERATIONS + ".lock-wait";

    /**
     * The audit library's own keys, read from the environment rather than bound.
     *
     * <p>{@code audit.http.*} belongs to {@code cp-audit-filter-springboot} and {@code cp.audit.*}
     * to its Artemis transport. Re-declaring either under a {@code yotresultsdistribution.} key would give a
     * deployment two places to set one thing - the same argument {@link #BROKER_URL} is read from
     * the environment for. The rules still have to be able to see them, because a transport
     * switched on and pointed at nothing publishes nothing and says so only in a log line.
     *
     * <p><strong>{@code authz.http.enabled} is deliberately not among them.</strong> Neither it
     * nor {@code audit.http.enabled} is read as a condition of starting: both are ordinary
     * configuration an operator may turn on or off, they are secure by default in
     * {@code application.yaml}, and constitution 5.0.0 withdrew the refusal that tied them to
     * {@code yotresultsdistribution.operations.enabled} on a deployed pod. What is left below is about a
     * <em>value</em> that cannot mean what it says.
     */
    private static final String HTTP_AUDIT_ENABLED = "audit.http.enabled";
    private static final String OPENAPI_REST_SPEC = "audit.http.openapi-rest-spec";
    private static final String AUDIT_TRANSPORT_ENABLED = "cp.audit.enabled";
    private static final String AUDIT_HOSTS = "cp.audit.hosts";
    private static final String AUDIT_PORT = "cp.audit.port";

    /** The audit transport's hosts are a list wherever they come from, so they are bound as one. */
    private static final Bindable<List<String>> HOST_LIST = Bindable.listOf(String.class);

    /** What the audit transport's port has to read as before it is worth parsing. */
    private static final Pattern PORT_NUMBER = Pattern.compile("\\d{1,5}");

    /** The highest port a TCP stack can bind, and the top of the range the rule admits. */
    private static final int HIGHEST_PORT = 65_535;

    /** Spring's own key, not this service's: the broker the completion events arrive on. */
    private static final String BROKER_URL = "spring.artemis.broker-url";

    /** Shared so the wording of a lower-bound refusal is one string and not five. */
    private static final String MUST_BE_AT_LEAST = ") must be at least ";

    /** Shared so the wording of a stub-in-the-wrong-place refusal is one string and not five. */
    private static final String IS_STUB_WHILE = " is STUB while ";

    /** Shared so the wording of the published-pair refusals is one string and not two. */
    private static final String CARRIES_THE_PUBLISHED_LOCAL_PAIR_WHILE =
            " carries the published local pair while ";

    /**
     * The domain every real Azure App Configuration store's host ends with, and the estate has no
     * other shape.
     *
     * <p>Asked of the parsed <em>host</em> rather than searched for anywhere in the value, so that
     * the question asked is the one that matters and a path or a query mentioning the domain is not
     * mistaken for a store. A value no host can be read from names no store either way; what
     * refuses that is {@link #requireAReadableConnectionString}, under the setting's own name.
     */
    private static final String REAL_FLAG_STORE_DOMAIN = ".azconfig.io";

    /**
     * The trailing dot of an absolute DNS name, which names the same host as the relative form.
     *
     * <p>{@code store.azconfig.io.} and {@code store.azconfig.io} resolve identically and the SDK
     * builds the same client from either, so the dot is removed before the domain is asked about -
     * one dot, because one is all a root label ever is. Asking the question without removing it read
     * the absolute spelling of a real store as "not a store", which admitted the published local
     * identity against the real thing.
     */
    private static final String ROOT_LABEL_DOT = ".";

    /**
     * The two schemes an App Configuration client can be built from.
     *
     * <p>{@code http} and {@code https} and nothing else: App Configuration is reached over HTTP,
     * the compose stub included, and a scheme no client has a handler for fails in the same place as
     * no scheme at all.
     */
    private static final Set<String> CLIENT_SCHEMES = Set.of("http", "https");

    /** Shared so the wording of a required-setting refusal is one string and not four. */
    private static final String MUST_BE_SET_WHEN = " must be set when ";

    /**
     * The shape notificationnotify's {@code templateId} has to be in, checked here rather than at
     * send time (fix P9).
     *
     * <p>A pattern rather than {@link java.util.UUID#fromString}, which accepts shapes the estate's
     * canonical form does not - it parses groups of any length, so {@code 1-2-3-4-5} is a UUID to it
     * and is not one to notificationnotify.
     */
    private static final Pattern UUID_SHAPE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * The shape a report recipient has to be in, checked at startup rather than at 07:00.
     *
     * <p>Deliberately coarse - one local part, one at-sign, a dotted domain. Its job is to catch the
     * value that was never an address at all (a name, a Helm placeholder, a list pasted with a
     * stray separator), which is the failure notificationnotify refuses every recipient of every
     * morning on. Nothing here decides whether a well-formed address is a real mailbox, and nothing
     * can.
     */
    private static final Pattern ADDRESS_SHAPE =
            Pattern.compile("[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+");

    /** The four downstream modes, by the setting each one is spelled with in a refusal. */
    private static final String SDG_MODE = GENERATION + ".sdg-mode";
    private static final String FILESERVICE_MODE = GENERATION + ".fileservice-mode";
    private static final String FLAG_MODE = GENERATION + ".flag-mode";

    /** Shared so the wording of the run-budget refusals is one string and not three. */
    private static final String INSIDE_ITS_OWN_CLAIM =
            "); a run must be able to stop itself while its claim is still its own";

    /** The first attempt is the POST itself, so a policy that permits fewer never sends one. */
    private static final int MINIMUM_ATTEMPTS = 1;

    private final YotResultsDistributionProperties properties;

    private final GenerationProperties generation;

    private final FeatureFlagProperties feature;

    private final ReportProperties report;

    /** The operations API's own settings, whose refusals are {@link #validateOperations}'s alone. */
    private final OperationsProperties operations;

    /**
     * The resolved environment, kept because most of the operations refusals are about settings
     * this service does not own and therefore does not bind: {@code authz.http.*} belongs to
     * {@code cp-auth-rules-filter}, {@code audit.http.*} to {@code cp-audit-filter-springboot} and
     * {@code cp.audit.*} to its transport. Re-declaring any of them under a
     * {@code yotresultsdistribution.} key would give a deployment two places to set one thing, which is the
     * same argument {@link #brokerUrl} below is read from here for.
     */
    private final Environment environment;

    /**
     * The broker the completion events arrive on, read from the environment rather than bound.
     *
     * <p>{@code spring.artemis.*} is Spring's own configuration and this service does not own it,
     * so re-declaring it under a {@code yotresultsdistribution.} key would give a deployment two places to
     * set one connection. The rule still has to be able to see it, because an event-driven
     * completion with no broker is a batch that waits for an outcome nobody will ever send.
     */
    private final String brokerUrl;

    /**
     * Creates the validator over the bound properties.
     *
     * @param properties  the bound settings
     * @param generation  the downstream half's settings
     * @param feature     where the one lever is read from
     * @param report      the morning exception report's settings
     * @param operations  the operations API's settings
     * @param environment the resolved environment, for Spring's own broker key and for the
     *                    authorisation and audit libraries' keys
     */
    public PropertiesValidator(final YotResultsDistributionProperties properties,
                               final GenerationProperties generation,
                               final FeatureFlagProperties feature,
                               final ReportProperties report,
                               final OperationsProperties operations,
                               final Environment environment) {
        this.properties = properties;
        this.generation = generation;
        this.feature = feature;
        this.report = report;
        this.operations = operations;
        this.environment = environment;
        this.brokerUrl = environment.getProperty(BROKER_URL);
    }

    @Override
    public void afterPropertiesSet() {
        validate(properties, generation, feature, report, brokerUrl);
        validateOperations(operations, environment);
    }

    /**
     * The operations API's refusals, kept in one method of their own.
     *
     * <p>Deliberately not folded into the static {@code validate} above, and deliberately reading
     * its own inputs: increment 004 is changing this class at the same time, and a rule set that
     * arrives as one added method rather than as a reshaped signature is one a rebase can keep
     * both halves of.
     *
     * <p><strong>None of these is a rule about one switch given another's value.</strong>
     * Constitution 5.0.0 withdrew the refusal that would not let a deployed pod start with the
     * operations API enabled and {@code authz.http.enabled} or {@code audit.http.enabled} off, and
     * withdrew the {@code yotresultsdistribution.servicebus.namespace} discriminator that decided where it
     * applied. Both switches are ordinary configuration an operator may set, they default to
     * {@code true} in {@code application.yaml} against library defaults of off, and a pod
     * configured with either of them off comes up. What is refused here is a <em>value</em> that
     * cannot mean what it says - and what is <em>said</em> here, rather than refused, is the one
     * configuration that publishes nothing while looking configured; see
     * {@link #sayWhereNothingIsPublished}.
     *
     * @param operations  the operations API's settings
     * @param environment the resolved environment, for the audit library's own keys
     * @throws IllegalStateException if any rule is broken
     */
    /* default */ static void validateOperations(final OperationsProperties operations,
                                                 final Environment environment) {
        validateTheAuditTransportNamesSomewhereToPublish(environment);
        validateTheAuditFilterHasADocumentToRead(environment);
        validateTheSupersedeBoundAdmitsSomething(operations);
        validateTheLockAttemptIsSomethingAThreadCanMake(operations);
        sayWhereNothingIsPublished(environment);
    }

    /**
     * Says, once and at start-up, that the audit filter is switched on over a transport that is off
     * - which is a pod serving the operations API with no audit event reaching anybody.
     *
     * <p>Not a refusal, and deliberately not one: constitution 5.0.0 made both switches ordinary
     * configuration and the pod always comes up. It is the shipped default, too, so this is the
     * common case rather than an exotic one - {@code audit.http.enabled} reads {@code true} in
     * {@code application.yaml} and {@code cp.audit.enabled} reads {@code false}, because the
     * transport's connection factory validates its hosts and port while it is being built and a
     * laptop has no audit broker. Condition (b) of Principle III is carried the rest of the way by
     * a deployed values file setting {@code CP_AUDIT_ENABLED=true} beside the two defaults, with
     * the broker's hosts, port and credentials from Key Vault.
     *
     * <p>Said here because it is sayable nowhere else. Every {@code audit.http.*} bean sits inside
     * the {@code @AutoConfiguration} class the transport's key gates, so the filter that would have
     * published is never constructed and cannot complain; and where it <em>is</em> constructed it
     * swallows its own publishing failures. A deployment that forgot the transport would otherwise
     * look exactly like one that has it.
     *
     * <p>Only settings are named. Nothing a caller supplied and nothing from Key Vault reaches the
     * line, and no throwable is attached to it.
     *
     * @param environment the resolved environment, for the audit library's own keys
     */
    private static void sayWhereNothingIsPublished(final Environment environment) {
        if (switchedOnAsTheLibraryReadsIt(environment, HTTP_AUDIT_ENABLED, false)
                && !switchedOnAsTheLibraryReadsIt(environment, AUDIT_TRANSPORT_ENABLED, false)) {
            LOG.warn("the operations API is being served unaudited: {} is true but {} is not, and"
                            + " every bean the first gates lives inside the auto-configuration"
                            + " class the second gates - so no request and no response is published"
                            + " as an audit event. A deployed environment sets {}=true beside the"
                            + " filter, with its broker's hosts and port",
                    HTTP_AUDIT_ENABLED, AUDIT_TRANSPORT_ENABLED, AUDIT_TRANSPORT_ENABLED);
        }
    }

    /**
     * A transport switched on has to name a broker and a port it could actually reach.
     *
     * <p>The audit filter <strong>swallows its own publishing failures</strong>, so a transport
     * pointed at nothing produces a service that serves every endpoint and says so nowhere but the
     * log - which is the failure mode this service exists to end. The starter's own
     * {@code validateProps} checks {@code hosts.isEmpty()} and {@code port > 0} and nothing else,
     * so a list of blanks and a port of 70000 both pass it and are turned into connectors pointed
     * at nowhere.
     *
     * <p>Gated on the transport's own switch, read with an absent key taken as <strong>off</strong>
     * rather than as the library's {@code matchIfMissing = true}. This service's
     * {@code application.yaml} always sets the key, so a real JVM never has it absent; a context
     * that does is a test harness that did not load the file, and refusing one of those would be
     * refusing a harness rather than a deployment. Where the key really is absent the library
     * builds its connection factory and checks the two things it checks, which is the behaviour
     * this rule is sharpening rather than replacing.
     *
     * @param environment the resolved environment, for the transport's own keys
     */
    private static void validateTheAuditTransportNamesSomewhereToPublish(
            final Environment environment) {

        if (!switchedOnAsTheLibraryReadsIt(environment, AUDIT_TRANSPORT_ENABLED, false)) {
            return;
        }
        final List<String> hosts = Binder.get(environment).bind(AUDIT_HOSTS, HOST_LIST)
                .orElse(List.of());
        if (hosts.isEmpty() || hosts.stream().anyMatch(host -> !hasText(host))) {
            throw new IllegalStateException(publishingNowhere(AUDIT_HOSTS)
                    + " names no broker, and the audit filter swallows every publishing failure -"
                    + " so the events would be lost in silence rather than refused");
        }
        if (!namesAPort(environment.getProperty(AUDIT_PORT))) {
            throw new IllegalStateException(publishingNowhere(AUDIT_PORT)
                    + " must be the port the audit broker listens on, in 1.." + HIGHEST_PORT);
        }
    }

    /**
     * Whether the audit transport's port setting reads as a port at all.
     *
     * <p>Read as text and parsed here rather than asked of the environment as an {@code Integer},
     * because a conversion failure is raised by Spring during the refresh and names neither the
     * setting nor the transport it leaves unpublished - and it quotes the offending value back.
     * FR-053 requires the refusal to name the offending setting, so the reading is this service's
     * own.
     *
     * @param value the raw value of {@code cp.audit.port}, or null where it is unset
     * @return whether it is a whole number in 1..65535
     */
    private static boolean namesAPort(final String value) {
        final String port = value == null ? "" : value.trim();
        final int number = PORT_NUMBER.matcher(port).matches() ? Integer.parseInt(port) : 0;
        return number > 0 && number <= HIGHEST_PORT;
    }

    /**
     * What a transport refusal says before it names its setting.
     *
     * @param setting the transport setting this refusal is about
     * @return the sentence the refusal is built from
     */
    private static String publishingNowhere(final String setting) {
        return AUDIT_TRANSPORT_ENABLED + " is true, so the audit events have to reach a broker"
                + " - but " + setting;
    }

    /**
     * Reads one of the two audit switches the way the library that owns it reads it: as the
     * <strong>literal</strong> string {@code true}.
     *
     * <p>Not a fussy distinction. {@code cp.audit.enabled} gates
     * {@code ArtemisAuditAutoConfiguration} and {@code audit.http.enabled} gates its filter, its
     * parser and its path-parameter service, and all four conditions are
     * {@code @ConditionalOnProperty(havingValue = "true")}, which compares the raw value with
     * {@code equalsIgnoreCase} and matches nothing else. Spring's own conversion is wider: asked
     * for a {@code Boolean}, it reads {@code yes}, {@code on} and {@code 1} as true as well. A
     * deployment that writes {@code yes} would leave the library switched off while reading as
     * switched on to anything that converted - so a rule gated on "the transport is on" would let
     * a value rule fire where there is nothing to configure, or skip one where there is.
     *
     * <p>The {@code whenUnset} argument is the caller's, not the library's, and both callers pass
     * {@code false}. The transport's own class-level condition carries
     * {@code matchIfMissing = true}, so the library would treat an absent key as on; this service's
     * {@code application.yaml} always sets it, so an absent key means a context that did not load
     * the file, and the rules gated on it are about what a deployment configured rather than about
     * what a harness left out. The HTTP half's conditions carry no {@code matchIfMissing}, so for
     * that one {@code false} is the library's reading too.
     *
     * @param environment the resolved environment
     * @param key         the setting to read
     * @param whenUnset   what the owning library's own condition does when the key is absent
     * @return whether the library would consider the switch on
     */
    private static boolean switchedOnAsTheLibraryReadsIt(final Environment environment,
                                                         final String key,
                                                         final boolean whenUnset) {
        final String value = environment.getProperty(key);
        return value == null ? whenUnset : "true".equalsIgnoreCase(value);
    }

    /**
     * The audit filter finds its OpenAPI document by a <strong>suffix</strong> glob,
     * {@code classpath*:} + {@code **}{@code /*} + the value of {@code audit.http.openapi-rest-spec}.
     *
     * <p>Unset, that globs for {@code *null}, matches nothing, and the library throws during the
     * refresh naming neither the setting nor this service. Refused here instead.
     *
     * <p>Refused where the parser that does the globbing would actually be built, which is where
     * <strong>both</strong> switches are on: every {@code audit.http.*} bean the starter declares
     * sits inside the {@code @AutoConfiguration} class {@code cp.audit.enabled} gates, so the HTTP
     * half switched on over a transport that is off builds no parser and globs for nothing. The
     * HTTP half is on by default in this service's own configuration, and a laptop with no audit
     * broker has the transport off - so reading the HTTP switch alone would refuse the local loop
     * for a trap it cannot fall into.
     *
     * @param environment the resolved environment, for the audit library's own keys
     */
    private static void validateTheAuditFilterHasADocumentToRead(final Environment environment) {
        if (switchedOnAsTheLibraryReadsIt(environment, AUDIT_TRANSPORT_ENABLED, false)
                && switchedOnAsTheLibraryReadsIt(environment, HTTP_AUDIT_ENABLED, false)
                && !hasText(environment.getProperty(OPENAPI_REST_SPEC))) {
            throw new IllegalStateException(OPENAPI_REST_SPEC + MUST_BE_SET_WHEN
                    + HTTP_AUDIT_ENABLED + " is true: the filter globs the classpath for a suffix"
                    + " match and an unset value globs for *null, which matches nothing and fails"
                    + " the refresh without naming either this service or the key");
        }
    }

    /**
     * A supersede bound of zero admits no instant at all.
     *
     * <p>Every call would be refused as too old, which is a configuration error wearing a refusal's
     * clothes: the operator reads "the instant you gave is older than the bound" and goes looking
     * at their own argument. Unconditional, because an unusable bound is unusable wherever it is
     * set.
     */
    private static void validateTheSupersedeBoundAdmitsSomething(
            final OperationsProperties operations) {
        requirePositive(operations.supersedeMaxAge(), SUPERSEDE_MAX_AGE);
    }

    /**
     * Zero is the non-blocking attempt at the nightly lock, and is the default; a positive value is
     * a bounded wait. A negative one is neither, and would reach {@code LockConfiguration} as a
     * duration no lock can be asked for.
     */
    private static void validateTheLockAttemptIsSomethingAThreadCanMake(
            final OperationsProperties operations) {
        if (operations.lockWait().isNegative()) {
            throw new IllegalStateException(LOCK_WAIT + " (" + operations.lockWait()
                    + ") must not be negative — zero is the non-blocking attempt at the"
                    + " register-generation lock, and anything positive is a bounded wait for it");
        }
    }

    /**
     * Checks every rule, the intake half's and the downstream half's alike.
     *
     * @param properties the bound settings
     * @param generation the downstream half's settings
     * @param feature    where the one lever is read from
     * @param report     the morning exception report's settings
     * @param brokerUrl  {@code spring.artemis.broker-url}, empty or null where none is configured
     * @throws IllegalStateException if any rule is broken
     */
    public static void validate(final YotResultsDistributionProperties properties,
                                final GenerationProperties generation,
                                final FeatureFlagProperties feature,
                                final ReportProperties report,
                                final String brokerUrl) {
        validate(properties);
        validateReport(report);
        generation.validate();
        validateTheGenerationDurationsAreUsable(generation);
        validateTheSchedulerLockOutlivesTheRun(generation);
        validateTheNotificationClaimOutlastsOnePostCycle(properties);
        feature.validate();
        validateTheStubsAreNotWhereRegistersAreProduced(properties, generation);
        validateThePublishedLocalPairIsNowhereARealFlagIsRead(properties, feature);
        validateGenerationHasTheDownstreamsItNeeds(properties, generation, feature);
        validateWhicheverHalfWritesAFileCanReachTheFileService(properties, generation, report);
        validateWhicheverHalfSendsCanReachNotificationnotify(properties, generation, report);
        validateWhicheverHalfSendsHasAnIdentityToSendUnder(properties, generation, report);
        validateTheCompletionMechanismCanHearAnOutcome(generation, brokerUrl);
    }

    /**
     * Checks the settings the intake half must hold to for the service to be safe to run.
     *
     * @param properties the bound settings
     * @throws IllegalStateException if any rule is broken
     */
    public static void validate(final YotResultsDistributionProperties properties) {
        validateRunFinishesBeforeTheClaimExpires(properties);
        validateLockOutlivesTheRun(properties);
        validateExactlyOneCredentialSource(properties);
        validateThePayloadSourceCanFetch(properties);
        validateTheSubscriptionsSourceCanFetch(properties);
        validateTheSubmissionCanPost(properties);
        validateEveryStepTogetherFinishesInsideTheRun(properties);
        validateTheOutboundValidatorIsOnWhereItIsDeployed(properties);
        validateTheIntakeGaugesCanBeRefreshed(properties);
    }

    /**
     * The intake gauges have to be refreshed by something, and a fixed delay of zero refreshes
     * nothing.
     *
     * <p>On the intake half's own list rather than the report's, because that is where the setting
     * is and where the sweep is: it publishes the age of the oldest unfinished request and the count
     * of unfinished requests over the threshold in every JVM that is not a command, whichever of the
     * other two halves this deployment switched on. A refresh a pod cannot schedule is a pod whose
     * gauges read whatever they were born with for the life of it, and an alert on a gauge that
     * never moves is an alert that never fires.
     */
    private static void validateTheIntakeGaugesCanBeRefreshed(
            final YotResultsDistributionProperties properties) {
        requirePositive(properties.intake().gaugeRefresh(), INTAKE_GAUGE_REFRESH);
    }

    private static void validateRunFinishesBeforeTheClaimExpires(
            final YotResultsDistributionProperties properties) {
        final Duration deadline = properties.claim().processingDeadline();
        final Duration lease = properties.claim().lease();
        if (deadline.compareTo(lease) >= 0) {
            throw new IllegalStateException(
                    PROCESSING_DEADLINE + " (" + deadline + ") must be strictly shorter than " + LEASE
                            + " (" + lease + "), so a slow run stops before its claim can be"
                            + " reclaimed");
        }
    }

    private static void validateLockOutlivesTheRun(final YotResultsDistributionProperties properties) {
        final Duration deadline = properties.claim().processingDeadline();
        final Duration renewal = properties.servicebus().maxAutoLockRenewDuration();
        final Duration required = deadline.plus(RENEWAL_MARGIN);
        if (renewal.compareTo(required) < 0) {
            throw new IllegalStateException(
                    RENEW_DURATION + " (" + renewal + MUST_BE_AT_LEAST + PROCESSING_DEADLINE
                            + " plus the " + RENEWAL_MARGIN + " renewal margin (" + required
                            + "), so the broker lock outlives any legitimate run");
        }
    }

    private static void validateExactlyOneCredentialSource(
            final YotResultsDistributionProperties properties) {
        final boolean hasConnectionString = hasText(properties.servicebus().connectionString());
        final boolean hasNamespace = hasText(properties.servicebus().namespace());
        if (hasConnectionString == hasNamespace) {
            throw new IllegalStateException(
                    "Set exactly one of " + CONNECTION_STRING + " (local and CI) or " + NAMESPACE
                            + " (deployed) — currently "
                            + (hasConnectionString ? "both are set" : "neither is set"));
        }
    }

    /**
     * The payload source must be one that can actually produce a payload.
     *
     * <p>Three separate ways a deployment can look healthy and fetch nothing: the stub selected
     * where the service is deployed, a live source with no identity to authorise its fallback with,
     * and a cache or fallback configured out of existence.
     *
     * <p>Each rule is asked of the source actually selected. The cache and the query side belong to
     * the live adapter and {@code STUB} builds neither of them, so holding a stub run to settings
     * nothing will read would fail a local run configured exactly as it means to be.
     */
    private static void validateThePayloadSourceCanFetch(
            final YotResultsDistributionProperties properties) {
        final YotResultsDistributionProperties.Payload payload = properties.payload();
        if (payload.mode() == PayloadSourceMode.STUB) {
            validateTheStubIsNotDeployed(properties);
        } else {
            validateTheLiveSourceHasAnIdentity(properties);
            validateTheCacheIsAddressable(payload.redis());
            validateTheFallbackIsAttempted(payload.fallback());
            validateTheFetchFinishesInsideTheRun(properties);
        }
    }

    /**
     * Constitution Principle V: the stub must not be reachable in a production profile. A namespace
     * means workload identity, which means a deployed pod — the same discriminator the credential
     * rule above already draws deployment on.
     */
    private static void validateTheStubIsNotDeployed(final YotResultsDistributionProperties properties) {
        if (hasText(properties.servicebus().namespace())) {
            throw new IllegalStateException(
                    PAYLOAD_MODE + IS_STUB_WHILE + NAMESPACE + " is set, which is a deployed"
                            + " environment — the stub fetches nothing, so every request would be"
                            + " settled having produced no register at all");
        }
    }

    /**
     * The query-side fallback authorises with the system user identity, and without one it cannot be
     * used: every cold-cache request would be abandoned, redelivered and dead-lettered by a pod
     * reporting itself healthy throughout.
     */
    private static void validateTheLiveSourceHasAnIdentity(
            final YotResultsDistributionProperties properties) {
        if (!hasText(properties.results().systemUserId())) {
            throw new IllegalStateException(
                    SYSTEM_USER_ID + MUST_BE_SET_WHEN + PAYLOAD_MODE + " is LIVE, because the"
                            + " payload fallback cannot be used without an identity to authorise"
                            + " with");
        }
    }

    private static void validateTheCacheIsAddressable(final YotResultsDistributionProperties.Redis redis) {
        if (!hasText(redis.host())) {
            throw new IllegalStateException(REDIS + ".host must name the payload cache");
        }
        if (!hasText(redis.keyPrefix())) {
            throw new IllegalStateException(
                    REDIS + ".key-prefix must be the prefix the producer writes the payload under,"
                            + " INT_ for this flow — an empty prefix reads a key nobody writes");
        }
        requirePositive(redis.connectTimeout(), REDIS + CONNECT_TIMEOUT_SUFFIX);
        requirePositive(redis.commandTimeout(), REDIS + ".command-timeout");
    }

    private static void validateTheFallbackIsAttempted(
            final YotResultsDistributionProperties.Fallback fallback) {
        if (fallback.maxAttempts() < MINIMUM_ATTEMPTS) {
            throw new IllegalStateException(
                    FALLBACK_MAX_ATTEMPTS + " (" + fallback.maxAttempts() + MUST_BE_AT_LEAST
                            + MINIMUM_ATTEMPTS + " — at zero every cache miss skips a query side that"
                            + " could have answered, and the request is retried to the dead-letter"
                            + " queue instead");
        }
        validateTheBackOffIsUsable(FALLBACK, fallback.initialBackoff(), fallback.maxBackoff());
        requirePositive(fallback.connectTimeout(), FALLBACK + CONNECT_TIMEOUT_SUFFIX);
        requirePositive(fallback.readTimeout(), FALLBACK + READ_TIMEOUT_SUFFIX);
    }

    /**
     * The fetch happens inside the run, and the run must finish before its claim can be reclaimed.
     *
     * <p>So the whole fetch's worst case has to fit inside the processing deadline: the two cache
     * reads that come first, each able to spend its connect and command timeouts, and then every
     * fallback attempt spending its connect and read timeouts with a bounded wait between them. A
     * configuration where it does not guarantees exactly what the deadline exists to prevent: a
     * runner still waiting on a socket while another runner takes its request.
     *
     * <p>Strictly shorter, not merely no longer. The run measures the deadline before the fetch and
     * tests it after, so a fetch that fills the deadline exactly leaves the rest of the run nothing
     * and can only end at {@code PROCESSING_DEADLINE_EXCEEDED}.
     */
    private static void validateTheFetchFinishesInsideTheRun(
            final YotResultsDistributionProperties properties) {
        final YotResultsDistributionProperties.Redis redis = properties.payload().redis();
        final YotResultsDistributionProperties.Fallback fallback = properties.payload().fallback();
        final Duration deadline = properties.claim().processingDeadline();
        final Duration cacheReads = cacheReadsWorstCase(redis);
        final Duration queryReads = queryReadsWorstCase(fallback);
        final Duration worstCase = cacheReads.plus(queryReads);
        if (worstCase.compareTo(deadline) >= 0) {
            throw new IllegalStateException(
                    "The payload settings allow a fetch of up to " + worstCase + " — " + REDIS
                            + " reads of " + cacheReads + " ahead of " + FALLBACK + " attempts of "
                            + queryReads + " — which is not strictly shorter than "
                            + PROCESSING_DEADLINE + " (" + deadline + INSIDE_ITS_OWN_CLAIM);
        }
    }

    /** The two cache reads a fetch makes, each spending both of its timeouts. */
    private static Duration cacheReadsWorstCase(final YotResultsDistributionProperties.Redis redis) {
        return redis.connectTimeout()
                .plus(redis.commandTimeout())
                .multipliedBy(CACHE_READS_PER_FETCH);
    }

    /** Every query-side attempt spending both timeouts, with the waits between them. */
    private static Duration queryReadsWorstCase(
            final YotResultsDistributionProperties.Fallback fallback) {
        return attemptsWorstCase(fallback.connectTimeout(), fallback.readTimeout(),
                fallback.maxAttempts())
                .plus(backOffWorstCase(fallback.maxBackoff(), fallback.maxAttempts()));
    }

    /** The whole payload fetch, or nothing at all where the stub is the selected source. */
    private static Duration payloadFetchWorstCase(final YotResultsDistributionProperties properties) {
        return properties.payload().mode() == PayloadSourceMode.STUB
                ? Duration.ZERO
                : cacheReadsWorstCase(properties.payload().redis())
                        .plus(queryReadsWorstCase(properties.payload().fallback()));
    }

    /**
     * The now-subscriptions source must be one that can actually reach reference data.
     *
     * <p>The same class of hole the payload rules close, on the port that decides who a register is
     * addressed to. A live source with no endpoint or no identity is a pod that abandons, redelivers
     * and finally parks every hearing that produced a register, while readiness, liveness and the
     * queue's own metrics all say the deployment succeeded — and unlike the payload case, nothing
     * downstream ever gets far enough to notice.
     */
    private static void validateTheSubscriptionsSourceCanFetch(
            final YotResultsDistributionProperties properties) {
        final YotResultsDistributionProperties.Referencedata referencedata = properties.referencedata();
        if (referencedata.mode() == SubscriptionsSourceMode.STUB) {
            validateTheEmptyAnswerIsNotDeployed(properties);
            validateTheEmptyAnswerIsNotGivenAboutARealHearing(properties);
        } else {
            validateTheLiveSourceCanAskReferenceData(referencedata);
            validateTheSubscriptionsReadIsAttempted(referencedata);
            validateTheSubscriptionsReadFinishesInsideTheRun(properties);
        }
    }

    /**
     * Constitution Principle V, the same rule {@link #validateTheStubIsNotDeployed} applies to the
     * payload stub: a deployed pod running this one asks reference data nothing, so every hearing
     * it reads completes {@code no-subscriptions} and no register is ever addressed.
     */
    private static void validateTheEmptyAnswerIsNotDeployed(
            final YotResultsDistributionProperties properties) {
        if (hasText(properties.servicebus().namespace())) {
            throw new IllegalStateException(
                    SUBSCRIPTIONS_MODE + IS_STUB_WHILE + NAMESPACE + " is set, which is a"
                            + " deployed environment — the stub asks reference data nothing, so every"
                            + " hearing that produced a register would complete addressed to nobody");
        }
    }

    /**
     * The stub answers "nobody is subscribed", which is a legitimate business outcome, so it must
     * never be given about a hearing anybody could mistake for a real one.
     *
     * <p>This is the pairing that would be indistinguishable from working: a live payload source
     * fetching real hearings, and a subscriptions source that says nobody wants them. Every run
     * would complete {@code no-subscriptions} — the flow's commonest legitimate outcome — and the
     * metrics, the processed log and the queue would all agree that the service was doing its job.
     * The stub cannot make itself safe here by refusing instead: the read happens before the
     * transformation, so a refusal would only trade a silent completion for a queue that never
     * drains. It is the configuration that has to be refused, and it is refused at startup.
     */
    private static void validateTheEmptyAnswerIsNotGivenAboutARealHearing(
            final YotResultsDistributionProperties properties) {
        if (properties.payload().mode() == PayloadSourceMode.LIVE) {
            throw new IllegalStateException(
                    SUBSCRIPTIONS_MODE + IS_STUB_WHILE + PAYLOAD_MODE + " is LIVE - real"
                            + " hearings would be fetched and every one of them completed"
                            + " no-subscriptions, because reference data was never asked");
        }
    }

    /**
     * A query needs somewhere to go and somebody to be from.
     *
     * <p>{@code CJSCPPUID} is part of the reference-data query's own contract and its access-control
     * rules authorise on it, so an anonymous query is a refused query — every time, for ever.
     */
    private static void validateTheLiveSourceCanAskReferenceData(
            final YotResultsDistributionProperties.Referencedata referencedata) {
        if (!hasText(referencedata.baseUrl())) {
            throw new IllegalStateException(
                    REFDATA_BASE_URL + " must name the reference-data context when "
                            + SUBSCRIPTIONS_MODE + " is LIVE, because the now-subscriptions query has"
                            + " nowhere to go without it");
        }
        if (!hasText(referencedata.systemUserId())) {
            throw new IllegalStateException(
                    REFDATA_SYSTEM_USER_ID + MUST_BE_SET_WHEN + SUBSCRIPTIONS_MODE + " is LIVE,"
                            + " because reference data authorises the now-subscriptions query on"
                            + " CJSCPPUID and refuses an anonymous one");
        }
    }

    private static void validateTheSubscriptionsReadIsAttempted(
            final YotResultsDistributionProperties.Referencedata referencedata) {
        if (referencedata.maxAttempts() < MINIMUM_ATTEMPTS) {
            throw new IllegalStateException(
                    REFDATA_MAX_ATTEMPTS + " (" + referencedata.maxAttempts() + MUST_BE_AT_LEAST
                            + MINIMUM_ATTEMPTS + " — at zero the query is never made and every"
                            + " hearing that produced a register is parked having asked nobody");
        }
        validateTheBackOffIsUsable(
                REFDATA, referencedata.initialBackoff(), referencedata.maxBackoff());
        requirePositive(referencedata.connectTimeout(), REFDATA + CONNECT_TIMEOUT_SUFFIX);
        requirePositive(referencedata.readTimeout(), REFDATA + READ_TIMEOUT_SUFFIX);
    }

    /**
     * The now-subscriptions read happens inside the run, so its worst case has to fit inside it.
     *
     * <p>Every attempt can spend its connect and its read timeout, with a wait bounded by
     * {@code max-backoff} between them, and nothing else bounds the total. Ten attempts against a
     * minute-long read is a startup that succeeds and a run that is still waiting on a socket ten
     * minutes later — long after its claim became reclaimable and another delivery began processing
     * the same request.
     *
     * <p>The bound here is per-step: it refuses a reference-data read that cannot finish inside a
     * run at all. Whether this step and the two around it fit inside one run <em>together</em> is a
     * different question, and it is asked by
     * {@link #validateEveryStepTogetherFinishesInsideTheRun}.
     */
    private static void validateTheSubscriptionsReadFinishesInsideTheRun(
            final YotResultsDistributionProperties properties) {
        final Duration deadline = properties.claim().processingDeadline();
        final Duration worstCase = subscriptionsReadWorstCase(properties);
        if (worstCase.compareTo(deadline) >= 0) {
            throw new IllegalStateException(
                    "The " + REFDATA + " settings allow a now-subscriptions read of up to "
                            + worstCase + ", which is not strictly shorter than "
                            + PROCESSING_DEADLINE + " (" + deadline + INSIDE_ITS_OWN_CLAIM);
        }
    }

    /** The whole now-subscriptions read, or nothing where the stub is selected. */
    private static Duration subscriptionsReadWorstCase(
            final YotResultsDistributionProperties properties) {
        final YotResultsDistributionProperties.Referencedata referencedata = properties.referencedata();
        return referencedata.mode() == SubscriptionsSourceMode.STUB
                ? Duration.ZERO
                : attemptsWorstCase(referencedata.connectTimeout(), referencedata.readTimeout(),
                        referencedata.maxAttempts())
                        .plus(backOffWorstCase(referencedata.maxBackoff(),
                                referencedata.maxAttempts()));
    }

    /**
     * The submission must be able to make the call this whole service exists to make.
     *
     * <p>The endpoint and the identity are asked of a live pipeline, which is every deployed one:
     * {@link #validateTheStubIsNotDeployed} already refuses a stub payload source wherever a
     * namespace is set, so no deployment reaches the POST through the exemption a local stub run
     * relies on — and a local stub run never fetches a hearing, so it never reaches the POST at all.
     *
     * <p>{@code max-attempts} below one is the setting that matters most: the loop that POSTs the
     * register never runs, every hearing is handed back as an unresolved transient failure, and the
     * queue fills with deliveries that were never attempted — silent non-delivery wearing a retry
     * policy's clothes, and unobservable except as a queue that will not drain.
     */
    private static void validateTheSubmissionCanPost(final YotResultsDistributionProperties properties) {
        if (properties.payload().mode() == PayloadSourceMode.LIVE) {
            validateTheSubmissionHasSomewhereToPost(properties.progression());
        }
        validateTheRetryPolicyCanPost(properties.progression());
        validateTheSubmissionFinishesInsideTheRun(properties);
    }

    private static void validateTheSubmissionHasSomewhereToPost(
            final YotResultsDistributionProperties.Progression progression) {
        if (!hasText(progression.baseUrl())) {
            throw new IllegalStateException(
                    PROGRESSION_BASE_URL + " must name the progression context when " + PAYLOAD_MODE
                            + " is LIVE, because the add-court-register command has nowhere to go"
                            + " without it");
        }
        if (!hasText(progression.systemUserId())) {
            throw new IllegalStateException(
                    PROGRESSION_SYSTEM_USER_ID + MUST_BE_SET_WHEN + PAYLOAD_MODE + " is LIVE,"
                            + " because progression authorises the add-court-register command on"
                            + " CJSCPPUID and refuses an anonymous one — every register, one 403 at"
                            + " a time");
        }
    }

    private static void validateTheRetryPolicyCanPost(
            final YotResultsDistributionProperties.Progression progression) {
        if (progression.maxAttempts() < MINIMUM_ATTEMPTS) {
            throw new IllegalStateException(
                    PROGRESSION_MAX_ATTEMPTS + " (" + progression.maxAttempts() + MUST_BE_AT_LEAST
                            + MINIMUM_ATTEMPTS + ": a policy with no attempts posts no register at"
                            + " all and hands every hearing back unsent");
        }
        validateTheBackOffIsUsable(
                PROGRESSION, progression.initialBackoff(), progression.maxBackoff());
        requirePositive(progression.connectTimeout(), PROGRESSION + CONNECT_TIMEOUT_SUFFIX);
        requirePositive(progression.readTimeout(), PROGRESSION + READ_TIMEOUT_SUFFIX);
    }

    /**
     * The POST happens inside the run the payload fetch and the reference-data read happen in, so
     * its worst case is held to the same bound theirs are.
     *
     * <p>Every attempt can spend its connect and read timeouts, and between them sits a wait the
     * policy bounds at {@code max-backoff} — which is where a retry policy that looks modest stops
     * being one. A run still waiting on the last of them when its claim becomes reclaimable is a
     * second runner processing the same hearing, and this flow POSTs a document that progression
     * appends rather than replaces.
     */
    private static void validateTheSubmissionFinishesInsideTheRun(
            final YotResultsDistributionProperties properties) {
        final YotResultsDistributionProperties.Progression progression = properties.progression();
        final Duration deadline = properties.claim().processingDeadline();
        final Duration attempts = attemptsWorstCase(progression.connectTimeout(),
                progression.readTimeout(), progression.maxAttempts());
        final Duration waits =
                backOffWorstCase(progression.maxBackoff(), progression.maxAttempts());
        final Duration worstCase = submissionWorstCase(properties);
        if (worstCase.compareTo(deadline) >= 0) {
            throw new IllegalStateException(
                    "The " + PROGRESSION + " settings allow a submission of up to " + worstCase
                            + " — attempts of " + attempts + " and back-off waits of " + waits
                            + " — which is not strictly shorter than " + PROCESSING_DEADLINE + " ("
                            + deadline + INSIDE_ITS_OWN_CLAIM);
        }
    }

    /** Every POST attempt spending both timeouts, with the back-off waits between them. */
    private static Duration submissionWorstCase(final YotResultsDistributionProperties properties) {
        final YotResultsDistributionProperties.Progression progression = properties.progression();
        return attemptsWorstCase(progression.connectTimeout(), progression.readTimeout(),
                progression.maxAttempts())
                .plus(backOffWorstCase(progression.maxBackoff(), progression.maxAttempts()));
    }

    /**
     * One run, one budget: the three network steps and the rest of the run, against the deadline.
     *
     * <p>Every rule above asks whether <em>one</em> step can outlast the deadline, and three
     * separate "no"s do not answer the question that matters. The steps are spent inside one run
     * holding one claim, so what has to fit inside the processing deadline is their sum plus the
     * margin the guard's writes and the transformation need. A configuration where it does not is
     * a runner still waiting on a socket while its claim is reclaimed and a second delivery starts
     * the same request — and for this flow that is not a wasted retry: progression's
     * {@code add-court-register} appends a register per POST, so the second runner's send is a
     * second register for the hearing.
     *
     * <p>A step no adapter makes costs the run nothing, which is why the two stubbed sources
     * contribute zero — the same reading the per-step rules take.
     */
    private static void validateEveryStepTogetherFinishesInsideTheRun(
            final YotResultsDistributionProperties properties) {
        final Duration deadline = properties.claim().processingDeadline();
        final Duration payload = payloadFetchWorstCase(properties);
        final Duration subscriptions = subscriptionsReadWorstCase(properties);
        final Duration submission = submissionWorstCase(properties);
        final Duration worstCase =
                payload.plus(subscriptions).plus(submission).plus(RUN_OVERHEAD_MARGIN);
        if (worstCase.compareTo(deadline) >= 0) {
            throw new IllegalStateException(
                    "One run spends every step in turn, and together they allow up to " + worstCase
                            + " — a " + PAYLOAD + " fetch of " + payload + ", a " + REFDATA
                            + " read of " + subscriptions + ", a " + PROGRESSION + " submission of "
                            + submission + " and the fixed " + RUN_OVERHEAD_MARGIN
                            + " the guard's writes and the transformation need — which is not"
                            + " strictly shorter than " + PROCESSING_DEADLINE + " (" + deadline
                            + INSIDE_ITS_OWN_CLAIM);
        }
    }

    /**
     * What every attempt costs when each one spends both of its timeouts.
     *
     * <p><strong>The arithmetic is unchanged by the transport's attempt reservation</strong>
     * ({@code adapter/http/RetryPolicy.attemptFitsBefore}), and deliberately so. This formula
     * already charges every attempt its whole connect-plus-read worst case, which is the same number
     * the transport now reserves before starting one; a run that refuses an attempt it cannot afford
     * spends less than this, never more. So the reservation makes the real worst case smaller and
     * leaves the bound this method computes exactly where it was — the startup budget stays the
     * pessimistic one, which is the only kind worth refusing a deployment over.
     */
    private static Duration attemptsWorstCase(final Duration connectTimeout,
                                              final Duration readTimeout,
                                              final int maxAttempts) {
        return connectTimeout.plus(readTimeout).multipliedBy(maxAttempts);
    }

    /**
     * The shared retry policy's two waits, held to what a wait has to be to be takeable.
     *
     * <p>One rule for all four clients, because there is one policy: {@code initial-backoff} and
     * {@code max-backoff} mean the same thing wherever they are read, and so does a configuration
     * that makes them unusable. A negative first wait throws from inside the retry rather than being
     * taken, and a ceiling below the first wait shortens the very wait it exists to bound.
     *
     * @param prefix         the settings prefix, so the message names the client that is misconfigured
     * @param initialBackoff the first wait between retryable attempts
     * @param maxBackoff     the ceiling on any wait
     */
    private static void validateTheBackOffIsUsable(final String prefix,
            final Duration initialBackoff, final Duration maxBackoff) {
        if (initialBackoff.isNegative()) {
            throw new IllegalStateException(
                    prefix + INITIAL_BACKOFF_SUFFIX + " (" + initialBackoff + ") must not be"
                            + " negative: a negative wait throws from inside the retry rather than"
                            + " being taken");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalStateException(
                    prefix + MAX_BACKOFF_SUFFIX + " (" + maxBackoff + MUST_BE_AT_LEAST + prefix
                            + INITIAL_BACKOFF_SUFFIX + " (" + initialBackoff + "), or the ceiling"
                            + " shortens the very wait it exists to bound");
        }
    }

    /**
     * Every wait a retry policy can take, at its worst.
     *
     * <p><strong>{@code max-backoff} per wait, not the doubling schedule.</strong> The schedule is
     * what the client waits when nothing tells it otherwise, and it is not the bound: a
     * {@code Retry-After} is honoured on <em>every</em> retryable answer in all four clients, and
     * the only thing limiting what a remote service can ask for is {@code max-backoff}. So a service
     * answering {@code Retry-After: 3600} on every attempt costs a full ceiling per wait, and a
     * budget computed from the doubling would licence a run that cannot finish inside its claim —
     * which is exactly the shape this validation exists to refuse. The doubling only ever makes the
     * real cost smaller.
     *
     * @param maxBackoff  the ceiling on any wait
     * @param maxAttempts total attempts including the first
     * @return the worst case the waits between those attempts can cost
     */
    private static Duration backOffWorstCase(final Duration maxBackoff, final int maxAttempts) {
        return maxAttempts <= MINIMUM_ATTEMPTS
                ? Duration.ZERO
                : maxBackoff.multipliedBy(maxAttempts - 1L);
    }

    /**
     * The C29 pre-send validation is what turns a schema-invalid document into an explicit, recorded
     * failure instead of the 400 the legacy pipeline swallowed, losing the whole hearing's register.
     * A deployed pod with it switched off is back in that failure mode with none of the legacy's
     * excuses, so it does not start.
     *
     * <p>This is the whole of what the setting does. {@code PipelineConfig} wires the validator
     * unconditionally and no bean reads the value, so switching it off outside a deployed
     * environment changes nothing at runtime — a wiring that honoured it would put C29's blind spot
     * back behind one property. A suite that needs a shape the schemas reject to reach the wire gets
     * it from {@code ProgressionCommandGatewayTest} over WireMock instead.
     */
    private static void validateTheOutboundValidatorIsOnWhereItIsDeployed(
            final YotResultsDistributionProperties properties) {
        if (!properties.submission().validateOutbound()
                && hasText(properties.servicebus().namespace())) {
            throw new IllegalStateException(
                    VALIDATE_OUTBOUND + " is false while " + NAMESPACE + " is set, which is a"
                            + " deployed environment — without the pre-send check an invalid"
                            + " document is a 400 nobody sees and a register nobody can find");
        }
    }

    /**
     * The two durations the generation half decides for itself, held to being durations at all.
     *
     * <p>Unconditional, like the zone rule and the lock rule beside them, and for the same reason: a
     * job that happens to be disabled in this deployment is not a reason to accept a value that
     * would be wrong in the next one.
     *
     * <p>{@code stale-after} is the destructive one. It decides when a run gives up on a batch still
     * awaiting its render, fails it and releases its registers into that night's assembly, so a
     * value of zero or less is a run that fails every batch it can see and re-renders every court
     * centre day, every night. {@code batch-age-refresh} is the fixed delay the batch-age readings
     * are taken on, and a non-positive delay is a reading nobody ever takes again - which would
     * leave three gauges holding whatever they last said, for ever, because a Micrometer gauge does
     * not decay.
     *
     * @param generation the downstream half's settings
     * @throws IllegalStateException if either duration is zero or negative
     */
    private static void validateTheGenerationDurationsAreUsable(
            final GenerationProperties generation) {
        requirePositive(generation.staleAfter(), GENERATION_STALE_AFTER);
        requirePositive(generation.batchAgeRefresh(), GENERATION_BATCH_AGE_REFRESH);
    }

    /**
     * The nightly run's lock has to outlast the run it locks, by a margin nothing can set to zero.
     *
     * <p>The ShedLock lock expires on its own after {@code lock-at-most-for} whether the run has
     * finished or not, which is what makes it safe against a pod that dies mid-run and is exactly
     * why it cannot be shorter than a run is allowed to be. A deployment that lengthens
     * {@code run-deadline} and leaves the lock where it was gets a window in which a run is still
     * inside its hour and the lock is free for another replica to take; the second run assembles
     * nothing (the first run's stamps are already on the rows) but it does read, request and notify
     * for every batch the first has not reached, which is two documents and two e-mails for every
     * court centre in the country.
     *
     * <p>Unconditional, like the zone rule: a job that happens to be disabled in this deployment is
     * not a reason to accept a lock that cannot cover the run in the next one. The margin is fixed
     * for the reason {@link #RUN_OVERHEAD_MARGIN} is - a lock expiring the instant the deadline
     * does is a lock the last batch of a run races - and it is what
     * {@code application.yaml} ships the two settings apart by.
     *
     * @param generation the downstream half's settings
     * @throws IllegalStateException if the lock cannot cover the run deadline plus the margin
     */
    private static void validateTheSchedulerLockOutlivesTheRun(
            final GenerationProperties generation) {

        final Duration required = generation.runDeadline().plus(SCHEDULER_LOCK_MARGIN);
        if (generation.lockAtMostFor().compareTo(required) < 0) {
            throw new IllegalStateException(
                    LOCK_AT_MOST_FOR + " (" + generation.lockAtMostFor() + MUST_BE_AT_LEAST
                            + RUN_DEADLINE + " (" + generation.runDeadline() + ") plus the "
                            + SCHEDULER_LOCK_MARGIN + " margin (" + required + "), so a run still"
                            + " inside its hour cannot be joined by the replica that took the lock"
                            + " it had already lost");
        }
    }

    /**
     * The notifying leg's claim has to outlast one recipient's POST cycle, twice over.
     *
     * <p>The notification claim's lease bounds work whose length it cannot know from itself: a batch
     * is addressed to as many Youth Offending Teams as subscribed to its court centre, and each of
     * them costs up to {@code max-attempts} POSTs with a connect timeout, a read timeout and a
     * back-off wait apiece. The claim is renewed before every POST - the retries of one recipient
     * included - and before every settlement, so what the lease has to cover is the retry cycle one
     * recipient's turn can become - and a lease
     * shorter than that expires under a notifier still waiting on a socket, after which a second
     * notifier takes the batch over, derives the same owed set from the same records, and a Youth
     * Offending Team is sent a register about children twice.
     *
     * <p>The formula is therefore the transport's own worst case for one recipient - which is the
     * arithmetic the run's per-step budgets are computed from, {@link #attemptsWorstCase} plus
     * {@link #backOffWorstCase} over the shared endpoints settings - times
     * {@link #NOTIFICATION_LEASE_MARGIN} as margin:
     *
     * <pre>
     *   max-attempts x (connect-timeout + read-timeout) + (max-attempts - 1) x max-backoff
     * </pre>
     *
     * <p>At the shipped {@code yotresultsdistribution.endpoints.*} values that is
     * {@code 2 x (3 x (5s + 10s) + 2 x 2s)} = 98s, which the shipped fifteen-minute lease clears
     * comfortably; the rule bites on the deployment that lengthens a timeout or raises the attempt
     * budget and leaves the lease where it was.
     *
     * <p><strong>The connect timeout is charged</strong>, because an attempt that hangs on the
     * connect and then on the read is the longest single thing an outbound call does and it is the
     * shape a claim is really lost inside - a notifier waiting on a socket is a notifier not
     * renewing. A bound that counted the read alone said a five-minute connect timeout cost nothing,
     * so a deployment could lengthen the setting that decides how long a POST to a mesh host that is
     * not answering takes to fail and keep a lease the first attempt of the night outlives.
     *
     * <p><strong>And the waits are the gaps between attempts</strong>, of which there is one fewer
     * than there are attempts: nothing is waited after the last one, because there is nothing left
     * to wait for. {@code max-backoff} rather than the doubling schedule, for the reason
     * {@link #backOffWorstCase} gives: a {@code Retry-After} is honoured on every retryable answer
     * and the ceiling is the only thing bounding what the other side can ask for.
     *
     * <p>Unconditional, like the zone and scheduler-lock rules: a job that happens to be disabled in
     * this deployment is not a reason to accept a lease that cannot cover a POST cycle in the next
     * one. And in practice the rule is broken from the other side - nobody sets a short lease
     * deliberately, but a deployment that gives notificationnotify five minutes to answer has
     * quietly made the shipped lease too short.
     *
     * @param properties the bound settings, for the lease and the transport it is measured against
     * @throws IllegalStateException if the lease cannot cover one POST cycle twice over
     */
    private static void validateTheNotificationClaimOutlastsOnePostCycle(
            final YotResultsDistributionProperties properties) {

        final YotResultsDistributionProperties.Endpoints endpoints = properties.endpoints();
        final Duration attempts = attemptsWorstCase(endpoints.connectTimeout(),
                endpoints.readTimeout(), endpoints.maxAttempts());
        final Duration waits = backOffWorstCase(endpoints.maxBackoff(), endpoints.maxAttempts());
        final Duration onePostCycle = attempts.plus(waits);
        final Duration required = onePostCycle.multipliedBy(NOTIFICATION_LEASE_MARGIN);
        final Duration lease = properties.notification().claimLease();
        if (lease.compareTo(required) < 0) {
            throw new IllegalStateException(
                    NOTIFICATION_CLAIM_LEASE + " (" + lease + MUST_BE_AT_LEAST
                            + NOTIFICATION_LEASE_MARGIN + " x " + ENDPOINTS_MAX_ATTEMPTS + " ("
                            + endpoints.maxAttempts() + ") attempts of "
                            + ENDPOINTS_CONNECT_TIMEOUT + " (" + endpoints.connectTimeout()
                            + ") plus " + ENDPOINTS_READ_TIMEOUT + " (" + endpoints.readTimeout()
                            + ") - " + attempts + " - and the " + ENDPOINTS + MAX_BACKOFF_SUFFIX
                            + " (" + endpoints.maxBackoff() + ") waits between them - " + waits
                            + " - which is the longest a single POST cycle can take ("
                            + onePostCycle + "), doubled as margin (" + required
                            + "). A shorter lease expires under a notifier still waiting on one"
                            + " recipient's POST, and the notifier that then takes the batch over"
                            + " sends that court centre's register a second time");
        }
    }

    /**
     * Constitution Principle V, applied to the four downstreams the nightly run has.
     *
     * <p>The same rule the payload and now-subscriptions stubs are held to, and the same two
     * discriminators: a namespace means workload identity, which means a deployed pod, and an
     * enabled generation means a pod that means to produce registers tonight. A stubbed renderer or
     * notifier in either of those places is a run that completes, counts its batches, reports
     * itself healthy - and sends nobody anything.
     */
    private static void validateTheStubsAreNotWhereRegistersAreProduced(
            final YotResultsDistributionProperties properties, final GenerationProperties generation) {
        validateTheStubIsSomewhereItCanDoNoHarm(SDG_MODE, generation.sdgMode(), properties,
                generation);
        validateTheStubIsSomewhereItCanDoNoHarm(NN_MODE, generation.nnMode(), properties,
                generation);
        validateTheStubIsSomewhereItCanDoNoHarm(FILESERVICE_MODE, generation.fileserviceMode(),
                properties, generation);
        validateTheStubIsSomewhereItCanDoNoHarm(FLAG_MODE, generation.flagMode(), properties,
                generation);
    }

    /**
     * One mode, against both discriminators, so the refusal names the one that caught it.
     *
     * @param setting    the mode's own key, so the message names the setting to change
     * @param mode       what that key is currently set to
     * @param properties the bound settings, for the credential source
     * @param generation the downstream half's settings, for the master switch
     */
    private static void validateTheStubIsSomewhereItCanDoNoHarm(final String setting,
            final GenerationProperties.SourceMode mode, final YotResultsDistributionProperties properties,
            final GenerationProperties generation) {
        if (mode == GenerationProperties.SourceMode.STUB) {
            if (hasText(properties.servicebus().namespace())) {
                throw new IllegalStateException(
                        setting + IS_STUB_WHILE + NAMESPACE + " is set, which is a deployed"
                                + " environment - the stub renders, notifies, stores or reads"
                                + " nothing, so a run would count its batches and send nobody"
                                + " anything");
            }
            if (generation.enabled()) {
                throw new IllegalStateException(
                        setting + IS_STUB_WHILE + GENERATION_ENABLED + " is true - a deployment"
                                + " that means to produce registers tonight cannot produce them"
                                + " against a stand-in");
            }
        }
    }

    /**
     * The same rule again, on the key the one lever is read with.
     *
     * <p>The compose loop reads its WireMock stub with a connection string carrying the published
     * local pair ({@code Id=} {@link FeatureFlagProperties#PUBLISHED_LOCAL_ID}). It is not a stub -
     * the reader, the SDK client and the fail-closed parsing are all the deployed ones - but the
     * pair is one no Azure store has ever been given. So it fails in precisely the way the four STUB
     * refusals above exist to prevent: the pod starts, reports itself healthy, waits until 18:00 and
     * is refused by the store, which is UNREADABLE, which is a run skipped and counted and
     * indistinguishable from an outage.
     *
     * <p>Two discriminators. An endpoint whose host ends {@code .azconfig.io} is a real store
     * whatever else is configured. And a Service Bus namespace means a deployed pod: the same
     * discriminator the STUB refusals draw deployment on, so the two rules agree about where
     * "deployed" is. Neither refusal quotes the value, whose parts are the setting's alone to know
     * (constitution Principle VII).
     *
     * <p>Unconditional on the master switch, for the reason the zone and lock rules are: a job that
     * happens to be disabled in this deployment is no reason to accept a pair that cannot read the
     * flag in the next one.
     *
     * @param properties the bound settings, for the Service Bus namespace
     * @param feature    where the one lever is read from, and with which key
     * @throws IllegalStateException if the published pair is anywhere the reading could matter
     */
    private static void validateThePublishedLocalPairIsNowhereARealFlagIsRead(
            final YotResultsDistributionProperties properties, final FeatureFlagProperties feature) {

        final boolean published = feature.connectionStringPart(FeatureFlagProperties.ID_PART)
                .map(String::trim)
                .filter(FeatureFlagProperties.PUBLISHED_LOCAL_ID::equals)
                .isPresent();
        if (published) {
            final String endpoint =
                    feature.connectionStringPart(FeatureFlagProperties.ENDPOINT_PART).orElse(null);
            if (namesARealFlagStore(endpoint)) {
                throw new IllegalStateException(
                        FEATURE_CONNECTION_STRING + CARRIES_THE_PUBLISHED_LOCAL_PAIR_WHILE
                                + "its Endpoint names a real App Configuration store - the local"
                                + " pair is one no store authorises, so every run would be refused"
                                + " the flag and skip, and the legacy would be presumed to be"
                                + " generating");
            }
            if (hasText(properties.servicebus().namespace())) {
                throw new IllegalStateException(
                        FEATURE_CONNECTION_STRING + CARRIES_THE_PUBLISHED_LOCAL_PAIR_WHILE + NAMESPACE
                                + " is set, which is a deployed environment - the flag is read"
                                + " there with the estate's connection string from Key Vault, and"
                                + " the published local pair in its place is a night's registers"
                                + " skipped on a flag nobody could read");
            }
        }
    }

    /**
     * Whether the connection string's endpoint is a real Azure App Configuration store.
     *
     * <p>Parsed rather than matched, because the question is about the host and only a parse can
     * find one: a pattern has to decide where the authority ends before it can look inside it, and
     * every spelling it did not anticipate - the absolute DNS form above among them - is read as
     * "not a store" and admitted. Any scheme, deliberately: an endpoint that names a real store
     * under a scheme no client can be built from is still a real store, and the pair that cannot
     * read it is still the wrong pair to have configured.
     *
     * @param endpoint what the deployment supplied, blank where it supplied nothing
     * @return true where the host, normalised, ends {@code .azconfig.io}
     */
    private static boolean namesARealFlagStore(final String endpoint) {
        return asEndpointUri(endpoint)
                .map(PropertiesValidator::hostOf)
                .filter(host -> host.endsWith(REAL_FLAG_STORE_DOMAIN))
                .isPresent();
    }

    /**
     * The endpoint as the URI a client would be built from, or empty where it is not one.
     *
     * <p>A host is what a client connects to, so a value that names none is not an endpoint however
     * it is spelled - and requiring one is also what requires a port that parses, because a port
     * that is not a number leaves no server authority for a host to be read out of.
     *
     * @param endpoint what the deployment supplied, blank where it supplied nothing
     * @return the parsed endpoint, or empty where no host can be read from it
     */
    private static Optional<URI> asEndpointUri(final String endpoint) {
        Optional<URI> parsed;
        try {
            parsed = hasText(endpoint) ? Optional.of(new URI(endpoint.trim())) : Optional.empty();
        } catch (final URISyntaxException notAUri) {
            parsed = Optional.empty();
        }
        return parsed.filter(uri -> hasText(uri.getHost()));
    }

    /**
     * The endpoint's host, in the one form a question about its domain can be asked of.
     *
     * @param uri an endpoint a host was read from
     * @return the host, lower-cased and without the root label's trailing dot
     */
    private static String hostOf(final URI uri) {
        final String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.endsWith(ROOT_LABEL_DOT)
                ? host.substring(0, host.length() - ROOT_LABEL_DOT.length())
                : host;
    }

    /**
     * Enabling the downstream half is enabling everything it depends on.
     *
     * <p>Each of these is the same failure wearing a different name: the pod starts, reports itself
     * healthy, waits until 18:00 and then cannot read the flag or cannot ask for a render. The
     * endpoint it sends through is required here no longer, because it is required of whichever
     * half sends; the rest are this half's alone. The registers are not late, they are never
     * produced,
     * and the first anybody hears of it is a Youth Offending Team asking where the register is.
     * Every one of these settings arrives from the deployment, so every one of them is a deploy that
     * should have failed.
     *
     * <p>Conditional on the master switch and on nothing else. A local run and every plain
     * context-load test leave all of them unset and are configured exactly as they mean to be: with
     * the job, the listener and the second datasource absent, there is nothing to store a payload
     * in, nothing to render and nobody to notify.
     */
    private static void validateGenerationHasTheDownstreamsItNeeds(
            final YotResultsDistributionProperties properties, final GenerationProperties generation,
            final FeatureFlagProperties feature) {
        if (generation.enabled()) {
            requireForGeneration(feature.connectionString(), FEATURE_CONNECTION_STRING,
                    "the run reads the cutover flag before it does anything else, and an unreadable"
                            + " flag is a run skipped every night");
            requireAReadableConnectionString(feature);
            requireForGeneration(feature.label(), FEATURE_LABEL,
                    "one App Configuration store serves every stack, so an unlabelled read is a read"
                            + " of somebody else's flag or of none");
            requireForGeneration(properties.endpoints().systemdocgenerator(), SDG_ENDPOINT,
                    "the generate-document command has nowhere to go without it");
            validateTheEmailTemplateIsOneNotificationnotifyWillAccept(properties, generation);
        }
    }

    /**
     * Fix P9: the e-mail template id is a deployment fact, checked at startup rather than at 18:00.
     *
     * <p>The legacy resolved it per recipient and, finding it blank, logged a line at INFO and moved
     * on to the next one. The e-mail was never sent, nothing recorded that it had not been, and the
     * batch reported the state it would have reported had every recipient been e-mailed. A whole
     * evening's registers can be lost that way to a value nobody set, and the loss is invisible.
     *
     * <p>Shape as well as presence, because a malformed id fails in exactly the same way and later:
     * notificationnotify refuses the command, every recipient of every batch, on a value that was
     * wrong before the pod ever started. Asked of a LIVE notifier only - the stub sends under no
     * template, and it is already refused wherever registers are really produced.
     */
    private static void validateTheEmailTemplateIsOneNotificationnotifyWillAccept(
            final YotResultsDistributionProperties properties, final GenerationProperties generation) {
        if (generation.nnMode() == GenerationProperties.SourceMode.LIVE) {
            final String template = properties.email().templates().crStandard();
            if (!hasText(template)) {
                throw new IllegalStateException(
                        EMAIL_TEMPLATE + " must be the notificationnotify template the register is"
                                + " sent under when " + GENERATION_ENABLED + " is true and " + NN_MODE
                                + " is LIVE - a blank id discovered at 18:00 is a night's registers"
                                + " unsent, and the legacy logged one line and moved on (P9)");
            }
            if (!UUID_SHAPE.matcher(template).matches()) {
                throw new IllegalStateException(
                        EMAIL_TEMPLATE + " (" + template + ") must be a UUID when " + NN_MODE
                                + " is LIVE - notificationnotify refuses the command on anything"
                                + " else, every recipient of every batch");
            }
        }
    }

    /**
     * A batch has to be able to learn what became of its render.
     *
     * <p>There is one way a batch learns what became of its render: systemdocgenerator publishes the
     * outcome and this service hears it on a durable subscription. It cannot hear anything without a
     * broker to subscribe to, and a run that never learns an outcome is a batch that stays
     * GENERATING until the next run gives up on it - every batch, every night, silently. The rule
     * therefore applies whenever the generation half is enabled, with no setting able to excuse it.
     */
    private static void validateTheCompletionMechanismCanHearAnOutcome(
            final GenerationProperties generation, final String brokerUrl) {
        if (generation.enabled() && !hasText(brokerUrl)) {
            throw new IllegalStateException(
                    BROKER_URL + " must name the broker the outcome events arrive on when "
                            + GENERATION_ENABLED + " is true - without it every batch waits for an"
                            + " outcome nobody will send, until the next run gives up on it");
        }
    }

    /**
     * Whichever half writes a file has to have a file service to write it into.
     *
     * <p>The file service is the one downstream the two outward legs share: the nightly run stores a
     * render payload and hands systemdocgenerator its id, and the morning report stores the
     * exception list as a CSV and hands notificationnotify its id. The URL was required of the
     * generation half alone, which made a pod in FR-004's shape with the e-mail output on a pod that
     * starts clean, reports itself healthy, and fails at 07:00 with nothing to attach - the exact
     * shape of failure this validator exists to turn into a refused deploy.
     *
     * <p>The refusal names <strong>which half asked</strong>, because the value to set is the same
     * either way and the thing an operator has to know is why a pod that renders nothing wants a
     * file service at all.
     *
     * <p>Only the URL. The credentials stay optional for the reason
     * {@link FileServiceDataSourceConfig} gives - a stack that authenticates the pod itself supplies
     * neither - and the URL is the one of the three that has no other way of arriving.
     */
    private static void validateWhicheverHalfWritesAFileCanReachTheFileService(
            final YotResultsDistributionProperties properties, final GenerationProperties generation,
            final ReportProperties report) {

        if (hasText(properties.fileservice().url())) {
            return;
        }
        if (generation.enabled()) {
            throw new IllegalStateException(FILESERVICE_URL + MUST_BE_SET_WHEN + GENERATION_ENABLED
                    + " is true - systemdocgenerator renders only a payload that is already in the"
                    + " file service, and there is nowhere to put one");
        }
        if (report.email().enabled()) {
            throw new IllegalStateException(FILESERVICE_URL + MUST_BE_SET_WHEN
                    + REPORT_EMAIL_ENABLED + " is true - the report's exception list is attached by"
                    + " file-service id, so a morning with nowhere to write the CSV is a morning"
                    + " support is told nothing about, on a pod that renders no document at all");
        }
    }

    /**
     * Whichever half sends has to have a notificationnotify to send through.
     *
     * <p>The second downstream the two outward legs share, and the second rule of this shape: the
     * nightly run posts one send-email-notification per Youth Offending Team, and the morning report
     * posts one per support address with the exception CSV attached by id. The endpoint was required
     * of the generation half alone, which made a pod in FR-004's shape with the e-mail output on a
     * pod that starts clean, reports itself healthy, and fails every send at 07:00 against a client
     * with no base URL - the shape review gate 7 had just moved the file-service URL out of, one
     * setting along.
     *
     * <p>The refusal names <strong>which half asked</strong>, for the reason the file service's
     * does: the value to set is the same either way, and what an operator has to know is why a pod
     * that renders nothing wants a notificationnotify endpoint at all.
     */
    private static void validateWhicheverHalfSendsCanReachNotificationnotify(
            final YotResultsDistributionProperties properties, final GenerationProperties generation,
            final ReportProperties report) {

        if (hasText(properties.endpoints().notificationnotify())) {
            return;
        }
        if (generation.enabled()) {
            throw new IllegalStateException(NN_ENDPOINT + MUST_BE_SET_WHEN + GENERATION_ENABLED
                    + " is true - the send-email-notification command has nowhere to go without it,"
                    + " and every Youth Offending Team on every batch goes untold");
        }
        if (report.email().enabled()) {
            throw new IllegalStateException(NN_ENDPOINT + MUST_BE_SET_WHEN + REPORT_EMAIL_ENABLED
                    + " is true - the report's own client is built over it, so a morning with"
                    + " nowhere to post is support told nothing, on a pod that renders no document"
                    + " at all");
        }
    }

    /**
     * Whichever half sends has to have an identity to send under.
     *
     * <p>The gap review gate 7 named and deliberately left: it fixed the endpoint and recorded that
     * {@code yotresultsdistribution.endpoints.system-user-id} was asked of neither half, because giving it a
     * rule inside a remediation commit would have been a new refusal wearing a bug fix's clothes.
     * This is the gate that catalogues it.
     *
     * <p>Both outward legs put the value in the {@code CJSCPPUID} header of every
     * {@code send-email-notification} they make, and the framework refuses a command without one.
     * So a deployment that sets the endpoint and forgets the identity is a pod that starts clean,
     * reports itself healthy, and has every send refused - the Youth Offending Teams at 18:00, or
     * support at 07:00. It is the same shape as the endpoint rule beside it, one setting along.
     *
     * <p>The value is <strong>never quoted back</strong>: it is a secret, and a startup failure is a
     * log line in the same index as every other (constitution Principle VII).
     */
    private static void validateWhicheverHalfSendsHasAnIdentityToSendUnder(
            final YotResultsDistributionProperties properties, final GenerationProperties generation,
            final ReportProperties report) {

        if (hasText(properties.endpoints().systemUserId())) {
            return;
        }
        if (generation.enabled()) {
            throw new IllegalStateException(ENDPOINTS_SYSTEM_USER_ID + MUST_BE_SET_WHEN
                    + GENERATION_ENABLED + " is true - every send-email-notification is made under"
                    + " it, and a command with no CJSCPPUID is refused: every Youth Offending Team"
                    + " on every batch goes untold");
        }
        if (report.email().enabled()) {
            throw new IllegalStateException(ENDPOINTS_SYSTEM_USER_ID + MUST_BE_SET_WHEN
                    + REPORT_EMAIL_ENABLED + " is true - the report's own send is made under it, so"
                    + " a morning with no identity to post under is support told nothing, on a pod"
                    + " that renders no document at all");
        }
    }

    /**
     * A setting the downstream half cannot run without, and the consequence of its absence.
     *
     * @param value       what the deployment supplied
     * @param setting     the key, so the message names the setting to set
     * @param consequence what happens at 18:00 without it, so the message says why it matters
     */
    private static void requireForGeneration(final String value, final String setting,
            final String consequence) {
        if (!hasText(value)) {
            throw new IllegalStateException(
                    setting + MUST_BE_SET_WHEN + GENERATION_ENABLED + " is true - "
                            + consequence);
        }
    }

    /**
     * The connection string has to be one a client can be built from, not merely a value that is set.
     *
     * <p>Presence is not enough, and the refusal belongs here rather than in the SDK.
     * {@code ConfigurationClientBuilder.connectionString} parses the value as the reader is built -
     * during refresh, on every pod - and throws on a part it cannot read under an exception that
     * names no setting of this service's and may quote what it was given, which is the one value
     * this service holds that must never be quoted. So the three parts the SDK needs are required
     * here, under the setting's own name, and the refusal carries none of them.
     *
     * <p><strong>The endpoint is parsed rather than matched</strong>, because the shapes that get
     * past a pattern are the ones that fail furthest from here. {@code http://foo:bad} is a port
     * that is not a number and throws where the reader is built; {@code http://:} is worse, because
     * the builder accepts it - the client is built on an empty host, every read at 18:00 fails to
     * connect, and an unreadable flag is a run skipped and counted and indistinguishable from a store
     * outage. So the endpoint is required to parse as written, to carry one of
     * {@link #CLIENT_SCHEMES}, and to name a host; the secret is required to be the Base64 the SDK
     * decodes it as.
     *
     * <p>Asked only where generation is enabled. The reader is built on every pod, since
     * {@code GET /operations/flag} is served on every pod, but a pod with no nightly job and no
     * connection string answers {@code NOT_CONFIGURED} rather than refusing to start.
     *
     * @param feature what the deployment supplied for the flag store
     */
    private static void requireAReadableConnectionString(final FeatureFlagProperties feature) {
        final boolean endpointReadable = feature
                .connectionStringPart(FeatureFlagProperties.ENDPOINT_PART)
                .filter(endpoint -> endpoint.equals(endpoint.strip()))
                .flatMap(PropertiesValidator::asEndpointUri)
                .map(URI::getScheme)
                .filter(scheme -> CLIENT_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT)))
                .isPresent();
        final boolean idPresent = feature.connectionStringPart(FeatureFlagProperties.ID_PART)
                .filter(PropertiesValidator::hasText)
                .isPresent();
        final boolean secretReadable = feature
                .connectionStringPart(FeatureFlagProperties.SECRET_PART)
                .filter(PropertiesValidator::hasText)
                .filter(PropertiesValidator::isBase64)
                .isPresent();
        if (!endpointReadable || !idPresent || !secretReadable) {
            throw new IllegalStateException(
                    FEATURE_CONNECTION_STRING + " must be an App Configuration connection string"
                            + " when " + GENERATION_ENABLED + " is true - Endpoint=<an http or https"
                            + " URL with a host>;Id=<the key's id>;Secret=<the key's Base64"
                            + " secret>, as Key Vault holds it. The client is built as this context"
                            + " starts, and a string with a part missing or unreadable is either a"
                            + " pod that never starts or a client that cannot reach anything, read"
                            + " as an unreadable flag every night. The value is not quoted here");
        }
    }

    /**
     * Whether a secret is the Base64 the SDK decodes it as.
     *
     * @param secret the connection string's secret part
     * @return {@code true} where it decodes
     */
    private static boolean isBase64(final String secret) {
        boolean decodes;
        try {
            Base64.getDecoder().decode(secret);
            decodes = true;
        } catch (final IllegalArgumentException notBase64) {
            decodes = false;
        }
        return decodes;
    }

    /**
     * The morning report's own refusals - a schedule nothing can read, a threshold that reports
     * everything as late, a schedule read in the wrong zone, a rendering limit of zero whichever of
     * its two sources it came from, a lock that cannot cover the run it locks, and an e-mail output
     * enabled with nobody to send to or no template to send under.
     *
     * <p>Unconditional on {@link ReportProperties#enabled()}, for the reason the generation half's
     * zone and lock rules are unconditional on its own switch: a report that happens to be disabled
     * in this deployment is not a reason to accept a setting that would be wrong in the next one.
     * The two e-mail rules are the exception, because neither of their settings is required until
     * something sends.
     *
     * <p>No refusal here quotes an address or a template id back. A startup failure is a log line in
     * the same index as every other, recipients are people's addresses, and naming the setting is
     * what an operator needs in order to fix it.
     *
     * @param report the report's settings
     * @throws IllegalStateException if any of the report's rules is broken
     */
    /* default */ static void validateReport(final ReportProperties report) {
        validateTheScheduleCanBeRead(report);
        GenerationProperties.requireTheCourtsZone(report.zone(), report.zoneOverrideAcknowledged(),
                REPORT_ZONE, REPORT_ZONE_OVERRIDE_ACKNOWLEDGED, REPORT_HOUR);
        requirePositive(report.requestTerminalWithin(), REPORT_REQUEST_TERMINAL_WITHIN);
        requirePositive(report.notifiedWithin(), REPORT_NOTIFIED_WITHIN);
        validateTheRenderingLimitIsUsable(report);
        validateTheReportCanCarryAtLeastOneException(report);
        validateTheReportLockOutlivesItsRun(report);
        validateTheReportCanReachSomebody(report);
    }

    /**
     * A report capped at nothing is a morning that reads exactly like a quiet one.
     *
     * <p>The cap exists so that one very bad night cannot become a line-per-exception write that
     * outlives its own lock, and it bounds the two late kinds alone - the failure kinds are carried
     * whole, because a failure one window dropped is a failure no window would report again. At
     * zero it keeps no late entry at all: the summary's five counts would still be true and every
     * late event would be missing, which is the same silence the whole feature exists to end - and
     * it would be discovered at 07:00 on the morning it mattered rather than at startup.
     */
    private static void validateTheReportCanCarryAtLeastOneException(final ReportProperties report) {
        if (report.maxEntries() < ONE_EXCEPTION) {
            throw new IllegalStateException(REPORT_MAX_ENTRIES + " (" + report.maxEntries()
                    + ") must be at least " + ONE_EXCEPTION + " - a report that carries no"
                    + " late exception at all is indistinguishable from a morning with nothing"
                    + " wrong on it");
        }
    }

    /**
     * A schedule nothing can read is two failures at once, and neither is discovered before 07:00.
     *
     * <p>The cron is the run's trigger <em>and</em> the window it reads back over
     * ({@code ReportWindow.forScheduledRun} for the job, {@code sinceLastScheduledRun} for the
     * command), so an unparseable one is a job
     * {@code @Scheduled} refuses at refresh and a window neither the run nor the command can open.
     * Unconditional on {@link ReportProperties#enabled()}, like the zone rule beside it: a schedule
     * that would be wrong in the next deployment is not made right by this one having the report
     * switched off.
     *
     * <p>The value is not quoted back. It is an operator's own text and this refusal is a log line;
     * naming the setting is what is needed in order to fix it.
     */
    private static void validateTheScheduleCanBeRead(final ReportProperties report) {
        if (!CronExpression.isValidExpression(report.cron())) {
            throw new IllegalStateException(
                    REPORT_CRON + " must be a schedule Spring's six-field dialect can read - it is"
                            + " both the run's trigger and the window the run reads back over, so"
                            + " an unreadable one is a job that never fires and a window nothing"
                            + " can open");
        }
    }

    /**
     * The rendering limit has to be usable, and it is the report's own value to get wrong.
     *
     * <p>It borrowed the generation half's grace period until 004 retired it. The two answer
     * different questions now - "when should support be told a render is late" and "when does a run
     * give up and re-batch" - so the report keeps a value of its own and a refusal names the report's
     * own key. Left unchecked it is a limit of zero seconds, under which every batch in the estate is
     * late on its first morning and the report says nothing useful ever again.
     */
    private static void validateTheRenderingLimitIsUsable(final ReportProperties report) {
        requirePositive(report.batchGeneratedWithin(), REPORT_BATCH_GENERATED_WITHIN);
    }

    /**
     * The lock over the morning run has to outlast the run it locks.
     *
     * <p>The same arrangement the nightly run's lock is in, and refused the same way. The run budget
     * is fixed rather than configured, so there is only one number an operator can get wrong here -
     * and a lock that expires under a run still reading is a lock the next replica takes, after
     * which support is sent the same morning twice by two pods that each believe they are the only
     * one.
     */
    private static void validateTheReportLockOutlivesItsRun(final ReportProperties report) {
        final Duration required = REPORT_RUN_BUDGET.plus(SCHEDULER_LOCK_MARGIN);
        if (report.lockAtMostFor().compareTo(required) < 0) {
            throw new IllegalStateException(
                    REPORT_LOCK_AT_MOST_FOR + " (" + report.lockAtMostFor() + MUST_BE_AT_LEAST
                            + "the fixed " + REPORT_RUN_BUDGET + " run budget plus the "
                            + SCHEDULER_LOCK_MARGIN + " margin (" + required + "), so a run still"
                            + " reading cannot be joined by the replica that took the lock it had"
                            + " already lost");
        }
    }

    /**
     * An e-mail output that is switched on must have somewhere to send and something to send under.
     *
     * <p>The same argument as fix P9 makes for the register's own template, one morning earlier: a
     * deployment that sends nothing every morning and says so only in a log line is the silence this
     * service exists to end, whereas a deployment that cannot start is a deployment that gets fixed.
     * The Log Analytics output is unaffected either way, which is why the e-mail switch is its own.
     */
    private static void validateTheReportCanReachSomebody(final ReportProperties report) {
        if (!report.email().enabled()) {
            return;
        }
        final String template = report.email().templateId();
        requireForReport(template, REPORT_EMAIL_TEMPLATE,
                "notificationnotify refuses the command on a blank id, every recipient of every"
                        + " morning");
        if (!UUID_SHAPE.matcher(template).matches()) {
            throw new IllegalStateException(
                    REPORT_EMAIL_TEMPLATE + " must be a UUID when " + REPORT_EMAIL_ENABLED
                            + " is true - notificationnotify refuses the command on anything else,"
                            + " and the id is not quoted back here because a refusal is a log line");
        }
        if (report.email().recipients().isEmpty()) {
            throw new IllegalStateException(
                    REPORT_EMAIL_RECIPIENTS + MUST_BE_SET_WHEN + REPORT_EMAIL_ENABLED
                            + " is true - an e-mail output with nobody to send to is a morning"
                            + " report nobody receives, and nothing says so");
        }
        // No null check on the entry: ReportProperties.Email freezes the list with List.copyOf,
        // which refuses a null element before the validator ever sees it. An empty entry is not a
        // null one and is refused here, by the address rule, which is where the stray separator
        // that produces it belongs.
        for (final String recipient : report.email().recipients()) {
            if (!ADDRESS_SHAPE.matcher(recipient.trim()).matches()) {
                throw new IllegalStateException(
                        REPORT_EMAIL_RECIPIENTS + " must be a comma-separated list of addresses"
                                + " when " + REPORT_EMAIL_ENABLED + " is true - one entry is not"
                                + " one, and it is deliberately not quoted back: it is somebody's"
                                + " address and this refusal is a log line");
            }
        }
    }

    /**
     * A setting the morning report's e-mail output cannot send without, and the consequence of its
     * absence.
     *
     * @param value       what the deployment supplied
     * @param setting     the key, so the message names the setting to set
     * @param consequence what happens at 07:00 without it, so the message says why it matters
     */
    private static void requireForReport(final String value, final String setting,
            final String consequence) {
        if (!hasText(value)) {
            throw new IllegalStateException(
                    setting + MUST_BE_SET_WHEN + REPORT_EMAIL_ENABLED + " is true - " + consequence);
        }
    }

    private static void requirePositive(final Duration value, final String setting) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalStateException(
                    setting + " (" + value + ") must be positive — a timeout that never expires is"
                            + " a run that never ends");
        }
    }

    /**
     * A blank value counts as unset: a deployed environment overrides the local connection string
     * with an empty value rather than deleting the key, and treating that as "set" would fail every
     * deployment as ambiguous.
     */
    private static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }
}
