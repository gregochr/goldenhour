-- V145: region bases and the shared region->location drive-time matrix (heat-field plan P7).
--
-- Two additions, one purpose: let the Plan tab's origin move off the reader's home and onto a
-- region, so "what is the sky doing near Keswick, and how far is everything from there" is one
-- gesture rather than a different account.
--
-- ---------------------------------------------------------------------------------------------
-- (a) REGION BASE — nullable, because a region without one cannot be an origin
-- ---------------------------------------------------------------------------------------------
-- A base is the town you would actually stay in, not the region's centroid: the centroid of a
-- coastal region is frequently offshore, and every drive time computed from it would be a
-- measurement of a journey nobody can make. So it is admin-entered and NULLABLE, and the UI
-- disables "Plan from here" for a region that has none rather than inventing a point. The three
-- columns move together — `RegionService` rejects a partial base — but the constraint is not
-- expressed in SQL, because the check would have to be `(all three null) OR (all three non-null)`
-- and Flyway's baseline has regions with no base at all.
ALTER TABLE regions ADD COLUMN base_name VARCHAR(120);
ALTER TABLE regions ADD COLUMN base_lat  DOUBLE PRECISION;
ALTER TABLE regions ADD COLUMN base_lon  DOUBLE PRECISION;

-- ---------------------------------------------------------------------------------------------
-- (b) region_drive_time — the SHARED matrix, and why it is not user_drive_time with a null user
-- ---------------------------------------------------------------------------------------------
-- `user_drive_time` answers "how far is this from YOUR house", and everything that reads it lives
-- behind `/api/user/settings/*`, which is deliberately excluded from ETag revalidation because a
-- revalidated body persists to a browser HTTP cache JavaScript cannot evict on logout
-- (`HttpCachingConfig`, `plan-panel-data-contracts.md`). This table answers "how far is this from
-- Keswick", which is the same answer for every reader, so it rides the shared, cacheable payload
-- instead. Widening the per-user table with a nullable user_id would have put one row set on both
-- sides of that seam, where the whole point of the seam is that it is structural.
--
-- Keyed on region_id, NOT on region name. `daily_briefing_cache`, `cached_evaluation` and
-- `briefing_region_snapshot` are all name-keyed, and V137 §7 had to DELETE FROM two of them to
-- rename a region — an ordinary admin action through `RegionService.update`. This is the phase
-- that gives regions a durable identity, so it starts with one.
--
-- ON DELETE CASCADE on both sides: a drive time to a deleted location, or from a deleted region,
-- describes a journey that no longer exists. Unknown is safe here and wrong is not — the same rule
-- `UserDriveTimeWriter.clearForUser` states for a moved house.
CREATE TABLE region_drive_time (
    region_id              BIGINT  NOT NULL REFERENCES regions(id)   ON DELETE CASCADE,
    location_id            BIGINT  NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
    drive_duration_seconds INTEGER NOT NULL,
    PRIMARY KEY (region_id, location_id)
);

-- The serve path reads the WHOLE matrix in one go (`GET /api/regions/drive-times`), so the primary
-- key's leading column already serves every query this table has. No second index: an index nothing
-- reads is write cost and a claim about a read that does not happen.

-- ---------------------------------------------------------------------------------------------
-- (c) The refresh job
-- ---------------------------------------------------------------------------------------------
-- 03:10, twenty minutes after `drive_time_refresh` (V133, 02:40) rather than beside it: both hit
-- the same ORS key through the same two-permit limiter, and staggering them keeps a slow user
-- sweep from queueing every region behind it. Same reasoning as V133's own slot — after the
-- nightly pipeline has settled, on the quietest part of the rate limit.
INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type,
                                  cron_expression, status)
VALUES ('region_drive_time_refresh',
        'Region Drive Time Refresh',
        'Recalculates the shared drive-time matrix from every region base to the whole location '
            || 'roster, so the Plan tab can re-point its drive figures when the origin moves. '
            || 'Skips regions with no base town.',
        'CRON',
        '0 10 3 * * *',
        'ACTIVE');
