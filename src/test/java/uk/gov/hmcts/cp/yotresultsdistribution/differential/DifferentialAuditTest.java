package uk.gov.hmcts.cp.yotresultsdistribution.differential;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.adapter.progression.OutboundContractValidator;
import uk.gov.hmcts.cp.yotresultsdistribution.application.GroupProceedingsPolicy;
import uk.gov.hmcts.cp.yotresultsdistribution.application.TransformationResult;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;
import uk.gov.hmcts.cp.yotresultsdistribution.config.ProcessingMetrics;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.yotresultsdistribution.domain.TransformationFailedException;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.Dates;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.DefendantTypeResolver;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.PdfPayloadMapper;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.RegisterBuilder;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.RegisterTransformationChain;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.SubscriptionMatcher;
import uk.gov.hmcts.cp.yotresultsdistribution.pipeline.SubscriptionRules;
import uk.gov.hmcts.cp.yotresultsdistribution.support.DifferentialCorpus;
import uk.gov.hmcts.cp.yotresultsdistribution.support.DifferentialCorpus.RecordedCase;
import uk.gov.hmcts.cp.yotresultsdistribution.support.JsonParity;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisteredDefectFixes;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisteredDefectFixes.Claim;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisteredDefectFixes.Divergence;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisteredDefectFixes.GoldenDeviation;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisteredDefectFixes.PortOutcome;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisteredDefectFixes.PortResult;
import uk.gov.hmcts.cp.yotresultsdistribution.support.RegisteredDefectFixes.ProgressionRow;

/**
 * The differential audit: 381 recorded legacy runs, put through this port, with every difference
 * made to name the {@code doc/DEFECT-FIXES.md} row that allows it.
 *
 * <p><strong>What the audit is for.</strong> Every other suite in this repository asserts what the
 * port <em>should</em> do. This one asserts what the legacy <em>did</em>, and then requires the port
 * to agree with it everywhere no C-number says otherwise. That is the constitution's rule stated as
 * a test: the port encodes fixed behaviour, the legacy is the oracle for everything uncatalogued,
 * and an unattributed difference is a port defect. It fails in both directions — a catalogued defect
 * still reproduced shows up as an unexplained agreement in the pinning suites, and a behaviour
 * change nobody registered shows up here, named by its JSON pointer.
 *
 * <p><strong>The real chain, not a rehearsal of it.</strong> Each case runs the recorded hearing and
 * the recorded reference-data answer through the group-proceedings policy and then through
 * {@link RegisterTransformationChain} built over its real stages — the fragment builder, the
 * subscription matcher, the twelve mappers and the frozen-contract validator. Nothing is stubbed:
 * the chain is pure by construction, so the only things it needs are the two the recorder captured.
 * The subscriptions arrive pre-fetched because that is how the running service supplies them; the
 * legacy activity that reads them is the one part of this flow the audit cannot re-run, and the
 * cases where that read failed are treated accordingly below.
 *
 * <p><strong>Three obligations, decided by the output contract axis.</strong>
 *
 * <ul>
 *   <li><strong>{@code IN_CONTRACT}</strong> — progression accepts the document the legacy produced,
 *       so the port must produce it: every difference must be claimed by
 *       {@link RegisteredDefectFixes}, and an unclaimed one fails naming its path.</li>
 *   <li><strong>{@code SCHEMA_INVALID}</strong> — progression answers 400 and C1 swallows it, so the
 *       register is lost with no trace. The port must classify rather than reproduce: refuse the
 *       document at the contract at a field the recorder's own validator named (C29), or produce a
 *       repaired one that differs at such a field, every difference claimed by the row that
 *       authorises the repair. {@link #classifiedOrRepaired} asserts that on the axis itself rather
 *       than leaving the attribution machinery to imply it — a document reproduced unchanged meets
 *       the agreement question as {@code document} against {@code REGISTER}, which is agreement, so
 *       nothing would differ and nothing would be asked.</li>
 *   <li><strong>{@code NO_DOCUMENT}</strong> — the legacy produced nothing, and its recorded reason
 *       carries the obligation instead. The port's outcome must be the one its row governs: the same
 *       no-op where no row applies, and the row's own answer where one does.</li>
 * </ul>
 *
 * <p><strong>One recording is not an oracle and says so itself.</strong> The three
 * {@code shared-time__absent} cases were recorded with no shared time at all, and {@code moment}
 * reads an absent date as <em>now</em>, so the register they produced is a reading of the clock the
 * corpus was built at. There is nothing there for a port to reproduce — reproducing it would require
 * a clock inside a transformation the constitution requires to be pure — so those cases are held to
 * the one thing that is assertable: the port refuses the payload rather than inventing a date for
 * it. C35's two {@code hearing-date} cases are recorded clock-dependent as well and are
 * <em>not</em> treated this way: there the payload is complete and one field is filled from the
 * clock, so the rest of the document remains an oracle and the field is a difference C35 claims —
 * by reading the recorded value back as a London wall clock and requiring it to name the instant the
 * corpus was built at.
 *
 * <p><strong>One comparison is about a request rather than a value.</strong> C12 moves which day's
 * subscription set a hearing is addressed by, and that never reaches the document: a register built
 * from the wrong day's subscriptions looks entirely ordinary. The recorder captured the whole
 * {@code now-subscriptions} GET, so the audit compares the day the legacy asked for against the day
 * this port's own {@link Dates} answers for the same shared time, and reports the difference like
 * any other.
 *
 * <p><strong>One field has no recorded counterpart, and is left out of the comparison.</strong>
 * Increment 002 records the register instead of posting it, and puts the {@code defendantType}
 * progression used to resolve after the POST onto the document itself. The command the legacy posted
 * does not declare that field, so the corpus holds nothing at that path for any of its 381 cases -
 * see {@link #asPosted}, which takes it out of the tree before anything is compared, and
 * {@code RegisterTransformationChainTest}, which pins what the chain puts there.
 *
 * <p><strong>The second oracle, and the two things nothing asserted together until T069.</strong>
 * Everything above reads the 001 recordings. Increment 002 answers to a second oracle as well -
 * progression's own classes, recorded by running them, under
 * {@code src/test/resources/goldens/progression/} - and the section at the end of this suite is
 * where the two meet. It states two things, and the point is that it states them <em>together</em>:
 *
 * <ul>
 *   <li><strong>001's corpus is unchanged by 002.</strong> Not field by field: the recorder wrote a
 *       digest of every one of the 404 files it read and a manifest digest over the lot
 *       ({@code PROVENANCE.md} "Inputs and the corpus digest"), and
 *       {@link #the_001_corpus_reproduces_the_digest_the_recording_left_on_it()} recomputes both
 *       from the tree on disk. A recording somebody adjusted to agree with the port has stopped
 *       being evidence, and this is the assertion that says so about the whole corpus at once
 *       rather than about whichever field a comparison happened to look at.</li>
 *   <li><strong>Every {@code PdfPayloadMapper} golden is reproduced from its recorded
 *       input.</strong>
 *       {@code PdfPayloadMapperTest} already holds each golden to the file beside it, byte for
 *       byte;
 *       what it cannot say is that the file beside it is the file T004 recorded. So
 *       {@link #every_pdf_payload_golden_is_reproduced_from_its_recorded_input()} closes that loop
 *       through the index's own {@code outputSha256} - the digest recorded at recording time, not a
 *       file the suite reads twice - and does it as one digest over the whole set, so a golden the
 *       mapper stopped being asked about fails the same assertion as a golden it got wrong.</li>
 * </ul>
 *
 * <p><strong>Two places the oracle legitimately has nothing to reproduce, and the rule for
 * each.</strong>
 * Fifty-two of the 213 recorded documents have no golden because progression's generator
 * <em>threw</em> on them - 51 at {@code buildParentGuardianNameAndAddress:184} and one at
 * {@code getAge:325} - and {@code PROVENANCE.md} states the rule: they are recorded as
 * {@code refusal} with no output file, "because there is no output to be equal to", and no such
 * document can reach this mapper in service, because the frozen contract refuses it at the write
 * (C29) so it is never recorded, never batched and never rendered. What <em>is</em> assertable is
 * that this port produces no payload for them either, and
 * {@link #every_document_progression_refused_is_refused_here_too()} asserts exactly that and no
 * more: the refusal's text is a {@code javax.json} message on one side and a Jackson one on the
 * other, so the text is not an oracle and is not compared. The second place is
 * {@code defendantType}, and {@link #asPosted} explains it; the case below is the missing half of
 * that explanation, which is that the removal takes out nothing the corpus ever held.
 *
 * <p><strong>It is fast because it is pure.</strong> The whole corpus runs in seconds against no
 * container, no socket and no clock, so it needs no tag and runs in {@code ./gradlew build} with
 * everything else. The goldens section keeps that property: {@code PdfPayloadMapper} takes the
 * clock the goldens were recorded against, read out of the index, because {@code cases[].age} is
 * the one field in the payload that reads one.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a>
 */
