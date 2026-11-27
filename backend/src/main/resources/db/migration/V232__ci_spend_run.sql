-- V232: CI prompt-regression spend (ci-spend-plan.md, C2).
--
-- The two paid CI jobs ("Prompt regression - sky" / "- ask") spend real Anthropic money on a separate,
-- spend-capped key. They upload a small token-count artifact; the ci_spend_poll job pulls those artifacts from the
-- GitHub Actions API, prices them with CostCalculator and stores them here. These tables are read only by the
-- Runs cost views (C3). They are never mixed into job_run, api_call_log or any app figure.
--
-- ci_spend_attempt: records that a (workflow run, attempt) is settled, so later polls skip it with no API call.
--   suites_ran   comma-separated suites that ran in that attempt (for example 'sky,ask'), or '' when none did.
-- ci_spend_run: one row per suite that ran in an attempt. Identity, event, PR, times and conclusion come from the
--   GitHub API, never from the artifact file.
--   usage_state  KNOWN (artifact read and priced) | UNKNOWN (the suite ran but no valid artifact exists yet, or it
--                expired) | UNPRICED (tokens stored, a model id the price table does not know).
--   cost_micro_dollars is non-null only when KNOWN. The exchange rate is the GBP-per-USD rate of started_at's UTC date.
-- ci_spend_model_usage: one row per model per run, with the rate snapshot the cost was priced at (null when the
--   model is unpriced).
CREATE TABLE ci_spend_attempt (
    github_run_id BIGINT                   NOT NULL,
    run_attempt   INTEGER                  NOT NULL,
    settled_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    suites_ran    VARCHAR(16)              NOT NULL DEFAULT '',
    PRIMARY KEY (github_run_id, run_attempt)
);

CREATE TABLE ci_spend_run (
    id                        BIGSERIAL PRIMARY KEY,
    github_run_id             BIGINT                   NOT NULL,
    run_attempt               INTEGER                  NOT NULL,
    suite                     VARCHAR(16)              NOT NULL,
    event                     VARCHAR(32),
    head_sha                  VARCHAR(40),
    pr_number                 INTEGER,
    pr_title                  VARCHAR(300),
    html_url                  VARCHAR(300)             NOT NULL,
    started_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at               TIMESTAMP WITH TIME ZONE,
    conclusion                VARCHAR(32),
    cases                     INTEGER,
    failed                    INTEGER,
    usage_state               VARCHAR(16)              NOT NULL,
    errored_calls             INTEGER                  NOT NULL DEFAULT 0,
    cost_micro_dollars        BIGINT,
    exchange_rate_gbp_per_usd DOUBLE PRECISION,
    fetched_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ci_spend_run UNIQUE (github_run_id, run_attempt, suite),
    CONSTRAINT ck_ci_spend_run_state CHECK (usage_state IN ('KNOWN', 'UNKNOWN', 'UNPRICED'))
);

CREATE INDEX idx_ci_spend_run_started_at ON ci_spend_run (started_at);

CREATE TABLE ci_spend_model_usage (
    id                          BIGSERIAL PRIMARY KEY,
    ci_spend_run_id             BIGINT           NOT NULL REFERENCES ci_spend_run (id) ON DELETE CASCADE,
    model                       VARCHAR(64)      NOT NULL,
    calls                       INTEGER          NOT NULL DEFAULT 0,
    errored_calls               INTEGER          NOT NULL DEFAULT 0,
    input_tokens                BIGINT           NOT NULL DEFAULT 0,
    output_tokens               BIGINT           NOT NULL DEFAULT 0,
    cache_read_tokens           BIGINT           NOT NULL DEFAULT 0,
    cache_write_tokens          BIGINT           NOT NULL DEFAULT 0,
    cache_write_1h_tokens       BIGINT           NOT NULL DEFAULT 0,
    input_usd_per_mtok          DOUBLE PRECISION,
    output_usd_per_mtok         DOUBLE PRECISION,
    cache_write_usd_per_mtok    DOUBLE PRECISION,
    cache_write_1h_usd_per_mtok DOUBLE PRECISION,
    cache_read_usd_per_mtok     DOUBLE PRECISION,
    cost_micro_dollars          BIGINT
);

CREATE INDEX idx_ci_spend_model_usage_run ON ci_spend_model_usage (ci_spend_run_id);

-- The poll job. Seeded DISABLED_BY_CONFIG: photocast.ci-spend.enabled is false (and no GitHub token is set) until
-- the owner turns it on; DynamicSchedulerService restores ACTIVE when the gate opens. Hourly, first poll five
-- minutes after startup. time_zone is explicit: NULL means UTC since V204.
INSERT INTO scheduler_job_config
    (job_key, display_name, description, schedule_type, fixed_delay_ms, initial_delay_ms, time_zone, status,
     status_before_config_disable, config_source)
VALUES ('ci_spend_poll', 'CI Prompt-Test Spend Poll',
        'Reads the token-count artifacts the paid CI prompt-regression suites upload to GitHub Actions and stores their priced spend for the Runs cost views',
        'FIXED_DELAY', 3600000, 300000, 'Europe/London', 'DISABLED_BY_CONFIG', 'ACTIVE',
        'photocast.ci-spend.enabled');
