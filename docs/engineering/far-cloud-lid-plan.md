# Far cloud on the sun's line: what blocks the light we do not sample

**Status:** draft, revised after adversarial review, 2026-10-13. Nothing built. Owner decisions in §9.
**Trigger:** Angel of the North, 13 Oct 2026 sunset. Forecast 4★ (Fiery Sky 72, Golden Hour 78). The
owner recorded 2★ (`actual_outcome` id 1, inserted with `actual_rating` set by SQL because
`POST /api/outcome` cannot carry a star; a suggested task covers that gap).
**Builds on:** `cloud-approach-veto-fix.md` §8–§10 and `adversarial-solar-cloud-physics-review.md`.
Nothing here overrides them; where this plan touches one of their conclusions it says so.
**Review record:** §10 lists what the review changed.

---

## 1. What happened, and what it does and does not show

From the owner's report and photos:
- Five minutes before sunset a dark bank along the western horizon had already hidden the sun.
- Overhead, thin cirrus was pale.
- After sunset there was a short pink fringe on low and mid streaks just above the horizon.

The owner's rating: 2★, "I'd have been disappointed if I'd taken a chance on that."

Production rows (`forecast_evaluation`, location 64, sunset):

| Run (UTC) | Model | Rating | Cone low / mid / high | 226 km low |
|---|---|---|---|---|
| 10 Oct 01:06 (T+3) | HAIKU | 4 | 31 / 13 / 19 | 0 |
| 11 Oct 01:06 (T+2) | HAIKU | 3 | 12 / 10 / 0 | 3 |
| 12 Oct 01:07 (T+1) | SONNET_55 | 3 | 28 / 0 / 0 | 100 |
| 12 Oct 14:05 (T+1) | SONNET_55 | 3 | 0 / 0 / 0 | 2 |
| 13 Oct 01:06 (T+0) | SONNET_55 | 3 | 29 / 22 / 0 | 0 |
| **13 Oct 14:22 (T+0)** | SONNET_55 | **4** | **7 / 24 / 91** | **0** |

"Cone" is the 113 km solar cone. Overhead read 0% at every layer on the 14:22 run.

What changed into the 4★ is **two** inputs, not one:
- cone low fell from 29 to 7, crossing the prompt's `< 20` gate for IDEAL and the rate-4 floor;
- cone high rose from 0 to 91.

The 14:22 summary ("low cloud just 7% under a heavy high-cloud canvas") is the IDEAL rule firing
(`PromptBuilder.java:120-121`). Run-to-run sampling noise is also real: the Copt Hill fixture's
fixed input gave Fiery Sky 28–68 over 25 Haiku runs. So the raise is not yet attributed to either
input, or to the model.

**The comparison cases are weaker than the first draft claimed:**
- **Low Hauxley, 4 Oct 2026, sunrise** (coastal, looking east over the North Sea):
  - 113 km cone 0 / 10 / 100, 226 km low 1;
  - overhead mid and high 100, antisolar 100 / 100 / 100;
  - "worth going", pinned as an exact-4 regression case whose user message is byte-identical to the
    stored production prompt (`LowHauxleyPromptFidelityTest`).
- **Copt Hill, 11 Mar 2026, sunset** is **not** a like case:
  - it was sampled at **50 km** (the offset then; the fixture javadoc says so);
  - it is a hand-built fixture;
  - its recorded failure has its own explanation: 70% low cloud 87 km upwind and a [BUILDING]
    trend.

  It is a different-mechanism control, not a second instance.

So there is one bad case and one good case. They share a clear low gate and a high deck at 113 km,
and they differ in at least these ways, all already measured:
- event (sunset vs sunrise);
- direction and terrain (west over the Pennines vs east over open sea);
- overhead canvas (0 vs 100);
- antisolar canvas (0 vs 100).

"Nothing we measure separates them" is wrong. What is true is narrower: **nothing on the sun's line
beyond 113 km is measured**, so we cannot tell whether a far blocker is the difference.

## 2. Geometry, done properly

R = 6371 km. Distances are geometric. Refraction typically stretches them outward by 7–10%, but it
is profile-dependent and nonlinear near the horizon (cross-vendor review C6): treat it as
uncertainty, not a correction.

Open-Meteo's layers, which this codebase uses (`DirectionalCloudData` javadoc), are **low 0–3 km,
mid 3–8 km, high 8 km and up**. ERA5 diagnoses layers differently (σ 0.8 / 0.45, roughly 2 / 6.5 km)
and `best_match` blends models, so layer boundaries are part of every error budget.

