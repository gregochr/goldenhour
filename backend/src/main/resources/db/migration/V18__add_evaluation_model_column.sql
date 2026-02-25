ALTER TABLE forecast_evaluation
    ADD COLUMN evaluation_model VARCHAR(10) DEFAULT NULL;

UPDATE forecast_evaluation
    SET evaluation_model = 'SONNET'
    WHERE fiery_sky_potential IS NOT NULL;

UPDATE forecast_evaluation
    SET evaluation_model = 'HAIKU'
    WHERE rating IS NOT NULL AND fiery_sky_potential IS NULL;
