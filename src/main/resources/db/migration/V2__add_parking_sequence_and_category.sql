-- V2 — the parking delivery's identity (spec 001-replay-by-sequence-number).
--
-- Additive and forward-only (FR-014). A FAILED row from before this migration has both columns
-- NULL, which both new constraints permit — that is the documented legacy-record state (FR-005,
-- FR-010), not a transitional one to be backfilled.
--
-- Rollout constraint (spec "Edge Cases", Rolling deployment): an older consumer re-parking a row a
-- newer consumer already parked leaves these two columns stale rather than clearing them, which a
-- newer consumer can then misread as a resubmission. This is not guarded in code — the deployment
-- for this migration MUST ensure no consumer older than this version stays able to re-park rows on
-- informantregister.requests once any pod is running it.

ALTER TABLE processed_request
    ADD COLUMN parking_sequence_number bigint,
    ADD COLUMN parking_category        text;

ALTER TABLE processed_request
    ADD CONSTRAINT processed_request_parking_category_chk
        CHECK (parking_category IS NULL OR parking_category IN ('EXHAUSTED', 'NON_TRANSIENT')),
    ADD CONSTRAINT processed_request_parking_pair_chk
        CHECK ((parking_sequence_number IS NULL) = (parking_category IS NULL));
