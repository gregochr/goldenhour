# P1: wasted trips, the canvas rule and far decks, measured on the history we already have

**Status:** revised after adversarial review, 2026-10-14. Tools built (`scripts/far-cloud-p1/`, code-reviewed). Exports done; Part B has run and the Part D set is built (selection run) on the owner's export. The live probe FAIL (4) on 2026-10-14, on Parts A/C assumptions; fixed and passing in #1166. §11 records what the API actually serves and what changed in Parts A and C because of it. D-1/D-2 thresholds decided (§6). The review record is §10, and the owner's decisions are §9. Part D run 2026-10-15 (§13). Part A run for the Angel 2026-10-21 (§14): a forecastable far high lid behind the canvas, unread by the prompt. Meteosat agrees (2026-10-21). Part B re-run on the verified Sonnet 5.5 era 2026-10-21 (§15): keeping Sonnet 5.5 is evidence-backed. Far-lid rule sized on the first week of capture (§16): the single far-point reading is not a detector; the Angel shape is n = 1. The 15 Oct Meteosat frame (2026-10-22) refutes the far lid as the Angel's cause: the fault is a weak local canvas (§16, §9 item 7). Weak-canvas shape sized on stored data (§17, 2026-10-22).

**Parent:** `far-cloud-lid-plan.md` §6 P1, which pre-registered hypotheses H1–H5. This plan builds P1
and adds the analyses the reviews showed P1 cannot do without. Where it departs from a P1 rule, it
says so.

**The owner's requirement (2026-10-14):** "If I'd have woken up at 4am and had that result I'd not use
the app again."
- A confident go that fails, a **wasted trip**, is the error that loses a user. A missed good sky costs
  much less.
- Every decision rule below weighs the two accordingly, and every part reports sunrise first.

---

## 0. What this plan can and cannot deliver, and when

| When | What | Truth it rests on |
|---|---|---|
| **Now (owner decision, §9 Q1)** | An interim protective switch, if the owner takes one | Replay of one case (P0) |
| **About 2 days after the export** | Part D, replay: what each model and a canvas-rule variant do to the last 30 days of real go prompts | Model behaviour, not truth |
| **About 2 days after the export** | Part B, premise check: how often recent gos stood on premises ERA5 contradicts, by model | ERA5, a reanalysis |
| **About a week** | Part A: what lay on the Angel's sun line, by model, plus one satellite look | Reanalysis, forecasts, and one observation |
| **Weeks** | Part C: whether far decks are common and forecastable (H1–H5) | ERA5, from the retrospective fetch and then from V175/V176 capture |
| **Weeks to months** | A validated **star** change | Recorded outcomes only (Part E) |

Nothing in this plan proves any sky was beautiful, or that it was not. Only recorded outcomes do that.
Until about 20 have been recorded under a protocol (Part E), protection can come only from:
- a model choice;
- a rule taken on replay evidence plus a change to wording or confidence (§6).

## 1. What the Angel case already tells us

- **The 4★ is the prompt's own rule, followed exactly.**
  - `PromptBuilder.java:120-121`: "IDEAL scenario: solar horizon low cloud <20% AND mid cloud <50%,
    with high cloud present on either horizon as canvas … rating 4-5".
  - Lines 122-126 add: "the solar-side mid/high IS the canvas", even when the antisolar side is bare.
  - On the 14:22 run, cone low was 7, mid 24 and high 91. Overhead read 0% at every layer.
- **P0 replay, 20 runs per variant.** On the 14:22 prompt:
  - Sonnet 5.5 gave 4★ 20/20.
  - Sonnet 4.6 gave 3★ 20/20.
  - Haiku 4.5 gave 3★ 17/20 and 4★ 3/20.
  - Setting cone high to 0% gives 3★ on every model. No variant reaches 2★.
- **Timing.**
  - Sunset was about 17:10 UTC, at an azimuth of about 257°.
  - Production read the 17:00 slot, when the sun was about 0.9° up and the sun line at 113 km was
    about 2.8 km high. So that night's readings were close to the golden-hour sun line. They were not
    an hour early.
- **The 91% deck at 113 km was not the bank that hid the sun** (parent §2(A), consequence 3).
  - A deck at that range stands well above the sun at 0.9°, so the sun shone under it.
  - Such a deck glows after sunset only through a clear slot further sunward (parent §2(C)). Nothing
    samples that slot.

**Two faults, linked:**
- **(F1) The canvas rule.** It credits a distant lid as a sure canvas with nothing overhead to catch
  the light. That fault moved the star.
- **(F2) The sunward corridor.** It decides whether the lid is ever lit and whether the sun reaches the
  horizon. That fault plausibly made the sky a 2.

## 2. Parts, in the order they deliver

### Part D — replay the stored go prompts (days; spends money, owner approves, §9 Q2)

`forecast_evaluation_prompt` holds the exact batch sky user message for every prompted row. It keeps
them for 30 days, so the oldest stored prompt is about 14 September now. The Angel prompts are pruned
about 12 November. **The export (§3) saves them all now.**

**Set.**
- Every stored prompt whose row was a go (rating ≥ 4) on its latest run before the event.
- Plus an equal-sized random sample of 3★ rows, so that raises can be seen as well as demotions.
- The training cases are reported, not counted (§4).

**Arms, each prompt replayed N = 3 times:**
1. The production system prompt on Sonnet 5.5. This is the noise baseline against the stored rating.
2. The production prompt on Sonnet 4.6.
3. The production prompt on Haiku 4.5.
4. **An R1 system-prompt variant** on Sonnet 5.5.
   - The IDEAL and rate-4 canvas clauses require **near** canvas (overhead or antisolar mid/high
     ≥ 20) before a distant solar-side lid can carry a 4.
   - The exact wording is drafted in T4, and the owner reads it before any spend.

**Measures:**
- for each arm, how many gos fall below 4, which ones, and with what premise class from Part B;
- how many 3★ rows rise.

**Hard stops** (an arm that fails one is not taken forward):
- Low Hauxley's pinned prompt must stay at 4 in every replay of arms 1, 2 and 4.
- The prompt-regression suite and `PromptGoldenMasterTest` must not regress on any arm proposed for
  production.

**Harness change (T4).** `PromptReplayHarnessTest` always sends `builder.getSystemPrompt()`
(`:157-160`). It gains a test-only system-prompt override read from a file. No production code
changes.

**Cost.** Assume about 100–300 prompts × 3 runs × 4 arms, at standard (not batch) prices. That is of
the order of tens of dollars. T4 prints the exact count and cost estimate before the run.

### Part B — premise check of recent gos (days; no network)

This was called "wasted-trip backtest" in the first draft. Renamed, because it measures disagreement
with ERA5, not failed trips.

**Unit.**
- The primary unit is the **location-go**: a location's latest run before the event, with
  `rating >= 4`. A reader drives to one location.
- Intervals are clustered (§5). Region-event figures are a robustness check only.

**Population.**
- T+0 and T+1, sunrise and sunset, SUNRISE/SUNSET rows only, sky locations only.
- Synchronous-engine rows (`batch_state` NULL with `triage_reason` NULL) are reported separately and
  never pooled.

**Premise classes**, each reported **separately**, never merged into one "contradicted" figure.
Thresholds are the prompt's own where it has one. The ERA5 hour is production's hour (`findBestIndex`).

| Premise | ERA5 test | What it means |
|---|---|---|
| P-low | cone mean low ≥ 60 (`horizon_low_cloud`), with `horizon_low_min` reported beside it | The forecast's own BLOCKED line was crossed in reality. This is partly a model-compliance figure, and is labelled so. |
| P-far | far 226 km low ≥ 60 (`far_low_cloud`) | Diagnostic only. The far reading may never confirm (`CLAUDE.md`), so this premise feeds no decision rule. |
| P-canvas | Observer **and** cone mid and high all < 20 | No canvas where ERA5 has points. ERA5 has **no antisolar point**, and a null cone bearing counts as 0 in the stored mean, so this is an **upper bound** on "no canvas". |
| P-unknown | The row has no `cloud_verification` row, or a needed value is NULL | Reported as its own bucket, never dropped. |

**Angel sanity check.** Where the Angel row lands on each premise, once ERA5 is verified, about
19 October.
- If P-low, P-far and P-canvas all pass for the Angel, Part B **cannot see the Angel class**, and the
  report says so in its first line.
- Its absolute rates are then not evidence that the class is rare.

**Reported, with intervals:**
1. **Base rates.** Each premise's failure share among all gos, by event, lead and model.
2. **Model comparison (named hypothesis M).** Go rate, and P-low and P-canvas shares, by
   `evaluation_model`, within matched bins of forecast cone low (< 20, 20–40) and cone high (< 60,
   ≥ 60). Sonnet 5.5's switch date (the first SONNET_55 row) is printed so before and after are clean.
3. **The Angel shape.** A go with forecast cone low < 20, cone high ≥ 60, and observer plus antisolar
   mid and high all < 20 (no near canvas).
   - Its count, and its P-low share against other gos.
   - Its P-canvas share is printed but **not used** to judge R1, because R1 and P-canvas share inputs.
     That would be circular.
   - The count of Angel-shape gos whose ERA5 cone or observer mid/high is ≥ 20 is the R1 **cost side**:
     an ERA5 canvas that R1 would have refused.
4. **Raised gos.**
   - Definition: the final run is ≥ 4, and the latest earlier row with a rating was ≤ 3.
   - Split same-model against changed-model.
   - Rows with no earlier rated run are their own bucket.
5. **Already capped.** Gos carrying the cloud-approach combined signal (V51 columns) are flagged,
   because they were already capped at 3.
6. **Sun altitude at the production slot**, per row, in bands of 0–2°, 2–5° and 5–9°. Every premise
   rate is also shown by band, because the slot can sit up to 59 minutes before a sunset.

### Part A — the Angel case, reconstructed (about a week; a few hundred calls)

**Transect.** Every 25 km from 0 to 400 km, deduplicated by grid cell. Each hour's points lie on that
hour's own solar azimuth.

**Hours.**
- 16:00 and 17:00 UTC, the golden hour and the event slot.
- 18:00 UTC, the dark-side hour, for the afterglow (sunset was about 17:10 UTC).
- The instants come from the stored `solar_event_time`, not hard-coded.

