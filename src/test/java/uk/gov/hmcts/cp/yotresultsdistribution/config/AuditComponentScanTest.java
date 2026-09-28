package uk.gov.hmcts.cp.yotresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import uk.gov.hmcts.cp.Application;

/**
 * The audit starter's Spring stereotypes stay out of this application's component scan.
 *
 * <p>{@code cp-audit-filter-springboot}'s package root is {@code uk.gov.hmcts.cp.filter.audit} and
 * this service's {@code @SpringBootApplication} sits at {@code uk.gov.hmcts.cp}, so the library's
 * own classes are inside the scanned tree. Five of them still carry a stereotype despite the
 * library's README saying there are none, and one of those - {@code OpenApiSpecificationParser}, a
 * {@code @Component} whose only constructor takes a {@code String} nothing can supply - fails the
 * refresh outright. It fails it <strong>even with auditing switched off</strong>, because a
 * component scan answers to neither {@code cp.audit.enabled} nor {@code audit.http.enabled}: those
 * two gate the {@code @AutoConfiguration} class, and a scanned stereotype never reaches it.
 *
 * <p>So the exclusion is not a tidiness measure, and what it prevents is not a stray bean: it is
 * the pod that does not start at all, reporting a bean-definition error naming a class nobody in
 * this repository has heard of. The regex filter on {@link uk.gov.hmcts.cp.Application} is what
 * closes it, and {@code @AutoConfiguration} classes are not subject to {@code @ComponentScan}
 * filters, so the starter's auto-configuration still runs in full where it is switched on.
 *
 * <p><strong>Why an {@link ApplicationContextRunner} and not {@code @SpringBootTest}.</strong> The
 * thing under test is a refresh that fails, and under {@code @SpringBootTest} a failed refresh is
 * an error raised by the framework before any test method runs - so the red run that proves the
 * exclusion is load-bearing would be an initialisation error rather than a failing assertion, which
 * is not the red this repository's TDD rule asks for (constitution Principle II). The runner starts
 * the same {@link Application} class, with the same {@code test} profile and the same switches, and
 * hands the outcome over as something to assert on: {@code hasNotFailed()} is a failing assertion
 * when the parser is scanned, and names the bean that did it.
 *
 * <p><strong>Why the assertion is by class name rather than by type.</strong> This suite was
 * written before the dependency was added, so that the failure was observed rather than predicted:
 * it passed with nothing on the classpath to find, failed the moment the dependency arrived without
 * the filter, and passes again now the filter is there. A {@code .class} literal would not compile
 * in the first of those three states, which is the one that makes the other two mean anything.
 *
 * <p>The switches are off here because that is the configuration the trap hides in. A context with
 * auditing on would build the library's beans legitimately, and an assertion that found none would
 * only be saying the auto-configuration had not run.
 */
@DisplayName("the audit starter's component-scan clash")
class AuditComponentScanTest {

    /** The audit starter's package root, which is inside this application's scanned tree. */
    private static final String AUDIT_PACKAGE = "uk.gov.hmcts.cp.filter.audit.";

    /**
     * The one stereotype that fails the refresh: a {@code @Component} whose only constructor takes
     * a {@code String} the container cannot supply.
     */
    private static final String THE_UNSATISFIABLE_ONE =
            AUDIT_PACKAGE + "parser.OpenApiSpecificationParser";

    /**
     * The real application class, on the real {@code test} profile, with every audit switch off.
     *
     * <p>{@link ConfigDataApplicationContextInitializer} is what makes {@code application.yaml} and
     * {@code application-test.yaml} bind, so this is the configuration a JVM would really start on
     * rather than a set of property values a test invented.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean("testTypeExcludeFilter", TypeExcludeFilter.class, ExcludesOtherSuites::new)
            .withPropertyValues("spring.profiles.active=test",
                    "yotresultsdistribution.operations.enabled=false",
                    "cp.audit.enabled=false",
                    "audit.http.enabled=false",
                    "authz.http.enabled=false")
            .withUserConfiguration(Application.class);

    /**
     * The delegate {@link TypeExcludeFilter} that {@code @SpringBootTest} would have registered.
     *
     * <p>{@link Application}'s {@code @ComponentScan} restates Boot's own
     * {@code TypeExcludeFilter}, and that filter is a <em>delegating</em> one: it excludes whatever
     * the {@code TypeExcludeFilter} beans on the context say to exclude, and there are none unless
     * something puts one there. Under {@code @SpringBootTest} the test bootstrapper does. Under a
     * runner nothing does, so a scan of {@code uk.gov.hmcts.cp} finds every other suite's nested
     * {@code @Configuration} and the context fails on a duplicate {@code objectMapper} - the same
     * collision {@code Application}'s javadoc describes, arriving from the other direction.
     *
     * <p>This is that missing bean and nothing more. It excludes exactly what a deployed JVM does
     * not have on its classpath in the first place - everything compiled from {@code src/test} -
     * by the directory the class file was read from rather than by a naming convention, so a
     * support class that is not named after a suite is excluded like the suites are. It says
     * nothing about the audit package, so the exclusion this suite is about is still the only
     * thing keeping the starter's stereotypes out.
     */
    private static final class ExcludesOtherSuites extends TypeExcludeFilter {

