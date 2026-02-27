-- Rename job run name from WILDLIFE to WEATHER
UPDATE job_run SET job_name = 'WEATHER' WHERE job_name = 'WILDLIFE';
