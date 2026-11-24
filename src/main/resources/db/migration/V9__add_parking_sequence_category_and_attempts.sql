-- V9 - the parking delivery's identity (spec 007-replay-by-sequence-number).
--
-- Additive and forward-only (FR-014). A FAILED row from before this migration has all three new
-- columns NULL, which both new constraints permit - that is the documented legacy-record state
-- (FR-005, FR-010), not a transitional one to be backfilled.
--
-- Three columns, not two: parking_attempt_count is this repository's own addition beyond the
-- informant-register precedent (research.md R6). It is the attempts value the row held at the
-- moment it was parked, and it is what lets a later decision tell an EXACT row (this version's own
-- parking, still current) from a STALE one (an older consumer re-parked it without knowing these
-- columns exist, so the triple below survives from an earlier parking while attempts has moved on
-- underneath it) - the rolling-deployment edge case FR-005a names.
--
-- Rollout constraint (spec "Edge Cases", Rolling deployment): an older consumer re-parking a row a
-- newer consumer already parked leaves these three columns stale rather than clearing them, which a
-- newer consumer can then misread as a resubmission unless it also checks parking_attempt_count
-- against the row's current attempts (FR-005a) - this is not guarded in code by a constraint, only
-- by that check, because a constraint tying the triple to FAILED would make an older consumer's own
-- (unaware) replay statement fail outright rather than merely leave stale data behind. The
-- deployment for this migration MUST still ensure no consumer older than this version stays able to
-- park or re-park rows on yotresultsdistribution.requests once any pod is running it.

ALTER TABLE processed_request
    ADD COLUMN parking_sequence_number bigint,
    ADD COLUMN parking_category        text,
    ADD COLUMN parking_attempt_count   integer;

ALTER TABLE processed_request
    ADD CONSTRAINT processed_request_parking_category_chk
        CHECK (parking_category IS NULL OR parking_category IN ('EXHAUSTED', 'NON_TRANSIENT')),
    ADD CONSTRAINT processed_request_parking_triple_chk
        CHECK ((parking_sequence_number IS NULL) = (parking_category IS NULL)
           AND (parking_sequence_number IS NULL) = (parking_attempt_count IS NULL));
