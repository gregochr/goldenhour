# Frost & mist — plan

**Status:** built (2026-11-25; revision 2 of the plan), after an adversarial review against the code (findings folded in below).
The owner asked for it on 2026-11-25 ("implement Frost and Mist as follow ups").
**Sequencing:** built after snow S3 (`snow-topics-truth-plan.md`) and the bluebell rings fix have merged, one
phase, never alongside another build.
**Roles:** Opus plans and reviews; Sonnet builds (CLAUDE.md, "How every code change is made").

## 1. The gap

A clear, calm, freezing night with mist in the valleys is the classic hoar-frost morning. Today the app shows it
only as **"Valley mist"**. Frost is named in exactly one place: the **"Snow, mist & frost"** variant of SNOW_MIST
(`SnowFreshHotTopicStrategy`), which needs snow lying. Until 2026-11-03 that variant was labelled "Snow mist &
hoar frost" (`snow-mist-valley-band-plan.md`), which is why hoar frost felt covered. On bare ground it never was.

## 2. The physics, and what our inputs can honestly claim

- **[F] Hoar frost** is deposition of vapour as ice on surfaces below the frost point. Sub-zero air that is
  near saturation (with respect to water) is supersaturated with respect to ice, so hoar frost is the likely
  outcome on exposed surfaces. That pair — sub-zero 2 m air and the valley mist band — is the honest core of
  this topic.
- **[F] The valley mist band does not prove a clear, calm night.** `ValleyMistCalculator` reaches LIKELY on
  moisture + calm + cooling with mean cloud up to 69 % (the cloud veto is at ≥ 70 %), its wind term uses the
  night's *minimum* wind, and its "night" is the 3 h before the event slot
  (`OpenMeteoResponseParser.MIST_TREND_HOURS_BACK`). So the copy says frost **can** feather the branches; it
  does not promise a clear, calm night.
- **[F] Rime** needs supercooled fog droplets striking a surface; thin mist gives little. The copy says mist
  **may** freeze as rime. FOGBOUND stays excluded, as it is for Valley mist.
- **[F] Ground frost happens at screen temperatures above 0 °C** (grass minimum runs 2–5 °C below the 2 m value
  on radiative nights). A sub-zero 2 m rule therefore under-claims; it never over-claims.
- **[F] The temperature is a point value from the first hourly slot at or after sunrise**
  (`TimeSlotUtils.findBestIndex`, written by `SlotAtmosphereWriter` from the event slot's `temperature_2m`,
  elevation-downscaled by Open-Meteo). Up to 59 minutes after sunrise a clear morning is already warming, which
  again under-claims. SNOW_MIST's frost variant reads the same column, so the two frost claims share one input.

## 3. The design

### 3.1 The rule

New type **`FROST_MIST`**, label **"Frost & mist"**, from **SUNRISE** rows only. A place fires on a day when:

1. its SUNRISE slot's valley mist band is **LIKELY** (`MistHotTopicStrategy.isLikely`, unchanged; the band is
   read, never re-derived); and
2. its SUNRISE `temperatureCelsius` is **below 0.0 °C**, the same test as SNOW_MIST's frost variant. Null never
   fires.

[J] `FROST_AIR_MAX_C = 0.0` is a named constant with boundary tests (−0.1 fires; 0.0 and null do not).

The rule is a public static predicate, `FrostMistHotTopicStrategy.isFrostMist(SlotSignals)`, because Valley mist
reads it too (§3.2).

**No snow exclusion.** Revision 1 excluded places with snow lying. Review showed a hole: SNOW_MIST takes its frost
variant from one representative place per day, so a snowy, misty, sub-zero place whose representative is warmer
would lose the frost claim entirely. Without the exclusion a snowy frost-and-mist morning carries both
"Snow, mist & frost" (or "Fresh snow with mist") and "Frost & mist": both true, rare, and it removes this phase's
dependency on S3's snow rows.

### 3.2 Shape, and Valley mist

- `PerDateHotTopicBuilder.perDate`: **one topic per day, exact `locationNames`**, regions of the ringed places.
- Keep `freshness.isAhead`, as Valley mist does: the photograph is the sunrise, unlike lying snow.
- Stamped SUNRISE by the detector (`withEvent`), as Valley mist is. Add `FROST_MIST` to
  `HotTopicEventEnricher.EVENT_BY_TYPE` (SUNRISE) and `EVENT_STAMPED_BY_DETECTOR`.
- **Frost & mist replaces Valley mist for those place-days (planner decision D2).** Every Frost & mist place is
  by definition a Valley mist place on the same sunrise, so stacking would draw two rings and two badges on the
  same places every time. `MistHotTopicStrategy` drops SUNRISE rows where `isFrostMist` holds, so Valley mist
  keeps the mild-mist places and its sunsets unchanged. Owner decision Q7 (snow mist stacking with Valley mist)
  accepted an overlap that already existed between two different phenomena; it is not a precedent for
  duplicating one. Reversal is deleting that one filter.
- A new `FrostMistHotTopicStrategy`, reading `inputs.slotSignals()`; its two-argument `detect` reads them itself.

### 3.3 Facts and copy

