package uk.gov.hmcts.cp.yotresultsdistribution.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpErrorContext;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.servicebus.ServiceBusErrorSource;
import com.azure.messaging.servicebus.ServiceBusException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.yotresultsdistribution.application.HearingPayloadSource;
import uk.gov.hmcts.cp.yotresultsdistribution.config.FileServiceDataSourceConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.config.RunProgress;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ServiceBusHealthIndicator;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.support.AdjustableClock;
import uk.gov.hmcts.cp.yotresultsdistribution.support.PostgresTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceBusEmulatorTestSupport;
import uk.gov.hmcts.cp.yotresultsdistribution.support.ServiceTestSupport;

/**
 * The readiness policy, proven against both dependencies going away (spec FR-011).
 *
 * <p>The policy is asymmetric on purpose, and the asymmetry is the whole test:
 *
 * <ul>
 *   <li><strong>The store gates readiness.</strong> Processing is unsafe without the processed log —
 *       a pod that cannot record what it has done must not be sent work — and so does this pod's own
 *       gated start, because a database that replies is not the same thing as a service in a
 *       position to use it.</li>
 *   <li><strong>The queue never gates readiness.</strong> A pod cannot heal a broker by restarting,
 *       so putting the broker in readiness converts a blip into a rolling restart. It is reported as
 *       its own health component and its own gauge, and that is all.</li>
 * </ul>
 *
 * <p><strong>The file-service datasource is the third answer, and it is neither of those two.</strong>
 * It is in the readiness group and it decides for itself when the membership means anything: the
 * pool is open for a few seconds a night, so a file service that is unreachable at 09:00 is not a
 * pod that rolls - the intake half is still recording registers, which is the half that must not
 * stop - while a file service that is unreachable at 18:01 is a run about to fail every batch
 * {@code PAYLOAD_STORE_UNAVAILABLE}, and a replica that cannot do its one nightly job should stop
 * claiming it can. Both halves are asserted here against a real pool and a real database that
 * really goes away; what the component does with the run flag is
 * {@code FileServiceRunHealthIndicatorTest}'s, over a probe it can make answer anything.
 *
 * <p>The staleness rule is asserted against the indicator directly, with a clock the test moves. Its
 * content is entirely "how long ago was that error?", and both interesting cases sit a millisecond
 * either side of the window: a suite that slept could not land on either deliberately, and one that
 * waited a real minute would trade an exact assertion for a slow, approximate one.
 *
 * <p>The store and broker cases here freeze a dependency the whole build shares. That is safe
 * because Gradle runs this build's suites sequentially in one JVM - no other suite is running while
 * a container is paused - and because every freeze is undone in {@code @AfterEach}, including when
 * an assertion fails. The file-service cases freeze nothing: the shared container holds both
 * databases, so a freeze could not tell the two outages apart, and the outage is staged by closing
 * one database this suite owns to connections (see
 * {@code PostgresTestSupport.refuseConnectionsTo}).
 *
 * <p>Two of the cases below are labelled <strong>[A]</strong>: they characterise behaviour the
 * service already had rather than driving new behaviour, so each records a passing run instead of a
 * red one. They are the broker case that holds readiness up for the whole of an outage and the
 * file-service case that takes readiness down during a run. Each is shown non-vacuous by a mutation
 * of the readiness {@code include:} line, quoted on the case itself and in the commit that ran it,
 * and reverted before that commit: the mutations were named when the cases landed and run later,
 * which is why neither is quoted in the commit that introduced them.
 *
 * <p><strong>The third file-service case is not one of them</strong>, and it is why the fix beside
 * it exists. {@code should_keep_readiness_up_while_the_file_service_database_is_down_outside_a_run}
 * was the red half of a pair: it failed against the service as it then stood, and the failing
 * assertion is quoted in the test commit that landed it and the green run in the fix commit that
 * followed. It therefore owes a red run rather than a mutation, and it has one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
// The context owns a running consumer on the shared emulator queue, and this suite deliberately
// breaks that consumer's dependencies. Closing it with the class keeps both facts local.
class ReadinessPolicyIT {

    private static final String STORE_COMPONENT = "db";
    private static final String STARTUP_COMPONENT = "intakeStartup";
    private static final String BROKER_COMPONENT = "servicebus";

    /**
     * The file-service datasource's component, which is in the group and quiet outside a run.
     *
     * <p>Named here because the group's membership is the half of that rule this suite can see: what
     * the component does with the membership - probe during a run, answer UP without asking between
     * them - is {@code FileServiceRunHealthIndicatorTest}'s, and this pod is not generating.
     */
    private static final String FILE_SERVICE_COMPONENT = "fileServiceRun";

    /**
     * The details the file-service component reports, which are two bounded words and no more.
     *
     * <p>Named here because the DOWN case is the one that could leak: a driver's refusal carries the
     * host, the port and the database name in its message, and the component reports the status and
     * never the message (constitution Principle VII).
     */
    private static final String RUN_DETAIL = "run";
    private static final String FILE_SERVICE_DETAIL = "fileservice";
    private static final String IN_PROGRESS = "in-progress";
    private static final String IDLE = "idle";
    private static final String NOT_PROBED = "not-probed";

    /**
     * The file-service database this suite owns, so an outage of it is nobody else's outage.
     *
     * <p>Named apart from {@code GenerationStackSupport}'s and {@code FileServicePayloadStoreIT}'s
     * for the reason those two are named apart from each other: the container is shared, and a
     * suite that closed a database another suite was using would be staging that suite's outage
     * too.
     */
    private static final String FILE_SERVICE_DATABASE = "fileservice_readiness";

    /** What Postgres 16 - the pinned image - answers a connection to a database closed to them. */
    private static final String REFUSED = "not currently accepting connections";

    private static final Duration OBSERVED_WITHIN = Duration.ofSeconds(120);
    private static final Duration POLL = Duration.ofSeconds(1);

    /**
     * How long an outage is held open while readiness is sampled through the whole of it.
     *
     * <p>A window rather than one sample, because the claim is about a rolling restart and a
     * rolling restart needs consecutive failed probes: a case that read readiness once could not
     * tell "readiness never moved" from "readiness had not moved yet".
     *
     * <p><strong>Thirty seconds, because that is the deployed roll threshold.</strong> No
     * deployment manifest ships in this repository, so this constant cannot be derived from one
     * here - but the numbers exist one repository away, and pretending otherwise made the window
     * weaker than the thing it protects. {@code cpp-aks-deploy} ships this service's probes as
     * {@code failureThreshold: 3} over {@code periodSeconds: 10}
     * ({@code ansible/group_vars/yotresultsdistribution-service_values.yaml.j2}), so Kubernetes rolls a pod
     * after about thirty seconds of sustained readiness failure. Holding the outage for that long
     * is what turns "readiness did not flip while we looked" into the claim an operator actually
     * needs: this outage would not have rolled the pod.
     *
     * <p>Read as a floor, not as a mirror of the deployment. If those values change the case does
     * not break - it becomes conservative, which is the safe direction for a window whose job is to
     * be long enough.
     */
    private static final Duration OUTAGE = Duration.ofSeconds(30);

    /** The default window, so the boundary asserted below is the one the service ships with. */
    private static final Duration STALENESS = Duration.ofSeconds(60);

    /** So a failure is reported as a failure rather than as a hang. */
    private static final Duration HELD_AT_MOST = Duration.ofMinutes(2);

    /**
     * The empty claim-check envelope: a hearing that gathered nobody, so a run completes
     * {@code no-defendants}. Nothing in it resembles hearing content — every defendant on a court
     * register is a youth — but it is an envelope, because the pipeline reads the hearing and the
     * share instant out of what the payload port returns.
     */
    private static final JsonNode PLACEHOLDER =
            JacksonConfig.contractObjectMapper().readTree(
                    "{\"stub\":true,\"sharedTime\":\"1970-01-01T00:00:00Z\",\"hearing\":{\"courtCentre\":{}}}");

    private static final String SOURCE = "RECEIVE";
    private static final String ENTITY_PATH = "yotresultsdistribution.requests";

    private static String connectionString;

    /** This suite's file-service database, created once and remembered for the pool below. */
    private static String fileServiceUrl;

    @MockitoBean
    private HearingPayloadSource payloadSource;

    @Autowired
    private HealthEndpoint healthEndpoint;

    /**
     * The run, as the run itself reports it.
     *
     * <p>The seam, and it is named rather than hidden: no generation suite in this repository holds
     * a run open. They all call {@code RegisterGenerationJob.run()} on the test thread and assert
     * on what it returned, so there is nothing to observe readiness through while one is in flight.
     * {@link RunProgress} is the narrowest thing left and it is the run's own signal - the interface
     * exists for exactly this fact, {@code RegisterGenerationJob} raises it around
     * {@code generate(...)} and nowhere else, and {@code RegisterGenerationJobTest} pins that it
     * does. What the case below therefore proves is everything downstream of that signal: the real
     * pool, the real datasource contributor, the real component and the real readiness group.
     */
    @Autowired
    private RunProgress runProgress;

    /** The request whose run is held open across the transport cut. */
    private final UUID held = UUID.randomUUID();

    /** Raised when that run has genuinely started. */
    private final CountDownLatch inFlight = new CountDownLatch(1);

    /** Lowered once the transport has been cut underneath it. */
    private final CountDownLatch release = new CountDownLatch(1);

    @DynamicPropertySource
    static void wireTheContainers(final DynamicPropertyRegistry registry) {
        connectionString = ServiceBusEmulatorTestSupport.connectionString();
        if (fileServiceUrl == null) {
            fileServiceUrl = PostgresTestSupport.createEmptyDatabase(FILE_SERVICE_DATABASE);
        }
        registry.add("spring.datasource.url", PostgresTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport::username);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
        registry.add("yotresultsdistribution.servicebus.connection-string", () -> connectionString);
        ServiceTestSupport.stubPayloadSource(registry);
        registry.add("yotresultsdistribution.progression.system-user-id",
                () -> ServiceTestSupport.SYSTEM_USER_ID);
        registry.add("yotresultsdistribution.results.system-user-id",
                () -> ServiceTestSupport.SYSTEM_USER_ID);
        // A frozen container swallows the connection attempt rather than refusing it, so the driver
        // waits out its connect timeout. The deployed default is thirty seconds, which would make
        // every health poll in this suite a thirty-second block; three keeps the outage observable
        // without changing what is being observed.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "2000");
        // And a socket timeout, because a frozen container stops answering on connections it never
        // closes: a query over a connection the pool already holds would otherwise wait for ever.
        registry.add("spring.datasource.hikari.data-source-properties.socketTimeout", () -> "5");
    }

    @BeforeEach
    void holdOneRunOpen() {
        when(payloadSource.fetch(any(DistributionCommand.class))).thenAnswer(this::payloadFor);
    }

    @AfterEach
    void thawEverything() {
        release.countDown();
        // Both idempotent, and both run whether or not this case staged the outage they undo: a
        // failed assertion must not leave the rest of the build against a frozen server, a database
        // that will not answer, or a pod that believes it is for ever generating.
        runProgress.recordRunEnded();
        PostgresTestSupport.allowConnectionsTo(FILE_SERVICE_DATABASE);
        PostgresTestSupport.unpause();
        ServiceBusEmulatorTestSupport.restore();
    }

    /**
     * The payload port. A neighbouring suite's message is handed the placeholder and passes through.
     */
    private JsonNode payloadFor(final InvocationOnMock invocation) throws InterruptedException {
        final DistributionCommand command = invocation.getArgument(0);
        if (held.equals(command.requestId())) {
            inFlight.countDown();
            release.await(HELD_AT_MOST.toSeconds(), TimeUnit.SECONDS);
        }
        return PLACEHOLDER;
    }

    // --- helpers ---------------------------------------------------------------------------

    private Status readinessStatus() {
        return healthEndpoint.healthForPath("readiness").getStatus();
    }

    private CompositeHealthDescriptor readiness() {
        return (CompositeHealthDescriptor) healthEndpoint.healthForPath("readiness");
    }

    private CompositeHealthDescriptor overall() {
        return (CompositeHealthDescriptor) healthEndpoint.health();
    }

    private Status brokerComponentStatus() {
        final HealthDescriptor component = overall().getComponents().get(BROKER_COMPONENT);
        return component == null ? Status.UNKNOWN : component.getStatus();
    }

    /**
     * The file-service component as readiness sees it, details and all.
     *
     * <p>Read off the readiness group rather than off the bean, because the claim is about what the
     * probe an orchestrator calls says: a component that decided correctly and was aggregated into
     * a group that ignores it would pass every assertion made against the indicator alone.
     */
    private IndicatedHealthDescriptor fileServiceComponent() {
        final HealthDescriptor component = readiness().getComponents().get(FILE_SERVICE_COMPONENT);
        // Named rather than dereferenced. This suite deliberately mutates the readiness group to
        // prove the membership claim, and a component the mutation removed used to arrive here as
        // a NullPointerException pointing at this line - which says a reader broke, not which
        // component went missing. The assertion says the second thing.
        assertThat(component)
                .as("%s is not in the readiness group, so nothing below can be asked about it - "
                        + "the group is %s", FILE_SERVICE_COMPONENT,
                        readiness().getComponents().keySet())
                .isNotNull();
        return (IndicatedHealthDescriptor) component;
    }

    /**
     * Which readiness component holds which status, for a failure message that names the objector.
     *
     * <p>A bare DOWN says the pod is not ready and not which of the three said so, and the whole
     * of the policy is about which of them may say it.
     */
    private Map<String, Status> readinessComponentStatuses() {
        return readiness().getComponents().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, each -> each.getValue().getStatus(),
                        (first, second) -> first, TreeMap::new));
    }

    /** Waits until the gated start has happened, which is where every outage case begins. */
    private void awaitReady() {
        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.UP.equals(readinessStatus()));
    }

    /**
     * A connection-class fault: the link the receiver was using was torn down under it.
     */
    private static Throwable connectionFailure() {
        return new ServiceBusException(
                new AmqpException(true, AmqpErrorCondition.CONNECTION_FORCED,
                        "the connection was forced closed",
                        new AmqpErrorContext("sbemulatorns")),
                ServiceBusErrorSource.RECEIVE);
    }

    private static ServiceBusHealthIndicator indicatorOn(final Clock clock) {
        return new ServiceBusHealthIndicator(
                STALENESS, new ProcessingMetrics(new SimpleMeterRegistry()), clock);
    }

    // --- the policy ------------------------------------------------------------------------

    @Test
    @DisplayName("readiness names the store and never the broker")
    void should_gate_readiness_on_the_store_alone() {
        assertThat(readiness().getComponents())
                .as("the store gates readiness, and so does this pod's own gated start — a database "
                        + "that replies is not a service in a position to use it; the file-service "
                        + "component is the third, and it decides for itself that it has nothing to "
                        + "say outside a run")
                .containsOnlyKeys(STORE_COMPONENT, STARTUP_COMPONENT, FILE_SERVICE_COMPONENT);

        assertThat(readiness().getComponents().get(FILE_SERVICE_COMPONENT).getStatus())
                .as("this pod is not generating and no run is in progress, so a datasource it does "
                        + "not even hold cannot make it unready")
                .isEqualTo(Status.UP);

        assertThat(overall().getComponents())
                .as("the broker is still observable — as its own component, outside readiness")
                .containsKey(BROKER_COMPONENT);
    }

    @Test
    @DisplayName("a store outage takes readiness down, and readiness comes back with the store")
    void should_report_readiness_down_while_the_store_is_unreachable() {
        // Waited for rather than assumed: readiness also covers this pod's own gated start, and the
        // start is on a probe interval. A test that asserted UP the instant the context came up
        // would be asserting that the start had already happened, which is a different claim.
        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.UP.equals(readinessStatus()));

        PostgresTestSupport.pause();
        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.DOWN.equals(readinessStatus()));

        PostgresTestSupport.unpause();
        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.UP.equals(readinessStatus()));
    }

    @Test
    @DisplayName("a queue outage leaves readiness up and shows itself in the broker component")
    void should_keep_readiness_up_and_report_the_broker_down_during_a_queue_outage()
            throws InterruptedException {
        // The outage is staged with work in hand, because that is the only kind the SDK reports: a
        // processor with nothing to do treats a lost connection as retryable and rolls its message
        // pump silently, and five minutes against a broker that had been stopped outright produced
        // no callback of any kind. The evidence the health component is built on is a round trip
        // that failed, so there has to be one.
        //
        // Cutting the transport through the proxy makes that a sequence rather than a race: hold a
        // delivery, cut, then let it finish into a settlement that is refused at once.
        ServiceTestSupport.publish(ServiceTestSupport.validBody(held, UUID.randomUUID()));
        assertThat(inFlight.await(OBSERVED_WITHIN.toSeconds(), TimeUnit.SECONDS))
                .as("the run must genuinely be in flight before the transport is cut")
                .isTrue();

        ServiceBusEmulatorTestSupport.disconnect();
        release.countDown();
        try {
            await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> Status.DOWN.equals(brokerComponentStatus()));

            assertThat(readinessStatus())
                    .as("a broker blip must never roll the pods")
                    .isEqualTo(Status.UP);
        } finally {
            ServiceBusEmulatorTestSupport.restore();
        }

        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.UP.equals(brokerComponentStatus()));
    }

    /**
     * <strong>[A]</strong> Characterisation: the case above already asserts readiness is UP once the
     * broker component has gone DOWN, and this asserts the same thing at every probe for as long as
     * the outage lasts.
     *
     * <p>The distinction is the whole of why a broker is kept out of readiness. Kubernetes does not
     * roll a pod for one failed probe; it rolls one for {@code failureThreshold} consecutive failed
     * probes. So "readiness was UP at the moment the outage was noticed" and "readiness never
     * failed a probe during the outage" are different claims, and only the second is the one spec
     * FR-011 makes. The broker component is held DOWN for the whole window as part of the
     * condition, so the window cannot pass by the outage quietly ending.
     *
     * <p><strong>Non-vacuous by a reverted mutation.</strong> With {@code servicebus} added to the
     * readiness {@code include:} line of {@code application.yaml} - the group the whole policy is
     * about - this case fails, because readiness then follows the broker down and the awaited
     * condition never holds: "org.awaitility.core.ConditionTimeoutException: Condition with Lambda
     * expression in uk.gov.hmcts.cp.yotresultsdistribution.e2e.ReadinessPolicyIT was not fulfilled within 2
     * minutes.", thrown from the {@code during(OUTAGE)} wait. 8 tests completed, 3 failed: this
     * case, the single-probe broker case beside it on "[a broker blip must never roll the pods]
     * expected: UP but was: DOWN", and the membership case on {@code servicebus} being a key it did
     * not expect. Mutation reverted.
     */
    @Test
    @DisplayName("[A] a broker outage fails no readiness probe at all, not merely the first")
    void should_hold_readiness_up_for_the_whole_of_a_broker_outage() throws InterruptedException {
        // Staged exactly as the case above stages it, and for the reason recorded there: a lost
        // connection is only reported by the SDK where a round trip was in hand to fail.
        ServiceTestSupport.publish(ServiceTestSupport.validBody(held, UUID.randomUUID()));
        assertThat(inFlight.await(OBSERVED_WITHIN.toSeconds(), TimeUnit.SECONDS))
                .as("the run must genuinely be in flight before the transport is cut")
                .isTrue();

        ServiceBusEmulatorTestSupport.disconnect();
        release.countDown();
        try {
            await().during(OUTAGE).atMost(OBSERVED_WITHIN).pollInterval(POLL)
                    .until(() -> Status.DOWN.equals(brokerComponentStatus())
                            && Status.UP.equals(readinessStatus()));

            assertThat(readiness().getComponents())
                    .as("and the group is still the same three names under outage: a broker "
                            + "component that only joined readiness when the broker failed would "
                            + "be invisible to a membership assertion made on a healthy pod")
                    .containsOnlyKeys(STORE_COMPONENT, STARTUP_COMPONENT, FILE_SERVICE_COMPONENT);
        } finally {
            ServiceBusEmulatorTestSupport.restore();
        }
    }

    /**
     * The file-service database is down and no run is on, so readiness is not interested.
     *
     * <p><strong>Driven, not characterised.</strong> This case was the red half of a pair and the
     * service failed it as it then stood: {@code db} was contributed as a composite over both pools
     * on a generation-enabled pod, so an unreachable file service reported {@code db} DOWN at any
     * hour and rolled a pod whose intake half was recording registers perfectly well. It failed on
     * the assertion below, at {@code {db=DOWN, fileServiceRun=UP, intakeStartup=UP}} and "expected:
     * UP but was: DOWN"; that assertion is quoted in the test commit that landed it, and the green
     * run in the fix commit that switched the auto-configured contributor off and had
     * {@code config/StoreHealth} contribute {@code db} over the primary pool alone. So it owes a
     * red run rather than a reverted mutation, and the red run is what it has.
     *
     * <p>The outage is a real one and is shown to be real, because the whole case turns on it: a
     * database that was answering all along would pass this assertion without the policy having any
     * part in it. It is staged by closing this suite's own file-service database to connections
     * rather than by freezing a container, because the shared container holds the processed log as
     * well - a freeze would take readiness DOWN through {@code db} and the case would be asserting
     * the opposite of what it claims.
     */
    @Test
    @DisplayName("the file-service database can be unreachable all morning without a pod rolling")
    void should_keep_readiness_up_while_the_file_service_database_is_down_outside_a_run()
            throws SQLException {
        awaitReady();
        PostgresTestSupport.connectTo(FILE_SERVICE_DATABASE);

        PostgresTestSupport.refuseConnectionsTo(FILE_SERVICE_DATABASE);

        assertThatThrownBy(() -> PostgresTestSupport.connectTo(FILE_SERVICE_DATABASE))
                .as("the outage has to be a real one, or this case is about nothing")
                .hasMessageContaining(REFUSED);
        assertThat(readinessStatus())
                .as("readiness is about the work this pod is being sent, and at 09:00 that is "
                        + "intake: a database nothing will touch until 18:00 must not roll a pod "
                        + "whose intake half is recording registers perfectly well. The components "
                        + "say who objected: %s", readinessComponentStatuses())
                .isEqualTo(Status.UP);
        await().during(OUTAGE).atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.UP.equals(readinessStatus()));

        assertThat(fileServiceComponent().getDetails())
                .as("nothing needs that database until 18:00, so nothing asked it - and the "
                        + "component says it did not ask, because UP and UP-unverified are "
                        + "different claims and only one of them is true at 09:00")
                .containsEntry(RUN_DETAIL, IDLE)
                .containsEntry(FILE_SERVICE_DETAIL, NOT_PROBED);
    }

    /**
     * <strong>[A]</strong> Characterisation: the same database, the same outage, and a run in
     * progress - which is the one state in which it matters.
     *
     * <p>A run that cannot write a payload fails every batch {@code PAYLOAD_STORE_UNAVAILABLE}, and
     * a pod that cannot do its one nightly job should stop telling the platform it can. Readiness
     * comes back while the run is still on, so what gates it is the database and not a latch the
     * first failure set.
     *
     * <p>The details are asserted by key as well as by value. This is the one path on which a
     * driver's own words could reach a scraped surface - a refused connection's message carries the
     * host, the port and the database name - and the component reports the status rather than the
     * message (constitution Principle VII). {@code FileServiceRunHealthIndicatorTest} makes the same
     * claim over a probe that throws on demand; this one makes it with a real refusal behind it.
     *
     * <p><strong>Non-vacuous by a reverted mutation.</strong> With {@code fileServiceRun} dropped
     * from the readiness {@code include:} line of {@code application.yaml}, so the component still
     * decides correctly and readiness no longer listens, this case fails: readiness stays UP and
     * the wait for DOWN runs out - "org.awaitility.core.ConditionTimeoutException:
     * Condition with Lambda expression in uk.gov.hmcts.cp.yotresultsdistribution.e2e.ReadinessPolicyIT was
     * not fulfilled within 2 minutes." 8 tests completed, 4 failed: this case, the two cases that
     * assert the group's three component names, and the case above, which reads the component off
     * the group and found none at all - a {@code NullPointerException} on
     * {@code fileServiceComponent()}. Mutation reverted.
     */
    @Test
    @DisplayName("[A] a run that cannot write its payload stops claiming the pod can do its one job")
    void should_report_readiness_down_while_a_run_is_in_progress_and_the_file_service_is_down() {
        awaitReady();

        PostgresTestSupport.refuseConnectionsTo(FILE_SERVICE_DATABASE);
        runProgress.recordRunStarted();

        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.DOWN.equals(readinessStatus()));
        assertThat(fileServiceComponent().getStatus())
                .as("the component readiness aggregated, and not some other pod's opinion of it")
                .isEqualTo(Status.DOWN);
        assertThat(fileServiceComponent().getDetails())
                .as("two bounded words and no third: a health endpoint is scraped and indexed like "
                        + "any other surface, and a driver's refusal names hosts and databases")
                .containsOnlyKeys(RUN_DETAIL, FILE_SERVICE_DETAIL)
                .containsEntry(RUN_DETAIL, IN_PROGRESS)
                .containsEntry(FILE_SERVICE_DETAIL, Status.DOWN.getCode());

        PostgresTestSupport.allowConnectionsTo(FILE_SERVICE_DATABASE);

        await().atMost(OBSERVED_WITHIN).pollInterval(POLL)
                .until(() -> Status.UP.equals(readinessStatus()));
        assertThat(fileServiceComponent().getDetails())
                .as("the run has not ended, so the component is still asking - it is the database "
                        + "that gates readiness here and not a flag the first refusal set")
                .containsEntry(RUN_DETAIL, IN_PROGRESS)
                .containsEntry(FILE_SERVICE_DETAIL, Status.UP.getCode());
    }

    @Test
    @DisplayName("an unresolved error older than the staleness window, on an idle queue, is not an outage")
    void should_stop_reporting_an_error_that_nothing_has_contradicted_or_repeated() {
        final AdjustableClock clock = AdjustableClock.startingAt(Instant.parse("2026-08-31T09:00:00Z"));
        final ServiceBusHealthIndicator indicator = indicatorOn(clock);

        // The broker has answered this consumer before: that is what entitles a later silence to the
        // idle-queue reading. A consumer never answered at all keeps reporting the fault — the SDK
        // will not repeat it, so aging it out would hide a total outage — and that case is asserted
        // in ServiceBusHealthIndicatorTest.
        indicator.recordTraffic();
        clock.advance(Duration.ofMinutes(5));

        indicator.recordProcessorError(SOURCE, ENTITY_PATH, connectionFailure());
        assertThat(indicator.health().getStatus())
                .as("a fresh, unresolved connection failure is an outage")
                .isEqualTo(Status.DOWN);

        clock.advance(STALENESS.minusSeconds(1));
        assertThat(indicator.health().getStatus())
                .as("still inside the window, and still unresolved")
                .isEqualTo(Status.DOWN);

        clock.advance(Duration.ofSeconds(2));
        assertThat(indicator.health().getStatus())
                .as("an idle queue produces no traffic, and absence of traffic is not an outage")
                .isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("the gauge answers a scrape correctly without the health endpoint being asked first")
    void should_expose_the_broker_gauge_to_a_scrape_that_never_calls_health() {
        final AdjustableClock clock = AdjustableClock.startingAt(Instant.parse("2026-08-31T09:00:00Z"));
        final SimpleMeterRegistry scraped = new SimpleMeterRegistry();
        final ServiceBusHealthIndicator indicator =
                new ServiceBusHealthIndicator(STALENESS, new ProcessingMetrics(scraped), clock);

        indicator.recordIntakeStarted();
        indicator.recordProcessorError(SOURCE, ENTITY_PATH, connectionFailure());

        // Deliberately no health() call. Prometheus does not visit the health endpoint on its way
        // past, and a gauge that is only correct after somebody else has asked the same question is
        // a dashboard that disagrees with the probe for as long as nobody probes.
        assertThat(scraped.find(ProcessingMetrics.SERVICEBUS_UP).gauge().value())
                .as("the gauge and the component answer from the same live state")
                .isEqualTo(0);
    }

    /**
     * The file-service pool this context probes, wired the way the deployment wires it.
     *
     * <p>{@code FileServiceDataSourceConfig} is conditional on {@code generation.enabled}, and
     * enabling generation here would require the whole downstream half - two endpoints, an App
     * Configuration store, an e-mail template and a broker - none of which readiness is about. So
     * the one bean the file-service component needs is declared here instead, under the same name,
     * with the same {@code defaultCandidate = false} and the same lazy initialisation.
     *
     * <p><strong>{@code defaultCandidate = false} is load-bearing, not decoration.</strong> Spring
     * Boot's datasource and health auto-configurations collect datasources by type, so a second
     * default candidate would take the processed log's pool away and rename its {@code db}
     * component - the store this suite's first case asserts about, removed by the fixture that was
     * meant to add a third component beside it. {@code GenerationHealth} reaches this one by
     * qualifier, which is the only way it is reachable at all.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FileServicePool {

        /** Hikari's own name for the pool, so the two are told apart in a thread dump. */
        private static final String POOL_NAME = "yot-results-distribution-fileservice-readiness";

        /**
         * Lazy pool initialisation, as the deployed pool has it: the pod must start with the
         * database down, or it could not report the outage it started into.
         */
        private static final long STARTS_WITHOUT_A_DATABASE = -1L;

        /**
         * Short, for the reason the processed log's pool is short here: a refused connection is
         * immediate, but a pool asked during a health poll must not be able to outlast the poll.
         */
        private static final long CONNECT_TIMEOUT_MILLIS = 3000L;

        private static final long VALIDATION_TIMEOUT_MILLIS = 2000L;

        /** Seconds, and matching the processed log's here rather than the deployed thirty. */
        private static final String SOCKET_TIMEOUT_SECONDS = "5";

        /**
         * The write-only pool, pointed at the database this suite closes and reopens.
         *
         * @return the pool, closed with the context
         */
        @Bean(name = FileServiceDataSourceConfig.DATA_SOURCE, defaultCandidate = false,
                destroyMethod = "close")
        HikariDataSource fileServiceDataSource() {
            final HikariConfig config = new HikariConfig();
            config.setPoolName(POOL_NAME);
            config.setJdbcUrl(fileServiceUrl);
            config.setUsername(PostgresTestSupport.username());
            config.setPassword(PostgresTestSupport.password());
            config.setInitializationFailTimeout(STARTS_WITHOUT_A_DATABASE);
            config.setConnectionTimeout(CONNECT_TIMEOUT_MILLIS);
            config.setValidationTimeout(VALIDATION_TIMEOUT_MILLIS);
            config.addDataSourceProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS);
            return new HikariDataSource(config);
        }
    }
}
