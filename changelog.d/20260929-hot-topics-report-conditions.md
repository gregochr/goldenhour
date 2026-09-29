### Changed — hot topics and Coming up report conditions, never a rating's retraction

The owner drew a line between two questions this product answers separately: "what is
happening" (hot topics, the "Coming up" panel) and "where is worth going" (stars, verdicts,
picks — `cached_evaluation`, `forecast_evaluation`, `GET /api/briefing`,
`GET /api/briefing/evaluate/scores`, the map's forecast rows). A nightly Gate 4 stability skip
or a weather-triage stand-down is a decision against the second question, so it must retract a
rating — but it has nothing to say about the first. Knowing there is snow, Saharan dust or a
likely valley inversion is interesting on its own terms, independent of whether the pipeline
currently judges anywhere worth the drive to photograph it.

This reverses part of #940 (commit c6e14cc8, landed the previous day), which made
`SurvivorSignalReader` — the read path for all six survivor-signal hot-topic strategies
(bluebell, inversion, dust, fresh snow, snow on the tops, storm surge) and the "Coming up"
panel's dust/inversion standing conditions — drop an INVERSION or BLUEBELL `forecast_score`
component whose own `evaluated_at` predated its slot's most recent nightly stability skip, on
the reasoning that "a component is evidence exactly like a rating". The owner's decision is that
this was the wrong analogy for a panel that reports conditions rather than verdicts: a slot the
pipeline has since declined to re-score is not a reason to stop telling a reader there was strong
inversion potential, or dust in the air, on that morning.

`SurvivorSignalReader.read` now applies **no retraction of any kind** — it returns every
`forecast_score` component and every `survivor_atmosphere` reading in the requested window
exactly as stored. Its `withStabilityWindow`/`resolveStabilitySkips`/`isComponentRetracted`
machinery, its `ThreadLocal` stability-skip window, and its dependency on `EvaluationViewService`
are all removed; `HotTopicAggregator` no longer opens a shared window around the strategies pass
and no longer holds a `SurvivorSignalReader` reference at all, so a hot-topic aggregation issues
zero stability-skip or disposition queries (previously one, shared across all six strategies;
before that, briefly, one per strategy). `ComingUpConditionsBuilder`'s three reads through
`SurvivorSignalReader` (the trailing inversion history and both forward-peak reads) needed no
code change — they simply stopped being filtered.

`ForecastDtoMapper` is unaffected and deliberately different: it serves the same BLUEBELL
component as the forecast DTO's *rating*, the second question, so it keeps its stability-skip
retraction exactly as #940 shipped it. A consequence, stated so it is not later filed as an
inconsistency: after a stability skip, a slot's bluebell hot-topic chip can now show while the
same slot's DTO bluebell rating reads null — the chip says bluebells are (or were) out, the
rating says whether the pipeline currently judges that place worth the drive. Two different
questions, deliberately answered from the same underlying component by two different rules.

`EvaluationViewService`'s rating-side retraction (`isSlotRetracted`, `isRetractedByStabilitySkip`,
`stabilitySkipKey`) is unchanged; its javadoc is updated to say only `ForecastDtoMapper` reaches
those primitives directly now, and to record that `SurvivorSignalReader` deliberately does not.

Tests: `SurvivorSignalReaderTest`, `HotTopicAggregatorTest`, `BluebellHotTopicStrategyTest` and
`ComingUpConditionsBuilderTest` are updated to pin the new contract with literal expectations — a
component evaluated long before any later pipeline decision is still returned and still emits its
topic, through real (not mocked) readers/strategies where the original tests did the same.
`ForecastDtoMapperTest`'s two retraction tests, and every rating-side test in
`EvaluationViewServiceTest`, `BriefingRegionEvaluationRollupTest` and `ForecastControllerTest`,
pass unchanged.
