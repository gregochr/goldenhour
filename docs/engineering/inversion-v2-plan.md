# Cloud inversion v2: measure the warm layer where the fells are (2026-10-28)

**Status:** plan, not started. Phase 0 can start once this plan is reviewed. Phase 1 waits on the
measurement in §4.1a and on at least one dated fog-sea morning (§6).

**Prompted by:** three field tips from a Lake District photographer (roamingthelakes, Instagram, 2026-10-28):

1. *"A cloud inversion forms when the temperature on the ground is colder than the temperature at higher
   elevation. This usually happens following a cold, clear night during a period of high pressure."*
2. *"Choose a mountain summit that's likely to be above the forecast cloud level … viewpoints overlooking
   valleys and lakes."*
3. *"Light winds often favour cloud inversions … Stronger winds can mix the air and break up the cloud
   layer."*

These describe a **radiation inversion**: the ground cools under a clear, calm night, the air in contact
with it cools below the air above, and fog fills the valley under a warm cap. Checked against the code, they
showed that the inversion score looks for the warm layer at the wrong height, never scores the clear night,
and probably cannot see the classic fog-sea morning at all (§1).

**The constraint the owner set:** improve inversion forecasting without losing valley mist. Mist
(`docs/engineering/valley-mist-plan.md`, built 2026-10-21) was prompted by the River Brathay sunrise on
17 Oct 2026 (Mike Ridley, Facebook; thin mist on the river, the fell behind in sun). Every phase below runs
against the mist guards in Phase 0, and §3 lists the coupling rules that keep mist untouched.

Read §1 and §3 before §4.

---

## 1. What is true today

### 1.1 The inversion score (`service/InversionScoreCalculator.java`)

0–10, from the **event-hour** slot only:

| Part | What it reads | Effect |
|---|---|---|
| Moisture | temperature − dew point, via `MoistureScoring.gapScore` | 0–5 |
| Wind | 10 m wind, steps at 1.5 / 3 / 5 m/s | 0–3 |
| Low-cloud deck | `cloud_cover_low`; 25–65% earns 2, else 1 | 0–2 |
| Low-cloud gate | `cloud_cover_low` outside 15–80% | caps at 6.0 |
| Clearance gate | `elevation_m` ≤ `boundary_layer_height` | caps at 6.0 |
| Lapse gate | `temperature_925hPa` (else 850) − `temperature_2m` | < 0 caps at 6.0; < 2 °C or no profile caps at 8.0; ≥ 2 °C allows 10 |

Bands (`PromptBuilder.InversionPotential`): NONE < 7 ≤ MODERATE < 9 ≤ STRONG. The prompt's inversion
block appears at 7 or more, with a "Measured reversal … ~760m (925 hPa)" line. The **system prompt** names
"~760m" in its MEASURED REVERSAL guidance (`PromptBuilder` lines ~226–231).

Eligibility (`ForecastDataAugmentor.augmentWithInversionScore`): `elevation_m ≥ 200` **and**
`overlooks_water`. The ten eligible sites (V65) run from **335 m** (Loughrigg Fell) to **597 m**
(Innominate Tarn / Haystacks).

Readers: `slot_atmosphere.inversion_score` (V158) feeds `InversionHotTopicStrategy`, Coming up's "Valley
inversions" condition and the `INVERSION_CALC` daily log (`TopicDailyLogJob`). Claude's echo feeds the map
badge.

### 1.2 What the tips expose

1. **The warm layer is looked for above every viewpoint.** 925 hPa is about 760 m; 850 hPa about 1,460 m.
   Every eligible site is at 335–597 m. A fog sea seen from those fells tops out a few hundred metres up,
   under a warm layer in the lowest 200–500 m, around 975 hPa (~320 m) and 950 hPa (~540 m). Neither is
   requested (`OpenMeteoClient.FORECAST_PARAMS`).

   The owner's Brathay file (Open-Meteo, 16–20 Oct 2026, grid elevation 68 m, which requests 975/950 too)
   shows the gap:

   | Hour (UTC) | Warmer at 975 hPa? | Warmer at 925 hPa? | 925 hPa wind |
   |---|---|---|---|
   | 16 Oct 05:00–06:00 | yes | no | 18–21 mph |
   | 17 Oct 00:00 | yes | no | 19 mph |
   | 18 Oct 02:00–06:00 | yes (+0.2 to +1.6 °C) | no (−2.8 °C at 06:00) | 4.5–11 mph |
   | 19 Oct 02:00 | no | yes | 29 mph |

   The one 925 hit was warm air arriving aloft on a windy night, not a fog sea. 18 Oct dawn (shallow warm
   layer, 54% low cloud in the valley cell at 06:00, calm aloft) is the week's best-looking set-up, and
   today's lapse gate caps it at 6. No observation exists for 18 Oct; it is an illustration, not evidence.

