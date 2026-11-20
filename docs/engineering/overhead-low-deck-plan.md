# Overhead low deck with a clear horizon: does the forecast ignore the layer that decides the evening?

**Status:** revised after adversarial review, 2026-11-20 (§7). **Measurement only.** Nothing in production
changes. Phase A is a free sizing query and a fifth class (OL) on the blind daily protocol list; a prompt rule
is not drafted unless §4's pre-registered gate passes. The owner pre-approved any later replay spend
(2026-11-19); that does not bypass the gate.

---

## 1. Trigger: two sunsets seen from beside Copt Hill, opposite errors

Owner at Copt Hill. The 16 Nov forecast is the **Angel of the North's** 12:08 UTC T+0 run (~12 km NW, the
record queried that day; overhead mid/high not queried, Copt Hill's own run not looked at). 20 Nov is Copt
Hill's own 13:32 UTC T+0 run.

| | 16 Nov sunset (Angel's run) | 20 Nov sunset (Copt Hill's run) |
|---|---|---|
| Overhead low / mid / high | **88** / not queried | **81** / 5 / 0 |
| Horizon 113 km low / mid / high | 0 / 0 / 100 | 0 / 0 / 0 |
| Far 226 km low | 0 | 9 |
| Forecast | **4★** (a full high canvas over a clear gap) | **2★**, fiery 15 ("no mid or high cloud to catch colour") |
| Owner | **1★**, flat grey lid | **3★**, broken low cloud lit orange and pink from below |

Two sunsets, one place, opposite signs. A trigger, not evidence.

## 2. Why it matters, and the physics

- The prompt grades low cloud only as a blocker at the horizon (`PromptBuilder` ~l.119–125); fiery needs
  mid/high cloud (~l.262–263); overhead low cloud is subordinate to the directional readings (~l.173–176). So an
  overhead low deck neither earns colour (20 Nov) nor costs it (16 Nov).
- **Physics** (`far-cloud-lid-plan.md` §2 (C)): a deck overhead can be lit from below only **after sunset**
  (before sunrise), for about **8–12 minutes** (until the sun's depression δ ≈ √(2b/R), ≈ 1.0–1.4° for a 1–2 km
  base), and only through a sunward slot clear of low cloud out to ≈ 2Rδ ≈ 220 km. Before sunset it is lit from
  above or on its sides. **Under a canvas it also hides the canvas**: a 1 km deck reaching ≈ 15 km or more
  sunward occludes cloud at 3–4° elevation (16 Nov's likely mechanism).
- The real variable is the deck's **sunward extent and gappiness**, which overhead % plus two sunward points
  (113 and 226 km) sample only crudely; nothing samples the 0–100 km where the deck itself sits.
- Related plans: `broken-low-canvas-plan.md` (low cloud at the horizon, overhead < 60) and
  `far-shut-canvas-plan.md` (the far corridor).

## 3. Phase A (read-only, free)

**A1. Sizing.** `docs/engineering/cost-queries/overhead-low-deck-sizing.sql` (tested on PostgreSQL 16): one slot
per (location, date, event), the latest Sonnet T+0/1 run before the event, chosen before any filter; a triaged
latest run gives no class (it is not replaced by an older one). Classes, all with cone low < 20, far low < 50,
not `[BUILDING]`, triage clear, `sky_rating` present: **OL-canvas** (overhead low ≥ 60, mid/high canvas ≥ 50),
**OL-bare** (overhead low ≥ 60, no canvas), and descriptive controls **CTL-canvas** / **CTL-bare** (overhead
low < 30). It reports size, stars, fiery, ERA5 overhead and horizon, per-location frequency at T+0, and all admin
outcome ratings (descriptive, **not** protocol evidence).

**A2. Blind protocol class OL** on `scripts/far-cloud-p1/bl_today.sql`: cone low < 20, far low < 50, overhead
low ≥ 60, plus the list's common conditions. Checked after BL and CL (which need overhead < 60, so never
overlap) and before FS and FO (so a low-deck evening is never an FO control; FS is disjoint, far ≥ 80). Output
stays blind: place, event, UK time, stars. **OL protocol evidence starts on the day this PR merges**
(`--protocol-start` = merge date), which excludes the 16 and 20 Nov triggers.