// PMD.OnlyOneReturn: this suite is a decision table — which obligation a case carries, whether two
// runs ended in the same place, which row explains a divergence — and each question answers where it
// is decided. A single exit would put every one of those answers behind one variable.
@SuppressWarnings("PMD.OnlyOneReturn")
@DisplayName("Differential audit — 381 recorded legacy runs against the port")
class DifferentialAuditTest {

    private static final Logger LOG = LoggerFactory.getLogger(DifferentialAuditTest.class);

    /** Where the register the audit reads its C-numbers from lives, for the citation check. */
    private static final Path DEFECT_FIXES = Path.of("doc", "DEFECT-FIXES.md");

    /**
     * A register row number at the head of a registered reference, e.g. {@code C10 (…)}.
     *
     * <p>Either catalogue. The {@code C} rows are the 001 function app's defects and the {@code P}
     * rows are progression's, and increment 002 answers to both oracles, so one citation rule
     * spans them: an unregistered {@code P} deviation is refused exactly as an unregistered
     * {@code C} one is.
     */
    private static final Pattern REGISTER_ROW = Pattern.compile("^([CP]\\d+) ");

    /** How a classified contract refusal names the field at fault in its message. */
    private static final String AT = " at ";

    /** The recorded reason a legacy run gave for matching nobody. */
    private static final String NO_MATCHED_SUBSCRIPTIONS = "no matched subscriptions";

    /** The recorded reason a legacy run gave for finding no youth. */
    private static final String NO_YOUTH_DEFENDANT = "no youth defendant";

    /**
     * What each row was found to explain — components the two runs actually rendered differently.
     *
     * <p>A difference and not an evaluation. A claim is only ever consulted about something that
     * already differs, but a derivation is asked about every component it covers, and for half of
     * them it is the identity: C10 relabels nothing in winter, and counting those as differences
     * would report the fix as reaching a hundred and thirty-one places the corpus never made it
     * change anything.
     */
    private static final Map<String, Integer> DIFFERED = new LinkedHashMap<>();

    /**
     * How many components each derivation was asked about, whether or not the two runs differed.
     *
     * <p>The other half of the same reading, and the one that answers "was this fix reached at
     * all". Only derivations appear here: a claim has no evaluated population to speak of.
     */
    private static final Map<String, Integer> EVALUATED = new LinkedHashMap<>();

    /** Cases the corpus itself disqualifies as oracles, listed in the summary. */
    private static final List<String> NOT_AN_ORACLE = new ArrayList<>();

    /**
     * One case and place per row, so the summary is checkable rather than only countable.
     *
     * <p>A count alone cannot be followed back to anything. The report T075 commits names, for every
     * row, a case a reader can open — and the first one the audit meets is as good a witness as any
     * and is stable, because the corpus is enumerated in the order the recorder wrote it.
     */
    private static final Map<String, String> EXAMPLES = new LinkedHashMap<>();

    /** The production date reader, for the one comparison that is about a request and not a value. */
    private static final Dates DATES = new Dates();

    /** The mapper both oracles are read with, so a recording arrives as the service reads it. */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /** Where T004 wrote what it recorded of progression's own classes, on the test classpath. */
    private static final String GOLDENS = "/goldens/progression/";

    /** Where the recorded 001 corpus the goldens were made from lives, on the test classpath. */
    private static final String RECORDED = "/differential/recorded/";

    /** How progression's caller spells "this register names no court application". */
    private static final String NO_TYPE = "";

    /** The prefix the index's repo-relative input paths carry and the classpath does not. */
    private static final String SOURCE_ROOT = "src/test/resources";

    /** The array progression's payload generator reads a batch's documents out of. */
    private static final String REQUESTS = "courtRegisterDocumentRequests";

    /** The one field increment 002 adds that the command the legacy posted never declared. */
    private static final String DEFENDANT_TYPE = "defendantType";

    /** The three index sections that name a recorded golden. */
    private static final List<String> GOLDEN_SECTIONS =
            List.of("pdfPayloadDocuments", "pdfPayloadBatches", "defendantType");

    /** T004's own record of what it recorded: every golden, its inputs, and the counts. */
    private static final JsonNode INDEX = readResource(GOLDENS + "INDEX.json");

    /** The digest algorithm the recorder wrote every digest in that index with. */
    private static final String SHA_256 = "SHA-256";

    /** The two spaces a manifest line puts between a digest and the path it is of. */
    private static final String MANIFEST_GAP = "  ";

    /** How many files the recorder read, so a corpus that quietly shrank is caught by count too. */
    private static final int RECORDED_INPUTS = 404;

    /** How many of the 381 recorded cases produced a document, and so are oracles for one. */
    private static final int RECORDED_DOCUMENTS = 205;

    /** How many goldens the recorder wrote across the three sections: 161 + 7 + 9. */
    private static final int RECORDED_GOLDENS = 177;

    /** How many of those are the payload generator's: 161 documents and 7 whole batches. */
    private static final int PDF_PAYLOAD_GOLDENS = 168;

    /** How many recorded documents progression's generator threw on rather than mapping. */
    private static final int PROGRESSION_REFUSALS = 52;

    /** How many recorded defendant-type goldens carry a thrown stack instead of an answer. */
    private static final int UNREADABLE_APPLICATIONS = 2;