**Sources:**
- ERA5 with `models=era5` stated: low, mid and high;
- the Historical Forecast API's day-0 low, mid and high per model (§11: Previous Runs serves the
  same day-0 values, and has **no** low/mid/high `_previous_dayN`, so there is no "as issued the
  day before" arm and none is reconstructed);
- `best_match` and each named UK-relevant model the probe finds answering;
- the Historical Forecast API: the six pressure levels.

**Owner's manual step, about 10 minutes.**
- Look at Meteosat imagery for 13 October 15:30–17:20 UTC along the 257° bearing (EUMETSAT's public
  viewer or any archive viewer) (done 2026-10-21, §14).
- This is the plan's only observation, rather than a model.

**Status.** Part A is exploratory, because it is the training case. It may say which mechanism to
size, but it decides nothing.

### Part C — H1–H5 sizing (weeks; background; may be abandoned)

**What is fetched.** The forecast side uses the **stored** values wherever production stored them:
the cone mean, far 226 km low and, after V175/V176, far mid/high and pressure levels. The run fetches
only what is missing:
- ERA5 far mid/high at 226 km for rows verified before V175;
- ERA5 and the Historical Forecast day-0 values at 165 and 320 km (H3, H4);
- the Historical Forecast day-0 far mid/high at 226 km before V175 (H1, H2);
- Historical Forecast pressure levels at 226 km before V176. These are for H5's sensitivity only.

**There is no reconstruction exclusion.**
- It would select against the heterogeneous skies that are the target case.
- Instead, a **calibration pilot of 100 rows** fetches the stored fields too and reports how fetched
  values differ from stored ones: the median absolute difference, and the share within 10 and 20 pp.
- The verdict is **per lead** and fails closed: T+0 and T+1 rows are judged on their own cone-low pairs
  (at least 20 each), and a lead is allowed fetched values only if its own median absolute difference
  is within 15 pp. Well-aligned T+0 pairs never vouch for T+1 rows, whose fetched day-0 value is a
  later forecast than production's (§7, §11). A lead with too few pairs is "unknown" and uses stored
  values.
- If the median absolute difference for a lead exceeds 15 pp on cone low, its fetched forecast side is labelled
  unreliable, and H1–H4 are reported on ERA5 alone until V175 capture fills the forecast side.

**Sample.**
- **Three fixed locations per region-event**: a seeded random choice among the region's firing
  locations. Points are deduplicated by grid cell and date before fetching.
- The population is every prompted region-event that passes the cone gate. If that exceeds 2,000, a
  stratified sample is taken by month, event and lead, with weights applied in every estimate.
- The 2% failure-population bar divides by **all** prompted region-events, never by the sample.

**Budget.** The design is about 10 weighted calls per location. 2,000 region-events × 3 locations ×
10 calls, less about 30% from deduplication, is roughly 40,000 weighted calls, about 5 days at the
default 8,000 a day. Before the first live call the run prints its projected total and days. It
caches every successful response, and never caches an error body. It resumes from the cache.

**Power, stated now.**
- About 40 dates of rated history exist.
- The parent bar is 80 region-events over 25 dates. **No Angel-shape subset can meet it from this
  history**: the estimate is about 2–12 events.
- H1–H5 are therefore judged on **all** prompted cone-gated region-events, which is the parent's
  definition. Any Angel-shape figure is exploratory.
- **The decisive H1, H2 and H5 run is the prospective one.** From V175/V176 onward, both the forecast
  side and ERA5 far mid/high are stored, so the same script re-runs on the DB alone at no API cost.
  The retrospective fetch only buys time, and H3 and H4.

### Part E — outcomes (owner; starts now; the only path to a star change)

- **Protocol (owner fixes it, §9 Q4).** Choose a named set of 5–8 locations, including at least two
  with a westward high-ground horizon like the Angel. Record **every** go (4★ or above) at them, at
  sunrise and at sunset, whatever the sky did. "Didn't go" also counts if the sky was watched or
  photographed.
  - `POST /api/outcome` takes `actualRating` (from 2026-10-13), so each one is a single API call.
  - The one-tap feedback UI is planned, not built. Building it is a separate decision.
- **Target.** 20 outcomes under the Angel-shape or R1 condition.
- **Rate.** At the expected rate of a few a week, that takes months. The report says so, with a date
  computed from the rate actually achieved.

## 3. The export (owner runs it once; read-only)

Use server-side `COPY (SELECT …) TO STDOUT WITH CSV HEADER`, piped from files:

```
docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f - < export_forecasts.sql > p1_forecasts.csv
```

This needs no superuser. Do not use `\copy` inside `-c`, which breaks on a multi-line query.

**Files:**

| File | Contents |
|---|---|
| `export_forecasts.sql` | One row per SUNRISE/SUNSET `forecast_evaluation` row from 2026-02-01, every run (not only the latest), so the script picks "latest" and "previous" itself under §4's rules. It carries all the fields in the list below. |
| `export_prompts.sql` | `forecast_evaluation_prompt.user_message` for every stored prompt, keyed by `evaluation_id`, with `days_ahead`, model, rating and location. |
| `export_summary.sql` | First `batch_state='SCORED'` run, first `sky_rating` row, first SONNET_55 row, counts by month, lead and event, and the training-case ids by name. |

**Fields in `export_forecasts.sql`:**
- ids, date, type, run and event instants, `azimuth_deg`, `days_ahead`;
- model, `batch_state`, `triage_reason`, `rating`, `sky_rating`;
- every forecast cloud column (observer, cone, antisolar, far, dark hour, pressure levels);
- the V51 cloud-approach columns;
- the location's lat/lon, name, region id and name, `enabled`, `is_coastal_tidal`, and its types
  aggregated with `string_agg` (never a plain join, which duplicates rows);
- `cloud_verification` by LEFT JOIN, with every column.

The CSVs contain place names, coordinates and the model's text, but no user data. They are not
committed. **Stored prompts are pruned after 30 days**, so the prompt export is the most urgent step.

## 4. Definitions (pre-registered)

- **Latest run before the event:**
  - the row with the greatest `forecast_run_at` that is less than `solar_event_time`;
  - with `batch_state` NULL or `SCORED`;
  - `rating` not NULL, for gos; `triage_reason IS NULL` for prompted forecasts.
  This is stricter than the serve query, which does not cut at the event and lets ABANDONED rows win.
  The difference is deliberate: a post-event admin row is not what a reader saw.
- **What a reader saw** can still differ from that row.
  - A stability skip writes no row and retracts the old rating when the briefing is served. This is
    rare at T+0/T+1, which every stability may score.
  - Gos at T+0/T+1 are used as they are, and the report states this limit.
- **Previous rated run:** the latest earlier row with `rating` not NULL and `batch_state` NULL or
  `SCORED`.
- **Lead:** `days_ahead`, and the hours from run to event, both reported. T+2 and later are
  descriptive only.
- **Training cases, reported individually and never pooled:** Angel of the North, 13 Oct 2026 sunset;
  Low Hauxley, 4 Oct 2026 sunrise; Copt Hill, 5 and 11 Mar 2026 sunset. Each is found by name in the
  summary export.
- **Rating:** `rating` is primary, because it is the star readers see. `sky_rating` is reported where
  present, as a sensitivity.

## 5. Statistics (pre-registered)

- **Estimands.**
  - Proportions get Wilson 95% intervals.
  - Comparisons are **risk differences** with Newcombe intervals.
  - Ratios are reported as secondary figures, never used for a decision.
- **Clustering.** A moving-block bootstrap over dates, with blocks of 5 days and 2,000 draws on a fixed
  seed, and sensitivity at 3 and 7. Because weather regimes persist, an interval is only as wide as the
  regimes allow.
- **Minimum counts.**
  - No verdict is given for any comparison with fewer than 30 units in either arm, or fewer than 10
    events in the flagged arm. It is reported "inconclusive", which is not the same as "no effect".
- **One primary test per rule:**
  - pooled T+0/T+1, both events, `rating`, location-go unit, premise P-low;
  - everything else is descriptive;
  - Holm correction across R1–R3.
- **Freeze.** The scripts and thresholds are committed, and the commit hash is printed in every report,
  before the owner runs the export.

## 6. Decision rules

**Part B and Part D together (rules R1–R3):**
- **R1 (canvas):** a go with forecast cone low < 20, cone high ≥ 60 and no near canvas would not have
  been a 4. It is tested by Part D's replay arm 4, and sized by Part B's Angel-shape row.
- **R2 (no raise):** a go that rose from ≤ 3 on the previous rated run of the same model is held back.
- **R3 (R1 ∪ R2).**

**Thresholds (owner decision, 2026-10-14; §9 Q3):**
- **D-1. A rule is ready for the owner to consider for production** when all four hold:
  1. Replay (Part D): the rule gives the Angel prompt 3★ or below in at least 15 of 20 runs, and Low
     Hauxley stays 4★ in at least 18 of 20. No prompt-regression failure beyond the one Sonnet 5.5
     already fails.
  2. Replay cost: of the go prompts in the set (658 on 2026-10-14), the rule demotes at most 15%. More
     is a blanket downgrade, not a rule.
  3. Premise (Part B): demoted gos fail P-low more often than kept gos, with the 95% block-bootstrap
     interval entirely above 0 and the count gate met. Until Part B can meet this (it cannot while
     every Sonnet 5.5 go is premise-unknown), conditions 1 and 2 together license a change to
     **wording or confidence only**, never a star.
  4. Cost side: demoted gos with an ERA5 canvas are at most 25% of demotions. On 2026-10-14 every
     drafted rule read 84–86% on Part B, so this is expected to fail and to select the "Part B cannot
     see the class" branch below; that is a result, not a reason to loosen it.
  A rule that passes changes **wording or confidence**: the star shows a stated ceiling ("could be
  3–4★"), on the same surface as the star. A star change waits for Part E.
- **D-2. Batch Near-Term goes back to Sonnet 4.6** if either holds on the replay:
  1. Sonnet 5.5 gives 4★ to at least 8 percentage points more of the three-star sample (prompts whose
     stored rating was 3★; 658 on 2026-10-14) than Sonnet 4.6 does.
  2. Sonnet 5.5's Angel result is 4★ in more than half of its 20 runs while Sonnet 4.6's is not.
  Reversal: switch back to Sonnet 5.5 when a replay on a fresh month shows the gap under 4 points.
  Hypothesis M is the record; the switch is reversible on the Models screen and needs no outcomes.

**What a confidence mark must do (D-1 above):**
- It must be shown **on the star itself**, on every surface that shows the star.
- It must be a per-location fact.
- Today's `ConfidenceDeriver` is per region and horizon-dominant, so T+0/T+1 always read HIGH, and
  `ProvisionalMark` only shows on LOW. That channel cannot carry the mark. A new per-slot field would
  be its own design and review.

**Part C:** the parent's rules, on the population defined in Part C:
- at least 80 region-events over at least 25 dates;
- precision at least 0.7 with a lift interval excluding 1;
- a failure population of at least 2%;
- for H5, a lift interval above H1 ∪ H2.

Whatever the outcome, the figures and reasons are written into the parent plan.

**Branches, written now:**
- **If gos rarely fail any premise, and the Angel row fails none,** Part B cannot see the class.
  - Rely on Part D and Part E.
  - Keep the capture.
  - Run Part C prospectively only.
- **If gos often fail P-low or P-canvas (say over 15%)** or model M shows Sonnet 5.5 worse than 4.6 on
  matched bins, the interim switch (§9 Q1) becomes the recommendation, and Part E is accelerated.

## 7. Known weaknesses

- ERA5 shares much of the forecast's physics, so agreement flatters both. Its layer bands (σ about 2 and
  6.5 km) differ from Open-Meteo's (3 and 8 km).
