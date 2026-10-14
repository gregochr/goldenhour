# P1: wasted trips, the canvas rule and far decks, measured on the history we already have

**Status:** draft, 2026-10-14, before adversarial review. Nothing built.
**Parent:** `far-cloud-lid-plan.md` §6 P1 (pre-registered hypotheses H1–H5). This plan builds P1 and
adds two read-side analyses the parent plan does not have. Where it changes a P1 rule it says so.
**Owner's requirement (2026-10-14):** "If I'd have woken up at 4am and had that result I'd not use
the app again." A confident go that fails (a wasted trip) is the error that loses a user. A missed
good sky costs much less. Every decision rule below weighs the two accordingly.

---

## 1. What the Angel case already tells us

- **The 4★ is the prompt's own rule, followed exactly.**
  - `PromptBuilder.java:120-121`: "IDEAL scenario: solar horizon low cloud <20% AND mid cloud <50%,
    with high cloud present on either horizon as canvas. Score fiery_sky 70-90, rating 4-5."
  - The 14:22 run had cone low 7, mid 24, high 91. Overhead read 0% at every layer, and the
    antisolar side carried no canvas either.
- **P0 replay (N = 20 per variant).**
  - On the 14:22 prompt, Sonnet 5.5 gave 4★ 20 times out of 20, Sonnet 4.6 gave 3★ 20/20, and Haiku 4.5
    gave 3★ 17/20 and 4★ 3/20.
  - Setting the cone high to 0% drops every model to 3★. Raising cone low from 7% to 29% changes
    nothing. No variant reaches 2★.
- **The 91% deck at 113 km was not the bank that hid the sun** (parent §2(A), consequence 3).
  - A deck at 8–12 km at that range stands 3.5–5° above the horizon, and the sun shone underneath it.
  - Such a deck glows after sunset only through a clear slot further sunward (parent §2(C)). That slot
    is exactly the region nothing samples.

So there are two faults, and they are linked:
- **(F1) The canvas rule** credits a distant lid as a sure canvas, with nothing overhead to catch the
  light. That is what made the star 4 rather than 3.
- **(F2) The unmeasured sunward corridor** decides whether that lid can be lit at all, and whether the
  sun reaches the horizon. That is plausibly what made the sky a 2.

F1 is the cheaper fix and moves the star. F2 decides whether F1's credit is ever deserved. P1 measures
both from history, and does both before any prompt change.

## 2. What P1 will and will not establish

**It will:**
- size how often today's go ratings (4★+) rest on premises that ERA5 contradicts, especially for the
  Angel shape (part B);
- size how often the far deck occurs, and whether the forecast sees it (H1–H5, part C);
- reconstruct what lay along the Angel's sun line, by model (part A).

**It will not:**
- **prove that any sky looked good or bad.**
  - ERA5 is a reanalysis, a model, not an observation.
  - Its cloud physics overlaps with the forecast's, so agreement flatters both.
  - Only recorded outcomes measure beauty. A rule still needs the parent plan's prospective outcome
    arm (P4) before it changes a star.
- **settle P4's evidence bar for a star change.** Part B can, however, justify a confidence or wording
  change sooner, because neither is a star (§6, D-B).

## 3. The data we already hold, and its limits

- `forecast_evaluation`: every run's cloud inputs, one row per run, never pruned.
  - Its columns include the cone mean, antisolar, observer, far 226 km low (and, since V175/V176, far
    mid/high, dark hour and pressure levels), the model, the rating and the sky rating.
  - Batch-scored rows carry a rating only from about the end of August 2026 (V149,
    `prompted-row-persistence-plan.md`). The first job of the export is to measure that date (§5.1).
- `cloud_verification`: ERA5 per evaluation, at the same hour production read
  (`TimeSlotUtils.findBestIndex`).
  - It holds the cone mean, min and max of the low layer, the observer layers, and far 226 km low.
  - Far mid/high exist only for rows verified after V175 (13 Oct 2026).
  - It lags by 6 days.
- **Not held:** ERA5 mid/high at 226 km before V175, anything at 165 km or 320 km, the forecast's far
  mid/high before V175, and any pressure level before V176. Part C fetches these.

## 4. Unit, population and independence (pre-registered here)

- **A forecast**, for every part, is the **latest run before the event**:
  - the row with the greatest `forecast_run_at` that is less than `solar_event_time`;
  - excluding `batch_state = 'PENDING'` and `'ABANDONED'`;
  - over enabled-or-not, regioned, sky locations (the `hasColourTypes` SQL in §5.1).
  This is the forecast a reader saw at its last update.
- **A go** is a forecast with `rating >= 4`. When `sky_rating` is present, it is also reported with
  `sky_rating >= 4`, which removes the tide average (§7).
- **A prompted forecast** is one with `triage_reason IS NULL`, which is what the parent plan's 2% bar
  divides by.
- **The firing unit is region × date × event,** as the parent plan states.
  - Locations in a region share weather, so they are not independent.
  - A region-event fires a condition when **at least half** of its sky locations' forecasts meet it.
  - Its ERA5 truth is the **median** of those same locations' ERA5 values.
  - Every count is reported both per location and per region-event, so the reduction is visible.