### (A) The sun line moves with the sun's altitude

The sight line to the sun at apparent elevation α, from an observer at height h0, is at height
h0 + αd + d²/2R at distance d. So a cloud at height H is on the line at

d = R·(−α + √(α² + 2(H − h0)/R)).

| Sun elevation α | Low tops 1 / 3 km | Mid 3 / 8 km | High 8 / 12 km |
|---|---|---|---|
| 0° (touching the horizon) | 107 / 192 km | 192 / 317 km | 317 / 389 km |
| 0.4° (about 5 min before sunset) | 71 / 153 km | 153 / 276 km | 276 / 347 km |
| 1° | 43 / 111 km | 111 / 225 km | 225 / 294 km |
| 2.5° (about 20–25 min before) | 20 / 60 km | 60 / 144 km | 144 / 200 km |

h0 = 0.1 km, geometric (no refraction), to the nearest km.

Three consequences:
1. **The sun line belongs to one instant.** Each probe distance means something only together with
   the time it represents; "the sun line at 320 km" is the last minute or two before the sun
   touches the horizon.
2. **The 113 km cone's mid and high readings are already on the golden-hour sun line.** At 113 km
   the line is at about 1.8 km at α = 0.4°, 4 km at 1.5° and 6 km at 2.5°. Mid cloud at the cone sits
   on the line roughly 10–35 minutes before sunset.
   - The prompt's "never treat thick solar mid/high cloud as 'blocked'" is a rule about the
     canvas after sunset. As physics about golden-hour light on the land it is false.
   - It is a kept, regression-pinned rule, so this plan does not change it; it only stops claiming
     the rule is physically true at 113 km.
3. **Tonight, about 5 minutes before sunset (α ≈ 0.3–0.6°)**, the blocker was one of:
   - low cloud with 2–3 km tops beyond the cone (out to about 155 km);
   - mid cloud at about 155–275 km;
   - high cloud at about 275–345 km.

   The 91% high at 113 km **cannot** have been the bank. A deck at 8–12 km there appears 3.5–5°
   above the horizon, well above a sun at 0.4°, which shone underneath it.

### Terrain

The 256° bearing from the Angel crosses the Hartside and Alston fells (about 550–600 m) at about 65
km. That lifts the terrain horizon by roughly 0.15–0.4°, so 1–3 minutes of the "5 minutes early" may
be terrain alone, and orographic cap cloud over the Pennines and Lakes is a classic bank source.

This is an estimate; no digital elevation model (DEM) was checked. Several proposed probe cells are
also over high ground: 113 km lands on the west Lakeland fells, and 320 km is near the Mournes.

### (B) Canvas underlighting by low cloud

Unchanged from `cloud-approach-veto-fix.md` §8: for a canvas at height h, low cloud with tops z
blocks the light over √(2Rh) ± √(2Rz).

### (C) Whether a deck can be underlit at all, and where its clear slot must be

A deck based at height b above distance x0 is underlit only while the sun is **below that point's
own horizon**. With the sun on the observer's horizon it is 1.02° *above* the horizon at 113 km, so
the deck over the cone is lit from above, not underlit.

When the sun's depression at the observer is δ, a deck there is lit from below only through a clear
slot sunward. For the overhead deck, the ray returns to the deck's base at about 2Rδ:
- about 220 km at δ = 1°, 7 minutes after sunset;
- about 320 km at δ = 1.44°;
- out to 2√(2Rb) ≈ 550 km (6 km base) only at that deck's last light.

So the existing 226 km point and a 320 km point are the (C) probes for the first 7–12 minutes after
sunset. Only the final, reddest phase needs 550–750 km. The glow windows run from about 0 to 20
minutes after sunset for an overhead 8 km deck, and about 7 to 28 minutes for a deck above the
cone.

### Cloud fraction is not opacity

At grazing elevations broken cloud closes up: its sides fill the gaps, so a 24% layer can look
overcast at 0.5°. The path through a 6–10 km layer at α = 0 is about 80 km long, so a cirrostratus
of optical depth 0.2 overhead is roughly τ 4 along the line (about 2% transmission). A thin canvas
can also stay faint when lit. That is a fourth failure mode, separate from (A)–(C).

Every threshold below is a proxy, not a measure of opacity.

## 3. What we sample today