- ERA5 is 0.25°, about 28 by 16 km here, so 165, 226 and 320 km points can share a cell. Adjacent
  transect points can repeat. A multi-coordinate request answers each coordinate separately and
  in order even when they share a cell (§11), so batching does not merge them; the scripts still
  de-duplicate by cell before fetching, to save calls, never for correctness.
- Open-Meteo's default `cell_selection=land` answers a point over the sea from a neighbouring land
  cell where it finds one (52.0°N 4.0°E came back as 51.75°N 4.0°E). Production's calls send no
  `cell_selection`, so the scripts do not either, and like is compared with like. A far point over
  open water can therefore read a coastal cell; the answered cell is on `Point.latitude/longitude`.
- An hourly slot can sit 0–59 minutes before a sunset, which is why §2 reports sun-altitude bands. At a
  sunrise it can sit 0–59 minutes after the event.
- The fetched forecast side is the model's **day-0** value (Historical Forecast, §11), not the value
  issued the day before. For a T+0 row that is close to what production saw (an intraday run is about
  2–3 hours from its sunset). For a T+1 row it is a **later and better** forecast than the one
  production used, so H1–H4 on fetched T+1 values understate how wrong the forecast was. The pilot
  reports the cone-low disagreement per lead (`[T+0 rows]`, `[T+1 rows]`) and gates each lead on its own
  figure, so a T+1 row only reads fetched values if the T+1 pairs themselves are within 15 pp; the
  prospective V175/V176 capture removes the gap.
- About 40 dates of rated history, so most Part B comparisons will be wide, or inconclusive.

## 8. Build (task cards for subagents)

Everything lives under `scripts/far-cloud-p1/`, plus one test-only Java change. The Python is version
3, standard library only, with offline `unittest` tests against fixture CSV and JSON.

| Card | Contents |
|---|---|
| T1 | The three export SQL files and `README.md` (commands, the copy step, prompt-pruning urgency). |
| T2 | `geometry.py` (port of `GeoUtils.offsetPoint`, the cone, far and transect points, deduplication by `CloudPointCache.gridKey`), `slots.py` (`findBestIndex`, the dark-side index) and `sun.py` (solar altitude and azimuth, NOAA algorithm). Parity tests against the Java tests' expected values, and a check against `azimuth_deg` on fixture rows. |
| T3 | `premise.py` (Part B: latest and previous selection, premise classes, Angel shape, raised gos, hypothesis M, altitude bands) and `stats.py` (Wilson, Newcombe, block bootstrap, Holm, minimum-count gates). Fixture tests, including every NULL case. |
| T4 | `PromptReplayHarnessTest`'s system-prompt override (test-only), the R1 variant's wording file, and `replay_set.py`, which builds Part D's prompt directory from the prompt export and prints the cost estimate. |
| T5 | `openmeteo.py` (cached and budgeted; refuses to cache error bodies; uses `start_date`/`end_date`, never `past_days`; rounds coordinates; passes `models=era5` on the archive; refuses a batch whose points are answered more than 0.55° from the request), `probe.py` (checks every API assumption in §7 and exits non-zero on any failure), `case.py` (Part A) and `size.py` (Part C, with the pilot). |
| T6 | Integration, an adversarial code review and a PR. |

**Order:** T1, T2 and T4 in parallel, then T3 and T5, then T6.

## 9. Owner decisions

1. **Interim protection, now.** — **Decided 2026-10-15: Batch Near-Term stays on Sonnet 5.5 until the question is resolved** (the Part B re-run after ERA5 verification reaches the Sonnet 5.5 era, §12 step 6, or Part E outcomes). D-2's second condition was met (§13) and the owner chose not to act on one case plus a disagreement whose truth is unknown. §15 supports this.
   - Should Batch Near-Term go back to Sonnet 4.6 until Parts D and B report?
   - It is reversible on the Models screen.
   - The P0 evidence is one case: 4.6 gave 3★ 20/20 where 5.5 gave 4★ 20/20. — see §13 for the D-2 reading.
   - Sonnet 4.6 will be deprecated eventually, so this buys time and is not the fix.
   - Before the switch, run the prompt-regression suite on 4.6. `CLAUDE.md` records that 5.5 fails one
     case 4.6 passes, which is the safe direction.
2. Part D's spend (§2, printed exactly before the run) — **approved and run on 2026-10-15** (about $6.30 at batch prices across five arms, primers not counted, against a printed floor of $14.74–22.11). The earlier printed range, $174–261 for 1,316 prompts × 3 runs × 4 arms, excluded Sonnet 5.5 thinking tokens and was superseded by the trimmed Option A.
3. The thresholds in §6 (D-1, D-2 and the branches) — **decided 2026-10-14**, values in §6.
4. Part E's protocol: which locations, and who records.
5. Whether Part A should also run for the other training cases.
6. **Open:** whether to buy a narrower R1 arm that fires only on the Angel shape (10 of 513 gos), at about $4 (§13). Not yet decided.
7. **A weak-canvas rule, not a lid rule** — the shape the Angel establishes is 'clear overhead (observer mid and high < 20%) with cirrus only at the horizon (cone high ≥ 60%, cone low < 20%)': a weak canvas whatever lies behind it, since §16 shows the far deck present under a 4★ sky too. No far-point clause. Open; n = 1 rated (the Angel, 2026-10-13); every input it needs was already in the prompt before V175, so it could be sized on the whole Sonnet 5.5 era and replayed on stored prompts — a prompt-wording change, cost unestimated, not yet proposed. The V175/V176 far capture stays on for H1/H2's prospective test (§12 step 10) but is no longer this rule's input.

## 10. Review record (2026-10-14)

There were five read-only reviewers (statistics, data and SQL, physics, Open-Meteo, product), then two
refutation agents (code facts, external facts).

**Sustained and applied:**
- part B's premise checks cannot see the Angel class (far mid/high absent before V175, nothing at
  155–345 km), so the Angel row must be its sanity check;
- judging R1 on "no canvas" is circular;
- the ratio rule fails on small or zero cells;
- the denominators were undefined and the cost side was missing;
- power: about 40 dates, an Angel subset of about 2–12;
- regimes persist, so a block bootstrap is needed;
- multiple comparisons need one primary test per rule;
- location-go is the right unit for part B;
- the reconstruction exclusion biases the sample;
- the Previous Runs `previous_day0` suffix is wrong;
- the budget was about 300,000 calls as drafted, so it is now three locations per region-event;
- `models=era5` must be stated, at 0.25° resolution;
- error bodies must never be cached, and `past_days` must not be used;
- `\copy` inside `-c` fails, so the export uses `COPY … TO STDOUT` from a file;
- the type join duplicates rows;
- ABANDONED rows win the serve query;
- the previous run was undefined, and a model change confounds raises;
- R1 cannot be read from columns;
- ERA5 mid/high count a null as 0, and ERA5 has no antisolar point;
- there was no interim protection track;
- the cheapest experiment (replay) was missing, and the harness cannot vary the system prompt;
- part B was not a wasted-trip measure;
- there were no branches;
- `ConfidenceDeriver` cannot carry a per-location mark;
- the outcome protocol was unscheduled;
- the per-instant azimuth;
- the dark-side hour for the afterglow.

**Refuted:**
- "the Angel slot was 16:00 with the sun at 7°". Sunset was about 17:10 UTC, the slot was 17:00, and
  the sun was at 0.9°. The general risk (the slot can be up to 59 minutes early) is kept as §2's
  altitude bands;
- "16:18 UTC" was never in the plan.

**Not checked by anyone:**
- live Open-Meteo behaviour (the probe settles it);
- production row counts;
- whether stability skips at T+0/T+1 are as rare as the policy implies.

## 11. What the live API serves (probe, 2026-10-14)

`scripts/far-cloud-p1/probe.py` first ran on 2026-10-14 and failed four checks, all of which gated
Parts A and C. Neither Part B nor Part D's selection touches these hosts. Each is now explained.

