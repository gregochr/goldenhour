-- Adds foreign key constraints from forecast_evaluation and actual_outcome
-- to locations(name). Both tables already reference locations by name string;
-- this enforces referential integrity at the DB level.
--
-- ON DELETE RESTRICT (default): prevents deleting a location that has
-- associated forecast or outcome history. This is intentional — historical
-- data should be preserved. Remove the location from YAML config instead,
-- and archive or manually clean up history if needed.
ALTER TABLE forecast_evaluation
    ADD CONSTRAINT fk_forecast_eval_location
    FOREIGN KEY (location_name) REFERENCES locations(name);

ALTER TABLE actual_outcome
    ADD CONSTRAINT fk_actual_outcome_location
    FOREIGN KEY (location_name) REFERENCES locations(name);
