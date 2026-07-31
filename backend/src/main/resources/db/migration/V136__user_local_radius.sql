-- V136: per-user "local radius" for the Close to home block.
--
-- The radius was a client constant (RADIUS_MILES = 22), then briefly a deployment-wide config
-- property. Neither can be what the design needs: it belongs to the USER, next to the home
-- postcode it is measured from.
--
-- Why a setting rather than simply a bigger default: catchment area grows with the SQUARE of the
-- radius, so 22 -> 30 miles is roughly 1.9x the candidate locations competing for the same handful
-- of card slots, and "close to home" starts meaning a 45-50 minute drive. A user whose best-loved
-- spot sits further out can widen it themselves without degrading the block for everyone else.
--
-- Nullable with no backfill, deliberately. NULL means "never chosen", which the service reads as
-- the 22-mile default — so existing users are unaffected and the column distinguishes "left alone"
-- from "deliberately set to 22". A DEFAULT 22 would erase that distinction on every existing row.

ALTER TABLE app_user ADD COLUMN local_radius_miles INTEGER;