| Probe check | Finding | Cause | Change |
|---|---|---|---|
| (b), (i): Previous Runs `cloud_cover_{low,mid,high}_previous_day1`, `best_match`, ~30 days and ~9 months ago | The request is accepted and the variable comes back **all null**; the plain day-0 variable is served. | The Previous Runs documentation lists `_previous_dayN` (N = 1–7) for **total** `cloud_cover` only, never for low, mid or high. `cloud_cover_previous_day1` (total) is served, so this is not a date-range issue. | **The "as issued the day before" arm for the layers is dropped.** |
| (g): any model, any of four dates | Same: day-1 low cloud is null for `best_match`, `ukmo_seamless`, `ecmwf_ifs025`, `icon_seamless` and `gfs_seamless`. | Same cause. It was never a model or depth limit. | (g) now asks the question that can be answered: does each model's day-0 low cloud exist at the export start (2026-02-01)? All five do. |
| (iii): 20-coordinate ERA5 request, point [4] "answered for the wrong cell" | Point [4] was requested at 52.0°N 4.0°E and answered for 51.75°N 4.0°E. All 20 points came back, in order, each equal to the single-coordinate answer. | Not an order fault. The default `cell_selection=land` moved a sea point to the nearest land cell, one 0.25° cell away; the probe's 0.2° tolerance was tighter than a cell. `cell_selection=nearest` answers 52.0°N 4.0°E. | The order check now allows a cell and tests for a point nearer to a different request. `Client.get` refuses a batch with any point more than 0.55° from its request. |
| §7 suspect: adjacent coordinates in one 0.25° cell merged or reordered | Three coordinates inside one cell, sent unsorted, returned three points, one per coordinate. | Not a fault: no merge. | New probe check (iv). |

**Previous Runs plain day 0 equals the Historical Forecast API** for the same model (72 of 72 values
identical at the Angel, `best_match`, 2026-10-04). Low, mid and high and the six pressure levels are
documented on the Historical Forecast host, which is where the scripts now read the day-0 layer
values. All five probed models serve it from the export start.

**What this does to the hypotheses.**
- **Part A:** the sources are ERA5, each model's day-0 layers and the pressure levels. The day-before
  column is gone. The Angel's 14:22 run was a T+0 intraday run, so day 0 is the relevant lead for the
  training case anyway.
- **Part C, H1–H5:** the fetched forecast side is day-0 for every row, a **T+0-quality forecast**. For
  a T+1 row the stored value (V175 onward) is still preferred and is the day-before run production
  used. Fetched T+1 values are the limit named in §7. They bias H1–H4 towards the forecast agreeing
  with ERA5 for T+1 rows pre-V175, which can only make a far deck look **less** wrong than it was
  at T+1; the per-lead pilot verdict keeps them out unless their own pairs are within 15 pp. The decisive H1, H2 and H5 run stays the prospective one (§2 Part C), which reads stored
  values only and is unaffected.
- **No hypothesis is dropped.** The affected evidence is T+1 rows before V175 on the fetched side of
  H1–H4, reported separately per lead by the pilot.

**Known limits left open.**
- ERA5 mid/high at the far point before V175 and the pressure levels before V176 are as before.
- The day-before layers cannot be reconstructed from any host found. If Open-Meteo adds layer
  `_previous_dayN`, probe check (b) reports "NOW SERVED" as an INFO and the arm can be restored.

## 12. Plan from 2026-10-15

Written after the exports, Part B, the Part D set, the probe fix (#1166) and the harness batch mode (#1169) all landed on 2026-10-14. Waits are stated where they exist.

**This week**
1. **Keep the replay set.** It is built at `/tmp/p1-replay` (1,316 prompts plus the two training cases), so prompt pruning no longer threatens Part D; macOS clears `/tmp` on reboot, so copy it somewhere permanent first. **Done 2026-10-15:** the set is at `~/goldenhour-p1/p1-replay/` on the owner's Mac, the split with the 150-prompt control at `~/goldenhour-p1/p1-replay-arms/` (2,124 calls, $14.74–22.11 at batch prices before thinking tokens), and the R1 prompt at `~/goldenhour-p1/sp/system-r1.txt`; `~/goldenhour-p1/README.md` describes each. Nothing there is committed.
2. **Run Part D as Option A in batch mode:** two arms, one run each — the production prompt on `SONNET_55,SONNET`, and the R1 prompt on `SONNET_55` — with `REPLAY_BATCH=true REPLAY_N=1` (README §3). Printed floor about $23–35 at batch prices plus Sonnet 5.5 thinking tokens; the owner approves the printed figure on each arm's first run. Haiku is dropped (not the model under suspicion) and repeats are bought later only where the arms disagree.
   **Trimmed (2026-10-15):** a stored production rating is that model's arm for free, so `replay_set.py split` writes `need-sonnet/` (the 1,112 prompts SONNET_55 rated, replayed on SONNET), `need-sonnet55/` (the 204 HAIKU rated, replayed on SONNET_55) and `r1-gos/` (the 658 gos, R1 on SONNET_55; a 3-star tells R1 nothing), about 1,974 calls and $13.9–20.9 at batch prices (`--three-sample 300`: 1,616 calls, $11.0–16.6), and `compare` takes the stored rating as the missing arm, labelled `stored/…`. Cost of the trim: a stored rating is one sample, and a stored SONNET_55 3-star is 3 by selection, so the trimmed run can refute D-2.1 but not confirm it (no SONNET_55 side can sit above SONNET's); a seeded SONNET_55 replay of a sample of the 599 prompts is the control, README §3. `split --control-sample 150` adds that control as `control-sonnet55/` (a seeded 150 of the SONNET_55-stored prompts, go and 3-star in proportion, replayed on SONNET_55 with the production prompt, about $0.8–1.2 at batch prices): the stored rating is selected on its own value, so any replay against it drifts toward the mean, and `compare` prints that noise floor beside the raw D-1 part 2 and one-sided D-2 lines and the D-1 part 2 figure net of it. Recommended before reading either line; not an arm and not paired in any decision line.
3. **Read D-2 and D-1 parts 1 and 2** from `stats.py` over the two `runs.csv` files. That decides the Sonnet 4.6 switch on evidence and says whether R1 is a rule or a blanket downgrade.
4. **If D-2 says switch:** run the prompt-regression suite on Sonnet 4.6 first, then flip Batch Near-Term on the Models screen (§9 Q1).
5. **Part E starts now** (§2 Part E): 5–8 named locations, two with a westward high horizon like the Angel; every go recorded with `POST /api/outcome` whatever the sky did.

**Two to three weeks**
6. **ERA5 verification catching up with Sonnet 5.5.** At the 2026-10-14 export the latest verified `target_date` was 2026-10-04 and Sonnet 5.5's first run was 2026-10-06, so every Sonnet 5.5 go was premise-unknown and Part B judged Haiku's gos only. The hourly `cloud_verification_backfill` job walks forward in slices; check `GET /api/admin/cloud-verification/backfill/status` weekly, and when verification passes about 2026-10-14, re-export `p1_forecasts.csv` and re-run Part B (free, no network). That is the first Part B that can judge Sonnet 5.5 gos and the Angel row. **Done 2026-10-21 (§15) and again 2026-10-31 (§21)**; a third re-run waits on ratings or on ERA5 reaching the 27 and 30 October evenings.
7. **Part C in the background**, now the probe passes: `size.py` under the 60-call daily Open-Meteo weight, so weeks; drop it if steps 3 and 6 settle the question.
8. **Part A** (§9 Q5): about a week of calls, only if step 3 leaves the Angel mechanism open. **Done 2026-10-21 for the Angel (§14)**; the other training cases (§9 Q5) are not run.

**Months**
9. **Twenty outcomes** at a few a week; the report dates its finish from the rate achieved. A star change waits for this.
10. **Prospective far-deck data** accrues daily from the V175/V176 capture already in production, feeding H1, H2 and H5 from stored values with no fetch.

## 13. Part D results (2026-10-15)

**Run.** The trimmed Option A of §12 step 2 plus a fifth arm, `training-r1`: the two training prompts under R1 on Sonnet 5.5, 20 runs each. Batch mode, one run per prompt otherwise. Tokens and cost per arm, from the harness summaries:

| Arm | Requests | Cost |
|---|---|---|
| need-sonnet | 1,114 | $3.31 |
| need-sonnet55 | 206 | $0.57 |
| r1-gos | 660 | $1.87 |
| control-sonnet55 | 152 | $0.42 |
| training-r1 | 42 | $0.11 |

That is about $6.28 in total at batch prices, primer batches excluded. Every arm had zero failed attempts. The report is `~/goldenhour-p1/part_d.md` on the owner's Mac (not committed).

**Control (noise floor).** Sonnet 5.5 replayed on 150 prompts it rated in production, with the production prompt: 9 of 150 moved (6.0%). 3 of 69 gos fell below 4★ (4.3%). 2 of 81 three-stars rose to 4★+ (2.5%).

### D-1: the R1 canvas rule (fiery_sky cap 55, on Sonnet 5.5)

- **Part 1 passes.** The Angel of the North 2026-10-13 sunset prompt scored 3★ in 20 of 20 runs under R1 (it was 4★ in 20 of 20 under the production prompt, P0). Low Hauxley 2026-10-04 sunrise stayed 4★ in 20 of 20 under R1.
- **Part 2 fails.** R1 took 179 of 658 go prompts below 4★ (27.2%) against the 15% cap; net of the 4.3% control floor, that is +22.9 pp.
  - On the 513 gos Sonnet 5.5 itself rated, 164 fell (32.0%).
  - On the 145 Haiku-rated gos, 15 fell (10.3%), against Sonnet 5.5's own production-prompt 12 of 145 on the same prompts, so there is no R1 effect there beyond noise.
  - Only 10 of the 513 Sonnet 5.5 gos are Angel-shape by Part B's definition. R1 demoted 8 of those 10, and also 156 of the 503 that are not Angel-shape (31%).
  - By event, on the 513: sunrise 51 of 267 fell (19%), sunset 113 of 246 (46%).
  - R1 as drafted is a blanket downgrade concentrated on sunsets, not a rule.
- **Part 3 cannot be tested yet.** Part B has no verified Sonnet 5.5 gos until ERA5 verification passes 2026-10-14 (§12 step 6).
- **Part 4** was already failing on Part B's 2026-10-14 figures (84–86% of flagged gos had an ERA5 canvas).

### D-2: Sonnet 5.5 vs Sonnet 4.6

