-- V159: survivor_atmosphere -> slot_atmosphere — a mechanical rename, no behaviour change.
--
-- Since #947/#948 (2026-09-30, "record conditions for every place", Phases 1-2) this table holds
-- a row for EVERY candidate the pipeline fetched weather for that cycle -- triaged-out and
-- Gate-4-stood-down candidates included -- not only the ones that survived triage and Gate 4
-- ("survivors"). "Survivor" has therefore been a false name on the table, and on every class built
-- around it (SurvivorAtmosphereEntity, SurvivorAtmosphereRepository, SurvivorAtmosphereWriter,
-- SurvivorSignalReader, SurvivorSignals), since the day those phases shipped -- both PRs said so
-- and left the rename for its own change. This migration pays that debt. This repository's word
-- for (location, date, event_type) is "slot" (BriefingSlot, "slot" throughout CLAUDE.md), so the
-- new name is slot_atmosphere.
--
-- Renaming the table does NOT rename its dependent objects in Postgres -- the primary key
-- constraint, the foreign key, the unique constraint and the index all keep their old names unless
-- renamed explicitly -- so each is renamed here too, to the slot_atmosphere spelling, so a future
-- \d slot_atmosphere shows one consistent name rather than a mix of old and new.
--
-- The primary key was never given an explicit name in V115's CREATE TABLE, so Postgres assigned
-- the default survivor_atmosphere_pkey; it is renamed here for the same reason.
--
-- Additive-only in effect (a rename, not a structural change): every column, every row and every
-- other table's foreign keys into this one are unaffected. The application-layer flag
-- photocast.slot-atmosphere.write replaces photocast.survivor-atmosphere.write, but
-- SlotAtmosphereWriter continues to read the OLD key too (and prefers it when set) so that a
-- production application.yml -- not in this repository -- which still sets the old key keeps
-- working unchanged until it is updated.

ALTER TABLE survivor_atmosphere RENAME TO slot_atmosphere;

ALTER TABLE slot_atmosphere RENAME CONSTRAINT survivor_atmosphere_pkey TO slot_atmosphere_pkey;
ALTER TABLE slot_atmosphere RENAME CONSTRAINT fk_survivor_atmosphere_location TO fk_slot_atmosphere_location;
ALTER TABLE slot_atmosphere RENAME CONSTRAINT uq_survivor_atmosphere TO uq_slot_atmosphere;

ALTER INDEX idx_survivor_atmosphere_date RENAME TO idx_slot_atmosphere_date;
