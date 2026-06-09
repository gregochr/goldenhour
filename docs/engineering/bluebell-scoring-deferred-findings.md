# Deferred — Bluebell Scoring Findings

**Origin:** surfaced (code-confirmed) during the tide-path Hot Topics
investigation — see [hot-topics-tide-observer-refactor-design.md](hot-topics-tide-observer-refactor-design.md)
Step 0, "Bluebell read." Captured here as standalone follow-ons so the corrections
are not buried in a tide-refactor doc.

**Status:** Not in scope for the tide refactor. Two open items to reconcile
**before any bluebell hot-topic work** begins.

---

## (a) Backlog defect — `bluebell_score` scale inconsistency (0–100 vs 0–10)

The prompt asks Claude for a **0–100** score; every consumer treats it as **0–10**.

- **Producer says 0–100:** `PromptBuilder` instructs Claude to return
  "`bluebell_score (integer, 0-100)`" (`PromptBuilder.java:523`). The structured-output
  schema declares `bluebell_score` as a bare integer with no range
  (`PromptBuilder.java:590`), so nothing clamps it.
- **Consumers assume 0–10:**
  - `BluebellHotTopicStrategy` — hot-topic threshold `6`, expanded-detail threshold
    `5` (`:40, :43`); `deriveQualityLabel` buckets at `≥9` / `≥7` (`:222–230`).
  - Frontend renders "`{score}/10`" (`HotTopicStrip.jsx:367`) and colours at `≥9` /
    `≥7` (`bluebellScoreColour`).
  - `ClaudeEvaluationStrategy` parses the raw value with no rescale
    (`:240–241`).

**Effect:** if Claude obeys the prompt and returns a value in 0–100, every bluebell
location clears the `≥6` hot-topic threshold (and the `≥9` "Excellent" bucket), and
the UI shows nonsensical "`73/10`". If Claude infers 0–10 from context, it works by
luck. Either the prompt instruction or all consumers must be corrected to a single
agreed scale. **Recommend standardising on 0–10** (matches every consumer and the UI)
and fixing only `PromptBuilder.java:523`, plus adding a schema range to catch drift.

**Trigger:** bluebell-only, season-gated (`SeasonalWindow.BLUEBELL`, mid-April →
mid-May). Dormant out of season. **Fix before next bluebell season (~April 2027)** or
as part of bluebell hot-topic work, whichever comes first.

---

## (b) Design discrepancy — "dedicated bluebell prompt" vs folded-in implementation

The tide-refactor brief assumed bluebell scoring would arrive as a **dedicated,
separate scoring prompt** that populates `bluebell_score`. **The code does it
differently:** bluebell scoring is **folded into the standard colour-evaluation
prompt**, not a separate call.

- `PromptBuilder` appends a `BLUEBELL CONDITIONS:` block + the two extra output-field
  instructions to the *same* user message used for the normal Fiery-Sky / Golden-Hour
  evaluation, gated on bluebell season + a non-null `BluebellConditionScore`
  (`:505–523`).
- `buildOutputConfig` carries `bluebell_score` / `bluebell_summary` in the shared
  schema for *every* evaluation (`:590–592`).
- So a bluebell site in season gets **one** Claude call that scores sky **and**
  bluebell together — there is no separate bluebell prompt or model path.

**Why it matters for bluebell hot-topic work:** the prior assumption ("the intended
scoring path does not exist yet") is wrong on the existence point — a path exists, it
is just *shaped differently* than the design imagined. Before building bluebell hot
topics, **reconcile the intended design with the as-built reality**: decide whether
bluebell stays folded into the colour prompt (cheaper, but couples bluebell quality to
the sky call and its triage) or is split into a dedicated prompt/observer (the brief's
assumption, and the cleaner fit for a future per-element bluebell observer). This
decision should precede, and will shape, the deferred bluebell deterministic-per-element
observer.

**Trigger:** before bluebell hot-topic / observer work. Not season-gated — it is a
design reconciliation, not a runtime defect.

---

## Cross-references

- Tide design doc: [hot-topics-tide-observer-refactor-design.md](hot-topics-tide-observer-refactor-design.md)
- Existing investigation: [hot-topics-investigation.md](hot-topics-investigation.md)
