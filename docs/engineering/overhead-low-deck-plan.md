# Overhead low deck with a clear horizon: does the forecast ignore the layer that decides the evening?

**Status:** draft, 2026-11-20. **Measurement only.** Nothing in production changes. Phase A is a free sizing
query and a fifth class on the blind daily protocol list; a prompt change is not drafted unless Phase A's
pre-registered gate passes. The owner pre-approved any later replay spend (2026-11-19); that does not bypass
the gate.

---

## 1. Trigger: two evenings at Copt Hill, opposite errors

Both observed by the owner from beside Copt Hill.

| | 16 Nov sunset | 20 Nov sunset |
|---|---|---|
| Overhead low / mid / high (forecast) | **88** / 0 / 0 | **81** / 5 / 0 |
| Horizon 113 km low / mid / high | 0 / 0 / 100 | 0 / 0 / 0 |
| Far 226 km low | 0 | 9 |
| Forecast | **4★** (a full high canvas over a clear gap) | **2★**, fiery 15 ("no mid or high cloud to catch colour") |
| Owner | **1★**, flat grey lid | **3★**, broken low cloud lit orange and pink from below |

(16 Nov's numbers are the Angel of the North's T+0 run, the closest stored record to the owner's view; 20 Nov's
are Copt Hill's 13:32 run.)

In both, the decisive layer was the **low cloud overhead with a clear path to the sun**, and in both the
forecast's stars followed the mid/high canvas instead: too high when the deck was solid (16 Nov), too low when
it was broken and lit (20 Nov). n = 2; opposite signs; one place.

## 2. Why the prompt behaves this way

- Solar-horizon low cloud is graded only as a blocker (`PromptBuilder` ~l.119–125); fiery_sky "requires clouds
  (mid/high) to catch light" (~l.262–263). Overhead (observer) low cloud is subordinate to the directional data
  ("a clear observer point is irrelevant if the solar horizon is blocked; equally…", ~l.173–176).
- So an overhead low deck neither earns colour (20 Nov) nor costs it (16 Nov). Whether it lights or blocks
  depends on whether it is **broken or solid** and whether light can reach its underside, which the overhead
  percentage alone does not say.
- Related plans: `broken-low-canvas-plan.md` (low cloud as canvas, but at the horizon and with overhead low
  < 60, so these evenings are outside it) and `far-shut-canvas-plan.md` (far corridor). This plan is the
  near-field end of the same ray-path question.

## 3. Phase A (read-only, free)

**A1. Sizing.** `docs/engineering/cost-queries/overhead-low-deck-sizing.sql` (tested on PostgreSQL 16): one slot
per (location, date, event), latest Sonnet T+0/1 run before the event, chosen before filtering. Classes, all
with cone low < 20, far low < 50, not `[BUILDING]`:
- **OL-canvas**: overhead low ≥ 60 with a mid/high canvas ≥ 50 (16 Nov's shape);
- **OL-bare**: overhead low ≥ 60 without one (20 Nov's shape);
- controls **CTL-canvas** / **CTL-bare**: overhead low < 30, same split (30–59% left out to separate them).

It reports size, star distribution, fiery, ERA5's overhead low (does a deck really sit there?) and horizon,
per-location frequency, and readers' outcome ratings with the signed error (forecast − outcome), descriptive.

**A2. Blind protocol class OL** on `scripts/far-cloud-p1/bl_today.sql`: cone low < 20, far low < 50, overhead
low ≥ 60, not `[BUILDING]`, triage clear, stars present. It is checked **after BL and CL** (which need overhead
low < 60, so they never overlap) and **before FS and FO**; so a low-deck evening is never an FO (far-open)
control. `far-shut-canvas-plan.md` §5 A2 records the change (no protocol ratings existed yet). The output stays
blind: place, event, UK time, stars.

## 4. Decision point (fixed now)

Evaluate once, when **12 OL protocol evenings** exist spanning **at least 3 different calendar weeks** (no
date floor). Using protocol ratings only (`by_admin`, "didn't see it" excluded), with subtypes assigned from
stored values afterwards:
1. **Over-forecast under a solid-deck canvas**: OL-canvas mean signed error (forecast − outcome) **≥ +1★**,
   5-day block-bootstrap lower bound **> 0**; or
2. **Under-forecast under a lit broken deck**: OL-bare mean signed error **≤ −1★**, upper bound **< 0**;
3. and in either case the OL error exceeds the **same quantity on the other protocol evenings** (BL, CL, FS,
   FO) by at least 0.5★, so it is not a general bias of the owner's ratings;
4. and **A1's ERA5 check** shows a deck overhead (ERA5 overhead low ≥ 60) in at least half the OL slots.

Pass → a plan for a rule, with the hard part stated: telling a broken deck from a solid one from stored inputs
(candidates: overhead low 60–85 vs > 85, the trend, ERA5-style spread, low-cloud variability between
neighbouring cells). Fail or too few evenings → record and close; inconclusive is not a fail.

## 5. Limits

- n = 2, one place, one observer. The protocol is blind but the observer chooses when to watch.
- "Broken vs solid" is the real variable and is not stored; the percentage is a proxy.
- ERA5 overhead low is a ~31 km cell average.

## 6. Out of scope

Any prompt change before §4 passes; low cloud at the horizon (broken-low plan); the far corridor (far-shut plan).
