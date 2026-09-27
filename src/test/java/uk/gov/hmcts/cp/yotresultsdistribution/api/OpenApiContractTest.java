package uk.gov.hmcts.cp.yotresultsdistribution.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;
import uk.gov.hmcts.cp.yotresultsdistribution.application.BatchListingService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.FeatureFlagReader;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OnDemandExceptionReportService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsRunLauncher;
import uk.gov.hmcts.cp.yotresultsdistribution.application.OperationsSupersessionService;
import uk.gov.hmcts.cp.yotresultsdistribution.application.RegisterNotifierService;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.OperationsReason;

/**
 * The owned contract, asserted against the controllers in <strong>both</strong> directions.
 *
 * <p>{@code src/main/resources/yot-results-distribution-openapi.yaml} is the third contract this repository
 * owns (constitution Principle III), and it has two readers rather than one. A reviewer reads it to
 * see what the surface is. {@code cp-audit-filter-springboot} reads it at <em>runtime</em> to
 * resolve path parameters - and a path it does not find is not an error there: the event is
 * published with its path parameters silently missing (research R7). So a path mapped and not
 * described is an endpoint whose audit event is wrong and nothing says so, and a path described and
 * not mapped is a surface a reviewer believes in that is not there. Both are failures here.
 *
 * <p><strong>And the file's name is load-bearing.</strong> The filter globs
 * {@code classpath*:} + {@code **}{@code /*} + the value of {@code audit.http.openapi-rest-spec} - a
 * <em>suffix</em> match over every jar on the classpath, not a path - and hands the parser whichever
 * resource the glob returns first. A value of {@code openapi.yaml} would match this document and any
 * other {@code *openapi.yaml} a dependency ships. {@link TheGlobTheAuditFilterResolvesItBy} runs the
 * real glob against the real test classpath and asserts <strong>exactly one</strong> match; a count
 * is the only assertion that catches a dependency adding a second document later, where asserting
 * that the parser found <em>a</em> document would pass on the wrong one.
 * {@code config/PropertiesValidator} already refuses an unset value where both audit switches are
 * on; it cannot refuse an ambiguous one, which is why this is a test.
 *
 * <p>A slice over the four real controllers rather than a whole context, for one reason: every
 * controller on this surface carries {@code @Profile("!test")}, so the profile a context-load test
 * runs under is precisely the one on which none of them is registered. The slice takes the real
 * classes, the real annotations and the framework's own
 * {@code RequestMappingHandlerMapping} - which is what publishes the surface - with the application
 * services mocked, because what is being asserted is the shape of the surface and not what any
 * endpoint answers.
 */
@WebMvcTest(controllers = {
    FlagController.class,
    BatchesController.class,
    RegistersController.class,
    ExceptionReportsController.class},
    properties = {
        "authz.http.enabled=false",
        "audit.http.enabled=false",
        "cp.audit.enabled=false",
        // Three of the seven endpoints are contributed only where the generating half is, and a
        // slice that said nothing would assert four sevenths of the contract.
        "yotresultsdistribution.generation.enabled=true"})
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the operations API's own contract")
class OpenApiContractTest {

    /**
     * The document, by the name the audit filter's glob has to be able to find and nothing else.
     *
     * <p>Named here as a literal rather than read from the setting, so that a setting changed
     * without the file being renamed fails this suite instead of failing a refresh in a deployed
     * environment with a message naming neither the key nor this service.
     */
    private static final String DOCUMENT = "yot-results-distribution-openapi.yaml";

    /** The prefix every path this contract covers sits under, and the only one it may cover. */
    private static final String OPERATIONS = "/operations";

    /** The one path parameter in the whole document, which is what the filter reads it for. */
    private static final String THE_PATH_PARAMETER = "batchId";

    /** The notify path, the only one of the seven that carries a path parameter. */
    private static final String NOTIFY = "/operations/batches/{batchId}/notify";

    /** Everything served that is not this surface: actuator's own endpoints and Boot's /error. */
    private static final List<String> NOT_THIS_SURFACE = List.of("/actuator", "/error");

    /** The document, parsed once: it is a file, and seven cases asking the disk seven times is six
     * reads for an answer that cannot have changed. */
    private static Map<String, Object> document;

    @InjectSoftAssertions
    private SoftAssertions softly;

    /** The application services the four controllers hold; none of them is called by this suite. */
    @MockitoBean
    private FeatureFlagReader flag;

    @MockitoBean
    private BatchListingService listings;

    @MockitoBean
    private OperationsRunLauncher launcher;

    @MockitoBean
    private RegisterNotifierService notifier;

    @MockitoBean
    private OperationsSupersessionService supersession;

    @MockitoBean
    private OnDemandExceptionReportService reports;

    private final RequestMappingHandlerMapping mappings;

