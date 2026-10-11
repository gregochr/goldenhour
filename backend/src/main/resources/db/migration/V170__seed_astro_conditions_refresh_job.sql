-- Seeds the scheduler row for the daily astro conditions refresh.
--
-- AstroConditionsService scores each night's observing conditions (cloud, visibility, moonlight)
-- for every enabled location with a Bortle class, from one batched Open-Meteo request and no Claude
-- call. Its only caller was the hand-started synchronous engine, which the scheduled pipeline never
-- reaches, so production has held no astro_conditions row since 2026-07-31 and the night forecast
-- was absent on nearly every night. AstroConditionsRefreshJob is the schedule it never had: it
-- scores tonight and tomorrow night (never a night already in progress before dawn, whose forecast
-- hours would be partial). The service upserts its own row per (location, date), so storage stays
-- flat; it never deletes one.
--
-- 15:30 UTC is free in production's scheduler and sits where the data is wanted: after the 14:00
-- intraday pipeline cycle, and before dusk at every time of year in the UK. Once a day because the
-- score looks at most two nights ahead and a night's forecast moves little between mid-afternoon and
-- dusk. job_key is NOT NULL UNIQUE, so the insert adds exactly one row.
INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type,
                                  cron_expression, status)
VALUES ('astro_conditions_refresh',
        'Astro Conditions',
        'Scores tonight''s and tomorrow night''s astro observing conditions (cloud, visibility, '
            || 'moonlight) for every enabled location with a Bortle class. One batched Open-Meteo '
            || 'request, no Claude call. Updates each place''s row for a night rather than adding '
            || 'to it.',
        'CRON',
        '0 30 15 * * *',
        'ACTIVE');
