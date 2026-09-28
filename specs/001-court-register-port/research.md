# Research: YOT Results Distribution Service — full pipeline port, fix-first

Decisions taken before planning, with rationale and alternatives. Numbering continues the habit of
the informant research file; where a decision is inherited unchanged from that service it is cited,
not restated.

## 1. Transport, settlement, idempotency, health — inherited

**Decision**: clone the informant service's delivery machinery unchanged: dedicated ASB queue
`yotresultsdistribution.requests` + DLQ, `ServiceBusProcessorClient` peek-lock with explicit settlement,
durable `(source, request_id)` processed-log with fingerprint + single-runner claim, deferred
Flyway, store-outage intake suspension, store-gates-readiness/queue-never health policy.

**Rationale**: proven in the informant build and by its SIT→STE-42 replays; the court register's
delivery problem is identical. **Alternatives**: re-derive per flow — rejected; divergence between
sibling services is cost with no benefit.

## 2. Fix-first, not parity-first (the commissioning decision)

**Decision**: of the 34 catalogued defects (design register C1–C34), the 31 that live in this
service are fixed outright in this increment; C18/C28/C34 are externally-owned remediations
registered as PENDING with an owner and a trigger, tracked to conclusion before cutover.
`doc/DEFECT-FIXES.md` is the audit ledger; content-changing fixes are flagged
`sign-off pending` gating cutover. User instruction, 2026-08-31: *"fix all 34 defects and
document them in a separate file in git repo. reference them from readme.md."*

**Rationale**: the informant port chose bug-for-bug parity to make cutover a pure infrastructure
change; the court register's catalogue includes live data-loss (C29) and always-wrong output
(C9), and the commissioning decision is that carrying those forward has no value. **Alternatives**:
parity-then-fix (two passes over every defect — double work); per-fix feature toggles (config
explosion for 34 flags; rejected by the user in favour of sign-off gating cutover).

## 3. What "legacy is still the oracle" means under fix-first

**Decision**: for every behaviour *not* catalogued, the legacy JS remains authoritative; the
differential audit (Phase 8) replays a recorded legacy corpus through the Java pipeline and
requires every difference to map to a C-number. A difference with no C-number is a port defect.

**Rationale**: fix-first without a differential fence invites accidental, silent behaviour change —
the exact disease the informant parity harness existed to prevent. **Alternatives**: rely on twins
alone (misses interactions between components); full golden parity (contradicts fix-first).

## 4. processed_output key — no fan-out

**Decision**: `UNIQUE (source, request_id)`; `court_centre_id`, `court_centre_ou_code`,
`register_date`, `file_name` carried as descriptive columns; status PENDING/POSTED/FAILED and
`request_digest` semantics unchanged from informant.

**Rationale**: `SetCourtRegister` builds exactly one fragment and `ProcessOutboundCourtRegister`
makes exactly one POST — the court centre is a property of the single output, not a fan-out unit.
Keying on it would misstate cardinality. If a fan-out dimension ever appears the unique constraint
widens without a rewrite. **Alternatives**: drop the table (loses the what-was-attempted digest
evidence); key on court centre (misleading).

## 5. 202-only success

**Decision**: `202 Accepted` is the only success answer from
`POST /progression-command-api/command/api/rest/progression/court-register`; any other 2xx is
non-transient `SUBMISSION_NOT_ACCEPTED`.

**Rationale**: the RAML types the endpoint as 202; another 2xx means something other than the
command endpoint answered (informant precedent, same reasoning). **Alternatives**: accept any 2xx —
hides mesh/gateway misrouting.

## 6. Submission retry policy (C1/C3)

**Decision**: retry connect/IO/5xx/429/408 with exponential backoff (`initial-backoff` 500ms,
`max-backoff` 20s, `max-attempts` 4), honour `Retry-After` in **delta-seconds only, bounded by
max-backoff**; an HTTP-date `Retry-After` is classified as present-but-unusable and never parsed
(a server clock minutes ahead would park the run past its claim lease). Other 4xx and non-202 2xx
park immediately. Exhaustion on the final permitted delivery records FAILED +
`exhausted_message_id`.

**Rationale**: replaces the legacy wrapper that treated **every status ≤ 429 as non-retryable**
and never retried the final POST at all. The Retry-After bound is informant deviation #8's
reasoning, adopted as a fix here. **Alternatives**: parse HTTP-dates with a clock-skew cap — more
code for a case the estate does not emit.

## 7. C11 — the fixed filename convention

**Decision**: `court-register_<yyyy-MM-dd of registerDate>_<courtCentreCode>_<hearingId>.pdf`,
e.g. `court-register_2020-06-01_B01LY_1828f356-f746-4f2d-932b-79ef2df95c80.pdf`.

**Rationale**: the legacy embeds the full datetime with colons (invalid on Windows filesystems) and
collides when two hearings share a centre and a second. The date part matches the informant CSV
convention (bare date); the hearingId suffix guarantees uniqueness and is the support-facing
correlation key. Progression uses fileName only as File Service metadata, so the change is
low-blast-radius — still sign-off flagged because the name is externally visible. **Alternatives**:
sanitise colons only (still collides); a UUID-only name (loses at-a-glance centre/date).

