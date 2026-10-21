# Valley mist — the morning PhotoCast cannot see (2026-10-17)

**Status:** M0 measured and the six decisions taken (2026-10-21, §5). **M1a built** (2026-10-21): the
`mist_prone` flag, `ValleyMistCalculator`, and the `slot_atmosphere` reading (§3 M1, first four
bullets and the writer). **M1b built** (2026-10-21): the hot topic, Coming up condition, `MIST_CALC`
daily log and the two wording fixes (§3 M1, last five bullets). **M2 built** (2026-10-21): the triage
exemption, the prompt block, Claude's echo (V186) and `effectiveMistPotential()` (§3 M2, with the
amendments marked *as built*). **M3 built** (2026-10-21): the `seen` tag, the ERA5 saturation proxy and
the mist-prone calibration bucket (V187, §3 M3, with the amendments marked *as built*). What is left is the
rarity census, which waits on a year of `MIST_CALC`, and the saturation report.
**Prompted by:** a River Brathay sunrise on 17 Oct 2026 — thick radiation mist on the river under a clear
sky, low sun through it — that the pipeline would have stood down before Claude saw it, and that no surface
would have named the night before. The owner's words: "Shame we missed this."

Read §1 before §3. The design is small once the asymmetry in §1 is seen; most of this document is the
argument for why it is *that* small and no smaller.

---

## 1. What is true today

PhotoCast already knows mist is a prize. It knows it in three places, and all three are on the wrong side
of a gate for the morning above.

1. **The sky prompt.** `PromptBuilder` carries a whole MIST AND VISIBILITY GUIDANCE block: thin ground mist
   (2–8 km) under a clear sky at sunrise scores UP, a burn-off is "the photographic opportunity", convey
   urgency. It is fed by `MistTrend` (visibility, temperature and dew point, T−3h to T+2h), extracted from
   the forecast response at no extra cost (`OpenMeteoResponseParser.extractMistTrend`).
2. **The canopy lane.** `WoodlandPromptBuilder` says "MIST is the prize, and the single thing most worth
   travelling for", and `WoodlandVerdictEvaluator` reads visibility < 5 km as "mist — the reason to be
   there". `ForecastTaskCollector` exempts a `woodlandTask` from the triage verdict for exactly that reason
   ("without this the feature is born inverted — every good woodland morning triaged out").
3. **The inversion subject.** `InversionScoreCalculator` scores mist *seen from above* — moisture from the
   temp–dew gap, calm wind, a low-cloud sea to stand over, a measured reversal aloft — for a place with
   `elevation ≥ 200 m` and `overlooksWater`. `SlotAtmosphereWriter` records it for every fetched candidate,
   `InversionHotTopicStrategy` and Coming up's "Valley inversions" read it, `ForecastDtoMapper` serves
   Claude's echo as the map badge.

And the gate:

4. **`WeatherTriageEvaluator` rule 3: visibility < 5,000 m stands a sky slot down**, before Claude, writing a
   `LOW_VISIBILITY` triage row. The sky-prompt guidance in (1) therefore runs only for mist thin enough to
   leave visibility above 5 km — haze, in practice — and never for the morning it describes best.
   `BriefingVerdictEvaluator` agrees from the other side: visibility < 5 km is STANDDOWN, humidity > 90% is
   a "Mist risk" flag — a *risk*, on every sky slot, whatever the site.

The asymmetry, stated once: **mist is modelled as a subject from above (inversion) and from inside a wood
(canopy), and as a modifier for haze — never as a subject from beside it.** The riverside, the valley floor,
the lake shore at dawn is a whole genre of British landscape photography, it arises from the same physics
and most of the same predictors as the inversion the calculator already scores, and it is the one shape of
the phenomenon with no model, no reading, no topic and no star.

Two further facts bound the design:

