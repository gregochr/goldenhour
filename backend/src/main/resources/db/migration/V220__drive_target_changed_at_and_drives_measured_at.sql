-- V220: tell an unroutable place apart from one that is merely not measured yet (reach-scoped-card-plan R1).
--
-- (a) locations.drive_target_changed_at: when the point OpenRouteService routes to last changed. It starts as
--     created_at, and the application bumps it when a coordinate moves (LocationService.update), together
--     with deleting that location's user_drive_time and region_drive_time rows so the drive reads PENDING
--     until it is re-measured. A raw-SQL seed inserted after this migration gets NOW() from the default.
--     DriveTimeRefreshJob.rosterGrewSince reads the max of this column, so a moved location re-measures
--     every home overnight with no other change.
ALTER TABLE locations ADD COLUMN drive_target_changed_at TIMESTAMP;
UPDATE locations SET drive_target_changed_at = created_at;
ALTER TABLE locations ALTER COLUMN drive_target_changed_at SET NOT NULL;
ALTER TABLE locations ALTER COLUMN drive_target_changed_at SET DEFAULT NOW();

-- (b) regions.drives_measured_at: the instant a region base's drive times were last fully measured, the
--     region counterpart of app_user.drive_times_calculated_at. Nullable: NULL means "not fully measured
--     from this base", so every drive from it reads PENDING (kept in, never dropped) until the nightly
--     region job stamps it. Existing rows stay NULL for that reason.
ALTER TABLE regions ADD COLUMN drives_measured_at TIMESTAMP WITH TIME ZONE;
