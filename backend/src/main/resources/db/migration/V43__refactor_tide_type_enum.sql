-- V43: Simplify TideType enum from 5 values to 3.
-- OLD: HIGH_TIDE, LOW_TIDE, MID_TIDE, ANY_TIDE, NOT_COASTAL
-- NEW: HIGH, MID, LOW
-- Empty set = not coastal (replaces NOT_COASTAL sentinel).
-- All three selected = any tide works (replaces ANY_TIDE).

-- Rename existing values
UPDATE location_tide_type SET tide_type = 'HIGH' WHERE tide_type = 'HIGH_TIDE';
UPDATE location_tide_type SET tide_type = 'MID'  WHERE tide_type = 'MID_TIDE';
UPDATE location_tide_type SET tide_type = 'LOW'  WHERE tide_type = 'LOW_TIDE';

-- ANY_TIDE → expand to all three (HIGH, MID, LOW), avoiding duplicates
INSERT INTO location_tide_type (location_id, tide_type)
  SELECT location_id, 'HIGH' FROM location_tide_type WHERE tide_type = 'ANY_TIDE'
    AND NOT EXISTS (SELECT 1 FROM location_tide_type lt2
      WHERE lt2.location_id = location_tide_type.location_id AND lt2.tide_type = 'HIGH');

INSERT INTO location_tide_type (location_id, tide_type)
  SELECT location_id, 'MID' FROM location_tide_type WHERE tide_type = 'ANY_TIDE'
    AND NOT EXISTS (SELECT 1 FROM location_tide_type lt2
      WHERE lt2.location_id = location_tide_type.location_id AND lt2.tide_type = 'MID');

INSERT INTO location_tide_type (location_id, tide_type)
  SELECT location_id, 'LOW' FROM location_tide_type WHERE tide_type = 'ANY_TIDE'
    AND NOT EXISTS (SELECT 1 FROM location_tide_type lt2
      WHERE lt2.location_id = location_tide_type.location_id AND lt2.tide_type = 'LOW');

-- Remove sentinel values
DELETE FROM location_tide_type WHERE tide_type IN ('ANY_TIDE', 'NOT_COASTAL');