    /** The zone the goldens were recorded in, which is the only zone this service runs in. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** The payload generator's own answer, for the goldens leg. */
    private final PdfPayloadMapper payloads = new PdfPayloadMapper(recordingClock());

    /** Progression's defendant-type rule as this port carries it, for the P-row leg. */
    private final DefendantTypeResolver defendantTypes = new DefendantTypeResolver();

    /** Everything the mappers skipped on the way, which C19, C20 and C27 count rather than fail on. */
    private final List<TransformationAnomaly> anomalies = new ArrayList<>();

    private final GroupProceedingsPolicy groupProceedings =
            new GroupProceedingsPolicy(new ProcessingMetrics(new SimpleMeterRegistry()));

    private final RegisterTransformationChain chain = new RegisterTransformationChain(
            new RegisterBuilder(new Dates()),
            new SubscriptionMatcher(new SubscriptionRules()),
            new OutboundContractValidator(JacksonConfig.contractObjectMapper()));

    /**
     * Every recorded case, as the parameter of the audit below.
     *
     * @return the case identifiers
     */
    static List<String> recordedCorpus() {
        return DifferentialCorpus.caseIds();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordedCorpus")
    @DisplayName("differs from the legacy only where a defect-fix row says it may")
    void differs_from_the_legacy_only_where_a_defect_fix_row_says_it_may(final String caseId) {
        final RecordedCase recorded = DifferentialCorpus.load(caseId);
        final PortOutcome port = run(recorded);

        if (recorded.sharedTimeAbsent()) {
            // The recording is a reading of the clock it was made at (moment.tz(undefined, zone) is
            // the current time), so there is no oracle here to agree or disagree with — not the
            // register date, not the file-name day, and not the day the subscription set was read
            // for. What is assertable is that the port did not invent one either.
            //
            // Only the shared-time cases are held this way, and deliberately so. C35's two clock
            // legs are ALSO recorded clock-dependent, but there the whole payload is present and it
            // is one field the legacy fills from the clock, so the rest of the document is a
            // perfectly good oracle and the field itself is a difference C35 claims by proving the
            // recorded value IS the clock. Excluding every clock-dependent case would have hidden
            // exactly the row the corpus was extended to reach.
            NOT_AN_ORACLE.add(caseId);
            assertThat(port.result())
                    .describedAs("%s was recorded with no shared time, so its output is a reading "
                            + "of the corpus's own clock; the port must refuse the payload rather "
                            + "than invent a date for it", caseId)
                    .isEqualTo(PortResult.FAILED);
            return;
        }

        final List<Divergence> divergences = divergences(recorded, port);
        tallyDerivations(recorded, port);
        for (final Divergence divergence : divergences) {
            tally(caseId, divergence);
        }
        classifiedOrRepaired(caseId, recorded, port, divergences);
    }

    @Test
    @DisplayName("registers nothing that is not a row of doc/DEFECT-FIXES.md")
    void registers_nothing_that_is_not_a_row_of_the_defect_fix_register() {
        // The register is only worth what its citations are worth: an entry quoting a C-number that
        // does not exist would attribute a behaviour change to a review that never happened.
        final String rows = read(DEFECT_FIXES);
        final List<String> cited = new ArrayList<>();
        RegisteredDefectFixes.claims().forEach(claim -> cited.add(claim.reference()));
        RegisteredDefectFixes.progressionLegRows().forEach(row -> cited.add(row.reference()));
        cited.add(RegisteredDefectFixes.forProperty("registerDate").reference());
        cited.add(RegisteredDefectFixes.forProperty("wording").reference());

        assertThat(cited).isNotEmpty();
        for (final String reference : cited) {
            final Matcher number = REGISTER_ROW.matcher(reference);
            assertThat(number.find())
                    .describedAs("every registered entry opens with its register row number, but "
                            + "%s does not", reference)
                    .isTrue();
            assertThat(rows)
                    .describedAs("%s cites %s, which is not a row of doc/DEFECT-FIXES.md",
                            reference, number.group(1))
                    .contains("| " + number.group(1) + " | ");
        }
    }

    @Test
    @DisplayName("audits the whole recorded corpus and not a subset of it")
    void audits_the_whole_recorded_corpus() {
        // A corpus that quietly shrank would make this suite pass by looking at less.
        assertThat(recordedCorpus()).hasSize(381);
    }

    // --- the second oracle: 001's corpus, and progression's own recorded output ------------------

    @Test
    @DisplayName("[A] reproduces increment 001's whole recorded corpus, digest for digest")
    void the_001_corpus_reproduces_the_digest_the_recording_left_on_it() {
        // [A] characterisation. The recorder wrote a digest of every file it read and a manifest
        // digest over the sorted lot, precisely so that a corpus somebody adjusted to agree with
        // the port could be detected without re-reading 404 files by eye. Both halves are
        // recomputed here from the tree on disk: the per-file digests first, because PROVENANCE.md
        // says the point of keeping them is that a changed input can be *found* rather than only
        // detected, and then the manifest digest, which is the statement about the whole corpus and
        // the one that also catches an input added or taken away.
        final JsonNode recordedDigests = INDEX.get("inputDigests");
        final List<String> moved = new ArrayList<>();
        final StringBuilder manifest = new StringBuilder(RECORDED_INPUTS * 80);
        for (final String path : propertyNames(recordedDigests).stream().sorted().toList()) {
            final String digest = digestOf(Path.of(path));
            if (!digest.equals(recordedDigests.get(path).stringValue())) {
                moved.add(path);
            }
            manifest.append(digest).append(MANIFEST_GAP).append(path).append('\n');
        }

        assertThat(recordedDigests.size())
                .describedAs("the recorder read 404 files; an index naming fewer would let this "
                        + "case pass by looking at less")
                .isEqualTo(RECORDED_INPUTS);
        assertThat(moved)
                .describedAs("these recorded inputs are no longer the bytes T004 read, so the "
                        + "oracle has been edited rather than the port corrected")
                .isEmpty();
        assertThat(digestOf(manifest.toString()))
                .describedAs("the corpus manifest digest is what says increment 001's corpus is "
                        + "unchanged as a whole, inputs added or removed included")
                .isEqualTo(INDEX.get("corpusDigest").stringValue());
    }

    @Test
    @DisplayName("[A] holds every golden T004 recorded to the digest it was recorded under")
    void every_recorded_golden_is_the_one_that_was_recorded() {
        // [A] characterisation, and the half that lets the case below compare against the recording
        // rather than against a file: outputSha256 was written at recording time, so a golden
        // edited afterwards fails here even though every suite that reads it agrees with it.
        final List<String> moved = new ArrayList<>();
        int counted = 0;
        for (final JsonNode entry : recordedGoldens()) {
            counted++;
            final String golden = entry.get("golden").stringValue();
            if (!digestOf(Path.of(SOURCE_ROOT, GOLDENS.substring(1) + golden))
                    .equals(entry.get("outputSha256").stringValue())) {
                moved.add(golden);
            }
        }

        assertThat(counted)
                .describedAs("161 document goldens, 7 batch goldens and 9 defendant-type goldens")
                .isEqualTo(RECORDED_GOLDENS);
        assertThat(moved)
                .describedAs("these goldens are no longer the bytes the recorder wrote")
                .isEmpty();
    }

    @Test
    @DisplayName("[A] reproduces every PdfPayloadMapper golden from the input it was recorded from")
    void every_pdf_payload_golden_is_reproduced_from_its_recorded_input() {
        // [A] characterisation. PdfPayloadMapperTest holds each golden to the file beside it, byte
        // for byte, with its own canonical writer; what it cannot say is that the file beside it is
        // the one T004 recorded. This says both at once, and says it as one digest over the whole
        // set rather than 168 comparisons: the manifest is built from the goldens the mapper
        // actually reproduced and is required to equal the manifest of every golden the index
        // names, so a golden the mapper got wrong and a golden nobody asked it about fail the same
        // assertion. Key order is the one thing forgiven, for the reason PdfPayloadMapperTest gives
        // - javax.json and Jackson disagree about insertion order and the template reads the
        // payload by name - and trees are compared, which is exactly that tolerance and no other.
        final List<String> notReproduced = new ArrayList<>();
        final StringBuilder reproduced = new StringBuilder(PDF_PAYLOAD_GOLDENS * 80);
        final StringBuilder named = new StringBuilder(PDF_PAYLOAD_GOLDENS * 80);
        for (final JsonNode entry : payloadGoldens()) {
            final String golden = entry.get("golden").stringValue();
            final String digest = entry.get("outputSha256").stringValue();
            named.append(digest).append(MANIFEST_GAP).append(golden).append('\n');
            final JsonNode recorded = readResource(GOLDENS + golden);
            if (mapped(entry).map(recorded::equals).orElse(false)) {
                reproduced.append(digest).append(MANIFEST_GAP).append(golden).append('\n');
            } else {
                notReproduced.add(golden);
            }
        }

        assertThat(payloadGoldens()).hasSize(PDF_PAYLOAD_GOLDENS);
        assertThat(notReproduced)
                .describedAs("these goldens are not reproduced from the input they were recorded "
                        + "from, which is a defect in this increment rather than an expectation to "
                        + "move: the recording is what progression did")
                .isEmpty();
        assertThat(digestOf(reproduced.toString()))
                .describedAs("the digest over every payload golden reproduced must be the digest "
                        + "over every payload golden the index names")
                .isEqualTo(digestOf(named.toString()));
    }

    @Test
    @DisplayName("[A] produces no payload for the documents progression's generator refused")
    void every_document_progression_refused_is_refused_here_too() {
        // [A] characterisation, and the rule rather than a skip. PROVENANCE.md: the 52 are recorded
        // as a refusal with no output file "because there is no output to be equal to", and no such
        // document reaches this mapper in service, because the frozen contract refuses it at the
        // write (C29) so it is never recorded, never batched and never rendered. So the refusal's
        // text is not an oracle - progression dereferenced an absent value through javax.json and
        // this port does it through Jackson, so the two messages differ by construction - and what
        // is asserted is the only thing that is assertable: no payload comes back on either side.
        final List<String> produced = new ArrayList<>();
        final List<String> misrecorded = new ArrayList<>();
        for (final JsonNode entry : refusedDocuments()) {
            final String caseId = entry.get("caseId").stringValue();
            final String status = entry.get("contractStatus").stringValue();
            if (entry.get("refusal").isNull() || !RecordedCase.SCHEMA_INVALID.equals(status)) {
                misrecorded.add(caseId);
            }
            if (mapped(entry).isPresent()) {
                produced.add(caseId);
            }
        }

        assertThat(refusedDocuments()).hasSize(PROGRESSION_REFUSALS);
        assertThat(misrecorded)
                .describedAs("a recorded document with no golden is only permitted where the "
                        + "recorder wrote the refusal that came instead, and every one of those is "
                        + "a document the frozen contract was already refusing (C29)")
                .isEmpty();
        assertThat(produced)
                .describedAs("progression's generator threw on these documents, so a payload from "
                        + "this mapper is a payload no recording accounts for")
                .isEmpty();
    }

    @Test
    @DisplayName("[A] takes nothing out of the comparison that the recorded corpus ever carried")
    void the_corpus_carries_nothing_at_the_field_002_adds() {
        // [A] characterisation, and the missing half of asPosted's explanation. That method removes
        // defendantType before anything is compared, on the grounds that the add-court-register
        // command the legacy posted does not declare it. Stated as prose that is an argument; here
        // it is a fact about all 381 recordings, so the removal is shown to hide no difference
        // rather than asserted to.
        final List<String> carrying = new ArrayList<>();
        int documents = 0;
        for (final String caseId : recordedCorpus()) {
            final RecordedCase recorded = DifferentialCorpus.load(caseId);
            if (!recorded.producedDocument()) {
                continue;
            }
            documents++;
            if (recorded.expected().get(DEFENDANT_TYPE) != null) {
                carrying.add(caseId);
            }
        }

        assertThat(documents)
                .describedAs("205 of the 381 recorded cases produced a document at all")
                .isEqualTo(RECORDED_DOCUMENTS);
        assertThat(carrying)
                .describedAs("these recordings carry a defendantType, so the corpus is an oracle "
                        + "for it after all and asPosted is taking a real comparison out")
                .isEmpty();
    }

    @Test
    @DisplayName("[A] attributes every deviation from the recorded goldens to one P row")
    void every_deviation_from_the_progression_oracle_names_its_p_row() {
        // [A] characterisation. The same rule as the corpus half, over the second oracle: where
        // this port and a recorded golden disagree, exactly one doc/DEFECT-FIXES.md row must
        // explain it. Two of the nine defendant-type goldens carry a thrown stack rather than an
        // answer, which is P10, and the other seven are reproduced - DefendantTypeResolverTest owns
        // the per-case pins; what this case owns is that those two are the *only* deviations, so a
        // tenth answer that changed would arrive here with no row to name.
        final List<GoldenDeviation> deviations = new ArrayList<>();
        for (final JsonNode entry : INDEX.get("defendantType")) {
            if (entry.get("golden").isNull()) {
                continue;
            }
            final GoldenDeviation deviation = answeredFor(entry);
            if (deviation != null) {
                deviations.add(deviation);
            }
        }

        assertThat(deviations)
                .describedAs("the two shapes progression's rule cannot read are the whole of what "
                        + "this port answers differently")
                .hasSize(UNREADABLE_APPLICATIONS);
        for (final GoldenDeviation deviation : deviations) {
            final List<ProgressionRow> rows = RegisteredDefectFixes.claimedBy(deviation);
            assertThat(rows)
                    .describedAs("%s: progression recorded %s and this port answers %s, which no "
                            + "doc/DEFECT-FIXES.md P row explains, so it is a port defect until "
                            + "one says otherwise", deviation.goldenId(),
                            deviation.recordedRefusal() == null
                                    ? deviation.recordedAnswer() : deviation.recordedRefusal(),
                            deviation.portAnswer())
                    .hasSize(1);
            DIFFERED.merge(rows.get(0).reference(), 1, Integer::sum);
            EXAMPLES.putIfAbsent(rows.get(0).reference(),
                    deviation.goldenId() + " - the recorded defendant-type answer");
        }
    }

    /**
     * Prints what each row was found to explain, which is the audit's own evidence for T075.
     */
    @AfterAll
    static void report() {
        final StringBuilder summary = new StringBuilder(512)
                .append("\nDifferential audit — components evaluated and actual differences, by "
                        + "defect-fix row:");
        final Set<String> rows = new TreeSet<>(EVALUATED.keySet());
        rows.addAll(DIFFERED.keySet());
        for (final String row : rows) {
            summary.append("\n  ").append(row).append(" — ");
            if (EVALUATED.containsKey(row)) {
                summary.append(EVALUATED.get(row)).append(" component(s) evaluated, ");
            }
            summary.append(DIFFERED.getOrDefault(row, 0))
                    .append(" actual difference(s)\n      e.g. ")
                    .append(EXAMPLES.getOrDefault(row,
                            "nothing in this corpus is rendered differently here"));
        }
        summary.append("\n  ").append(NOT_AN_ORACLE.size())
                .append(" × case(s) the corpus marks clock-dependent, held to a refusal instead: ")
                .append(NOT_AN_ORACLE);
        // The audit's own evidence, logged rather than asserted: T075 reconciles these counts
        // against doc/DEFECT-FIXES.md, where a fix that explains nothing means the corpus misses
        // its shape rather than that the fix is absent.
        LOG.info("{}", summary);
    }

    // --- running the port ------------------------------------------------------------------------

    /**
     * What this port does with one recorded case's inputs.
     *
     * @param recorded the recorded case
     * @return the outcome
     */
    private PortOutcome run(final RecordedCase recorded) {
        if (recorded.subscriptionsNeverAnswered()) {
            // The legacy caught the reference-data failure and carried on with nothing; this port's
            // read raises out of the adapter, so the transformation is never reached at all. There
            // is nothing to run here, and saying so is more honest than running the chain against a
            // subscription set the port would never have been given.
            return PortOutcome.notTransformed(
                    "reference data never answered the now-subscriptions read");
        }

        final DistributionCommand command = commandFor(recorded);
        try {
            if (groupProceedings.suppresses(command, recorded.hearing())) {
                return new PortOutcome(PortResult.SUPPRESSED, null, "group-proceedings", null, "");
            }
            final TransformationResult result = chain.transform(
                    command, recorded.payload(), recorded.subscriptions(), anomalies::add);
            if (result instanceof TransformationResult.Register register) {
                return new PortOutcome(PortResult.REGISTER,
                        asPosted(register.document()), null, null, "");
            }
            return new PortOutcome(PortResult.NO_REGISTER, null,
                    ((TransformationResult.NoRegister) result).reason().completion().value(),
                    null, "");
        } catch (TransformationFailedException classified) {
            return new PortOutcome(PortResult.FAILED, null, null,
                    classified.reason().name(), pointerOf(classified));
        }
    }

    /**
     * The register as the output contract the corpus was recorded against renders it: the assembled
     * document, without the one field increment 002 adds.
     *
     * <p>{@code defendantType} is a legal, optional field of the frozen register document
     * ({@code courtRegisterDocumentRequest.json:30-32}) and is not a field of the
     * {@code add-court-register} command the legacy posted, which is
     * {@code additionalProperties: false} and does not declare it: progression resolved the type for
     * itself once the POST had arrived ({@code CourtRegisterHandler.java:131-153}), and that is the
     * leg 002 consolidates. So the recorded corpus has nothing at that path and never could have
     * had - it is not an oracle for a field the flow it recorded did not carry - and the field is
     * taken out of the tree before the comparison rather than being attributed to a defect-fix row,
     * which is what {@link RegisteredDefectFixes} would otherwise demand of it. It is not left
     * unattributed either: {@code RegisterTransformationChainTest} pins what the chain puts there,
     * against the answers {@code DefendantTypeResolverTest} holds to progression's own recorded
     * goldens.
     *
     * @param document the assembled register
     * @return the tree this audit compares, which is the document the legacy's contract carried
     */
    private JsonNode asPosted(final CourtRegisterDocument document) {
        final ObjectNode posted = (ObjectNode) MAPPER.valueToTree(document);
        posted.remove("defendantType");
        return posted;
    }

    /**
     * The validated request a recorded case would have been admitted under.
     *
     * <p>The hearing day and the request identity are the message's, not the payload's, and neither
     * reaches the transformation: the chain reads its dates from the envelope. They are filled in so
     * the correlation fields of the chain's own logging are the shape they are in production.
     *
     * @param recorded the recorded case
     * @return the command
     */
    private static DistributionCommand commandFor(final RecordedCase recorded) {
        return new DistributionCommand(
                "RESULTS",
                UUID.nameUUIDFromBytes(recorded.caseId().getBytes(StandardCharsets.UTF_8)),
                recorded.hearingId(),
                LocalDate.parse("2020-01-20"),
                Instant.parse("2020-06-01T10:00:00Z"),
                "Hearing_Resulted");
    }

    /**
     * The JSON pointer a classified contract refusal names.
     *
     * <p>Read out of the message rather than off the exception: the bounded
     * {@code OUTBOUND_CONTRACT_VIOLATION} is what the pipeline records and what the dead-letter
     * carries, and the pointer is deliberately a log-and-message diagnostic rather than a second
     * field on the failure (C29). The audit wants it because a refusal for the right document and
     * the wrong reason is still a difference.
     *
     * @param classified the failure
     * @return the pointer, or the empty string where the failure named no field
     */
    private static String pointerOf(final TransformationFailedException classified) {
        final String message = classified.getMessage();
        final int at = message == null ? -1 : message.lastIndexOf(AT);
        return at < 0 ? "" : message.substring(at + AT.length());
    }

    // --- what differs ----------------------------------------------------------------------------

    /**
     * Everything about this run that the legacy did differently.
     *
     * <p>At most one outcome divergence — the run as a whole ended somewhere else — and then, only
     * where both sides produced a document, one field divergence per component the comparator
     * cannot reconcile.
     *
     * @param recorded the recorded case
     * @param port     what the port did
     * @return the divergences, empty where the port did exactly what the legacy did
     */
    private static List<Divergence> divergences(
            final RecordedCase recorded, final PortOutcome port) {

        final List<Divergence> divergences = new ArrayList<>(referenceDataDay(recorded, port));
        if (!agrees(recorded, port)) {
            divergences.add(new Divergence.Outcome(recorded, port));
            return List.copyOf(divergences);
        }
        if (!recorded.producedDocument()) {
            return List.copyOf(divergences);
        }
        JsonParity.differences(recorded.expected(), port.document()).stream()
                .map(difference -> (Divergence) new Divergence.Field(recorded, port,
                        difference.path(), difference.expected(), difference.actual()))
                .forEach(divergences::add);
        return List.copyOf(divergences);
    }

    /**
     * Whether the two runs would read the same day's subscription set, as a divergence where they
     * would not.
     *
     * <p>The one externally-visible effect of C10 that never reaches the document. The recorder
     * captured the whole {@code now-subscriptions} GET — query string included — so the day the
     * legacy asked for is evidence rather than a re-derivation, and the day this port asks for is
     * computed here from the same shared time by the production {@link Dates}. A register addressed
     * from the wrong day's subscription set is otherwise indistinguishable from a correct one, which
     * is precisely why C12 is its own row and why it took a comparison of its own to observe.
     *
     * @param recorded the recorded case
     * @param port     what the port did
     * @return the divergence, or nothing where the two days agree or the run never made the read
     */
    private static List<Divergence> referenceDataDay(
            final RecordedCase recorded, final PortOutcome port) {

        final String legacyDay = recorded.referenceDataDay().orElse(null);
        final String sharedTime = recorded.sharedTime().orElse(null);
        if (legacyDay == null || sharedTime == null) {
            return List.of();
        }
        final String portDay;
        try {
            portDay = DATES.subscriptionDay(sharedTime).toString();
        } catch (TransformationFailedException theSharedTimeIsUnreadable) {
            // The port refuses the payload rather than reading a day out of it, which is the whole
            // of C2's territory and is already claimed as an outcome divergence.
            return List.of();
        }
        return legacyDay.equals(portDay)
                ? List.of()
                : List.of(new Divergence.ReferenceDataDay(recorded, port, legacyDay, portDay));
    }

    /**
     * Whether the port's run ended where the legacy's did.
     *
     * <p>The legacy's four endings map onto this port's five, and two of them have no counterpart at
     * all: a run whose exception was caught, logged and discarded reported {@code Success: true}
     * with no register and no record, and so did a run whose reference-data read never answered.
     * Neither is an ending this port has, which is C1's and C2's whole point — so those cases are
     * never agreement, and the register has to say what the port's ending is instead.
     *
     * @param recorded the recorded case
     * @param port     what the port did
     * @return whether the two runs ended in the same place
     */
    // PMD.OnlyOneReturn: this is a mapping table written as one, and a single exit would hide which
    // of the legacy's endings was matched.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static boolean agrees(final RecordedCase recorded, final PortOutcome port) {
        if (recorded.subscriptionsNeverAnswered()) {
            return false;
        }
        return switch (recorded.outcome()) {
            case "document" -> port.result() == PortResult.REGISTER;
            case "skipped-group-proceedings" -> port.suppressed();
            case "swallowed-exception" -> false;
            case "no-document" -> agreesOnNoDocument(recorded, port);
            default -> false;
        };
    }

