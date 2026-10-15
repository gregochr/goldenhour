-- Outcome rating (docs/engineering/outcome-rating-plan.md §2 #1): how a sunrise or sunset that has
-- already happened actually looked, recorded by one Pro or Admin reader for one location. One row
-- per (user, location, event date, event type), upsert-only: a later rating replaces the earlier.
--
-- rating               1..5 stars, or NULL for "didn't see it". A row's existence is the answer, so
--                      there is no separate flag: NULL is the spec's "not seen", not a missing value.
-- event_time           the solar event instant the SERVER computed for THIS location (not the
--                      roster's earliest), stored so the admin footer's "Xh before sunrise" needs no
--                      recomputation and survives a later change to the solar library.
-- pipeline_run_id,     the forecast the reader actually SAW for the slot at the event (resolved by
-- forecast_run_at,     AsOfEventResolver on every write): the served result's own submission instant,
-- forecast_evaluation_id,  the cycle that instant names (exact trigger-time match) and the one
-- forecast_rating      forecast_evaluation row available by the event and not retracted at it. All
--                      nullable: a slot can have been shown with no forecast behind it at all.
--                      forecast_rating is the PREDICTED STAR THE READER SAW, stored because a served
--                      star is not always any one row's (a recombined OPEN_FELL slot averages a sky
--                      and a bluebell result); NULL means "the star seen is not recoverable" and the
--                      calibration gate skips the rating.
--
-- ON DELETE CASCADE on user_id: deleting a user (UserService.deleteUser is a plain delete) must not be
-- blocked by, or leave behind, their ratings - the ask_usage precedent (V166). location_id does NOT
-- cascade: locations are never hard-deleted (actual_outcome is the precedent). forecast_evaluation_id
-- is SET NULL because forecast_evaluation is never pruned today, but a row that is must not take a
-- reader's rating with it.
--
-- uq_outcome_rating_slot is what makes the first rating's find-or-insert race safe: the loser of two
-- concurrent inserts gets a unique violation, which the store reads as "the row exists now".
CREATE TABLE outcome_rating (
    id                     BIGSERIAL    PRIMARY KEY,
    user_id                BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    location_id            BIGINT       NOT NULL REFERENCES locations (id),
    event_date             DATE         NOT NULL,
    event_type             VARCHAR(10)  NOT NULL,
    rating                 INTEGER,
    event_time             TIMESTAMPTZ  NOT NULL,
    pipeline_run_id        BIGINT,
    forecast_run_at        TIMESTAMPTZ,
    forecast_evaluation_id BIGINT       REFERENCES forecast_evaluation (id) ON DELETE SET NULL,
    forecast_rating        INTEGER,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_outcome_rating_slot UNIQUE (user_id, location_id, event_date, event_type),
    CONSTRAINT ck_outcome_rating_range CHECK (rating IS NULL OR (rating BETWEEN 1 AND 5)),
    CONSTRAINT ck_outcome_rating_event_type CHECK (event_type IN ('SUNRISE', 'SUNSET'))
);

CREATE INDEX idx_outcome_rating_user_date ON outcome_rating (user_id, event_date);
CREATE INDEX idx_outcome_rating_slot ON outcome_rating (location_id, event_date, event_type);
-- The pointer is read by the calibration join and checked by the FK on every delete of a
-- forecast_evaluation row (the wildlife comfort refresh deletes HOURLY rows twice a day).
CREATE INDEX idx_outcome_rating_evaluation ON outcome_rating (forecast_evaluation_id);
