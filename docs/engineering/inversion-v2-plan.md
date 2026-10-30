# Cloud inversion v2: measure the warm layer where the fells are (2026-10-28)

**Status:** plan, reviewed once (adversarial review 2026-10-28, folded in below). Phase 0 can start. Phase 1
starts with a measurement (§4 Phase 1a) and ships in **shadow mode** (§4): the new score is logged beside
the old one and drives nothing until a winter of comparisons says it should. The owner has no observed
inversion morning of their own and cannot get out to test; shadow mode, an automatic reanalysis check and
the dated candidates in §8 replace that.

**Prompted by:** three field tips from a Lake District photographer (roamingthelakes, Instagram, 2026-10-28):

1. *"A cloud inversion forms when the temperature on the ground is colder than the temperature at higher
   elevation. This usually happens following a cold, clear night during a period of high pressure."*
2. *"Choose a mountain summit that's likely to be above the forecast cloud level … viewpoints overlooking
   valleys and lakes."*
3. *"Light winds often favour cloud inversions … Stronger winds can mix the air and break up the cloud
   layer."*

These describe a **radiation inversion**: the ground cools under a clear, calm night, the air in contact
with it cools below the air above, and fog fills the valley under a warm cap. Checked against the code,
the inversion score looks for the warm layer above most viewpoints, measures it from a "surface" that is
probably the fell rather than the valley, never scores the clear night, and may veto the classic fog-sea
morning through its low-cloud gate (§1).

**The constraint the owner set:** improve inversion forecasting without losing valley mist. Mist
(`docs/engineering/valley-mist-plan.md`, built 2026-10-21) was prompted by the River Brathay sunrise on
17 Oct 2026 (Mike Ridley, Facebook: thin mist on the river, the fell behind in sun). Every phase runs
against the mist guards in Phase 0, and §3 lists the coupling rules.

Read §1 and §3 before §4.

---

## 1. What is true today

### 1.1 The inversion score (`service/InversionScoreCalculator.java`)

0–10, from the **event slot** only (`TimeSlotUtils.findBestIndex`: the earliest hourly slot at or after
sunrise, the latest at or before sunset):

| Part | What it reads | Effect |
|---|---|---|
| Moisture | temperature − dew point, via `MoistureScoring.gapScore` | 0–5 |
| Wind | 10 m wind, steps at 1.5 / 3 / 5 m/s | 0–3 |
| Low-cloud deck | `cloud_cover_low`; 25–65% earns 2, else 1 | 0–2 |
| Low-cloud gate | `cloud_cover_low` outside 15–80% | caps at 6.0 |
| Clearance gate | `elevation_m` ≤ `boundary_layer_height` | caps at 6.0 |
| Lapse gate | `temperature_925hPa` (else 850) − `temperature_2m` | < 0 caps at 6.0; < 2 °C or no profile caps at 8.0; ≥ 2 °C allows 10 |

Every score today is a whole number. Bands: NONE < 7 ≤ MODERATE < 9 ≤ STRONG. The prompt's inversion
block appears at 7 or more and **rounds** the score for display (`PromptBuilder` ~654); the hot topic and
`INVERSION_CALC` compare the **raw** score with 9 (`InversionHotTopicStrategy` ~116, ~161;
`TopicDailyLogJob` ~341). The user-message "Measured reversal … ~760m (925 hPa)" line comes from
`appendInversionReversal` (~852), which calls the public `InversionScoreCalculator.reversalCelsius`. The
**system prompt** names "~760m" in its MEASURED REVERSAL guidance (~226–231).

Eligibility (`ForecastDataAugmentor.augmentWithInversionScore`): `elevation_m ≥ 200` **and**
`overlooks_water`. V86 seeds **26 eligible sites from 200 m to 597 m** across the Lakes, the Dales,
Hadrian's Wall and Sutton Bank (e.g. Ashness Bridge 200, Tarn Hows 215, Orrest Head 238, Gummer's How
321, Loughrigg Fell 335, Cat Bells 451, Haystacks 597). Production may have changed since; read it there.

Readers: `slot_atmosphere.inversion_score` (V158) feeds `InversionHotTopicStrategy`, Coming up's "Valley
inversions" and `INVERSION_CALC`. Claude's echo feeds the map badge.

Production requests the **location's own** `lat`/`lon` (`ForecastService` ~170), with `timezone=UTC`.

### 1.2 What the tips expose

1. **The warm layer is looked for above most viewpoints.** 925 hPa is about 760 m (higher under high
   pressure); every eligible site is at 200–597 m. A fog sea seen from those heights sits under a warm
   layer in the lowest few hundred metres, around 975 hPa and 950 hPa, which are not requested.

   The owner's Brathay file (Open-Meteo, 16–20 Oct 2026, 54.431, −3.026, returned elevation 68 m; it
   requests 975/950 too) shows shallow warm layers at 975 hPa that 925 never sees:

   | Hour (UTC) | 975 hPa − surface | 925 hPa − surface |
   |---|---|---|
   | 16 Oct 05:00 / 06:00 | +0.3 / +0.8 | −2.7 / −2.5 |
   | 17 Oct 00:00 | +0.2 | −2.5 |
   | 18 Oct 01:00 / 02:00 / 04:00 / 06:00 | +1.0 / +1.6 / +0.4 / +0.2 (05:00: 0.0) | all negative |
   | 19 Oct 02:00 / 03:00 | negative | +0.6 / +1.7 (925 wind 29–32 mph) |

   ⚠️ **This is illustration, not evidence of a missed morning.** At the slots production reads (07:00 on
   17 and 18 Oct), every level is colder than the surface, and on 18 Oct the raw score is 4 anyway. The
   only 925 hits were warm air arriving aloft on a windy night. The file is a valley floor, not a fell.