    /**
     * Whether the port declined for the same recorded reason the legacy did.
     *
     * @param recorded the recorded case
     * @param port     what the port did
     * @return whether the two no-ops are the same no-op
     */
    private static boolean agreesOnNoDocument(
            final RecordedCase recorded, final PortOutcome port) {

        final String reason = recorded.noDocumentReason();
        return reason.endsWith(NO_MATCHED_SUBSCRIPTIONS) && port.isNoRegister("no-subscriptions")
                || reason.endsWith(NO_YOUTH_DEFENDANT) && port.isNoRegister("no-youth-defendants");
    }

    // --- attributing it --------------------------------------------------------------------------

    /**
     * Requires exactly one registered row to explain a divergence, and counts it.
     *
     * @param caseId     the case the divergence was observed in
     * @param divergence the divergence
     */
    private static void tally(final String caseId, final Divergence divergence) {
        final List<Claim> claims = RegisteredDefectFixes.claimedBy(divergence);

        assertThat(claims)
                .describedAs("%s: %s is not explained by any doc/DEFECT-FIXES.md row, so it is a "
                        + "port defect until one says otherwise — %s",
                        caseId, where(divergence), describe(divergence))
                .isNotEmpty();
        assertThat(claims)
                .describedAs("%s: %s is claimed by more than one row, so the register has stopped "
                        + "saying which fix produced what", caseId, where(divergence))
                .hasSize(1);

        DIFFERED.merge(claims.get(0).reference(), 1, Integer::sum);
        EXAMPLES.putIfAbsent(claims.get(0).reference(), caseId + " — " + where(divergence));
    }