2. **The clear night is not scored.** Tip 1. The score reads cloud only at the event hour. Mist already
   scores a clear night (its own term, §1.3).

3. **High pressure is not read**, and should not be (§5): it is a proxy for clear, calm and a warm cap,
   each of which is scored directly.

4. **"Above the cloud" is approximated by boundary-layer height.** Tip 2. Open-Meteo reports it above the
   grid cell's ground, compared here with an above-sea-level elevation; the class comment already calls
   this an approximation.

5. **The low-cloud gate may veto the textbook morning.** Valley-mist M0 showed the 2 km model does not
   fill valley fog: the Brathay cell held 0% low cloud all night on 17 Oct while mist lay on the river. On a
   clear-night fog sea the model may show near-zero low cloud and the gate caps the score at 6. The gate
   suits the other kind of cloud sea (anticyclonic stratus under a subsidence inversion). Unproven until a
   fog-sea morning is checked (§6).

6. **The "surface" may not be the valley floor.** `temperature_2m` refers to the height of the grid point
   the request is made for. The app does not parse Open-Meteo's returned `elevation`, so for a fell site we
   do not know whether "surface" is the valley, the fellside or the top. If the grid point sits on the fell
   above the fog, the profile above it shows no reversal however good the sea below. §4.1a measures this
   before anything is designed on top of it.

7. **Tip 2 also names valleys without lakes.** `overlooks_water` excludes a fell above a dry valley. Owner
   decision, §7.

### 1.3 Valley mist, for contrast (`service/ValleyMistCalculator.java`)

Scores the night-side slots (T−3h..event for sunrise, event..T+2h for sunset) of the `MistTrend`: clear
night 0–2 (mean **total** cloud, ramp 10%→60%), calm 0–1.5 (minimum 10 m wind, ramp 2→4 m/s), moisture
0–4 (minimum gap via `MoistureScoring`), cooling 0–1.5, wet ground 0–0.5, confirmation 0–0.5; overcast
≥ 70% or wind > 4 m/s caps at 3.9. Bands NONE < 4 ≤ POSSIBLE < 6 ≤ LIKELY, FOGBOUND by visibility < 300 m.
Gated on `locations.mist_prone` alone, any elevation (`augmentWithValleyMist`).

On the owner's real Brathay file at 07:00 UTC (the hour production reads; sunrise 06:41 UTC, nearest-hour
index): clear 1.63 + calm 1.50 + moisture 2.40 + cooling 1.50 = **7.03, LIKELY**. At 06:00 it would read
5.03, POSSIBLE. The existing `ValleyMistCalculatorTest.brathay()` fixture uses 06:00 and invented
temperatures (its own comment says so); Phase 0 replaces that with the real data.

---

## 2. What is shared today

Exactly one function: `MoistureScoring.gapScore(double)`. Nothing else is read by both calculators. Mist
reads no pressure level, boundary layer, elevation or `overlooks_water`; inversion reads no `MistTrend` and
no `mist_prone`. They run side by side in `ForecastService.fetchWeatherAndTriage` and write separate
`slot_atmosphere` columns, hot topics and prompt blocks.

---

## 3. Coupling rules (hold for every phase)

1. **Mist's score and band must not move** on any input in Phase 0's fixtures. Each phase runs the Phase 0
   guards unchanged; a phase that needs to change one is out of scope and comes back to the owner.
2. **`MoistureScoring` is frozen** for this series. If inversion needs a different moisture curve it gets
   its own, in `InversionScoreCalculator`.
