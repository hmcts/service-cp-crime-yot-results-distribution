# Contracts - increment 004

**Nothing is vendored here, nothing is added, and no contract changes.** This increment adds no
contract of its own and changes none of the platform contracts this service adapts to (constitution
Principle III). It has no inbound message, no REST surface and no event of its own.

What it does is **stop calling one endpoint of a contract it does not own**.

| Contract | Owner | Before 004 | After 004 |
|---|---|---|---|
| systemdocgenerator `generate-document` (REST command, 202) | systemdocgenerator | Called once per batch by the requesting leg | **Unchanged.** Same path, same media type, same 202-and-nothing-else rule |
| systemdocgenerator `document-available` / `generation-failed` (public events on the Artemis `public.event` topic) | systemdocgenerator | The primary way an outcome is learned | **Unchanged, and now the only way.** Same envelope, same `originatingSource` filter, same shared durable subscription, same acknowledge-and-drop rule |
| systemdocgenerator `GET document/{payloadFileId}` (the query API) | systemdocgenerator | Called by the grace-period reconciler, once per overdue batch | **No longer called at all.** The client method, its answer parsing and the `DocumentStatus` record that modelled its body are deleted |
| notificationnotify `send-email-notification` (REST command, 202) | notificationnotify | | **Unchanged** |
| The framework file service `metadata` + `content` tables (write-only, changesets 001-006) | the framework | | **Unchanged** |
| The `YotResultsDistributionService` App Configuration flag | the cutover | Read once per run by the nightly job | **Unchanged.** The release pass is inside the run, behind the same single read, and is not a second reader |
| The inbound `yotresultsdistribution.requests` message (`distribution-command.schema.json`) | this service, with `cpp-context-results` | | **Unchanged** |
| The register document (`courtRegisterDocument/*`, `criminal-court-public-model` 17.103.13) | this service | | **Unchanged** |

**Ceasing to call an endpoint is not a contract change.** Nobody else's shape moves, nothing this
service publishes moves, and systemdocgenerator is not asked to do or stop doing anything: it simply
receives fewer reads. No cross-team event, no agreement to seek, no vendored artefact to re-point.
The reverse would be true — starting to call something, or widening a body — and is not what this
increment does.

The vendored artefacts stay exactly where 002 put them and are not copied here:
`specs/002-consolidate-progression-leg/contracts/`, whose README remains the authority for
provenance, media types, ACLs and verification history for all of them.

**One local artefact changes and it is not a contract**: the WireMock mapping for
`GET document/{id}` in `docker/wiremock/` is deleted, because a stub for a call nothing makes would
let a regression pass unnoticed. `docker/wiremock/README.md` loses its line about it.
`docker/sdg-echo/sdg-echo.py` is **not** affected: it watches WireMock's request journal and
publishes `document-available` onto the topic, and implements no query endpoint. (Checked, because
one design review listed it.)