- **Open-Meteo's `best_match` over the UK IS the Met Office 2 km model, and even at 2 km the river did
  not saturate.** Measured 2026-10-21 (§3 M0): for the Brathay on 17 Oct, `best_match`, `ukmo_seamless`
  and `ukmo_uk_deterministic_2km` return byte-identical hourly rows at a grid elevation of 54 m — the
  valley floor — and those rows hold a 2–3 °C temp–dew gap all night, 81–87% humidity, 37 km visibility
  and a clear sky until 06:00. ICON agrees. The mist that was photographed is sub-grid for every model
  Open-Meteo offers: a river corridor a few hundred metres wide saturates where the cell's 2 m air does
  not. The model's own visibility is therefore evidence of the SET-UP, never the answer — which is why
  the inversion was given a calculator over predictors rather than a threshold on one field, and why
  this calculator must score the radiative set-up rather than wait for the model to saturate.
- **No gridded source verifies valley fog.** ERA5 has no visibility; `cloud_verification` cannot score it.
  The owner's own outcome ratings (`outcome_rating`, since 2026-10-15) are the only observation the
  project holds, and they record a star, not what was seen.

---

## 2. The shape of the right change

Five principles, each already in force somewhere in this codebase; the change is to apply them to this
subject rather than to invent new ones.

1. **Two questions, two surfaces** (owner decision 2026-09-29). "Is valley mist likely" is *what is
   happening* — a reading, recorded for every place, read by a hot topic and Coming up, free. "Is the
   Brathay worth the drive" is *where is worth going* — Claude's star, with the reading as evidence. The
   first ships without the second and is useful alone: it would have put "Valley mist likely — sunrise" on
   the Plan the evening before.
2. **A calculator over predictors, stated to Claude as facts, never a threshold on one field** — the
   `InversionScoreCalculator` shape, and its two rules: moisture counted once, and no band reached by
   inference alone that the evidence does not support.
3. **The star is the shot** (tide gate lift, 2026-09-18). One sky prompt, mist-aware, with the combined
   `rating` as Claude's judgement of the frame from *here* at *this* event. Not a second lane: a mist site on
   a clear morning still wants the sky lane, so a MIST lane would run beside SKY on the same slot, double the
   cost, and need a combination rule (max? average?) nobody can verify. The inversion precedent is the
   model: a deterministic score, a prompt block, Claude's echo persisted beside it for the badge.
4. **Geography is metadata, entered once** — `overlooksWater` and `elevationMetres` are how the inversion
   knows its viewpoints. A site where mist forms *around* you is a fact about the site, not derivable from
   an 11 km cell, so it is a location attribute an admin sets, with a derived hint as a later convenience.
5. **A triage exemption is a routing fact, not a lowered threshold.** The canopy lane does not lower the
   5 km rule; it declares that for a canopy the rule answers the wrong question. Same here: for a mist-prone
   site, and only when the calculator's signature says the mist is photographable, rule 3 is not consulted.
   Every other site keeps the rule unchanged; the cost of the exemption is bounded by the number of flagged
   sites (owner-curated, tens).

---

## 3. Phases

Each phase is its own PR, its own changelog entry, built → tests → adversarial review → fix → commit, per
CLAUDE.md's review cadence. Migrations are proven by CI only (no local Docker); write the Testcontainers test.

### M0 — Measure before building (read-only, owner-run SQL, no code)

`docs/engineering/valley-mist-phase0.sql`, run on the production host. Questions it answers:

1. How many `LOW_VISIBILITY` triage rows in 180 days, by location and event — and what share carried the
   photographable signature (observer low cloud < 30%, wind ≤ 3 m/s, temp−dew gap < 2 °C)? That share is the
   count of "Brathay mornings" the pipeline refused. Zero means M2 is not worth its cost; a steady trickle at
   a few riverside sites is the expected shape.
2. The same signature on rows that were *not* triaged (visibility ≥ 5 km, haze band): how often does the
   sky prompt already see a near-saturated calm clear dawn? This is the population the existing guidance
   acts on, and the baseline the calculator's bands are set against.
3. Which enabled locations are plausibly mist-prone from what is stored (elevation ≤ 150 m, not coastal,
   LANDSCAPE/WATERFALL) — a shortlist for the owner to tick, not a derivation.
4. 17 Oct 2026 SUNRISE itself, for every roster location: what did the model say? If any Lakes or Durham
   river site is on the roster, this is the one morning we can compare against a photograph.

