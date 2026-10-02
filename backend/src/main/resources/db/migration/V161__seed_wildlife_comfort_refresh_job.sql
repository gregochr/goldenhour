-- Seeds the scheduler row for the twice-daily wildlife hourly comfort refresh.
--
-- Wildlife hides carry an hourly comfort forecast (temperature, feels-like, wind, rain between
-- sunrise and sunset). Its only trigger was commented out by accident on 2026-02-27 and never
-- replaced, so production has held no HOURLY forecast_evaluation rows since. WildlifeComfortRefreshJob
-- replaces it: one batched Open-Meteo forecast request for every WILDLIFE-only hide, no air-quality
-- call, no Claude call, replacing each place-date's superseded HOURLY rows so storage stays flat.
--
-- 05:30 and 17:30 UTC are free in production's scheduler: the neighbours are the 05:00 briefing
-- model comparison (PAUSED), 04:40 topic_daily_log, 03:30 aurora batch and disposition cleanup,
-- 14:00 intraday pipeline and the hourly :20 cloud verification. Twice a day because the table
-- looks at most five days ahead and a morning and an evening refresh keep it current for both
-- the early and the late planner. job_key is NOT NULL UNIQUE, so the insert adds exactly one row.
INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type,
                                  cron_expression, status)
VALUES ('wildlife_comfort_refresh',
        'Wildlife Comfort Forecast',
        'Refreshes the hourly comfort forecast (temperature, feels-like, wind, rain between '
            || 'sunrise and sunset) for every location tagged WILDLIFE and nothing else, for today '
            || 'and the next five days. One batched Open-Meteo request, no Claude call. Replaces '
            || 'each place''s superseded hourly rows for a date rather than adding to them.',
        'CRON',
        '0 30 5,17 * * *',
        'ACTIVE');