## 8. C23 — what verdictCode carries

**Decision**: `offence.verdictCode = verdict.verdictType.verdictCode ?? verdict.verdictType.categoryType`
— the actual code field the payload already carries (fixtures show `verdictType: {verdictCode:
"1234", description: "desc1234", category: "Guilty", …}`), falling back to `categoryType` because
live hearing payloads have been observed carrying only `category`/`categoryType`/`id`; **never the
prose description**. The missing-`verdictCode` fallback has its own pinning case
(`OffenceMapperTest.verdict_code_falls_back_to_category_type_when_absent`).

**Rationale**: the legacy ships the *description* in a field named `verdictCode`; the source object
carries a real code alongside it, so the fix is a field-selection correction, not an invention.
The PDF currently renders the description; after the fix it renders the code — that is a visible
content change and the row is sign-off flagged, with "revert to description under a renamed
presentation rule" recorded as the fallback if the business prefers prose. **Alternatives**: keep
the description (perpetuates the misnomer); send both (schema is closed — `additionalProperties:
false`).

## 9. C31 — per-defendant subscription matching

**Decision**: evaluate subscription vocabulary predicates against **each** register defendant's
vocabulary; the subscription matches if any defendant satisfies it. `judicialResults` remain
collected across all defendants (unchanged).

**Rationale**: the legacy matches on `registerDefendants[0]` only, so an adult-first hearing runs
youth-register matching on adult vocabulary — the register's whole business rule (youth defendants)
is invisible to matching whenever a youth is not listed first. Any-defendant semantics is the
minimal fix that makes matching order-independent. **Alternatives**: aggregate vocabulary union
(changes predicate meaning for negative flags, e.g. `allNonCustodialResults` — union semantics
would be wrong); first-youth-defendant (still order-dependent among youths).

## 10. C29 — pre-send outbound contract validation

