### Changed — the cloud inversion hot topic now scores every eligible place, not only the ones Claude rated

Phase 1 of "record conditions for every place" (`changelog.d/20260930-record-conditions-every-place.md`)
made dust, snow and storm-surge readings survive weather triage and the Gate 4 stability policy, but
named cloud inversion as a deliberate exception: `InversionHotTopicStrategy` and the Coming up
"Valley inversions" condition both read `forecast_score`'s INVERSION component, which is written
only from a completed Claude evaluation, so a triaged-out or Gate-4-stood-down inversion-eligible
location still had no inversion topic at all.

This phase closes that gap. `InversionScoreCalculator` already ran a deterministic 0–10 likelihood
score for every candidate with elevation ≥ 200 m and `overlooksWater` — inside
`ForecastService.fetchWeatherAndTriage`, before either triage check runs — but that score lived only
on the in-memory `AtmosphericData` and was discarded for anything that did not go on to a completed
Claude evaluation. Migration `V158__add_survivor_inversion_score.sql` adds a nullable
`inversion_score DOUBLE PRECISION` column to `survivor_atmosphere`; `SurvivorAtmosphereWriter.write`
now sets it from `data.inversionScore()` at the same collection-time seam as every other reading on
that row, so it is populated (or left null for an ineligible location) whatever the triage verdict
or Gate 4 decision that follows.

`SurvivorSignals.Readings` gains `inversionScore` (kept in `Readings`, a derived input, not
`Scores`, which is Claude's judgement) and `SurvivorSignalReader` carries the new column through
unchanged. `InversionHotTopicStrategy` and `ComingUpConditionsBuilder.buildInversion`'s trailing
occurrence list and forward-peak cell now read `readings().inversionScore()` instead of
`scores().inversion()` — same STRONG threshold (score ≥ 9), same SUNRISE-only filter, same
freshness rule. Coming up's rarity term is untouched: it still reads the config fallback, and
`inversionRarityNeverUpgrades` still pins that — only the *occurrence population* moved off Claude's
echo onto the calculator's complete, unbiased one; whether to also move rarity onto that population
is a separate decision, left open.

**The band label is now derived, not stored.** The calculator produces no NONE/MODERATE/STRONG
string of its own (only Claude's echo used to carry one, in the `forecast_score` INVERSION row's
`summary` column). The fact line's band now comes from `PromptBuilder.InversionPotential.fromScore`
— the identical score-to-band mapping the prompt already applies to the same calculator score — so
the hot topic's "9/10 · strong" can never disagree with the threshold that gated it firing at all.
`InversionHotTopicStrategy.bandLabel` is now a pure function of the score rather than a fallback for
a possibly-absent stored classification.

**Two surfaces, two questions — and they may disagree, deliberately.** The map popup's inversion
badge (`ForecastDtoMapper` → `forecast_evaluation.inversion_score`/`inversion_potential`) is
unaffected by this change and stays on Claude's own echo: it answers "is this place worth going to",
where Claude has narrow, evidence-checked discretion to disagree with the calculator on the measured
reversal. The hot topic and Coming up answer "what is happening" and always follow the deterministic
calculator. A location can therefore show a strong-inversion chip while its own map badge reads a
different band that morning, or vice versa for a location Claude never evaluated — the intended
split the two-question rule (2026-09-29) already established for dust, snow and surge, now applied
to the one condition Phase 1 named as its exception.

`SurvivorSignals.Scores.inversion()`/`inversionBand()` (Claude's `forecast_score` echo) are left in
place — nothing in this change removes them, since they are still populated from `forecast_score`
and still exercised by test coverage that pins the composite's join behaviour — but after this
change no production code reads either accessor any more: `TopicDailyLogJob` still logs from
`forecast_score` directly for its own reasons (a comment on that class now names
`survivor_atmosphere.inversion_score` as the eventual unbiased source, a separate decision left
open), bypassing the `Scores` accessors entirely rather than reading them. Removing the two now-dead
accessors and their reader wiring in `SurvivorSignalReader` is a candidate future cleanup,
deliberately not done in this change.

**No backfill.** A `survivor_atmosphere` row written before this migration carries a null
`inversion_score`, and the inversion hot topic and Coming up condition stay silent for that slot
until the next pipeline cycle upserts it — every slot is upserted every cycle, so the gap is at most
one cycle old for any location that is still being evaluated.
