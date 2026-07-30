-- V137: the small regions lose the ability to declare themselves "Worth it" on one location,
-- and five sites return to the county they are actually in.
--
-- WHY. BriefingVerdictEvaluator.rollUpVerdict is a pure ratio with no minimum n: GO fires when
-- goCount >= 20% of the VIABLE (non-STANDDOWN) slots. Since 1 * 100 >= totalViable * 20 holds for
-- every viable count up to 5, a SINGLE GO location carries an entire region whenever five or
-- fewer of its locations are viable. In the large regions that is unreachable. In the small ones
-- it is a normal morning:
--
--   region                      voting roster   STANDDOWNs before one GO carries the region
--   Teesdale                          4          0  -- unconditional
--   Tyne and Wear                     7          2
--   The North York Moors              9          4
--   The North Yorkshire Coast        24         19
--   The Yorkshire Dales              55         50
--   The Lake District                58         53
--   Northumberland                   70         65
--
-- Voting roster = enabled locations, less woodland-only sites (excluded from the sky vote by
-- BriefingHierarchyBuilder, because a wood is judged on inverted polarity), less wildlife-only
-- sites (kept out of the briefing entirely by BriefingService.isColourLocation).
--
-- Two stand-downs out of seven is an ordinary British morning, so Tyne and Wear was reaching
-- "Worth it" off one location on precisely the mixed days the verdict exists to adjudicate.
-- Teesdale never escaped the band at all.
--
-- ConfidenceDeriver does not compensate for this; it inverts it. The ratingRange >= 2 downgrade
-- is a min-max statistic, so it saturates with n — near-certain across 70 locations, uncommon
-- across 4. The channel meant to read "trust this less" was in practice reading roster size, and
-- reading it backwards.
--
-- The fix is data, not thresholds. The 20% ratio is correct for a region of adequate size; the
-- problem was regions that were never large enough for a ratio to mean anything. After this
-- migration the smallest voting roster is 33.
--
-- WHAT THIS DOES NOT FIX — two limits, both found by adversarial review of the first draft:
--
-- 1. Degeneracy is keyed on the VIABLE count, not the roster. rollUpVerdict's denominator is
--    goCount + marginalCount, computed per day and per event from runtime verdicts, and it
--    collapses on bad weather. A region of 77 whose 73 worst locations stand down has 4 viable,
--    and one GO among them still carries it. Regional-scale overcast in the North East is not
--    exotic, so this migration RAISES the bar rather than removing the failure. An earlier draft
--    of this header claimed "no region is reachable on a normal day"; that was wrong.
--
-- 2. The counts below are LOCATIONS. Slots are built per (date, event) and skipped when
--    !supportsTargetType(eventType) (BriefingService), so each region's real per-event roster is
--    a subset — and V138 adds nine SUNRISE-only locations, whose sunset rosters are zero. The
--    per-event numbers are not recorded here because they were never measured.
--
-- A real fix would put a minimum-n term in rollUpVerdict itself, which this deliberately does not
-- touch: the verdict is the star/quality signal, and changing it is a product decision, not a
-- data cleanup.
--
-- The groupings themselves came from photography guides (Lake District / Northumberland / North
-- Yorkshire), which is why the large ones are coherent. The small ones were the leftovers.

-- ---------------------------------------------------------------------------------------------
-- 0. Pre-flight. Every move below is "SET region_id = (SELECT id FROM regions WHERE name = ...)",
--    which on a name that does not match sets region_id to NULL rather than failing — turning a
--    typo into silently unregioned locations. Assert the four destinations exist first.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE found INT;
BEGIN
    SELECT COUNT(*) INTO found FROM regions WHERE name IN (
        'The Lake District', 'The Yorkshire Dales', 'Northumberland', 'The North Yorkshire Coast');
    IF found <> 4 THEN
        RAISE EXCEPTION 'V137: expected 4 destination regions, found %. Reconcile region names before applying.', found;
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------
-- 1. Corrections. Two sites were in Teesdale that are not in Teesdale.
-- ---------------------------------------------------------------------------------------------

