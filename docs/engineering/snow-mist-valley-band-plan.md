# Snow and mist reads the valley mist band

One phase, one PR. Planned 2026-11-03, revised after an adversarial review the same day.

## Why

`SnowFreshHotTopicStrategy` upgrades its topic to `SNOW_MIST` when any snowy row has event-hour
relative humidity above 90 % (`isMisty`, `BriefingVerdictEvaluator.HUMIDITY_MARGINAL`). That test
was written in June 2026 (`snow-trio-investigation.md` §3.3), when humidity was the only mist signal
the app had. `ValleyMistCalculator` arrived in October (`valley-mist-plan.md`) and was never wired
in. The humidity test is wrong in three ways:

1. **It misses real mist.** Valley mist M0 showed the Met Office 2 km model does not saturate a river
   corridor: the Brathay on 17 Oct 2026 read RH 81–87 % and 37 km visibility all night, with mist
   in the photograph. That is why the calculator scores the radiative set-up, not saturation.
2. **It fires on damp overcast or windy mornings** that the calculator rates NONE (its overcast and
   wind vetoes), and at any site, mist-prone or not.
3. **Below 0 °C, high RH is mostly ice saturation.** With RH taken over water, ice saturation is
   about 98 % at −2 °C, 95 % at −5 °C and 91 % at −10 °C, so 93 % at −8 °C says nothing about mist.

⚠️ The band does not fully cure (3): it reads the temperature–dew point gap, which is the same
measurement as RH (`ValleyMistCalculator` Javadoc). What it adds is the clear-sky, calm and cooling
terms and the `mist_prone` geography. A sub-zero LIKELY is therefore a clear, calm, near-saturated
night over snow at a mist site: hoar frost is near certain, and mist is likely but not proven. The
copy below says exactly that and no more (no "rime on the windward side": LIKELY implies near-calm
air, and rime needs droplets driven onto surfaces).

Two more faults ride along. When one row is misty the whole topic becomes SNOW_MIST but keeps
**every** snowy region and is dated to the earliest snowy day, so it claims mist in regions and on
days where none was found. And the band is scored for SUNSET as well, while SNOW_MIST is pinned to
the sunrise window (`HotTopicEventEnricher.EVENT_BY_TYPE`) and its copy says first light.

## The change

All logic in `backend/src/main/java/com/gregochr/goldenhour/service/SnowFreshHotTopicStrategy.java`.

1. **Mist means the valley mist band is `LIKELY` on a SUNRISE row.** Replace
   `isMisty(Integer humidity)` with `isMisty(SlotSignals s)`:
   `s.eventType() == TargetType.SUNRISE && MistHotTopicStrategy.isLikely(s)`. Reusing
   `MistHotTopicStrategy.isLikely` (public static, null-guarded, reads `effectiveMistPotential()`)
   keeps the two topics on one rule by construction. `FOGBOUND`, `POSSIBLE`, `NONE` and null (an
   unflagged site, a scored-null row, a pre-V185 row with no Claude echo) are not misty. No humidity
   fallback. Drop the `HUMIDITY_MARGINAL` reference (the constant stays; `BriefingVerdictEvaluator`
   uses it).
