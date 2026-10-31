# Model disagreement: does "other models see cloud" predict the cloud `best_match` misses?

**Status:** draft, 2026-10-31. Nothing built. Read-side only: no production code, no migration, no
Claude spend. One PR (scripts + this plan); the owner runs it.
**Trigger:** Angel of the North, 31 Oct 2026 sunset. Every stored mid and high reading (observer,
113 km cone, 226 km far point, all six pressure levels) was 0 in all five runs; the 12:38 summary said
"no mid or high cloud anywhere". The owner photographed broken altocumulus lit orange under a pale
high veil. Forecast 3★, which landed for the wrong reason.
**Reuses:** `scripts/far-cloud-p1/` (`openmeteo.py` cache/budget client, `geometry.py` cone points,
`slots.py` slot choice, `stats.py` intervals and block bootstrap, `export_forecasts.sql`).
**Feeds:** a later, separate decision on capturing a disagreement signal (data only), then on using it.

---

## 1. What we know

**The case.** Open-Meteo's historical-forecast API, 31 Oct 16:00 UTC (owner, 2026-10-31):

| Model | Observer mid / high | Horizon 113 km mid / high | Far 226 km mid / high |
|---|---|---|---|
| `ukmo_seamless` | 0 / 0 | 0 / 0 | 0 / 0 |
| `icon_seamless` | 23 / 61 | 33 / 44 | 0 / 62 |
| `ecmwf_ifs025` | 3 / 65 | 10 / 26 | 0 / 50 |
| `gfs_seamless` | 0 / 100 | 0 / 22 | 0 / 79 |
| `meteofrance_seamless` | 100 / 0 | 68 / 0 | 100 / 0 |

Production's stored values equal UKMO's, consistent with `best_match` leaning on UKMO over the UK at
short range. Every other model had mid or high cloud somewhere on the line; they also disagree with
each other (Météo-France 99% low at 226 km).

**The base rate.** Production, 2026-10-31: among forecasts with ERA5 verification whose stored coned
horizon read mid 0 and high 0, ERA5's coned horizon had mid or high ≥ 30 in **28.4%** of sunrises
(3,349 / 11,788) and **28.2%** of sunsets (3,245 / 11,521). Overhead, ERA5 had ≥ 30 in 35% / 26%.

So "no mid or high cloud" is wrong more than one time in four. The question this plan answers is
whether the other models *tell us which time*.

---

## 2. Pre-registered question and decision rule

Written before any data is pulled. Thresholds are proxies, as in the far-cloud plans.

**Population (P).** `forecast_evaluation` rows with a `cloud_verification` row and non-null
`cv_horizon_mid_cloud`, `target_type` SUNRISE or SUNSET, stored `solar_mid_cloud = 0` and
`solar_high_cloud = 0` (coalesced). Sunrise and sunset reported separately.

**Outcome (Y).** ERA5 coned horizon `max(cv_horizon_mid_cloud, cv_horizon_high_cloud) ≥ 30`.

**Signal.** For each comparison model m, `M_m` = max(mid, high) at the solar horizon, as a **3-point
cone mean** (bearings az−15, az, az+15 at 113 km, `geometry.cone_points`) at the production slot
(`slots.find_best_index` on the stored `solar_event_time` and `target_type`). Mean each layer across
the cone first, then take the max, exactly as the stored and ERA5 values are built.

`S2` = at least two of **ICON, GFS, Météo-France** have `M_m ≥ 30`.

⚠️ **ECMWF is excluded from the decision signal.** ERA5 is ECMWF's reanalysis and shares its physics,
so IFS will agree with ERA5 for reasons that are not skill. ECMWF is still fetched and reported
(alone, and as a fourth voter in `S2+E`), labelled as the optimistic variant.

**Metrics** (Wilson intervals; day-block bootstrap via `stats.py` because rows on one date share
weather):
- **Precision** P(Y | S2), against the base rate P(Y) in the same stratum.
- **Recall** P(S2 | Y): the share of misses the signal would flag.
- **Flag rate** P(S2): how often the signal would fire inside P.
- Per model: P(Y | M_m ≥ 30), and the same for `S1` (any one) and `S3` (all three).

**Decision (both events, T+0 stratum, all must hold):**
1. Precision's lower 95% bound ≥ **50%** (a flagged slot has cloud more often than not).
2. Precision − base rate, Newcombe lower bound ≥ **+15 pp**.
3. Recall ≥ **35%** (point estimate).
4. Flag rate ≤ **40%** (otherwise it is a blanket hedge, not a signal).

Pass → write the capture plan (step 2: record the disagreement as data only). Fail → record the result
here and in `architecture-decisions.md`, and the next lever is the summary's wording alone
("model data shows no mid or high cloud" rather than "there is none"), a prompt change sized on its own.
`S1`, `S3` and the per-model numbers are **descriptive only**; no threshold is retuned after seeing them.

**Secondary (descriptive, no decision):** the same at the observer point (ERA5 `cv_observer_*`,
the location itself, no cone), because the owner's photo showed a veil overhead too.

---

## 3. Leads: the asymmetry that could fake a result

