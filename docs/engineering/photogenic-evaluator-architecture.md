# Photogenic Evaluator Architecture — Target State

**Status:** Design intent, not yet implemented. Captured ahead of v2.13.x consolidation work to prevent the architectural reasoning being lost or reconstructed inaccurately later.

**Scope:** Backend forecast evaluation. The way PhotoCast decides whether a location is photogenic at a given time.

---

## Why this exists

The current evaluation system has three structural problems that have been worked around rather than solved:

1. **The monolithic prompt dilutes Claude's signal.** `PromptBuilder.SYSTEM_PROMPT` is roughly 12,000 tokens; for any given location, only ~4,000 of those are relevant. The hard-ceiling rule for cloud-approach risk competes for attention with attractive-conditions guidance, the clear-sky cap competes with golden-hour scoring criteria, and Claude's output has been observed to soften ceiling rules because the surrounding context dilutes their force. Past fixes have patched specific sentences; the structural cause — too many competing signals in one prompt — remains.

2. **`CoastalPromptBuilder` was bolted on, not designed.** The class exists because the inland prompt was already saturated and adding tide logic to it would have made everything worse. The split was reactive, not principled. The next phenomenon (NLC, autumn colour, waterfall flow) faces the same pressure, leading to either more forked builders or more conditional blocks crammed into existing ones. Both paths compound the relevance problem.

3. **The 1–5★ scale assumes universal commensurability across location types.** A coastal location with a king tide and a strong sky genuinely scores higher than an inland fell with the same sky, because the rating implicitly compares dimensions that don't apply equally. Hadrian's Wall is being penalised for not being coastal. The Plan view's quality slider ranks locations against each other on a single axis when in reality each location should be ranked against its own ceiling.

Underneath these symptoms is a single insight: **everything in the evaluation pipeline — `PromptBuilder`, `CoastalPromptBuilder`, `BluebellHotTopicStrategy`, `WeatherTriageService`, `BriefingBestBetAdvisor` — is answering the same question.** *Will this location be photogenic at this time?* They differ in cost, granularity, and what signal they weight, but not in purpose. Today they don't share an abstraction, and the seams leak (triage and Claude scores have disagreed; the bluebell strategy and the prompt builder have parallel scoring; gating, scoring, and hot topics overlap inconsistently).

The target architecture replaces the implicit shared purpose with an explicit one.

---

## Core domain insight

Photographically, a location is photogenic when it has a strong sky AND a strong foreground appropriate to the location's character. This decomposes into two primitives:

**Sky** — universal where it applies. Horizon clarity, cloud canvas, light quality, atmospheric stability, aerosol load, golden hour conditions. Every location with a visible sky is evaluated on this dimension.