## 4. Decision (fixed now)

**Sunsets only** decide (both triggers are sunsets; sunrise OL slots are listed and reported descriptively).
Protocol ratings only (`by_admin`, listed slots, on or after the protocol start, "didn't see it" excluded);
classes from stored values afterwards.

**Comparison: each arm against its own clean control, at matched forecast stars** (strata = the star the reader
saw, weighted per stratum as `bl_size.py` does), on signed error (forecast − outcome):
- **Arm C:** OL-canvas vs **FO** (canvas, far open, overhead < 60).
- **Arm B:** OL-bare vs **CL** (no canvas, overhead < 60).

**When:** once **each arm** has **≥ 8** protocol sunsets in matched strata spanning **≥ 4 five-day blocks**, or at
the ceiling date **2027-03-31**, whichever is first. At the ceiling an arm below n = 8 is **inconclusive**.

**Pass, per arm** (one-sided, 2.5% per arm, i.e. Bonferroni over the two arms; 5-day block bootstrap; more than
5% of draws undefined means waiting):
- Arm C passes when OL-canvas − FO **≥ +0.5★** and its 2.5% bound is **> 0** (over-forecast under a deck with a
  canvas above).
- Arm B passes when OL-bare − CL **≤ −0.5★** and its 97.5% bound is **< 0** (under-forecast under a bare deck).
- The absolute errors (e.g. ±1★) are reported, descriptive only.

**ERA5 check, per arm** (protocol slots with ERA5 available, at least 6): ERA5 overhead low ≥ 60 in at least half
the OL-canvas slots; ≥ 40 in at least half the OL-bare slots (a broken deck averages lower over a ~31 km cell).

**Selection check:** listed-but-unwatched OL sunsets are counted per arm; a skip rate above 50% in either arm
makes that arm inconclusive.

Pass (either arm) → a plan for a rule, starting from the hard part: telling a deck's sunward extent and gaps
from stored inputs. Fail or inconclusive → record it here and in the ledger, and close.

Tooling when the counts are near: a sibling `ol_size.py` reusing `bl_size.py`'s ratings join and
`stats.bootstrap_counts_ex`.

## 5. Limits

- Two triggers, one place, one observer.
- **Blindness is weaker here**: on an OL afternoon the deck is visible on site, the rater wrote the hypothesis,
  and the list prints the forecast stars; the selection check above is the guard.
- ERA5 overhead low is a ~31 km cell average at the hour **before** sunset (after sunrise), not the after-glow
  window: it confirms a deck's presence, not its state when lit.
- Sunward extent and gappiness, the real variables, are not stored.
- The list has no model filter (A1 does: Sonnet only); near-term production is Sonnet, so this rarely matters.

## 6. Out of scope

Any prompt change before §4 passes; low cloud at the horizon (broken-low plan); the far corridor (far-shut plan).

---

## 7. Review record (2026-11-20)

One read-only reviewer. Changes from the draft:
- **Decision rebuilt**: per-arm clean controls (C vs FO, B vs CL) at matched forecast stars instead of a pooled
  baseline (regression to the mean and opposing class biases); ≥ 8 per arm over ≥ 4 blocks with a 2027-03-31
  ceiling; Bonferroni; sunsets only; per-arm ERA5 gate (≥ 60 canvas, ≥ 40 bare); skip-rate check.
- **Far-shut confound**: FS rows with overhead low ≥ 60 are reported separately and excluded from far-shut's
  decision (amendment in `far-shut-canvas-plan.md` §5).
- **Physics corrected**: underlighting only after sunset for ~8–12 min; 16 Nov is likely occlusion.
- **Query**: triage and `sky_rating` handled inside the class (no older run stands in); T+0 by `days_ahead`;
  outcome section labelled descriptive.
- **Trigger table** labelled honestly (Angel's run for 16 Nov); protocol start = merge date.
