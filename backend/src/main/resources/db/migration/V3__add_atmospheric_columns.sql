ALTER TABLE forecast_evaluation
  ADD COLUMN humidity               INTEGER,
  ADD COLUMN weather_code           INTEGER,
  ADD COLUMN boundary_layer_height  INTEGER,
  ADD COLUMN shortwave_radiation    DECIMAL(7,2),
  ADD COLUMN pm2_5                  DECIMAL(7,2),
  ADD COLUMN dust                   DECIMAL(7,2),
  ADD COLUMN aerosol_optical_depth  DECIMAL(5,3);
