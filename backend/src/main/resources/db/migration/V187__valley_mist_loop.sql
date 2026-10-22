-- V187: valley mist loop (valley-mist-plan M3).
--
-- outcome_rating.seen is what the reader says they actually saw: the enum NAME of SeenTag
-- (COLOUR / MIST / INVERSION / FLAT / NOT_SEEN). Nullable and never required - a rating made without
-- a tag, and every row written before this migration, reads NULL. It is independent of the star:
-- "4 stars, mist" and "4 stars, colour" are different mornings. NOT_SEEN goes with the "didn't see
-- it" record (rating NULL) and only with it; any other tag goes with a star.
--
-- The second CHECK states that pairing so a writer that bypasses OutcomeRatingService cannot store a
-- row the calibration gate would misread: (seen = 'NOT_SEEN') must equal (rating IS NULL) whenever a
-- tag is present. A NULL tag is unconstrained.
ALTER TABLE outcome_rating ADD COLUMN seen VARCHAR(10);
ALTER TABLE outcome_rating ADD CONSTRAINT ck_outcome_rating_seen
    CHECK (seen IS NULL OR seen IN ('COLOUR', 'MIST', 'INVERSION', 'FLAT', 'NOT_SEEN'));
ALTER TABLE outcome_rating ADD CONSTRAINT ck_outcome_rating_seen_pairs_rating
    CHECK (seen IS NULL OR ((seen = 'NOT_SEEN') = (rating IS NULL)));

-- The ERA5 saturation proxy: the observer point's 2 m temperature and dew point (deg C) at the event
-- hour. Recorded, never required - the V175 rule: neither column is in
-- CloudVerificationRepository.deleteIncompleteVerifications, so a row with them NULL (every row
-- written before this migration, and any whose archive carried no value) is complete and is not
-- re-walked. No backfill, no report yet.
ALTER TABLE cloud_verification ADD COLUMN observer_temp_c DOUBLE PRECISION;
ALTER TABLE cloud_verification ADD COLUMN observer_dew_point_c DOUBLE PRECISION;