    /**
     * Requires a document the frozen contract refuses to be classified or repaired, never reproduced.
     *
     * <p>The obligation the output-contract axis carries, asserted rather than left to the
     * attribution machinery to imply. Agreement is the wrong question for a {@code SCHEMA_INVALID}
     * case: progression answers 400 and C1 swallows it, so reproducing the legacy's document exactly
     * is reproducing a register that is lost with no trace — and it would reach
     * {@link #agrees(RecordedCase, PortOutcome)} as {@code document} against {@code REGISTER}, which
     * is agreement, which is no divergence, which is a green build for the one behaviour the port
     * exists to stop.
     *
     * <p>So the axis decides, and there are three endings the row allows:
     *
     * <ul>
     *   <li>the port <strong>refuses</strong> the document at the contract, naming a field the
     *       recorder's own validator named — C29's obligation, stated here as the obligation rather
     *       than inferred from that row's predicate having fired;</li>
     *   <li>the port <strong>repairs</strong> it: the document it produced passed this port's own
     *       validator on the way out of the chain, and differs from the recorded one at a field the
     *       recorder's validator refused. Every such difference has already been required to name
     *       exactly one row, so a repair is an attributed repair or the case has already failed;</li>
     *   <li>the port <strong>does not produce that document at all</strong> — it matched nobody, was
     *       suppressed, or never ran — an ending that is itself a divergence and has already been
     *       required to name its row.</li>
     * </ul>
     *
     * <p>An unchanged invalid output is none of the three and fails here.
     *
     * @param caseId      the case being audited
     * @param recorded    the recorded case
     * @param port        what the port did
     * @param divergences everything about the run that differed
     */
    private static void classifiedOrRepaired(
            final String caseId,
            final RecordedCase recorded,
            final PortOutcome port,
            final List<Divergence> divergences) {

        if (!RecordedCase.SCHEMA_INVALID.equals(recorded.contractStatus())) {
            return;
        }
        if (port.refusedByTheContract()) {
            assertThat(recorded.violationPointers())
                    .describedAs("%s: the legacy's document was refused by the frozen contract and "
                            + "the port refuses it too, but at %s — a field the recorder's own "
                            + "validator never named, so the port is refusing the right document "
                            + "for the wrong reason", caseId, port.failurePointer())
                    .anyMatch(pointer -> port.failurePointer().startsWith(pointer));
            return;
        }
        if (port.result() != PortResult.REGISTER) {
            return;
        }
        assertThat(repairedFields(recorded, divergences))
                .describedAs("%s: progression answers 400 to the document the legacy produced "
                        + "(refused at %s) and C1 swallows it, so the register is lost with no "
                        + "trace. The port assembled a register that repairs nothing the contract "
                        + "refused, which is that same lost register reproduced — it must refuse "
                        + "the document at the contract instead, or repair the field the contract "
                        + "named", caseId, recorded.violationPointers())
                .isNotEmpty();
    }

