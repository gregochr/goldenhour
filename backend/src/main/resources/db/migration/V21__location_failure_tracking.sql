-- Add consecutive failure tracking to locations
ALTER TABLE locations ADD COLUMN consecutive_failures INT DEFAULT 0;
ALTER TABLE locations ADD COLUMN last_failure_at TIMESTAMP;
ALTER TABLE locations ADD COLUMN disabled_reason VARCHAR(255);  -- null=enabled, or reason text
