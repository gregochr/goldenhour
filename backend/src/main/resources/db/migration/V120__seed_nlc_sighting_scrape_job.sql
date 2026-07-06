-- V120: Seed the NLC sighting scrape scheduled job.
-- Scrapes the NLCNET real-time sightings page for fresh noctilucent-cloud observer reports.
-- Reactive community signal (not a forecast); the scrape fails open and the endpoint gates on
-- freshness + season + clear skies. Cadence mirrors the 10-minute client poll.

INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type, fixed_delay_ms, initial_delay_ms, status, config_source)
VALUES ('nlc_sighting_scrape', 'NLC Sighting Scrape', 'Scrapes NLCNET for fresh noctilucent-cloud observer reports to drive the NLC sighting banner', 'FIXED_DELAY', 600000, 120000, 'ACTIVE', 'nlc.sighting-enabled');
