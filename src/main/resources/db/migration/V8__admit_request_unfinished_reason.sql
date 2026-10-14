-- V8 - the failure vocabulary admits the requesting leg's own verdict when its own call raised
-- something no classified failure names (review of #31, fix 3, part 2).
--
-- RegisterGenerationJob.requested() used to leave such a batch PENDING for a later run's
-- stale-batch pass to find and release once it had been in flight long enough. It now fails the
-- batch REQUEST_UNFINISHED and releases its registers at the same moment, which the column's
-- constraint enumerated seven values and refused.
--
-- This migration WIDENS ONLY, exactly as V6 did for NOT_COMPLETED_BY_NEXT_RUN: a constraint that
-- only widens admits every row the old one did, so this applies to any store in any state and
-- refuses to apply to none of them - there is no volume to clean before it.
--
-- The two attribution constraints are deliberately NOT touched. The new reason is not
-- generator-attributed, so an attributed row under it lands in
-- register_batch_completed_by_shape_chk's third arm as `false = true` - the reason is not in that
-- arm's attributed list, and an attribution is nevertheless present - and is refused with no edit
-- at all; SchemaMigrationV2IT asserts that refusal rather than reasoning about it, because "no edit
-- was needed" and "no edit was made" are the same diff. Additive and forward-only: V2, V6 and V7
-- are not edited, and no column, table or index is added.

ALTER TABLE register_batch DROP CONSTRAINT register_batch_failure_reason_chk;
ALTER TABLE register_batch ADD CONSTRAINT register_batch_failure_reason_chk
    CHECK (failure_reason IS NULL
        OR failure_reason IN ('PAYLOAD_STORE_UNAVAILABLE', 'RENDER_REQUEST_FAILED',
                              'RENDER_REQUEST_REJECTED', 'GENERATION_FAILED', 'ASSEMBLY_FAILED',
                              'NOT_COMPLETED_BY_NEXT_RUN', 'REQUEST_UNFINISHED'));