| Point | Layers fetched | Layers used |
|---|---|---|
| 3 × solar cone, 113 km, ±15° | low, mid, high | all three (averaged) |
| Antisolar, 113 km | low, mid, high | all three |
| Far solar, 226 km | low, mid, high (`CLOUD_ONLY_PARAMS`) | **low only** |
| Observer | full forecast | all |
| ERA5 verification: cone, observer, 226 km | low, mid, high (`ARCHIVE_HOURLY`) | far: **low only** |

Four code paths build or read these points:

| Path | What it does |
|---|---|
| Live reader, `OpenMeteoService.fetchDirectionalCloudData` | positional `responses.get(3)`/`get(4)`; used by `BatchRetryService`, `ForceSubmitBatchService` and as the executor's fallback |
| Cache reader, `CloudPointCacheReader` | positional `points.get(3)`/`get(4)` |
| Two prefetchers, `BatchWeatherPrefetcher` and `ForecastCommandExecutor.prefetchCloudPoints` | gather points via `computeDirectionalCloudPoints` |
| ERA5 sampler, `CloudVerificationService` | builds its own list: cone, observer, far, at positional offsets |

The upwind point's geometry is re-implemented inline in `OpenMeteoService.fetchCloudApproachData`
(veto-fix D8). The directional points are not re-implemented anywhere.

## 4. Which samples would help (the owner's question)

| # | Sample | What it answers, and when | Cost |
|---|---|---|---|
| S0 | **Pressure-level cloud cover at the existing points**, at the level the sun line crosses (113 km → ~900–700 hPa; 226 km → ~600 hPa; far points → ~350 hPa) | (A) at the exact line height, instead of a 3–8 km aggregate; also catches a 2–3 km-topped low bank via the 113 km point | Variables, not coordinates: up to 10 per location cost one call. **Checked 2026-10-13:** the forecast API (`best_match`) serves 850–300 hPa; the ERA5 archive returns `null` for them, so S0 can be captured but not ERA5-verified. |
| S1 | **Mid and high at the existing 226 km point** | high: sun line at α ≈ 0.5–1° (the minutes the owner watched the sun go); mid: sun line at α ≈ 0–1°; both: (C) for the overhead deck ~7 min after sunset | **Free**: fetched and discarded today |
| S3 | **New point at ~160–170 km, all layers** | low and mid between the two existing points: the gap where a 2–3 km-topped bank or a low mid deck hides the sun at α ≈ 0–0.5° | One coordinate per candidate, mostly a new grid cell |
| S2 | **New point at ~320 km, all layers** | high at the near edge of the band, sun line in the last minute or two; (C) for the overhead deck ~10 min after sunset; (B) corridor centre for an 8 km canvas | One coordinate, almost always a new cell |
| S4 | ~450 and ~600 km, mid/high | (C) for the late, reddest phase only; over Ireland and its west coast for westward sunsets, the North Sea and Denmark for sunrises | Two coordinates |

**Not proposed**, with reasons:
- **A cone at the far points:** the 113 km cone's structure buckets were shelved (veto-fix §9); ×3
  cost.
- **More antisolar points:** the antisolar-low rule stands.
- **Upwind mid/high:** `SolarCloudTrend` already carries mid/high at 113 km.
- **Radiation, DNI or sunshine duration:** models compute them one column at a time (blind to a deck
  300 km down the slant path), as an hourly average, with the sun near 0°.
- **Cloud base height:** not confirmed on the forecast API.
- **A digital elevation model (DEM) horizon** is not a sample. It is an offline correction P1 applies
  per location (see P1), and a candidate fixed per-location attribute later.

**Recommended order:** S1 (free) and S0 (if available) first; S3 then S2 only if P1 passes; S4 last.
The cost of S2–S4 is per unique 0.1° grid cell, not per candidate (§6 P3).

## 5. What the earlier work settled, and why this is a different question

- **The earlier sizing** (veto-fix §9) is a different population. The "false go" for far LOW cloud
  (`farCloudier&fcstClear`) was 428 evenings, **1.5% of all evaluations**, conditioned on the
  **observed** (ERA5) corridor. It did not look at mid or high cloud on the sun line. Any new figure
  must state its own denominator and conditioning; it is not comparable to that 1.5% as it stands.