- **Independence across regions on one evening is not assumed.**
  - Every interval is a 95% **date-clustered bootstrap** (resample dates, 2,000 draws, fixed seed).
  - The parent plan's "at least 80 independent firings" is read as at least 80 region-events spread
    over at least 25 distinct dates.
- **Excluded training cases:**
  - Angel of the North, 13 Oct 2026 sunset (location 64);
  - Low Hauxley, 4 Oct 2026 sunrise;
  - Copt Hill on 5 and 11 Mar 2026, sunset.
  They are reported separately in part A, and never counted.
- **Leads** are reported as T+0 and T+1 separately. T+2 and later are reported, but not used for any
  decision.
- **Sunrise and sunset** are reported separately, and sunrise first: it is the 4am alarm.

## 5. The three parts

### 5.1 The export (owner runs it once, read-only)

- One `SELECT` produces one CSV row per forecast (the §4 definition). The row carries:
  - every cloud column listed in §3;
  - `cloud_verification`'s columns;
  - the location's name, coordinates, region and types;
  - the **previous run's** rating for the same slot, from the latest earlier non-PENDING row, which
    gives the rating's movement.
- It also emits a summary: `min(forecast_run_at) WHERE batch_state = 'SCORED'`, and the row counts by
  month, lead and event.
- Run on the Docker host using the repo's own pattern:
  `docker exec goldenhour-db psql -U goldenhour -d goldenhour -X -v ON_ERROR_STOP=1 -c "\copy (...) TO STDOUT WITH CSV HEADER" > p1.csv`
- No write, no lock beyond a read.
- The CSV holds location names and coordinates, no user data. It is not committed.

### 5.2 Part B: wasted-trip backtest (no network; runs on the CSV in seconds)

ERA5 can say whether a go's **premises** held: the light got through, and there was something to
light. It cannot say the sky was beautiful. So part B classifies each go by its premises.