- **Item 1 (an 8-point 4★ gap on the three-star sample) is not met, and could only be read one-sided**, because a stored Sonnet 5.5 three-star is 3 by selection.
  - Sonnet 4.6 rated 68 of 599 of Sonnet 5.5's three-stars 4★+ (11.4%), against the 2.5% control floor.
  - Sonnet 4.6 also took 127 of 513 of Sonnet 5.5's gos below 4★ (24.8%), against the 4.3% floor.
  - Over those 1,112 prompts Sonnet 4.6 gives 4★+ to 454 (40.8%) where Sonnet 5.5's stored ratings give 513 (46.1%): about 5 points, under the 8 the rule asks.
  - The two models disagree in both directions rather than one being uniformly generous.
  - Aside: Sonnet 5.5 on the 145 Haiku-rated gos took 12 below 4★ (8.3%) and rated 39 of Haiku's 59 three-stars 4★+ (66.1%). Sonnet 5.5 is far more generous than Haiku, which is not a D-2 arm.
- **Item 2 is met, on P0's evidence.** The Angel prompt is 4★ in 20 of 20 runs on Sonnet 5.5 and 3★ in 20 of 20 on Sonnet 4.6. By §6's "either holds", D-2 recommends the switch.
- **The 4.6 demotions and the R1 demotions are largely different prompts.** Of 228 Sonnet 5.5 gos that either demoted, only 63 were demoted by both.

### Reading (the plan's own reading, not a decision)

- D-2's switch stands on one case plus a 25% disagreement on gos whose truth is unknown until Part B can see the Sonnet 5.5 era (§12 step 6) or Part E records outcomes.
- Under the owner's requirement (a wasted trip costs more than a missed sky), a model that withdraws a quarter of the other's gos is the safe direction if the two are equally accurate, and the switch is reversible.
- R1 at a 55 cap is rejected as drafted. A narrower R1 that fires only on the Angel shape (10 of 513 gos) is a possible follow-up arm at about $4, not yet decided (§9 item 6, open).
- Part B re-run after verification will say whether the 127 gos Sonnet 4.6 withdrew fail P-low more often than the 386 it kept. That is the one test that can adjudicate the switch without outcomes.

## 14. Part A result: the Angel's sun line (2026-10-21)

**Run.** `case.py --row-csv p1_forecasts.csv --row-id 101110` on 2026-10-21: 18 requests, 288 Open-Meteo weighted calls, no Anthropic spend. The report is `~/goldenhour-p1/case_2026-10-13.md` and `.csv` on the owner's Mac (not committed).
- **Sources:** ERA5 (`models=era5`); the Historical Forecast API's day-0 low, mid and high for `best_match`, `ukmo_seamless`, `ecmwf_ifs025` and `icon_seamless`; and its pressure levels (§11 explains why day 0 is the only forecast lead).
- **Transect:** every 25 km from 0 to 400 km along each hour's own solar azimuth, at 16:00, 17:00 and 18:00 UTC. Sunset was 17:14 UTC. The production slot was 17:00, with the sun 1.3° up at azimuth 255°.

**What the production prompt saw** (the stored prompt for evaluation 101110):
- Overhead: `Low 0%, Mid 0%, High 0%`.
- Solar horizon at 113 km: `Low 7%, Mid 24%, High 91%`, which is a cirrus canvas.
- Antisolar: 0/0/0.
- `Beyond horizon (226km, solar azimuth): Low 0%`. The prompt carried no far mid or high: V175 has recorded them since 2026-10-13, but no prompt reads them.
- Sonnet 5.5 rated it 4★ (20 of 20 in replay). The observed sky was 2★.