- **The 226 km forecast low reading** was bimodal against ERA5: +2.9 pp when it shows a clearing,
  +67.7 pp when it claims cover. Hence `CLAUDE.md`'s rule that the far reading "may soften and must
  never confirm".
  - The rule was written about the low reading.
  - Using the far mid or high reading to **penalise** would extend escalatory force to the same point
    from the same blended model.
  - That is an owner decision (§9 Q4), not something this plan may assume.

## 6. Phases

### P0 — replay the stored prompts (now: they are deleted after 30 days, about 12 November)

1. Export `forecast_evaluation_prompt` for location 64's 13 Oct sunset rows (01:06 and 14:22), and
   Low Hauxley's if still held.
2. Replay each prompt N = 20 times on the production model (`SONNET_55`), on Sonnet 4.6 (the model
   V92 seeded for Batch Near-Term) and on Haiku 4.5 (Batch Far-Term).
3. Then vary one input at a time: cone low 29 vs 7, cone high 0 vs 91.

**What it decides:** whether the 3★ → 4★ raise is the IDEAL gate, the high-cloud canvas, sampling
noise or the model. If it is noise or the model, the far-sample question is moot for this case and
the plan reduces to measurement. Costs a few hundred Claude calls; the owner approves the spend.

### P1 — retrospective sizing, read-side, before any new code

**Pre-registered here, before any data is pulled.** Thresholds are proxies (§2). The cases in §1 are
the training set, excluded from the evaluation.