-- Stanley Ghyll Force is a Lakeland ravine at Boot in Eskdale, Cumbria — two miles from Muncaster
-- Castle Gardens, which is already correctly in The Lake District, and roughly ninety miles from
-- High Force. This is a plain misfiling, worth fixing independently of everything below.
UPDATE locations
SET region_id = (SELECT id FROM regions WHERE name = 'The Lake District')
WHERE name = 'Stanley Ghyll Force';

-- Hardwick Hall Country Park is at Sedgefield, County Durham — 25 miles east of Teesdale. It
-- joins the Durham cluster (Houghall Woods, Wharton Park) that section 3 carries into
-- Northumberland. Woodland-only, so it votes in neither region; this is correctness, not weight.
UPDATE locations
SET region_id = (SELECT id FROM regions WHERE name = 'Northumberland')
WHERE name = 'Hardwick Hall Country Park';

-- ---------------------------------------------------------------------------------------------
-- 2. Teesdale -> The Yorkshire Dales.
-- ---------------------------------------------------------------------------------------------
-- What remains after section 1 is Teesdale's three genuine members: High Force, Low Force, and
-- Summerhill Force & Gibson's Cave. The North Pennines run continuously into Swaledale, and all
-- three are the upland waterfall subject the Dales roster is already full of. Predicated on the
-- region rather than a name list so anything added since this was written travels with them.
UPDATE locations
SET region_id = (SELECT id FROM regions WHERE name = 'The Yorkshire Dales')
WHERE region_id = (SELECT id FROM regions WHERE name = 'Teesdale');

-- ---------------------------------------------------------------------------------------------
-- 3. Tyne and Wear -> Northumberland.
-- ---------------------------------------------------------------------------------------------
-- Marsden Rock, Trow Rocks and Souter Lighthouse are the southern continuation of the same
-- coastline Northumberland already covers up to Tynemouth; there is no weather boundary at the
-- county line. Carries three County Durham sites with it (Houghall Woods, Wharton Park, Low
-- Barns) — see section 5 on what that costs the name.
UPDATE locations
SET region_id = (SELECT id FROM regions WHERE name = 'Northumberland')
WHERE region_id = (SELECT id FROM regions WHERE name = 'Tyne and Wear');

-- ---------------------------------------------------------------------------------------------
-- 4. The North York Moors -> The North Yorkshire Coast.
-- ---------------------------------------------------------------------------------------------
-- The moors run to the sea at Whitby and Robin Hood's Bay, and the guides treat them as one area.
-- This also reunites Rievaulx Abbey — filed under the Coast but sitting inland in the moors —
-- with the landscape it belongs to.
UPDATE locations
SET region_id = (SELECT id FROM regions WHERE name = 'The North Yorkshire Coast')
WHERE region_id = (SELECT id FROM regions WHERE name = 'The North York Moors');

-- ---------------------------------------------------------------------------------------------
-- 5. Renames.
-- ---------------------------------------------------------------------------------------------
-- Northumberland now carries seven Tyneside and Wearside sites. To a user in South Shields or
-- Sunderland a region called "Northumberland" is not merely imprecise, it reads as somewhere
-- else, so the name follows the roster. Three County Durham sites go unnamed by this; two of
-- those (Houghall, Hardwick Hall) are woodland-only and one (Low Barns) is wildlife-only, so
-- exactly one — Wharton Park — is an unnamed voting member. "North East England" would be the
-- fully accurate name and is the alternative if that one bothers you; it costs the evocative one.
--
-- DROP THIS SECTION if you would rather keep the book names. Nothing below section 6 depends on
-- the rename beyond the cache keys, which are listed by old name and stay correct either way.
UPDATE regions SET name = 'Northumberland & Tyneside' WHERE name = 'Northumberland';
UPDATE regions SET name = 'North York Moors & Coast'  WHERE name = 'The North Yorkshire Coast';

