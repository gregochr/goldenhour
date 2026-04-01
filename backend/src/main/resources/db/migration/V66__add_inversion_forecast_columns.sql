-- Cloud inversion score and potential persisted from Claude evaluation
ALTER TABLE forecast_evaluation ADD COLUMN inversion_score INTEGER;
ALTER TABLE forecast_evaluation ADD COLUMN inversion_potential VARCHAR(10);
