-- V152: per-user "Coming up" last-seen timestamp — the tab badge's only stored state (plan D3).
--
-- The badge counts arrivals into the feed's 90-day window since the user last looked; the arrival
-- date itself is computable from the served payload (entries carry enteredWindow), so the ONLY
-- per-user fact is when they last looked. On app_user like every other per-user setting (V67 home
-- location, V136 local_radius_miles, V147 map_colour_scale) — there is no user_settings table.
--
-- Nullable, no default, no backfill, deliberately — the V136/V147 pattern. NULL means "never
-- opened the tab", which the client renders as NO badge and NO flags (a brand-new account opens
-- quiet), and which the first tab open converts to now via a quiet bootstrap write (plan D3's
-- round-3 deadlock fix). A DEFAULT or backfill of now would be a false claim that every existing
-- account looked at the feed today; a backfill of an old date would flag ninety days of almanac
-- as "new" at every account's next visit — the loud failure this design chooses silence over.
--
-- TIMESTAMP WITH TIME ZONE, not DATE: the served comparison value is the Europe/London civil date,
-- but that is DERIVED at serve time (ForecastHorizon) so the timezone rule lives in one place —
-- the stored instant stays the durable truth and survives any future change to the derivation.

ALTER TABLE app_user ADD COLUMN coming_up_last_seen_at TIMESTAMP WITH TIME ZONE;
