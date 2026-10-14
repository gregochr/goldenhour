# P1: wasted trips, the canvas rule and far decks, measured on the history we already have

**Status:** revised after adversarial review, 2026-10-14. Tools built (`scripts/far-cloud-p1/`, code-reviewed); nothing run on production data yet. The review record is §10, and
the owner's decisions are §9.

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
- the Previous Runs API, as issued the day before and on the day;
- the plain variable for day 0, and `_previous_day1` for the day before;
- `best_match` and each named UK-relevant model the probe finds answering;
- the Historical Forecast API: the six pressure levels.

**Owner's manual step, about 10 minutes.**
- Look at Meteosat imagery for 13 October 15:30–17:20 UTC along the 257° bearing (EUMETSAT's public
  viewer or any archive viewer).
- This is the plan's only observation, rather than a model.

**Status.** Part A is exploratory, because it is the training case. It may say which mechanism to
size, but it decides nothing.

### Part C — H1–H5 sizing (weeks; background; may be abandoned)

**What is fetched.** The forecast side uses the **stored** values wherever production stored them:
the cone mean, far 226 km low and, after V175/V176, far mid/high and pressure levels. The run fetches
only what is missing:
- ERA5 far mid/high at 226 km for rows verified before V175;
- ERA5 and the Previous Runs forecast at 165 and 320 km (H3, H4);
- the Previous Runs forecast of far mid/high at 226 km before V175 (H1, H2);
- Historical Forecast pressure levels at 226 km before V176. These are for H5's sensitivity only.

**There is no reconstruction exclusion.**
- It would select against the heterogeneous skies that are the target case.
- Instead, a **calibration pilot of 100 rows** fetches the stored fields too and reports how fetched
  values differ from stored ones: the median absolute difference, and the share within 10 and 20 pp.
- If the median absolute difference exceeds 15 pp on cone low, the fetched forecast side is labelled
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

**Thresholds, proposed for the owner (§9 Q3):**
- **D-1. A rule is ready for the owner to consider for production** when all of these hold:
  1. Part D shows it demotes Angel-shape gos and leaves Low Hauxley and the prompt-regression suite
     intact.
  2. Part B's primary test shows its demoted gos fail P-low more often than kept gos, with a risk
     difference interval above 0.
  3. Its cost side (demoted gos with an ERA5 canvas) is at most 25% of its demotions.
  Without outcomes, a rule that passes changes **wording or confidence**: the star shows a stated
  ceiling ("could be 3–4★"), shown on the same surface as the star. A star change waits for Part E.
- **D-2. A model switch** is an owner call on Part D and Part B evidence, with hypothesis M as its
  record. It is reversible on the Models screen, and needs no outcomes.

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
  transect points can repeat.
- An hourly slot can sit 0–59 minutes before a sunset, which is why §2 reports sun-altitude bands. At a
  sunrise it can sit 0–59 minutes after the event.
- Previous Runs matches by day, not by hour. A T+0 intraday run is about 2–3 hours from its sunset,
  while the API's day 0 is the model's latest value.
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
| T5 | `openmeteo.py` (cached and budgeted; refuses to cache error bodies; uses `start_date`/`end_date`, never `past_days`; rounds coordinates; passes `models=era5` on the archive), `probe.py` (checks every API assumption in §7 and exits non-zero on any failure), `case.py` (Part A) and `size.py` (Part C, with the pilot). |
| T6 | Integration, an adversarial code review and a PR. |

**Order:** T1, T2 and T4 in parallel, then T3 and T5, then T6.

## 9. Owner decisions

1. **Interim protection, now.**
   - Should Batch Near-Term go back to Sonnet 4.6 until Parts D and B report?
   - It is reversible on the Models screen.
   - The P0 evidence is one case: 4.6 gave 3★ 20/20 where 5.5 gave 4★ 20/20.
   - Sonnet 4.6 will be deprecated eventually, so this buys time and is not the fix.
   - Before the switch, run the prompt-regression suite on 4.6. `CLAUDE.md` records that 5.5 fails one
     case 4.6 passes, which is the safe direction.
2. Part D's spend (§2, printed exactly before the run).
3. The thresholds in §6 (D-1, D-2 and the branches).
4. Part E's protocol: which locations, and who records.
5. Whether Part A should also run for the other training cases.

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
