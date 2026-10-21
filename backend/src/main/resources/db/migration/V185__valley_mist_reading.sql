-- V185: valley mist reading (valley-mist-plan M1a) -- the site fact and the recorded score.
--
-- locations.mist_prone is the admin's fact that mist forms AROUND this place (valley floor,
-- riverside, lake shore, a low crag with negligible relief). It is not derivable from stored
-- metadata (elevation_m is null for most of the roster, M0 section 3), so it is a manual flag, the
-- overlooks_water pattern. Existing rows default to FALSE: nothing is flagged until the owner ticks
-- it, so this migration changes no behaviour on its own.
--
-- slot_atmosphere.mist_score / mist_scored are V158's inversion_score / inversion_scored pair for
-- the same reason. mist_score is the deterministic ValleyMistCalculator's 0-10 reading, written at
-- the same collection-time seam as every other reading on the row, so a triaged-out or Gate-4-stood
-- -down candidate is recorded too. mist_scored is TRUE on every write the current writer makes,
-- WITH a score and WITH a null one alike (an unflagged site, or a flagged one the calculator could
-- not score for want of inputs): a fresh null is an AUTHORITATIVE answer, where a row written
-- before this column existed (FALSE by the default) is not. M2 adds Claude's echo as the fallback
-- for the second kind only.
--
-- mist_potential is the band's enum NAME (NONE/POSSIBLE/LIKELY/FOGBOUND), the inversion_potential
-- convention. It must be stored: FOGBOUND is decided by the event-hour visibility, not by the
-- score, so a high-scoring white frame and a LIKELY morning share a score and cannot be told apart
-- from mist_score alone. NULL exactly when mist_score is NULL.
ALTER TABLE locations ADD COLUMN mist_prone BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE slot_atmosphere ADD COLUMN mist_score DOUBLE PRECISION;
ALTER TABLE slot_atmosphere ADD COLUMN mist_scored BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE slot_atmosphere ADD COLUMN mist_potential VARCHAR(10);
