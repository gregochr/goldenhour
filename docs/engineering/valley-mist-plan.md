# Valley mist — the morning PhotoCast cannot see (2026-10-17)

**Status:** plan, awaiting the owner's decisions in §5. Nothing in it is built.
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

- **Open-Meteo's `visibility` is a grid-cell figure** (best_match, ~9–11 km over the UK; the client sets no
  `models=`). A 2 km river valley's fog is sub-grid. The model's own visibility is therefore evidence, not
  the answer, exactly as the coned horizon reading was found to be for cloud approach — which is why the
  inversion was given a calculator over predictors rather than a threshold on one field.
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

Also in M0, by hand (one request each, no code): fetch Open-Meteo `hourly=visibility,dew_point_2m,
temperature_2m,wind_speed_10m,cloud_cover_low` for the Brathay (54.42, −3.02) for 17 Oct with
`models=best_match` and `models=ukmo_seamless`, and record both in the plan. If UKV's 2 km visibility
resolved the river where best_match did not, M4 is justified; if neither did, the calculator must carry the
whole weight and the plan should say so.

### M1 — The reading and the topic ("what is happening"; no Claude cost, no star change)

- **`locations.mist_prone BOOLEAN NOT NULL DEFAULT FALSE`** (migration; `updatable = false`, written by a
  column-scoped `LocationRepository.updateMistProne`, the `overlooksWater` pattern; admin UI checkbox beside
  it in `LocationManagementView`, worded "Mist forms here: valley floor, riverside, lake shore"). ⚠️ Not a
  `LocationType`: a type routes a subject lane and inverts polarity (WOODLAND); this is geography on a sky
  site that keeps its sky.
