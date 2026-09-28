package uk.gov.hmcts.cp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Boot entry point for the YOT results distribution service.
 *
 * <p><strong>The audit exclusion is load-bearing.</strong> {@code cp-audit-filter-springboot}'s
 * package root is {@code uk.gov.hmcts.cp.filter.audit}, which is inside the tree this class's
 * {@code @SpringBootApplication} scans. Five of that library's classes still carry a Spring
 * stereotype, and one of them - {@code OpenApiSpecificationParser}, a {@code @Component} whose only
 * constructor takes a {@code String} - cannot be constructed by the container at all. Scanning it
 * fails the refresh with {@code Unsatisfied dependency expressed through constructor parameter 1:
 * No qualifying bean of type 'java.lang.String'}, and it fails it <strong>with auditing switched
 * off</strong>: a component scan answers to neither {@code cp.audit.enabled} nor
 * {@code audit.http.enabled}, because both of those gate the library's {@code @AutoConfiguration}
 * class and a scanned stereotype never reaches it.
 *
 * <p>{@code @AutoConfiguration} classes are <strong>not</strong> subject to {@code @ComponentScan}
 * filters, so the starter's auto-configuration still runs in full wherever it is switched on - the
 * filter removes the duplicates the scan would make, and nothing else.
 *
 * <p><strong>And the two filters {@code @SpringBootApplication} supplies have to be restated.</strong>
 * Declaring a {@code @ComponentScan} of our own replaces the one that annotation carries, and with
 * it {@link TypeExcludeFilter} and {@link AutoConfigurationExcludeFilter}. The first is what keeps
 * one test's nested {@code @Configuration} out of another test's context; without it this
 * repository's suites collide on the refresh
 * ({@code The bean 'objectMapper' ... has already been defined} - two different slice suites'
 * nested configurations, both scanned into every context). The second is what keeps an
 * auto-configuration class from also being picked up as a component. Neither is optional, and the
 * failure the first one prevents surfaces nowhere near this class.
 *
 * <p>The root is named as {@code basePackageClasses = Application.class} rather than as the string
 * {@code uk.gov.hmcts.cp}: it is the same package either way, and this way moving this class moves
 * the scan with it instead of quietly scanning a tree it no longer lives in.
 *
 * <p>{@code cp-auth-rules-filter} needs no exclusion: its package root is
 * {@code uk.gov.moj.cpp.authz}, outside this tree.
 *
 * <p>Pinned by {@code config/AuditComponentScanTest}, which is written to fail if the audit
 * exclusion is removed rather than to describe it.
 */
@SpringBootApplication
@ComponentScan(
        basePackageClasses = Application.class,
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @ComponentScan.Filter(
                    type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
            @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = "uk\\.gov\\.hmcts\\.cp\\.filter\\.audit\\..*")
        }
)
public class Application {

    /**
     * Starts the Spring container.
     *
     * @param args the command line arguments, passed through to Boot untouched
     */
    public static void main(final String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