| Hypothesis | Forecast gate (cone low < 20) **and** |
|---|---|
| H1 | high ≥ 60 at 226 km |
| H2 | mid ≥ 60 at 226 km |
| H3 | low ≥ 50 or mid ≥ 60 at ~165 km (S3's position) |
| H4 | high ≥ 60 at ~320 km |
| H5 | at 226 km, cloud ≥ 60 at any of 600, 500 or 400 hPa (the levels the sun line crosses there at α ≈ 0–1°, §2) |

**H5, added 2026-10-13 before any level data was pulled.** It asks whether a deck's height,
not its layer band, picks out the decks that cross the sun line. Open-Meteo's mid band (3–8 km)
mixes a 3 km deck below the line with a 6 km one across it.

- **Observed side.** ERA5's archive serves no pressure-level cloud (it returns `null`), so H5 is
  judged against ERA5's mid or high ≥ 60 at 226 km. That is coarser than the forecast gate, and it
  is stated as a limit.
- **Forecast side.** The Previous Runs API rejects pressure-level variables, and the Historical
  Forecast API rebuilds only about T+0. So H5 uses production's own stored levels
  (`docs/engineering/far-cloud-capture-plan.md` T7), from T7's deploy on. Stitched T+0 levels may
  be used only as a labelled sensitivity check, never for the decision.
- **Extra decision rule.** Beyond the common rules below, H5 proceeds only if its lift's interval
  lies above that of H1 ∪ H2 (the aggregate gate) on the same firings. Height must earn its keep
  over the bands.

*Note, 2026-10-31 (H5 itself is unchanged).* Stored levels now run 850 to 200 hPa
(`far-cloud-cirrus-levels-plan.md`, V209), but pressure-level cloud still under-reports thin cirrus:
in an owner sample of 384 hours with single-level high >= 30, 144 had 500, 400 and 300 hPa all 0,
and only 78 of those (54%) showed anything at 250 or 200 hPa. So "all levels 0" is not "no cloud
above the line"; about 46% of high-only hours stay invisible to every level.

**Unit.** One firing is a region × date × event, judged on the latest run before the event (the one
readers saw), stratified by lead (T+0, T+1, T+2). Sunsets and sunrises are reported separately.

**Data:**
- **ERA5**, via a scratch reconstruction from the archive API (it already returns all layers;
  nothing stored needs changing). This gives the observed base rate and the observed-conditioned
  failure population (forecast-clear gate, ERA5 deck on the line). That population sizes the
  problem.
- **The forecast side**, from Open-Meteo's lead-matched Previous Runs API, with the stitched
  Historical Forecast API as a fallback, recording which served each row.
  - The reconstruction is valid only if it reproduces the persisted cone low and 226 km low to
    within a few pp; otherwise its far readings are not what production could have seen and the
    forecast-side result is void.
  - Record which model served each point.
  - A DEM horizon per location is applied before attributing an early loss of the sun to cloud.

**Statistics, per hypothesis, with 95% intervals:**
- **Precision:** an ERA5 deck given a forecast deck.
- **Recall:** a forecast deck given an ERA5 deck. A missed deck is the costly failure.
- **Lift**, or equitable threat score, over the ERA5 base rate. Raw agreement is not enough: a deck
  that is common anyway makes 70% easy.
- Layer agreement is checked at several points along the line, not one cell. The shared-bias caveat
  applies: if `best_match` leans on ECMWF, ERA5 shares its high-cloud physics.

**Decision.** A hypothesis proceeds to P3 only if all of these hold:
- at least 80 independent firings, the number needed for ±10 pp at 70% precision;
- precision at least 0.7, with a lift whose interval excludes 1;
- the observed-conditioned failure population is at least 2% of prompted region-events.

Otherwise it stops and the figures are written here.

**Data volume.** The ERA5 backlog is 33k+ rows. Sizing reads a sample (for example a stratified 2,000
region-events), not the whole history, to stay inside Open-Meteo's fair use.

### P2 — capture S1 (and S0 if available), data only, the prompt byte-identical

The delegation plan, broken into task cards for subagents, is
`docs/engineering/far-cloud-capture-plan.md`.

**Built 2026-10-13 (S1 and the dark-side hour), pending CI:** V175, on `feature/far-cloud-capture` (PR #A of the
capture plan). S0 (pressure levels 850–300 hPa at 226 km) built 2026-10-13 as that plan's T7 (V176), forecast only.

Can run alongside P1. It is the only build step that does not wait for P1, because S1 is free.

1. `DirectionalCloudData` gains nullable `farSolarMidCloudPercent` and `farSolarHighCloudPercent`.
   Keep a 7-argument compatibility constructor so the 26 test call sites, including the
   prompt-regression sources, are untouched.
2. Read them in **both** readers (the live reader and `CloudPointCacheReader`), by named index
   rather than position.
3. Persist them in new nullable `forecast_evaluation` columns via `DirectionalCloudDetails`.
4. `CloudVerificationService` stores the ERA5 far mid and high it already fetches; extend
   `CloudVerificationRepository`'s projection.
5. **Do not add the new columns to `deleteIncompleteVerifications`.** Old rows have no forecast-side
   far mid/high to pair with, so re-walking the 33k-row history would spend fair use for nothing.
   Verify the new fields only for rows written after the deploy.
6. The migration is the next number (V174 at the time of writing; read the tree), with a
   Testcontainers test. CI proves it.
7. **The prompt does not print the new readings.** A test builds the same `AtmosphericData` with and
   without them and asserts identical system and user messages; `LowHauxleyPromptFidelityTest`
   already pins one real case.
8. Note in the rollback runbook: `PromptTestService` and `ModelTestService` persist `AtmosphericData`
   JSON with a default `ObjectMapper` (unknown properties fail), so rows written after this change
   do not replay on a rolled-back build. Either set those mappers to ignore unknown properties in
   this step, or accept it.

### P3 — new points (S3, S2, then S4), only for hypotheses P1 passed

- **Behind a flag** (e.g. `photocast.cloud.sun-line-points.enabled`, off by default). Absent
  readings stay null, which the nullable design tolerates.
- **Each point belongs to an instant, and its azimuth is computed for that instant.** The event-time
  bearing callers pass today is right only for a probe of the moment of sunset. A probe that stands
  for golden hour (before) or the after-glow (after) is placed on the sun's azimuth at its own
  instant (`SolarCalculator`, the same library the bearings come from), because the bearing moves
  about 5° across the minutes that matter (§8): about 20 km cross-track at 226 km and 50 km at 600 km.
  Each named offset is therefore a (distance, minutes from the event) pair, and P1 uses the same
  pairs, so the experiment and the capture sample the same columns.
- **Code:** `DirectionalSamplingGeometry` gains named (distance, instant) probes.
  - Update every sampler in the §3 table, including the ERA5 sampler's own layout and stride.
  - Update the live reader's hard-coded "5 points" log strings.
- **Persistence and read paths for every new point**, specified before any extra cell is fetched:
  - `DirectionalCloudData` gains a nullable low/mid/high triple per new probe (behind the same
    compatibility constructor as P2), read by named index in both readers;
  - matching nullable columns on `forecast_evaluation` via `DirectionalCloudDetails`, in the
    migration that ships the probe, with its Testcontainers test;
  - the ERA5 sampler stores the same triples on `cloud_verification`, and
    `CloudVerificationRepository`'s projection carries them, so P1's cut can be re-run on live data;
  - `deleteIncompleteVerifications` again leaves the new columns out (new rows only);
  - the prompt still prints none of them until P4.

  A probe without all four is not shipped: a fetched reading nobody keeps is cost with no evidence.
- **Cost in unique grid cells, not limiter permits.**
  - The `open-meteo` rate limiter wraps the public batch method once and chunk requests bypass it,
    so it cannot see added coordinates. Open-Meteo's free terms count per location (about 10,000 a
    day, 5,000 an hour, 600 a minute; weight about nLocations × nDays/14 × nVariables/10).
  - Far points dedupe badly: the sunset azimuth moves 1.5–2° a day, so each far point is nearly one
    new cell per candidate per cycle.
  - Before shipping, dry-run the geometry over a real cycle's candidates and read the existing
    "N raw points -> M unique grid cells" line.
  - Add the weather prefetch, briefing batches and ERA5, and compare against the daily cap. If the
    total approaches it, §9 Q6 decides.