**Decision**: validate every mapped document against the vendored `courtRegisterDocument/*` schemas
(v17.103.13, `additionalProperties: false`) before the POST; a violation is a non-transient FAILED
with a bounded reason naming the first violating path. The schemas are vendored once from the
`cpp-platform-core-domain` clone at tag `v17.103.13` (also present as
`criminal-court-public-model-17.103.13.jar` under progression's `target/dependency/`) and pinned;
the version is re-checked against progression's `pom.xml` `coredomain.version` at cutover.

**Rationale**: the legacy's most damaging behaviour: an address-less youth defendant → 400 →
swallowed → the whole hearing's register silently lost. Validating before send converts data loss
into an attributable, replayable failure. **Alternatives**: rely on Progression's 400 (couples the
failure signal to a network round-trip and to another team's error text); relax the outbound model
(forbidden — the contract is Progression's).

## 11. Group proceedings typing (C7)

**Decision**: the skip is preserved as a business rule and recorded as
`completion_reason = group-proceedings`. Interpretation is strict: JSON boolean `true` skips;
`false`/`null`/absent proceed; any non-boolean value proceeds **with a WARN and a metric** naming
the type seen.

**Rationale**: the legacy `==` made the *string* `"false"` suppress a register — data-typing
noise deciding whether a court document exists. Strict interpretation with loud telemetry is the
smallest fix that cannot silently drop a register; the producer schema types the field boolean, so
non-boolean values are producer drift we want to see. Sign-off flagged (content-affecting in the
drift case). **Alternatives**: port the loose `==` (perpetuates C7); treat non-boolean as a
failure (parks hearings for a field the register may not even need — too blunt).

## 12. Test-twin strategy under fix-first

**Decision**: every legacy Jest case is classified (twin-as-is / twin-repointed-at-fix / repaired /
not-applicable) per the recorded test-twin map (session working notes; its content is folded into
tasks.md and the matrix). Twins that pinned defective behaviour assert the fixed behaviour and are
cited by the DEFECT-FIXES row; vacuous or stale legacy assertions are repaired, never inherited
(the register's fixtures include a vocabulary block wrong in 7 of 18 keys — reproduced fixtures
would fail production matching invisibly).

**Rationale**: the Jest suite is simultaneously the best available behaviour spec and demonstrably
untrustworthy in places (assertions on `undefined === undefined`; a suite file Jest never runs).
Classification keeps what is load-bearing and repairs what is not. **Alternatives**: twin
everything blindly (imports the vacuities); ignore the Jest suite (loses 28 genuinely pinning
cases).

## 13. Differential-audit oracle

**Decision**: record the legacy corpus by **executing the Node code** (the informant parity-pack
method: hermetic CLI over the three court-register activities, clock + `TZ=Europe/London` pinned,
provenance recorded), seeded from six authored base hearings plus the transferable mutation
operators; compare through the vendored comparator (absent ≠ null ≠ empty, array-order-sensitive)
with a `RegisteredDefectFixes` map — the fix-first analogue of the informant
`RegisteredFieldDeviations` — deriving each expected difference from its C-number.

**Rationale**: re-implementing legacy behaviour as the oracle would test the port against itself.
The corpus is smaller than the informant's 384 because twins carry more of the load here; the
audit's job is interaction effects and uncatalogued drift. **Alternatives**: skip the audit
(no fence against uncatalogued change); full 384-case parity corpus (cost without benefit —
most cases exercise informant-only shapes).

## 14. Vocabulary fixture rebuild

**Decision**: all Java fixtures carry the full 18-key vocabulary at the real capitalisation
(`atleastOneCustodialResult`, lower-case `l`); `VocabularyBuilderTest` asserts the exact key set.

**Rationale**: seven legacy fixtures carry a 7-key set with two keys mis-capitalised; the mismatch
is invisible in Jest because the matcher is mocked there, and it would fail production matching if
reproduced. **Alternatives**: none defensible.

## 15. Inbound schema identity

**Decision**: own inbound schema at `src/main/resources/contracts/distribution-command.schema.json`
with a court-register `$id`; six required fields identical to the informant contract; `eventType`
enum `["Hearing_Resulted"]` only (no SJP variant — the court register has no SJP leg); no shared
cross-flow schema and **no added `flow` field** (adding one would break the informant contract's
closedness; the queue already identifies the flow).

**Rationale**: design risk register — one schema per queue with its own `$id`. **Alternatives**:
shared `distribution-command` schema with a `flow` member — a breaking change to a sibling's
frozen contract for zero information gain.

## 16. C35 — the hearing date's two wall-clock legs

**Decision**: `getHearingDate`'s two `moment.tz(undefined, zone)` legs are replaced by deterministic
answers and registered as **C35** in `doc/DEFECT-FIXES.md`: a hearing with no ordered date carries no
`hearingDate`, and a sitting record naming no `sittingDay` matches nothing so the ordered date is
carried. In the opposite direction, the two shapes the legacy's own dereference throws on — a truthy
`hearingDays` that is not an array, and a `null` sitting record — are classified transformation
failures rather than iterated safely.

**Rationale**: the transformation is pure and has no clock (Principle V), so neither wall-clock leg
is portable at all; leaving them undocumented would have been an uncatalogued deviation, which
Principle I forbids as firmly as it forbids reproducing a catalogued defect. The reach half is the
mirror image: answering "no sitting days" where the legacy throws would emit a register the legacy
loses, and a port must never differ in that direction without a C-number.
**Alternatives**: inject a clock into the transformation to reproduce the legacy answer — rejected,
it makes the whole chain untestable against fixtures and produces a register that differs on every
replay; classify both legs as transformation failures — rejected, the legacy produces a register for
both and refusing them would lose registers it sends.

## 17. CI alignment with the sibling service — what was adopted and what was not

**Decision**: adopt from `service-cp-crime-hearing-results-validator` only the parts of its CI and
quality configuration that are gates this repo lacked, and record the rest as deliberate
non-adoptions rather than leaving them as unexplained divergence.

**Adopted**: PMD pinned (`toolVersion = "7.22.0"`); `pmdMain` loses its `onlyIf` and joins `check`;
`pmdTest` enabled against a copied `.github/pmd-test-ruleset.xml`; `checkstyleTest` enabled with
`config/checkstyle/checkstyle-suppressions.xml` wired in through `configProperties`;
`jacocoTestCoverageVerification` ordered after `jacocoTestReport`, with the report uploaded; and a
pull-request coverage ratchet guard in the Test job. The two rationale comments the test ruleset
carries about test naming were checked against this repo rather than copied on trust: the
convention is documented here in `.claude/rules/technical-rules.md`, not `CLAUDE.md`, and the
`LinguisticNaming` exclusion was **dropped** — this suite's names are underscore-separated, which
that rule does not mistake for a getter, and it reports nothing here.

**Deliberately not adopted**:

1. **`renovate.json`** — the sibling has none either (verified: no renovate configuration exists in
   `service-cp-crime-hearing-results-validator`). `.github/dependabot.yml` is the estate line for
   this repo and stays.
2. **The API-Test job and `validate-api-spec-version`** — both are about an API-spec artefact.
   This service publishes none (Principle III: actuator only, no OpenAPI file), so the job
   would have nothing to validate.
3. **Entity and persistence coverage exclusions** — the sibling excludes those packages from the
   JaCoCo gate. Here that would be a loosening wearing a configuration's clothes: the persistence
   layer is where the idempotency guard and the processed log live, which is exactly the logic the
   gate exists to hold. The only exclusions stay the application entry point and `config/**`.
4. **Drools excludes** — no rules engine in this service; nothing to exclude.
5. **Version-catalog migration (`gradle/libs.versions.toml`)** — `build.gradle` carries a standing
   instruction that every dependency is declared there so dependabot can track it, dependabot not
   following `apply from:` files. Moving versions into a catalogue would silently end those
   updates. Revisit only if dependabot gains catalogue support.

**Rationale**: the two repos are read by the same people, so divergence should be a decision on the
record rather than an accident. Naming the five non-adoptions is what stops the next alignment pass
re-proposing them, and stops the third one being adopted by momentum. **Alternatives**: adopt the
sibling's configuration wholesale (imports a coverage loosening and two jobs with nothing to do);
leave the repos to drift (the next reader cannot tell a decision from an omission).
