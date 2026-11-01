-- V211: the two event-slot readings the Sea mist hot topic gates on (coastal-mist-plan Phase 2).
--
-- coastal_mist_low_cloud_pct is the event-slot low cloud cover (percent) and coastal_mist_precip_mm the event-slot
-- precipitation (mm) that the coastal mist check read, so the hot topic (a LIGHT band under 50 % low cloud with no
-- precipitation) can be decided from storage alone, with no Claude call and no forecast re-fetch.
--
-- Both are written on EVERY slot_atmosphere write by SlotAtmosphereWriter (NULL when the coastal check did not
-- run or the weather was missing), the V210 pattern, so a switched-off feature leaves no stale value. Existing
-- rows stay NULL, which the topic reads as "cannot confirm", so it raises nothing for them.
ALTER TABLE slot_atmosphere ADD COLUMN coastal_mist_low_cloud_pct INTEGER;
ALTER TABLE slot_atmosphere ADD COLUMN coastal_mist_precip_mm DOUBLE PRECISION;
