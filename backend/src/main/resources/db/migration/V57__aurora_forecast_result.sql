-- V57: Aurora forecast results stored per location per date.
-- Written by AuroraForecastRunService on manual forecast runs triggered from the Admin UI.
-- Allows the map view to display aurora scores for any date that has been forecast,
-- independent of the live alert state machine (AuroraStateCache).

CREATE TABLE aurora_forecast_result (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    location_id         BIGINT NOT NULL,
    forecast_date       DATE NOT NULL,
    run_timestamp       TIMESTAMP WITH TIME ZONE NOT NULL,
    stars               INT NOT NULL,
    summary             VARCHAR(500),
    factors             TEXT,
    triaged             BOOLEAN NOT NULL DEFAULT FALSE,
    triage_reason       VARCHAR(500),
    source              VARCHAR(50),
    alert_level         VARCHAR(20),
    max_kp              DOUBLE PRECISION,
    CONSTRAINT fk_afr_location FOREIGN KEY (location_id) REFERENCES locations(id)
);

CREATE INDEX idx_afr_date ON aurora_forecast_result(forecast_date);
CREATE INDEX idx_afr_location_date ON aurora_forecast_result(location_id, forecast_date);