    @Autowired
    OpenApiContractTest(
            @Qualifier("requestMappingHandlerMapping") final RequestMappingHandlerMapping
                    requestMappings) {

        // By NAME, because actuator contributes a second RequestMappingHandlerMapping of its own
        // (`controllerEndpointHandlerMapping`) and the whole point of this suite is to assert what
        // the application's own controllers publish rather than what actuator does.
        this.mappings = requestMappings;
    }

    @BeforeAll
    static void readTheDocument() throws IOException {
        try (InputStream source =
                     Thread.currentThread().getContextClassLoader()
                             .getResourceAsStream(DOCUMENT)) {
            document = new Yaml().load(source);
        }
    }

    /**
     * Every path and method this service maps, as {@code method path} pairs under /operations.
     *
     * @return what the application actually serves
     */
    private Set<String> mapped() {
        final Set<String> served = new TreeSet<>();
        for (final RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            final Set<String> patterns = info.getPathPatternsCondition() == null
                    ? Set.of()
                    : info.getPathPatternsCondition().getPatternValues();
            for (final String pattern : patterns) {
                if (isThisSurface(pattern)) {
                    info.getMethodsCondition().getMethods()
                            .forEach(method -> served.add(method.name().toLowerCase(
                                    java.util.Locale.ROOT) + " " + pattern));
                }
            }
        }
        return served;
    }

    /**
     * Whether one mapped pattern belongs to the surface this document describes.
     *
     * @param pattern the mapped pattern
     * @return true where it is an operations path
     */
    private static boolean isThisSurface(final String pattern) {
        return pattern.startsWith(OPERATIONS)
                && NOT_THIS_SURFACE.stream().noneMatch(pattern::startsWith);
    }

