-- V144: briefing_region_snapshot — the run-to-run movement sink for the Plan tab's heat strip.
--
-- One row per region x date x event per briefing build, holding the number that build DISPLAYED.
-- The strip's movement chip is `current serve-time mean - the same region's mean in the previous
-- build`, and nothing in this schema could answer that before: `daily_briefing_cache` keeps a
-- single row (id = 1, upserted), so the previous build's payload is overwritten by the next one.
--
-- ---------------------------------------------------------------------------------------------
-- WHY NOT evaluation_delta_log (V97), which already stores rating deltas
-- ---------------------------------------------------------------------------------------------
-- Three reasons, all fatal for this use:
--   * it is populated only where a cache entry was REFRESHED, so its population is the
--     intersection of two runs rather than everything a build showed;
--   * its delta is per LOCATION and absolute, while the strip's chip is a region MEAN over
--     `BriefingSlot.votingSlots` — the canopy merges that separate the two are invisible in it;
--   * it therefore cannot reconcile with the `meanRating` printed beside the chip, and a movement
--     figure that disagrees with the number it qualifies is worse than no movement figure.
--
-- ---------------------------------------------------------------------------------------------
-- THE TWO TIMESTAMPS ARE DELIBERATELY DIFFERENT TYPES
-- ---------------------------------------------------------------------------------------------
-- `briefing_generated_at` is TIMESTAMP (no zone) because it is a COMPARAND: it is copied verbatim
-- from `DailyBriefingResponse.generatedAt`, a `LocalDateTime`, which `daily_briefing_cache
-- .generated_at` already stores as TIMESTAMP. Storing the same value as TIMESTAMPTZ would need a
-- zone conversion on write and the mirror of it on read, and any drift in the JVM's zone between
-- the two would silently move which build a delta is measured against. (§4.7 specifies TIMESTAMPTZ
-- for both; this is a deliberate deviation, recorded here and in the plan's P6 row.)
--
-- ⚠️ TIMESTAMP holds MICROseconds, and `LocalDateTime.now()` on Linux carries NANOseconds — so the
-- writer truncates before storing and the reader truncates the comparand to match. See
-- `BriefingRegionSnapshotService.STAMP_PRECISION`: without it, half of all builds on the production
-- platform compare against themselves and the whole strip fills with "unchanged".
--
-- `generated_at` is TIMESTAMPTZ because it is an ABSOLUTE audit instant, read only by the pruner
-- to answer "is this row older than 90 days". Age is a question about elapsed time, so it wants
-- the type that cannot be ambiguous about it.
--
-- ---------------------------------------------------------------------------------------------
-- ⚠️ THIS IS THE THIRD BRIEFING STORE KEYED ON REGION *NAME*, AND A RENAME MUST CLEAR IT
-- ---------------------------------------------------------------------------------------------
-- `daily_briefing_cache` and `cached_evaluation` are the other two, and V137 §7 already had to
-- delete from both when it renamed `Northumberland` -> `Northumberland & Tyneside` and
-- `The North Yorkshire Coast` -> `North York Moors & Coast`. `RegionService.setName` makes that an
-- ordinary admin action, not just a migration event.
--
-- The join here is byte-identical by design (nothing trims or case-folds; the same string is the
-- heat field's focus key), so after a rename the previous build's rows simply stop matching: the
-- renamed region loses its movement chip for one build cycle — ~11-13 h, since V103 retired the
-- standalone briefing cron and builds now ride the pipeline — and its history before the rename is
-- permanently unjoinable. The degrade is silence, which is this channel's documented degrade, so
-- nothing lies. But the NEXT region rename needs a clause for this table, and P7 (which adds
-- `regions.base_*` and a `region_id`-keyed `region_drive_time`) is where a key on the id could be
-- introduced. `BriefingRegion` carries no region id today, which is why this does not.
--
-- ---------------------------------------------------------------------------------------------
-- mean_rating IS NULLABLE, AND THAT IS A VALUE
-- ---------------------------------------------------------------------------------------------
-- Null means "this region had nothing that votes scored in that build" — not zero. A null on
-- either side of the subtraction yields no chip at all (silence), which is the documented degrade:
-- the design's `-` chip means a real MEASURED zero and must never stand in for "unknown".
CREATE TABLE briefing_region_snapshot (
    id                    BIGSERIAL PRIMARY KEY,
    region_name           VARCHAR(255) NOT NULL,
    evaluation_date       DATE         NOT NULL,
    target_type           VARCHAR(10)  NOT NULL,
    mean_rating           NUMERIC(3, 1),
    voting_count          INTEGER      NOT NULL,
    display_verdict       VARCHAR(20),
    generated_at          TIMESTAMPTZ  NOT NULL,
    briefing_generated_at TIMESTAMP    NOT NULL
);

-- ⚠️ UNIQUE, not a plain index, and NOT the index the plan specified.
--
-- §4.7 asked for a plain `(region_name, evaluation_date, target_type, briefing_generated_at DESC)`
-- sized for a per-REGION read: "the latest snapshot with briefing_generated_at < the current
-- build's stamp". The implementation compares against ONE previous build for the whole screen
-- instead (so that a single "at the last forecast run" line can describe every chip truthfully),
-- and neither query it issues constrains `region_name` — so as a plain index this would have been
-- dead weight whose own comment described a read the repository deliberately does not perform.
--
-- As a UNIQUE constraint it earns its place: one build may produce at most one row per region per
-- date per event, and nothing else enforces that. It also covers the plan's read shape if a later
-- phase wants it back.
ALTER TABLE briefing_region_snapshot
    ADD CONSTRAINT uq_brs_key
    UNIQUE (region_name, evaluation_date, target_type, briefing_generated_at);

-- The pruner's own access path, and the one the "which build came before this one" probe uses.
CREATE INDEX idx_brs_generated_at ON briefing_region_snapshot(generated_at);
CREATE INDEX idx_brs_briefing_generated_at ON briefing_region_snapshot(briefing_generated_at);
