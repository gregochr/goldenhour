# Model disagreement: does "other models see cloud" predict the cloud `best_match` misses?

**Status:** draft, revised after adversarial review, 2026-10-31 (two read-only reviewers: study design,
and the P1 tooling it reuses; §7). Nothing built. Read-side only: no production code, no migration, no
Claude spend. One PR (scripts + this plan); the owner runs it.
**Trigger:** Angel of the North, 31 Oct 2026 sunset. Every stored mid and high reading (observer,
113 km cone, 226 km far point, all six pressure levels) was 0 in all five runs; the 12:38 summary said
"no mid or high cloud anywhere". The owner photographed broken altocumulus lit orange under a pale
high veil. Forecast 3★, which landed for the wrong reason.
**Reuses:** `scripts/far-cloud-p1/` (`openmeteo.py` cache/budget client, `geometry.py` cone points,
`slots.py` slot choice, `stats.py` intervals and block bootstrap, `export_forecasts.sql`).
**Feeds:** a later, separate decision on capturing a disagreement signal (data only), then on using it.
**A pass here is an upper bound** (§3): the capture plan must re-size the signal prospectively, at
production's real lead, before anything reaches a prompt.

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

Production's stored values equal UKMO's. Every other model had mid or high cloud somewhere on the line,
and they disagree with each other (Météo-France 99% low at 226 km). These are single spot values from a
short-lead rebuild, not the cone means production stores.

**The base rate, counted in runs.** Production, 2026-10-31: among forecasts with ERA5 verification
whose stored coned horizon read mid 0 and high 0, ERA5's coned horizon had mid or high ≥ 30 in **28.4%**
of sunrise runs (3,349 / 11,788) and **28.2%** of sunset runs (3,245 / 11,521). This counts every run, so
a slot forecast five times counts five times; §2's unit fixes that and `md_size.py` recomputes the base
rate per unit. Stored 0/0 means each layer's three-point cone sum was ≤ 2 (integer floor mean), not that
every point read 0.

---

## 2. Pre-registered question and decision rule

Written before any data is pulled. Thresholds are proxies, as in the far-cloud plans.

**Unit.** One row per **(location_id, target_date, target_type, days_ahead)**: the run with the greatest
`forecast_run_at` before `solar_event_time` (ties by `evaluation_id`), `batch_state` NULL or SCORED as in
P1, triaged rows kept (their stored cloud is still the forecast). The decision uses **days_ahead = 0
only**, so each slot counts once there. `md_size.py` prints row counts before and after deduplication.

**Population (P).** Units with a `cloud_verification` row and non-null `cv_horizon_mid_cloud`; SUNRISE or
SUNSET; stored `solar_mid_cloud` and `solar_high_cloud` **non-null and equal to 0**; stored
`solar_low_cloud < 60` (a blocked horizon cannot benefit from a canvas). Excluded from the decision and
shown only as worked examples: 31 Oct 2026 Angel of the North sunset (the trigger) and the training cases
named in `far-cloud-lid-plan.md` §1. A guard drops units whose recomputed centre cone point differs from
`cv_horizon_sample_lat/lon` by more than 0.01° (a location moved after the run); the count is reported.

**Outcome (Y).** ERA5 coned horizon `max(cv_horizon_mid_cloud, cv_horizon_high_cloud) ≥ 30` **and**
`cv_horizon_low_cloud < 60`. Descriptive variants, no decision: `Y_mid` (mid ≥ 30, the altocumulus
case), `Y50` (≥ 50), and Y split by stored rating (≤ 2 vs ≥ 3). Owner outcome ratings, where any exist,
are shown against S2 with their (tiny) n.

**Model value.** For model m, at the production slot (`slots.find_best_index`, §4 conversions), over the
three cone points (`geometry.cone_points`, az−15, az, az+15 at 113 km):
`M_m = max(sum(mid) // 3, sum(high) // 3)` on integer values, matching the stored and ERA5 arithmetic.
**m votes only if all three cone points have non-null mid and high**; otherwise it is "not voting",
never 0. Test: mids (30, 30, 29) give 29, no vote.

**Signal.** `S2` = at least two of **ICON, GFS, Météo-France** have `M_m ≥ 30`, **with all three
voting**. Units with fewer than three voters are reported separately and never decide.

