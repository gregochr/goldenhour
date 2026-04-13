-- V80: Add app_version to job_run for per-run build traceability

ALTER TABLE job_run ADD COLUMN app_version VARCHAR(20);