2. **The "surface" is probably the fell, by design.** Open-Meteo's returned `elevation` is the 90 m DEM
   height of the requested point, and by default (`cell_selection=land`) `temperature_2m` is taken from a
   model cell of similar height and adjusted to that elevation with a standard lapse rate (**verify against
   the Open-Meteo docs in 1a**). Production requests the fell's own coordinates, so "surface − aloft" is
   measured from the fell top. Above a fog sea the fell sits in the warm cap, and the profile above it can
   show no reversal however good the sea below. This is the biggest problem in the current score and the
   first thing Phase 1a measures.

3. **The clear night is not scored** (tip 1). The score reads cloud only at the event slot.

4. **High pressure is not read**, and should not be (§5).

5. **"Above the cloud" is approximated by boundary-layer height** (tip 2), measured above the model's
   ground and compared with an above-sea-level elevation.

6. **The low-cloud gate may veto the textbook morning.** Valley-mist M0 showed the 2 km model does not
   fill valley fog: the Brathay cell held 0% low cloud all night on 17 Oct while mist lay on the river. On a
   clear-night fog sea the model may show near-zero low cloud, and the gate caps the score at 6. Unproven
   until a fog-sea morning is checked (§8).

7. **Tip 2 also names valleys without lakes.** `overlooks_water` excludes a fell above a dry valley.
   Owner decision, §7.

### 1.3 Valley mist, for contrast (`service/ValleyMistCalculator.java`)

Scores the night-side `MistTrend` slots (T−3h..event for sunrise, event..T+2h for sunset): clear night
0–2 (mean **total** cloud, ramp 10%→60%), calm 0–1.5 (minimum 10 m wind, ramp 2→4 m/s), moisture 0–4
(minimum gap via `MoistureScoring`), cooling 0–1.5, wet ground 0–0.5, confirmation 0–0.5; overcast ≥ 70%
or wind > 4 m/s caps at 3.9. Bands NONE < 4 ≤ POSSIBLE < 6 ≤ LIKELY, FOGBOUND by visibility < 300 m.
Gated on `locations.mist_prone` alone, any elevation (`augmentWithValleyMist`).

On the owner's Brathay file at 07:00 UTC (sunrise 06:41.8 UTC; production reads 07:00): clear 1.63 (mean
cloud 19.25%) + calm 1.50 (minimum 1.79 m/s) + moisture 2.40 (minimum gap 2.0) + cooling 1.50 = **7.03,
LIKELY**. At 06:00 it would read 5.03, POSSIBLE. ⚠️ LIKELY rests on a tie: the cooling term needs the last
night-side temperature not to exceed the first (`>` at `ValleyMistCalculator` ~326), and both are 5.8 °C;
0.1 °C warmer at 07:00 gives 5.53, POSSIBLE. The existing `ValleyMistCalculatorTest.brathay()` uses 06:00
and invented temperatures (its own comment says so).

---

## 2. What is shared today

`MoistureScoring.gapScore` and `MoistureScoring.MAX_SCORE`. Nothing else is read by both calculators.
Mist reads no pressure level, boundary layer, elevation or `overlooks_water`; inversion reads no
`MistTrend` and no `mist_prone`.

They are **chained**, not parallel: `ForecastService` (~245–251) runs `augmentWithInversionScore`, then
`augmentWithValleyMist` on its output. They write separate `slot_atmosphere` columns, hot topics and
prompt blocks, but a site can be both `mist_prone` and inversion-eligible, and then **both prompt blocks
sit in the same user message** and Claude's `mist_potential` echo (map badge, `forecast_score` MIST row,
the Mist hot topic's fallback through `SlotSignals.effectiveMistPotential`) can be swayed by the inversion
block beside it.

---

## 3. Coupling rules (hold for every phase)

1. **Mist's score and band must not move** on any Phase 0 fixture. Each phase runs the Phase 0 guards
   unchanged; a phase that needs to change one is out of scope and comes back to the owner.
2. **`MoistureScoring` is frozen** for this series. If inversion needs a different moisture curve it gets
   its own.
3. **No shared "fog-formation core" with shared weights.** An earlier draft (outside the repo) proposed one
   holding wind, gap, humidity and overnight cloud. Rejected:
   - humidity is the gap measured again (why it was removed from the inversion score);
   - mist already has a clear-night term on total cloud; a shared low+mid term would count clear sky twice
     for mist or replace its term, and either moves the 17 Oct score;
   - the two subjects want different things from cloud and wind.

   A pure helper that only reads (e.g. "mean cloud over these slots") may be shared; weights and ramps stay
   in each calculator.
4. **Fog depth must never reach mist.** A deep, well-capped layer is good for an inversion and bad for the
   riverside shot. No inversion-strength input may feed the mist score.
5. **Inversion may read mist's carriers, never the reverse, and every such read is named.** If Phase 2
   reads `MistTrend` (it carries the night-side slots), that is a dependency on a mist carrier: a test in
   the inversion suite names it, and **`MistSlot` is not changed for inversion's sake** (no new fields on
   it; inversion gets its own night carrier if it needs low/mid cloud).
6. **Open-Meteo load is a shared risk.** Production is on the free tier, weighted by variable count, and
   has had 429 storms (`application-prod.yml` ~75). A failed fetch leaves mist unscored too. Every
   variable added is counted against that (§4 Phase 1b).

---

## 4. Phases

