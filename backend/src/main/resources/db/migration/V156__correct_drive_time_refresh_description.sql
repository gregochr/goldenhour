-- V156: Corrects the drive_time_refresh scheduler description, left stale by the skip-logic
-- change on fix/drive-time-refresh-skips-unchanged. V133 seeded "Recalculates every user's
-- per-location drive times ... Skips users with no home location.", true at the time it was
-- written; a scheduled fire no longer measures everyone every night (see
-- DriveTimeRefreshJob.needsRefresh / rosterGrewSince) — it skips a user whose postcode is
-- unchanged and whose stamp already covers the newest location, and only the admin's "Run now"
-- trigger still measures every enabled user with a home unconditionally.
--
-- A plain UPDATE, not a DELETE+INSERT: matching zero rows (a database older than V133, or one
-- where an admin has already hand-edited the row) is a safe no-op, and it touches no column but
-- description — the job's schedule, status and every other field are left exactly as they are.

UPDATE scheduler_job_config
SET description = 'Recalculates a user''s per-location drive times when their home postcode has '
    || 'changed, when they have never been measured, or when a location has been added since '
    || 'their last refresh. Skips everyone else, and skips users with no home location. Run now '
    || 'always re-measures every user.'
WHERE job_key = 'drive_time_refresh';
