-- V157: Repairs a "stamp without rows" state the pre-fix manual refresh could leave behind
-- (fix/drive-time-refresh-skips-unchanged, second review of PR #942). Before that fix,
-- UserSettingsService.refreshDriveTimes stamped app_user.drive_times_calculated_at even when
-- OpenRouteService gave no answer at all — through the now-deleted
-- UserDriveTimeWriter.stampIfHomeUnchanged — so a user could carry a non-null stamp and zero
-- user_drive_time rows (a postcode change cleared both, then a manual refresh with no ORS
-- answer restamped the user without storing anything). DriveTimeRefreshJob.needsRefresh reads
-- that stamp alone as proof the roster was covered, so such a user would be skipped by the
-- nightly job indefinitely — where the pre-fix job, which re-measured everyone every night,
-- would have caught them regardless.
--
-- This cannot recur after this PR: every remaining writer of the stamp
-- (UserDriveTimeWriter.storeIfHomeUnchanged, on both the manual and scheduled routes) only sets
-- it together with at least one stored row — see the writer audit in
-- UserSettingsService.refreshDriveTimes's class/method javadoc and DriveTimeRefreshJob.run.
--
-- Idempotent: a user with rows, or with no stamp at all, satisfies neither the IS NOT NULL nor
-- the NOT EXISTS term and is left untouched by re-running this statement. Clearing the stamp is
-- the correct repair, not a data loss — a cleared stamp is exactly what makes
-- DriveTimeRefreshJob.needsRefresh measure this user again on its very next scheduled run.
UPDATE app_user
SET drive_times_calculated_at = NULL
WHERE drive_times_calculated_at IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM user_drive_time udt WHERE udt.user_id = app_user.id
  );
