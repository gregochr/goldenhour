-- Add consecutive failure tracking to locations
ALTER TABLE locations
    ADD COLUMN consecutive_failures INT DEFAULT 0,
    ADD COLUMN last_failure_at TIMESTAMP,
    ADD COLUMN disabled_reason VARCHAR(255);  -- null=enabled, or reason text