Also in M0, by hand (one request each, no code): the Open-Meteo side-by-side for the Brathay on 17 Oct
across `best_match`, `ukmo_seamless`, `ukmo_uk_deterministic_2km` and `icon_seamless`. Done 2026-10-21;
the result is in the findings below and it killed M4.

**Results (run on the production host, 2026-10-21; one pre-event run per morning, 180 days):**

| | Sunrise | Sunset |
|---|---|---|
| Mornings forecast | 17,101 | 14,724 |
| Reached the sky prompt (`triage_reason IS NULL`) | 7,251 | 5,755 |
| Stood down `LOW_VISIBILITY` | 1,266 | 221 |
| Haze-band (5–10 km) mist signature the prompt actually saw | 32 | 5 |

- **M0 §1 — the refused population is real, and 145 is its FLOOR.** Query 1 lists the top 60
  site/event groups sorted by the photographable count itself, so its 145 signature mornings (observer
  low cloud < 30%, wind ≤ 3 m/s, gap < 2 °C; mean gap 0.1–0.3 °C, mean visibility ~3 km) are an enriched
  slice and cannot be extrapolated over all 1,266 — a Codex review of #1268 caught the first draft doing
  exactly that. What the slice does prove is a lower bound: at least 145 refused sunrise mornings carried
  the signature, against the 32 the prompt's own MIST guidance ever reached — at least four and a half to
  one. The whole-population figure is query 1b (added after that review), to be run before M1's bands
  are set; the ratio can only grow. Walltown Crags led (6 of 11), then White Moss Common, Norber Erratics and Dent
  Head (4 each); the Brathay at Elterwater had 3 of 13. Every one of the 60 rows was SUNRISE.
- **M0 §2 — the signature is a sunrise phenomenon here**: 1,266 sunrise stand-downs to 221 sunset.
- **M0 §3 — the shortlist cannot be derived.** `elevation_m` is stored for 18 of the 125 candidates
  (all Lakes lakeshores); the rest are null. Q1's manual tick is the only honest source.
- **M0 §4 — 17 Oct was NOT a triage miss.** The Brathay reached Claude at 01:07 UTC (T+0): visibility
  40,220 m, low cloud 47%, wind 1.6 m/s, humidity 81%, gap 3.0 °C — and was rated 2. Nearby: Buttermere
  gap 0.5 / wind 0.1, Derwent Water S&W gap 1.1 / wind 0.4, Loughrigg Fell gap 1.3 / wind 0.4, Stockghyll
  gap 1.3 / wind 0.3; all rated 2–3. **Ingleton Waterfalls** is the sharpest row: visibility 8,840 m, gap
  0.1 °C, wind 1.3 m/s, low cloud 0 — the textbook signature, seen by the prompt — rated 2. So the 32
  mornings the guidance reaches are not being lifted either.
- **The model side-by-side (Brathay, 54.4287 −3.0350, 17 Oct, UTC):** `best_match` = `ukmo_seamless` =
  `ukmo_uk_deterministic_2km`, identical to the row: 02:00–05:00 clear, wind 1.8–2.6, gap 2.8–3.0, RH
  81–82, vis 37–41 km; 06:00 low cloud 47%, gap 2.1, RH 86, vis 37 km; 07:00 gap 2.0, RH 87. ICON: gap
  2.0–2.2, RH 86–87 all night, vis 27 km. No model saturated the cell.

**What M0 decides:**

1. **M1 and M2 are justified** by the floor alone: at least 145 refused signature mornings against 32
   seen, at least four and a half to one. Query 1b gives the true count.
2. **M4 is deleted** (§3 below): production already runs the 2 km model.
3. **The calculator scores the radiative set-up, not the model's saturation.** The 17 Oct row — clear
   night, calm, RH rising through the 80s, temperature falling toward a dew point it never reaches in
   the cell — is what a LIKELY looks like at a flagged riverside site. A gap < 2 gate would have missed
   it; the gap is a term, not a gate. The M0 "photographable" signature (gap < 2) is the subclass the
   model DID saturate — itself a floor on the set-up population, not its size.
