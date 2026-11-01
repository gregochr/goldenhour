-- V210: coastal mist band on slot_atmosphere (coastal-mist-plan Phase 1).
--
-- coastal_mist_band is the CoastalMistBand enum NAME (NONE/LIGHT/THICK) for the event slot at a coastal
-- (tide types), non-mist_prone site, the mist_potential convention: a plain string, no @Enumerated, so no
-- LocalH2EnumWidener entry. THICK is stored for tuning only; the prompt shows LIGHT alone.
--
-- coastal_mist_scored is TRUE when the writer's data carried a coastal mist result (the switch was on and the
-- site is coastal and not mist_prone), whatever the band, so a TRUE row never has a NULL band. It is FALSE for
-- every existing row, for an inland or mist_prone site, and whenever photocast.coastal-mist.enabled is false.
--
-- Both are written on EVERY slot_atmosphere write by SlotAtmosphereWriter (NULL / FALSE when the check did not
-- run), so a switched-off feature or a re-flagged site leaves no stale value. Existing rows stay NULL / FALSE.
ALTER TABLE slot_atmosphere ADD COLUMN coastal_mist_band VARCHAR(10);
ALTER TABLE slot_atmosphere ADD COLUMN coastal_mist_scored BOOLEAN NOT NULL DEFAULT FALSE;
