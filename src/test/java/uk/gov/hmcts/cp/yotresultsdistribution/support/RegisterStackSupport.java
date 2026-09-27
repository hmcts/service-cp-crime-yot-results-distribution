package uk.gov.hmcts.cp.yotresultsdistribution.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload.HearingPayloadCacheKey;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.payload.ResultsQueryHearingPayloadClient;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.ProgressionCommandGateway;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.refdata.ReferenceDataNowSubscriptionsClient;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;

/**
 * Everything outside the pod, for a suite that wants the pipeline as it really runs.
 *
 * <p>{@link ServiceTestSupport} starts the service against the store and the broker with the payload,
 * reference-data and submission ports stubbed out, because the suites it was written for are about
 * settlement and the processed log. That leaves the whole middle of this service — the cache, the
 * query fallback, the now-subscriptions read, the twelve mappers, the contract validator and the POST
 * — proven only against mocks of whatever sits either side of it. This fixture supplies the other
 * three dependencies for real, so a suite can drive a hearing from the queue to a socket.
 *
 * <p><strong>One WireMock server, three contexts.</strong> The results-query, reference-data and
 * progression paths are disjoint, so one server can be all three at once; three would be three ports,
 * three lifecycles and three chances to reset the wrong one. What a suite gains from it is the
 * assertion no per-adapter suite can make — that a register refused before it is sent reaches
 * <em>no</em> socket at all — because "nothing was posted" is a claim about the one server that would
 * have received it.
 *
 * <p><strong>Every context answers politely by default.</strong> The emulator queue is shared with
 * every other {@code *IT} in the build, so a neighbouring suite's message can reach a consumer this
 * fixture is behind. Unstubbed hearings are therefore answered with an empty envelope, unstubbed
 * days with nobody subscribed, and any POST with a 202: a stranger's message completes
 * {@code no-defendants} and leaves, instead of being retried to the dead-letter queue by a suite it
 * has nothing to do with. A case that wants a specific answer stubs over the default, which WireMock
 * resolves in the more specific stub's favour ({@link #DEFAULT_PRIORITY} against the default).
 */
public final class RegisterStackSupport implements AutoCloseable {

    /** The catch-all stubs' priority: any stub a case adds is more specific and wins. */
    private static final int CATCH_ALL_PRIORITY = 10;

    /** The priority a case's own stub carries, which beats the catch-all. */
    private static final int DEFAULT_PRIORITY = 5;

    private static final int ACCEPTED = 202;

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /**
     * An envelope with nothing in it: a hearing that gathers nobody, so a stranger's request
     * completes {@code no-defendants} rather than failing inside this suite's scenario.
     *
     * <p>Nothing in it resembles hearing content. Every defendant on a court register is a child, and
     * a placeholder shaped like a payload invites an assertion to depend on it.
     */
    private static final String EMPTY_ENVELOPE =
            "{\"hearing\":{\"courtCentre\":{}},\"sharedTime\":\"1970-01-01T00:00:00Z\"}";

    /** Reference data's answer when nobody is subscribed, which is a legitimate business outcome. */
    private static final String NOBODY_SUBSCRIBED = "{\"nowSubscriptions\":[]}";

    /**
     * The master defendant the surviving-youth hearing's register covers.
     *
     * <p>Named only by {@link #payloadOfAnAppealTheChildIsTheAppellantOn(UUID)}, which has to put
     * that same child on the application's applicant for the appellant branch to be reached.
     */
    private static final String APPELLANT_CHILD = "6647df67-a065-4d07-90ba-a8daa064ecc4";

    private final WireMockServer contexts;
    private final RedisClient cacheClient;
    private final StatefulRedisConnection<String, String> cache;

    private RegisterStackSupport(final WireMockServer contexts, final RedisClient cacheClient,
            final StatefulRedisConnection<String, String> cache) {
        this.contexts = contexts;
        this.cacheClient = cacheClient;
        this.cache = cache;
    }

