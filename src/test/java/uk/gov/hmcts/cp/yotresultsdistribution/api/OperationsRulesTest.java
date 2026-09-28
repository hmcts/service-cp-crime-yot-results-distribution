package uk.gov.hmcts.cp.yotresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kie.api.KieServices;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import uk.gov.moj.cpp.authz.drools.Action;
import uk.gov.moj.cpp.authz.drools.Outcome;
import uk.gov.moj.cpp.authz.http.providers.UserAndGroupProvider;

/**
 * What {@code acl/operations-rules.drl} admits, and what it refuses by saying nothing.
 *
 * <p>No Spring. The rules are the whole of condition (a) of Principle III, and they are decided by
 * a rule engine rather than by any code in this repository - so the only honest way to assert them
 * is to build the container the library builds, hand it the same two facts and the same global, and
 * read the outcome back.
 *
 * <p><strong>The denial cases are the point.</strong> There is no deny rule in the file and there
 * must never be one: an action is refused because nothing allows it, so a rule accidentally widened
 * to a second group, or a rule added for a path nobody meant to expose, shows up here as a case
 * that stopped refusing rather than as a file nobody read.
 */
@DisplayName("the operations API's authorisation rules")
class OperationsRulesTest {

    /** Where the rules sit, which is also the shape {@code authz.http.drools-classpath-pattern} globs. */
    private static final String RULES = "acl/operations-rules.drl";

    /** The one group any rule may name (design owner, 2026-09-19). */
    private static final String ADMITTED = "Second Line Support";

    /** A group that exists in the estate and is not ours, used for every denial case. */
    private static final String SOMEBODY_ELSE = "Court Clerks";

    /** The group lists the file hands the provider, one capture per rule. */
    private static final Pattern GROUP_LIST =
            Pattern.compile("isMemberOfAnyOfTheSuppliedGroups\\(\\$a,\\s*([^)]*)\\)");

    /** Every rule's opening line, counted so a rule added without a case here is visible. */
    private static final Pattern RULE = Pattern.compile("(?m)^rule \"");

    /** The file as text, for the assertions that are about what it names rather than what it does. */
    private static String rules;

    /** The built rules, shared by every case: compiling them once is the library's own usage. */
    private static KieContainer container;

    @BeforeAll
    static void compileTheRules() throws IOException {
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(RULES)) {
            assertThat(stream).as("the rules must be on the classpath at %s", RULES).isNotNull();
            rules = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        final KieServices services = KieServices.Factory.get();
        final KieFileSystem files = services.newKieFileSystem();
        files.write(services.getResources().newClassPathResource(RULES).setResourceType(ResourceType.DRL));
        services.newKieBuilder(files).buildAll();
        container = services.newKieContainer(services.getRepository().getDefaultReleaseId());
    }

    /**
     * Fires the rules for one action on behalf of a caller in the groups given.
     *
     * @param action what the action filter derived for the request
     * @param groups the groups usersgroups says the caller is in
     * @return what the outcome says, which starts false and is only ever set true by a rule
     */
    // PMD.CloseResource: the session is disposed in the finally below, on every path.
    @SuppressWarnings("PMD.CloseResource")
    private static boolean admits(final String action, final String... groups) {
        final KieSession session = container.newKieSession();
        try {
            final UserAndGroupProvider provider = mock(UserAndGroupProvider.class);
            when(provider.isMemberOfAnyOfTheSuppliedGroups(any(Action.class), any(String[].class)))
                    .thenAnswer(invocation -> {
                        final String[] allowed = (String[]) invocation.getRawArguments()[1];
                        return Arrays.stream(groups).anyMatch(Arrays.asList(allowed)::contains);
                    });
            session.setGlobal("userAndGroupProvider", provider);
            final Outcome outcome = new Outcome();
            session.insert(outcome);
            session.insert(new Action(action, Map.of()));
            session.fireAllRules();
            return outcome.isSuccess();
        } finally {
            session.dispose();
        }
    }

    @Nested
    @DisplayName("a caller in Second Line Support")
    class Admitted {

        @ParameterizedTest
        @ValueSource(strings = {
            "yot-results-distribution-operations.check-flag",
            "yot-results-distribution-operations.list-batches",
            "yot-results-distribution-operations.list-recorded-while-off",
            "yot-results-distribution-operations.generate-register",
            "yot-results-distribution-operations.notify-register",
            "yot-results-distribution-operations.supersede-before",
            "yot-results-distribution-operations.report-exceptions",
        })
        void every_action_should_be_allowed(final String action) {
            assertThat(admits(action, ADMITTED)).isTrue();
        }

        @Test
        void one_admitted_group_among_others_should_be_enough() {
            assertThat(admits("yot-results-distribution-operations.generate-register", SOMEBODY_ELSE, ADMITTED))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("a caller in any other group")
    class Refused {

        @ParameterizedTest
        @ValueSource(strings = {
            "yot-results-distribution-operations.check-flag",
            "yot-results-distribution-operations.list-batches",
            "yot-results-distribution-operations.list-recorded-while-off",
            "yot-results-distribution-operations.generate-register",
            "yot-results-distribution-operations.notify-register",
            "yot-results-distribution-operations.supersede-before",
            "yot-results-distribution-operations.report-exceptions",
        })
        void every_action_should_be_denied(final String action) {
            assertThat(admits(action, SOMEBODY_ELSE)).isFalse();
        }

        @Test
        void a_caller_in_no_group_at_all_should_be_denied() {
            assertThat(admits("yot-results-distribution-operations.check-flag")).isFalse();
        }
    }

    @Nested
    @DisplayName("an action the file does not carry")
    class Unknown {

        @Test
        void an_action_name_no_rule_names_should_be_denied_for_the_admitted_group() {
            assertThat(admits("yot-results-distribution-operations.delete-everything", ADMITTED)).isFalse();
        }

        @Test
        void the_libraries_own_fallback_action_shape_should_be_denied() {
            assertThat(admits("POST /operations/batches/generate", ADMITTED)).isFalse();
        }
    }

    @Nested
    @DisplayName("what the rules file itself says")
    class TheRulesFile {

        @Test
        void it_should_carry_one_rule_per_action_and_no_more() {
            assertThat(RULE.matcher(rules).results().count()).isEqualTo(7L);
        }

        @Test
        void it_should_name_no_group_but_second_line_support() {
            final List<String> named = new ArrayList<>();
            final Matcher matcher = GROUP_LIST.matcher(rules);
            while (matcher.find()) {
                named.add(matcher.group(1).trim());
            }
            assertThat(named).hasSize(7).containsOnly('"' + ADMITTED + '"');
        }

        @Test
        void it_should_carry_no_rule_that_sets_an_outcome_false() {
            assertThat(rules).doesNotContain("setSuccess(false)");
        }
    }
}
