-- ┌───────────────────────────────────────────────────────────────────────────────────────────┐
-- │ Q5 — how often do the forecast's TWO PICKS name the SAME REGION, and what would forcing   │
-- │      them to differ actually cost?  (docs/engineering/map-landing-plan.md §6 Q5, §4 #2)   │
-- └───────────────────────────────────────────────────────────────────────────────────────────┘
--
-- Run on the PRODUCTION host (Postgres 17). It never writes to your data: the only object it
-- creates is one TEMP VIEW, session-scoped and dropped at the end, so the reconstruction below
-- has ONE definition instead of being pasted twice for the two reports.
--
--   docker exec -i <pg-container> psql -U <user> -d <db> -f repeat.sql
--
-- ─── Why this table ─────────────────────────────────────────────────────────────────────────
-- `daily_briefing_cache` is a SINGLE-ROW table (id = 1, upserted every build), so it holds only
-- the latest briefing and can answer a frequency question for exactly one build. V144's
-- `briefing_region_snapshot` is the run-to-run sink: one row per region x date x event PER BUILD,
-- carrying `mean_rating` — the voting-slot region mean that `PlanWindowProjector` ranks picks on
-- (`Draft::averageRating`), which is why this reconstruction can use it directly rather than
-- re-deriving a mean from locations. Retention is 90 days; the table has existed since
-- 2026-08-19, so early runs of this query have a proportionally shorter window.
--
-- ─── ⚠️ What this models, and what it does NOT ──────────────────────────────────────────────
-- This is a RECONSTRUCTION of `selectPicks` in SQL, not the real thing, and the difference is
-- worth stating before any number here is quoted:
--
--   MODELLED EXACTLY   · the window's own top region = argmax(mean_rating) over that (date,event)
--                      · BY_PICK_RANK — window average DESC, then chronology
--                      · AlsoGoodFloor — candidate >= 3.0 AND within 0.5 of the top (both inclusive)
--                      · exactly two picks, never three
--
--   APPROXIMATED       · `rendered` (PlanRenderLimits.MAX_VISIBLE_EVENTS = 6) is taken here as the
--                        six earliest solar windows in the build. The real filter is the set of
--                        windows the rail actually drew, which also drops elapsed windows — so this
--                        can admit a window production had already retired.
--                      · BY_PICK_RANK's tie-break is the window's earliest EVENT TIME; this uses
--                        (date, SUNRISE before SUNSET), which orders identically but cannot split
--                        an exact tie the way a clock time would.
--
--   NOT MODELLED       · `candidate() != null`, which requires a USABLE Claude gloss headline on
--                        the top region. `briefing_region_snapshot` stores no gloss, so a window
--                        whose top region had none is eligible here and was not in production.
--                        This can only ADD eligible windows, so `also_awarded` here is an UPPER
--                        bound and `same_region` is measured over a slightly larger population.
--
-- If the headline fraction lands near a decision boundary, do not split it with this instrument —
-- drive the real `PlanWindowProjector` instead.
--
-- ═══════════════════════════════════════════════════════════════════════════════════════════

CREATE TEMP VIEW q5_verdict AS
WITH solar AS (
    -- Night carries no pick at all (§6 Q1), and a null mean means "nothing that votes was scored"
    -- — a value, never a zero, so it must not enter an argmax.
    SELECT briefing_generated_at AS build, evaluation_date AS d, target_type AS ev,
           region_name, mean_rating
    FROM   briefing_region_snapshot
    WHERE  target_type IN ('SUNRISE', 'SUNSET')
      AND  mean_rating IS NOT NULL
),
win_all AS (
    -- One Draft per window, naming its own top region — the shape `candidate(top, …)` builds.
    -- ⚠️ ROW_NUMBER rather than Postgres' `DISTINCT ON`, deliberately: the window-function form
    -- runs unchanged on SQLite, which is what lets this file be EXECUTED against fixtures on a
    -- machine with no Postgres (see README — the logic is tested, the dialect is standard).
    SELECT build, d, ev, region_name AS top_region, mean_rating AS avg_rating,
           ROW_NUMBER() OVER (PARTITION BY build, d, ev
                     ORDER BY mean_rating DESC, region_name) AS region_rank
    FROM   solar
),
win AS (
    SELECT build, d, ev, top_region, avg_rating FROM win_all WHERE region_rank = 1
),
rendered AS (
    SELECT *, ROW_NUMBER() OVER (PARTITION BY build
              ORDER BY d, CASE ev WHEN 'SUNRISE' THEN 0 ELSE 1 END) AS chrono
    FROM   win
),
ranked AS (
    SELECT *, ROW_NUMBER() OVER (PARTITION BY build
              ORDER BY avg_rating DESC, d, CASE ev WHEN 'SUNRISE' THEN 0 ELSE 1 END) AS pick_rank
    FROM   rendered
    WHERE  chrono <= 6
),
picks AS (
    SELECT b.build,
           b.top_region AS best_region, b.avg_rating AS best_avg,
           a.top_region AS also_region, a.avg_rating AS also_avg
    FROM      ranked b
    LEFT JOIN ranked a ON a.build = b.build AND a.pick_rank = 2
    WHERE     b.pick_rank = 1
),
alt AS (
    -- What option (b) "skip to a differing region" would actually find, within the same six.
    SELECT p.build, MAX(r.avg_rating) AS best_differing_avg
    FROM   picks p
    JOIN   ranked r ON r.build = p.build AND r.pick_rank > 1
                   AND r.top_region <> p.best_region
    GROUP  BY p.build
),
verdict AS (
    SELECT p.*,
           a.best_differing_avg,
           (p.also_avg IS NOT NULL AND p.also_avg >= 3.0
              AND p.best_avg - p.also_avg <= 0.5)                   AS also_awarded,
           (p.also_region IS NOT NULL AND p.also_region = p.best_region) AS same_region,
           (a.best_differing_avg IS NOT NULL AND a.best_differing_avg >= 3.0
              AND p.best_avg - a.best_differing_avg <= 0.5)         AS differing_would_qualify
    FROM      picks p
    LEFT JOIN alt a ON a.build = p.build
)
SELECT * FROM verdict;

-- ── 1. HEADLINE ────────────────────────────────────────────────────────────────────────────
\echo '── 1. Headline ────────────────────────────────────────────────'
SELECT COUNT(*)                                                          AS builds,
       COUNT(*) FILTER (WHERE also_awarded)                              AS with_two_picks,
       COUNT(*) FILTER (WHERE also_awarded AND same_region)              AS same_region,
       ROUND(100.0 * COUNT(*) FILTER (WHERE also_awarded AND same_region)
             / NULLIF(COUNT(*) FILTER (WHERE also_awarded), 0), 1)       AS pct_same_region,
       -- Of the same-region builds, how many could be forced to differ AT ALL without
       -- dropping below AlsoGoodFloor — i.e. how often option (b) yields a pick vs SILENCE.
       COUNT(*) FILTER (WHERE also_awarded AND same_region
                          AND differing_would_qualify)                   AS forcing_finds_one,
       COUNT(*) FILTER (WHERE also_awarded AND same_region
                          AND NOT differing_would_qualify)               AS forcing_yields_silence,
       -- The rating a forced second pick would give up, averaged over the cases where one exists.
       ROUND(AVG(also_avg - best_differing_avg) FILTER
             (WHERE also_awarded AND same_region AND differing_would_qualify), 2)
                                                                         AS avg_rating_given_up
FROM   q5_verdict;

\echo ''
\echo '── 2. Per build ───────────────────────────────────────────────'
SELECT build, best_region, best_avg, also_region, also_avg,
       also_awarded, same_region, best_differing_avg, differing_would_qualify,
       -- NULL, not 0, wherever the question does not arise: no second pick at all, the picks
       -- already differ, or forcing would fall below AlsoGoodFloor (which is SILENCE, not a
       -- rating drop). A 0 in this column means "forcing costs nothing here" and must not be
       -- reachable by the cases where nothing is given up because nothing is offered.
       CASE WHEN also_awarded AND same_region AND differing_would_qualify
            THEN ROUND(also_avg - best_differing_avg, 1) END AS rating_given_up_if_forced
FROM   q5_verdict
ORDER  BY build;

DROP VIEW q5_verdict;
