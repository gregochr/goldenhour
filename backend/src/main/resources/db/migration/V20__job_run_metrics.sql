-- Track each scheduled run (Sonnet, Haiku, Weather, Tide)
CREATE TABLE job_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_name VARCHAR(20) NOT NULL,          -- SONNET, HAIKU, WEATHER, TIDE
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,                 -- null if still running
    duration_ms BIGINT,                     -- null if still running
    locations_processed INT DEFAULT 0,      -- total locations attempted
    succeeded INT DEFAULT 0,                -- successful evaluations
    failed INT DEFAULT 0,                   -- failed evaluations
    total_cost_pence INT DEFAULT 0,         -- sum of all API call costs in pence
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_job_run_name_started ON job_run (job_name, started_at DESC);

-- Track individual API calls within a run
CREATE TABLE api_call_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_run_id BIGINT NOT NULL REFERENCES job_run(id) ON DELETE CASCADE,
    service VARCHAR(50) NOT NULL,           -- OPEN_METEO_FORECAST, ANTHROPIC, WORLD_TIDES, etc.
    called_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    duration_ms BIGINT,
    request_method VARCHAR(10),             -- GET, POST, etc. (null for non-HTTP)
    request_url VARCHAR(2048),              -- full URL for retry purposes
    request_body TEXT,                      -- JSON payload if applicable (null for GET)
    status_code INT,                        -- HTTP status (null for non-HTTP services)
    response_body TEXT,                     -- response body on error (for debugging)
    succeeded BOOLEAN NOT NULL DEFAULT FALSE,
    error_message VARCHAR(500),             -- truncated error message if failed
    cost_pence INT DEFAULT 0,               -- calculated cost for this single API call
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_api_call_log_job_run ON api_call_log (job_run_id);
CREATE INDEX idx_api_call_log_service ON api_call_log (service);
CREATE INDEX idx_api_call_log_failed ON api_call_log (succeeded);
