# Coastal mist: light sea mist is the shot, sea fret is not (2026-11-01)

**Status:** plan. Owner decisions 2026-11-01: every recommendation in §6 accepted (all coastal non-mist-prone
sites; 3 km / 10 km / 1.5 °C; the Phase 1 wording; live with a switch), and **yes to the Phase 2 hot topic**.
Next: adversarial review, then Phase 0. Owner idea (2026-10-31): "fog is not good, but light mist would be
good at, say, Bamburgh Castle." Evidence: `coastal-mist-data/` (1,000 Boulmer sunrise mornings against the
forecast model, 2023–2025).

## 1. What is true today

- **Valley mist** (`ValleyMistCalculator`, `MistPotential` NONE/POSSIBLE/LIKELY/FOGBOUND) runs only on
  `mist_prone` sites. It models radiation fog (a clear, calm night cooling the ground). FOGBOUND is model
  visibility < 300 m.
- **Coastal sites** (`LocationService.isCoastal`: the site has tide types) get no mist signal at all. Bamburgh
  is not mist-prone.
- **The system prompt's generic mist guidance** (`PromptBuilder` ~186–196) calls "thin ground mist (visibility
  2–8 km) with … `cloud_cover_low` < 30 %" **POSITIVE**. Only "dense fog (visibility < 1 km)" is NEGATIVE.

## 2. What the evidence says (`coastal-mist-data/README.md`)

| Observed at Boulmer | Mornings | Model visibility (median) | Model T−Td |
|---|---|---|---|
| Clear | 824 | 21.5 km | 2.2 °C |
| Light mist (BR, 1–8 km) | 137 | 7.4–7.6 km | ~1.0 °C |
| Fog / haar (FG or < 1 km) | 38 | 1.8–2.1 km | ~0.5 °C |

1. **The model separates clear, light mist and fog** at the coast, by visibility alone.
2. **It underestimates fog:** observed haar under 1 km sits at a model median of 2.1 km, and only 1 of the 14
   haar mornings came in under 1 km. So valley mist's FOGBOUND (< 300 m) would almost never fire at the
   coast.
3. **Today's prompt probably rewards some haar mornings.** A model reading of 1.4 km with 24 % low cloud
   (observed fog under 100 m, 15 May 2024) reads as the system prompt's POSITIVE "thin mist, clear above".
4. **Wind direction and sea temperature are not needed for the bands.** Fog off the land (24 mornings) is just
   as much a white-out as onshore haar (14), and dew point minus sea-surface temperature is identical (−1.0 °C)
   for onshore mist and haar.
5. **Light mist is common:** 137 mornings in three years against 38 fog mornings.

## 3. Coupling rules (as in the inversion series)

1. **Valley mist does not move.** `ValleyMistCalculator`, `MoistureScoring`, `MistSlot`, `FORECAST_PARAMS` and
   `mist_prone` behaviour are unchanged. The Phase 0 valley-mist guards stay green and are extended.
2. **One mist signal per site.** Coastal mist runs only on coastal sites that are **not** `mist_prone`, so
   valley mist keeps any site that has both.
3. **No new request.** Every input (visibility, temperature, dew point, low cloud, wind) is already in the main
   forecast fetch.
4. **The system prompt is not touched** (shared batch cache prefix). The coastal guidance goes in the user
   message, as valley mist's site sentence does.

## 4. Phases

### Phase 0: pin the evidence (tests only)
- **Fixture:** copy the CSV into test resources and run the proposed band rule (Phase 1) over all 1,000
  rows.
- **What the tests pin** (they report, not tune):
  - the confusion matrix (observed class × band);
  - "observed fog → FRET" at **≥ 70 %**;
  - "observed clear → NONE" at **≥ 85 %**.
- **Guard test:** a valley-mist fixture (Brathay, Stainton) through the coastal path must give no coastal
  band, and mist must be unchanged.

### Phase 1: `CoastalMistCalculator` and the prompt line (live, with a switch)
- **Calculator:** pure, using the event-slot model values. Bands:
  - **FRET:** visibility < `FRET_VISIBILITY_M` = 3,000 m. A white-out risk, never an opportunity.
  - **LIGHT:** 3,000–10,000 m **and** T−Td ≤ `LIGHT_GAP_C` = 1.5 °C. Atmospheric light sea mist.
  - **NONE:** otherwise. Missing visibility gives no band.
- **Carrier:** one nullable field on `AtmosphericData`, with a delegating constructor and every `withX`
  carrying it, as in the inversion pattern.
- **Prompt:** a user-message line, coastal non-mist-prone sites only. Wording is for the owner to approve.
  - *LIGHT:* "Coastal mist: the model shows about X km visibility with near-saturated air. A light sea mist is
    likely, softening the light and adding depth; it can be the shot here."
  - *FRET:* "Sea fret risk: the model shows about X km, and at the coast it underestimates fog. Expect
    visibility under 1 km. The subject may be hidden; rate the light down."
  - This overrides the system prompt's "2–8 km positive" for coastal sites without touching the system
    prompt. With no band, the prompt is byte-identical.
- **Switch:** `photocast.coastal-mist.enabled`, default true, set in config and applied on restart.
- **Log:** one `[COASTAL MIST]` INFO line per slot (site, date, event, visibility, gap, band).
- **Docs:** `conditions.md`, `configuration.md`, the changelog.

### Phase 2: a "Sea mist" hot topic (LIGHT only)
- **What it does:** mirrors `MistHotTopicStrategy`. LIGHT bands at coastal sites, sunrise first. It is never
  raised for FRET, as FOGBOUND is never raised for valleys.
- **Rules it follows:** the two-question rule (it reports what is happening, and is never silenced by a
  rating stand-down), the simulation gates and the served-briefing epoch, as the other topics do.
- **Needs:** frontend work (topic copy, icon), so it goes through the UI review cadence.

### Phase 3 (optional, needs evidence)
- **Onshore wording:** a per-site sea bearing would let the FRET line say "onshore wind, haar" instead of
  "fog". It needs a new column, so build it only if outcome ratings show the wording matters.
- **Tuning:** recalibrate the 3 km and 10 km thresholds against outcome ratings ("How was it?") at coastal
  sites after a season.

## 5. Known limits

- **Boulmer is about 25 km from Bamburgh,** and haar is patchy, so treat a single morning as indicative.
- **4 of 14 haar mornings had model visibility of 3.2–10 km,** so they would be called LIGHT. That is the
  forgiving direction (a hopeful trip that finds a white-out). About 15 % of light-mist mornings fall under
  3 km and would be called FRET, which is the conservative direction (a missed opportunity).
- **Only the event slot is read.** A sunrise trend ("fret clearing by T+1h") is left for later, using the
  existing `MistTrend` hours.

## 6. Decisions for the owner

1. **Scope:** every coastal non-mist-prone site, or an opt-in `coastal_mist` flag per site? **Recommended:**
   every coastal site, since the evidence is generic to the North Sea coast. Note that west-coast sites (the
   Cumbrian coast) have no evidence behind them yet.
2. **Thresholds:** 3 km and 10 km, with a 1.5 °C gap. **Recommended:** as above, pinned by Phase 0.
3. **Prompt wording:** the two lines in Phase 1.
4. **Hot topic** (Phase 2): yes or no.
5. **Shadow or live:** **Recommended:** live with the switch, as for inversion v2, since it only adds a
   user-message line and is reversible.
