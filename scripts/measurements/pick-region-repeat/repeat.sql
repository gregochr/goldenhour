-- Q5 — do the forecast's two picks name the same region, and what would forcing them apart cost?
-- docs/engineering/map-landing-plan.md §6 Q5 (DECIDED 2026-09-11) · §4 #2 · README.md beside this file.
--
-- ⚠️ The statements below are BYTE-IDENTICAL to the ones run against production Postgres 17 on
-- 2026-09-11. They are deliberately dense: a long, well-spaced version was the first draft, and a
-- terminal mangled it on paste. What each name means is spelled out in README.md, so a re-run and
-- the recorded result can never be two different queries.
--
--   docker exec -i goldenhour-db psql -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 < repeat.sql
--
-- s = solar snapshot rows · w = each window's top region · c = chronological order (for
-- `rendered`) · k = pick rank · p = the two picks · alt = the best DIFFERING window, i.e. what a
-- both-differ rule would pick instead. It never writes to your data: its only object is one
-- TEMP VIEW, dropped at the end.

CREATE TEMP VIEW q5 AS
WITH s AS (SELECT briefing_generated_at b, evaluation_date d, target_type ev, region_name r, mean_rating m FROM briefing_region_snapshot WHERE target_type IN ('SUNRISE','SUNSET') AND mean_rating IS NOT NULL),
w AS (SELECT b,d,ev,r,m FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY b,d,ev ORDER BY m DESC, r) rk FROM s) x WHERE rk=1),
c AS (SELECT *, ROW_NUMBER() OVER (PARTITION BY b ORDER BY d, CASE ev WHEN 'SUNRISE' THEN 0 ELSE 1 END) ch FROM w),
k AS (SELECT *, ROW_NUMBER() OVER (PARTITION BY b ORDER BY m DESC, d, CASE ev WHEN 'SUNRISE' THEN 0 ELSE 1 END) pr FROM c WHERE ch<=6),
p AS (SELECT t.b, t.r best_r, t.m best_m, a.r also_r, a.m also_m FROM k t LEFT JOIN k a ON a.b=t.b AND a.pr=2 WHERE t.pr=1),
alt AS (SELECT p.b, MAX(k.m) diff_m FROM p JOIN k ON k.b=p.b AND k.pr>1 AND k.r<>p.best_r GROUP BY p.b)
SELECT p.*, alt.diff_m, (p.also_m IS NOT NULL AND p.also_m>=3.0 AND p.best_m-p.also_m<=0.5) awarded, (p.also_r IS NOT NULL AND p.also_r=p.best_r) same, (alt.diff_m IS NOT NULL AND alt.diff_m>=3.0 AND p.best_m-alt.diff_m<=0.5) diff_ok FROM p LEFT JOIN alt ON alt.b=p.b;
SELECT COUNT(*) builds, COUNT(*) FILTER (WHERE awarded) two_picks, COUNT(*) FILTER (WHERE awarded AND same) same_region, ROUND(100.0*COUNT(*) FILTER (WHERE awarded AND same)/NULLIF(COUNT(*) FILTER (WHERE awarded),0),1) pct_same, COUNT(*) FILTER (WHERE awarded AND same AND diff_ok) forcing_finds_one, COUNT(*) FILTER (WHERE awarded AND same AND NOT diff_ok) forcing_silence, ROUND(AVG(also_m-diff_m) FILTER (WHERE awarded AND same AND diff_ok),2) avg_given_up FROM q5;
SELECT b build, best_r, best_m, also_r, also_m, awarded, same, diff_m, diff_ok, CASE WHEN awarded AND same AND diff_ok THEN ROUND(also_m-diff_m,1) END given_up FROM q5 ORDER BY b;
DROP VIEW q5;