| Field | Value |
|---|---|
| Label | "Frost & mist" |
| Detail | "Hoar frost and valley mist at first light" |
| Facts | `air -N °C · frost likely` (exactly SNOW_MIST's frost fact, from the day's coldest qualifying place), then `valley mist · likely at N sites` |
| Note | "densest before dawn — frost on every branch" (SNOW_MIST's frost note, reused) |
| Description | "On a cold, still, misty night, hoar frost can feather twigs and grass, and mist pools on the valley floor. Where the mist thickens it may freeze as rime. Both go as the sun warms the ground, so first light is the window." |
| Glance | `dawn · -N °C, N sites` |

The air fact's rounding is today an inline `Math.min(-1L, Math.round(temperature))` in `SnowFreshHotTopicStrategy`
(it keeps readings in [−0.5, 0) from printing "0 °C" beside "frost likely"). **Extract it** to one
package-private helper used by both strategies, and keep the existing ASCII hyphen-minus so the two facts read
identically.

### 3.4 Registration (every place a hot-topic type is named or counted)

- **Rarity** (`TopicRarity`): rank **18**, moving `MIST` to 19 and `SEA_MIST` to 20. Frost & mist is a strict
  subset of valley mist, so rarer. Update the comment at the MIST entry (it currently explains not renumbering)
  to say why this one renumbers: a subset must outrank its superset. The only reader is ordering
  (`PlanWindowProjector`).
- **Priority** 1 (as SNOW_MIST), above Valley mist's 2.
- `HotTopicGlanceDecorator.WEATHER_TYPES` (the detector's own `GlanceLine` reads it). Not `FIRST_FACT_TYPES`.
- `HotTopicSimulationService`: a template (Lake District, priority 1), and `HotTopicSimulationServiceTest`'s
  `hasSize(19)` (l.116, 120) and type list (l.305) become 20.
- `HotTopicInputsEquivalenceTest`: add the new strategy to its strategy list, so its one- and two-argument paths
  are checked against each other.
- **Frontend `utils/topicPalette.js`:**
  - `SWATCH_COLOR.FROST_MIST`: a **new hue** that the map can tell apart. The map's rings are colour only, and
    this topic sits beside MIST and the snow family. The candidate `#dbeafe` was rejected (ΔE76 5.0 from
    SNOW_FRESH). Start from an icy cyan such as `#22d3ee` and add a ΔE / colour-vision test modelled on the
    SEA_MIST one (`topicPalette.test.js` ~l.148-159) against every other swatch.
  - `TOPIC_META.FROST_MIST`: glyph `🧊`, name "Frost and mist". Not 🌫️, which is MIST's.
  - The "nineteen" comment at `TOPIC_META` and `topicPalette.test.js`'s `NINETEEN` literal and
    `toHaveLength(19)` (l.12-19) become twenty.
- **Frontend `utils/windowFirstTopics.js`:** `REGION_SCOPED_TOPIC_TYPES` and its test's literal mirror (the
  comment's "nineteen" becomes twenty). There is no backend mirror of that set.
- **Frontend `utils/windowFirstCards.js` `badgeChannel`:** `t.includes('FROST')` already maps any frost type to
  the `snow` channel. That is right for this topic — the snow channel is the white-ground, winter channel, and
  hoar frost whitens the ground. Keep it, and add `FROST_MIST → 'snow'` to the `badgeChannel` `it.each` so the
  choice is pinned, not accidental.
- **Ask:** the snapshot and `roundRobinLimit` take types generically (`AskSnapshotBuilder`). No change.
  `ReadyQuestion.Events.SNOW` stays snow-only: frost is not snow.
- `TopicDailyLogJob`: no change (MIST_CALC already logs the band; frost is a derived claim).
- **Sweep:** grep backend `src/main` and `src/test` and frontend `src` for `"SEA_MIST"`, `SEA_MIST:`, `nineteen`,
  `NINETEEN`, `(19)` and `hasSize(19)`. Each hit either gets `FROST_MIST` or a sentence in the PR saying why not.

### 3.5 Tests

- `FrostMistHotTopicStrategy`:
  - fires on a LIKELY band at −0.1 °C;
  - does not fire at 0.0 °C, at null temperature, or on POSSIBLE / FOGBOUND / NONE;
  - SUNSET rows never fire;
  - per day, with exact `locationNames` and only the ringed places' regions;
  - a multi-day spell gives one topic per day;
  - facts use the coldest place, the site count and the [−0.5, 0) rounding guard;
  - a snowy place still fires.
- `MistHotTopicStrategy`:
  - a SUNRISE frost-and-mist place drops out of Valley mist;
  - a mild misty place stays;
  - SUNSET is unaffected;
  - a day whose only misty places are frosty emits no sunrise Valley mist topic.
- The extracted rounding helper: boundaries −0.5, −0.4 and −1.6.
- Rarity, glance, enricher event stamping, simulation template, palette (distinctness included), badge channel
  and the region-scoped set mirrors.

## 4. Phase

One PR: the strategy, the Valley mist filter, the shared helper, the registrations, the frontend palette and
sets, tests, docs (`reference/conditions.md`, `hot-topics-implementation-inventory.md`, the TopicRarity comment)
and a changelog entry. Backend and frontend gates per CLAUDE.md, including the E2E smoke. It touches the UI (a new
ring colour, glyph and badge), so the UI review cadence applies: one adversarial reviewer on the diff.

## 5. Deliberately unchanged

- Valley mist's rule, copy and priority (it only loses the place-days Frost & mist now owns).
- SNOW_MIST and its frost variant.
- Triage, prompts and ratings.

## 6. Decisions taken by the planner (the owner may overrule any)

- **D1 — air frost only** (< 0 °C in the first hour at or after sunrise), matching the snow variant. It
  under-claims ground frost and never over-claims.
- **D2 — Frost & mist replaces Valley mist** for the same sunrise place-day, rather than stacking (§3.2).
- **D3 — no snow exclusion**: a snowy frost-and-mist place carries both snow and frost topics (§3.1).
- **D4 — label "Frost & mist"**, the owner's own name for it; glyph 🧊; the snow (winter) badge channel.

## 7. Out of scope

- A ground-frost (0 to +2 °C) variant.
- Rime on the tops without valley mist (freezing fog on the fells).
- Frost without mist (a clear frosty dawn with no mist band).