3. **No shared "fog-formation core" with shared weights.** An earlier draft (outside the repo) proposed one
   holding wind, gap, humidity and overnight cloud. Rejected:
   - humidity is the gap measured again (the reason it was removed from the inversion score);
   - mist already has a clear-night term on total cloud; a shared low+mid term would count clear sky twice
     for mist or replace its term, and either moves the 17 Oct score;
   - the two subjects want different things from cloud and wind (mist: clear and calm to the ground;
     inversion: clear and calm overnight, a fog layer present at dawn, calm at the summit).

   Sharing a pure helper that only *reads* (e.g. "mean cloud over these slots") is fine; weights and ramps
   stay in each calculator.
4. **Fog depth must never reach mist.** A deep, well-capped layer is good for an inversion and bad for the
   riverside shot (a valley full of fog hides the fell behind). No inversion-strength input may feed the
   mist score. The Phase 0 isolation test enforces it.
5. **If inversion reads a mist signal, it is one-way and named.** Option B in §4.2 would let inversion read
   the mist set-up. Mist never reads inversion. The dependency gets a test in the inversion suite so a later
   mist change that moves inversion scores is visible.

---

## 4. Phases

One PR per phase, each branched from `main` after the previous merged (CLAUDE.md *How every code change is
made*). Every phase: Sonnet builds in its own worktree; the backend build ladder through `clean verify`
(with the integration exclusion); the frontend's four commands only if the phase touches the frontend (none
does as planned); a `changelog.d/` entry; `docs/engineering/reference/conditions.md` updated where
behaviour changes. No phase spends Anthropic money.

### Phase 0: mist guards (tests only)

- Copy the owner's file into `backend/src/test/resources/valley-mist/open-meteo-brathay-2026-10-16_20.json`
  (as-is; record its provenance in a sibling `README.md`: Open-Meteo, coordinates, fetch date, grid
  elevation 68 m, and the timezone note below).
- **Real-data fixture test** (`ValleyMistBrathayFixtureTest`, or a nested class in
  `ValleyMistCalculatorTest`): build `AtmosphericData` from the file through the production parser path
  (`OpenMeteoResponseParser`) if the file's fields allow it, else through a small test-only mapper; event
  time 17 Oct 06:41 UTC so the parser picks 07:00 itself. Assert band **LIKELY** and the score
  **7.03 ± 0.01**, and pin each sub-score in a comment. Also assert the 06:00 alignment reads POSSIBLE
  (5.03), documenting that one hour moves a band.
  - The file has no total `cloud_cover`; the test must say how cloud is supplied (max of the three layers
    is exact here: mid and high are 0 through the night). Visibility and precipitation are also absent;
    the test sets them neutral and says so.
  - The file reports `timezone: Europe/London` with `utc_offset_seconds: 0` although 17 Oct is BST. The
    production request uses `timezone=UTC`. Treat the times as UTC and record the assumption.
- **Correct `ValleyMistCalculatorTest.brathay()`**: keep it (it pins the invented-cooling case) but fix its
  comment's event hour, or retire it in favour of the real fixture. Do not change any other M0 fixture or
  assertion.
