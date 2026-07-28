-- Schedule the cloud-verification backfill so it walks its own backlog down.
--
-- The backlog cannot be drained in one pass. ~25,000 evaluations at four archive sample points
-- each is roughly 5,000 requests — far beyond Open-Meteo's hourly allowance. A single unbounded
-- run therefore burns the quota, hits a batch that returns no observations, stops, and waits for
-- a human to re-trigger it. Observed in production: 13 batches (~1,250 evaluations) before the
-- limit bit, leaving ~24,000 outstanding.
--
-- Ticking hourly and taking a bounded slice (SCHEDULED_BATCHES_PER_RUN) turns that into steady
-- unattended progress: ~1,200 evaluations an hour, so a full backlog clears in about a day with
-- nobody watching.
--
-- Runs at :20 past to sit clear of the top-of-hour jobs already scheduled, and because
-- Open-Meteo's hourly allowance resets on the hour — starting a little after it means a throttled
-- previous tick has its quota back.
--
-- Cheap to leave enabled once the backlog is clear: the first batch finds no candidates and
-- returns immediately. New evaluations become verifiable as they age past the archive lag, so the
-- job keeps the dataset current rather than needing to be re-run after each forecast cycle.

INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type,
                                  cron_expression, status)
VALUES ('cloud_verification_backfill',
        'Cloud Verification Backfill',
        'Scores past forecasts against ERA5 reanalysis in bounded hourly slices, staying inside '
            || 'the archive rate limit. No-op once the backlog is clear.',
        'CRON',
        '0 20 * * * *',
        'ACTIVE');
