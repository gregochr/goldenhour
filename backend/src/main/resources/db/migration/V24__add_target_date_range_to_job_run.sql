-- Track the earliest and latest forecast target dates for each job run
ALTER TABLE job_run ADD COLUMN min_target_date DATE;
ALTER TABLE job_run ADD COLUMN max_target_date DATE;
