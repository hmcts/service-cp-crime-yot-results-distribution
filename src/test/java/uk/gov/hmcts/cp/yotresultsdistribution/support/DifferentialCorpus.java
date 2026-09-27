package uk.gov.hmcts.cp.yotresultsdistribution.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.yotresultsdistribution.config.JacksonConfig;

/**
 * The recorded legacy corpus, read back exactly as it was written.
 *
 * <p>Everything under {@code src/test/resources/differential/recorded/} was produced by T073 running
 * the three real court-register activities of the Node function app at legacy HEAD {@code 0d63f3ae},
 * behind the orchestrator's own group-proceedings guard, with the clock pinned to
 * {@code 2026-08-21T09:15:00.000Z} and {@code TZ=Europe/London}. It is the oracle, and this class
 * only reads it: nothing here regenerates, repairs or normalises a recording, because a golden file
 * adjusted to agree with the port has stopped being evidence.
 *
 * <p><strong>Two contract axes, and they mean different things.</strong> Each case carries an
 * <em>output</em> classification ({@code contractStatus} — what the frozen {@code add-court-register}
 * contract makes of the document the legacy produced) and an <em>input</em> one
 * ({@code inputContractStatus} — whether the producer could have sent the payload at all). The audit
 * reads both: the output axis decides what obligation the case carries, and the input axis is why
 * some of them are robustness evidence rather than parity obligations. Neither is recomputed here.
 *
 * <p><strong>Loaded case by case.</strong> The corpus is twenty megabytes across 351 cases, so the
 * index is read once and a case's own payloads only when that case runs. A suite that held every
 * tree at once would spend more of its runtime in the heap than in the transformation it is testing.
 */
public final class DifferentialCorpus {

    /** Where the recordings live on the test classpath. */
    private static final String ROOT = "/differential/recorded/";

    /** The mapper the port itself reads payloads with, so a case arrives as a fetched one would. */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    private DifferentialCorpus() {
    }

    /**
     * Every case in the corpus, in the order the recorder wrote them.
     *
     * @return the case identifiers
     */
    public static List<String> caseIds() {
        final List<String> ids = new ArrayList<>();
        for (final JsonNode entry : read(ROOT + "index.json")) {
            ids.add(entry.get("caseId").stringValue());
        }
        return List.copyOf(ids);
    }

    /**
     * One recorded case, with its inputs, its outputs and the classifications it was given.
     *
     * @param caseId the case identifier
     * @return the case
     */
    public static RecordedCase load(final String caseId) {
        final JsonNode meta = read(ROOT + caseId + "/meta.json");
        final JsonNode params = meta.get("params");
        final JsonNode contract = meta.get("contract");
        return new RecordedCase(
                caseId,
                contract.get("status").stringValue(),
                meta.get("inputContract").get("status").stringValue(),
                meta.get("observed"),
                meta.get("provenance"),
                params,
                contract.get("violations"),
                meta.path("clockDependent").asBoolean(false),
                read(ROOT + caseId + "/inputs/hearing.json"),
                subscriptionsOf(caseId, params),
                read(ROOT + caseId + "/expected.json"));
    }

    /**
     * Reference data's answer for a case, from wherever that case keeps it.
     *
     * <p>Three shapes, all of them the recorder's: a shared file for the cases that did not change
     * the body, the case's own copy for the cases that did, and nothing at all for the cases whose
     * reference-data call was made to fail.
     *
     * @param caseId the case identifier
     * @param params the recorded parameters
     * @return the answer, or {@code null} where the read never produced one
     */
    private static JsonNode subscriptionsOf(final String caseId, final JsonNode params) {
        final JsonNode answer;
        if (params.path("subscriptionsAbsent").asBoolean(false)) {
            answer = null;
        } else if (params.path("subscriptionsInline").asBoolean(false)) {
            answer = read(ROOT + caseId + "/inputs/subscriptions.json");
        } else {
            answer = read(ROOT + params.get("subscriptionsFixture").stringValue());
        }
        return answer;
    }

