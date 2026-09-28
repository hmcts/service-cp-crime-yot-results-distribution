# Pull request body: consolidate the progression court-register leg

Ready to paste into the pull request for `002-consolidate-progression-leg` when it is raised, under
the repository's own template. It exists as a file because one section of it is required rather than
optional: the constitution obliges a recorded deviation to be justified in the pull request
description as well as in the plan, and a description composed at the moment of raising would be
the one place that obligation could quietly go unmet.

### JIRA link (if applicable)

None. The increment is tracked by `specs/002-consolidate-progression-leg/` and the design page.

### Change description

Progression's court-register leg is consolidated into this service. The service already recorded
what it assembled; it now also batches at 18:00 Europe/London, writes the payload to the platform
file service, asks systemdocgenerator to render the unchanged `OEE_Layout5` template, learns the
outcome from the platform's public events with a grace-period reconciler behind them, and sends one
notificationnotify e-mail per Youth Offending Team with the PDF attached. Every command and every
batch has a recorded terminal state.

The whole flow is switched between the legacy implementation and this service by one Azure App
Configuration feature flag, `YotResultsDistributionService`, read by the results producer, by the legacy
function-app triggers and by this service's nightly job. Every failure to read it leaves the legacy
in charge.

Operations are a CLI baked into the image and run with `kubectl exec`, not an API: `generate-
register`, `notify-register`, `list-batches`, `supersede-before` and `check-flag`. The service still
exposes no REST surface beyond Actuator.

Ten progression defects are catalogued in `doc/DEFECT-FIXES.md` with the test that pins each: seven
fixed, two retired with the leg, one moot. Three carry a sign-off marker because they change what a
recipient receives, and those are gated on business sign-off before cutover.

### Recorded deviation from the constitution

**One commit on this branch does not carry an accepted Conventional Commit type**: `41009d0`,
`Merge remote-tracking branch 'origin/main' into 002-consolidate-progression-leg`.

*Why it was needed.* A push of this branch's work to `main` was rejected because `main` had moved
fourteen commits ahead: Spring Boot 4.1.0 to 4.1.1, ShedLock 6.6.0 to 7.9.0, the Gradle wrapper,
several CI workflow changes, a PMD classpath fix and the removal of the imported ruleset files. The
branch was already published, so its history is append-only and the divergence had to be resolved on
the branch itself.

*Why the simpler alternatives were rejected.* Rebasing would have rewritten published history, which
the increment's own preamble forbids outright. Cherry-picking main's fourteen commits would have
duplicated every one of them and left the branch's merge base wrong. Composing a conventional
subject for the merge by hand would have described a change nobody wrote.

*Approval.* **Design owner, 2026-09-09**, as a deviation recorded in
`specs/002-consolidate-progression-leg/plan.md` under Complexity Tracking. The constitution is
deliberately **not** amended, so merge commits are not exempt in general and the next one on a
branch of this repository needs its own entry.

**Does this PR introduce a breaking change?**

```
[ ] Yes
[x] No
```

Nothing consumes this service. The register document written to the store is the schema progression
already receives, and the POST to progression is retained behind `yotresultsdistribution.output=progression-
post` for the fallback sequencing the design describes.