-- ---------------------------------------------------------------------------------------------
-- 6. Retire the emptied regions — disabled, not deleted.
-- ---------------------------------------------------------------------------------------------
-- model_test_result.region_id is a NOT NULL FK to regions(id) recording which region a historical
-- A/B/C model comparison covered. Deleting these rows would either violate that constraint or
-- falsify the record of a run that really did evaluate Teesdale. They hold no locations after the
-- sections above, so they cannot appear in a briefing regardless of this flag.
UPDATE regions SET enabled = FALSE
WHERE name IN ('Teesdale', 'Tyne and Wear', 'The North York Moors');

-- ---------------------------------------------------------------------------------------------
-- 7. Both briefing caches key on region NAME, and both are now stale.
-- ---------------------------------------------------------------------------------------------
-- daily_briefing_cache holds one row whose JSON payload carries region names inline. Left in
-- place it would serve a phantom "Teesdale" region.
--
-- ⚠️ THERE IS NO SCHEDULED REBUILD TO WAIT FOR. V103 retired the standalone daily_briefing cron;
-- the briefing is now rebuilt only in the BRIEFING phase at the TAIL of a pipeline cycle. So
-- between this migration and the next cycle (nightly ~01:00 UTC / intraday 14:00 UTC),
-- GET /api/briefing has nothing to serve and the Plan tab reads as still preparing.
-- Trigger POST /api/briefing/run straight after deploying rather than waiting.
DELETE FROM daily_briefing_cache;

-- cached_evaluation is keyed "regionName|date|targetType" with a UNIQUE constraint on cache_key,
-- so these rows are deleted rather than rewritten: rewriting 'Teesdale|...' to
-- 'The Yorkshire Dales|...' would collide with the Dales row that already exists for that date.
--
-- Only the five regions that were merged away or renamed are cleared. Those rows are orphaned —
-- no future lookup can generate their key. The Yorkshire Dales and The Lake District rows are
-- deliberately KEPT even though each gained locations: the newcomers simply read as unscored
-- until the next batch, which is cheaper and less disruptive than discarding scores already paid
-- for across 113 locations.
DELETE FROM cached_evaluation
WHERE region_name IN ('Teesdale', 'Tyne and Wear', 'The North York Moors',
                      'Northumberland', 'The North Yorkshire Coast');

-- ---------------------------------------------------------------------------------------------
-- 8. Post-flight. Scoped to THIS migration's own work.
-- ---------------------------------------------------------------------------------------------
-- The obvious check — "no location anywhere has a NULL region_id" — is wrong, and was here until
-- review caught it. A NULL region is a legal, supported state: the column is nullable (V32),
-- LocationEntity documents it as optional, LocationService.resolveRegion(null) returns null, and
-- the admin Add-location form *defaults* the region select to "— None —". A pre-existing
-- region-less row that this migration never touches would abort Flyway, fail the Spring context,
-- and brick every subsequent restart until someone hand-edited Postgres — a boot-blocker created
-- by an assertion about data the migration has no opinion on.
--
-- So assert only what the sections above actually claim to have done.
DO $$
DECLARE stranded INT;
BEGIN
    -- The two named moves landed somewhere.
    SELECT COUNT(*) INTO stranded FROM locations
    WHERE region_id IS NULL
      AND name IN ('Stanley Ghyll Force', 'Hardwick Hall Country Park');
    IF stranded > 0 THEN
        RAISE EXCEPTION 'V137: % named move(s) left a location with no region', stranded;
    END IF;

    -- The three retired regions are empty, so section 6 disabled nothing still in use.
    SELECT COUNT(*) INTO stranded FROM locations l
    JOIN regions r ON r.id = l.region_id
    WHERE r.name IN ('Teesdale', 'Tyne and Wear', 'The North York Moors');
    IF stranded > 0 THEN
        RAISE EXCEPTION 'V137: % location(s) still in a retired region', stranded;
    END IF;
END $$;
