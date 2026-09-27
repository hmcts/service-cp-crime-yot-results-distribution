# Specification Quality Checklist: Exception report for production support

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-14
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) - the spec names the platform
      services the report uses (Log Analytics, the notification service, the file service) because
      they are the requirement, not the implementation; no class, library or table is named
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain - two open points (template ownership, file-service
      acceptance) are recorded as assumptions with a stated fallback; the third, the Monday window,
      was settled on 2026-09-14 and is no longer open, the window being derived from the schedule
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Validated 2026-09-14 on the first pass; ready for `/speckit-plan`.
- Re-validated 2026-09-14 after `/speckit-analyze`; the four decisions and the critical relocation
  applied. The decisions: the scheduled window runs from the previous scheduled run rather than a
  fixed 24 hours (so `yotresultsdistribution.report.window` is gone); the intake sweep holds no lock and
  refreshes per JVM; a fifth exception kind, `BATCH_FAILED`, carrying only its bounded reason; and
  the sweep runs in every non-command JVM from its own `IntakeSweepConfig`, with `gauge-refresh`
  moved to `yotresultsdistribution.intake.gauge-refresh`. The critical relocation: `RegisterBatchRepository`
  and `RegisterNotificationRepository` beans move from `GenerationConfig` to `ProcessedLogConfig`,
  without which the report cannot wire on a generation-disabled pod.
- User Story 4 (e-mail) is deliverable only once the notification template exists; the spec says
  so in Assumptions rather than gating the whole increment on it.
- Re-validated 2026-09-14 after the second analysis (Fable + Codex); F01-F07 and R1-R19 applied.
  The substantive ones: the log sink's summary event drops the two delivery statuses it could not
  observe and the job gains its own `exception_report_run` line written after every sink returns;
  all four scheduled methods name their scheduler, the attribute having been verified present on
  the resolved `spring-context-7.0.9.jar`; the report e-mail goes through a new `ReportMailer` port
  and adapter rather than the register's `RegisterNotifier`, with the vendored schema confirmed to
  permit arbitrary `personalisation` keys so the counts still travel in the body; batch,
  notification and recorded-register ages are SQL-computed projections like the request ones;
  `LastScheduledRun` moves to `domain/` and `RunCorrelation` becomes public for the CLI; and the
  missing SC-001, SC-005, SC-006 and SC-008 cases and the US3.4 refusal now have named tests.