    /**
     * Starts the three HTTP contexts and connects to the shared payload cache.
     *
     * @return the running stack, to be closed by the caller
     */
    // PMD.CloseResource: the client is not a local resource — it is handed to the instance this
    // method returns and is shut down by close(), which is the only point at which the suite that
    // owns it is finished with the cache.
    @SuppressWarnings("PMD.CloseResource")
    public static RegisterStackSupport start() {
        final WireMockServer contexts = new WireMockServer(wireMockConfig().dynamicPort());
        contexts.start();
        final RedisClient client = RedisClient.create(RedisURI.create(RedisTestSupport.uri()));
        final RegisterStackSupport stack =
                new RegisterStackSupport(contexts, client, client.connect());
        stack.answerPolitely();
        return stack;
    }

    /**
     * The settings that point the service at this stack, on top of
     * {@link ServiceTestSupport#start(Map)}'s own defaults.
     *
     * <p>The two modes are the whole point: {@code LIVE} selects the cache-and-fallback payload
     * adapter, the reference-data client and — because the submission configuration is chosen by the
     * payload mode — the real progression gateway. The retry policies are shortened rather than
     * removed: what a suite here needs is that a retryable answer <em>is</em> retried, not how long
     * the deployed configuration waits between attempts, and a suite that spent the shipped
     * back-offs would spend most of its runtime asleep.
     *
     * @return the settings
     */
    public Map<String, String> settings() {
        final Map<String, String> settings = new LinkedHashMap<>();
        settings.put("yotresultsdistribution.payload.mode", "LIVE");
        settings.put("yotresultsdistribution.referencedata.mode", "LIVE");
        settings.put("yotresultsdistribution.payload.redis.host", RedisTestSupport.host());
        settings.put("yotresultsdistribution.payload.redis.port", String.valueOf(RedisTestSupport.port()));
        settings.put("yotresultsdistribution.results.base-url", contexts.baseUrl());
        settings.put("yotresultsdistribution.referencedata.base-url", contexts.baseUrl());
        settings.put("yotresultsdistribution.progression.base-url", contexts.baseUrl());
        settings.put("yotresultsdistribution.referencedata.system-user-id", ServiceTestSupport.SYSTEM_USER_ID);
        settings.put("yotresultsdistribution.payload.fallback.max-attempts", "2");
        settings.put("yotresultsdistribution.payload.fallback.initial-backoff", "50ms");
        settings.put("yotresultsdistribution.payload.fallback.max-backoff", "100ms");
        settings.put("yotresultsdistribution.referencedata.max-attempts", "2");
        settings.put("yotresultsdistribution.referencedata.initial-backoff", "50ms");
        settings.put("yotresultsdistribution.referencedata.max-backoff", "100ms");
        settings.put("yotresultsdistribution.progression.max-attempts", "2");
        settings.put("yotresultsdistribution.progression.initial-backoff", "50ms");
        settings.put("yotresultsdistribution.progression.max-backoff", "100ms");
        return settings;
    }

    /**
     * The same stack, with the deployment stating the last stage that POSTs.
     *
     * <p>For the suites whose subject <em>is</em> the POST: what the gateway sends, what it does with
     * what progression answers, and what a run that ran out of budget does about a command
     * progression appends. {@code yotresultsdistribution.output} defaults to {@code record}, under which a
     * register is written into this service's own store and progression is never called at all, so a
     * suite about the submission has to say which of the two last stages it is about rather than
     * inherit an answer that would leave it asserting against a socket nothing was ever sent to.
     *
     * <p>It is a statement about the deployment and not a second cutover lever - the value is fixed
     * for the life of a release, and the one lever is the App Configuration flag. The suites about
     * accounting, tracing and settlement take {@link #settings()} and run in the default mode, as a
     * deployed pod does.
     *
     * @return the settings
     */
    public Map<String, String> postingSettings() {
        final Map<String, String> settings = settings();
        settings.put("yotresultsdistribution.output", "progression-post");
        return settings;
    }

    // --- the payload cache -----------------------------------------------------------------------

    /**
     * Publishes a hearing into the cache under the dated key, as the producer does.
     *
     * <p>The key is built by the service's own {@link HearingPayloadCacheKey}, not by a copy of its
     * rule: a key that is nearly right reads nothing, and a cache miss is indistinguishable from a
     * hearing nobody cached.
     *
     * @param hearingId  the hearing the payload belongs to
     * @param hearingDay the day the request names
     * @param payload    the claim-check envelope
     */
    public void cached(final UUID hearingId, final LocalDate hearingDay, final JsonNode payload) {
        cache.sync().set(HearingPayloadCacheKey.cacheKey("INT_", hearingId, hearingDay),
                payload.toString());
    }

