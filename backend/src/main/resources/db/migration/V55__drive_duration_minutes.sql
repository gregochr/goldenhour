-- V55: Add drive_duration_minutes to location for drive-time map filter
-- Populated by DriveDurationService via POST /api/locations/drive-times
-- NULL = not yet computed

ALTER TABLE location ADD COLUMN IF NOT EXISTS drive_duration_minutes INTEGER;
