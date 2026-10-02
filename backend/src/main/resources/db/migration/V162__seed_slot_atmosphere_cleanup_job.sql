-- Seeds the scheduler row for the nightly slot_atmosphere retention prune.
--
-- slot_atmosphere (the per-slot atmospheric readings: dust, AOD, PM2.5, surge, snow depth, freezing
-- level, humidity, temperature, inversion score; unique on location, date, event) was never pruned,
-- and since #947 ("record conditions for every place") it is written for every candidate slot the
-- pipeline fetched weather for -- roughly 500 new rows a day. Owner decision 2026-10-02: keep 180
-- days (photocast.slot-atmosphere.retention-days) and delete older rows nightly. The longest
-- reader looks back 60 days (the Coming up valley-inversions trailing history), so 180 days keeps
-- every reader whole with a wide margin; SlotAtmosphereCleanupJob refuses to start with a retention
-- shorter than that read-back.
--
-- The job deletes rows whose evaluation_date (the slot's own date, not its write time) is more than
-- the retention before today's UK civil date, in one bulk DELETE the date index serves. It prunes no
-- other table.
--
-- 03:45 UTC is free in production's scheduler: 03:30 holds the aurora batch and the disposition
-- cleanup, 04:40 the topic daily log. Same shape as V101's disposition_cleanup seed. job_key is
-- NOT NULL UNIQUE, so the insert adds exactly one row.
INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type,
                                  cron_expression, status)
VALUES ('slot_atmosphere_cleanup',
        'Slot Atmosphere Cleanup',
        'Prunes per-slot atmospheric readings (dust, snow, surge, inversion score) whose slot date '
            || 'is more than 180 days old',
        'CRON',
        '0 45 3 * * *',
        'ACTIVE');