One PR per phase, each branched from `main` after the previous merged (CLAUDE.md *How every code change
is made*). Every phase: Sonnet builds in its own worktree; the backend build ladder through `clean verify`
with the integration exclusion; a `changelog.d/` entry; `docs/engineering/reference/conditions.md`
updated where behaviour changes. No phase spends Anthropic money.

### Phase 0: mist guards (tests only)

- Copy the owner's file into `backend/src/test/resources/valley-mist/open-meteo-brathay-2026-10-16_20.json`
  unchanged, with a sibling `README.md`: source, coordinates, returned elevation 68 m, units (wind in
  **mph**), the timezone note (the file says `Europe/London` with offset 0 though 17 Oct is BST; daily
  maxima at 13–14h confirm the times are UTC), and that it lacks visibility, precipitation, weather code,
  radiation, boundary layer, total cloud and air quality.
- **Build the fixture through the production parser, not a hand mapper.** `extractAtmosphericData`
  requires the missing fields (`requireDouble`/`requireInt`), `extractMistTrend` returns null without
  visibility, and the air-quality hourly must be non-null. So: deserialise the file into
  `OpenMeteoForecastResponse` (it ignores unknown fields), patch the hourly arrays with its setters, then
  call the real `extractAtmosphericData` so `findBestIndex` and `extractMistTrend` are exercised:
  - visibility 20,000 every hour (never 0: `visibilityMetres` is a primitive int and 0 reads FOGBOUND);
  - precipitation 0.0; `cloud_cover` = max of the three layers (exact here: mid and high are 0 overnight);
  - wind × 0.44704 (mph → m/s); dummy weather code, shortwave radiation and boundary layer;
  - an air-quality response with an empty hourly.
  Each patch is commented in the test.
- **Real-data test**: event 17 Oct 06:41 UTC SUNRISE, so the parser picks 07:00 itself. Assert band
  **LIKELY**, score **7.03 ± 0.01**, sub-scores pinned in a comment, and the tie noted (§1.3). A second
  case at a sunrise that resolves to 06:00 asserts POSSIBLE (5.03).
- **Fix `ValleyMistCalculatorTest.brathay()`**: keep it (it pins the invented-cooling case) and correct its
  comment's event hour and provenance. No other M0 fixture or assertion changes.
- **Isolation test** (`ValleyMistIsolationTest`): run the real chain, `augmentWithInversionScore` then
  `augmentWithValleyMist`, on the real fixture, varying only inputs mist must not read: 925/850
  temperatures, boundary layer, elevation, `overlooks_water`; later phases add their new inputs here.
  Assert the mist score and band are identical.
- **Prompt isolation test**: for a site that is both `mist_prone` and inversion-eligible, the
  `VALLEY MIST CONDITIONS` block text is identical whatever the inversion inputs. (It cannot test what
  Claude does with the neighbouring block; see the production query below.)
- **Inversion baseline**: the same file scores inversion **== 6.0** at 07:00 for a 335 m
  `overlooks_water` site (every level colder than the surface). Phase 1 should leave this at 6.0.
- **Production query (owner, read-only)**: which sites are `mist_prone` AND `elevation_m ≥ 200` AND
  `overlooks_water`? Those are the only sites where an inversion change can reach mist through Claude.
  Record the answer in §8.

### Phase 1a: measure first (no code)