⚠️ **ECMWF is excluded from the decision signal.** ERA5 is ECMWF's reanalysis (IFS cycle 41r2) and
shares its physics, so IFS agrees with ERA5 for reasons that are not skill. It is fetched and reported
alone and as a fourth voter (`S2+E`), labelled optimistic. ICON and GFS are not expected to share ERA5's
structure.

**Null comparator.** `S_UK` = the **refetched** short-lead `ukmo_seamless` has `M ≥ 30`, and the same for
refetched `best_match`. These carry the same stitched lead as the other models, so any skill S2 shows
*over* them is model diversity, not lead (§3).

**Metrics.** The sample is self-weighting within event (§4 T2), so raw counts apply to the decision.
- **Precision** P(Y | S2); **separation** P(Y | S2) − P(Y | ¬S2); **recall** P(S2 | Y); **flag rate** P(S2).
- **Decision bounds**: 5-day moving-block bootstrap by calendar day (`stats.DEFAULT_BLOCK_DAYS`), 2,000
  draws, blocks resampled within month; 3- and 7-day blocks as sensitivity. Wilson intervals with Kish
  effective n are shown beside them, descriptively. No weighted float is ever passed to `stats.wilson`
  (test).
- Per model: P(Y | M_m ≥ 30) and each model's flag rate; `S1`, `S3`, `S2` without GFS (GFS over-produces
  high cloud), and **`S2-PL`**, the same vote from each model's `cloud_cover_700hPa/500hPa/300hPa`
  (pressure-defined, so consistent across models' differing layer boundaries). All descriptive.

**Decision (pooled sunrise + sunset, T+0, lead-fair population P′, §3).** Pass needs all of:
1. Precision's lower bound ≥ **50%**.
2. Separation P(Y|S2) − P(Y|¬S2), lower bound ≥ **+15 pp**.
3. Recall's lower bound ≥ **25%**.
4. Flag rate ≤ **40%**.
5. S2 beats the null comparators, **evaluated on full P** (in P′ they are below 10 by construction and
   can never fire). Per comparator (refetched UKMO, refetched `best_match`): if it reaches M ≥ 30 on
   fewer than 30 units it is **silent** and that part passes (short lead alone does not reproduce the
   cloud); otherwise precision(S2) − precision(comparator) needs a lower bound **> 0**. Fail if either
   part fails, else inconclusive if either is unassessable, else pass. (Revised during the build review,
   2026-10-31: the first wording made a silent comparator block every pass.)
6. Separation holds within month (Mantel–Haenszel pooled within-month difference, lower bound > 0), so S2
   is not merely tracking season.
7. Each event alone has a point precision ≥ 50% (consistency, not a second test).

**Minimum counts**, else **inconclusive**: ≥ 30 units in each of S2 and ¬S2, ≥ 10 distinct dates in S2,
≥ 20 alignment pairs (§3). Inconclusive is its own outcome: it does **not** trigger the fail branch.

**Outcomes.**
- **Pass** → write the capture plan (step 2: record the disagreement as data only, re-sized at real lead).
- **Fail** → record it here and in `architecture-decisions.md`; the next lever is the summary's wording
  alone ("model data shows no mid or high cloud" rather than "there is none"), sized on its own.
- **Inconclusive** → record it with the power table; the owner decides whether to extend the sample.

No threshold is retuned after seeing the data. Everything not in the decision list is descriptive.

**Baselines (descriptive).** Month × location climatology of Y; a stored-only predictor (any non-zero
stored observer, far or pressure-level mid/high); and P split into "zero at every stored point" (the
31 Oct shape) and the rest. If S2 adds nothing over the stored-only predictor, say so.

**Secondary (descriptive).** The same at the observer point (ERA5 `cv_observer_*`, no cone).

---

## 3. Lead: the asymmetry that could fake a pass

The historical-forecast API stitches each model's first hours, so it rebuilds roughly 0–6 h lead with no
data latency: close to analysis, which assimilates the same observations as ERA5. Production's "T+0"
is up to ~17 h (a 02:58 run for a 16:32 sunset, plus UKMO's own run age and latency). Comparing the two
would credit the comparators with lead, not skill.

- **Lead bands.** `lead_h = solar_event_time − forecast_run_at` (both in the export). Every metric is
  reported in bands < 6 h, 6–12 h, > 12 h.
- **Lead-fair population P′ (the decision population).** P′ = P ∩ {refetched `ukmo_seamless` M < 10 and
  refetched `best_match` M < 10}. In P′ the stored zeros are reproduced at the comparators' own lead, so
  every model is compared at the same stitched lead. Full P is reported beside it.
- **Alignment control, read first.** Per event and lead band: the share of P where refetched
  `best_match` M ≥ 30 (the meaningful gate on a population stored at 0; P1's median-|Δ| rule is near 0 by
  construction here and is reported beside it), with ≥ 20 pairs. **If that share exceeds 20% at T+0, no
  pass is possible on full P**; the decision stands on P′ alone, labelled so.
- **No fair T+1.** Open-Meteo's Previous Runs API does not serve `cloud_cover_low/mid/high` per layer
  (all null across five models, P1 probe 2026-10-14, `openmeteo.py` ~l.31). T+1 and T+2 are not
  analysed. The Single Runs API is probed (T0); if it serves the layers per model, a lead-fair T+1 is a
  follow-up, not this plan.
- A pass is an **upper bound** on what a capture at production run time would see.

---

## 4. Build (Sonnet writes; the owner runs the network steps)

New directory `scripts/model-disagreement/`, Python 3 standard library only. Scripts are named
**`md_probe.py`, `md_sample.py`, `md_fetch.py`, `md_size.py`** (P1 already has `probe.py` and
`size.py`, and P1's `tests/om_fake.py` puts P1 first on `sys.path`). P1's directory is added with
`sys.path.append` (never `insert(0)`), and P1 modules are imported only by unique names (`openmeteo`,
`geometry`, `slots`, `stats`). **P1's files are not edited.** Own `.gitignore`: `*.csv`, `out*/`,
`cache/`, `__pycache__/`, `!tests/fixtures/*.csv` (the export holds production data). No shell wrappers.

**Conversions.** `azimuth_deg` → `int(row['azimuth_deg'])` for `cone_points`. `solar_event_time` →
`datetime.strptime(v[:19], '%Y-%m-%dT%H:%M:%S')` as `find_best_index`'s `event`, with the API's
`YYYY-MM-DDTHH:MM` strings as `times`. Fetch date = `solar_event_time.date()` (UTC), as production's
verifier groups it.

- **T0 `md_probe.py`** (owner runs; a few calls). For one location and one past date:
  - one **single-model** historical-forecast request per model (`ukmo_seamless`, `best_match`,
    `icon_seamless`, `gfs_seamless`, `meteofrance_seamless`, `ecmwf_ifs025`) for low/mid/high and
    `cloud_cover_700hPa/500hPa/300hPa`; print which are served;
  - **real cost**: the owner reads Open-Meteo's usage before and after (dashboard or response headers);
    the local `budget.json` is a formula (`openmeteo.weight`, which ignores models), not a measurement;
  - whether the Single Runs API serves the layer bands per model (record only).
- **T1 Export.** Reuse `scripts/far-cloud-p1/export_forecasts.sql` unchanged. It carries every needed
  column; its only filters are SUNRISE/SUNSET and `target_date ≥ 2026-02-01`.
- **T2 `md_sample.py`.** Deduplicate to units (§2), filter to P, keep T+0. Draw a stratified random
  sample, **proportional to month within event** (self-weighting), fixed seed. Size it from T0's
  measured cost against the **observed ~600 weighted calls per day** (P1 §15), and print the expected
  number of fetch days. Rough guide before T0: 6 models × 3 cone points ≈ 18 calls per unit before
  grid-cell deduplication, so 600 units ≈ 18 days and 300 ≈ 9. **Print the power table** (simulated
  bound on precision at true precision 0.55, 0.65, 0.75 for the chosen n and a 20% flag rate) and stop
  for the owner's go before T3. The owner may instead supply a commercial Open-Meteo key; never paste it
  into a file or command line.
- **T3 `md_fetch.py`.** **One request per model** (`models=<m>`, single model), so response keys stay
  unsuffixed and `openmeteo`'s cache, served/not-served logic and resume work unchanged; a comma-separated
  `models` list is never sent through `Client.get` (it would never cache, `openmeteo.py` ~l.227). Group by
  date so one request carries many coordinates; deduplicate by (date, `geometry.grid_key`). Keep model
  and variable order fixed between T0 and T3 (the cache key is the full URL). Use P1's `cache/` as the
  cache directory so one budget counter covers both studies, run with `--budget` set to the observed
  daily limit, and never run both studies on the same UTC day. Observer points are fetched only if
  budget allows; they are secondary.
- **T4 `md_size.py`.** Alignment control first (§3), then §2 on P′ and P, writing `out/report.md`: the
  dedup counts, alignment table, decision table (pass / fail / inconclusive per criterion), lead bands,
  baselines, per-model and `S2-PL` tables, and the 31 Oct row on its own as a worked example.
- **Tests** (`scripts/model-disagreement/tests/`; a fake provider that switches on `models=` in the URL,
  since P1's `FakeOM` ignores it; no network): unit deduplication picks the latest pre-event run per
  (location, date, type, lead); floor-mean-then-max and the (30, 30, 29) case; a missing cone point means
  not voting; S2 needs three voters and excludes ECMWF; P′ and the 20% alignment gate; the decision rule
  on hand-built tables that pass, fail each criterion, and fall to inconclusive on each minimum count;
  sample proportions match month shares; `wilson` never receives a float; the moved-location guard.

**Gates (local only; no CI job runs Python or lints `scripts/` subdirectories).**
`cd scripts/model-disagreement && python3 -m unittest discover -s tests`, and the same in
`scripts/far-cloud-p1` (imports must not break P1). Changelog:
`changelog.d/YYYYMMDD-model-disagreement-sizing.md`, `### Added — …`.

**Runbook** `scripts/model-disagreement/README.md`, in P1's style: export, probe, sample (stop for go),
fetch (resumable, days), size; what to paste back.

---

## 5. Limits, stated up front

- **ERA5 is not observation.** A ~31 km reanalysis from IFS cycle 41r2. Part of the 28% is resolution:
  ERA5 sees cloud a 2 km UKMO point does not. The study sizes agreement with ERA5, not truth; outcome
  ratings are the only ground truth on colour.
- **The comparators run at a short, stitched lead** (§3). P′ and S_UK control it; they do not make the
  result prospective.
- **Layer bands differ by model**: GFS's "low" reaches ~640 hPa and can hold altocumulus that ERA5 calls
  mid; kilometre-scale point values (ICON-D2, AROME) are bimodal against ERA5's smooth cells. `S2-PL`
  and the per-model tables expose this; they do not remove it.
- **One threshold (30) on a 0–100 proxy**, not retuned after the fact.
- **Budget**: the free tier caps the sample; a fail on a small n may really be inconclusive, which is why
  the minimum counts and power table exist.
- **No effect on users** until later plans capture and then use a signal, each with its own review.

---

## 6. Out of scope

- Any production code, migration, prompt or rating change.
- Switching `best_match` to another model: the models disagree widely; disagreement, not a replacement,
  is the hypothesis.
- T+1 and T+2 (no lead-fair source), the far 226 km and antisolar points, and low cloud.
- Editing `scripts/far-cloud-p1/`.

---

## 7. Review record (2026-10-31)

Two read-only reviewers before any build: study design, and the P1 tooling. What changed:
- **Unit**: from runs to one row per (location, date, type, lead), latest pre-event run; T+0 only for
  the decision. The §1 base rate is labelled as counted in runs.
- **Lead**: lead bands, the lead-fair population P′, the null comparator S_UK and criterion 5; the
  alignment gate now has a consequence; the stale Previous Runs option replaced (P1 already found the
  layers unserved); a pass is stated as an upper bound.
- **Budget**: sized against the observed ~600 calls/day, not 8,000; real cost read from Open-Meteo, not
  the local formula; expected days printed and a stop for the owner's go.
- **Statistics**: separation against ¬S2 replaces "minus base rate" (overlapping groups); 5-day blocks;
  self-weighting sample so intervals take raw counts; minimum counts and an inconclusive outcome; a
  power table; the within-month criterion; climatology and stored-only baselines.
- **Outcome**: tied to sunsets that could use a canvas (stored and ERA5 low < 60); `Y_mid`, `Y50` and
  rating splits descriptive.
- **Arithmetic**: integer floor-mean then max, three-point voting, non-null zeros only.
- **Tooling**: one request per model (multi-model responses never cache in P1's client); distinct
  script names and `sys.path.append`; own `.gitignore`; shared cache directory and budget; no CI covers
  this directory; stated conversions; moved-location guard.
