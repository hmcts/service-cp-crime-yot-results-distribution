# WireMock stubs for the local generation loop

LOCAL ONLY. These mappings stand in for the three HTTP dependencies the generation half of the
service talks to, so `docker compose up` and `scripts/container-smoke.sh` can run with
`YOTRESULTSDISTRIBUTION_GENERATION_ENABLED=true` without systemdocgenerator, notificationnotify or an Azure
App Configuration store. They are stubs, never a specification: the contracts in
`specs/002-consolidate-progression-leg/contracts/` are the authority, and each mapping below cites
the one it mirrors.

The whole directory is mounted at `/home/wiremock`, so WireMock loads `mappings/` on start. The
container is reachable on `http://localhost:8089` from the host and `http://wiremock:8080` from
inside the compose network.

| Mapping | Stands for |
| --- | --- |
| `systemdocgenerator-generate-document.json` | `POST /systemdocgenerator-command-api/command/api/rest/systemdocgenerator/generate-document`, `Content-Type: application/vnd.systemdocgenerator.generate-document+json` → **202** with no body, exactly as the real command API answers. The body patterns require the three fields `systemdocgenerator.generate-document.json` marks required, so a malformed request 404s here rather than being silently accepted. |
| `notificationnotify-send-email-notification.json` | `POST /notificationnotify-command-api/command/api/rest/notificationnotify/notifications/{notificationId}` (the id matched as a UUID in the path), `Content-Type: application/vnd.notificationnotify.email+json` → **202**, with the two fields `notificationnotify.email.json` marks required (`templateId`, `sendToAddress`). The media type is what maps the request to the `notificationnotify.send-email-notification` command, and the body carries no `notificationId`: the id is the path parameter. |
| `appconfiguration-flag-on.json` / `appconfiguration-flag-off.json` | The App Configuration data-plane read of `.appconfig.featureflag/YotResultsDistributionService`: `application/vnd.microsoft.appconfig.kv+json` whose `value` is the feature-flag JSON of `contracts/appconfiguration/feature-flag-value.schema.json`. ON is the default. |
| `flag-switch-off.json` / `flag-switch-on.json` | The flip, as a scenario transition. |

## Flipping the flag

The flag is a WireMock **scenario** named `YotResultsDistributionServiceFlag`: state `Started` serves
`enabled: true`, state `off` serves `enabled: false`. Either flip it with the shorthand stubs

```bash
curl -X PUT http://localhost:8089/flag/off
curl -X PUT http://localhost:8089/flag/on
```

or with WireMock's own scenario admin API, which does the same thing:

```bash
curl -X PUT http://localhost:8089/__admin/scenarios/YotResultsDistributionServiceFlag/state \
     -d '{"state":"off"}'
curl -X PUT http://localhost:8089/__admin/scenarios/YotResultsDistributionServiceFlag/state \
     -d '{"state":"Started"}'
curl -s http://localhost:8089/__admin/scenarios          # which state it is in now
```

The shorthand lives at `/flag/off` and NOT at `/__admin/flag/off`, which is what the plan sketched:
WireMock reserves the whole `/__admin` prefix for its own admin API and never consults the stub
mappings there, so a mapping registered at `/__admin/flag/off` is unreachable - verified against
`wiremock/wiremock:3.13.2`, which answers such a request `404` from the admin router.

## Adding a mapping

One stub per file, named after what it stands for, with the contract it mirrors cited in the table
above. Restarting the container reloads them; `curl -X POST http://localhost:8089/__admin/mappings/reset`
reloads them without a restart, and also returns the flag scenario to its default ON state.
