CREATE TABLE actual_outcome
(
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    location_lat  DECIMAL(9,6) NOT NULL,
    location_lon  DECIMAL(9,6) NOT NULL,
    location_name VARCHAR(255) NOT NULL,
    outcome_date  DATE         NOT NULL,
    target_type   VARCHAR(10)  NOT NULL,
    went_out      BOOLEAN,
    actual_rating INTEGER,
    notes         TEXT,
    recorded_at   TIMESTAMP    NOT NULL
);