For every eligible site, at the coordinates production actually sends (the location's `lat`/`lon`), and
for a valley-floor point below each (picked by hand):

- Open-Meteo's returned `elevation`, and the hourly `temperature_2m`, `temperature_975hPa`/`950hPa`/
  `925hPa`/`850hPa`, `geopotential_height_975hPa`/`950hPa`/`925hPa`/`850hPa`, low cloud and 10 m wind,
  for the dated candidates in §8, using the historical-forecast API exactly as the Brathay file was made.
- Confirm from the Open-Meteo docs how `temperature_2m` is downscaled (`elevation`, `cell_selection`).
- Check whether the models `best_match` hands over to at longer lead times provide 975/950 hPa at all.
- Check whether the archive API (`archive-api.open-meteo.com`, already used by `OpenMeteoArchiveClient`)
  serves ERA5 pressure-level temperatures; that decides whether the automatic check below is possible.

- **The `elevation=` request parameter.** For two or three sites, fetch the same point with and without
  `elevation=<valley floor>` and record how `temperature_2m` changes. This tells us whether the parameter
  gives a usable valley-floor surface temperature from the same coordinates (owner idea, 2026-10-29).
- **The automatic valley-floor estimate** (Phase 1-pre below): for each eligible site, the lowest of a grid
  of Open-Meteo elevation points within about 2 km, checked by eye against the map. Wrong answers to look
  for: a neighbouring valley, a lake surface, a site on a broad plateau with no valley below.
- **Why the returned elevation moved.** Stainton came back as 235 m in one fetch and 214 m in the next for
  the same coordinates (§8). Find out why before any design depends on that field.

This container cannot reach Open-Meteo or most of the web (proxy), so the owner runs these requests or a
session with network access does. Results go in §8. **The answer to "is the surface the fell?" decides the
shape of 1b**, so 1b is not designed in detail until 1a is in.

### Phase 1-pre: location heights (owner idea, 2026-10-29; its own PR, before 1b)

Three heights, kept apart:

1. **The viewpoint's height is already a static, automatic property.** `locations.elevation_m` is filled on
   add by `LocationEnrichmentService.fetchElevation` (Open-Meteo's elevation API, a 90 m Copernicus DEM)
   and shown in the add dialog; the inversion score reads this stored value, not the forecast response's.
   The gaps:
   - it is fetched only at add time, and **cannot be edited afterwards** (`UpdateLocationRequest` has no
     elevation field; the inline editor has no box);
   - **older locations are often null** (valley-mist M0 found 18 of 125 candidates with a value);
   - a 90 m DEM cell averages a summit with its slopes and can read tens of metres low.

   **OS maps were considered and rejected for now.** OS Terrain 50 is free (OGL) but a bulk download,
   not a lookup API; the OS Data Hub APIs need an account key. The accuracy is not needed: pressure levels
   are about 220 m apart, so ±20–30 m does not change a result. Fixes:
   - a one-off backfill of null `elevation_m` from the same API;
   - an editable elevation field in the location editor, so a summit can be corrected by hand from the OS
     map, as the V86 values were.
2. **The height the forecast's 2 m temperature refers to is set, not stored.** Open-Meteo chooses it
   unless told, hence 235 m vs 214 m. Pass `elevation=` on the request so the surface-versus-aloft
   comparison is deterministic (which height to pass is decided by 3 and by 1a).
3. **The valley-floor height is a new static property, auto-suggested.** It is what the inversion
   comparison needs: fog forms in the valley below the viewpoint. Open-Meteo's elevation API takes up to
   100 coordinates per call, so on add or edit one call can sample a grid within about 2 km and suggest
   the lowest point; the editor shows "Valley floor ≈ 120 m (suggested)" and the admin confirms or
   corrects it. A nullable column, written by the editor only, never inferred silently at forecast time.

Scope of this PR: the backfill, the editable elevation, the suggested and confirmed valley-floor field
(migration, entity, `AddLocationRequest`/`UpdateLocationRequest`, enrichment, editor UI), tests. **It
touches the UI**, so it gets the CLAUDE.md adversarial review cadence (about six lenses), the frontend's
four commands, and role gating (admin-only, like the rest of location management). It changes no score;
1b decides how the valley floor is used.

### Phase 1b: the warm layer at the right height, in shadow mode

Sketch; finalise after 1a.

- **The surface reference.** If 1a confirms the 2 m surface is the fell, the reversal must be measured from
  a valley-floor temperature. Options, for the owner (§7): a valley-floor point per site (new location
  columns, set by hand like `mist_prone`; one more forecast sample per site, so more Open-Meteo load), or
  the same point re-requested with Open-Meteo's `elevation=` set to the valley floor (also an extra
  request). There is no zero-cost option; the extra requests count against rule 6.
- **New variables**, nullable everywhere (`getDoubleValue`, never `require*`, or a model without 975/950
  fails the whole fetch and mist goes unscored): `temperature_975hPa`, `temperature_950hPa`,
  `geopotential_height_975hPa`/`950hPa`/`925hPa`/`850hPa` (heights, not standard-atmosphere guesses: under
  1025–1030 hPa, 975 hPa sits near 420–460 m, not 320 m). Consider whether they belong in
  `FORECAST_PARAMS` (which also serves `fetchForecastBriefing` and the astro batch) or in a second constant
  for inversion-eligible sites only. Count the added load (24 → 30 variables if all are added to the one
  constant) and decide against the 429 history.
- **Plumbing.** Parse the response's top-level `elevation`, and carry it to the calculator and to
  `PromptBuilder`. Append the new `WeatherData` fields **after** `precedingPrecipitationMm` (it is
  positional with several adjacent `Double`s, and `precedingPrecipitationMm` is mist's wet-ground input:
  a swap would compile and silently move mist). Change `reversalCelsius`'s signature with its one caller.
- **Profile walk.** From the reference surface upward over 975 → 950 → 925 → 850: skip any level whose
  geopotential height is not at least a minimum clearance (proposed 50 m) above the reference elevation;
  *reversal* = the warmest level of the **first** rise above the surface (the first local maximum walking
  up, so a separate warm layer aloft cannot mask a surface inversion); *warm-layer top* = that level's
  geopotential height. Fall back to 925-only behaviour when the new levels are null, and document that
  this happens at longer lead times.
- **Clearance.** Fog tops out in the lower part of the warm layer, so requiring the viewpoint to be above
  the warm-layer top is stricter than the physics. Proposed: viewpoint above the first warm level's height
  clears; within one level spacing below it caps at MARGINAL; further below caps at VETOED. Thresholds are
  priors; the review of 1b decides them with 1a's numbers in hand. The boundary-layer check is dropped
  where a profile exists (with no reversal the lapse gate already caps at 6, so the fallback adds nothing).
- **Shadow mode.** The new score is computed and logged (`[INVERSION v2] site date event old=… new=…
  reversal=… top=… ref=…`), and optionally stored in a new nullable `slot_atmosphere` column; **the
  existing score keeps driving the hot topic, Coming up, `INVERSION_CALC` and the prompt.** Switching over
  is its own small PR after a winter of comparisons (§6), and it records the switch date in
  `conditions.md` so the `INVERSION_CALC` series never mixes the two regimes silently.
- **Prompt.** Nothing changes while in shadow. At switch-over the user-message reversal line states the
  level and the top; the system prompt's "~760m" wording changes only with the owner's sign-off (shared
  batch cache prefix, near the prompt-regression suite). `PromptGoldenMasterTest` (darksky-inversion
  fixture) and `PromptBuilderTest` (~1480–1509) string checks will need regenerating then; they are not
  prompt-regression assertions.
- Tests: the walk (levels below the minimum clearance skipped, first local maximum chosen over a warmer
  layer aloft, nulls fall back to 925), clearance boundaries, the Brathay baseline still 6.0, the isolation
  test extended with every new input.

### Phase 2: the clear night (inversion only, shadow first)

- An inversion-only night term, on its **own** carrier extracted from the same hourly arrays (no new
  request; `MistSlot` untouched, rule 5). Window: decide in review between the existing T−3h and roughly
  21:00–05:00 local; define SUNSET explicitly (proposal: the inversion is a dawn subject; score the
  coming night only for a sunrise, and leave SUNSET as today).
- Cloud measure: **mid + high** over the window, or low cloud excluding the last two hours before the
  event. Low cloud forming in the valley before dawn is the fog itself (18 Oct 06:00 had 54%) and must not
  count against the night.
- **Scoring shape.** A ramp makes scores fractional for the first time. Before building, pick one rule for
  the band boundary (round everywhere, or compare raw everywhere) so the prompt's rounded "9/10 STRONG"
  and the raw-score hot topic cannot disagree. An additive term under a cap of 10 lifts NONE and MODERATE
  mornings and brings back the "calm saturated dawn reads STRONG" problem the class comment describes; so
  the proposal is a **damping multiplier or a cap** (an overcast night caps the score), not an added point.
- **Option B (owner decision, §7):** let a clear, calm, moist night (inversion's own reading of it, not the
  mist band) stand in for the low-cloud deck when `cloud_cover_low` is under 15% at the event.
- Tests: clear vs overcast nights, ramp ends, the cap, the gate bypass, the rounding rule, unchanged scores
  for existing `InversionScoreCalculatorTest` cases with no night data.

### Phase 3: wind at summit height (inversion only, shadow first)

- `wind_speed` at the levels from Phase 1 (consider 900 hPa, which fills the 925–850 gap), nullable. Pick
  the level whose geopotential height is nearest the viewpoint; for 200–600 m sites usually 975 or 950.
- A ramp that **caps** the score (wind across the cap churns the top of the sea): proposed no cap at
  ≤ 5 m/s, MARGINAL at 10 m/s, VETOED at ≥ 15 m/s. Priors, named constants. 10 m wind unchanged.
- Count the load (rule 6). Extend the isolation test. Tests convert the fixture's mph.

### Phase 4 (optional, owner sign-off): sky above the sea at dawn

High cloud does not decide whether an inversion forms; it decides the light on it, which Claude already
judges (mid/high cloud as canvas). The change would be one sentence in the system prompt's CLOUD
INVERSION GUIDANCE. Recommendation: do not build unless outcome ratings show inversion mornings
under-rated for colour. Never add high cloud to the formation score.

---

## 5. Considered and left out

- **Surface pressure as a term.** High pressure brings clear, calm and a warm cap, each scored directly.
- **Relative humidity.** The gap again.
- **MWIS as an input.** Human-written, no API. Use it to check scores by hand (§6).
- **Night length.** Captured by the night window.
- **Steam fog for mist** (water warmer than air). No lake-temperature source; a later, mist-only idea.
- **Recent rain for inversion.** Deferred until Phase 2 shows whether the night terms are enough.

---

## 6. Validation without going out

The owner cannot get out often enough to test, so validation is built from data the system already has
or can fetch:

- **Shadow logs** (Phases 1–3): old and new scores side by side every day.
- **Automatic reanalysis check** (if 1a confirms the archive serves ERA5 pressure levels): after each
  morning, fetch the reanalysis profile at the site and record whether the warm layer the forecast
  promised was there. ERA5 cannot see valley fog (31 km), but it can confirm the cap; this follows the
  V175 "recorded, never required" pattern of `cloud_verification`.
- **Dated public reports** (§8), turned into fixtures from the historical-forecast API.
- **Other readers' ratings**: the `seen` tag `INVERSION` on "How was it?" (V187).
- **MWIS by hand** when convenient: their "cloud inversion likely" days against the shadow log.

Switch-over (shadow → live) is an owner decision on that evidence.

---

## 7. Decisions for the owner

1. **Surface reference** (after 1a): a valley-floor point per site, or `elevation=` re-request, or accept
   the fell-top reference. Each extra sample costs Open-Meteo load. The owner's lean (2026-10-29): a
   valley-floor height per site, auto-suggested in the location editor and confirmed by hand (Phase 1-pre).
2. **Option B** (Phase 2): may a clear, calm, moist night stand in for the low-cloud deck? Recommended:
   yes, inversion's own reading.
3. **`overlooks_water`**: keep, or add a hand-set `overlooks_valley` flag? Recommended: the new flag.
4. **Night window** (Phase 2): T−3h or ~21:00–05:00. Recommended: the longer window.
5. **Phase 4**: recommended not yet.
6. **High viewpoints** (2026-10-30, from §8): both confirmed inversion mornings topped out at about
   700–900 m, above every roster site. Add a few summits (Blencathra, Helvellyn, Skiddaw, Scafell Pike,
   Red Pike) as inversion viewpoints, or accept that the roster scores shallow valley fog only?
   **Decided 2026-10-30: both.** Score shallow valley fog for the 200–600 m roster AND add high
   viewpoints. Added the same day by the owner (direct `INSERT`, region 3, sub-region copied from a
   neighbour, `overlooks_water`, sunrise and sunset, LANDSCAPE, OS summit heights): **264 Helvellyn
   (950 m), 265 Scafell Pike (978 m), 266 Blencathra (868 m)**. Grid cells left for the nightly
   `GridCellBackfillJob`; no Bortle. Until 1b they are scored by today's calculator, whose 925 hPa
   comparison happens to sit near summit height (right on 15 Oct at Blencathra, vetoed by cloud on 4 Nov
   at Skiddaw); 1b scores them properly, and their shadow-mode record starts now.
7. **Switch-over** from shadow, per phase, on §6's evidence.

---

## 8. Measurements and candidates (to be filled)

- **Production query (2026-10-30, owner, read-only `COPY` from `goldenhour-db`):** 26 enabled sites are
  inversion-eligible, 200–597 m, exactly the V86 set. **No enabled site is `mist_prone`**: the query asked
  for inversion-eligible OR mist-prone, and returned only the 26, all with `mist_prone = f`. Two
  consequences:
  1. **There is no overlap today**, so no inversion change can reach mist through Claude's echo. The
     Phase 0 prompt-isolation test still matters, because the overlap appears the moment a site is ticked.
  2. **Valley mist is dormant in production.** `augmentWithValleyMist` scores only flagged sites, so the
     calculator, the "Valley mist" hot topic, the Coming up condition, the triage exemption, the prompt
     block and the `MIST_CALC` log have nothing to work on. The valley-mist plan's Q5 shortlist (the
     Brathay, Rydal, Grasmere, Buttermere, the Derwent Water shores, the Dales sites, the Hadrian's Wall
     sites) was never ticked. That is an owner action in the location editor, not code.
     ⚠️ Three Wall sites on that shortlist are also inversion-eligible here (Steel Rigg & Winshield Crags
     345 m, Highshield Crags 300 m, Cawfields 250 m): ticking them creates the overlap at once.
  3. **Ticked the same day (2026-10-30, owner, `UPDATE … RETURNING`, 37 rows):** ids 156, 177, 175, 166,
     169, 173, 189, 190, 194, 197, 159, 182, 179, 168, 157, 165 (Lakes shores, tarns, valley floors);
     111, 102, 114, 136, 141, 137, 131, 132 (Dales); 223, 213, 214 (Northumberland); 41, 46, 43, 45, 48,
     49, 50, 42, 44, 47 (Hadrian's Wall). Left out on purpose: Rydal Cave and Rydal Grotto (shot from
     inside), Surprise View (a viewpoint above the lake), Bothal Woods (woodland has its own mist path);
     Lamley Viaduct did not match. **Five sites are now both mist-prone and inversion-eligible**
     (Malham Tarn, Tarn Hows, Cawfields, Steel Rigg & Winshield Crags, Highshield Crags), so the Phase 0
     prompt-isolation test now guards a live case. Many ticked sites have no `elevation_m`; mist does
     not need it, and Phase 1-pre's backfill will fill it.
