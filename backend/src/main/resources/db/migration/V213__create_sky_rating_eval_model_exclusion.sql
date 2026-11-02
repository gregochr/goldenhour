-- V213: Models excluded from the weekly Sky-Rating Eval.
--
-- An opt-out list: a selectable model is scheduled unless it has a row here, so a newly added
-- platform model joins the weekly eval with no edit. A row for a model that is no longer
-- selectable is ignored. Written from Operations -> Sky Eval ("Weekly run").
CREATE TABLE sky_rating_eval_model_exclusion (
    model        VARCHAR(10) PRIMARY KEY,
    excluded_at  TIMESTAMP   NOT NULL
);
