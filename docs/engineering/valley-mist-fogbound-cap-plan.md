# Valley mist — bind the FOGBOUND cap (plan, 2026-11-27)

## Problem

`ValleyMistPromptRegressionTest.brathay_17Oct2026_sunrise_flaggedSite_fogbound_isAWhiteFrame` fails on
`main` on Sonnet 5.5: a FOGBOUND Brathay sunrise (event visibility 150 m, no later slot back above 300 m)
rates 4★ (prompt-regression run 45986396686, 2026-11-27; 5★ on PR #1966). The test expects ≤ 2. The
summary says the fog "will lift gently", which the fixture's trend contradicts.

## Why the model overrides the rule

1. **The cap is stated only in prose.** `PromptBuilder.VALLEY_MIST_SITE_SENTENCE` ends "Fog under 300 m
   with no clearing is a white frame: rate 2.", after "may rate 4–5" in the same sentence. The
   `cloud-approach-veto-fix.md` measurement (twice: the rating cap and the `golden_hour` penalty) is that a
   constraint in rules prose is advisory and one restated on the output field is binding.
2. **The rating is committed before the band.** The schema's property order is `rating, …,
   mist_potential`, so the model writes its rating before it names FOGBOUND. The `rating` description
   says nothing about mist.
3. **The block reads as good news.** It prints `Score: 9.0/10 (FOGBOUND)`, and the system prompt's
   MIST AND VISIBILITY GUIDANCE ("SUNRISE SPECIFIC: mist is often already present and burns off…",
   "ethereal") pulls toward a positive reading.

## Fix (revised after the plan review)

**0. Run the advisory test on the model the job names.** The CI step for `ValleyMistPromptRegressionTest`
passes no model, and the test reads `regression.model` / `REGRESSION_MODEL`, which defaults to `HAIKU`
(Haiku 4.5). So the 4★ in run 45986396686 was Haiku 4.5, not Sonnet 5.5, although the summary title
says `SONNET_55`. Add `-Dregression.model="$SKY_MODEL"` to that step in
`.github/workflows/prompt-regression.yml`. This changes the workflow only, not the test or its assertions.

**A. The FOGBOUND block gets its own sentences.** In `PromptBuilder.appendValleyMistConditions`, when
`data.mistPotential() == MistPotential.FOGBOUND`:
- Replace the `Clearing:` line with `VALLEY_MIST_FOGBOUND_CLEARING`: "Clearing: none in the window. Every
  later slot in the MIST/VISIBILITY TREND stays under 300 m, so a small rise inside the fog is not
  clearing; do not describe the fog as lifting or burning off."
- Replace `VALLEY_MIST_SITE_SENTENCE` with `VALLEY_MIST_FOGBOUND_SITE_SENTENCE`: "This location is a
  valley-floor / riverside site, and it is fogbound: fog under 300 m with no clearing is a white frame,
  not the thin-mist shot. MAXIMUM rating 2; the score above measures how sure the fog is, not how good
  the shot is."
  This sentence drops the "may rate 4–5" permission, which the review found was read after the cap.
- Keep `VALLEY_MIST_ECHO_INSTRUCTION` last, unchanged.

For NONE, POSSIBLE and LIKELY the block stays byte-identical, and `VALLEY_MIST_SITE_SENTENCE` keeps its pin.

**B. Output schema, on the field.** Append to the `rating` description in `buildOutputConfig()`:
` MAXIMUM 2 when the VALLEY MIST CONDITIONS block's band is FOGBOUND.` Append to the `mist_potential`
description: ` FOGBOUND caps rating at 2.`

**Not changed: the system prompt.** `PromptBuilderValleyMistTest.systemPrompt_isUntouched` pins it, and the
MIST AND VISIBILITY GUIDANCE stays as it is. The schema is part of the cached prefix, so B already costs
one cache re-warm for every site. A clause in the system prompt's field list therefore remains the cheap next
step if A+B do not hold.

## Effect on the other cases

- **LIKELY (expects ≥ 4):** its block is unchanged; only the schema clause names FOGBOUND. The LIKELY fixture
  already fights the system prompt's CLEAR SKY CAP (≤ 3), so a second cap on the field could pull it down to 3.
  Watch it on the run.
- **Unflagged (expects ≤ 3, no echo):** no VALLEY MIST block, so both clauses are inert.
- **Coastal / woodland / bluebell builders:** `CoastalPromptBuilder extends PromptBuilder` and inherits
  the schema. Its golden files change with the schema text. A site there with no VALLEY MIST block
  never meets the clause.

## Cost and cache

One schema text change: the shared structured-output schema changes once and the batch primer re-warms
it, as when `mist_potential` was added. The system prompt is unchanged. No extra Claude calls.

## Tests (Sonnet writes; local, no API key)

- `PromptBuilderValleyMistTest`:
  - A FOGBOUND message carries both FOGBOUND constants and neither the generic site sentence nor the
    generic `Clearing:` line.
  - The echo instruction stays the block's last line.
  - LIKELY, POSSIBLE and NONE messages carry neither FOGBOUND constant.
  - The null-score `Band:` form works for FOGBOUND too.
  - Pin tests for both new constants' exact text.
- Schema test: `rating` description contains "MAXIMUM 2 when the VALLEY MIST CONDITIONS block's band is
  FOGBOUND"; the existing CLOUD APPROACH clause still present.
- Regenerate the `prompt-golden/*.txt` files that embed the schema, with
  `./mvnw test -Dtest=PromptGoldenMasterTest -Dprompt.golden.regenerate=true`, and and check the diff is only the two description strings.
- No regression assertion changes. The approval-gated `Prompt regression — sky` job is the proof;
  the owner approves it.

## Docs

- `docs/engineering/valley-mist-plan.md` M2 "The prompt" bullet: an *As amended 2026-11-27* note.
- `docs/engineering/reference/conditions.md` valley-mist section, if it quotes the block or schema.
- `docs/engineering/reference/dev-and-testing.md` (or wherever the prompt-regression CI step is described):
  the ValleyMist step now honours `SKY_MODEL`.
- `changelog.d/20261127-valley-mist-fogbound-cap.md`.