The historical-forecast API stitches each model's first hours, so it rebuilds about **T+0**. Production
rows run at T+0, T+1 and T+2. Comparing a T+0 rebuild of ICON against a T+1 production UKMO value
would credit ICON with a shorter lead, not better skill.

- **Primary stratum: `days_ahead = 0`.** The decision is made here only.
- **Alignment control, required before any result is read.** For the same sampled rows, also fetch
  `ukmo_seamless` and `best_match` from the historical API and compare with the stored coned mid and
  high. Report, per event: the share where stored = 0 and refetched `best_match` max(mid, high) ≥ 30,
  and the median absolute difference. **If more than 20% of T+0 rows disagree that way, the population
  is not "UKMO said 0" at the lead we think, and the result is labelled unreliable** (P1's alignment
  rule, `far-cloud-p1-plan.md` ~l.191, adapted).
- **T+1 and T+2** are reported, descriptive only, labelled "fetched lead shorter than production's".
- **Optional, if the probe (§4 T0) shows it is served:** Previous Runs API
  (`cloud_cover_mid_previous_day1` etc.) per model, giving a fair T+1. If it is not served per model,
  say so and skip.

---

## 4. Build (Sonnet writes; the owner runs the network steps)

New directory `scripts/model-disagreement/`, Python 3 standard library only, importing
`openmeteo`, `geometry`, `slots` and `stats` from `scripts/far-cloud-p1/` by path (no copies).
Outputs to `out/` and `cache/`, both git-ignored, as in P1.

- **T0 `probe.py`** (owner runs, ~10 weighted calls). For one location and one past date:
  - confirm the historical API serves `cloud_cover_low/mid/high` for each of `ukmo_seamless`,
    `icon_seamless`, `gfs_seamless`, `meteofrance_seamless`, `ecmwf_ifs025`, `best_match` in **one**
    request, and record the response key names (the multi-model response suffixes them; `best_match`
    came back as missing keys on 2026-10-31, so find its real key);
  - measure the weighted cost of a multi-model request (does each model count as a call?) by reading
    the budget counter before and after, and print it;
  - check whether the Previous Runs API serves the layer bands per model.
- **T1 Export.** Reuse `scripts/far-cloud-p1/export_forecasts.sql` unchanged (it already carries
  `days_ahead`, `azimuth_deg`, `solar_event_time`, location lat/lon, the stored cone values and every
  `cv_*` column). No new SQL.
- **T2 `sample.py`.** Filter to P (§2). Draw a stratified random sample by month × event × lead, fixed
  seed, sized from T0's measured cost so the fetch fits **3 days** of the default 8,000 daily budget;
  target ≥ 600 T+0 rows per event. Write the sample with its inverse-probability weights.
- **T3 `fetch.py`.** For each sampled row: the three cone points (and the observer point for the
  secondary), the target date, all models from T0, the three layer variables. Group by date so one
  request carries many coordinates (`openmeteo` already chunks, caches per coordinate and resumes).
  Deduplicate by grid cell as P1 does.
- **T4 `size.py`.** Builds `M_m`, `S1/S2/S3/S2+E`, runs the alignment control first, then §2's
  metrics with weights, bootstrap by calendar day (block 1 day), and writes `out/report.md`: the
  alignment table, the decision table (pass/fail per criterion, per event), the descriptive tables,
  and the 31 Oct Angel of the North row shown on its own as a worked example.
- **Tests** (`scripts/model-disagreement/tests/`, the P1 `om_fake.py` pattern, no network):
  cone-mean-then-max order; `S2` excludes ECMWF; the alignment rule's 20% gate; the decision rule on a
  hand-built table that passes and one that fails each criterion; sample weights sum to stratum sizes;
  a missing model value counts as "not voting", never as 0.
- **Runbook** `scripts/model-disagreement/README.md`, in P1's style: export, probe, sample, fetch
  (resumable), size; what to paste back.

Gate: `cd scripts/model-disagreement && python3 -m unittest discover -s tests` and the same for
`scripts/far-cloud-p1` (imports must not break P1). CI's ShellCheck covers any shell wrapper.
Changelog: `changelog.d/YYYYMMDD-model-disagreement-sizing.md`, `### Added — …`.

---

## 5. Limits, stated up front

- **ERA5 is not observation.** It is a reanalysis at ~31 km, and it is ECMWF's (§2). A 28% "miss" rate
  is partly ERA5 seeing cloud UKMO doesn't for resolution reasons. The result sizes agreement with
  ERA5, not truth; a photographer's outcome rating is the only ground truth on colour.
- **Historical-forecast is a T+0 rebuild**, not what production saw (§3).
- **One threshold (30) on a 0–100 proxy.** Not retuned after the fact.
- **Layer bands differ by model** (cloud-height boundaries are model-defined), so "mid" is not the
  same layer in every model. Max(mid, high) is used to blunt that.
- **No effect on users** until a later plan captures and then uses a signal, each with its own review.

---

## 6. Out of scope

- Any production code, migration, prompt or rating change.
- Switching `best_match` to another model: the case shows models disagreeing widely; disagreement,
  not a replacement, is the hypothesis.
- The far 226 km point and the antisolar point (descriptive extension later if §2 passes).
- Low cloud (`best_match` low vs ERA5 low is the far-cloud plans' territory).
