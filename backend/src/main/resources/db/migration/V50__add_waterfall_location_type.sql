-- Add WATERFALL location type and reclassify waterfall locations.
-- Replaces LANDSCAPE with WATERFALL for known waterfall locations.

-- Delete existing LANDSCAPE entries for waterfall locations
DELETE FROM location_location_type
WHERE location_type = 'LANDSCAPE'
  AND location_id IN (
    SELECT id FROM location WHERE name IN (
      'Aysgarth Falls', 'Cauldron Snout', 'Crammel Linn',
      'East Gill Force', 'Gibson''s Cave', 'Grey Mare''s Tail',
      'Hardraw Force', 'High Cup Nick', 'High Force',
      'Ingleton Waterfalls Trail', 'Janet''s Foss',
      'Jesmond Dene Waterfall', 'Keld Waterfalls',
      'Kisdon Force', 'Linhope Spout', 'Low Force',
      'Malham Cove', 'Moss Force', 'Pistyll Rhaeadr',
      'Roughting Linn', 'Scale Force', 'Sgwd Clun-Gwyn',
      'Sgwd Yr Eira', 'Sgwd yr Eira (Lower)',
      'Summerhill Force', 'Swaledale Waterfalls',
      'Thornton Force', 'The Strid', 'Wain Wath Force',
      'Whitfield Gill Force', 'Hareshaw Linn'
    )
  );

-- Insert WATERFALL type for each waterfall location
INSERT INTO location_location_type (location_id, location_type)
SELECT id, 'WATERFALL' FROM location WHERE name IN (
  'Aysgarth Falls', 'Cauldron Snout', 'Crammel Linn',
  'East Gill Force', 'Gibson''s Cave', 'Grey Mare''s Tail',
  'Hardraw Force', 'High Cup Nick', 'High Force',
  'Ingleton Waterfalls Trail', 'Janet''s Foss',
  'Jesmond Dene Waterfall', 'Keld Waterfalls',
  'Kisdon Force', 'Linhope Spout', 'Low Force',
  'Malham Cove', 'Moss Force', 'Pistyll Rhaeadr',
  'Roughting Linn', 'Scale Force', 'Sgwd Clun-Gwyn',
  'Sgwd Yr Eira', 'Sgwd yr Eira (Lower)',
  'Summerhill Force', 'Swaledale Waterfalls',
  'Thornton Force', 'The Strid', 'Wain Wath Force',
  'Whitfield Gill Force', 'Hareshaw Linn'
);