- **`ValleyMistCalculator`** (pure, `service/`, 0–10, the inversion calculator's sibling). Terms, each a
  prior stated as cheap-to-be-wrong-about until M3 verifies: moisture from the **minimum** temp–dew gap over
  the night-side `MistTrend` slots (T−3h..event for sunrise; event..T+2h for sunset, where mist *forms*) —
  reusing `InversionScoreCalculator.moistureScore`, extracted to a shared helper so "moisture is counted
  once" has one home; wind from the night-side minimum 10 m wind (calm ≤ 2 m/s full marks, > 4 m/s zero);
  a **sky gate**, the inverse of the inversion's cloud veto: radiative cooling needs a clear night, so
  observer **total** cloud over the night-side slots above ~50% caps the score (the inversion needs 15–80%
  low cloud to form a sea; here the fog *is* the low cloud and the sky above must be open); a wet-ground
  term from `precedingPrecipitationMm` (rain then clearing is the classic set-up); and a **confirmation**
  term, not a gate, when the model's own event-hour visibility is already under 5 km. Season enters only
  through night length implicitly (the slots), never as a calendar rule. Bands via a `MistPotential`
  (NONE / POSSIBLE / LIKELY / FOGBOUND) — FOGBOUND when visibility < 300 m with no improving trend, the
  white-frame case the woodland evaluator already names (`MIST_TOO_THICK_M`). Requires
  `MistTrend.MistSlot` to carry observer total cloud and wind, both already in the hourly array
  (`extractMistTrend` grows two fields; the prompt's existing rendering is unchanged).
- **Gate:** `ForecastDataAugmentor.augmentWithValleyMist(data, mistProne)` runs for `mist_prone` sites only,
  in `fetchWeatherAndTriage` beside `augmentWithInversionScore`, BEFORE the readings write and triage.
- **`slot_atmosphere.mist_score DOUBLE NULL` + `mist_scored BOOLEAN NOT NULL DEFAULT FALSE`** — the V158
  pair exactly, for the same reason (a fresh null is an answer; a pre-column row is not). `SlotSignals
  .Readings` carries both. No Claude echo exists yet, so no `effective…` fallback is needed in M1; M2 adds
  the echo and the `effectiveMistScore()` twin of `effectiveInversionScore()`.
- **`MistHotTopicStrategy`** (`"MIST"`, priority 2, SUNRISE and SUNSET rows — unlike the inversion, mist
  forming at dusk over a river is a real evening shot — regions of mist-prone sites at LIKELY or above,
  `SolarEventFreshness` drops passed events, one topic per remaining event) with a fact line naming the
  count of sites and the italic cue ("get there before the sun — it burns off within the hour"). Rides
  `GET /api/briefing` to every role; reaches Ask's `get_hot_topics` and the Coming up condition without
  further change; `AskEventType.key` already normalises a new type.
- **Coming up "Valley mist" condition** beside "Valley inversions" (`SlotSignalReader`, forward peak and
  trailing history, rarity PINNED to the config fallback exactly as the inversion's is, until the log exists).
- **`TopicDailyLogJob` series `MIST_CALC`** (SUNRISE and SUNSET rows at LIKELY+), the unbiased population
  for a later rarity census — the `INVERSION_CALC` precedent, by name not by date.
- **Wording honesty, two small fixes that belong with the reading:** `BriefingVerdictEvaluator`'s
  "Mist risk" flag reads "Mist likely" on a `mist_prone` site; and `BriefingHonestyFilter`'s `STOOD_DOWN`
  wording, "Too unsettled to forecast", is wrong for a region whose slots were all stood down for
  `LOW_VISIBILITY` — `CoverageReasons.of` already folds the latest disposition per slot, so a
  LOW_VISIBILITY majority can read "Fog forecast" (the honest word; the plan-card-honesty rule, applied to
  a reason that was always mis-worded).

Tests: calculator table-driven on the M0 signatures; the writer's two columns; the strategy's region
grouping and freshness; the Coming up condition; the migration under Testcontainers; the admin form.

### M2 — The star ("where is worth going"; Claude cost, bounded)

- **Triage routing.** `WeatherTriageEvaluator.evaluate` gains the slot's `MistPotential` (on
  `AtmosphericData`, null for an unflagged site). Rule 3 is NOT CONSULTED when the potential is LIKELY
  (never FOGBOUND, never POSSIBLE): the slot proceeds to Gate 4 and the sky lane as a `MIST_CANDIDATE`
  (an INFO line and a disposition note; no new `DispositionCategory` — it is an `EVALUATED` slot). Rules 1
  and 2 still apply (rain is rain; a blocked solar horizon 113 km away is still blocked). ⚠️ M0 must first
  confirm how best_match represents valley fog at the observer: if it inflates `cloud_cover_low` to ~100%
  at the site, the directional rule 1 is unaffected (it reads the solar horizon), but the observer
  fallback would stand the slot down, and the exemption must then cover that fallback too.
- **The prompt.** A `VALLEY MIST CONDITIONS:` block in the user message for a mist-prone site (score,
  band, the minimum gap and the hour it falls, night-side wind, sky, the clearing trend already printed by
  `MistTrend`) and ONE site sentence: "This location is a valley-floor / riverside site where mist forms
  around the camera; thin mist with a clear sky above at sunrise is the shot here, and may rate 4–5 with no
  horizon colour at all. Fog under 300 m with no clearing is a white frame: rate 2." The existing MIST
  guidance is otherwise untouched; the inversion precedent on stating facts, not asking for the number back,
  holds. System prompt unchanged where possible, so the batch cache prefix is unchanged for every site.
- **Claude's echo.** `mist_potential` (enum name) on `forecast_evaluation`, nullable, written at
  `buildEntity`, served by `ForecastDtoMapper` as the popup/callout badge — the inversion badge's twin, on
  Claude's reading alone, so the badge and the topic may disagree by design (two surfaces, two questions).
- **`SlotSignals.effectiveMistScore()`**: the calculator's reading when `mist_scored`, else Claude's
  echo mapped onto the band scale, the `effectiveInversionScore()` rule verbatim.
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

### M4 — The right model for the right question (only if M0 says so)

If M0's side-by-side showed UKV resolving the Brathay's fog where best_match did not: one extra Open-Meteo
request per mist-prone site per prefetch with `models=ukmo_seamless`, visibility and dew point only, the
`fetchPressureLevelCloudBatch` shape (shared rate limiter, no retry, no breaker, best-effort, null on
failure), feeding the calculator's confirmation term and the prompt block as "UKV 2 km visibility". The
rating-affecting best_match fetch is untouched. If M0 did not show it, M4 is deleted from this plan rather
than left as an aspiration.

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

| # | Question | Recommendation |
|---|---|---|
| Q1 | The site fact: a manual `mist_prone` boolean, an enum of settings (valley floor / riverside / lake shore), or derived relief? | Boolean now. The calculator does not need the setting's kind, and an enum nobody branches on is a future session's temptation. Derived hint in a later phase. |
| Q2 | Mist inside the sky star (one prompt) or a separate component with a combination rule? | Inside the star. "The star is the shot" and the inversion precedent; a second lane doubles cost and needs a merge rule with no evidence behind it. Revisit only if M3 shows Claude under-rates mist mornings it was shown. |
| Q3 | Triage exemption scope: flagged sites at LIKELY only (recommended), or every site when the signature holds? | Flagged only. Cost is then owner-bounded, and a signature at an unflagged site has no geography to vouch for it. |
| Q4 | The outcome rating's `seen` tag — a product change to the "How was it?" block. | Yes, in M3. It is the only verification valley mist can ever get here. |
| Q5 | Roster: are there mist-prone sites to flag? (The Wear at Durham, the Tees and Derwent valleys, the Lakes if the roster reaches them.) | Owner's call; M0 §3 produces the shortlist from stored elevation. M1 ships whether or not any is ticked. |
| Q6 | Wording: "Valley mist" for the topic and condition; "Fog forecast" for the honesty filter's stand-down. | As written; the design-bundle rule that a word must be true of the data. |

---

## 6. Process

Same as every plan here: each phase build → `./mvnw clean verify -Dtest='!**/integration/**'
-DfailIfNoSpecifiedTests=false` and `npm run lint && npm test && npm audit --audit-level=high && npm run
build` → `changelog.d/YYYYMMDD-<slug>.md` → adversarial review of the diff (read-only agents) → fix → commit.
CLAUDE.md gains a **Valley mist** bullet under *What's Built* at M1, and the "Where a rating lives" readings
list names `mist_score`/`mist_scored`. M0's SQL results are pasted into §3 M0 of this document before M1
starts, so the thresholds in M1 are set against a measured population, not a guess.