    /**
     * The refused fields the port's own document no longer carries as the legacy wrote them.
     *
     * <p>A difference at a refused pointer, or anywhere inside or above one: {@code ajv} reports a
     * required failure against the object and the repair may be the property, and the other way
     * about for a value the port omitted from a subtree the contract refused whole.
     *
     * @param recorded    the recorded case
     * @param divergences everything about the run that differed
     * @return the pointers of the repairs, empty where the port repaired nothing the contract named
     */
    private static List<String> repairedFields(
            final RecordedCase recorded, final List<Divergence> divergences) {

        final List<String> refused = recorded.violationPointers();
        return divergences.stream()
                .filter(divergence -> divergence instanceof Divergence.Field)
                .map(divergence -> ((Divergence.Field) divergence).path())
                .filter(path -> refused.stream().anyMatch(
                        pointer -> path.startsWith(pointer) || pointer.startsWith(path)))
                .toList();
    }

    /**
     * Counts the components the comparator reconciled by derivation rather than by equality.
     *
     * <p>They never become divergences — the register's whole point is that the comparator computes
     * what the port owes and demands exactly that — so without this the report would show C10 and
     * C24 explaining nothing, which is precisely the reading T075 must not draw from it: a fix that
     * produces no difference is evidence the corpus misses its shape, and these two produce a great
     * many.
     *
     * <p><strong>Two counts, because they answer two questions.</strong> A component a derivation
     * covers has been <em>evaluated</em> whether or not the two runs rendered it differently, and
     * for C10 the derivation is the identity for half the year — every GMT share leaves the value
     * exactly where the legacy wrote it. Counting those as differences would report the fix as
     * changing something at every place it was consulted, which is a claim the corpus does not
     * support and the one the reconciliation in T075's report turns on.
     *
     * @param recorded the recorded case
     * @param port     what the port did
     */
    private static void tallyDerivations(final RecordedCase recorded, final PortOutcome port) {
        if (!recorded.producedDocument() || port.document() == null) {
            return;
        }
        countReconciled(recorded.caseId(), recorded.expected(), port.document());
    }