        /** Gradle's output directory for {@code src/test/java}, as it appears in a resource URL. */
        private static final String TEST_CLASSES = "/classes/java/test/";

        @Override
        public boolean match(final MetadataReader metadataReader,
                             final MetadataReaderFactory metadataReaderFactory) throws IOException {
            return metadataReader.getResource().getURL().toString().contains(TEST_CLASSES);
        }
    }

    @Test
    @DisplayName("leaves the context able to start at all")
    void the_application_context_should_refresh_with_the_audit_switches_off() {
        runner.run(context -> assertThat(context)
                .as("a scanned OpenApiSpecificationParser fails the refresh, and this is the case "
                        + "that reports it as a refusal rather than as an error before any "
                        + "assertion runs: the exclusion is what lets a pod start with auditing "
                        + "switched off")
                .hasNotFailed());
    }

    @Test
    @DisplayName("creates no bean of the audit starter's, by scanning or otherwise")
    void no_audit_starter_bean_should_be_on_a_context_with_auditing_switched_off() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(auditStarterBeans(context))
                    .as("with cp.audit.enabled=false the starter's auto-configuration is off, so "
                            + "every bean of its is one the component scan made - and the scan is "
                            + "exactly what bypasses both switches")
                    .isEmpty();
        });
    }

    @Test
    @DisplayName("creates no OpenApiSpecificationParser, which is the bean that fails the refresh")
    void the_unsatisfiable_parser_should_not_be_component_scanned() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(auditStarterBeans(context))
                    .as("named on its own because it is the one that stops the pod rather than "
                            + "merely adding to it")
                    .doesNotContain(THE_UNSATISFIABLE_ONE);
        });
    }

    /**
     * The other half of the claim, and the one no case made.
     *
     * <p>{@link Application}'s javadoc says the exclusion removes the duplicates the scan would
     * make <em>and nothing else</em> - but every case above runs with the transport switched off,
     * so an over-broad regex that also swallowed the starter's {@code @AutoConfiguration} class
     * would have left all three green. This is the context that tells them apart: the transport
     * on, pointed at a host that does not resolve, and the auto-configuration's own bean present
     * anyway. {@code @AutoConfiguration} classes are registered from {@code
     * AutoConfiguration.imports} rather than found by the scan, which is why a scan filter cannot
     * reach them - and why this passes.
     *
     * <p>The host is unresolvable on purpose: the factory is constructed at refresh and does not
     * connect, so the bean is there to assert on without the suite needing a broker.
     */
    @Test
    @DisplayName("does not take the starter's auto-configuration with it")
    void the_audit_transport_should_still_be_auto_configured_where_it_is_switched_on() {
        runner.withPropertyValues("cp.audit.enabled=true",
                        "cp.audit.hosts=artemis-audit.invalid",
                        "cp.audit.port=61616")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .as("the exclusion is a component-scan filter, and an "
                                    + "@AutoConfiguration class is not component-scanned: with "
                                    + "cp.audit.enabled=true the starter contributes its "
                                    + "connection factory exactly as it would in a deployed pod")
                            .hasBean("auditConnectionFactory");
                });
    }

    /**
     * The class names of every bean on this context that belongs to the audit starter.
     *
     * <p>Types are resolved without initialising a {@code FactoryBean}, so asking the question
     * cannot itself create the bean the question is about.
     *
     * @param context the refreshed context
     * @return the class names, in bean-definition order, and empty when the exclusion holds
     */
    private List<String> auditStarterBeans(final AssertableApplicationContext context) {
        return Arrays.stream(context.getBeanDefinitionNames())
                .map(name -> context.getType(name, false))
                .filter(type -> type != null && type.getName().startsWith(AUDIT_PACKAGE))
                .map(Class::getName)
                .toList();
    }
}
