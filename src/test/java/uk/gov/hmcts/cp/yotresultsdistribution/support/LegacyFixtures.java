package uk.gov.hmcts.cp.yotresultsdistribution.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;

/**
 * Reads the legacy {@code NowsHelper} Jest fixtures the shared-kernel twins run against.
 *
 * <p>The files under {@code src/test/resources/fixtures/nowshelper/} are byte-identical copies of
 * {@code $DF/NowsHelper/service/test/}, verified with {@code diff} at copy time, as constitution
 * Principle I requires — the legacy is the oracle for everything the fix register does not
 * catalogue, and a fixture edited on the way in is an oracle that agrees with the port by
 * construction.
 *
 * <p><strong>Repaired fixtures are named, not edited.</strong> Where the design proves a fixture
 * stale or vacuous the repair lands as a <em>new</em> file beside the original, whose name says what
 * was changed and whose provenance note says why. The original stays exactly as the legacy suite
 * has it, so a twin and its repaired counterpart can be read side by side:
 *
 * <ul>
 *   <li>{@code application-case-level-prosecuting-applicant.json} —
 *       {@code application-case-level.json} with the applicant's {@code masterDefendant} replaced by
 *       the {@code prosecutingAuthority} block of {@code application-court-order-level.json}, byte
 *       for byte, and nothing else altered. The original's applicant is another master defendant, so
 *       under the C22 fix the application is not eligible and the fixture gathers no defendant at
 *       all — which is the fix, and which leaves the register-configuration result-level split it
 *       was written to pin with nothing to assert against. This copy pins that split.</li>
 * </ul>
 *
 * <p>They are read through the service's own contract mapper, so a fixture reaches the port exactly
 * as a fetched hearing payload would — {@code USE_BIG_DECIMAL_FOR_FLOATS} included. Reading them
 * with a default mapper would compare the port against a tree the port never sees.
 */
public final class LegacyFixtures {

    private static final String ROOT = "/fixtures/nowshelper/";

    private static final String COURT_REGISTER_ROOT = "/fixtures/courtregister/";

    private static final String REBUILT_ROOT = "/fixtures/rebuilt/";

    private static final String BASE_ROOT = "/fixtures/base/";

    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private LegacyFixtures() {
    }

    /**
     * Reads one legacy fixture.
     *
     * @param name the file name, as it is named in the legacy suite
     * @return the parsed tree
     */
    public static JsonNode read(final String name) {
        return readResource(ROOT + name);
    }

    /**
     * Reads one of the court register's own function-app fixtures.
     *
     * <p>Byte-identical copies, on the same terms as {@link #read(String)} and verified the same
     * way; they live under {@code fixtures/courtregister/} rather than beside the shared-kernel
     * files only because they come from the court-register functions' own {@code test/} directories
     * ({@code SetCourtRegister}, {@code CourtRegisterSubscriptions}, {@code OutboundCourtRegister})
     * rather than from {@code NowsHelper/service/test/}. A fixture the design proves stale or
     * vacuous is repaired under {@code fixtures/rebuilt/}, never here.
     *
     * @param path the file's path below {@code fixtures/courtregister/}, keyed by the function that
     *             owns it
     * @return the parsed tree
     */
    public static JsonNode readCourtRegister(final String path) {
        return readResource(COURT_REGISTER_ROOT + path);
    }

    /**
     * Reads one of the seven fixtures rebuilt to the real eighteen-key vocabulary.
     *
     * <p>These are deliberately <em>not</em> the oracle: they are repairs, they live under
     * {@code fixtures/rebuilt/} rather than beside the byte-identical copies, and
     * {@code fixtures/rebuilt/PROVENANCE.md} records what was changed in each and by what rule.
     *
     * @param path the file's path below {@code fixtures/rebuilt/}
     * @return the parsed tree
     */
    public static JsonNode readRebuilt(final String path) {
        return readResource(REBUILT_ROOT + path);
    }

    /**
     * Reads one of the six authored base hearings.
     *
     * <p>Not legacy at all, and not a repair of one either: these are hearings the legacy suite has
     * no equivalent of, written because the shapes they carry — a complete court centre, a youth
     * defendant who survives to the outbound document, group proceedings, an adult ahead of a youth,
     * an application whose applicant prosecutes nothing, a child and a parent with no address — are
     * the shapes the fixes are about, and not one of them appears in a court-register fixture today.
     *
     * <p>They are payloads rather than bare hearings: {@code {"hearing": …, "sharedTime": …}}, the
     * shape the claim-check actually hands the pipeline. A mapper test that wants the hearing alone
     * reads {@code .get("hearing")} off the answer. {@code fixtures/README.md} records how each was
     * derived, change by named change.
     *
     * @param path the file's name below {@code fixtures/base/}
     * @return the parsed payload
     */
    public static JsonNode readBase(final String path) {
        return readResource(BASE_ROOT + path);
    }

    /**
     * Reads a fixture from the test classpath.
     *
     * @param resource the absolute resource path
     * @return the parsed tree
     */
    private static JsonNode readResource(final String resource) {
        try (InputStream stream = LegacyFixtures.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            return MAPPER.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException cannotRead) {
            throw new IllegalStateException("could not read " + resource, cannotRead);
        }
    }
}
