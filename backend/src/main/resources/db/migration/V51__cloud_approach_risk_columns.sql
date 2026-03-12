-- Cloud approach risk detection columns on forecast_evaluation
ALTER TABLE forecast_evaluation ADD COLUMN solar_trend_event_low_cloud INT;
ALTER TABLE forecast_evaluation ADD COLUMN solar_trend_earliest_low_cloud INT;
ALTER TABLE forecast_evaluation ADD COLUMN solar_trend_building BOOLEAN;
ALTER TABLE forecast_evaluation ADD COLUMN upwind_current_low_cloud INT;
ALTER TABLE forecast_evaluation ADD COLUMN upwind_event_low_cloud INT;
ALTER TABLE forecast_evaluation ADD COLUMN upwind_distance_km INT;
