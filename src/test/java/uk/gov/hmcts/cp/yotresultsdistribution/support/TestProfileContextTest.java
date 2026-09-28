package uk.gov.hmcts.cp.yotresultsdistribution.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * The verification for the shared test fixtures: the {@code test} profile really does produce a
 * context with no broker, no database and no Docker.
 *
 * <p>It is the one thing about {@code application-test.yaml} that cannot be read off the file. The
 * profile makes three claims — intake is off, the datasource auto-configuration is excluded, and the
 * readiness group is well formed without a {@code db} contributor — and each of them fails the
 * context refresh when it is wrong, silently in the sense that nothing else in the build would say
 * so until the first suite that needed the profile was written. That suite would then be debugging
 * the fixtures instead of its own subject.
 *
 * <p>A bootstrap task rather than a TDD pair (tasks.md, Phase 1 preamble): there is no behaviour
 * being specified here, so the evidence recorded is the verification itself.
 */
@SpringBootTest
@ActiveProfiles("test") // context-load only: no broker, no database, no Docker (application-test.yaml)
@DisplayName("the test profile")
class TestProfileContextTest {

    private final ApplicationContext context;
    private final Environment environment;

    @Autowired
    TestProfileContextTest(final ApplicationContext context, final Environment environment) {
        this.context = context;
        this.environment = environment;
    }

    @Test
    @DisplayName("refreshes a context with no broker and no database")
    void refreshes_a_context_with_no_broker_and_no_database() {
        assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("test");
        assertThat(context.containsBean("dataSource"))
                .as("the datasource auto-configuration is excluded, so no pool is built and the "
                        + "suites in this profile need no container")
                .isFalse();
    }

    @Test
    @DisplayName("keeps intake switched off, so nothing reaches for a broker")
    void keeps_intake_switched_off() {
        assertThat(environment.getProperty("yotresultsdistribution.consumer.enabled", Boolean.class))
                .as("yotresultsdistribution.consumer.enabled must be false in this profile")
                .isFalse();
    }

    @Test
    @DisplayName("declares a readiness group that needs no store contributor")
    void declares_a_readiness_group_that_needs_no_store_contributor() {
        // Spring validates health-group membership during the refresh, so an absent contributor
        // named here would already have failed the two tests above. This pins the substitution
        // itself: `ping` stands in for the deployed `db,intakeStartup`, and a later edit that put
        // `db` back would take Docker with it.
        assertThat(environment.getProperty(
                "management.endpoint.health.group.readiness.include"))
                .isEqualTo("ping");
    }
}
