# Plan: rating caps stated only where they apply (Haiku)

**Status: APPROVED 2026-11-27 (owner: "go ahead"). One PR.** Follows
`star-score-coherence-plan.md` §9–§11.

## 1. The lesson this applies

Four Haiku 4.5 Sky Evals on 2026-11-27 (8 runs per fixture) showed that Haiku turns any star or score
cap that is present in every request (the system prompt, the JSON field list, or the output schema's
descriptions) into a general rule, even when the text names a narrow condition. Two examples:

- "MAXIMUM 2 when the upwind sample alone…" on the field list dropped copt-hill-15mar from 3★ to 2★
  and the clear sky to 1–2★.
- "When the rating given is 3 … reaches 40" lifted far-corridor to 3★ at 48/52 and dropped the clear
  sky to 1★.

Moving the floor into the per-location message, present only when its condition holds (#1966), fixed
both. A cap belongs in the location's own message, where the code already knows the condition.

## 2. The two changes

**A. The FOGBOUND cap comes off the schema.** #1967 put "MAXIMUM 2 when the VALLEY MIST CONDITIONS
block's band is FOGBOUND." on the `rating` schema description and "FOGBOUND caps rating at 2." on the
`mist_potential` one. Both are sent on every evaluation. Haiku on `main`:

| Fixture | Before #1967 | After |
|---|---|---|
| st-marys-7apr clear-sky cap | 3★ ×7, golden 62–72 | 1★ ×8, golden 35 |

The fogbound user message already carries the cap (`VALLEY_MIST_FOGBOUND_SITE_SENTENCE`, "MAXIMUM
rating 2", and `VALLEY_MIST_FOGBOUND_CLEARING`), only on a FOGBOUND site. Remove the two schema
clauses so both descriptions are byte-identical to before #1967.

- **Risk:** the fogbound case relied on the schema restatement in #1967's own measurement.
- **Guard:** `ValleyMistPromptRegressionTest` (fogbound ≤2) runs in CI; the owner's Haiku eval covers
  the sky fixtures. If fogbound regresses, strengthen the user-message sentence, never the schema.

**B. A blocked solar horizon is labelled in the location's message.** The system prompt says solar
horizon low cloud >60% means "light is BLOCKED … rating 1-2 … non-negotiable", but nothing on the
location's own horizon line says so. On `main`, far-corridor-blanket-canvas (70% near, far corridor
also cloudy) gets 3★ in 1–2 of 8 runs; Haiku reads the FAR CORRIDOR label's "3 is available", which
applies only at 50–60%.

- **Change:** append a label to the DIRECTIONAL CLOUD solar-horizon line when solar low cloud is
  >60% and `isThinStrip(dc)` is false. Draft wording: ` [BLOCKED — light cannot reach the canvas:
  rating 1-2, fiery_sky ≤20, golden_hour ≤30; this overrides any rate-4 label]`.
- **Thin strip:** excluded, because the THIN STRIP rule lifts the blocked ceilings by design.

## 3. Not in this PR

middling-hazy-mixed (35% low, transitional; band 2–3) goes 4★ in 2–5 of 8 runs on `main` with no
rule misread that we can see yet. It gets a separate look.

## 4. Gates

- Unit pins: in `PromptBuilderTest` and `PromptBuilderValleyMistTest`, the schema descriptions no
  longer carry FOGBOUND text, and the BLOCKED label appears at 61% and not at 60%, not on a thin
  strip, and not without directional data.
- Golden masters regenerated; only the expected hunks change.
- Local `clean verify`.
- CI, including the sky and ValleyMist prompt-regression runs (Sonnet).
- Codex 👍 on the head.
- Owner-run Haiku Sky Eval against today's `main` run. Bar: clear sky back to 3★, far-corridor no
  3★, nothing else worse.