    /**
     * Walks the two documents together, counting every component a derivation covers and, of those,
     * the ones the two runs actually rendered differently.
     *
     * @param caseId the case being walked, for the report's example
     * @param oracle the node the recording carries
     * @param port   the node the port wrote there, or {@code null} where it wrote nothing
     */
    private static void countReconciled(
            final String caseId, final JsonNode oracle, final JsonNode port) {

        if (oracle.isArray()) {
            for (int index = 0; index < oracle.size(); index++) {
                countReconciled(caseId, oracle.get(index), element(port, index));
            }
        } else if (oracle.isObject()) {
            for (final String name : propertyNames(oracle)) {
                final JsonNode oracleValue = oracle.get(name);
                final JsonNode portValue = port == null ? null : port.get(name);
                count(caseId, name, oracleValue, portValue);
                countReconciled(caseId, oracleValue, portValue);
            }
        }
    }

    /**
     * Counts one component against the derivation registered for it, if any.
     *
     * @param caseId      the case being walked, for the report's example
     * @param name        the property name the component reaches the wire under
     * @param oracleValue what the recording carries there
     * @param portValue   what the port wrote there, or {@code null} where it wrote nothing
     */
    private static void count(final String caseId, final String name,
            final JsonNode oracleValue, final JsonNode portValue) {

        final RegisteredDefectFixes.Fix fix = RegisteredDefectFixes.forProperty(name);
        if (fix == null) {
            return;
        }
        EVALUATED.merge(fix.reference(), 1, Integer::sum);
        if (!oracleValue.equals(portValue)) {
            DIFFERED.merge(fix.reference(), 1, Integer::sum);
            EXAMPLES.putIfAbsent(fix.reference(), caseId + " — the derived component " + name);
        }
    }

    /**
     * An array's element, where there is one to compare against.
     *
     * @param node  the node; may be {@code null}
     * @param index the index
     * @return the element, or {@code null}
     */
    private static JsonNode element(final JsonNode node, final int index) {
        return node == null || !node.isArray() || index >= node.size() ? null : node.get(index);
    }

    /**
     * An object's property names, as a list the walk can iterate twice over.
     *
     * @param node the object
     * @return the names, in the order it holds them
     */
    private static List<String> propertyNames(final JsonNode node) {
        final List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return List.copyOf(names);
    }

    /**
     * Where a divergence was observed, for a failure message.
     *
     * @param divergence the divergence
     * @return the JSON pointer, or a word for the run itself
     */
    private static String where(final Divergence divergence) {
        if (divergence instanceof Divergence.Field field) {
            return "the difference at " + field.path();
        }
        return divergence instanceof Divergence.ReferenceDataDay
                ? "the day the subscription set was read for"
                : "the run's outcome";
    }

    /**
     * A divergence rendered for a reader of a red build.
     *
     * @param divergence the divergence
     * @return both sides of it, in one line
     */
    private static String describe(final Divergence divergence) {
        if (divergence instanceof Divergence.Field field) {
            return "the legacy wrote " + render(field.oracleValue())
                    + " and the port wrote " + render(field.portValue());
        }
        if (divergence instanceof Divergence.ReferenceDataDay day) {
            return "the legacy read the subscriptions in force on " + day.oracleDay()
                    + " and the port reads the set in force on " + day.portDay();
        }
        final RecordedCase recorded = divergence.recorded();
        return "the legacy ended as " + recorded.outcome()
                + (recorded.noDocumentReason().isEmpty()
                        ? "" : " (" + recorded.noDocumentReason() + ")")
                + " and the port " + divergence.port().describe();
    }

    /**
     * A node rendered short enough to read in a failure message.
     *
     * @param node the node; may be {@code null}
     * @return a short rendering
     */
    private static String render(final JsonNode node) {
        if (node == null) {
            return "nothing";
        }
        final String rendered = node.toString();
        return rendered.length() <= 160 ? rendered : rendered.substring(0, 160) + "...";
    }

