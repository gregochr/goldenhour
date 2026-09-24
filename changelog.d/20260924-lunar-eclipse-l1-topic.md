### Added — lunar eclipse hot topic, almanac entry and Coming-up scoring (backend, L1)

Phase L1 of `docs/engineering/lunar-eclipse-plan.md`: `LunarEclipseHotTopicStrategy` and
`LunarEclipseAlmanacSource` mirror the solar `EclipseHotTopicStrategy`/`EclipseAlmanacSource`
structurally, built on L0's `LunarEclipseCatalog`/`LunarEclipseCalculator`. A lunar eclipse's depth
is the same everywhere it is visible at all — unlike the solar eclipse, the Plan-tab label is the
constant "Lunar eclipse" on every surface (the kind still reaches the Coming-up card's title) — so
the roster's representative location is chosen by the longest *visible* umbral span rather than by
magnitude. The pill's exposure cue ("No filter needed — bracket, the shadow is ~10 stops under the
lit edge") rides `note`, never `safetyNote`: nothing about photographing an eclipsed Moon is
hazardous. The topic withdraws once the umbral phase (`u4`) has passed.

`TopicRarity` gains `LUNAR_ECLIPSE` at rank 2 (SUPERMOON and everything below shift by one — a
lunar eclipse recurs far more often than a deep solar one but still well ahead of a supermoon).
`ComingUpScoringProperties.Rarity.lunarEclipseMeanGapDays = 900.0` is a documented long-run
estimate — deliberately **not** the six real gaps between the catalogue's seven seeded entries
(177/355/502/354/177/177 days, averaging ~290, an unusually dense run that would land under the
announce band rather than interrupt) — giving `log2(900) + 1 ≈ 10.8` bits, which clears the
interrupt band; the census year holds three penumbral eclipses (never seeded), so
`ComingUpAnnualBadgeCensusTest`'s pinned result is unchanged — confirmed by running it, not
assumed.

`ComingUpAssembler.enrichLunarEclipse` shares the `eclipse` family with the solar entry, derives
`superlative` ("sets in shadow") from the almanac source's own composed `shadow` fact rather than
re-deriving geometry, and writes a server-authored `scoreNote` ("The first visible from here since
<Month YYYY>.") from the almanac's catalogue-derived `since` fact — which now takes precedence over
`markScoreNotes`' generic rarity/magnitude phrasing (that method's skip guard was extended from
"has a `joinNote`" to "has a `joinNote` **or** a `scoreNote`"), falling through to the generic
phrasing only for the catalogue's very first entry, which has nothing earlier to name. The new
nullable `ComingUpEntry.aside` carries the exposure note as a generic dashed-top slot any later
type may reuse.

`HotTopicSimulationService` gains a `LUNAR_ECLIPSE` template using Dunstanburgh's own reduction of
the 2026-08-28 eclipse (verified against `LunarEclipseCatalog`'s class javadoc), carrying no
safety note — `HotTopicSimulationServiceTest` keeps pinning that only `ECLIPSE` warns. Both eclipse
strategies are now covered by `HotTopicStrategyRegistrationTest` (the solar one had never been
added, a pre-existing gap).

Frontend: none this phase (L3 wires the client registries and the clocked chip).
