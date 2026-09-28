# Data Model: YOT Results Distribution Service

The processed-log design is the informant service's; this file records only what is court-register
specific and cites the rest (IR-REPO `specs/CRA-220-informant-register-initial-poc/data-model.md`
and the "Idempotency" section of the design, now on Confluence). The guard SQL — claim acquisition, read-and-branch,
stale-claim reclaim, outcome writes, FAILED replay, collision check — transfers verbatim with the
table names unchanged.

## Entities

### DistributionCommand (domain record)

Identical shape to the informant command: `source` (enum `RESULTS`), `requestId` (UUID, the
idempotency key), `hearingId` (UUID), `hearingDay` (ISO date), `sharedTime` (ISO instant),
`eventType` (enum `Hearing_Resulted`), optional `userId` (UUID; absent ≠ null). Canonical schema:
`src/main/resources/contracts/distribution-command.schema.json` (court-register `$id`;
`additionalProperties: false`).

### processed_request

**Unchanged from informant V1** — same columns, same CHECKs (status enum RECEIVED/RETRYING/
COMPLETED/FAILED; attempts ≥ 0; claim-triple all-or-nothing; FAILED ⇒ exhausted_message_id), same
composite PK `(source, request_id)`, same `(hearing_id, hearing_day)` index, same database-clock
authority (every timestamp written and compared in SQL). See the informant data-model for the
column table and fingerprint canonicalisation; `RequestFingerprint` covers the same four immutable
fields.

### processed_output — the one structural change

One row per **submitted command** (0..1), not per fan-out unit: the court register makes exactly
one POST per hearing.

| Column | Type | Null | Notes |
|---|---|---|---|
| `output_id` | uuid | NOT NULL | application-generated v4; PK; no DB default |
| `source` | text | NOT NULL | FK half |
| `request_id` | uuid | NOT NULL | FK half |
| `court_centre_id` | uuid | NOT NULL | descriptive, from the fragment |
| `court_centre_ou_code` | text | null | descriptive (`hearing.courtCentre.code`; may be absent upstream) |
| `register_date` | date | NOT NULL | the fixed (C10) register day |
| `file_name` | text | NOT NULL | the C11 unique convention |
| `status` | text | NOT NULL | CHECK `IN ('PENDING','POSTED','FAILED')` |
| `response_code` | integer | null | recorded on the settlement of the POST (C1) |
| `request_digest` | text | null | SHA-256 of exactly the bytes sent; written **before** the POST, kept after failure |
| `anomaly_summary` | text | null | bounded reason-code counts for guarded, non-fatal transformation anomalies (`unresolvable-youth-defendant:1`, `unresolvable-application:1`, `letter-delivery-dropped:2`, `recipient-missing-email:1`, `recipient-not-for-distribution:1`) — the persistence half of C19/C20/C27; codes only, never free text |
| `created_at` / `updated_at` | timestamptz | NOT NULL | DEFAULT now(); updates set `updated_at = now()` in SQL |

Constraints: PK `(output_id)`; **`UNIQUE (source, request_id)`** (`processed_output_unique_request`
— the informant's per-authority unique is replaced; if a fan-out dimension ever appears the
constraint widens without a rewrite); FK `(source, request_id)` → `processed_request`
`ON DELETE RESTRICT`; status CHECK above.

Replay/skip semantics: a replayed request whose output row is already POSTED skips submission and
completes `submitted` (asserted by `SubmissionRedeliveryIT`).

## State machine (processed_request.status)

Transitions identical to the informant table (see its data-model.md). The completion vocabulary is
this flow's:

| completion_reason | Meaning | Origin |
|---|---|---|
| `submitted` | one POST made, 202 received (or already POSTED on replay) | happy path |
| `group-proceedings` | `isGroupProceedings === true` (strict) — the preserved business skip | C7 |
| `no-defendants` | the hearing produced an empty register-defendant list | C6 |
| `no-subscriptions` | reference data answered; nothing matched (per-defendant semantics, C31) | C33 |
| `no-youth-defendants` | subscriptions matched; the youth filter left nobody | C33 |

`failure_reason` stays a bounded code + sanitised summary; new non-transient codes this flow adds:
`OUTBOUND_CONTRACT_VIOLATION` (C29 pre-send validation), `SUBMISSION_NOT_ACCEPTED` (non-202 2xx),
`SUBMISSION_REJECTED` (parking 4xx), `TRANSFORMATION_FAILED` (**fatal** transformation errors only
— e.g. an unparseable ordered date, C13). Guarded C19/C20 skips and C27 drops are deliberately
**not** failures: the run completes, and the anomaly is recorded as a bounded reason-code count in
`processed_output.anomaly_summary` plus the metric
`yotresultsdistribution_transformation_anomalies_total{reason}`.
Transient: `PAYLOAD_UNAVAILABLE` (cache+fallback miss, C32), `REFERENCE_DATA_UNAVAILABLE`,
`SUBMISSION_TRANSIENT`.

## Pipeline entities (in-memory, never persisted)

- **RegisterFragment**: registerDefendants (contexts + 18-key vocabulary), courtCentreId,
  courtCentreOUCode, hearingDate (latest ordered date), registerDate (true instant of
  `sharedTime`), hearingId, matchedSubscriptions.
- **CourtRegisterDocument** (typed records, `@JsonInclude(NON_NULL)`, validated against the
  vendored schemas): registerDate, hearingDate, hearingId, courtCentreId, fileName, hearingVenue
  {courtHouse, ljaName, address}, recipients[] {recipientName, emailAddress1, emailAddress2,
  emailTemplateName}, defendants[] (youth only) {name, dateOfBirth, address, gender, nationality,
  ethnicity, postHearingCustodyStatus, masterDefendantId, parentGuardian, hearing, aliases,
  prosecutionCasesOrApplications, defendantResults, defenceCounsels}. Field-level semantics are
  the mapper tests' domain; the schema is the shape authority.

## Invariants

1. Every request reaching the state machine ends in exactly one terminal state with a bounded
   reason; there is no silent path (C1/C2/C32/C33 — the migration's purpose).
2. At most one pipeline run in flight per (source, request_id); no run after COMPLETED.
3. `processed_output.request_digest` is written before its POST and never erased.
4. No document is POSTed that fails the vendored contract (C29).
5. The three dates never cross: the command's `hearingDay` keys the cache only; the document's
   `hearingDate` derives from ordered dates; `registerDate` derives from `sharedTime`.
6. Vocabulary objects carry exactly the 18 agreed keys.
