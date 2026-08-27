# Handover: split fcstBlocked by whether the thin-strip override could have fired

**Status: SHIPPED as #525 (v2.18.8). Kept as the record of the task's scope and reasoning.**

Self-contained implementation plan. Backend-only, read-side only: no migration, no new sampling,
no scoring change. Third of the recut family (after #512 `&midCanvas` and #522 `fcst*`) — same
files, same conventions, same guardrails.

## Why (all the context you need)

The 2026-08-17 report pull sized `farClearer&fcstBlocked(>60)` at **6,281 of 29,016 evaluations
(21.7%)**: the forecast put the solar gate deep in the blocked band (mean 92%) while the observed
226 km corridor beyond was clear (mean far drop +60pp). Before the blocked-rule's over-pessimism
can be addressed, that population must be split by whether the prompt's own mitigation — the THIN
STRIP override, which lifts the blocked ceiling — had its preconditions in the forecast data:

- **Preconditions held** → the override plausibly softened the rating; not (primarily) a defect.
- **Preconditions missed** → the hard ceiling fired unmitigated over an open corridor. This count
  is the true over-pessimism number, and the mean `farError` +35.7 in the parent bucket predicts
  it is large: the forecast's far reading overpredicts corridor cloud by ~36pp exactly where the
  real corridor is clear, so the forecast-visible drop is often under the strip threshold while
  the real drop is over it.

The override's firing condition is `PromptBuilder.isThinStrip` (~line 765): far reading non-null
AND near ≥ 50 (`SOLAR_LOW_CLOUD_SIGNIFICANT_PERCENT`) AND near − far ≥ 30
(`THIN_STRIP_DROP_POINTS`). Mirror **that method**, not the prompt's prose — the prose says
"beyond-horizon low cloud ≤30%" but the label logic never checks the far level, only the drop
(a known text/code discrepancy; observe it, do not fix it here).

## Changes

`backend/src/main/java/com/gregochr/goldenhour/service/CloudVerificationService.java`,
method `corridorBuckets(...)`.

1. Constant `PROMPT_STRIP_DROP_PP = 30` beside the existing `PROMPT_*` band constants, javadoc
   noting it mirrors `PromptBuilder.THIN_STRIP_DROP_POINTS` / `isThinStrip` **label logic**
   (drop-only), not the prompt prose.
2. Two buckets appended after the existing ten (existing keys and order untouched), with the
   fcstBlocked predicate extracted to a shared helper so parent and subs cannot drift:
   - `farClearer&fcstBlocked&stripSeen` — fcstBlocked AND non-null `forecastFarLow` AND
     `forecastGapLow − forecastFarLow ≥ PROMPT_STRIP_DROP_PP` (the `near ≥ 50` arm is implied by
     the bucket's `> 60`).
   - `farClearer&fcstBlocked&stripMissed` — fcstBlocked AND NOT the strip condition; a null
     `forecastFarLow` lands here by design (no far figure = the override could not fire).
   By construction `stripSeen + stripMissed == fcstBlocked` — assert it.

## Tests

Extend `report_bucketsCorridorByForecastBand` with a `corridorPair` overload taking
`forecastFarLow` (existing call sites keep the old hard-coded value): members and non-members per
bucket, drop pinned at 29/30/35 so `>=` cannot drift, a null-far `stripMissed` member, a
below-band fixture proving the fcstBlocked gate guards both subs, a cloudier wrong-parent
detector, the full hand-counted key/count vectors, and the sum invariant asserted from the
report. Recompute by hand where the default far value puts the pre-existing blocked fixtures.

## Conventions

Exit-code build ladder (compile → targeted tests → checkstyle → CI-equivalent verify), CHANGELOG
under `[Unreleased]`, conventional commit, NEVER push, never tags. Out of scope: `PromptBuilder`
(including its text/label discrepancy), prompt regression tests, sampling/self-heal, the existing
ten bucket keys.
