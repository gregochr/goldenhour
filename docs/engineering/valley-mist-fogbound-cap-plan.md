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

## Fix (two placements, both conditional on FOGBOUND)

**A. User message, field-adjacent.** In `PromptBuilder.appendValleyMistConditions`, when
`data.mistPotential() == MistPotential.FOGBOUND`, append one line right after the `Score:`/`Band:` line
(before the night facts), from a new public constant `VALLEY_MIST_FOGBOUND_LINE`:

> FOGBOUND: visibility is under 300 m at the event and no later slot in the MIST/VISIBILITY TREND clears
> back above it — a white frame, not the thin-mist shot. MAXIMUM rating 2; the score above measures how
> certain the fog is, not how good the shot is; do not describe the fog as lifting or burning off.

Nothing changes for NONE / POSSIBLE / LIKELY, and `VALLEY_MIST_SITE_SENTENCE` stays byte-identical.

**B. Output schema, on the field.** Append to the `rating` description in `buildOutputConfig()`:
` MAXIMUM 2 when the VALLEY MIST CONDITIONS block's band is FOGBOUND.` Append to the `mist_potential`
description: ` FOGBOUND caps rating at 2.`

**Not changed: the system prompt.** The valley-mist design keeps the system prompt byte-identical (it is
the batch cache prefix every site shares), and the veto's other placement ("the prose field list") lives
there. The user-message line takes that role here, because it is read only by the sites it applies to.
If A+B do not hold on the next run, adding the clause to the system prompt's field list is the next step.

## Effect on the other cases

- **LIKELY (expects ≥ 4):** gets no new line; the schema clause names FOGBOUND only. Small priming risk,
  to watch on the run.
- **Unflagged (expects ≤ 3, no echo):** no VALLEY MIST block, so both clauses are inert.
- **Coastal / woodland / bluebell builders:** `CoastalPromptBuilder extends PromptBuilder` and inherits
  the schema. Its golden files change with the schema text. A site there with no VALLEY MIST block
  never meets the clause.

## Cost and cache

One schema text change: the shared structured-output schema changes once and the batch primer re-warms
it, as when `mist_potential` was added. The system prompt is unchanged. No extra Claude calls.

## Tests (Sonnet writes; local, no API key)

- `PromptBuilderValleyMistTest`: FOGBOUND message contains `VALLEY_MIST_FOGBOUND_LINE`, placed after the
  `Score:` line; LIKELY, POSSIBLE and NONE messages do not; a FOGBOUND block with a null score
  (`Band:` form) also carries it; a pin test for the constant's exact text, like the site sentence's.
- Schema test: `rating` description contains "MAXIMUM 2 when the VALLEY MIST CONDITIONS block's band is
  FOGBOUND"; the existing CLOUD APPROACH clause still present.
- Regenerate the `prompt-golden/*.txt` files that embed the schema, using the documented
  regeneration path, and check the diff is only the two description strings.
- No regression assertion changes. The approval-gated `Prompt regression — sky` job is the proof;
  the owner approves it.

## Docs

- `docs/engineering/valley-mist-plan.md` M2 "The prompt" bullet: an *As amended 2026-11-27* note.
- `docs/engineering/reference/conditions.md` valley-mist section, if it quotes the block or schema.
- `changelog.d/20261127-valley-mist-fogbound-cap.md`.