    /**
     * Publishes whatever the caller likes under the dated key, parseable or not.
     *
     * <p>For the one thing {@link #cached(UUID, LocalDate, JsonNode)} cannot express: a cached value
     * that is not a payload at all. A truncated write is the everyday version, and the interesting
     * half of it is what the cache adapter says while treating the key as absent — a parser quotes
     * the token it choked on, and in a truncated hearing that token is a child.
     *
     * @param hearingId  the hearing the value is stored against
     * @param hearingDay the day the request names
     * @param value      whatever is stored under the key
     */
    public void cachedRaw(final UUID hearingId, final LocalDate hearingDay, final String value) {
        cache.sync().set(HearingPayloadCacheKey.cacheKey("INT_", hearingId, hearingDay), value);
    }

    /**
     * Forgets whatever was cached for a hearing, under both key forms.
     *
     * @param hearingId  the hearing
     * @param hearingDay the day the request names
     */
    public void notCached(final UUID hearingId, final LocalDate hearingDay) {
        cache.sync().del(HearingPayloadCacheKey.cacheKey("INT_", hearingId, hearingDay),
                HearingPayloadCacheKey.cacheKey("INT_", hearingId, null));
    }

    // --- the results-query context ----------------------------------------------------------------

    /**
     * The query side holds this hearing.
     *
     * @param hearingId the hearing
     * @param payload   the claim-check envelope it answers with
     */
    public void queryHolds(final UUID hearingId, final JsonNode payload) {
        contexts.stubFor(queryFor(hearingId).willReturn(okJson(payload.toString())));
    }

    /**
     * The query side answers, and holds nothing — the same answer a {@code 404} gives.
     *
     * @param hearingId the hearing
     */
    public void queryHoldsNothing(final UUID hearingId) {
        contexts.stubFor(queryFor(hearingId).willReturn(aResponse().withStatus(404)));
    }

