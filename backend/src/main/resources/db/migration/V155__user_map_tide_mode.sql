-- V155: the per-user Map tab tide mode (map-mobile-sheet-plan.md, M4).
--
-- One more per-user map preference on app_user, alongside V147's map_colour_scale — there is no
-- user_settings table.
--
-- map_tide_mode: whether the phone Map tab's tide cues show — 'auto', 'always' or 'off'. Nullable
-- with no backfill and no default, deliberately, matching V147's reasoning exactly: NULL means
-- "never chosen", which the client reads as Auto — a DEFAULT clause here would erase that
-- distinction on every existing row and pre-empt a later change to what "never chosen" resolves to.
ALTER TABLE app_user ADD COLUMN map_tide_mode VARCHAR(10);