- **Isolation test** (`ValleyMistIsolationTest`): from the real fixture, vary only inputs mist must not
  read: `temperature925hPaCelsius`, `temperature850hPaCelsius`, boundary layer height, the location's
  elevation and `overlooks_water` (through `augmentWithValleyMist`'s caller shape), and, as later phases
  add them, the 975/950 temperatures, pressure-level winds and geopotential heights. Assert score and band
  are identical. Each later phase extends this test with its new inputs.
- **Inversion baseline**: assert the same file scores inversion ≤ 6.0 at 07:00 for a 335 m
  `overlooks_water` site (it is capped by the lapse gate: 925 hPa is 4.0 °C colder than the surface).
  Phase 1 will change this number on purpose; the test records the before.

### Phase 1: measure the warm layer at the right height

**1a. Measure first (no code merged).** For each of the ten eligible sites, record from Open-Meteo for the
site's stored `grid_lat`/`grid_lng`: the returned `elevation`, and the hourly `temperature_2m`,
`temperature_975hPa`/`950hPa`/`925hPa` and `geopotential_height_975hPa`/`950hPa`/`925hPa` for one or more
dated fog-sea mornings (§6). This session's container cannot reach `api.open-meteo.com` (proxy 403), so
the owner runs it, or a session with network access does. Write the results into §8 of this plan. The
question it answers is §1.2 point 6: is the 2 m "surface" the valley or the fell?

**1b. Build**, shaped by 1a:

- Add `temperature_975hPa`, `temperature_950hPa` and `geopotential_height_975hPa`/`950hPa`/`925hPa` to
  `FORECAST_PARAMS` (same call, grid-deduplicated; no new request). Parse Open-Meteo's top-level
  `elevation` into the response model. `WeatherData` gains the fields (7 constructor call sites; follow the
  existing convenience-constructor pattern).
- Replace the lapse gate's single 925 comparison with a profile walk over 975 → 950 → 925 → 850, skipping
  any level whose geopotential height is not above the grid elevation (levels below ground are
  extrapolated). Define:
  - *reversal* = warmest valid level minus `temperature_2m`;
  - *warm-layer top* = geopotential height of the warmest valid level (the top of the rise).
- Replace the boundary-layer clearance gate with **viewpoint elevation vs warm-layer top** when a reversal
  exists: viewpoint at or above the top clears; within the level spacing below it (about 220 m; the
  resolution limit, ±100 m) caps at MARGINAL (8.0); further below caps at VETOED (6.0). Fall back to the
  boundary-layer check when the profile has no reversal or no heights.
- Keep `STRONG_REVERSAL_CELSIUS` (2.0) and the ceilings unchanged; re-derive nothing from one morning.
- If 1a shows the grid point sits on the fell rather than in the valley, the surface reference is wrong
  for these sites and 1b stops here and comes back to the owner (options: a second, valley grid point per
  site, which is a new location field and a second sample; or accepting the limitation).
- **Prompt:** the user-message "Measured reversal" line states the level it used and the warm-layer top.
  The **system prompt's "~760m" wording is left as it is** in this phase (it is the shared batch cache
  prefix); a follow-up changes it only with the owner's sign-off, since `ValleyMistPromptRegressionTest`
  and the prompt-regression suite sit on it.
- Tests: profile walk (levels below ground skipped, warmest level chosen, ties), clearance bands at the
  boundaries, fallback to boundary layer, 925-only profile behaves as today, the Brathay file's 18 Oct
  06:00 hour now shows a reversal at 975 hPa. Extend the isolation test.
- ⚠️ **`INVERSION_CALC` continuity.** The daily log feeds the inversion rarity census. Record the merge
  date in `conditions.md` so the census never mixes the two regimes silently.

### Phase 2: the clear night (inversion only)

- Add an inversion-only clear-night term over the night-side slots. `MistTrend` currently carries T−3h..
  T+2h; tip 1 and the earlier draft want roughly 21:00–05:00 local. Decide in review between (a) reading
  the existing T−3h window (no data change) and (b) a longer overnight window extracted from the same
  hourly arrays (new carrier on `AtmosphericData`, no new request). (b) is closer to the physics; (a) is
  cheaper. Either way mist's own term and window do not change (rule 3).
- Cloud measure: mean `cloud_cover_low` + `cloud_cover_mid` (or total) over the window, linear ramp, a
  named weight. Proposed: 0–1.5 points, full at ≤ 10% mean, zero at ≥ 60%, and a **multiplier** rather than
  an added point if the review prefers that overcast nights damp the whole score. Default proposal: an
  additive term plus the same ≥ 70% cap the mist score uses, mirroring mist's structure.
- Score range: the inversion's 0–10 is already full (5 + 3 + 2). Adding a term means rescaling or capping;
  the review must pick one explicitly. Proposal: keep 10 as the cap and let the term buy back what the
  low-cloud gate takes (see Option B below), so existing STRONG mornings are unchanged.
