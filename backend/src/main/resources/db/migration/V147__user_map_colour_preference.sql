-- V147: per-user map colour preferences (heat-scale-unification-plan.md, Stage 6).
--
-- Two independent choices, both on app_user like every other per-user setting (V67 home
-- location, V136 local_radius_miles) — there is no user_settings table.
--
-- map_colour_scale: which ramp paints the heat field, markers and map legend — 'temp' or
-- 'verdict'. Nullable with no backfill, deliberately, matching V136's reasoning exactly: NULL
-- means "never chosen", which lets a later stage flip the DEFAULT applied to callers who never
-- chose without overriding anyone who explicitly picked one. A DEFAULT clause here would erase
-- that distinction on every existing row.
--
-- markers_follow_scale: whether markers follow map_colour_scale rather than staying on their own.
-- Also nullable — the service resolves a null to "on", but the column itself carries no DEFAULT
-- so a future default change has the same never-chosen signal to read.
ALTER TABLE app_user ADD COLUMN map_colour_scale VARCHAR(10);
ALTER TABLE app_user ADD COLUMN markers_follow_scale BOOLEAN;