**Premise checks against ERA5** (pre-registered; thresholds are the prompt's own where it has one):

| Verdict | Definition |
|---|---|
| **Contradicted** | Any of: ERA5 cone low ≥ 60 (the prompt's BLOCKED line); ERA5 far 226 km low ≥ 60 (a low bank on the line); ERA5 has no canvas anywhere (observer mid and high < 20 and cone mid and high < 20). |
| **Supported** | ERA5 cone low < 40, far low < 60, and some canvas (observer or cone mid or high ≥ 20). |
| **Ambiguous** | Everything else. |

**Reported, each with a 95% clustered interval:**
1. The share of gos that are contradicted, by event, lead and model.
2. **The Angel shape.**
   - Definition: a go where forecast cone low is < 20, cone high is ≥ 60, and overhead plus antisolar
     mid and high are all < 20 (no near canvas).
   - Report how often it occurs, its contradicted share, and the same for gos **with** near canvas,
     as the comparison.
   - This sizes the canvas rule (F1).
3. **Raised gos.**
   - Definition: the final run rose to ≥ 4 from ≤ 3 on the previous run.
   - Report their contradicted share against gos that were already ≥ 4 on the previous run.
   - This tests the "don't raise on one run" idea.
4. For each of the candidate rules in §6 (D-B), the demotion table:
   - how many gos it would demote;
   - how many of those are contradicted, supported or ambiguous;
   - how many contradicted gos it leaves untouched.

### 5.3 Part C: H1–H5 sizing (network; owner runs it, resumable over days)

This is the parent plan's P1, unchanged in hypotheses and decision rules, except for the unit and the
intervals (§4).

**Sample.**
- Every prompted region-event whose forecast passes the cone gate (cone low < 20).
- Then a stratified random sample of at most 2,000 region-events (seed fixed and recorded), by month,
  event and lead.

**Points per sampled location.**
- On the date's `azimuth_deg`, 165 km, 226 km and 320 km.
- The production geometry, ported exactly from `GeoUtils.offsetPoint`: a spherical Earth of radius
  6,371 km, longitude not normalised.

**Reads.**
- **ERA5 archive:** low, mid and high at the three far points, at the production hour.
- **Previous Runs API, lead-matched:**
  - T+0 uses `previous_day0`, the latest; T+1 uses `previous_day1`.
  - It reads low, mid and high at the three far points and at the three cone points.
- **Historical Forecast API:** the six pressure levels at 226 km, for H5's sensitivity check only.

**Reconstruction check, per row.** The reconstructed cone low and 226 km low are compared with the
stored values. A row whose reconstruction differs by more than 10 pp on either is excluded, and
counted. If more than 30% of rows fail, part C's forecast side is void, as the parent plan requires.
The reasons for a gap are known:
- the 0.1° grid reuse;
- the run's issue hour, which the Previous Runs API resolves only to the day.

**Budget.**
- Requests carry up to 20 coordinates, with a 3 s pause between requests.
- Every response is cached on disk, keyed by URL, so a re-run spends nothing.
- A daily weighted-call budget (default 8,000, under Open-Meteo's free 10,000) stops the run cleanly.
  The next day it resumes from the cache.
- The run prints the calls made, the calls cached and its projection to completion.

**Statistics.** The parent plan's precision, recall, lift and observed-conditioned failure population
for H1–H5, per event and lead, with clustered intervals. Additionally, the share of **gos** that each
hypothesis's condition would catch, alongside its catch of contradicted gos from part B, so H1–H5
are judged on wasted trips and not only on cloud agreement.

### 5.4 Part A: the Angel case, reconstructed (network; a few hundred calls)

- **The sun line, every 20 km from 0 to 400 km**, on the 13 Oct azimuth, at 15:00, 16:00 and
  17:00 UTC.
  - Each point gets ERA5 low, mid and high.
  - Each point gets the forecast as issued the day before and on the day, from `best_match` and from
    each named model the Previous Runs API serves for the UK (at least `ukmo_seamless`,
    `ecmwf_ifs025`, `icon_seamless`; the probe lists what answers).
  - Pressure levels come from the Historical Forecast API.
- **Output:** a table and a plain-text "what was on the line" summary.
- **Owner's manual step, about 10 minutes:** look at Meteosat imagery for 13 Oct 15:30–16:45 UTC
  along the 256° bearing (EUMETSAT's public viewer, or any archive viewer). This is the only
  observation, rather than a model, in the whole plan.
- Part A is exploratory. It may suggest a mechanism, but it is a training case and decides nothing.

## 6. Decision rules

**Part C (far decks):** the parent plan's rules, unchanged:
- at least 80 region-events over at least 25 dates;
- precision at least 0.7, with a lift interval that excludes 1;
- an observed-conditioned failure population of at least 2% of prompted region-events;
- for H5 only, a lift interval above that of H1 ∪ H2.

Passing them earns P3 and P4. Failing them stops the far-point programme, and the reasons are
written into the parent plan.

**Part B (wasted trips):** new, and proposed for the owner (D-B):
- **D-B1. A candidate rule earns a P0 replay and a prompt-regression run** when its demotions are at
  least 60% contradicted, and it removes at least 25% of all contradicted gos at T+0 and T+1.
- **D-B2. A confidence or wording change** may ship on part B alone. It changes no star, so it needs
  no outcome arm.
  - It qualifies when the condition's contradicted share is at least twice that of other gos, with a
    lower interval bound above 1.5×.
  - Examples: a "provisional" mark, or "could be 2–4★".
- **D-B3. A star change** still needs P4's prospective outcome arm, with the arm's 20 events taken
  from the condition part B identified.

**The candidate rules part B scores**, each written as a filter on forecast columns so it needs no
model run:
- **R1 (canvas):** an IDEAL or rate-4 go with no near canvas (overhead and antisolar mid and high all
  < 20) is capped at 3.
- **R2 (no raise):** a go that rose from ≤ 3 on the previous run is held at the previous rating.
- **R3 (R1 ∪ R2).**
- **R4 (far low):** a go with forecast far 226 km low ≥ 60. This is only a counterfactual: `CLAUDE.md`
  forbids the far reading confirming, and the figure is reported so that the rule is seen measured.

## 7. Known weaknesses, stated before review

- ERA5 shares the forecast's cloud physics, so "contradicted" undercounts real failures and
  "supported" overcounts real successes.
- The premise checks ignore beauty factors the data cannot see: thin cirrus that stays pale, and
  terrain hiding the sun early.
- `rating` averages in tide at coastal sites. Results are also reported on `sky_rating` where present
  (since V164).
- The rated-history window may be only about six weeks (§3). Part B's intervals will be wide, and
  that will be said.
- A run's issue hour is coarser in the Previous Runs API than in production, so lead-matching is
  approximate.

## 8. Build (task cards for subagents)

Everything lives under `scripts/far-cloud-p1/`. It is Python 3 using the standard library only, with
no installs, and `unittest` tests that run offline against fixture JSON. No backend code changes.

| Card | Contents |
|---|---|
| T1 | `export.sql` and `README.md`: the §5.1 query, run commands, and how to copy the CSV. |
| T2 | `geometry.py` and `slots.py`: the ports of `offsetPoint`, the cone/far layout and `findBestIndex`, with parity tests against the Java tests' expected values. |
| T3 | `backtest.py` (part B) over the CSV, with the bootstrap, and fixture CSV tests. |
| T4 | `openmeteo.py` (cached, budgeted client for the archive, Previous Runs and Historical Forecast APIs), and `size.py` (part C) and `case.py` (part A), with fixture tests and a `probe` command that makes one live request of each kind. |
| T5 | Integration, an adversarial code review, PR. |

**Order:** T1 and T2 together, then T3 and T4, then T5. Part B is the first result the owner can
have, since it needs only the export.

## 9. Owner decisions

1. D-B1 to D-B3, the part B thresholds.
2. Whether part A's reconstruction should also run for the other two training cases.
3. After part B: which of R1, R2 or R3 to take to replay, if any qualifies.
