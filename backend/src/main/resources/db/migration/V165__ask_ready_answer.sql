-- Ask PhotoCast's Ready answers (docs/engineering/ask-photocast-plan.md §2.4): one precomputed,
-- user-less answer per (scope, Ready question), written after each pipeline cycle and served by
-- GET /api/ask/ready.
--
-- scope_key is 'ALL' or a region id (as text); question_id is the ReadyQuestion name. Both together
-- are the identity, so a later precompute upserts the row rather than adding one: the table holds at
-- most (regions + 1) x 7 rows and needs no pruning.
--
-- question_text is the text the answer was written for, fixed at precompute time (it can name a day:
-- "Best spot tonight?"); window_ids are the windows the question was asked about, comma-separated
-- (a window id is yyyy-MM-dd_sunrise|sunset and never contains a comma), kept so a serve can tell
-- when they have all passed. answer_json is the validated answer INCLUDING each pick's
-- ratingAtAnswer and verdictAtAnswer: a serve withholds the whole question when live data no longer
-- agrees with them.
--
-- briefing_generated_at is a naive UTC timestamp like daily_briefing_cache's. pipeline_run_id is the
-- cycle that triggered the precompute, or NULL for an admin's on-demand run; it is deliberately not
-- a foreign key, so pruning pipeline runs never has to know about this table.
CREATE TABLE ask_ready_answer (
    id                    BIGSERIAL    PRIMARY KEY,
    scope_key             VARCHAR(20)  NOT NULL,
    question_id           VARCHAR(30)  NOT NULL,
    question_text         VARCHAR(200) NOT NULL,
    window_ids            TEXT         NOT NULL,
    briefing_generated_at TIMESTAMP    NOT NULL,
    pipeline_run_id       BIGINT,
    answer_json           TEXT         NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ask_ready_answer_scope_question UNIQUE (scope_key, question_id)
);
