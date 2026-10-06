-- Ask PhotoCast's question log (docs/engineering/ask-photocast-plan.md §2.5): one row per request that
-- was ANSWERED, so the owner can see how readers use the box, what the forecast cannot answer, and
-- how often a question is served free (Ready match, pre-filter, cache) rather than by Claude.
--
-- Denied requests (rate limited, allowance used up, spend cap, a malformed question) write NO row: a
-- looping client must not be able to write millions. They are counted in memory and logged at INFO.
--
-- outcome  READY_MATCH | PREFILTER_CANT | CACHE_HIT | CLAUDE_OK | CLAUDE_CANT | CLAUDE_FAILED
--
-- normalised_question is kept ONLY for the two outcomes the engine answered (CLAUDE_OK, CLAUDE_CANT),
-- at most 200 characters, and is never returned by the metrics endpoint. The check constraint makes
-- that a property of the table rather than of the application: a question on any other outcome is
-- refused by the database. missing is the "what PhotoCast does not have" phrase of a can't-answer, at
-- most 60 characters.
--
-- user_id is ON DELETE SET NULL, NOT cascade and NOT restrict: deleting a user (UserService.deleteUser
-- is a plain delete) must neither be blocked by their log rows nor erase the aggregate, but the link
-- to the person goes. (ask_usage, which is per user and meaningless without one, cascades instead.)
--
-- Pruned nightly by AskLogCleanupJob (ask_log_cleanup, seeded below) on created_at, 90 days by default
-- (photocast.ask.log.retention-days). idx_ask_log_created_at serves both that delete and the metrics
-- window.
CREATE TABLE ask_log (
    id                  BIGSERIAL    PRIMARY KEY,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    user_id             BIGINT       REFERENCES app_user (id) ON DELETE SET NULL,
    scope_key           VARCHAR(20)  NOT NULL,
    view                VARCHAR(20)  NOT NULL,
    outcome             VARCHAR(20)  NOT NULL,
    normalised_question VARCHAR(200),
    missing             VARCHAR(60),
    duration_ms         BIGINT       NOT NULL,
    CONSTRAINT ck_ask_log_question_only_when_engine_answered
        CHECK (normalised_question IS NULL OR outcome IN ('CLAUDE_OK', 'CLAUDE_CANT'))
);

CREATE INDEX idx_ask_log_created_at ON ask_log (created_at);

-- The nightly prune. 03:55 UTC is free in every scheduler seed across the migrations (V68, V73, V79,
-- V81, V101, V105, V118, V131, V133, V145, V146, V151, V161, V162 and V164 use 02:00, 02:40, 03:00,
-- 03:10, 03:30, 03:45, 03:50, 04:00, 04:40, 05:00, 05:30/17:30, 14:00, 15:00, 22:00 and :20 past the
-- hour; V151's own comment about which are taken is out of date). Same shape as V162's seed.
-- job_key is NOT NULL UNIQUE, so the insert adds exactly one row.
INSERT INTO scheduler_job_config (job_key, display_name, description, schedule_type,
                                  cron_expression, status)
VALUES ('ask_log_cleanup',
        'Ask Log Cleanup',
        'Prunes Ask PhotoCast question-log rows older than the retention '
            || '(photocast.ask.log.retention-days, 90 by default)',
        'CRON',
        '0 55 3 * * *',
        'ACTIVE');
