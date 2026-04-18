-- Remove the Met Office space weather scrape job from the scheduler.
-- The Met Office narrative has been removed from the aurora pipeline.
DELETE FROM scheduler_job_config WHERE job_key = 'met_office_scrape';