**Foreground** — location-dependent. For coastal tidal locations, tide alignment (and surge, and spring/king classification). For woodland bluebell sites in season, bluebell conditions (canopy diffusion, stillness, peak flowering). For dark-sky locations during aurora alerts, viewline clarity. For most inland landscapes (Hadrian's Wall, Lake District fells), the foreground is static — it doesn't change night-to-night, so it drops out of the scoring entirely.

A coastal location needs both sky and tide. An inland fell needs only sky. A woodland bluebell site doesn't really need sky at all — the canopy occludes it, and the bluebell-specific conditions become the entire evaluation.

Ratings are then honest within each location's own context: a 5★ inland fell and a 5★ coastal location both mean "best possible version of this kind of location," not parity across types. The slider stops penalising static-foreground locations for not being something they were never going to be.

---

## The visitor pattern

Location types are facts about the location. **Bluebell**, **Coastal**, **Landscape**, **Waterfall**, **DarkSky** — these describe what the location is. A single location can have several types simultaneously (Roseberry Topping is both Landscape and Bluebell — a hill with bluebells, not a woodland with bluebells).

Evaluators are visitors that know which location types they care about. The location does not declare its evaluators. Instead, each evaluator self-selects based on what types are present and what conditions apply (season, alert state, etc.).

The interface roughly:

```java
public interface LocationEvaluator {

    /**
     * Whether this evaluator applies to the given location in this evaluation context.
     * Encapsulates the evaluator's own gating logic — type checks, seasonal windows,
     * external state (e.g. AURORA only when an alert is active).
     */
    boolean appliesTo(Location location, EvaluationContext context);

    /**
     * Produces this evaluator's contribution to the location's rating.
     * Each evaluator owns its own data needs, its own prompt (if it calls Claude),
     * its own scoring logic. No evaluator depends on another evaluator's output.
     */
    EvaluatorContribution evaluate(Location location, EvaluationContext context);

    /**
     * REQUIRED contributions combine via weakest-link.
     * BONUS contributions can nuance within a star band but cannot push to a higher one.
     */
    EvaluatorRole role();
}

public record EvaluatorContribution(
    String evaluatorName,
    double score,         // normalised 0..1
    String reasoning,
    Set<String> tags      // e.g. "king-tide", "bluebell-peak", "inversion-likely"
) {}

public enum EvaluatorRole {
    REQUIRED,  // location is only as good as its weakest required dimension
    BONUS      // adds tags and nuances within a band, never breaks out
}
```

The pipeline becomes uniform:

1. Triage / cheap gates run first — these are themselves cheap evaluators that can short-circuit the pipeline before any expensive call.
2. For each location, every registered evaluator's `appliesTo` is consulted.
3. Applicable evaluators run, each producing a contribution.
4. A `RatingCombiner` aggregates: weakest-link across REQUIRED contributions for the headline rating; BONUS contributions add tags and small within-band adjustments.
5. The final rating, reasoning, and tag set are persisted.

---

## Worked examples

| Location | Types | Applicable evaluators | Combination |
|---|---|---|---|
| Hadrian's Wall | Landscape | Sky (REQUIRED) | Rating = sky |
| Saltwick Bay | Landscape, Coastal | Sky (REQUIRED), Tide (REQUIRED) | Rating = min(sky, tide) |
| Roseberry Topping | Landscape, Bluebell (open-fell exposure) | Sky (REQUIRED), BluebellBonus (BONUS) — only in season | Rating = sky, with bluebell tag and within-band nudge in season |
| Hardcastle Crags (woodland bluebell) | Bluebell (woodland exposure) | BluebellConditions (REQUIRED) — only in season | Rating = bluebell conditions; out of season the location is dormant |
| Kielder dark-sky site | Landscape, DarkSky | Sky (REQUIRED), Aurora (REQUIRED, conditional on Kp ≥ threshold and moon phase) | Rating = min(sky, aurora) when aurora applies, else just sky |

The model handles compositions cleanly because each evaluator is independent and self-gating. Adding NLC means one new `NlcEvaluator` class that applies to DarkSky locations during the May–August window when Sun depression is in the right band. No pipeline surgery, no new conditional blocks in any prompt, no risk of breaking existing scoring.

---

## What this does to existing classes

**`PromptBuilder` and `CoastalPromptBuilder` are retired.** They're replaced by per-evaluator prompts owned by their respective evaluators (`SkyEvaluator` owns the sky prompt, `TideEvaluator` owns the tide prompt). Each prompt is small and focused — relevance per token is high. The cleavage that produced `CoastalPromptBuilder` is no longer a fork in the prompt structure; it's just two evaluators independently registering interest in the same location.

**`BluebellHotTopicStrategy` becomes evaluator(s).** For woodland bluebell sites, a `BluebellConditionsEvaluator` becomes the location's primary required evaluator. For Roseberry-style hill-with-bluebells, a `BluebellBonusEvaluator` adds a tag and within-band nudge alongside the regular `SkyEvaluator`. The HotTopic concept survives — but as an aggregator over evaluator contributions ("bluebells are firing across five locations this week — surface a regional pill"), not as a parallel scoring system.

**`WeatherTriageService` becomes a `TriageEvaluator`** — same role it has now, just expressed in the same shape. It applies to every location, runs cheaply, and can short-circuit subsequent evaluators if it produces a STANDDOWN-class verdict. The disagreement between triage and Claude scores stops being structurally possible because they're now different evaluators contributing to the same combiner; the combiner's rules govern how they interact.

**`BriefingBestBetAdvisor` becomes the synthesis layer over evaluator outputs**, not a parallel scoring system. It reads evaluator contributions across locations and produces the best-bet recommendation. This is qualitatively different work from per-location scoring — synthesis genuinely benefits from a richer prompt (Opus, extended thinking) — but it's working on already-scored data, not redoing the scoring.

---

## Combiner rules

**Required contributions: weakest-link.** A location is only as good as its weakest required dimension. Saltwick on a strong-sky neap-tide night scores poorly because tide is weak. Hadrian's Wall on a strong-sky night scores high because sky is the only required dimension. This matches photographer intuition: if any one essential thing is wrong, the shoot is compromised.

**Bonus contributions: additive within band.** A bonus can nuance the rating within its current star band but cannot push it into a higher one. Roseberry with a 2★ sky and peaking bluebells is still a 2★ shoot — the flowers don't compensate for poor light. Roseberry with a 4★ sky and peaking bluebells gets a tag, possibly a small upward nudge within the 4★ band, but doesn't become 5★ on the strength of bonuses alone.

**Tags propagate independent of score.** A king-tide tag is set whenever `TideEvaluator` detects one, regardless of the final rating. The tags drive the Hot Topics aggregation layer and the user-facing tagged badges; they don't directly affect the headline rating.

---

## What this does to the rating semantics

The 1–5★ scale stops being absolute and starts being conditional. A 5★ inland fell and a 5★ coastal location both mean "best possible version of this kind of location" — not parity across types. The slider in the Plan view is then ranking each location against its own ceiling, and the user sees which ones are closest to their best tonight.

The user-facing rating definition becomes:

- **5★** — every applicable required evaluator scored its top band; bonuses (if any) tagged
- **4★** — every applicable required evaluator scored well; one possibly nuanced down by bonus rules
- **3★** — required evaluators acceptable but at least one holding the score back
- **2★** — at least one required evaluator weak enough to compromise the shoot
- **1★** — at least one required evaluator strongly negative; do not go

This is meaningfully different from the current model where dimensions implicitly compete on a universal scale. It also makes star ratings genuinely comparable across the user's saved or favourite locations regardless of type.

---

## Why this fixes the regression test flakiness

The current `PromptRegressionTest` is flaky because Claude is being asked, in a single 12k-token prompt, to apply hard ceilings to one signal while also weighing attractive conditions from an unrelated signal. The cloud-approach hard ceiling and the cloud canvas attractiveness rule live in the same prompt, competing for attention, with no architectural separation. Patching individual sentences — sharpening the ceiling language, weakening the canvas language — has produced a whack-a-mole pattern where each fix introduces a new failure mode.

Under the visitor model, each evaluator's prompt is small and focused. The cloud-approach hard ceiling lives inside `SkyEvaluator`'s prompt; cloud canvas reasoning also lives there but in a context where there's nothing else to compete with. The signal-to-noise ratio for any individual rule rises substantially. The Copt Hill cloud-approach false positive should resolve cleanly because the ceiling rule is no longer drowned out. The St Mary's clear-sky cap should resolve cleanly because the golden-hour scoring lives in the same focused context as the canvas requirement.

Each evaluator becomes independently testable. The Copt Hill scenario decomposes into a `SkyEvaluator` test (does it score this scenario correctly given just sky inputs?). The Saltwick scenario decomposes into a `SkyEvaluator` test plus a `TideEvaluator` test plus a combiner assertion. Tests stop being end-to-end against a monolithic prompt and become unit tests against focused evaluators, with integration tests over the combiner.

Once green, the prompt regression suite joins the e2e-real-api CI gate as the contract test for evaluator scoring quality.

---

## Cost considerations

The visitor model increases per-location call count where multiple evaluators apply. A coastal location goes from one Claude call (current monolithic prompt) to two (sky + tide). Roseberry-in-season goes from one to two (sky + bluebell bonus, though the bonus may be a deterministic calculation rather than a Claude call — that's an evaluator-level decision). On average, expect 1.4–1.6× the call count.

Mitigating factors:

- **Each call is smaller.** Tokens per call drop roughly to 30–40% of the current monolithic prompt, since each evaluator carries only its own context.
- **Caching becomes more effective.** Each evaluator's system prompt is fully stable across all locations of its type. `SkyEvaluator`'s prompt is byte-identical for all 200+ locations; cache hit rate within a batch should approach 1.0 after the first request. Today's caching problem (mixing two prefixes in one batch) disappears.
- **Required-evaluator short-circuiting.** If `SkyEvaluator` produces a 1★ score for a coastal location, weakest-link combination means tide cannot rescue it. The combiner can skip running `TideEvaluator` entirely. Smart short-circuiting trims the average call count.
- **Bonuses don't always need Claude.** A `BluebellBonusEvaluator` for an open-fell location can be a deterministic check (in season + bluebell exposure type → bonus tag, fixed within-band nudge). No API call required.

Net cost change is uncertain ahead of measurement. The £1/day target needs to remain reachable, and v2.13.0's investigation pass should model expected cost under the new architecture before code changes begin.

---

## Hot Topics under this model

Hot Topics stop being a parallel scoring system and become the **aggregation layer over evaluator outputs**. The pattern:

1. Per-location evaluation produces contributions with tags.
2. After all locations are evaluated for a given window, an aggregator scans the tag distribution.
3. Cross-cutting patterns get surfaced as Hot Topic pills: "King tides at 5 coastal locations tonight," "Bluebells peaking across northern woodlands this week," "NLC viewing window opens Friday."
4. The pills' detail views show which locations are firing for that tag, ordered by their headline rating.

This is structurally cleaner than the current implementation, where `BluebellHotTopicStrategy` runs its own scoring in parallel with the prompt path. Under the visitor model, the bluebell evaluator scores per-location bluebell conditions; the Hot Topic for bluebells reads those scores and tags. One source of truth for bluebell scoring, one aggregator surfacing the cross-cutting view.

---

## Implementation sequencing

This document describes the target. The implementation is a multi-pass programme, sequenced after the v2.12 consolidation work (which lays the foundation by extracting shared primitives, consolidating batch paths, and establishing the integration test pyramid).

**v2.13.0 — Investigation.** Map every existing scoring/triage/topic path against the visitor model. Document which evaluators emerge, what `EvaluationContext` needs to carry, what combiner rules produce correct ratings on the regression suite scenarios. Cost projection. No code changes.

**v2.13.1 — Contract.** `LocationEvaluator` interface, `EvaluatorContribution` record, `EvaluationContext` shape, `RatingCombiner`. No actual evaluators yet — this is the seam everything plugs into.

**v2.13.2 — `SkyEvaluator`.** First real evaluator. Owns its own focused prompt. Validated against the regression suite scenarios that decompose to sky-only.

**v2.13.3 — `TideEvaluator`.** Runs alongside `SkyEvaluator` for coastal locations. Combiner asserts weakest-link. Coastal regression scenarios validated.

**v2.13.4 — Bluebell evaluators.** `BluebellConditionsEvaluator` for woodland sites; `BluebellBonusEvaluator` for hill-with-bluebells. `BluebellHotTopicStrategy` becomes the aggregator over their outputs.

**v2.13.5 — Retire `PromptBuilder` and `CoastalPromptBuilder`.** Once the new architecture produces ratings of equal or better quality on the regression suite. The integration tests from v2.12.2 define the contract that must continue to hold.

**v2.13.6 — Promote prompt regression suite to e2e-real-api CI gate.** Once reliably green at 100% across all scenarios, joins the final-stage CI quality gate.

Each pass is independently committable and shippable. None requires a big-bang release.

---

## Out of scope for this document

- Specific prompt wording for any evaluator. Each evaluator owns its own prompt; the wording is determined during its implementation pass with the regression suite as ground truth.
- Aurora evaluator design. Aurora sits alongside this work but its current state (`ClaudeAuroraInterpreter`, `AuroraOrchestrator`) is architecturally separate enough that retrofit may be reasonable rather than full migration. v2.13.0's investigation should determine whether aurora joins the visitor model or stays independent.
- Frontend changes. The Plan view's quality slider behaviour changes meaningfully if ratings stop being universally commensurable — that warrants UX consideration, not just backend implementation. Out of scope here.
- Migration safety. Specific compatibility/rollback considerations are determined by the implementation passes themselves.

---

## Origin

This architecture emerged from a conversation in April 2026 reflecting on Val's comment on a Kent Beck post about token relevance, combined with field observation that the Plan view's slider was implicitly penalising inland locations for not being coastal. The reframing — *everything is a photogenic evaluator; locations have types; evaluators are visitors* — happened in a single sitting and is captured here ahead of implementation to ensure the structural reasoning isn't lost when work begins.
