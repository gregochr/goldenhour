-- V100: Persist Claude-authored short headline on the SSE/admin forecast path.
-- The batch path already surfaces headlines via cached_evaluation; this column
-- closes the gap for rows written via ForecastService.buildEntity (the SSE
-- "Run Full Forecast" admin trigger and the legacy command-executor flows).
-- Nullable — historical rows have no headline and that is fine.

ALTER TABLE forecast_evaluation ADD COLUMN headline VARCHAR(255);
