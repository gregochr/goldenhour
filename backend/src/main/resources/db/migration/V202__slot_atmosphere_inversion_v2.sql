-- V202: v2 inversion evidence on slot_atmosphere (inversion-v2-plan Phase 1b).
--
-- inversion_score_v1 is the OLD calculator's score for a slot the v2 calculator scored; inversion_score_v2_raw
-- is v2's own score before the first-winter raise cap. inversion_score keeps holding the value that drives the
-- readers (hot topic, Coming up, INVERSION_CALC), so it is the v2-derived (possibly raise-capped) score on such
-- a slot and the old score on every other.
--
-- Both columns are written on EVERY slot_atmosphere write by SlotAtmosphereWriter and are NULL when v2 did not
-- compute (a site with no confirmed valley floor, a failed profile fetch, the switch off, or a profile with no
-- usable hours), so a cleared floor or a reverted switch leaves no stale value. Existing rows stay NULL.
--
-- inversion_score_v1 IS NOT NULL marks a "v2 era" row, per slot rather than per date: the switch-over happens
-- site by site, as each valley floor is confirmed. (The rare slot where v2 scored and the old calculator could
-- not has inversion_score_v1 NULL and inversion_score_v2_raw set; inversion_score_v2_raw IS NOT NULL is the
-- exact marker.)
ALTER TABLE slot_atmosphere ADD COLUMN inversion_score_v1 DOUBLE PRECISION;
ALTER TABLE slot_atmosphere ADD COLUMN inversion_score_v2_raw DOUBLE PRECISION;