**What lay on the sun line at 17:00 UTC:**
- ERA5 high cloud was 96–99% from 125 km and 99–100% from 225 km to 400 km. Mid cloud was 21–49% from 125 km. Low cloud stayed under 25% to 325 km.
- Every one of the four forecast models (day 0, issued that day) shows high cloud at 98–100% from 225 km to at least 325 km: `best_match` and `ukmo_seamless` 100% at 225–300 km, `ecmwf_ifs025` 100% at 225–375 km, `icon_seamless` 100% at 225–350 km.
- The ray from the Angel at 1.3° reaches the 8 km high band at about 225 km (the report's own `h(d)` line gives 9.1 km at 225 km). That is exactly where the continuous deck begins, so the 113 km canvas sat under a lid the setting sun could not get beneath.
- At 16:00 UTC (sun 9°) the same deck is present from 150 km in ERA5 (99–100%) and from 175 km in all four models.

**Reading.**
- The Angel false go is the far-lid mechanism of H1 and H2: a horizon canvas with a continuous high deck behind it along the sun line.
- It was forecastable on the day, since all four models agree with ERA5, but the prompt did not read it: production sampled only low cloud at 226 km.
- The far-cloud-lid-plan's P2 capture (V175/V176) now records far mid/high and the pressure levels for every slot. The prospective H1, H2 and H5 test (§12 step 10) therefore reads stored values from 2026-10-13 onward.
**Meteosat observation (2026-10-21):** the owner viewed the EUMETSAT Meteosat composite in Zoom Earth for 2026-10-13 at 17:50 BST, i.e. 16:50 UTC, twenty minutes before the 17:00 UTC slot (the frame is saved on the owner's Mac as `~/goldenhour-p1/meteosat_2026-10-13_1650Z_zoomearth.webp`, not committed). Along 255° from Gateshead it shows: north-east England and the coast clear (ground and city lights visible, a few thin streaks); a fibrous, streaky band of high cloud across the North Pennines, Cumbria and Dumfries at roughly 50–150 km, the cirrus canvas the prompt saw at 113 km; and a broad, continuous, more uniform grey-white sheet over the Isle of Man, the north Irish Sea and Northern Ireland from about 200 km outward. One frame, read by eye from a dusk composite, not a measurement; it agrees with the ERA5 transect and the four models (thin cirrus at the horizon, a solid lid behind it from about 225 km). Part A's observation step is done; a second frame at 18:10 BST (17:10 UTC, four minutes before sunset, the terminator crossing Newcastle) was viewed and shows the same three zones; it was not saved.

**A first sizing hint, not a base rate.** Of the 98,255 exported `forecast_evaluation` rows, 577 (13–14 October only) carry `far_solar_high_cloud`. 390 of those read ≥ 60%, and 92 of the 390 were rated go. Two days inside one cirrus spell say nothing about frequency; Part C (§12 step 7) and the accruing capture do.

**Implication for the rules.**
- R1 as drafted, a fiery_sky cap keyed on "no canvas", was rejected in §13 as a blanket downgrade, and the Angel had a canvas.
- A rule keyed instead on the **far high deck** is the shape this case points at: the canvas is present AND far high is ≥ 60% along the solar azimuth.
- It cannot be replayed on stored prompts, because they carry no far high. Replaying it means rebuilding prompts from forecast rows, which is a prompt change, not a replay.
- Recorded as §9 item 7, open.

## 15. Part B re-run on the verified Sonnet 5.5 era (2026-10-21)

**Trigger.** On 2026-10-21 production's `cloud_verification` had reached `target_date` 2026-10-15, with 12,093 verified rows dated 2026-10-06 or later (the first Sonnet 5.5 run was 2026-10-06). So `p1_forecasts.csv` was re-exported (106,118 rows, dates 2026-02-26 to 2026-10-24) and `premise.py` re-run (`~/goldenhour-p1/part_b2.md`). `replay_set.py compare` was re-run over the five Part D arms with the new `slots.csv`, so its premise-class tables now cover Sonnet 5.5 gos (`~/goldenhour-p1/part_d2.md`). No Anthropic spend. Neither report is committed.

**Population.** 2,251 location-gos at T+0/T+1, 2,120 of them with a `cloud_verification` row (1,614 on 2026-10-14).

**P-low** (ERA5 cone low >= 60%, the one premise that counts), among gos, by model:

| Model | Event | Gos failing P-low |
|---|---|---|
| Haiku | sunrise | 79 of 904 (8.7%) |
| Haiku | sunset | 39 of 714 (5.5%) |
| Sonnet 5.5 | sunrise | 9 of 286 (3.1%) |
| Sonnet 5.5 | sunset | 2 of 220 (0.9%) |

Sonnet 5.5's gos stand on ERA5-blocked horizons less often than Haiku's.

**The Angel sanity row** now reads: P-low passes, P-far passes, P-canvas passes. Part B's three premises cannot see the far high-cloud lid that §14 found. The Angel class is invisible to this test by construction, as §2 Part B warned.

**Primary tests, same verdicts as before:**
- R1 is inconclusive: 0 of 67 flagged gos failed P-low, fewer than 10 events.
- R2 and R3 fail: the differences are +0.3 and 0.0 points, Holm p = 1.0.
- R1's cost side: 59 of 67 Angel-shape gos (88.1%) had an ERA5 canvas, against the 25% ceiling. D-1 part 4 fails.

**The Part D premise cross** (`part_d2.md`), which is the test §13 said could adjudicate the switch. Of Sonnet 5.5's 513 gos, 490 pass all three premises, 9 fail only the canvas premise, 1 fails only the far premise, and 13 are unknown.
- Sonnet 4.6 withdrew 118 of the 490 all-pass gos (24.1%, its overall withdrawal rate), 7 of the 9 no-canvas gos, 0 of the 1 far-flagged go and 2 of the 13 unknown.
- Its withdrawals are not concentrated on gos ERA5 contradicts. By ERA5 they read as missed opportunities, not avoided wasted trips, with the 9 no-canvas gos the one small class it catches.
- R1 demoted 144 of 613 all-pass gos (23.5%) and 9 of 9 no-canvas gos. D-1 part 3 fails: there is no enrichment of P-low among demoted gos. R1 fails parts 2, 3 and 4 of D-1 and is rejected.

**Reading.** The owner's 2026-10-15 decision to keep Batch Near-Term on Sonnet 5.5 (§9 item 1) is now supported by evidence rather than caution alone.

**Caveat.** ERA5 is a reanalysis, not an observation, and these premises see only the horizon gap, the local canvas and the far LOW corridor. The far-lid class (§14) and any other unmodelled failure are outside them. So this does not say Sonnet 5.5's gos are right; it says only that Sonnet 4.6's withdrawals are not where ERA5 says the faults are. Part E outcomes remain the only test of wasted trips.

**What this leaves as the live line of work:**
- the far-lid rule (§9 item 7) on the V175/V176 capture;
- Part C sizing (§12 step 7);
- Part E.

## 16. The far-lid rule, sized on the first week of capture (2026-10-21)

**Data.** The 2026-10-21 re-export (`p1_forecasts.csv`), latest run per slot, restricted to slots dated 2026-10-14 to 2026-10-24 that carry `far_solar_high_cloud` (the V175 capture): 3,029 slots, 2,155 rated, 276 gos (rating >= 4), all Sonnet 5.5. No network, no spend. Definitions used here:
- **Canvas:** cone (113 km) mid or high >= 60%.
- **Lid:** `far_solar_high_cloud` >= 60%.
- **Angel shape:** overhead mid and high both < 20%, cone low < 20%, and cone high >= 60%.

**Canvas plus lid is common and bunched.**
- 187 of the 276 gos had a canvas. 128 of those 187 (68%) had a lid behind it.
- 100 of the 128 fall on 2026-10-15 alone. The others: 14 Oct 9, 16 Oct 9, 18 Oct 2, 22 Oct 2, 24 Oct 6.
- A cap on canvas plus lid would have withdrawn 128 of the week's 276 gos.

**ERA5 cannot adjudicate the lid yet.**
- The 578 verified slots with `cv_far_high_cloud` are from 14-15 October only, where ERA5 itself saw a lid on 553 of 578.
- Forecast lid against ERA5 lid: agreed on 469, forecast missed 84, forecast over-called 15, both clear 10.
- Agreement on days when everything had a lid says nothing about days when the lid is local.

**The first two recorded outcomes refute canvas plus lid as a verdict.** The owner's first two entries in the `outcome_rating` table ("How was it?"), Angel of the North and Copt Hill, 2026-10-15 SUNSET, were forecast 3★ and observed 4★. Cone high was 100% and far high 100% in both the forecast and ERA5: a good sky under exactly that shape. The owner's photo shows a mid-level canopy overhead lit pink from beneath, with an orange horizon band. The owner also reports Penshaw Monument, same evening and run, forecast 4★, as right.

**What differed between 13 and 15 October is overhead, not the far point.**
- 13 Oct Angel: forecast overhead 0/0/0 and ERA5 observer low/mid/high 2/16/8. Nothing to light except the 113 km cirrus, with the lid behind it (§14). ERA5 far high 88% (dark hour 99%).
- 15 Oct Angel: forecast overhead 99/100/100 and ERA5 observer 2/100/84. A full mid/high canopy that lit anyway, with a far high of 100%.

**15 October's error was the opposite class: an over-forecast low deck.**
- At the Angel the forecast said overhead low 99% and horizon low 32% (ERA5: 2% and 4%).
- At Copt Hill, overhead low 100% and horizon low 43% (ERA5: 2% and 3%).
- At Penshaw Monument, horizon low 0% (ERA5 3%) and the rating was 4★.
- Same sky, three sites a few miles apart; the one with the right low-cloud figure got the right star. That is a missed opportunity, not a wasted trip, and outside this plan's rules.

**The Angel's exact shape is a sample of one.** Of the 276 gos, 25 match the Angel shape in the forecast (all 15 October), 15 of those with a lid, and ERA5 says none of the 15 was truly clear overhead. None has an outcome. No rule may ship on n = 1.

**Revised §9 item 7.**
- The candidate rule is not "canvas + far high >= 60" but "nothing overhead (mid and high < 20), horizon cirrus canvas (cone high >= 60), lid behind (far high >= 60)": the shape in which the only canvas needs the sun to pass under the far deck.
- It stays open and uncosted. It waits on more Angel-shaped gos with owner ratings.
- It must be sized against the far mid/high dark-hour columns and the pressure levels as well as the single 226 km point. The Angel's own far 300 hPa figure that evening, from the Historical Forecast API, was only 11-27%, so the pressure levels may not see a cirrus lid the layer figures do.

**Tooling note.** The 15 October transect (`case.py --date 2026-10-15`) was attempted and refused by Open-Meteo's daily limit; it resumes from cache on a later day. The free daily limit was reached after about 600 weighted calls, far below `openmeteo.py`'s 8,000 default, so plan one transect per day.

### The 15 October transect (2026-10-22)

**Run.** `case.py --lat 54.914116 --lon -1.5895191 --date 2026-10-15 --type SUNSET` ran on 2026-10-22: 4 live requests and 56 weighted calls, the rest from the previous day's cache. The report is `~/goldenhour-p1/case_2026-10-15.md` on the owner's Mac (not committed). Sunset was 17:09 UTC; in the 17:00 UTC hour the sun was at 0.7°, azimuth 254°.

**What lay on the sun line at 17:00 UTC (ERA5):**
- High cloud was 91–100% at every point from 0 to 400 km, the same continuous 100% deck from 250 km outward that 13 October showed (§14).
- Low cloud was 0–18% throughout.
- Mid cloud was 86–95% from 0 to 125 km, then 75% at 150 km, 42% at 175, 30% at 200, 33% at 225, 17% at 250, 4% at 275 and 2–14% beyond.
- All four forecast models agree on the high deck (100% from 150–175 km outward) and on the mid canopy to about 225 km.

**The outcomes were opposite under the same far deck.** That evening's sky was rated 4★ by the owner at the Angel and at Copt Hill, and forecast-right at Penshaw (§16 above). So the ray to the Angel passed through a 100% high deck beyond 250 km on both evenings, and the results were opposite. What differed is the local canvas:
- 15 October: a dense mid-level canopy overhead out to 125 km (ERA5 observer mid 100%), lit from under its thinning western edge.
- 13 October: mid 0–30% and nothing overhead, the horizon cirrus at 113 km being the only canvas.

**Consequence for the far-lid hypothesis: it is not established as the mechanism.**
- Cloud-cover percentages cannot distinguish thin cirrus, which transmits red light, from an opaque shield.
- The far point's pressure-level cloud (300–500 hPa) read under 25% on both evenings, so the capture cannot tell the two apart either.
- The one robust difference in the data is the local canvas: a mid canopy overhead (good) against horizon cirrus only with a clear sky overhead (poor).
- The §9 item 7 rule stays open. Its "lid behind" clause is now the least supported part and its "nothing overhead, horizon cirrus only" clause the best supported, with the Angel still its only rated case.

**Next cheap check (owner's).** View the Zoom Earth / Meteosat frame for 2026-10-15 at 17:50 BST (16:50 UTC) and compare the brightness and texture of the Irish Sea sheet with the 13 October frame (§14). A visibly thinner sheet on the 15th would keep the lid alive as an optical-depth question; the same sheet would argue the lid was never the cause.

### The 15 October Meteosat frame, and the end of the lid (2026-10-22)

The owner viewed the Zoom Earth / Meteosat composite for 2026-10-15 at 17:50 BST (16:50 UTC, nineteen minutes before the 17:09 UTC sunset). The frame is saved on the owner's Mac as `~/goldenhour-p1/meteosat_2026-10-15_1650Z_zoomearth.webp` and is not committed.

**What it shows.** A continuous, mottled, mid-level grey-white sheet over the Irish Sea, Northern Ireland, the Lake District, the Solway and Newcastle itself. It is denser and more uniform than the streaky cirrus of 13 October (§14), not thinner. This was the evening the owner rated 4★ at the Angel and at Copt Hill, and forecast-right at Penshaw.

**Consequence: the far-lid hypothesis is refuted.**
- The far cloud along the sun line was at least as thick on the good evening as on the bad one, so H1/H2 as the Angel's mechanism fail on the only two rated evenings. The lid was present both times and is not what separated them.
- What separated them is the local canvas. On the 15th the deck extended overhead and its underside caught the light. On the 13th the only cloud was a cirrus strip at the horizon, with nothing above the Angel to light.
- The far high-cloud reading, by cover percentage, is identical in both pictures and says nothing on its own.
- §9 item 7 is rewritten accordingly, as a weak-canvas rule with no far-point clause.

One frame each, read by eye from a dusk composite, not a measurement. A second frame at 18:10 BST (17:10 UTC, the terminator crossing Carlisle) was viewed and shows the same sheet, if anything a shade thinner over Durham and Newcastle than over the Solway; it was not saved.

## 17. Weak-canvas sizing (2026-10-22)

**Why.** §15 and the 15 October transect left a weak local canvas, not a far lid, as the best-supported difference between the Angel's 13 October fault and a good sky under the same far deck. Every input to that shape (observer mid/high, cone low/high) has been in `forecast_evaluation` and the prompt since the directional capture, so it can be sized on stored data. `scripts/far-cloud-p1/canvas.py` does it: no network, no spend, no decision.

**Definitions** (also at the top of `out/canvas.md`):
- **Go:** premise.py's selection: latest eligible run per (location, date, event), a prompted batch row with rating >= 4, T+0/T+1, sky locations. Training cases are included and flagged (the Angel 13 October is one go in the counts below).
- **Weak canvas:** forecast observer mid < 20 and high < 20, cone (113 km) low < 20, cone high >= 60. This is the "Angel shape" of §16, which did not require the antisolar mid/high; the stricter `premise.angel_shape` (antisolar < 20 too) is counted as a subset.
- **ERA5 view:** `cloud_verification` observer mid/high < 20 (clear overhead), cone high >= 60 (horizon cirrus), cone low < 20.
- **Canopy overhead:** forecast observer mid >= 60 or high >= 60; the mirror class is a canopy-overhead slot rated <= 3, taken over every prompted rated batch slot, not only gos.

**Headline counts** (the owner's 2026-10-21 export, 106,118 rows, go dates 2026-08-29 to 2026-10-22; 2,253 gos, 2,239 testable for the shape):
- **137 weak-canvas gos (6.1%)**, over 11 dates. Haiku 75 of 1,615 (4.6%); Sonnet 5.5 62 of 624 testable (9.9%). It is bunched, not rare-and-steady: 28 September 32 (Haiku, Lake District sunsets), 15 October 28 (sunrise), 13 October 18 (sunset, the Angel's day), 14 October 16. By event: sunrise 51 of 1,297 (3.9%), sunset 86 of 942 (9.1%). The strict form is 68, of which 66 are sunsets and 57 Haiku's.
- **ERA5's view of the 137.** All 137 have a `cloud_verification` row. ERA5 observer mid and high < 20 for 62 (45.3%): Haiku 55 of 75, Sonnet 5.5 only 7 of 62. ERA5 cone high >= 60 for 70 (51.1%), cone low < 20 for 130 (94.9%). All three together: 10 of 137 (Haiku 5 of 75, Sonnet 5.5 5 of 62).
- **Reconciling §16.** For Sonnet 5.5 gos dated 14 October on with the far capture, 28 match, all 15 October, and 16 of those have a far high >= 60; ERA5 calls none of the 16 clear overhead. §16 counted 25 and 15; the 3 and 1 difference is selection (this tool uses premise.py's pooled population), not a different definition.
- **Outcomes.** The forecast export carries no `outcome_rating`, so none of the 137 is joined. `canvas_weak_gos.csv` lists the slot keys; `export_outcome_ratings.sql` plus `canvas.py --ratings` does the join. Not yet run.
- **Mirror class.** 5,202 rated canopy-overhead slots, 3,827 (73.6%) rated <= 3; that is the ordinary outcome, so the class is large by construction. Low cloud, forecast against ERA5, among the 3,610 rated <= 3 slots with both values: a forecast deck (>= 60%) where ERA5's point read < 20% for 14.2% on overhead low and 10.4% on cone low. Against the disjoint set of canopy slots rated > 3 (1,336 with both values): 11.7% and 1.7%. The reverse (an ERA5 deck, a clear forecast) is 1.6% and 0.3% for the <= 3 set and 3.1% and 0.2% for the > 3 set. So the over-forecast cone low deck is several times commoner among the low-rated canopy slots; the overhead figure differs by less than three points. ERA5 is a model, and a rating of 3 or less is not a record that the sky was good.

**What this does and does not say.**
- The weak-canvas shape is not rare on stored forecasts (6.1% of gos), and ERA5 reproduces the whole shape for only 10 of the 137. Where they differ, either could be the one that is wrong; both are models.
- It says nothing about whether any of those skies was poor. Only owner ratings do, and none are joined here.
- Nothing here changes a rule or the §9 item 7 status.


## 18. Over-forecast low deck (2026-10-22)

**Why.** §16 left the 15 October evening with a second error class beside the weak canvas: the forecast put a 99–100% low deck over the Angel of the North and Copt Hill (32–43% on their solar horizon) where ERA5 read 2% and 3–4% (the 99–100% is the forecast's observer reading, the 32–43% its cone), and Sonnet 5.5 rated them 3★ against the owner's 4★. §17's first cut found a forecast deck >= 60% over an ERA5 reading < 20% on about 14% of canopy slots rated <= 3. `scripts/far-cloud-p1/lowdeck.py` measures that class across the whole verified history on stored data: no network, no spend, no decision. ERA5 is a reanalysis, not an observation.

**Definitions** (also at the top of `out/lowdeck.md`):
- **Slot:** premise.py's latest eligible run per (location, date, event), sky locations, with a `cloud_verification` row, T+0/T+1 (T+2+ appears only in the days_ahead split). Every engine is kept: batch, sync and triaged. Training cases and disabled locations are not excluded.
- **Observer low cloud:** forecast `low_cloud` against ERA5 `cv_observer_low_cloud`. **Cone low cloud:** `solar_low_cloud` (113 km) against `cv_horizon_low_cloud`.
- **Phantom deck:** forecast >= 60, ERA5 < 20. **Missed deck:** forecast < 20, ERA5 >= 60. Both >= 60, both < 20, and a middle band (any other combination) are counted separately.
- **Outcome:** *triaged on low cloud* (`triage_reason` HIGH_CLOUD, whose message names low cloud; the rule reads the cone), *triaged, other*, *rated 1-2 / 3 / 4-5* (the combined `rating`), *unrated*.
- **ERA5 canopy overhead:** `cv_observer_mid_cloud` >= 60 or `cv_observer_high_cloud` >= 60.

**Headline figures** (the owner's 2026-10-21 export, 106,118 rows: 35,982 slots, 31,393 sky slots with ERA5, **29,305 at T+0/T+1**, 2026-03-07 to 2026-10-15; intervals are 95% Wilson):
- **Contingency, observer low:** phantom 3,811 (13.0% [12.6, 13.4]); missed 659 (2.2%); both >= 60 8,892 (30.3%); both < 20 6,414 (21.9%); middle 9,529 (32.5%). Of the 16,194 slots with a forecast deck, ERA5 reads 3,811 (23.5%) clear. **Cone low:** phantom 4,144 (14.1% [13.7, 14.5]); missed 117 (0.4%); both >= 60 8,015; both < 20 4,891; middle 12,138; 22.9% of forecast decks. Mean error (forecast minus ERA5) is +13.3 pp observer (MAE 27.5) and +24.0 pp cone (MAE 29.6).
- **By model, observer phantom rate:** Haiku 13.4% (3,538 of 26,422), Sonnet 5.5 9.7% (266 of 2,737), Sonnet 146 slots 4.8%. Cone: Haiku 14.7%, Sonnet 5.5 7.4% (203), Sonnet 31.5% (46 of 146). **A model split is also a date split:** Sonnet 5.5 slots are all October (to the 15th); Haiku's October slots (97 of 1,580, 6.1%) are the early part of the month. By event, observer phantom is 7.6% for sunrise and 18.8% for sunset (cone 12.6% and 15.8%). By month it runs from 5.7% (April, 352 slots) and 8.4% (October) to 20.2% (May); by region 9.0% (Lake District) to 17.4% (North York Moors & Coast); coastal 14.9%, inland 12.5%.
- **What the rating did, observer phantom (3,811):** triaged on low cloud 2,223 (58.3%), triaged for another reason 122, rated 1-2 837 (22.0%), rated 3 346 (9.1%), **rated 4-5 283 (7.4%)**. Triaged on low cloud or rated <= 3: 3,406 (89.4% [88.4, 90.3]), against 82.2% for both-clear slots. Sonnet 5.5 alone (266): triaged 15.4%, rated 1-2 40.6%, rated 3 28.2%, rated 4-5 12.8%; triaged or <= 3 84.2% [79.3, 88.1]. For **cone** phantoms (4,144) the triage figure is 75.2% (the triage rule reads the cone; none of the 4,891 both-clear cone slots was triaged on low cloud) and 95.8% were triaged or rated <= 3, against 65.4% for both-clear.
- **Co-occurring canopy.** An ERA5 canopy overhead is present on 1,913 of the 3,811 observer phantoms (50.2%), against 36.7% of both-clear slots and 48.2% of all testable slots. Of those 1,913: triaged on low cloud **1,087**, rated <= 3 **579**, either **1,666 (87.1%)**, rated 4-5 187. Sonnet 5.5 alone: 95 canopy phantoms, 30 triaged, 38 rated <= 3, 27 rated 4-5. For cone phantoms with an ERA5 canopy overhead (2,026): 1,487 triaged, 449 rated <= 3, 1,936 either, 24 rated 4-5.
- `out/lowdeck.md` also lists 20 examples for each variable and `out/lowdeck_phantom.csv` holds every phantom slot with its outcome. The top gap is a ceiling, so the list is a sample: 80 observer slots share the largest gap (100 pp) and 19 cone slots do; ties go to the most recent date, which puts the 15 October sunset first (observer).

**What this does and does not say.**
- ERA5 reads clear where the forecast has a deck on 13-14% of slots, and 89-96% of those slots were triaged on low cloud or rated 3 or lower. If ERA5 is right about those slots they are the missed-opportunity class; ERA5 may not be. The cone comparison is largely mechanical: weather triage fires on a cone reading above 80%, so a cone phantom (>= 60) is triaged whenever it is above 80, and no slot with a cone below 20 can be, which is why 95.8% sits against 65.4% there. The observer figures are the informative ones. ERA5 is a ~31 km model field, it can be the one that is wrong, and no owner rating is joined here (`canvas.py --ratings` is the route for that).
- The Wilson intervals assume independent slots, which they are not (many locations share one weather situation on a date, and a model is a date range); the real uncertainty is wider. No date-resampled interval is computed here.
- A canopy overhead in ERA5 is the 15 October shape; it co-occurs on about half the phantoms, and those slots are rated 4-5 only 187 times in 1,913.
- Nothing here changes a rule or any §9 item.


## 19. Phantom deck under a forecast canopy (2026-10-22)

**Why.** The Codex peer review of `far-cloud-p1-findings-2026-10-22.md` agreed no production change is justified yet and named the offline sizing of a forecast-keyed triage relaxation as the best next step: the §18 phantom deck is the largest error class measured, about half of it sits under a real canopy, and weather triage's rule 1 (`HIGH_CLOUD`, solar-horizon low cloud above 80%) stands those slots down before Claude sees them. `scripts/far-cloud-p1/canopydeck.py` sizes the candidate rule "do not stand down on low cloud alone when the forecast also shows a mid/high canopy >= 60" on the same stored data as §18. No network, no spend, no decision. The same review's four measurement-quality fixes went in with it: `replay_set.py` prints the basis-against-combined-rating cross-tabulation, `lowdeck.py` reports HIGH_CLOUD rows without a low-cloud message as their own outcome category and lists every category so they sum to the total, `canvas.py` and `lowdeck.py` print a 5-day date-block bootstrap interval (3 and 7 days as sensitivity) beside each Wilson interval plus a sensitivity line without 2026-10-15, and `canvas.py` reports the strict Angel shape beside the broad one in its headline.

**Definitions** (also at the top of `out/canopydeck.md`):
- **Slot, population:** as §18 (premise.py's latest eligible run per (location, date, event), sky locations, T+0/T+1, with a `cloud_verification` row) for counts 1 to 3; count 4 takes every sky T+0/T+1 slot, because it needs no ERA5.
- **Forecast canopy:** forecast observer `mid_cloud` >= 60 or `high_cloud` >= 60. **Forecast deck:** forecast observer `low_cloud` >= 60. The rule keys on these, never on ERA5, which the forecast cannot see; ERA5 is used only to describe what the slots turned out to be. **Phantom deck:** forecast low >= 60, ERA5 observer low < 20. **Obstructed:** ERA5 observer low >= 60. **ERA5 canopy overhead:** `cv_observer_mid_cloud` or `cv_observer_high_cloud` >= 60.
- **Released by the rule:** the latest run was triaged on low cloud (`HIGH_CLOUD` with a message naming low cloud) and a forecast canopy is present.
- **Cost assumption (ASSUMED, not measured):** 3,500 input and 400 output tokens per call (the replay harness default); thinking tokens and prompt caching are not modelled; USD per million tokens from `application-example.yml` (Haiku 1/5, Sonnet 3/15, Sonnet 5.5 2/10); "batch" is the 50% Message Batches discount.

**Headline counts** (the owner's 2026-10-21 export, 106,118 rows: 29,305 verified T+0/T+1 sky slots, 2026-03-07 to 2026-10-15; 32,355 sky T+0/T+1 slots without the ERA5 requirement; intervals are 95% Wilson, with the 5-day date-block bootstrap beside them in the report, resampled over the whole study window even for a subgroup, so a rare subgroup shows its "drop" share, e.g. 23% for the 129 Sonnet 5.5 canopy phantoms):
1. **Phantom decks with a forecast canopy: 2,258 of 3,811 (59.2%, block interval [52.2, 65.0]).** The other 1,553 had none; none had an unknown canopy. Haiku 2,125 of 3,538, Sonnet 5.5 129 of 266.
2. **What the latest run did on the 2,258:** triaged on low cloud **1,354** (60.0%), triaged for another reason 80, rated 1-2 **433**, rated 3 **214** (rated <= 3 together **647**), rated 4-5 **177** (7.8%); the categories sum to 2,258. Sonnet 5.5 alone (129): triaged on low cloud 30, rated <= 3 69, rated 4-5 23.
3. **The exposure.** Among ALL slots with forecast low >= 60 and a forecast canopy (9,954 with ERA5 low known), ERA5 observer low reads >= 60 for **5,663 (56.9%, block interval [51.5, 61.8])**: 4,135 of them with an ERA5 canopy overhead, 1,528 without. ERA5 reads < 20 (the phantom) for 2,258 and 20-59 for 2,033. Forecast decks with no forecast canopy: 51.7% obstructed, 24.9% phantom. For the 8,285 slots the rule would actually release (latest run triaged on low cloud, canopy forecast, ERA5 known), ERA5 observer low reads obstructed for 3,655 (44.1%, block interval [36.5, 50.6]; 2,636 with an ERA5 canopy, 1,019 without) and < 20 for 2,879 (34.7%). By the ERA5 113 km cone, the reading rule 1 itself uses, the same 8,285 are >= 60 for 3,936 (47.5%, [40.3, 54.3]) and < 20 for 1,775 (21.4%, [16.0, 27.8]). The 5,663 above includes slots Claude already rated, which the rule does not touch; the 8,285 is the subset it would release.
4. **The cost.** The rule would have released **8,410** sky T+0/T+1 slots over the 239 calendar days from 2026-02-26 to 2026-10-22, **35.2 slots a day** (a lower bound: one evaluation per slot), or **22,381 runs, 93.6 a day** (an upper bound: every triaged run of those slots re-evaluated, none skipped by the freshness cache; runs are counted under the model and day span of the slot's latest run). By era: Haiku 35.6 slots a day (7,874 over 221 days; 90.9 runs a day), Sonnet 4.6 1.3 (78 over 58 days; 1.4 runs a day), **Sonnet 5.5 32.7 slots a day (458 over 14 days, 2026-10-09 to 10-22; 158.0 runs a day)**. At the stated tokens (one call is $0.011 at Sonnet 5.5 and $0.0055 at Haiku) each era row gives its own figure: Haiku era $0.20 a day (slots, at its own price); Sonnet 5.5 era $0.36 a day for its 32.7 slots a day (list; $0.18 batch) and $1.74 a day for its 158.0 runs a day ($0.87 batch). The eras overlap in calendar time and are not additive; pricing every era's slots at Sonnet 5.5 over the full 239 days gives $0.39 a day, a mixed-basis figure that is not the Sonnet 5.5 era's. A released slot could still fail the precipitation or visibility rule, which a rule-1 stand-down never reached, so both counts are upper bounds on that account.

**What this does and does not say.**
- A phantom deck is more often under a forecast canopy than not, and the latest run rated 7.8% of those 4-5 and sent 60% of them nowhere (triaged). That is the missed-opportunity class in numbers; whether any of those skies lit is not measured here (no owner rating joins to them).
- Of the slots the rule would release, ERA5 reads 44% (observer) or 47% (cone) as a real deck and 35% (observer) or 21% (cone) as clear; the rest are in the middle band. Claude would then see those slots and could rate or decline them. ERA5 is a reanalysis, and the phantom definition behind counts 1 and 2 is the observer point's while triage fires on the cone, so the headline mixes two readings; the report prints the cone table beside the observer one for that reason.
- The cost is per-day counts under an assumed token count; it is not a forecast of spend, and the freshness cache and Gate 4 are not modelled.
- Nothing here changes a rule or any §9 item.


## 20. Replay of rated forecast-deck-under-canopy analogues (2026-10-22)

**Why.** §19 sized the rule "do not stand down on low cloud alone when the forecast also shows a mid/high canopy >= 60" and could not say how Claude would rate the slots it releases. None of the 8,410 released slots has a stored prompt (weather triage stands a slot down before one is built), so they cannot be replayed. `scripts/far-cloud-p1/canopyreplay.py` replays the nearest stored thing: slots with the same FORECAST shape (observer low >= 60, observer mid or high >= 60) whose latest run was rated, so Claude saw them and a prompt was stored. No production change; measurement only.

**Design.** `select` takes the latest eligible run per (location, date, event), sky, T+0/T+1, rated and not triaged, forecast shape as above, with a stored prompt: **428 prompts** (394 inland, 34 coastal; stored by Haiku 93, Sonnet 5.5 335; 2026-10-04 to 10-14, 7 dates with ERA5; 61 are phantom decks). One Sonnet 5.5 call each with the production prompt through the Batch API (`REPLAY_BATCH`, N=1; 428 requests plus 2 primers; harness estimate $1.27 expected and $9.22 ceiling; the harness's own tally $1.21 at batch prices, primers not in it). All 428 returned a rating. `report` tabulates the replayed sky rating against ERA5 and the stored rating. Files, outside the repo: `~/goldenhour-p1/canopy-replay/` (set), `canopy-replay-arm/` (runs.csv, summary.md, batches.json), `out/canopyreplay.md`.

**Counts** (95% Wilson in the report; slots are not independent, 7 weather days):
- **By the ERA5 observer low band** (the reading the phantom definition uses): ERA5 < 20, 61 analogues, replayed go (rating >= 4) 17 (27.9%); 20-59, 98, 10 (10.2%); >= 60, 269, 37 (13.8%).
- **By the ERA5 cone low band** (the reading rule 1 uses): < 20, 210, 60 (28.6%); 20-59, 137, 4 (2.9%); >= 60, 81, **0 (0.0%, [0.0, 4.5])**.
- **Within the phantom band, by ERA5 canopy overhead:** with a canopy 14 of 27 (51.9%), without 3 of 34 (8.8%). Within the obstructed band: with a canopy 37 of 235 (15.7%), without 0 of 34.
- **Replay against the stored rating:** the stored and replayed go counts agree closely (observer bands 17/17, 9 against 10, 33 against 37), and the cross-tabulation is concentrated on the diagonal (53 of 59 stored 4s replayed at 4, 103 of 128 stored 3s at 3), so the Sonnet 5.5 replay does not move these slots much from the stored ratings, Haiku's included.

**What this does and does not say.**
- Shown these skies, Sonnet 5.5 gives a go to about 28% of the ones ERA5's cone reads clear and to none of the 81 where it reads obstructed. It separates them better on the cone (28.6% clear against 0% obstructed) than on the observer point (27.9% clear against 13.8% obstructed). That is an association between the prompt's inputs and ERA5, which share physics; it is not evidence that any of those skies lit.
- It is not evidence about the released slots. The analogues have forecast cone low cloud of at most 80 (median 54) and the released slots are above 80 by definition, so the replay is an extrapolation in the one variable that defines the rule. Seven dates, all early-to-mid October, and no owner rating is joined.
- Nothing here changes a rule or any §9 item.

## 21. Part B re-run on verification to 25 October (2026-10-31)

§12 step 6's second re-run. ERA5 verification had reached `target_date` 2026-10-25 (13,891 verified rows
dated 16 October or later); the forecasts and outcome ratings were re-exported (126,412 rows, dates to
2026-11-03) and `premise.py` and `canvas.py --ratings` were run with no network and no spend. Outputs:
`scripts/far-cloud-p1/out3/part_b.md` and `out/canvas.md` in the owner's checkout (git-ignored).

**Part B (`premise.py`).** Sonnet 5.5's gos with a known P-low went from 286 sunrise / 220 sunset (§15)
to 525 / 231, and the P-low share did not move: sunrise 9/525 (1.7%), sunset 2/231 (0.9%), against
Haiku's 79/904 (8.7%) and 39/714 (5.5%). The Angel still passes all three premises. R1's cost side is
88.1% (59 of 67 Angel-shape gos with an ERA5 canvas) against the 25% ceiling, and R2/R3 fail as before.
§9 item 1 (keep Sonnet 5.5) stands on the wider sample.

**Weak canvas (`canvas.py`).** 175 of 2,684 testable gos match the broad shape (6.5%, date-block
95% [2.9%, 10.1%]); 106 the strict Angel shape (3.9%). By model, Sonnet 5.5 9.4% of its gos, Haiku 4.6%,
on different weeks. ERA5 confirms the whole shape for 10 of the 137 with a verification row (as §17).

**Ratings.** Five `outcome_rating` rows exist in all. Exactly one weak-canvas go carries a rating, the
Angel (reader 2★, forecast 4★). The shape occurred on three further evenings inside the 24-day rating
window: 27 October (22 Lake District sunset gos and 5 North York Moors sunrise gos), 28 October (Crummock
Water, Scale Force) and 30 October (Sycamore Gap, Peel, Hotbank and Highshield Crags, Cawfields, Allen
Banks, Crummock Water, Rannerdale Knotts, Scale Force). The owner saw none of them, so they stay unrated;
ERA5 has not reached them yet. The question the plan is waiting on is still Part E, not more data.

**Decision.** Nothing changes. The next re-run is worth doing only once ratings exist on weak-canvas gos,
or when ERA5 passes the end of November to see the 27 and 30 October evenings.