- **1a profiles: reported mornings** (owner, 2026-10-30, historical-forecast API; raw files in
  `docs/engineering/inversion-v2-data/`). Each fetch is a summit plus valley points. **All three dates were
  checked against the original posts by the owner (2026-10-30)**, which corrected two of them (below).
  Reversals are "level minus the point's own `temperature_2m`", with each level's geopotential height.
  - **(a) 15 Jan 2025, Helvellyn, inversion reported (date confirmed).** The UKC thread: the Helvellyn
    fell-top assessor reported "cloud inversions putting on a great show"; one poster thought the top
    "looked quite low, sub 700 m"; from Helvellyn at about 11:00 the cloud was "surging over" Fairfield
    (873 m) with Cofa Pike (820 m) poking out, and the north almost clear. ⚠️ The "677 m" in the first
    draft was a misreading: 676 m was the poster's own height in the Howgills, where they "failed to break
    the inversion". **Reported cloud top: roughly 700–870 m, varying across the fells.** Valley (Glenridding,
    161 m) at 09:00: 975 hPa −0.8 at 469 m, 950 −0.1 at 682 m, 925 +0.8 at 902 m; earlier hours 950
    +0.6..+1.3, 925 +1.5..+2.6. Thirlmere (260 m) the same shape. Valley visibility 180 m at 06:00 (fog),
    low cloud 23–63%. **The warm layer starts at about 470–680 m and runs to about 900 m; the reported top
    sits inside it.**
  - **(b) Wednesday 15 Oct 2025, inversions reported (date corrected).** The Cumbria Crack article is
    dated 17 Oct but describes "this week": Scafell Pike on Wednesday EVENING ("stayed until dark"),
    Blencathra (868 m) on Wednesday MORNING from 05:30, Red Pike (755 m) from Buttermere on an unstated
    day. Re-fetched for 14–16 Oct with 900 hPa added and Blencathra plus Scales below it
    (`2b2-scafell-blencathra-2025-10-15.json`; the first file, `2b-scafell-2025-10-17.json`, is the wrong
    two days and is kept only as a 16–17 Oct record).
    - Morning, 07:00 (sunrise slot), Keswick 92 m: 975 −2.3 at 470 m, 950 −3.1 at 683 m, **925 +0.2 at
      903 m, 900 +2.3 at 1,130 m**, 850 +1.2. Temperature falls to about 680 m then rises 3.3 °C by 900 m:
      **warm-layer base between about 680 and 900 m, top near 1,130 m.** Valley low cloud 78% (it formed
      between 05:00 and 06:00 after a clear night: 2–11% at 03:00–05:00). Scales (152 m) the same shape.
      **Blencathra's summit cell (836 m): 0–12% low cloud, 10 km visibility: above it.** Matches the
      report.
    - Evening, 16:00 (sunset slot), Wasdale 163 m: 950 −4.1 at 675 m, 925 −2.1 at 894 m, 900 +0.6 at
      1,122 m; valley low cloud 97%. Base again about 680–900 m. Scafell's summit cell (961 m) 89% low cloud
      at 16:00, 41% at 17:00: **the top was close to the summit**, consistent with a viewer on the top.
    - Tuesday 14 Oct had the same layer (925 +1.2..+3.5 over the valleys); Thursday 16 Oct did not (every
      level to 900 hPa colder over the valleys). "This week" checks out.
  - **(c) 4 Nov 2024, Keswick/Skiddaw, NO inversion (date confirmed).** UKC, 4 Nov 2024: "murky… thicker
    cloud around and above the summits, no sign of inversion"; Wensleydale "cloud base above 600 m but not
    by much… doesn't look like you'd get above the clag"; cloud around the tops in the western Highlands.
    Profile: warm layer between 864 m and 1,552 m (no 900 hPa in this fetch), valley low cloud 80–100%;
    Skiddaw's cell (911 m) 96% low cloud and 2.6 km visibility (in cloud). At 925/850 alone this looks like
    an inversion morning; only the summit cell's cloud says otherwise. **900 hPa is needed** (the 15 Oct
    fetch shows it placing the warm layer well), and the summit's own cloud and visibility are a useful
    second signal.
  - **The summit trap, confirmed.** At the summit points (836–961 m), 975 and 950 hPa sit BELOW the
    ground, and "975 minus surface" reads +3 to +11 °C on every morning, including (c) with no inversion.
    That is the ordinary lapse rate, not an inversion. The 1b rule "skip any level not above the reference
    height" is required, not optional.
  - **What these say about the clearance rule.** The reported cloud tops sit inside the warm layer, near its
    lower part: (a) layer from about 470–680 m, tops 700–870 m; (b) layer from about 680–900 m, Red Pike
    (755 m) and Blencathra (868 m) above it. 1b's clearance should compare the viewpoint with the warm
    layer's BASE (conservatively) and treat the band up to the next level as MARGINAL.
  - ⚠️ **The roster cannot see these seas.** Both confirmed inversion mornings topped out at about
    700–900 m, and every inversion-eligible site is at or below 597 m. **No roster site could have seen
    either.** Two regimes follow, and v2 must keep them apart:
    1. *Deep seas* (stratus under a warm layer at 700–1,500 m, the ones people photograph from summits):
       visible only from viewpoints above about 750 m. The roster has none. Adding a few high viewpoints
       (Blencathra, Helvellyn, Skiddaw, Scafell Pike, Red Pike) is an owner decision (§7).
    2. *Shallow valley fog* (radiation fog tens to a few hundred metres deep, the Latrigg/Cat Bells shot
       over Derwent Water): visible from the 200–600 m roster. Its top sits below 975 hPa (about 450 m),
       so the pressure levels cannot place it; the valley's own surface readings (the mist set-up) are
       the evidence. This argues for Option B (§4 Phase 2) and for not letting a deep-sea reading boost a
       roster site.
  - **A likely false positive today.** On 15 Oct 07:00, scored on the Keswick cell's numbers for a 451 m
    viewpoint, the current calculator gives gap 1.9 → 3, wind 0.9 → 3, low cloud 78% → in band, 1 point
    = 7; boundary layer 85 m < 451 m passes the clearance gate; 925 reversal +0.2 caps at 8 → **7,
    MODERATE: the prompt's inversion block and a rating boost, for a viewpoint that was inside a cloud
    sea topping out at 700–900 m.** (Illustrative: production scores each site on its own cell.) The
    boundary-layer clearance gate is the weak link: 85 m said "clear" while the cloud reached 900 m.