    /**
     * Reads a file of this repository.
     *
     * @param path the path, relative to the project directory
     * @return its content
     */
    private static String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException cannotRead) {
            throw new UncheckedIOException(cannotRead);
        }
    }

    // --- reading what the recorder wrote ---------------------------------------------------------

    /**
     * The clock the payload goldens were recorded against.
     *
     * <p>Midday rather than midnight, so a mapper reading the date through some other zone still
     * reads the day the ages were counted on. {@code cases[].age} is the one field in the payload
     * that reads a clock at all, which is why the mapper takes one.
     *
     * @return a clock fixed to the recording date in Europe/London
     */
    private static Clock recordingClock() {
        final LocalDate recorded = LocalDate.parse(INDEX.get("ageClockDate").stringValue());
        return Clock.fixed(recorded.atTime(12, 0).atZone(LONDON).toInstant(), LONDON);
    }

    /**
     * Every index entry that names a golden file, across all three sections.
     *
     * @return the entries
     */
    private static List<JsonNode> recordedGoldens() {
        return GOLDEN_SECTIONS.stream().flatMap(section -> named(section).stream()).toList();
    }

    /**
     * Every index entry that names one of the payload generator's goldens.
     *
     * @return the entries: 161 single documents and 7 whole batches
     */
    private static List<JsonNode> payloadGoldens() {
        final List<JsonNode> goldens = new ArrayList<>(named("pdfPayloadDocuments"));
        goldens.addAll(named("pdfPayloadBatches"));
        return List.copyOf(goldens);
    }

    /**
     * Every recorded document the payload generator threw on rather than mapping.
     *
     * @return the entries, which carry a refusal and no golden
     */
    private static List<JsonNode> refusedDocuments() {
        return INDEX.get("pdfPayloadDocuments").valueStream()
                .filter(entry -> entry.get("golden").isNull())
                .toList();
    }

    /**
     * The entries of one index section that name a golden file.
     *
     * @param section the section
     * @return the entries
     */
    private static List<JsonNode> named(final String section) {
        return INDEX.get(section).valueStream()
                .filter(entry -> !entry.get("golden").isNull())
                .toList();
    }

    /**
     * The payload this port maps one recorded input to, where it maps one at all.
     *
     * <p>A refusal is carried into the answer rather than out of the case, for the same reason
     * {@code PdfPayloadMapperTest} carries it: what the assertions need is the news that a payload
     * did not come, and which type carried that news is the mapper's business. Nothing is
     * swallowed - the emptiness <em>is</em> the observation, asserted in both directions by the two
     * cases that read it, one requiring a payload for all 168 goldens and one requiring none for
     * all 52 refusals.
     *
     * @param entry the index entry naming the input
     * @return the payload, or empty where the mapper produced none
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private Optional<JsonNode> mapped(final JsonNode entry) {
        Optional<JsonNode> payload;
        try {
            payload = Optional.of(payloads.mapPayload(inputFor(entry)));
        } catch (RuntimeException refused) {
            payload = Optional.empty();
        }
        return payload;
    }

    /**
     * The input one golden was recorded from, in the shape the generator reads.
     *
     * <p>A document golden was recorded from a single register wrapped in a one-element array; a
     * batch golden from its members' registers in the order the index lists them, which is the
     * order the recorder grouped them in.
     *
     * @param entry the index entry naming the input
     * @return the {@code courtRegisterDocumentRequests} envelope
     */
    private static JsonNode inputFor(final JsonNode entry) {
        final ArrayNode requests = MAPPER.createArrayNode();
        final JsonNode members = entry.get("members");
        if (members == null) {
            requests.add(readResource(classpath(entry.get("source").stringValue())));
        } else {
            members.valueStream()
                    .map(member -> readResource(RECORDED + member.stringValue() + "/expected.json"))
                    .forEach(requests::add);
        }
        final ObjectNode envelope = MAPPER.createObjectNode();
        envelope.set(REQUESTS, requests);
        return envelope;
    }

    /**
     * What this port and one recorded defendant-type golden disagree about, where they do.
     *
     * <p><strong>The empty string and no answer at all are the same answer.</strong> Progression's
     * caller seeds the type with {@code StringUtils.EMPTY} ({@code CourtRegisterHandler:84}) and
     * replaces it only where an application was found, so its recorded answer for a hearing with no
     * application is {@code ""}; this port answers {@link Optional#empty()} and the document leaves
     * the field out. That is one statement in two vocabularies and not a deviation, which is the
     * mapping {@code DefendantTypeResolverTest} states in those words and the only place the two
     * spellings meet - so it is mapped here the same way rather than reported as a difference
     * neither oracle nor register would recognise.
     *
     * @param entry the index entry naming the golden
     * @return the deviation, or {@code null} where the two answers agree
     */
    private GoldenDeviation answeredFor(final JsonNode entry) {
        final JsonNode golden = readResource(GOLDENS + entry.get("golden").stringValue());
        final String recordedAnswer = text(golden.get("defendantType"));
        final String portAnswer = defendantTypes.resolve(
                        readResource(classpath(golden.get("hearingFixture").stringValue()))
                                .get("hearing"),
                        MAPPER.treeToValue(documentIn(golden), CourtRegisterDocument.class))
                .orElse(NO_TYPE);
        return NO_TYPE.equals(recordedAnswer) && NO_TYPE.equals(portAnswer)
                || Objects.equals(recordedAnswer, portAnswer)
                ? null
                : new GoldenDeviation(entry.get("goldenId").stringValue(), recordedAnswer,
                        text(golden.get("threw")), portAnswer);
    }

    /**
     * The register document a defendant-type golden's recorded input carries.
     *
     * <p>A synthesised input carries it under {@code document}; a base fixture's input <em>is</em>
     * the recorded register, because that is what the recorder typed.
     *
     * @param golden the golden
     * @return the document tree
     */
    private static JsonNode documentIn(final JsonNode golden) {
        final JsonNode source =
                readResource(classpath(golden.get("documentSource").stringValue()));
        final JsonNode document = source.get("document");
        return document == null ? source : document;
    }

    /**
     * One recorded field's text, where a golden that recorded a refusal carries nothing under it.
     *
     * @param value the field; may be {@code null}
     * @return its text, or {@code null} where the golden recorded none
     */
    private static String text(final JsonNode value) {
        return value == null || value.isNull() ? null : value.stringValue();
    }

    /**
     * The classpath resource behind one of the repo-relative paths the index names.
     *
     * @param recordedPath the path as the index records it
     * @return the resource path
     */
    private static String classpath(final String recordedPath) {
        if (!recordedPath.startsWith(SOURCE_ROOT)) {
            throw new IllegalStateException("the index names an input outside the test resources: "
                    + recordedPath);
        }
        return recordedPath.substring(SOURCE_ROOT.length());
    }

    /**
     * Reads one JSON file from the test classpath through the service's own contract mapper.
     *
     * @param resource the absolute resource path
     * @return the parsed tree
     */
    private static JsonNode readResource(final String resource) {
        try (InputStream stream = DifferentialAuditTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            return MAPPER.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException cannotRead) {
            throw new UncheckedIOException(cannotRead);
        }
    }

    // --- the digests the recorder left behind ----------------------------------------------------

    /**
     * The digest of one file of this repository, as the recorder wrote it.
     *
     * @param path the path, relative to the project directory
     * @return the lower-case hexadecimal sha256
     */
    private static String digestOf(final Path path) {
        try {
            return hex(Files.readAllBytes(path));
        } catch (IOException cannotRead) {
            throw new UncheckedIOException(cannotRead);
        }
    }

    /**
     * The digest of a manifest, over its UTF-8 bytes.
     *
     * @param manifest the manifest
     * @return the lower-case hexadecimal sha256
     */
    private static String digestOf(final String manifest) {
        return hex(manifest.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The sha256 of some bytes, rendered the way the index renders every digest it holds.
     *
     * @param bytes the bytes
     * @return the lower-case hexadecimal digest
     */
    private static String hex(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(SHA_256).digest(bytes));
        } catch (NoSuchAlgorithmException everyJvmHasIt) {
            throw new IllegalStateException("no " + SHA_256 + " on this JVM", everyJvmHasIt);
        }
    }
}
