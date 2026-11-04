-- V217: the evening refresh now re-rates tomorrow's sunset as well as tomorrow's sunrise, and the
-- afternoon refresh leaves an already-rated tomorrow's sunset to it (evening-tomorrow-sunset-plan).
--
-- Three guarded updates on scheduler_job_config, each on its own exact old value, so a row an operator
-- has since edited by hand is left alone. Each writes only the one column named, on at most one row
-- (job_key is NOT NULL UNIQUE). Same shape and reasoning as V141, V174 and V212 (an applied migration
-- is never edited in place). The job key evening_sunrise_refresh is unchanged (it is routed on).

-- 1. The afternoon row: V212's text said tomorrow's sunrise only, and described the settled skip as if
--    it covered tonight's sunset, which is rated at every stability. Kept under 480 characters.
UPDATE scheduler_job_config
SET description = 'Re-evaluates tonight''s sunset at every stability, a set time before it (else at its cron time), then re-runs the briefing. Tomorrow''s sunrise and sunset are left for tonight''s evening refresh. If that is paused, or a place has no rating yet for tomorrow, they are rated here too: SETTLED T+1 sunset is skipped (SKIPPED_NO_REFRESH_NEEDED).'
WHERE job_key = 'intraday_forecast_refresh'
  AND description = 'Re-evaluates T sunset, T+1 sunrise and T+1 sunset ahead of tonight''s sunset (a set time before it, else at its cron time). TRANSITIONAL/UNSETTLED are always evaluated. SETTLED T+1 sunset is skipped (SKIPPED_NO_REFRESH_NEEDED). A T+1 sunrise that already has a rating is left for tonight''s evening refresh when that is due. Re-runs the briefing afterwards.';

-- 2. The evening row's description (V204's text).
UPDATE scheduler_job_config
SET description = 'Re-evaluates tomorrow''s sunrise and sunset at 21:00 UK time all year, at every stability, so tonight''s decisions (an early alarm, tomorrow evening''s plan) rest on a fresh look'
WHERE job_key = 'evening_sunrise_refresh'
  AND description = 'Re-evaluates tomorrow''s sunrise at 21:00 UK time all year, so the bedtime decision rests on a fresh look';

-- 3. The evening row's display name (V184's), shown as the panel title and in the All-jobs list.
UPDATE scheduler_job_config
SET display_name = 'Evening Refresh'
WHERE job_key = 'evening_sunrise_refresh'
  AND display_name = 'Evening Sunrise Refresh';
