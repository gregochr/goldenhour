-- Ask PhotoCast's per-user daily ceilings (docs/engineering/ask-photocast-plan.md §2.5 step 8):
-- one row per (user, UK civil day) holding two counters.
--
-- used          how many typed questions the user has been charged today. Reserved before the engine
--               runs and REFUNDED when the engine fails or honestly says "not in the forecast".
-- engine_calls  how many times the engine has been run for the user today. NEVER refunded: it is the
--               ceiling that stops "refund on unanswerable" turning into unlimited free Claude calls.
--
-- Both are taken together by ONE conditional UPDATE (used < limit AND engine_calls < ceiling), so a
-- reservation is atomic however many requests race. The limits are configuration, not data, so
-- nothing here stores them.
--
-- usage_date is the Europe/London civil date the request RESERVED on (a refund targets that same
-- date even when the question completes after midnight).
--
-- ON DELETE CASCADE: deleting a user must not be blocked by, or leave behind, their usage rows
-- (UserService.deleteUser is a plain delete). The unique constraint is what makes the first
-- question's find-or-insert race safe: the loser of two concurrent inserts gets a unique violation,
-- which the application treats as "the row exists now".
--
-- No pruning yet: at most one row per asking user per day.
CREATE TABLE ask_usage (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT  NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    usage_date   DATE    NOT NULL,
    used         INTEGER NOT NULL DEFAULT 0,
    engine_calls INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_ask_usage_user_date UNIQUE (user_id, usage_date),
    CONSTRAINT ck_ask_usage_non_negative CHECK (used >= 0 AND engine_calls >= 0)
);