2. **Two topics when the rows split.** `snowy` is filtered as today. `misty` = snowy rows passing
   `isMisty`; `plain` = the other snowy rows.
   - If `misty` is non-empty, emit SNOW_MIST built only from `misty`: dated to its earliest day,
     `dayLabel` from its distinct days, `regions` from its rows, representative the deepest misty row.
   - If `plain` is non-empty, emit SNOW_FRESH built only from `plain`, the same way.
   - So snow everywhere with mist at one valley site gives both cards; snow with no mist gives only
     SNOW_FRESH (exactly today's output for that case); every snowy row misty gives only SNOW_MIST.
   Order the returned list SNOW_MIST first. Extract the regions/days helpers so both builders share
   them.
3. **Sub-zero variant: frost, with mist.** Same condition shape (`temperature != null &&
   temperature < 0` on the representative misty row, the −1 °C display clamp kept). Wording:
   - label `"Snow, mist & frost"` (shorter than the old "Snow mist & hoar frost"; update the example
     text in `frontend/src/index.css`'s `.wf-hc-tw` comment, no CSS change)
   - detail `"Freezing mist and frost over lying snow " + dayLabel`
   - fact `air`: `"<n> °C · frost likely"`
   - description, about this: "A clear, calm, freezing night over lying snow: hoar frost feathers
     every twig and blade, and mist pools on the valley floor. Where the mist thickens it freezes on
     contact as rime. It's densest before dawn and goes as the sun warms the ground, so first light
     is the window." The builder may polish the prose but must keep every claim in it and add none.
   - note: `"densest before dawn — frost on every branch"`
   Above-zero variant keeps `"Fresh snow with mist"`, its detail, `MIST_DESCRIPTION` and `MIST_NOTE`.
4. **Fact pill names the band, not humidity.** The `mist` fact becomes `"mist likely"` (same
   `HotTopicFact` flags as now). Humidity no longer appears on the topic.
5. **Javadoc and comments.** Rewrite the class, `isMisty`, `detect`, `buildMistTopic` and
   constructor Javadoc (the band is the signal, humidity dropped, pointer to this plan). Update the
   comments that say humidity feeds SNOW_MIST or that name hoar frost as the SNOW_MIST claim:
   `SlotSignals.java` ~196-197, `SlotAtmosphereWriter.java` ~178-179, `SlotAtmosphereEntity.java`
   ~43-44, 99, 103, and `SnowFreshHotTopicStrategyTest`'s class Javadoc (~25). The humidity column
   keeps being written; no migration.

Untouched: `TopicRarity`, `HotTopicEventEnricher`, `HotTopicGlanceDecorator`, Ask's
`ReadyRelevance`/`ReadyQuestion`, the frontend palette and topic list (all match on the type string
only), and the admin simulation template.

## Consequences the owner should know before merge

- **SNOW_MIST now always comes with a "Valley mist" (`MIST`) card** for the same sunrise, because
  both read the same rule. Suppressing one is a product question, out of scope here.
- **SNOW_MIST becomes rare.** It needs 2 cm of lying snow at a site the admin has ticked
  `mist_prone` (default false, V185) on a LIKELY morning. Snowy fell sites will show SNOW_FRESH and,
  where relevant, the inversion topic. That is the honest outcome; widening it means ticking more
  valley sites, an admin data job.

## Out of scope

- A snow variant over an inversion (white fells above a cloud sea) — belongs with inversion v2.
- Suppressing `MIST` when SNOW_MIST fires.
- Ticking more locations `mist_prone`.

## Tests (`SnowFreshHotTopicStrategyTest`)

Extend the `signal(...)` helper to build `Readings` with the constructor that carries `mistScored`
and `mistPotential` (`SlotSignals.java` ~333-340), plus event type and a `Scores.mistBand`. Cases:

- snow + SUNRISE LIKELY → one SNOW_MIST, priority 1, fact `mist likely`
- snow + POSSIBLE / NONE / FOGBOUND / null band → SNOW_FRESH only (parameterised)
- snow + RH 99 % + null band → SNOW_FRESH only (the humidity path is gone)
- snow + RH 70 % + LIKELY → SNOW_MIST (humidity is not consulted)
- `mistScored = false`, `Scores.mistBand = "LIKELY"` → SNOW_MIST (echo honoured)
- `mistScored = true`, band null, `Scores.mistBand = "LIKELY"` → SNOW_FRESH (the scored null wins)
- snow + SUNSET LIKELY → SNOW_FRESH only
- split: region A LIKELY, region B snow only → two topics, SNOW_MIST first with regions [A],
  SNOW_FRESH with regions [B]
- dates: day 0 snow only, day 1 snow + LIKELY → SNOW_MIST dated day 1, SNOW_FRESH dated day 0
- sub-zero LIKELY → label `Snow, mist & frost`, fact `-2 °C · frost likely`, description mentions
  hoar frost and rime and does not contain "windward"; keep the 0 °C and the −0.3 °C
  (clamps to −1) boundary tests, relabelled
- `isMisty` unit tests on the band and event; delete the three humidity boundary tests
- `HotTopicInputsEquivalenceTest`, `HotTopicSimulationServiceTest` pass unchanged

Mutation check: change `isMisty` to accept `POSSIBLE` and confirm a test fails; drop the SUNRISE
filter and confirm a test fails (back up with `cp`, never `git checkout --`).

## Docs and changelog

- `docs/engineering/reference/conditions.md`: a short **Snow and mist** paragraph after the valley
  mist one: SNOW_MIST reads `MistHotTopicStrategy.isLikely` on sunrise rows, no humidity, the
  two-topic split, the sub-zero frost wording, both consequences above, pointer to this plan.
- `changelog.d/20261103-snow-mist-valley-band.md`, `### Changed — Snow and mist uses the valley mist
  forecast`, stating that it now fires only at mist-prone sites.

## Gates

Backend build ladder through the full `clean verify` with the integration exclusion (jacoco per
class, spotbugs, checkstyle). Frontend: only a CSS comment changes; run `npm run lint && npm run
build`.