- **1a `elevation=` test (Cat Bells, 451 m; raw file `3-catbells-elevation.json`).** Same coordinates
  (54.568, −3.170), three requests:
  - `elevation=nan` (Open-Meteo's choice): snapped to 54.574, −3.183, **252 m**;
  - `elevation=451`: snapped to 54.556, −3.213, **about 3 km west**;
  - `elevation=80`: snapped to 54.575, −3.152, **80 m** (the Derwent Water shore).

  So the parameter **changes which model cell is used** (it picks a nearby cell of similar height) rather
  than only adjusting the lapse rate. The three cells' temperatures differ by up to 3 °C and not in a
  fixed lapse order (on 30 Oct at 18:00 the 252 m cell was coldest: 6.9 °C against 8.3 at 451 m and 9.6
  at 80 m). Consequences:
  1. **Production's Cat Bells forecast today comes from a 252 m mid-slope cell**, neither the summit
     (451 m stored) nor the lake. The current inversion score compares 925 hPa with that.
  2. `elevation=<valley floor>` **does give a valley-floor cell from the same coordinates**, so the owner's
     idea works, but the chosen cell can be up to a few km away and could be the wrong valley. Phase 1-pre's
     valley-floor suggestion should store the cell's coordinates too, and the editor should show them.
  3. This also explains Stainton's 235 m vs 214 m: different requests snapped to different cells.
- **Model coverage of 975/950 at long lead times:** _pending_ (the historical-forecast API served them for
  all three dates, but that is the short-range model).
- Dated fog-sea mornings (the owner has none of their own; candidates from a web search 2026-10-28, dates
  taken from search summaries because this container could not open the pages; confirm each before use):
  - **15 Jan 2025 (a Wednesday), Helvellyn**: UKC Hill Talk thread "lakes inversion today, Wednesday";
    the Helvellyn fell-top assessor reported inversions; a walker put the cloud top at about 677 m,
    deeper over the south-west Lakes than the north-east. The only candidate with a height.
  - **17 Oct 2025, across the Lakes** including Scafell Pike: Cumbria Crack, "Cloud inversions spotted
    across Lake District" (date from the article URL).
  - **4 Nov 2024, Keswick / Skiddaw: a NEGATIVE case** ("no sign of inversion conditions here"), UKC.
    Worth as much as a positive: the score should read low.
  - Undated, date to be read off the page: Times & Star, "Lake District cloud inversion spotted, Honister
    Pass"; Chris Gouge, "Cloud inversion & Brocken spectres on Helvellyn".
- Observed valley-mist mornings (mist guards, not inversion evidence):
  - **17 Oct 2026, River Brathay**: Mike Ridley's photographs; the Phase 0 fixture (7.03, LIKELY).
  - **29 Oct 2026, A66 near Penrith (Eden valley farmland), about 09:00 GMT**: the owner's own photographs
    from a car. Shallow mist at tree height over open fields, sky visible through it; frost or heavy dew on
    the grass; standing puddles (recent rain); broken cloud overhead, roughly half the sky, low sun
    breaking through. Sunrise about 07:12 UTC, so production reads the 08:00 slot and 09:00 is T+1h in
    the `MistTrend`. Not a riverside site: mist covered the valley floor. **Location and time confirmed
    from the photos' metadata (iPhone Photos info): 09:02–09:08 GMT, WEST of Penrith along the A66** —
    by Ullswater Community College on the edge of Penrith (09:02), Redhills by the Rheged roundabout
    (09:06), and just west of Stainton (09:08, two photos). So the **west point** below is the one that
    matches. The owner fetched both (Open-Meteo forecast API, `past_days=1`, 2026-10-29; pasted
    in the session, not yet saved as a file): east 54.648, −2.687, returned elevation 133 m; west
    54.648, −2.811, returned elevation 235 m.
    - **Same-morning contrast: Keswick, 09:22 GMT, no mist** ("it's clear"; the owner, driving west on
      the A66, about 20 km on). Caveat: 09:22 is 2 h 10 min after sunrise, so it cannot tell "never
      formed" from "already burned off". **Fetched** (second fetch, same session, both points in one
      file: `open-meteo-stainton-keswick-2026-10-27_29.json`; Keswick snapped to 54.593, −3.121,
      returned elevation 92 m). Keswick's night was overcast: mean total cloud 96% from 21:00 to 05:00
      and 73–100% over 05:00–08:00, against Stainton's 81% and 63%; gap 1.1 °C at best. Score:
      clear 0 + calm 1.50 + moisture 2.40 + cooling 0 + wet 0.03 + confirmation 0 = 5.43, **capped at
      3.90 by the overcast veto, NONE**. **So the pair discriminates, the right way round**: POSSIBLE
      where mist was seen, NONE where it was not, for a stated reason (cloud). A genuine contrast, not
      only burn-off timing.
    - **Second fetch of Stainton** (same coordinates, a later model run): returned elevation 214 m
      (235 m in the first fetch; Open-Meteo's reported elevation differs between the two responses
      for the same point, worth knowing for Phase 1a), and the 05:00–08:00 hours moved (cloud 100, 66,
      74, 11 instead of 73, 64, 41, 84). The score did not: 0 + 1.50 + 4.0 + 0 + 0 + 0.25 = **5.75,
      POSSIBLE**. Sunrise 07:05 UTC at both points, so production reads the 08:00 slot. The fixture
      uses this second fetch.
    - **What `ValleyMistCalculator` would say (hand-computed, 08:00 slot, night side 05:00–08:00):**
      east clear 0 (mean total cloud 67.5%) + calm 1.42 (min 2.1 m/s) + moisture 4.0 (min gap 0.3 °C)
      + cooling 0 + wet 0 + confirmation 0.25 (6,880 m) = **5.67, POSSIBLE**; west 0 + 1.50 + 4.0 + 0 +
      0 + 0.25 = **5.75, POSSIBLE**. Mist was seen, so this is one band short: no hot topic (LIKELY
      only) and no map badge.
    - **Why it fell short** (observations from ONE morning, not a case for retuning):
      1. *The clear spell was brief and before the window.* Cloud overnight was mostly 85–100%, with a
         clearance at 03:00–04:00 (east 59% then 6%) when the air cooled 9.3 → 7.0 °C and the gap
         closed to 0.2 °C; by 05:00 cloud was back to 60–94%. The T−3h window starts at 05:00.
      2. *The cooling term reads after sunrise.* The window ends at the 08:00 event slot, by which the
         air was warming (east 8.4 → 10.1 °C), so cooling scores 0. The real cooling was 01:00–04:00.
         (Brathay's LIKELY also rests on this term, on a tie; any change here moves Brathay.)
      3. *Wet ground misses "rain yesterday".* The term sums 6 h before the event; the rain (east
         ~3 mm, west ~8 mm) fell 01:00–12:00 the previous day.
      4. *The model saw the mist before dawn.* Visibility fell to 2,600 m (east, 04:00) and 2,800 m
         (west, 05:00), but the term reads the event slot only (6,880 m, half weight).
    - **Inversion-relevant:** a shallow surface inversion before dawn (east 04:00: 975 hPa +2.5 °C,
      950 +1.5, 925 +0.4 over the surface; west 05:00: 975 +2.0), gone by the 08:00 slot (975 −1.1)
      while the mist lasted to 09:00. So **reading the profile only at the event slot can miss the
      inversion that made the fog**; Phase 1b should consider the strongest pre-dawn reversal over the
      night-side slots. 975 hPa sat at 415–432 m (not the standard atmosphere's ~320 m), as the review
      predicted. Boundary layer 135–190 m overnight.
    - **Mist follow-ups (a separate, mist-only series; not part of inversion v2, and each must hold
      the Brathay guard):** cooling measured to the pre-dawn minimum rather than to the event slot; a
      wet-ground window long enough for "rain then a clear night"; confirmation from the night-side
      minimum visibility. Decide only with more than one morning; record the next misty morning the
      same way.
- MWIS comparison log: _pending_.