    /**
     * Every path and method the document describes, in the same {@code method path} spelling.
     *
     * @return what the contract claims
     */
    @SuppressWarnings("unchecked")
    private static Set<String> described() {
        final Set<String> claimed = new TreeSet<>();
        paths().forEach((path, operations) ->
                ((Map<String, Object>) operations).keySet()
                        .forEach(method -> claimed.add(method + " " + path)));
        return claimed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paths() {
        return (Map<String, Object>) document.get("paths");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> components() {
        return (Map<String, Object>) document.get("components");
    }

    /**
     * The bounded codes the document names, as one set.
     *
     * @return the {@code Reason} enumeration
     */
    @SuppressWarnings("unchecked")
    private static Set<String> documentedReasons() {
        final Map<String, Object> schemas =
                (Map<String, Object>) components().get("schemas");
        final Map<String, Object> reason = (Map<String, Object>) schemas.get("Reason");
        return new LinkedHashSet<>((List<String>) reason.get("enum"));
    }

    /**
     * The argument names the document says a {@code 400} may be about, as one set.
     *
     * @return the {@code argument} property's enumeration
     */
    @SuppressWarnings("unchecked")
    private static Set<String> documentedArguments() {
        final Map<String, Object> schemas =
                (Map<String, Object>) components().get("schemas");
        final Map<String, Object> problem = (Map<String, Object>) schemas.get("ProblemDetail");
        final Map<String, Object> properties =
                (Map<String, Object>) problem.get("properties");
        final Map<String, Object> argument = (Map<String, Object>) properties.get("argument");
        return new LinkedHashSet<>((List<String>) argument.get("enum"));
    }

    /**
     * The fields the document says a refusal may carry, as one set.
     *
     * @return the {@code ProblemDetail} schema's declared property names
     */
    @SuppressWarnings("unchecked")
    private static Set<String> documentedProblemFields() {
        final Map<String, Object> schemas =
                (Map<String, Object>) components().get("schemas");
        final Map<String, Object> problem = (Map<String, Object>) schemas.get("ProblemDetail");
        return new LinkedHashSet<>(((Map<String, Object>) problem.get("properties")).keySet());
    }

    /**
     * That the contract and the controllers describe the same seven endpoints.
     */
    @Nested
    @DisplayName("what it describes, against what is served")
    class BothDirections {

        @Test
        void every_path_this_service_maps_should_be_described() {
            softly.assertThat(described())
                    .as("a path mapped and not described is a path the audit filter resolves no "
                            + "parameters for - the event is published with them silently missing, "
                            + "and nothing fails to tell us (research R7)")
                    .containsAll(mapped());
        }

        @Test
        void every_path_the_document_describes_should_be_mapped() {
            softly.assertThat(mapped())
                    .as("a path described and not mapped is a surface a reviewer believes in and "
                            + "an operator cannot reach")
                    .containsAll(described());
        }

        @Test
        void the_surface_should_be_exactly_seven_endpoints() {
            softly.assertThat(described())
                    .as("one endpoint per operator action, and no other HTTP path besides actuator "
                            + "(FR-001). An eighth needs a constitution amendment, not a commit")
                    .hasSize(7);
        }
    }

    /**
     * The one path parameter, which is the reason the filter reads this document at all.
     */
    @Nested
    @DisplayName("the path parameter the audit filter resolves")
    class ThePathParameter {

        @Test
        @SuppressWarnings("unchecked")
        void the_notify_path_should_declare_its_batch_id_as_a_path_parameter() {
            final Map<String, Object> notify =
                    (Map<String, Object>) ((Map<String, Object>) paths().get(NOTIFY)).get("post");
            final List<Map<String, Object>> parameters =
                    (List<Map<String, Object>>) notify.get("parameters");

            softly.assertThat(parameters)
                    .as("the filter registers a path ONLY where it declares a path parameter; "
                            + "without this the notify call's audit event names no batch")
                    .anySatisfy(parameter -> {
                        softly.assertThat(parameter).containsEntry("name", THE_PATH_PARAMETER);
                        softly.assertThat(parameter).containsEntry("in", "path");
                        softly.assertThat(parameter).containsEntry("required", true);
                    });
        }

        @Test
        void it_should_be_the_only_path_parameter_on_the_whole_surface() {
            final List<String> withParameters = paths().keySet().stream()
                    .filter(path -> path.contains("{"))
                    .collect(Collectors.toCollection(ArrayList::new));

            softly.assertThat(withParameters)
                    .as("data-model section 5: one templated path in the whole document, so what "
                            + "the filter has to resolve is one identifier and not a family of them")
                    .containsExactly(NOTIFY);
        }
    }

    /**
     * The closed set of bounded codes, kept closed on both sides.
     */
    @Nested
    @DisplayName("the bounded reasons")
    class TheReasons {

        @Test
        void every_code_a_handler_can_emit_should_be_in_the_document() {
            final Set<String> emitted = Arrays.stream(OperationsReason.values())
                    .map(OperationsReason::wire)
                    .collect(Collectors.toCollection(TreeSet::new));

            softly.assertThat(documentedReasons())
                    .as("a runbook greps these, and a code a caller can be answered with that the "
                            + "contract does not name is a code nobody can have written a step for")
                    .containsAll(emitted);
        }

        @Test
        void every_field_a_refusal_can_carry_should_be_declared_on_the_problem_schema() {
            softly.assertThat(documentedProblemFields())
                    .as("a property a caller is answered with that the contract does not declare "
                            + "is a property no generated client can read and no runbook step can "
                            + "have been written for. The set is closed: it is every key "
                            + "OperationsProblem writes itself (argument, and the reason and "
                            + "status every refusal carries), every key a controller composes onto "
                            + "an OperationsRefusedException (batchId, accepted, failed, state, "
                            + "delivered) and every key the regeneration's partial tally carries "
                            + "(date, released, registers, batches, requested, deferred), plus "
                            + "runId. Adding one to a refusal means adding it here and to "
                            + "data-model.md in the same commit")
                    .containsExactlyInAnyOrder("type", "title", "status", "instance", "reason",
                            "argument", "batchId", "runId", "date", "released", "registers",
                            "batches", "requested", "deferred", "accepted", "failed", "state",
                            "delivered");
        }

        @Test
        void the_argument_enum_should_name_every_argument_a_refusal_can_be_about() {
            softly.assertThat(documentedArguments())
                    .as("a 400 names the argument and never the value (FR-025), so the closed set "
                            + "of names is part of the contract - including the override's, which "
                            + "OperationsRunLauncher refuses OVERRIDE_REQUIRES_BATCH by")
                    .contains(OperationsRunLauncher.IGNORE_FLAG)
                    .containsExactlyInAnyOrder("date", "batchId", "recordedBefore", "sharedBefore",
                            "since", OperationsRunLauncher.IGNORE_FLAG);
        }

        @Test
        void the_document_should_name_no_code_this_service_cannot_answer() {
            final Set<String> emitted = Arrays.stream(OperationsReason.values())
                    .map(OperationsReason::wire)
                    .collect(Collectors.toCollection(TreeSet::new));

            softly.assertThat(emitted)
                    .as("the other direction, and the one that catches a code left behind by a "
                            + "refusal that was removed")
                    .containsAll(documentedReasons());
        }
    }

    /**
     * The glob the audit filter finds this file by, run for real.
     */
    @Nested
    @DisplayName("the glob the audit filter resolves it by")
    class TheGlobTheAuditFilterResolvesItBy {

        @Test
        void exactly_one_resource_on_the_real_classpath_should_match_it() throws IOException {
            final Resource[] matched = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:**/*" + DOCUMENT);

            softly.assertThat(matched)
                    .as("a suffix glob over every jar on the classpath, first match wins: two "
                            + "documents and the parser is handed whichever comes back first, "
                            + "which is why the value names this service and not `openapi.yaml`")
                    .hasSize(1);
            softly.assertThat(matched[0].getFilename()).isEqualTo(DOCUMENT);
        }
    }
}
