# Coastal mist: light sea mist is the shot, sea fret is not (2026-11-01)

**Status:** plan, revised after two reviews; owner confirmed the three review changes (2026-11-01); Phase 1a done; Phase 1 merged (#1583); Phase 2 built and reviewed.
- *Owner decisions (2026-11-01):* every coastal non-mist-prone site gets the prompt line; it goes live with
  a switch; **yes to the hot topic**.
- *Changed since then:* the review moved the bands and narrowed the topic (§4). The owner should confirm
  those three changes before the build.

Evidence: `coastal-mist-data/` (1,000 Boulmer sunrise mornings against the forecast model, 2023–2025).

## 1. What is true today

- **Valley mist:** `ValleyMistCalculator` with bands NONE/POSSIBLE/LIKELY/FOGBOUND. It runs only on
  `mist_prone` sites and models radiation fog.
- **Coastal sites:** a site with tide types is treated as coastal. These sites get no mist signal.
- **Triage rule 3:** `WeatherTriageEvaluator` (`VISIBILITY_THRESHOLD` = 5,000 m) stands a slot down **before
  Claude sees it** when model visibility is under 5 km. The only exemption is valley-mist LIKELY. So anything
  this feature would say below 5 km never reaches a prompt in the scheduled, retry or sync paths.
- **System prompt:** it calls "thin mist, visibility 2–8 km, little low cloud" POSITIVE and "dense fog < 1 km"
  NEGATIVE, and says nothing about 1–2 km. *Corrected:* an earlier draft said this rewards haar. It barely
  does: 1 of 38 fog mornings falls in that window, and triage removes visibility under 5 km anyway.

## 2. What the evidence says (`coastal-mist-data/README.md`)

1. **Model visibility separates the three states.** Medians are 21.5 km (clear), about 7.5 km (light mist)
   and about 2 km (fog). It underestimates fog: only 1 of 38 observed fog mornings was forecast under
   1 km.
2. **Under 5 km is unreliable as "fret".**
   - Under 1.5 km: 71 % fog.
   - 1.5–3 km: 27 % fog.
   - 3–5 km: the worst slice for "light mist" (23 clear and 5 fog of 45).

   This sits on the same line where triage already stands slots down, so the feature needs nothing below
   5 km.
3. **5–10 km is the light-mist band**, and **low cloud is the biggest lever.** At 5–10 km with T−Td ≤ 1.5 °C:

   | Gate | Mornings per year (one site) | Precision (observed mist) |
   |---|---|---|
   | none | 25.7 | 56 % |
   | low cloud < 80 % | 15.3 | 63 % |
   | **low cloud < 50 %** | **12.3** | **68 %** |

   No threshold change (3/4/5 km low, 8/9/10/12 km high, gap 1.0/1.5/2.0) beats it.
4. **Wind direction and sea temperature add nothing to the bands.**
5. **Seasonal.** Spring fog is caught well, but summer haar is often missed: four June–July fog mornings
   were forecast at 10–24 km.
6. **The observation side uses a different window.** Observations take the minimum over sunrise ±1 h, while
   the model is the single sunrise hour. Phase 1a checks whether the model's minimum over that window does
   better.

## 3. Coupling rules

1. **Valley mist does not move.** `ValleyMistCalculator`, `MoistureScoring`, `MistSlot`, `FORECAST_PARAMS` and
   `mist_prone` behaviour stay as they are, and the valley-mist guards stay green and are extended.
2. **One mist signal per site.** Coastal mist runs only where
   `!location.getTideType().isEmpty() && !location.isMistProne()`.
   - Call it from `ForecastService` beside `augmentWithValleyMist`.
   - Never use `data.tide() != null`: a failed tide fetch would silently drop the band.
3. **No new weather request.**
4. **The system prompt is not touched,** and neither is `CoastalPromptBuilder.COASTAL_SYSTEM_PROMPT_SUFFIX`.
   Both are cache prefixes. The text goes in the user message.
5. **Triage is not changed:** no new exemption and no extra Claude calls.
6. **The two-question rule holds.** The band is computed in the augmentor, before the `slot_atmosphere` write
   and triage, as valley mist and inversion are. The topic reads storage, so a stood-down rating never
   silences it.

## 4. Phases

### Phase 1a: measurement (planner, before the build) — DONE 2026-11-01

**Result: keep the event slot.** The minimum visibility over sunrise ±1 h is clearly worse:

| Rule | LIGHT precision | Topic gate (per year, precision) | Fog → THICK | Clear → NONE |
|---|---|---|---|---|
| **event slot** | **56 % (n = 75)** | **12.0, 67 %** | 31/38 | **91.3 %** |
| window minimum | 36 % (n = 95) | 13.7, 54 % | 32/38 | 84.1 % |

This used the full hourly series already fetched, so no new requests were needed. (Phase 1 pins its matrix from the committed CSV, whose model values are rounded to 0.1, so one CLEAR and one MIST row move NONE→LIGHT: LIGHT n = 77, clear→NONE 91.1 %.) The evidence README has
been corrected.

Original task:
- Re-pull the model at sunrise −1 h and +1 h for the same 1,000 mornings, from the historical-forecast API
  within the daily limit.
- Compare the rule on the event slot alone against the rule on the window's minimum visibility. Adopt the
  minimum if it is clearly better; otherwise record the asymmetry as a limit.
- Fix the evidence README:
  - 103 rows carry RA or DZ, so rain mornings were not excluded;
  - two FOG rows are rain-reduced;
  - one FOG-on row has 4.4 km visibility;
  - 33 CLEAR rows carry BR;
  - one row is classed MIST-calm.

### Phase 1: calculator, storage, prompt line and log (live, with a switch)

1. **`CoastalMistCalculator`** (pure, in `service`). Inputs are event-slot visibility
   (`weather().visibilityMetres()`, metres), T (`comfort().temperatureCelsius()`), Td
   (`weather().dewPointCelsius()`) and low cloud. Bands:
   - **LIGHT:** 5,000 ≤ visibility ≤ 10,000 m (inclusive both ends) and gap ≤ 1.5 °C, with the gap rounded to
     0.1 °C first, as valley mist does.
   - **THICK:** visibility < 5,000 m. It is stored and logged but never shown; triage already stands these
     slots down. It is kept for tuning, because "fret versus thick mist" is not separable below 3 km.
   - **NONE:** everything else, including a null gap when visibility is 5 km or more.

   Every threshold is a named constant documented as a prior.
2. **Carrier.** A `CoastalMistResult(band, visibilityMetres, gapC, lowCloudPct)` appended last on
   `AtmosphericData`, with:
   - delegating constructors at today's arities;
   - a `withCoastalMist` method, and every existing `withX` carrying the new field;
   - a test that every `withX` preserves it (the inversion-v2 precedent).
3. **Storage.** Migration `V210__slot_atmosphere_coastal_mist.sql` (read the next free number at build time)
   adds:
   - `coastal_mist_band VARCHAR(10)` NULL;
   - `coastal_mist_scored BOOLEAN NOT NULL DEFAULT FALSE` (the `mist_scored` / `inversion_scored` pattern:
     when it is true, a null band means "scored, no band").

   Store the band as a String, as `mist_potential` is: no `@Enumerated`, so no `LocalH2EnumWidener` entry.
   - **Entity and writer:** `SlotAtmosphereEntity` gets the fields, and `SlotAtmosphereWriter` writes both on
     every write. When switched off or not coastal it writes null and false.
   - **Reader:** add a `SlotSignals.Readings` component (with delegating constructors and an updated `EMPTY`)
     and fill it in `SlotSignalReader.build`.
   - **Tests:** a Testcontainers migration test, which only CI runs.
4. **Prompt (user message).** Place it directly after the `appendValleyMistConditions` call site, and only
   when the band is LIGHT. It never says "VALLEY MIST" and never asks for `mist_potential`.
   - *Wording* (owner approved the intent; the exact text below is the review's hedged version):
     "Coastal mist: the model shows about X km visibility with near-saturated air, so a light sea mist is
     likely, softening the light and adding depth. It can be the shot here; the model sometimes misses
     thicker fret."
   - *Under a grey lid:* when low cloud is 80 % or more, add "under a grey lid".
   - *Number format:* X is floored to 0.1 km.
   - *With no band,* the prompt is byte-identical. The golden-master fixtures `coastal-mist-light.txt` (new),
     `coastal-tidal` and `coastal-surge` (unchanged) prove it.
5. **Switch.** An `@ConfigurationProperties("photocast.coastal-mist")` class with `enabled`, defaulting to
   true **in the class** (production's `application.yml` is not in the repo). Document it in
   `application-example.yml` and `configuration.md`. When off, nothing is computed or stored.
6. **Log.** One `[COASTAL MIST]` INFO line per slot when the band is not NONE, showing site, date, event,
   visibility, gap, low cloud and band. NONE logs at DEBUG.
7. **Tests.**
   - The 1,000-row fixture is pinned as an **exact confusion-matrix snapshot**, not percentage bars (n = 38 fog
     is too small for a ±15-point interval).
   - Band boundaries: 4,999 and 5,000 m; 10,000 and 10,001 m; a gap of 1.5 and 1.6 °C after rounding; null
     inputs.
   - The predicate (coastal, mist-prone, switch off).
   - The valley-mist isolation guard, extended.
   - Specific Mockito matchers.
8. **Docs.** `conditions.md` (new section), `configuration.md`, `database-migrations.md`, and the changelog.

### Phase 2: the "Sea mist" hot topic (owner: yes)
- **When it fires:** a LIGHT band **with low cloud < 50 %** and **no precipitation** at the event slot. That
  is about 12 mornings a year per site, with 68 % precision on the evidence. It is gated further:
  - **sunrise only**, since there is no sunset evidence;
  - **daysAhead ≤ 1**, since the evidence is short-lead forecasts;
  - **east (North Sea) coast only**, since haar evidence is North Sea and Irish Sea fog is a different
    mechanism. The rule is coastal sites at longitude > −2.5° **and latitude ≥ 53.5°** (build review,
    2026-11-01: longitude alone admitted the English Channel coast). The owner checks the qualifying sites with a
    read-only SQL query rather than a test, because production's location table is configured on the host. The
    Cumbrian coast waits for its own evidence.
- **Chip colour:** `#D5DCD6`. It was chosen by calculation to stay ≥ ΔE 14 from Big seas, valley mist, tide
  teal and the dawn blue under deuteranopia and protanopia, because the Map rings are colour-only. A test pins
  it.
- **Never raised for THICK**, just as FOGBOUND is never raised for valleys.
- **Name and display:** type `SEA_MIST` (never reuse `MIST`, which would collide with valley mist's per-date
  topic). It is served to every role, as MIST is.
- **Registries to update.** A missed one fails a test.
  - *Backend:*
    - `TopicRarity.RANK_BY_TYPE` and `TopicRarityTest`;
    - `HotTopicGlanceDecorator.WEATHER_TYPES`;
    - `HotTopicEventEnricher.EVENT_BY_TYPE` and `EVENT_STAMPED_BY_DETECTOR`;
    - the `HotTopicSimulationService` catalogue and its test;
    - optionally a `COASTAL_MIST_CALC` row in `TopicDailyLogJob`.
  - *Frontend:*
    - `topicPalette.js` `SWATCH_COLOR` and `TOPIC_META`, with a `chipName` of 6 characters or fewer that is
      not "Mist". Decide deliberately whether `placeNoun` is "coastal", because a test reserves that for tide
      types;
    - `windowFirstTopics.js` `REGION_SCOPED_TOPIC_TYPES`;
    - `topicPalette.test.js` and `windowFirstTopics.test.js` (the type lists go from 18 to 19), and the
      "eighteen" wording in both modules.
  - The UI review cadence applies.
- **Strategy:** a new `SeaMistHotTopicStrategy` modelled on `MistHotTopicStrategy`, reading `SlotSignals`. The
  served-briefing epoch and memo need nothing new, and simulation gating comes free through the
  catalogue.

### Phase 3 (optional, after a season)
- **Tuning:** recalibrate 5 km, 10 km, 1.5 °C and the < 50 % low-cloud gate against outcome ratings.
  - `slot_atmosphere` is pruned after 180 days, so recompute bands from `forecast_evaluation`
    (visibility, temperature, dew point, kept even for triaged rows) joined to `outcome_rating`.
- **Further scope:** the west coast, and onshore-wind wording (which needs a per-site sea bearing).
- **High cliff-tops** (Whitby, Hartlepool Headland, Bamburgh's rock): haar *below* the viewpoint can be a cloud
  sea rather than a white-out. That can only be judged with evidence.

## 5. Known limits
- **Distance:** Boulmer is about 25 km from Bamburgh, and haar is patchy.
- **Summer haar is often missed:** 4 of 10 June–August fog mornings were caught, against 12 of 13 in spring. A
  NONE band never suppresses anything; the feature only adds a line or a topic.
- **No sunset evidence:** the prompt line runs on both events, but the topic runs at sunrise only.
- **Short-lead evidence:** visibility at later lead times comes from coarser models.
- **Clear mornings misread as mist:** about 1 in 3 topic mornings will be clear rather than misty.

## 6. Decisions
- *Accepted 2026-11-01:* every coastal non-mist-prone site gets the prompt line; it goes live with a switch;
  yes to the hot topic.
- *For the owner to confirm (review changes):*
  1. The light band becomes **5–10 km**, not 3–10, so it sits above triage's 5 km line. Below 5 km nothing is
     shown, and **no triage exemption** is made, so there are no extra Claude calls.
  2. **The sea-fret line is dropped:** it could never reach a prompt, and under 5 km is not reliably
     "fret".
  3. **The topic is narrowed** to low cloud < 50 %, no precipitation, sunrise only, daysAhead ≤ 1, and the east
     coast only.
