package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * The whole HTTP surface of this service, asserted rather than assumed (constitution Principle III).
 *
 * <p>This service has no <strong>business</strong> API: its inbound contract is a queue message and
 * what it produces goes into its own store. The HTTP it serves is two things and nothing else - the
 * operational actuator set (health with its liveness and readiness groups, info, metrics and the
 * Prometheus scrape) and the seven named operator actions under {@code /operations/**} that replaced
 * the CLI in increment 005. There is deliberately no replay endpoint: replay is resubmitting a
 * parked message, not calling a URL.
 *
 * <p><strong>The claim is made over the mapped paths, not over the controller beans.</strong> Until
 * increment 005 it could be "no controller of ours exists", which a bean count says exactly; now
 * that seven paths are permitted, a bean count says only how many classes there are and nothing
 * about what they serve. So {@link #mappedPaths} reads what the handler mapping actually publishes:
 * every path is under {@code /actuator} or {@code /operations}, the {@code /operations} ones are
 * exactly the seven this service owns, and none of them submits a hearing, reads a register out or
 * creates a batch. That is the form that stops caring how many controllers there are - and it is
 * the test that stops the next increment from quietly adding a business endpoint, because
 * Principle III makes one a constitution amendment rather than a spec.
 *
 * <p>The actuator half is asserted from the link list rather than from the property that configures
 * it. A property assertion re-states the configuration file; the link list is the thing an operator,
 * a scanner and an attacker all see, and it is what changes when somebody adds an endpoint by adding
 * a dependency.
 *
 * <p><strong>Asserted on four shapes of the service.</strong> The enclosing class runs under the
 * {@code test} profile, where the controllers are deliberately not registered at all;
 * {@link OnAGeneratingPod} is the full deployment and is where the path sweep lives, because that
 * is the only shape that holds every one of the seven; {@link OnAPodThatRendersNothing} is a pod
 * without the generating half, and {@link WithTheOperationsApiSwitchedOff} is one with the surface
 * withdrawn.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("the service's whole HTTP surface")
class HttpSurfaceTest {

    /** Exactly the endpoints {@code application.yaml} exposes, and the only ones permitted. */
    private static final Set<String> PERMITTED_ENDPOINTS =
            Set.of("health", "info", "metrics", "prometheus");

    /**
     * Endpoints the template's dependencies would happily publish and this service must not: each
     * one leaks either configuration, secrets or a control surface. {@code env} and
     * {@code configprops} would publish the broker connection string and the {@code CJSCPPUID}
     * identities; {@code loggers} would let a caller turn on a level this repository's privacy rules
     * assume nobody can turn on.
     */
    private static final Set<String> FORBIDDEN_ENDPOINTS =
            Set.of("env", "beans", "configprops", "loggers", "threaddump", "heapdump",
                    "mappings", "shutdown", "conditions", "scheduledtasks", "caches");

    /**
     * The one controller a context of this service is permitted to hold, and it is Boot's own.
     *
     * <p>{@code BasicErrorController} is the framework's {@code /error} fallback: it is what renders
     * the 404 body the case above asserts for {@code /} and for every forbidden actuator path, and
     * it publishes no route of this service's. It is named rather than filtered out, so that the
     * assertion is "exactly this and nothing else" rather than "nothing that looks like ours" -
     * a controller arriving from a dependency is the surface change hardest to notice.
     */
    private static final String ERROR_FALLBACK = "basicErrorController";

    /** The bounded code a pod that holds no generating half answers its three paths under. */
    private static final String NOT_WIRED = "command-not-wired";

    /** A well-formed batch identity, so the notify path is exercised rather than the parser. */
    private static final String BATCH = "11111111-2222-4333-8444-555555555555";

    /**
     * The controllers a pod that serves the operations API holds, and the only ones it may.
     *
     * <p>Increment 005 replaced the six operations commands with seven endpoints under
     * {@code /operations/**}, so "no controller of ours exists" stopped being true the moment the
     * first one landed. What has not changed is the force of the assertion: it is still "exactly
     * these and nothing else", so a controller arriving from a dependency, or a business endpoint
     * arriving without the constitution amendment Principle III requires, still fails here.
     *
     * <p>It is kept beside {@link #THE_SEVEN} rather than replaced by it, because the two say
     * different things and both are worth saying: this one is which classes a shape of the pod
     * holds, and that one is what they serve. A controller withdrawn by a switch shows up here; a
     * path added to a controller that is already held shows up there.
     */
    private static final List<String> OPERATIONS_CONTROLLERS =
            List.of("flagController", "batchesController", "registersController",
                    "exceptionReportsController");

    /**
     * The seven paths this service is permitted to serve under {@code /operations}, with the method
     * each answers on, and nothing else.
     *
     * <p>A closed list rather than a rule about what is forbidden, for the reason every other sweep
     * in this repository is: a rule catches the shapes somebody thought of, and the endpoint nobody
     * thought of is the one that gets added. Adding a row here is a deliberate act, and
     * {@code .specify/memory/constitution.md} Principle III says what has to happen before one is.
     *
     * <p>Spelled with the path-variable placeholder the handler mapping publishes, not with a
     * sample id: this is a statement about the mapping and not about a request.
     */
    private static final Set<String> THE_SEVEN = Set.of(
            "GET /operations/flag",
            "GET /operations/batches",
            "GET /operations/registers/recorded-while-off",
            "POST /operations/batches/generate",
            "POST /operations/batches/{batchId}/notify",
            "POST /operations/registers/supersede",
            "POST /operations/exception-reports");

    /**
     * The roots every mapped path of this service must sit under.
     *
     * <p>{@code /error} is the framework's own and is the third: it is where the authorisation
     * filter's {@code sendError} forwards to, and what {@link OperationsErrorAttributes} renders the
     * body of. It publishes no route of this service's.
     */
    private static final String ERROR_PATH = "/error";

    /**
     * The same list on a pod with no generation half, where {@code batchesController} is not one.
     *
     * <p>Two of its three endpoints - the regeneration and the resend - need the flag gate, the
     * assembler, the requesting leg and the notifier, and none of those is contributed where
     * {@code yotresultsdistribution.generation.enabled} is false. The class is therefore conditional on it,
     * and such a pod answers all three batch paths through the not-wired fallback instead
     * (FR-052).
     */
    private static final List<String> CONTROLLERS_WITHOUT_GENERATION =
            List.of("flagController", "registersController", "exceptionReportsController",
                    "notWiredController");

    private final MockMvc mockMvc;

    private final ApplicationContext context;

    @Autowired
    HttpSurfaceTest(final MockMvc mockMvc, final ApplicationContext context) {
        this.mockMvc = mockMvc;
        this.context = context;
    }

    @Test
    @DisplayName("the actuator publishes exactly health, info, metrics and prometheus")
    void should_publish_exactly_the_permitted_operational_endpoints() throws Exception {
        assertThat(publishedEndpoints(mockMvc))
                .as("the whole HTTP surface of a service with no business API")
                .isEqualTo(PERMITTED_ENDPOINTS);
    }

    @Test
    @DisplayName("nothing else is reachable, including the endpoints a dependency could publish")
    void should_not_serve_any_endpoint_outside_the_permitted_set() throws Exception {
        for (final String forbidden : FORBIDDEN_ENDPOINTS) {
            mockMvc.perform(get("/actuator/" + forbidden))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("health publishes the liveness and readiness groups the platform probes")
    void should_publish_the_liveness_and_readiness_probe_paths() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the test profile registers none of the operations controllers")
    void should_hold_no_controller_but_the_error_fallback() {
        assertThat(controllerBeans(context))
                .as("every operations controller carries @Profile(\"!test\") because the store and "
                        + "its repositories do, so this profile holds none of them - which is a "
                        + "fact about this profile and not the surface claim. The surface claim is "
                        + "made over the mapped paths on a deployed shape, in OnAGeneratingPod")
                .containsExactly(ERROR_FALLBACK);
    }

    /**
     * The same surface on a pod with the downstream half switched on (FR-016).
     *
     * <p>Phase 7 adds regeneration, resending, listing, supersession and a flag read, and every one
     * of them is an operator asking this service to do something - which is exactly the shape a
     * REST resource is usually reached for. They are commands in the image instead, dispatched by
     * {@code docker/startup.sh}, and this is the case that says so: the answer to "is there an
     * endpoint for it" has to be asserted on the context that has the machinery, because a
     * controller wired behind {@code yotresultsdistribution.generation.enabled} would be invisible to the
     * enclosing class's {@code test}-profile context.
     *
     * <p>A generating pod's configuration, and the properties are
     * {@code GenerationWiringContextTest}'s: the four modes LIVE so the real adapters resolve, the
     * embedded broker for the {@code public.event} subscription, and intake off, which is a
     * different half and needs a broker this suite has no business standing up. The context is
     * fresh rather than inherited - {@link NestedTestConfiguration} OVERRIDE - because the
     * enclosing class's {@code test} profile is the absence of everything this case is about.
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(properties = {
        "yotresultsdistribution.generation.enabled=true",
        "yotresultsdistribution.generation.sdg-mode=LIVE",
        "yotresultsdistribution.generation.nn-mode=LIVE",
        "yotresultsdistribution.generation.fileservice-mode=LIVE",
        "yotresultsdistribution.generation.flag-mode=LIVE",
        "yotresultsdistribution.fileservice.url=jdbc:postgresql://fileservice.internal:5432/fileservice",
        "yotresultsdistribution.feature.connection-string=Endpoint=https://appconfig.internal;Id=ste-id;Secret=c3RlLXNlY3JldA==",
        "yotresultsdistribution.feature.label=ste86",
        "yotresultsdistribution.endpoints.systemdocgenerator=http://systemdocgenerator.internal:8080",
        "yotresultsdistribution.endpoints.notificationnotify=http://notificationnotify.internal:8080",
        "yotresultsdistribution.endpoints.system-user-id=00000000-0000-4000-8000-000000000000",
        "yotresultsdistribution.email.templates.cr_standard=5c9a0e21-3d47-4f18-9b62-0a71c4e8d530",
        "yotresultsdistribution.payload.mode=STUB",
        "yotresultsdistribution.referencedata.mode=STUB",
        "yotresultsdistribution.consumer.enabled=false",
        "spring.artemis.broker-url=tcp://localhost:61616",
        "spring.artemis.embedded.enabled=true",
        "spring.artemis.embedded.queues=public.event"})
    @AutoConfigureMockMvc
    @DisplayName("with the downstream half switched on")
    class OnAGeneratingPod {

        private final MockMvc mockMvc;

        private final ApplicationContext context;

        @Autowired
        OnAGeneratingPod(final MockMvc mockMvc, final ApplicationContext context) {
            this.mockMvc = mockMvc;
            this.context = context;
        }

        @Test
        @DisplayName("serves the operations controllers, and nothing else of ours")
        void a_generating_pod_should_hold_the_operations_controllers_and_nothing_else() {
            assertThat(controllerBeans(context))
                    .as("the operations API is the named operator actions and nothing else "
                            + "(constitution Principle III): a controller here that is not one of "
                            + "them is a business endpoint arriving without the amendment that "
                            + "would have to precede it, and this is where it is noticed")
                    .containsExactlyInAnyOrderElementsOf(Stream.concat(
                            OPERATIONS_CONTROLLERS.stream(), Stream.of(ERROR_FALLBACK)).toList());
        }

        @Test
        @DisplayName("maps nothing outside the actuator and /operations")
        void every_mapped_path_should_be_under_the_actuator_or_the_operations_root() {
            assertThat(mappedPaths(context).stream()
                            .map(mapped -> mapped.split(" ", 2)[1])
                            .filter(path -> !path.startsWith("/actuator"))
                            .filter(path -> !path.startsWith("/operations"))
                            .filter(path -> !ERROR_PATH.equals(path))
                            .toList())
                    .as("the whole HTTP surface is the actuator and the named operator actions; a "
                            + "path outside both is a business endpoint arriving without the "
                            + "constitution amendment that would have to precede it")
                    .isEmpty();
        }

        @Test
        @DisplayName("serves exactly the seven operator actions, and no hearing, register or batch")
        void the_operations_paths_should_be_exactly_the_seven() {
            assertThat(mappedPaths(context).stream()
                            .filter(mapped -> mapped.contains(" /operations"))
                            .toList())
                    .as("no hearing is submitted over HTTP, no register is read out and no batch "
                            + "is created by a caller: the three shapes this service is not "
                            + "allowed to grow into are excluded by the list being closed, not by "
                            + "a rule about what they would look like")
                    .containsExactlyInAnyOrderElementsOf(THE_SEVEN);
        }

        @Test
        @DisplayName("publishes the same four operational endpoints and nothing more")
        void a_generating_pod_should_publish_the_same_operational_endpoints() throws Exception {
            assertThat(publishedEndpoints(mockMvc))
                    .as("the downstream half adds health components, metrics and a job - it must "
                            + "add no endpoint, and a new one appearing here is a surface change "
                            + "nobody asked for")
                    .isEqualTo(PERMITTED_ENDPOINTS);
        }
    }

    /**
     * The same pod with no generation half at all (T040/T041, 2026-09-21).
     *
     * <p>The decision this case exists for: {@code GET /operations/flag} is served on
     * <strong>every</strong> pod, including one with {@code yotresultsdistribution.generation.enabled=false}.
     * "Which implementation is live" is not a property of the replica an operator happened to
     * reach, so answering {@code 501 command-not-wired} on a non-rendering pod would make the
     * lever's state look like one. The bean that had to move for it is {@code FeatureFlagReader}'s:
     * it used to be contributed only behind the generation switch, and it is now contributed
     * wherever the service runs, keeping its {@code !test} profile gating and its LIVE/STUB mode
     * selection exactly as they were.
     *
     * <p><strong>And no App Configuration endpoint is configured here on purpose.</strong> That is
     * the shape a pod with no nightly job is deployed in - {@code PropertiesValidator} asks for the
     * endpoint and the label only once generation is on - so the credential has no store to be
     * built against and the three workload-identity variables a deployed pod is given are absent.
     * The pod must start anyway, and the reading must be {@code UNREADABLE} with its own cause on
     * it rather than a refusal.
     *
     * <p>The two estate filters are off: what they do has suites of its own, and this case is about
     * which beans a pod holds and what the endpoint answers.
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(properties = {
        "yotresultsdistribution.generation.enabled=false",
        "yotresultsdistribution.payload.mode=STUB",
        "yotresultsdistribution.referencedata.mode=STUB",
        "yotresultsdistribution.consumer.enabled=false",
        "authz.http.enabled=false",
        "audit.http.enabled=false",
        "cp.audit.enabled=false"})
    @AutoConfigureMockMvc
    @DisplayName("with no generation half")
    class OnAPodThatRendersNothing {

        /** The flag reader's bean, by the name its factory method gives it. */
        private static final String FLAG_READER = "featureFlagReader";

        private final MockMvc mockMvc;

        private final ApplicationContext context;

        @Autowired
        OnAPodThatRendersNothing(final MockMvc mockMvc, final ApplicationContext context) {
            this.mockMvc = mockMvc;
            this.context = context;
        }

        @Test
        @DisplayName("the pod starts and still holds the flag reader")
        void the_generation_switch_should_not_take_the_flag_reader_with_it() {
            assertThat(context.containsBean(FLAG_READER))
                    .as("the flag endpoint is served on every pod, so the reader behind it exists "
                            + "on every pod - and a pod with no store configured must start "
                            + "without the three variables only a deployed pod is given")
                    .isTrue();
            assertThat(controllerBeans(context))
                    .as("the batch controller goes with the generating half it needs, and the "
                            + "not-wired fallback takes its three paths; the other three "
                            + "controllers are served on every pod")
                    .containsExactlyInAnyOrderElementsOf(Stream.concat(
                            CONTROLLERS_WITHOUT_GENERATION.stream(),
                            Stream.of(ERROR_FALLBACK)).toList());
        }

        @Test
        @DisplayName("the flag endpoint answers, and says it cannot see the flag")
        void the_flag_endpoint_should_answer_on_a_pod_that_renders_nothing() throws Exception {
            mockMvc.perform(get("/operations/flag"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.flag").value("UNREADABLE"))
                    .andExpect(jsonPath("$.reason").value("unreadable-not-configured"));
        }

        @Test
        @DisplayName("the three batch paths answer 501 command-not-wired")
        void the_three_batch_paths_should_answer_that_this_pod_does_not_hold_them()
                throws Exception {

            mockMvc.perform(get("/operations/batches").param("date", "2026-09-04"))
                    .andExpect(status().isNotImplemented())
                    .andExpect(jsonPath("$.reason").value(NOT_WIRED));
            mockMvc.perform(post("/operations/batches/generate"))
                    .andExpect(status().isNotImplemented())
                    .andExpect(jsonPath("$.reason").value(NOT_WIRED));
            mockMvc.perform(post("/operations/batches/" + BATCH + "/notify"))
                    .andExpect(status().isNotImplemented())
                    .andExpect(jsonPath("$.reason").value(NOT_WIRED));
        }

        @Test
        @DisplayName("and an endpoint that needs no generating bean is served normally")
        void an_endpoint_needing_no_generating_bean_should_not_be_answered_as_missing()
                throws Exception {

            // Its own refusal rather than the fallback's: the rollback is reached, reads its one
            // argument and declines it, which is what "served on this pod" looks like from
            // outside. A path this pod does not hold would never get as far as an argument.
            mockMvc.perform(post("/operations/registers/supersede")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sharedBefore\":\"ZQX7NOTANINSTANT\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("unreadable-argument"));
        }
    }

    /**
     * The same pod with the operations API switched off (FR-044).
     *
     * <p>{@code yotresultsdistribution.operations.enabled} is deployment shape rather than a cutover lever,
     * so it has one obligation above every other: <strong>turning it off must cost the pod
     * nothing</strong>. The controllers, the action filter and the listings they call go away
     * together; a controller left behind over a listing nothing contributes would be an
     * {@code UnsatisfiedDependencyException} at refresh, and a switch that crashes the pod is not a
     * switch.
     *
     * <p>Asserted over the real component scan and on a real profile, because that is the only
     * shape the crash has: a slice test that never scans the controllers, and the {@code test}
     * profile on which they are not registered at all, are both blind to it.
     *
     * <p>The error body is asserted too. The bounded error attributes are deliberately
     * <em>not</em> conditional: a pod that serves none of the seven paths still answers whatever an
     * operator tried, and Boot's own body for a 404 echoes the path they typed (FR-025, FR-027).
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(properties = {
        "yotresultsdistribution.operations.enabled=false",
        "yotresultsdistribution.generation.enabled=true",
        "yotresultsdistribution.generation.completion=event",
        "yotresultsdistribution.generation.sdg-mode=LIVE",
        "yotresultsdistribution.generation.nn-mode=LIVE",
        "yotresultsdistribution.generation.fileservice-mode=LIVE",
        "yotresultsdistribution.generation.flag-mode=LIVE",
        "yotresultsdistribution.fileservice.url=jdbc:postgresql://fileservice.internal:5432/fileservice",
        "yotresultsdistribution.feature.connection-string=Endpoint=https://appconfig.internal;Id=ste-id;Secret=c3RlLXNlY3JldA==",
        "yotresultsdistribution.feature.label=ste86",
        "yotresultsdistribution.endpoints.systemdocgenerator=http://systemdocgenerator.internal:8080",
        "yotresultsdistribution.endpoints.notificationnotify=http://notificationnotify.internal:8080",
        "yotresultsdistribution.endpoints.system-user-id=00000000-0000-4000-8000-000000000000",
        "yotresultsdistribution.email.templates.cr_standard=5c9a0e21-3d47-4f18-9b62-0a71c4e8d530",
        "yotresultsdistribution.payload.mode=STUB",
        "yotresultsdistribution.referencedata.mode=STUB",
        "yotresultsdistribution.consumer.enabled=false",
        "spring.artemis.broker-url=tcp://localhost:61616",
        "spring.artemis.embedded.enabled=true",
        "spring.artemis.embedded.queues=public.event"})
    @AutoConfigureMockMvc
    @DisplayName("with the operations API switched off")
    class WithTheOperationsApiSwitchedOff {

        /** The bean that registers the action filter, by the name its factory method gives it. */
        private static final String ACTION_FILTER = "operationsActionFilter";

        /** The bean that renders every body this service does not write itself. */
        private static final String ERROR_ATTRIBUTES = "operationsErrorAttributes";

        private final MockMvc mockMvc;

        private final ApplicationContext context;

        @Autowired
        WithTheOperationsApiSwitchedOff(final MockMvc mockMvc, final ApplicationContext context) {
            this.mockMvc = mockMvc;
            this.context = context;
        }

        @Test
        @DisplayName("the pod starts and holds no operations controller")
        void the_switch_should_take_the_controllers_away_without_taking_the_pod_down() {
            assertThat(controllerBeans(context))
                    .as("the context refreshed at all, which is the first half of the assertion, "
                            + "and what is left of our HTTP surface is nothing")
                    .containsExactly(ERROR_FALLBACK);
        }

        @Test
        @DisplayName("the action filter goes with them and the bounded error body stays")
        void the_switch_should_withdraw_the_filter_and_keep_the_error_body() {
            assertThat(context.containsBean(ACTION_FILTER))
                    .as("a pod that answers none of the seven paths has no action to name")
                    .isFalse();
            assertThat(context.containsBean(ERROR_ATTRIBUTES))
                    .as("a 404 is still answered, and Boot's own body for one echoes the path the "
                            + "caller typed")
                    .isTrue();
        }

        @Test
        @DisplayName("an operations path is refused in bounded fields, echoing nothing")
        void an_operations_path_should_be_refused_without_echoing_what_was_asked_for()
                throws Exception {
            final String body = mockMvc.perform(get("/operations/flag"))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .as("nothing of ours is mapped there any more and no action is derived for it, "
                            + "so the authorisation filter refuses an unidentified caller before "
                            + "the container gets as far as saying nothing is served - and "
                            + "whatever comes back, none of it is what the caller asked for. What "
                            + "the bounded body itself carries is OperationsErrorAttributesTest's")
                    .doesNotContain("/operations");
        }
    }

    /**
     * Every bean that serves HTTP, by the two annotations that make one.
     *
     * <p>{@code @RestController} carries {@code @Controller}, so the first name would find both;
     * both are asked for anyway, because this is the assertion that notices an endpoint arriving and
     * it should not depend on a meta-annotation staying where it is.
     *
     * <p>Bean names rather than types, so the one permitted answer can be stated without naming a
     * framework package that a Boot upgrade may move.
     */
    private static List<String> controllerBeans(final ApplicationContext context) {
        return Stream.concat(
                        Arrays.stream(context.getBeanNamesForAnnotation(Controller.class)),
                        Arrays.stream(context.getBeanNamesForAnnotation(RestController.class)))
                .distinct()
                .toList();
    }

    /**
     * Every path this context's handler mapping publishes, as {@code "METHOD /path"}.
     *
     * <p>Read off {@link RequestMappingHandlerMapping} rather than off the controllers' annotations,
     * because what is under assertion is what the application <em>serves</em>: a path contributed by
     * a dependency's auto-configuration carries no annotation of ours and is exactly the arrival
     * this suite exists to notice.
     *
     * <p>A mapping that names several methods, or none, yields one entry per method and one
     * {@code "* /path"} respectively - a mapping with no method condition answers every verb, which
     * is a surface fact and not a formatting one.
     *
     * <p><strong>Every such mapping on the context, not the one named
     * {@code requestMappingHandlerMapping}.</strong> The actuator contributes a second of this type
     * for its own controller endpoints, and a sweep that asked for one bean would either fail to
     * resolve or - worse, once somebody "fixed" it by name - read the application's paths and miss
     * whatever a dependency published beside them.
     *
     * @param context the context to read
     * @return the mapped paths, one entry per method
     */
    private static List<String> mappedPaths(final ApplicationContext context) {
        return context.getBeansOfType(RequestMappingHandlerMapping.class).values().stream()
                .flatMap(mapping -> mapping.getHandlerMethods().keySet().stream())
                .flatMap(HttpSurfaceTest::spell)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * One mapping, spelled once per method and path it answers on.
     *
     * @param info the mapping
     * @return {@code "METHOD /path"} for every combination it covers
     */
    private static Stream<String> spell(final RequestMappingInfo info) {
        final Set<String> patterns = info.getPathPatternsCondition() == null
                ? Set.of()
                : info.getPathPatternsCondition().getPatternValues();
        final Set<String> methods = info.getMethodsCondition().getMethods().stream()
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
        final Set<String> verbs = methods.isEmpty() ? Set.of("*") : methods;
        return patterns.stream().flatMap(path -> verbs.stream().map(verb -> verb + " " + path));
    }

    /**
     * The endpoint ids the actuator index advertises.
     *
     * <p>Templated variants — {@code health-path}, {@code metrics-requiredMetricName} — are the same
     * endpoint reached with a path variable, so they are folded back onto the endpoint they belong
     * to; {@code self} is the index itself.
     */
    private static Set<String> publishedEndpoints(final MockMvc mockMvc) throws Exception {
        final String body = mockMvc.perform(get("/actuator"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final JsonNode links = JacksonConfig.contractObjectMapper().readTree(body).get("_links");
        return links.propertyNames().stream()
                .filter(name -> !"self".equals(name))
                .map(name -> name.split("-", 2)[0])
                .collect(Collectors.toUnmodifiableSet());
    }
}