- **Option B (owner decision, §7): a second source for the fog sea.** Let a clear, calm, moist night
  (inversion's own reading of it, not the mist band) satisfy the low-cloud gate when `cloud_cover_low` is
  under 15%. Addresses §1.2 point 5. If the owner prefers inversion to read the mist band directly, rule 5
  applies.
- Tests: clear vs overcast nights, ramp ends, the cap, the gate bypass, unchanged scores for every
  existing `InversionScoreCalculatorTest` case that has no night data (or an explicit update with reasons).

### Phase 3: wind at summit height (inversion only)

- Add `wind_speed_975hPa`/`950hPa`/`925hPa` to `FORECAST_PARAMS`. Pick the level whose geopotential height
  (Phase 1) is nearest the viewpoint's elevation; for 335–597 m sites this is usually 975 or 950, not 925.
- A linear ramp that **caps** the score (a strong wind across the cap churns the top of the sea): proposed
  full allowance at ≤ 5 m/s, cap to MARGINAL at 10 m/s, VETOED at ≥ 15 m/s. Priors, named constants.
- 10 m wind stays as it is. Extend the isolation test with the new winds.
- Tests: calm vs windy cap, level selection by height, missing data skips the cap.

### Phase 4 (optional, owner sign-off): sky above the sea at dawn

- High cloud does not decide whether an inversion forms; it decides the light on it. It belongs in Claude's
  light judgement, which already treats mid/high cloud as canvas. The change would be one sentence in the
  CLOUD INVERSION GUIDANCE of the **system prompt**: the shared cache prefix, near the prompt-regression
  suite. Recommendation: do not build unless outcome ratings show inversion mornings under-rated for
  colour. Never add high cloud to the formation score.

---

## 5. Considered and left out

- **Surface pressure as a term.** High pressure brings clear, calm and a warm cap, each scored directly; a
  pressure term counts them twice. The warm-layer walk is the direct evidence.
- **Relative humidity.** The gap again (`InversionScoreCalculator` class comment).
- **MWIS as an input.** Human-written, no API. Use it to check scores by hand (§6).
- **Night length.** Captured by the overnight window and the cooling already in the data.
- **Steam fog (water warmer than air) for mist.** No lake-temperature source (Open-Meteo marine covers the
  sea). A proxy (last week's mean air temperature against tonight's minimum) is a mist-only idea for later,
  once a few dated lake-mist mornings exist.
- **Recent rain for inversion.** Mist has a wet-ground term; inversion could copy the idea (its own term).
  Deferred to after Phase 2 shows whether the night terms are enough.

---

## 6. Validation, and what is missing

There is **no dated, observed inversion morning** in the project. Phases 1–3 are built on physics and
priors, exactly as M1 of the mist plan was. To limit that:

- **Before Phase 1b:** the owner supplies one or two dated fog-sea mornings (roamingthelakes posts, MWIS
  "cloud inversion likely" days, or the owner's own fell days) with the fell they were seen from. These
  become fixtures alongside the 1a measurements.
- **This winter:** log the `INVERSION_CALC` band against MWIS's inversion calls by hand (a table in §8 or
  a sibling doc). Cheap, and the first honest tuning data.
- **Outcome ratings:** the `seen` tag (`INVERSION`) on the "How was it?" block (V187) records what readers
  saw; the calibration report can read it once it holds enough rows.

---

## 7. Decisions for the owner

1. **Option B** (§4 Phase 2): may a clear, calm, moist night stand in for the low-cloud deck? Recommended:
   yes, using inversion's own reading, not the mist band.
2. **`overlooks_water`**: keep it, or let a fell overlooking a dry valley qualify (a new flag, or elevation
   alone)? Recommended: a new `overlooks_valley` flag set by hand, so no site joins silently.
3. **Overnight window** (Phase 2): existing T−3h, or ~21:00–05:00? Recommended: the longer window.
4. **Phase 4**: build or not. Recommended: not yet.
5. **If 1a shows the grid point is on the fell**: second valley sample per site, or accept the limitation.

---

## 8. Measurements (to be filled)

- 1a grid elevations and profiles for the ten sites: _pending_.
- Dated fog-sea mornings (owner has none of their own; candidates from a web search 2026-10-28, dates
  taken from search summaries because this container could not open the pages; confirm each before use):
  - **15 Jan 2025, Helvellyn**: UKC Hill Talk thread "lakes inversion today, Wednesday"; the Helvellyn
    fell-top assessor reported inversions; a walker put the cloud top at about 677 m, deeper over the
    south-west Lakes than the north-east. The only candidate with a height.
  - **17 Oct 2025, across the Lakes** including Scafell Pike: Cumbria Crack, "Cloud inversions spotted
    across Lake District" (date from the article URL).
  - **4 Nov 2024, Keswick / Skiddaw: a NEGATIVE case** ("no sign of inversion conditions here"), UKC.
    Worth as much as a positive: the score should read low.
  - Undated, date needs reading off the page: Times & Star, "Lake District cloud inversion spotted,
    Honister Pass"; Chris Gouge, "Cloud inversion & Brocken spectres on Helvellyn".
- MWIS comparison log: _pending_.
