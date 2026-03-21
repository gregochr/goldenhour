-- V56: Add Bortle dark-sky class to locations for aurora photography scoring.
-- Populated by BortleEnrichmentService via POST /api/aurora/admin/enrich-bortle.
-- NULL = not yet enriched. Values 1 (darkest) – 9 (most light-polluted).

ALTER TABLE locations ADD COLUMN IF NOT EXISTS bortle_class INTEGER;
