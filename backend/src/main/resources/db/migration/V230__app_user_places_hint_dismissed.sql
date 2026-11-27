-- V230: the one-time "Bookmark places and watch dates" hint's only stored state (screen-spec D8).
--
-- A reader with nothing bookmarked or watched sees the hint once, in place of Your places. It is gone for
-- good after the first save of any kind (a place, an area or a date) or after a dismissal; the server
-- stamps this column on either, and serves "due" as placesHintDue on GET /api/user/settings, so a client
-- never re-derives it and removing every bookmark later does not bring the hint back.
--
-- Nullable, no default, the V181 postcode_prompt_dismissed_at pattern: NULL means "never dismissed or saved".
-- TIMESTAMP WITH TIME ZONE like its sibling. One deliberate difference from V181: a BACKFILL for every reader who
-- already holds a favourite of any kind (a place, an area or a watched date), so the hint is never shown to a reader
-- who has plainly learned to bookmark, nor comes back for one who later removes everything.

ALTER TABLE app_user ADD COLUMN places_hint_dismissed_at TIMESTAMP WITH TIME ZONE;

UPDATE app_user SET places_hint_dismissed_at = now()
WHERE id IN (SELECT DISTINCT user_id FROM user_favourite);