    /**
     * Reads one recorded file.
     *
     * @param resource the absolute resource path
     * @return the parsed tree
     */
    private static JsonNode read(final String resource) {
        try (InputStream stream = DifferentialCorpus.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing recorded resource " + resource);
            }
            return MAPPER.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException cannotRead) {
            throw new IllegalStateException("could not read " + resource, cannotRead);
        }
    }

    /**
     * One case of the recorded corpus.
     *
     * @param caseId              the case identifier, which names the directory it was written to
     * @param contractStatus      what the frozen contract makes of the document the legacy produced:
     *                            {@code IN_CONTRACT}, {@code SCHEMA_INVALID} or {@code NO_DOCUMENT}
     * @param inputContractStatus whether the producer could have sent this payload at all
     * @param observed            what the recorder saw the legacy do — the outcome, the counts, the
     *                            swallowed errors and the reference-data calls
     * @param provenance          how the case was derived: its base hearing, its operator, and why
     * @param params              the recorded run parameters, including the shared time
     * @param violations          the frozen contract's refusals of the legacy document, or
     *                            {@code null} where it had none to make
     * @param clockDependent      whether the recording is a function of when it ran, and therefore
     *                            not an oracle at all
     * @param hearing             the hearing the legacy was given
     * @param subscriptions       reference data's answer, or {@code null} where the read failed
     * @param expected            the document the legacy produced, or a JSON null where it produced
     *                            none
     */
    public record RecordedCase(
            String caseId,
            String contractStatus,
            String inputContractStatus,
            JsonNode observed,
            JsonNode provenance,
            JsonNode params,
            JsonNode violations,
            boolean clockDependent,
            JsonNode hearing,
            JsonNode subscriptions,
            JsonNode expected) {

        /** The classification of a legacy document the frozen contract accepts. */
        public static final String IN_CONTRACT = "IN_CONTRACT";

        /** The classification of a legacy document the frozen contract refuses. */
        public static final String SCHEMA_INVALID = "SCHEMA_INVALID";

        /** The classification of a run that produced no document at all. */
        public static final String NO_DOCUMENT = "NO_DOCUMENT";

        /**
         * The claim-check envelope the port is handed, assembled from what the recorder passed the
         * legacy: the hearing, and the shared time the orchestrator gave {@code SetCourtRegister}.
         *
         * <p>A case whose shared time was removed altogether gets an envelope without one, which is
         * exactly what the legacy was given.
         *
         * @return the envelope
         */
        public JsonNode payload() {
            final ObjectNode envelope = MAPPER.createObjectNode();
            envelope.set("hearing", hearing);
            final JsonNode sharedTime = params.get("sharedTime");
            if (sharedTime != null) {
                envelope.set("sharedTime", sharedTime);
            }
            return envelope;
        }

        /**
         * The hearing's own identifier.
         *
         * @return the hearing id
         */
        public UUID hearingId() {
            return UUID.fromString(hearing.get("id").stringValue());
        }

        /**
         * Whether the recorder ran this case with the shared time removed altogether.
         *
         * <p>Which makes the recording a reading of the clock it was made at rather than an oracle:
         * {@code DateService.js:37} hands the missing value to {@code moment-timezone}, which reads
         * {@code undefined} as <em>now</em>, so the register date, the file-name day and the day the
         * subscription set was read for are all whenever the corpus happened to be built. The
         * recorder marks the two cases whose <em>document</em> moved with the clock as
         * {@code clockDependent}; a third produced no document, is unreproducible for exactly the
         * same reason, and carries no such marking — so the audit asks this question instead.
         *
         * <p><strong>An explicit JSON null is a different case and is not this one.</strong>
         * {@code moment} reads {@code null} as an invalid date rather than as now, so those
         * recordings are deterministic failures, are perfectly good oracles, and are held to the row
         * that governs a swallowed one.
         *
         * @return whether the run was given no shared time at all
         */
        public boolean sharedTimeAbsent() {
            return params.get("sharedTime") == null;
        }

        /**
         * Whether reference data ever answered the now-subscriptions read.
         *
         * @return whether the answer is absent
         */
        public boolean subscriptionsNeverAnswered() {
            return subscriptions == null;
        }

        /**
         * Whether the legacy produced a document.
         *
         * @return whether a document was recorded
         */
        public boolean producedDocument() {
            return expected != null && !expected.isNull();
        }

        /**
         * What the recorder saw the run end as.
         *
         * @return the outcome: {@code document}, {@code no-document},
         *     {@code swallowed-exception} or {@code skipped-group-proceedings}
         */
        public String outcome() {
            return observed.get("outcome").stringValue();
        }

        /**
         * Why no document was produced, in the recorder's words.
         *
         * @return the reason, or the empty string where a document was produced
         */
        public String noDocumentReason() {
            final JsonNode reason = observed.get("noDocumentReason");
            return reason == null || reason.isNull() ? "" : reason.stringValue();
        }

        /**
         * The exceptions the legacy caught, logged and discarded.
         *
         * @return the recorded messages, empty where nothing was swallowed
         */
        public List<String> swallowedErrors() {
            final List<String> errors = new ArrayList<>();
            final JsonNode recorded = observed.get("swallowedErrors");
            if (recorded != null && recorded.isArray()) {
                recorded.forEach(error -> errors.add(error.stringValue()));
            }
            return List.copyOf(errors);
        }

        /**
         * How many subscriptions the legacy matched.
         *
         * @return the count, or empty where the run never got that far
         */
        public Optional<Integer> matchedSubscriptions() {
            return countOf("matchedSubscriptionCount");
        }

        /**
         * The calendar day the legacy asked reference data for its subscription set on.
         *
         * <p>The recorder captured the whole {@code now-subscriptions} GET, query string included,
         * so the day is evidence rather than a re-derivation: it is what
         * {@code ReferenceDataService.js:38} put on the wire. It is the one externally-visible
         * effect of C10's relabelling that never reaches the document — a register addressed by the
         * wrong day's subscription set looks perfectly ordinary — which is why C12 has its own row
         * and why this field has to be read rather than inferred from the output.
         *
         * @return the day, or empty where the run never reached the read
         */
        public Optional<String> referenceDataDay() {
            final JsonNode calls = observed.get("refdataCalls");
            final JsonNode day = calls == null || !calls.isArray() || calls.isEmpty()
                    ? null
                    : calls.get(0).get("on");
            return day == null || !day.isString() ? Optional.empty() : Optional.of(day.stringValue());
        }

        /**
         * The shared time the recorder gave the run, where it gave one.
         *
         * @return the shared time, or empty
         */
        public Optional<String> sharedTime() {
            final JsonNode shared = params.get("sharedTime");
            return shared == null || !shared.isString()
                    ? Optional.empty()
                    : Optional.of(shared.stringValue());
        }

        /**
         * The court-house OU code the subscription match is decided on.
         *
         * @return the code, or {@code null} where the hearing carries no court centre code
         */
        public String ouCode() {
            final JsonNode centre = hearing.get("courtCentre");
            final JsonNode code = centre == null ? null : centre.get("code");
            return code == null || !code.isString() ? null : code.stringValue();
        }

        /**
         * The court-register subscriptions reference data answered with.
         *
         * <p>The list the legacy's own upstream filter keeps
         * ({@code CourtRegisterSubscriptions/index.js:33}), so a claim about which match arm a
         * subscription took is asked of exactly the entries that reached the matcher.
         *
         * @return the entries, empty where the read never answered or carried none
         */
        public List<JsonNode> courtRegisterSubscriptions() {
            final JsonNode entries = subscriptions == null
                    ? null
                    : subscriptions.get("nowSubscriptions");
            final List<JsonNode> courtRegister = new ArrayList<>();
            if (entries != null && entries.isArray()) {
                entries.forEach(entry -> {
                    final JsonNode flag = entry.get("isCourtRegisterSubscription");
                    if (flag != null && flag.isBoolean() && flag.booleanValue()) {
                        courtRegister.add(entry);
                    }
                });
            }
            return List.copyOf(courtRegister);
        }

        /**
         * How many defendants the legacy's register fragment gathered.
         *
         * @return the count, or empty where the run never got that far
         */
        public Optional<Integer> registerDefendants() {
            return countOf("registerDefendantCount");
        }

        /**
         * The JSON pointers the frozen contract refused the legacy document at.
         *
         * @return the pointers, empty where the contract refused nothing
         */
        public List<String> violationPointers() {
            final List<String> pointers = new ArrayList<>();
            if (violations != null && violations.isArray()) {
                violations.forEach(violation ->
                        pointers.add(violation.get("pointer").stringValue()));
            }
            return List.copyOf(pointers);
        }

        /**
         * One of the recorder's counts.
         *
         * @param field the field to read
         * @return the count, or empty where it was not recorded
         */
        private Optional<Integer> countOf(final String field) {
            final JsonNode count = observed.get(field);
            return count == null || !count.isNumber()
                    ? Optional.empty()
                    : Optional.of(count.intValue());
        }
    }
}