    /**
     * The query side answers with a status and a body of the caller's choosing.
     *
     * <p>For the suites whose subject is what this service does with somebody else's words. A query
     * side explaining a refusal quotes the hearing it refused about, and the exception a client
     * raises from it carries that text wherever the exception goes.
     *
     * @param hearingId the hearing
     * @param status    the status it answers with
     * @param body      the body it answers with
     */
    public void queryAnswers(final UUID hearingId, final int status, final String body) {
        contexts.stubFor(queryFor(hearingId)
                .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    /**
     * The query side does not hold the hearing the first time it is asked, and holds it afterwards.
     *
     * <p>One 404 per delivery, deliberately: the query client treats a {@code 404} as "not held"
     * rather than as a failure, so it never asks twice inside one run. The first delivery therefore
     * meets a cold cache and an empty query side and is handed back; the second finds the payload.
     * That is the C32 pair and its recovery, made deterministic — a suite that seeded the cache
     * partway through would be racing the broker's redelivery.
     *
     * @param hearingId the hearing
     * @param payload   the envelope the query side holds from the second ask onwards
     */
    public void queryHoldsNothingThenHolds(final UUID hearingId, final JsonNode payload) {
        final String scenario = "query-" + hearingId;
        contexts.stubFor(queryFor(hearingId)
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(404))
                .willSetStateTo("held"));
        contexts.stubFor(queryFor(hearingId)
                .inScenario(scenario)
                .whenScenarioStateIs("held")
                .willReturn(okJson(payload.toString())));
    }

    /**
     * The number of times the query side was asked for a hearing.
     *
     * @param hearingId the hearing
     * @return the count
     */
    public int queriesFor(final UUID hearingId) {
        return contexts.findAll(getRequestedFor(urlEqualTo(queryPath(hearingId)))).size();
    }

    private static MappingBuilder queryFor(final UUID hearingId) {
        return get(urlEqualTo(queryPath(hearingId))).atPriority(DEFAULT_PRIORITY);
    }

    private static String queryPath(final UUID hearingId) {
        return ResultsQueryHearingPayloadClient.PATH.replace("{hearingId}", hearingId.toString());
    }

    // --- the reference-data context ----------------------------------------------------------------

    /**
     * Reference data's answer for every day this suite asks about.
     *
     * <p>Keyed on the path rather than on the {@code on=} day: which day a register is read for is
     * derived from the payload's own share instant, and a suite that restated that derivation in a
     * stub would be asserting its own arithmetic rather than the service's.
     *
     * @param subscriptions the subscriptions in force
     */
    public void subscriptionsInForce(final JsonNode... subscriptions) {
        contexts.stubFor(subscriptionsRead()
                .willReturn(okJson(NowSubscriptionFixtures.answerOf(subscriptions).toString())));
    }

    /**
     * Reference data answers the now-subscriptions read with a status.
     *
     * @param status the status it answers with
     */
    public void subscriptionsAnswer(final int status) {
        contexts.stubFor(subscriptionsRead().willReturn(aResponse().withStatus(status)));
    }

    /**
     * Reference data answers the now-subscriptions read with a status and a body.
     *
     * @param status the status it answers with
     * @param body   the body it answers with, which names the organisations it was asked about
     */
    public void subscriptionsAnswer(final int status, final String body) {
        contexts.stubFor(subscriptionsRead()
                .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    /**
     * Reference data refuses the read a number of times, and answers properly afterwards.
     *
     * <p>The refusal count is the suite's, because the client retries inside one run: a scenario
     * that recovered after a single refusal would prove the retry and never reach a redelivery.
     *
     * @param status        the status it refuses with
     * @param refusals      how many answers are refusals
     * @param subscriptions what it answers with once the refusals are spent
     */
    public void subscriptionsRefuseThenAnswer(
            final int status, final int refusals, final JsonNode... subscriptions) {
        final String scenario = "referencedata-" + UUID.randomUUID();
        for (int refusal = 0; refusal < refusals; refusal++) {
            contexts.stubFor(subscriptionsRead()
                    .inScenario(scenario)
                    .whenScenarioStateIs(refusal == 0 ? Scenario.STARTED : "refused-" + refusal)
                    .willReturn(aResponse().withStatus(status))
                    .willSetStateTo("refused-" + (refusal + 1)));
        }
        contexts.stubFor(subscriptionsRead()
                .inScenario(scenario)
                .whenScenarioStateIs("refused-" + refusals)
                .willReturn(okJson(NowSubscriptionFixtures.answerOf(subscriptions).toString())));
    }

    /**
     * Reference data answers slowly the first time, and at once afterwards.
     *
     * <p>Slowly rather than late, and the difference is the whole of what a deadline suite can
     * inject. {@code PropertiesValidator} budgets every step's connect and read timeouts, plus a
     * fixed margin, against the processing deadline, so on any configuration the service will start
     * on, a delay long enough to reach the deadline is a delay the read timeout cuts short first —
     * which is a payload failure, not an overrun. A body that arrives in pieces trips no timeout at
     * all, because each individual read returns promptly; only the run's own budget notices.
     *
     * @param chunks        how many pieces the body arrives in
     * @param overMillis    how long the whole body takes to arrive
     * @param subscriptions the subscriptions in force, answered slowly and then at once
     */
    public void subscriptionsDribbleThenAnswer(
            final int chunks, final int overMillis, final JsonNode... subscriptions) {
        final String answer = NowSubscriptionFixtures.answerOf(subscriptions).toString();
        final String scenario = "referencedata-slow-" + UUID.randomUUID();
        contexts.stubFor(subscriptionsRead()
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(answer).withChunkedDribbleDelay(chunks, overMillis))
                .willSetStateTo("prompt"));
        contexts.stubFor(subscriptionsRead()
                .inScenario(scenario)
                .whenScenarioStateIs("prompt")
                .willReturn(okJson(answer)));
    }

    /**
     * How many times reference data was asked for the subscriptions in force.
     *
     * <p>Counted over the path rather than over the {@code on=} day, for the reason the stub is
     * keyed that way: the day is derived from the payload's own share instant, and a count keyed on
     * a day this fixture restated would be counting its own arithmetic.
     *
     * @return the count
     */
    public int subscriptionReads() {
        return contexts.findAll(
                getRequestedFor(urlPathEqualTo(ReferenceDataNowSubscriptionsClient.PATH))).size();
    }

    private static MappingBuilder subscriptionsRead() {
        return get(urlPathEqualTo(ReferenceDataNowSubscriptionsClient.PATH))
                .atPriority(DEFAULT_PRIORITY);
    }

    // --- the progression context ------------------------------------------------------------------

    /**
     * Progression's answer to the {@code add-court-register} command.
     *
     * @param status the status it answers with
     */
    public void progressionAnswers(final int status) {
        contexts.stubFor(addCourtRegister().willReturn(aResponse().withStatus(status)));
    }

    /**
     * Progression's answer to the command, with the words it chose to explain itself.
     *
     * <p>A refusal from {@code add-court-register} quotes the document it refused, and that document
     * is a court register: the body of a 400 or a 422 from this endpoint can name a child.
     *
     * @param status the status it answers with
     * @param body   the body it answers with
     */
    public void progressionAnswers(final int status, final String body) {
        contexts.stubFor(addCourtRegister()
                .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    /**
     * Progression's answers, in order: the first status once, then the second for ever.
     *
     * <p>A scenario rather than two stubs, because what is under test is that the retry happens at
     * all — the client has to be answered differently on its second attempt than on its first.
     *
     * @param first the first answer
     * @param then  every answer after it
     */
    public void progressionAnswers(final int first, final int then) {
        final String scenario = "progression-" + UUID.randomUUID();
        contexts.stubFor(addCourtRegister()
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(first))
                .willSetStateTo("answered"));
        contexts.stubFor(addCourtRegister()
                .inScenario(scenario)
                .whenScenarioStateIs("answered")
                .willReturn(aResponse().withStatus(then)));
    }

    /**
     * Every {@code add-court-register} command this stack has received.
     *
     * @return the count
     */
    public int registersPosted() {
        return contexts.findAll(postRequestedFor(
                urlEqualTo(ProgressionCommandGateway.PATH))).size();
    }

    /**
     * The body of one received command, exactly as it arrived.
     *
     * <p>The bytes rather than a re-serialisation of them: {@code processed_output.request_digest}
     * is taken over what was sent, so a suite checking the digest has to hash what the socket
     * carried and not a tree that happens to be equal to it.
     *
     * @param index which command, in arrival order
     * @return the body progression was sent
     */
    public String postedBody(final int index) {
        return contexts.findAll(postRequestedFor(
                urlEqualTo(ProgressionCommandGateway.PATH))).get(index).getBodyAsString();
    }

    /**
     * The body of one received command, parsed.
     *
     * @param index which command, in arrival order
     * @return the document progression was sent
     */
    public JsonNode postedRegister(final int index) {
        return MAPPER.readTree(postedBody(index));
    }

    private static MappingBuilder addCourtRegister() {
        return post(urlEqualTo(ProgressionCommandGateway.PATH)).atPriority(DEFAULT_PRIORITY);
    }

    // --- lifecycle -------------------------------------------------------------------------------

    /**
     * Forgets every stub and every recorded request, and restores the polite defaults.
     *
     * <p>Called between cases so a count means "this case", and so a scenario left mid-state by the
     * previous case cannot answer this one's first attempt.
     */
    public void reset() {
        contexts.resetAll();
        answerPolitely();
    }

    /**
     * The catch-alls: an empty hearing, nobody subscribed, and a 202 for anything posted.
     *
     * <p>See this class's own note. These exist for the messages this suite did not publish.
     */
    private void answerPolitely() {
        contexts.stubFor(get(urlPathMatching(
                ResultsQueryHearingPayloadClient.PATH.replace("{hearingId}", ".*")))
                .atPriority(CATCH_ALL_PRIORITY)
                .willReturn(okJson(EMPTY_ENVELOPE)));
        contexts.stubFor(get(urlPathEqualTo(ReferenceDataNowSubscriptionsClient.PATH))
                .atPriority(CATCH_ALL_PRIORITY)
                .willReturn(okJson(NOBODY_SUBSCRIBED)));
        contexts.stubFor(post(urlEqualTo(ProgressionCommandGateway.PATH))
                .atPriority(CATCH_ALL_PRIORITY)
                .willReturn(acceptedResponse()));
    }

    private static ResponseDefinitionBuilder acceptedResponse() {
        return aResponse().withStatus(ACCEPTED);
    }

    /**
     * A hearing payload from the base fixtures, re-identified as the given hearing.
     *
     * <p>The six base hearings all carry the same hearing id, and a suite that published messages
     * naming it would have every case in the build competing for one processed-log key. The envelope
     * is deep-copied and its hearing id replaced, so a case owns its hearing outright.
     *
     * @param fixture   the file name below {@code fixtures/base/}
     * @param hearingId the hearing the request will name
     * @return the claim-check envelope
     */
    public static JsonNode payload(final String fixture, final UUID hearingId) {
        final JsonNode envelope = LegacyFixtures.readBase(fixture).deepCopy();
        ((ObjectNode) envelope.get("hearing")).put("id", hearingId.toString());
        return envelope;
    }

    /**
     * A hearing that gathers nobody: the same envelope with its cases and applications emptied.
     *
     * <p>Derived rather than authored, because the point of the case it serves is that a real
     * hearing shape with nothing to gather completes {@code no-defendants} — and a hand-built
     * envelope would be asserting that the guard fires on a shape no producer sends. The
     * defendant-level results go with the cases: they name master defendants no case then carries,
     * which the port classifies as a transformation failure rather than an empty gather.
     *
     * @param hearingId the hearing the request will name
     * @return the claim-check envelope
     */
    public static JsonNode payloadWithNothingToGather(final UUID hearingId) {
        final JsonNode envelope = payload("hearing-with-surviving-youth-defendant.json", hearingId);
        final ObjectNode hearing = (ObjectNode) envelope.get("hearing");
        hearing.putArray("prosecutionCases");
        hearing.putArray("courtApplications");
        hearing.putArray("defendantJudicialResults");
        return envelope;
    }

    /**
     * The surviving-youth hearing rebuilt as an appeal the child is the appellant on.
     *
     * <p>The one shape in reach of a whole-stack suite that resolves to a defendant type at all.
     * None of the six base hearings does: {@code getCourtApplicationId} reads element zero of the
     * register's first defendant's case-or-application list and nothing else, and on every base
     * fixture element zero is a prosecution case - which is why all six of the recorded
     * defendant-type goldens answer empty. A suite that asserted the recorded {@code defendant_type}
     * against one of them would be asserting {@code null}, and a pipeline that had dropped the
     * field entirely would satisfy it.
     *
     * <p>Three edits, each of them one the rule reads, and the same three
     * {@code RegisterTransformationChainTest.anAppealTheChildIsTheAppellantOn} makes: the
     * prosecution case goes, so the court application is the first entry the first defendant
     * carries; the applicant gains the master defendant the register covers; and the application
     * type gains the appeal and applicant-appellant flags, which is the branch the
     * {@code synthetic__appellant} golden was recorded against.
     *
     * @param hearingId the hearing the request will name
     * @return the claim-check envelope
     */
    public static JsonNode payloadOfAnAppealTheChildIsTheAppellantOn(final UUID hearingId) {
        final JsonNode envelope = payload("hearing-with-surviving-youth-defendant.json", hearingId);
        final ObjectNode hearing = (ObjectNode) envelope.get("hearing");
        hearing.putArray("prosecutionCases");

        final ObjectNode application = (ObjectNode) hearing.get("courtApplications").get(0);
        final ObjectNode type = (ObjectNode) application.get("type");
        type.put("appealFlag", true);
        type.put("applicantAppellantFlag", true);
        ((ObjectNode) application.get("applicant"))
                .putObject("masterDefendant")
                .put("masterDefendantId", APPELLANT_CHILD);
        return envelope;
    }

    /**
     * The same envelope, dated by a later share of the same results.
     *
     * <p>The register's own instant is read from the envelope's {@code sharedTime} and not from the
     * command, so a re-share that is to supersede rather than tie has to move it - and has to move
     * it inside the same London day, because the day is the batch key and a re-share landing on the
     * next one is a register of its own rather than a replacement (C10, C12).
     *
     * @param payload    the envelope to re-date
     * @param sharedTime the instant the results were shared again
     * @return the re-dated envelope
     */
    public static JsonNode sharedAgainAt(final JsonNode payload, final Instant sharedTime) {
        final JsonNode envelope = payload.deepCopy();
        ((ObjectNode) envelope).put("sharedTime", sharedTime.toString());
        return envelope;
    }

    @Override
    public void close() {
        cache.close();
        cacheClient.shutdown();
        contexts.stop();
    }
}
