-- V207: diff-on-write results on the WorldTides fetch provenance row (tide-source-integrity-plan P2).
--
-- Each non-SEED, non-POINT_CHANGE forward fetch compares the extremes it is about to replace with the
-- ones WorldTides just returned for the same span, and records the outcome here. All three are NULL when
-- no comparison was made (SEED, POINT_CHANGE, BACKFILL, failures, and rows written before this migration).
--   overlap_pairs         how many stored/new extremes were paired (same type, within 3 hours)
--   max_lag_delta_min     largest timing difference over the pairs, in minutes
--   max_height_delta_m    largest height difference over the pairs, in metres
-- The next fetch also reads these columns to tell a new difference from one already alerted.
ALTER TABLE tide_fetch ADD COLUMN overlap_pairs INTEGER;
ALTER TABLE tide_fetch ADD COLUMN max_lag_delta_min DOUBLE PRECISION;
ALTER TABLE tide_fetch ADD COLUMN max_height_delta_m DOUBLE PRECISION;
