-- V22: Create model_selection table for storing active evaluation model
CREATE TABLE model_selection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    active_model VARCHAR(10) NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- Insert default row (Haiku)
INSERT INTO model_selection (active_model, updated_at)
VALUES ('HAIKU', CURRENT_TIMESTAMP);