4. **M2's site sentence is load-bearing.** Ingleton shows Claude rates the full signature 2 when it is
   handed the numbers without the site fact and without being told the mist is the shot.
5. **The shortlist is Q5's, by hand.** Walltown Crags, Sycamore Gap and the other Wall sites head the
   refused table, and the owner confirms they are mist sites (2026-10-21): the Whin Sill crags are a
   ten-minute stroll from the road with negligible relief, and the mist sits around them, not below
   them. They and the lakeshore and riverside rows beneath them are the first ticks.

### M1 — The reading and the topic ("what is happening"; no Claude cost, no star change)

- **`locations.mist_prone BOOLEAN NOT NULL DEFAULT FALSE`** (V185; a plain `@Column(nullable = false)`
  written by the admin's whole-entity `LocationService.add`/`update`, the `overlooksWater` pattern; admin
  UI checkbox beside it in `LocationManagementView`, worded "Mist forms here: valley floor, riverside,
  lake shore"). ⚠️ Not a `LocationType`: a type routes a subject lane and inverts polarity (WOODLAND);
  this is geography on a sky site that keeps its sky. *(As built, 2026-10-21: the first draft of this
  bullet said `updatable = false` plus a column-scoped `updateMistProne`. That is the pattern for columns
  a detached or scheduled writer rewrites (failure counters, grid cell, Bortle); `mist_prone` has no such
  writer, so a scoped updater would have been dead code. And `overlooksWater` is set only at ADD time, so
  the flag also gets `UpdateLocationRequest.mistProne` and an inline-edit checkbox: the owner's first
  ticks are on EXISTING locations.)*
- **`ValleyMistCalculator`** (pure, `service/`, 0–10, the inversion calculator's sibling). ⚠️ **It scores
  the radiative SET-UP, because M0 showed the 2 km model does not saturate a river corridor** (the
  Brathay row: gap 2–3 °C all night, yet mist in the photograph). Terms, each a prior stated as cheap-
  to-be-wrong-about until M3 verifies, anchored so that the 17 Oct Brathay row reads LIKELY at a flagged
  site: a **clear-night term** from observer TOTAL cloud over the night-side `MistTrend` slots (T−3h..event
  for sunrise; event..T+2h for sunset, where mist *forms*) — radiative cooling needs an open sky, and this
  is the inverse of the inversion's cloud veto, where the fog *is* the low cloud and a sea must exist to
  stand over; a **calm term** from the night-side minimum 10 m wind (≤ 2 m/s full marks, > 4 m/s zero);
  a **moisture term** from the night-side MINIMUM temp–dew gap, reusing `InversionScoreCalculator
  .moistureScore` extracted to a shared helper so "moisture is counted once" has one home, but scaled so
  that a 2–3 °C gap with RH above 80% still scores (the model's cell is drier than the river beside it) —
  the gap is a term, never a gate; a **cooling term** from the temperature trajectory across the night-
  side slots (falling toward the dew point, the 17 Oct shape, scores; flat or rising does not); a
  **wet-ground term** from `precedingPrecipitationMm` (rain then clearing is the classic set-up); and a
  **confirmation** term, not a gate, when the model's own event-hour visibility is already in the haze
  band, under 10 km (full weight under 5 km; the Ingleton shape, 8,840 m — the subclass the model does
  saturate. *As built: the first draft said "under 5 km" yet named Ingleton as a confirmation, which
  8,840 m is not.*). Season enters only through night length
  implicitly (the slots), never as a calendar rule. Bands via a `MistPotential` (NONE / POSSIBLE /
  LIKELY / FOGBOUND) — FOGBOUND when visibility < 300 m with no improving trend (no LATER slot back above
  300 m), the white-frame case. *As built: its own constant, not the woodland evaluator's
  `MIST_TOO_THICK_M`, which is 100 m and answers "can the trees still resolve"; a 200 m morning must
  read FOGBOUND.* Requires `MistTrend.MistSlot` to carry observer
  total cloud and wind (`extractMistTrend` grows two fields; the prompt's existing rendering is
  unchanged). *As built: wind was in the hourly array but TOTAL cloud was not (only the three layer
  series), so `cloud_cover` joined `OpenMeteoClient.FORECAST_PARAMS`: one more variable on the existing
  request, no extra call. The layer maximum is not a substitute, it understates the total.* The M0 fixtures are the first unit tests: the Brathay row
  (LIKELY at a flagged site, NONE unflagged), Ingleton (LIKELY, confirmation on), Buttermere (gap 0.5,
  wind 0.1, 37% low cloud — LIKELY), and a windy or overcast night (NONE).
- **Gate:** `ForecastDataAugmentor.augmentWithValleyMist(data, mistProne)` runs for `mist_prone` sites only,
  in `fetchWeatherAndTriage` beside `augmentWithInversionScore`, BEFORE the readings write and triage.
- **`slot_atmosphere.mist_score DOUBLE NULL` + `mist_scored BOOLEAN NOT NULL DEFAULT FALSE` + `mist_potential
  VARCHAR(10) NULL`** (V185, with `mist_prone`; the band's enum name is stored because FOGBOUND is decided by
  event visibility, not the score, so two slots can share a score and differ in band) — the V158 pair exactly, `mist_scored` true on EVERY write of the current writer,
  unflagged sites included, for the same reason (a fresh null is an answer; a pre-column row is not). `SlotSignals
  .Readings` carries all three. No Claude echo exists yet, so no `effective…` fallback is needed in M1; M2 adds
  the echo and the `effectiveMistScore()` twin of `effectiveInversionScore()`.
- **`MistHotTopicStrategy`** (`"MIST"`, priority 2, SUNRISE and SUNSET rows — unlike the inversion, mist
  forming at dusk over a river is a real evening shot — regions of mist-prone sites at LIKELY *(as built: LIKELY ONLY, never FOGBOUND — the white frame the band exists to keep apart, for which "burns off within the hour" is false — and never POSSIBLE)*,
  `SolarEventFreshness` drops passed events, one topic per remaining event) with a fact line naming the
  count of sites and the italic cue ("get there before the sun — it burns off within the hour"). Rides
  `GET /api/briefing` to every role; reaches Ask's `get_hot_topics` and the Coming up condition without
  further change; `AskEventType.key` already normalises a new type.
- **Coming up "Valley mist" condition** beside "Valley inversions" (`SlotSignalReader`, forward peak and
  trailing history, rarity PINNED to the config fallback exactly as the inversion's is, until the log exists).
- **`TopicDailyLogJob` series `MIST_CALC`** (SUNRISE and SUNSET rows; *as built: `present` only at LIKELY, a measured non-LIKELY row logs `present = false`, the `INVERSION_CALC` shape*), the unbiased population
  for a later rarity census — the `INVERSION_CALC` precedent, by name not by date.
- **Wording honesty, two small fixes that belong with the reading:** `BriefingVerdictEvaluator`'s
  "Mist risk" flag reads "Mist likely" on a `mist_prone` site; and `BriefingHonestyFilter`'s `STOOD_DOWN`
  wording, "Too unsettled to forecast", is wrong for a region whose slots were all stood down for
  `LOW_VISIBILITY` — `CoverageReasons.of` already folds the latest disposition per slot, so a
  LOW_VISIBILITY majority can read "Fog forecast" *(as built: the reason is read off `forecast_run_disposition.detail` in the same single disposition read, a strict majority of the triage stand-downs, no added query)* (the honest word; the plan-card-honesty rule, applied to
  a reason that was always mis-worded).

Tests: calculator table-driven on the M0 signatures; the writer's two columns; the strategy's region
grouping and freshness; the Coming up condition; the migration under Testcontainers; the admin form.

### M2 — The star ("where is worth going"; Claude cost, bounded)

- **Triage routing.** `WeatherTriageEvaluator.evaluate` gains the slot's `MistPotential` (on
  `AtmosphericData`, null for an unflagged site). Rule 3 is NOT CONSULTED when the potential is LIKELY
  (never FOGBOUND, never POSSIBLE): the slot proceeds to Gate 4 and the sky lane as a `MIST_CANDIDATE`
  (an INFO line and a disposition note; no new `DispositionCategory` — it is an `EVALUATED` slot). Rules 1
  and 2 still apply (rain is rain; a blocked solar horizon 113 km away is still blocked). ⚠️ M0 measured how the
  2 km model represents a mist morning at the observer: the Brathay's 06:00 row carried 47% low cloud at
  37 km visibility, not an inflated ~100%, so the observer fallback of rule 1 would not have stood it
  down; the exemption covers rule 3 alone, and a `[BATCH DIAG]` line records any flagged slot rule 1 or
  2 still refuses, so the question can be re-read from the logs if a site ever shows the other shape.
  *(As built: the INFO lines are `[MIST CANDIDATE] <site> <date> <event>: visibility N m, mist LIKELY — rule 3
  not consulted` (logged only when rule 3 would have refused) and `[BATCH DIAG] MIST CANDIDATE … refused by
  rule N`. No disposition note was added: `LatestDisposition.lowVisibility` matches on that column's free
  text, and the INFO line carries the same information with no false-match risk. The briefing's
  `BriefingVerdictEvaluator` visibility STANDDOWN is out of scope, as the bullet says.)*
- **The prompt.** A `VALLEY MIST CONDITIONS:` block in the user message for a mist-prone site (score,
  band, the minimum gap and the hour it falls, night-side wind, sky, the clearing trend already printed by
  `MistTrend`) and ONE site sentence: "This location is a valley-floor / riverside site where mist forms
  around the camera; thin mist with a clear sky above at sunrise is the shot here, and may rate 4–5 with no
  horizon colour at all. Fog under 300 m with no clearing is a white frame: rate 2." The existing MIST
  guidance is otherwise untouched; the inversion precedent on stating facts, not asking for the number back,
  holds. System prompt unchanged where possible, so the batch cache prefix is unchanged for every site.
  *(As built: the system prompt is byte-identical; the echo instruction ("Include `mist_potential`…") is the
  last line of the user-message block. The structured-output schema gains an optional `mist_potential` enum,
  last, because constrained decoding forbids undeclared fields; that changes the shared schema once and the
  primer re-warms it. A flagged site whose calculator returned no band gets no block.)*
- **Claude's echo.** `mist_potential` (enum name) on `forecast_evaluation`, nullable, written at
  `buildEntity`, served by `ForecastDtoMapper` as the popup/callout badge — the inversion badge's twin, on
  Claude's reading alone, so the badge and the topic may disagree by design (two surfaces, two questions).
  *(As built, 2026-10-21, V186: the echo is also written by `ForecastResultHandler.scoreEvaluationRow` for the
  batch path, upserted into `forecast_score` as a new `ForecastType.MIST` component (id 8, seeded in the same
  migration) with the band on the row's `summary` and `MistPotential.echoScore()` (NONE 2, POSSIBLE 5, LIKELY 8,
  FOGBOUND 10) as its integer score, and drawn as "Valley mist likely" / "Fogbound" in `MarkerPopupContent` and
  the four-day sheet's `LocationSheetFacts`, PRO and ADMIN only. `MapCallout` draws no inversion badge, so the
  sheet is the Map-side home. The echo is kept only for a flagged site.)*
- **`SlotSignals.effectiveMistScore()`**: the calculator's reading when `mist_scored`, else Claude's
  echo mapped onto the band scale, the `effectiveInversionScore()` rule verbatim.
  *(As built: it is `effectiveMistPotential()` and returns the BAND, not a score — the band is what the topic
  and Coming up consume, and FOGBOUND is decided by visibility, so a score-mapped echo would lose it.)*
- **Prompt regression.** New cases in `backend/src/test/resources/prompt-regression/` — the Brathay
  signature (mist LIKELY, clear above, calm: expect ≥ 4 and mist named in the summary), the same site
  FOGBOUND (expect ≤ 2), and a non-flagged site with the identical weather (expect the existing haze
  behaviour) — **assertions pending the owner's approval**, the `AskThreadRegressionTest` convention;
  no existing assertion is touched.
- **Cost statement:** extra Claude calls = flagged sites × their LIKELY mornings. With tens of sites and
  M0's measured frequency this is a few calls a week on the near-term model. Written into the changelog
  entry with the M0 figure.

Nothing on the Plan tab, the Map, Ask or the heat field changes in M2: the star is the shot, the summary
says why, and `skyRating` keeps its meaning (Claude's rating before the tide average — mist is inside
Claude's judgement, as the inversion already is).

### M3 — Close the loop (verification; recorded, then reported)

**As built (2026-10-21, V187).** Departures from the bullets below, each deliberate:

- **`seen` is three-state on the wire.** Absent keeps the earlier tag, an explicit `"seen": null` clears
  it, a name sets it (`SeenChoice` and a deserialiser that overrides `getAbsentValue`). A kept tag the new
  rating contradicts (`NOT_SEEN` beside a new star, a sighting beside "didn't see it") is dropped. Two
  400s: `NOT_SEEN` with a star, a sighting with "didn't see it". V187 also CHECKs the pairing.
- **Writes are column-scoped** (Codex review of #1273): an existing row is never read-modified-written.
  No `seen` sent is a rating-only UPDATE with the keep-or-drop pairing rule in the SQL; `seen` with a star
  is a tag-only UPDATE (it never writes the star); `seen` with "didn't see it" is one UPDATE of both. A tag
  update the V187 CHECK refuses (the star changed underneath) is a 409. The client serialises writes per
  slot, so a tag tap and a star tap are never in the air together.
- **"Didn't see it" sends `NOT_SEEN`**, so the record says so in its own words; there is no NOT_SEEN chip.
- **A tag save has its own in-flight and failed sets** in `useOutcomeRatings`, so the block stays in its
  "You rated" layout while a tag saves; a failed one rolls back the tag alone.
- **The Lite lock shows the chips dimmed** rather than hiding them, so a Lite reader sees what Pro offers.
- **The saturation proxy needed no new Open-Meteo call**: the archive request already goes out for the
  observer point, and two more hourly variables stay inside one weight unit.
- **The mist-prone bucket selects `mist_potential == LIKELY` only** (the Claude echo on the forecast
  row), is a subset of `overall`, and is `null` when the window holds none; `mistProneSeenCounts` sits
  beside it with an `UNTAGGED` key. No admin screen renders the calibration report, so there is no UI row.

- **The outcome rating learns what was seen** (owner decision Q4): an optional `seen` tag on
  `PUT /api/outcome/ratings` — `COLOUR | MIST | INVERSION | FLAT | NOT_SEEN` — one chip row under the stars
  in `OutcomeRatingBlock`, nullable column, never required. It is the only observation of valley mist this
  project can ever hold, and it costs the reader one tap.
- **A saturation proxy from ERA5** at the observer at the event hour (`cloud_verification.observer_temp_c`,
  `observer_dew_point_c`; `CloudVerificationService` already fetches the observer point) — recorded, never
  required (so no backfill re-walk, the V175 rule), reported later as "forecast saturation vs reanalysis
  saturation" for mist-prone sites, with the stated caveat that a 31 km reanalysis cell cannot see a river.
- **The calibration gate** gains a mist-prone bucket: pairs where the forecast carried `mist_potential`
  LIKELY, and their `seen` tags. `missedOpportunities` on this bucket is the number this whole plan exists
  to drive to zero.
- **Rarity census** for the Coming up condition once `MIST_CALC` holds a year — switching off the pinned
  config fallback is its own change, as the inversion's is.

### M4 — Deleted (2026-10-21)

M0's side-by-side showed `best_match` over the UK already IS the Met Office 2 km model (`ukmo_seamless`
and `ukmo_uk_deterministic_2km` return byte-identical rows for the Brathay, grid elevation 54 m), and
that even at 2 km the river corridor did not saturate. There is no finer model to fetch; the calculator
carries the whole weight, which is why M1's terms score the set-up rather than the model's own fog.
Recorded here rather than removed so the question is not re-asked.

---

## 4. What this deliberately does not do

- **Does not lower the 5 km rule for anyone.** A hilltop in fog is in cloud; the rule is right there.
- **Does not create a MIST `LocationType` or lane.** A type inverts polarity and routes a lane (WOODLAND);
  a mist site is a sky site that gains a second way to be good. One prompt, mist-aware.
- **Does not put a second star anywhere.** No "mist 4★" beside "sky 2★" on a card: the star is the shot
  and the summary says which shot. (If the owner wants the split visible, it is `skyRating`'s sibling on
  the slot and a card change — Q2.)
- **Does not derive mist-proneness from terrain in M1.** A relief hint (site elevation against the maximum
  within 2 km from the elevation API already used at add time) is a convenience for later; the admin's
  tick is the fact.
- **Does not let the hot topic read Claude's echo first.** Calculator decides where it has run; the echo is
  a stand-in only — the round-4 inversion rule, applied from day one.

---

## 5. Owner decisions

**Decided 2026-10-21, all six as recommended** (owner, after reading the M0 results): Q1 a manual
`mist_prone` boolean; Q2 mist inside the one star, one sky prompt; Q3 the triage exemption for flagged
sites at LIKELY only; Q4 the `seen` tag on the outcome rating in M3; Q5 the tick list below as the first
pass, the Wall sites included; Q6 "Valley mist" and "Fog forecast".
M1 may start.

| # | Question | Recommendation |
|---|---|---|
| Q1 | The site fact: a manual `mist_prone` boolean, an enum of settings (valley floor / riverside / lake shore), or derived relief? | Boolean now. M0 §3 settled it: `elevation_m` is null for 107 of the 125 candidates, so nothing can be derived from stored metadata today. Derived hint in a later phase. |
| Q2 | Mist inside the sky star (one prompt) or a separate component with a combination rule? | Inside the star. "The star is the shot" and the inversion precedent; a second lane doubles cost and needs a merge rule with no evidence behind it. Revisit only if M3 shows Claude under-rates mist mornings it was shown. |
| Q3 | Triage exemption scope: flagged sites at LIKELY only (recommended), or every site when the signature holds? | Flagged only. Cost is then owner-bounded, and a signature at an unflagged site has no geography to vouch for it. |
| Q4 | The outcome rating's `seen` tag — a product change to the "How was it?" block. | Yes, in M3. It is the only verification valley mist can ever get here. |
| Q5 | Roster: which sites to tick mist-prone? | Owner's call, by hand (M0 §3). First ticks suggested from the refused-mornings table: the Brathay, Rydal Water, Grasmere Shoreline, Loughrigg Tarn, Waterhead, Bowness Bay, both Derwent Water shores, Buttermere, Crummock, Little Langdale Tarn, Tarn Hows, Brothers Water, Ullswater South Shore, Clappersgate, Slater's Bridge; Ribblehead, Dent Head, Malham Tarn, Arncliffe, Burnsall, Kilnsey, Muker and Gunnerside; the Breamish Valley, the Wansbeck at Bothal, Lamley Viaduct; and the Hadrian's Wall sites that lead the refused table — Walltown Crags, Sycamore Gap, Highshield Crags, Steel Rigg & Winshield Crags, Caw Gap, Cawfields, Peel Craggs, Hotbank Crags, Cuddy's Crags, Housesteads — which the owner confirms are mist sites of negligible relief, not viewpoints above a valley. M1 ships whether or not any is ticked. |
| Q6 | Wording: "Valley mist" for the topic and condition; "Fog forecast" for the honesty filter's stand-down. | As written; the design-bundle rule that a word must be true of the data. |

---

## 6. Process

Same as every plan here: each phase build → `./mvnw clean verify -Dtest='!**/integration/**'
-DfailIfNoSpecifiedTests=false` and `npm run lint && npm test && npm audit --audit-level=high && npm run
build` → `changelog.d/YYYYMMDD-<slug>.md` → adversarial review of the diff (read-only agents) → fix → commit.
CLAUDE.md gains a **Valley mist** bullet under *What's Built* at M1, and the "Where a rating lives" readings
list names `mist_score`/`mist_scored`. M0's SQL results are pasted into §3 M0 of this document before M1
starts, so the thresholds in M1 are set against a measured population, not a guess.