### P4 — a prompt rule, only with evidence of harm

P1 measures whether the forecast sees a deck, not whether ratings fail: Low Hauxley is an accurately
forecast deck over a good sky. So no rating change ships on P1 alone.

**Gate:**
- an outcome arm of at least 20 events under the hypothesis' condition, **recorded prospectively**,
  with disappointments clearly outnumbering successes. Outcomes recorded when they surprise (row 1
  was one) over-represent disappointments, so they cannot gate a rating rule. Before the arm opens,
  the owner fixes a protocol: a named set of locations and events, and every firing of the
  condition at them is recorded whatever the sky does (a GO, a disappointment, or "didn't go,
  watched from home"). Events recorded outside the protocol are reported, not counted;
- star ratings recordable through the API first (the suggested task), or by SQL as row 1 was;
- the P1 positive control (Low Hauxley) must not trigger at the chosen thresholds. This is a hard
  stop.

**Design constraints:**
- **The system prompt changes for every request.** The rules that interpret a label live in
  `SYSTEM_PROMPT`, which every call carries. Every regression case and eval fixture is therefore
  exposed, including ones whose fixtures lack the new fields.
  - Re-run the whole `PromptRegressionTest` suite and the sky-rating eval, before and after, on each
    production model (`REGRESSION_MODEL`: Sonnet 5.5 for near-term, as Q5 confirmed; Haiku for far-term).
  - Regenerate the five `PromptGoldenMasterTest` goldens. Any movement in the exact-4 cases (St
    Mary's 10 Mar, Low Hauxley) is a regression.
- **State precedence for every co-firing pair.** A mid deck over 80% at the cone (`[THICK MID CLOUD —
  rate 4]`) or IDEAL's "rating 4–5" will often fire beside a far-deck label. Edit the categorical
  clauses to say "at the 113 km horizon", and add a pair-firing fixture to the eval.
- **Choose the lever explicitly.**
  - Golden Hour alone does not move the star readers act on (`rating`; at coastal sites the tide
    average moves it further).
  - If the rule is meant to change the star, it bounds the sky rating, with matching text in the
    output schema.
  - Start with wording only (or a softening use, e.g. recognising Low Hauxley's open far side) unless
    the outcome arm justifies more.
- **Formatting:** a distinct, clearly named line for the far readings. Never reuse the "Beyond
  horizon (226km)" line, whose presence switches THIN STRIP and FAR CORRIDOR on. Word the label for
  sunrise and sunset alike.
- **Basic (LITE) tier:** the label falls under the basic tier's "NO directional cloud information"
  instruction, with a check that `basic_*` scores ignore it.
- **Low Hauxley stays untouched** (its fidelity pin is the case's value). A separately named variant
  carries the reconstructed far readings and says where they came from; the owner sets its band.
- **Cost:** the larger system prompt is paid on every request. Report the change in `max_tokens`
  stops, refusals and cost per call per production model.
  - `SystemPromptTokenCountTest` is prompt-regression-tagged (not in CI); the CI guard is
    `SystemPromptCacheabilityTest`.
  - The cache primer covers the scheduled path only; force-submit, JFDI and the synchronous engine
    each pay one cold write.

### P5 — triage (out of scope)

`WeatherTriageEvaluator` stands slots down on cone low > 80% and reads none of this. The briefing's
own samplers (`BriefingWeatherLoader`, `NorthwardTransectSampler`, `OverheadCloudSampler`) use their
own geometry and are unaffected.

## 7. What must not change

- The 113 km cone rules, THIN STRIP, FAR CORRIDOR and the veto demotion.
- The far low reading's role.
- The basic tier.
- Any prompt-regression assertion: new cases' assertions are the owner's.
- Low Hauxley's fixture and its stored prompt.

## 8. Risks and limits

- **The case set is one bad and one good**, confounded by event, direction, terrain and canvas. P1
  adds at least one good westward inland sunset and one bad sunrise to its exploratory set, and
  records how every case was chosen (outcomes get recorded when they surprise).
- **Model skill aloft** at T+0..T+1 may be no better than the far low reading proved to be. That is
  P1's whole point.
- **Timing:**
  - hourly slots cannot separate golden hour (T−30 to T−5) from the after-glow (T+0 to T+28), so P1
    reads the hours either side and interprets them against the per-instant geometry in §2;
  - the azimuth sweeps about 5° across the minutes that matter here (about 20 km cross-track at 226
    km, 50 km at 600 km), so far points belong at the azimuth for their time.
- **Intraday raises:** the 14:22 refresh is the run that went wrong. If P1 finds T+0 high-cloud
  forecasts unreliable, a rule's first effect would be to undo intraday raises; tell P1's readers.

## 9. Owner decisions

1. P0's spend: about 20 replays per prompt variant, on three models.
2. S1 capture (P2) regardless of P1's outcome? It is free and makes future sizing possible.
3. Which of S3, S2, S4 to buy, after P1 and P3's cost check.
4. May a far mid or high reading ever **confirm** (penalise), and on what measured bar? This extends
   `CLAUDE.md`'s far-reading rule beyond the low reading it was written for; amend `CLAUDE.md` only
   after P1 passes.
5. **Answered 2026-10-13:** production's Batch Near-Term run type is set to Sonnet 5.5 on the Run
   Configuration screen, so the T+1/T+0 rows above are working as configured (`CLAUDE.md`, which
   said Sonnet 5.5 was active nowhere, is corrected). `CLAUDE.md` also records that Sonnet 5.5 fails
   one prompt-regression case Sonnet 4.6 passes (2026-10-04); which case is not recorded there, and
   if it is a high-canvas case it bears directly on this plan. Still open: was the switch a trial
   or a settled choice? P0's comparison with Sonnet 4.6 informs it either way.
6. If P3's weighted Open-Meteo load approaches the free cap: a paid API plan, or drop S2–S4. The
   free API is for non-commercial use, and the product has a paid PRO tier.
7. The low Hauxley variant's band, and tonight's regression case's expectation, when P4 comes.

## 10. Review record (2026-10-13)

Five read-only reviewers (physics, code accuracy, evidence, prompt and regression risk, scope and
cost), then a refutation pass.

**Sustained and applied:**
- the sun line depends on solar altitude;
- the 113 km mid/high readings lie on the golden-hour line;
- (C) is a depression-dependent slot, not "far beyond any point";
- Open-Meteo's 0–3 / 3–8 / 8+ km bands;
- grazing optical depth;
- Copt Hill was sampled at 50 km and has a different mechanism;
- the raise had two inputs;
- P0 as first drafted could not fail;
- P2 as first drafted measured cloud, not harm;
- the undefined "bimodal" leg;
- the missing n, unit and denominators;
- retrospective sizing is possible;
- the system prompt reaches every case;
- label co-firing;
- the golden-hour lever does not move the star;
- the Low Hauxley fidelity pin;
- regression runs on the wrong model;
- the token test is not in CI;
- the limiter counts calls, not coordinates;
- the ERA5 sampler is a fourth path;
- the 28 constructor call sites;
- replay mappers after a rollback;
- §4's "S1 and S2 for certain" contradicted the stop rule.

**Corrected by the refutation pass:**
- the sun was about 0.3–0.6° up five minutes before sunset, not 0.7°, so 320 km is not ruled out for a
  high deck;
- the terrain horizon is about 0.15–0.4° (1–3 minutes), not 0.5°.

**Partly sustained:**
- "a mid deck at 150–250 km is likeliest" is one of three open explanations, not the answer;
- the ERA5 re-walk's cost was overstated, but the pairing gap is real;
- pressure-level cloud availability is unverified.

**Not checked by anyone:** a DEM profile, live Open-Meteo or ERA5 data for any case, Open-Meteo's
documentation (unreachable from the session), production's model selection, and the
`cloud_verification` row count.
