# Handover: measure the EXTENSIVE BLANKET label's precision

Self-contained implementation plan. Backend-only, read-side only: no migration, no new sampling,
no scoring change. Fourth of the recut family (#512 `&midCanvas`, #522 `fcst*`, #525 strip split)
— same file, same conventions, same guardrails.

## Why (all the context you need)

The strip split (#525, results in `docs/engineering/cloud-approach-veto-fix.md` §9 on the
`docs/era5-measured-results` branch) showed 3,366 evaluations (11.6% of all) where the forecast's
`[EXTENSIVE BLANKET — full penalty applies]` label confirmed a blanket over a corridor that was
really ~21% clear. But that measurement conditioned on *observed-clear* skies, so it counts the
label's failures without its successes. Before the blanket rule is changed
(`blanket-confirmation-plan.md`, gated on this cut), we need its **precision**: of ALL forecast
blanket calls, what fraction sat over a corridor that was really blanketed vs really open?

The label's firing logic is `PromptBuilder` ~line 489:
`near >= SOLAR_LOW_CLOUD_SIGNIFICANT_PERCENT && far >= SOLAR_LOW_CLOUD_SIGNIFICANT_PERCENT`
(both 50, both `>=`). Mirror that — note it is `>= 50`, NOT the `> 60` the `fcstBlocked` buckets
use; the two thresholds answer different questions and a fixture must pin the difference.

## Changes

`backend/src/main/java/com/gregochr/goldenhour/service/CloudVerificationService.java`,
method `corridorBuckets(...)`.

### 1. Three constants (beside the existing `PROMPT_*` constants)

```java
/** Forecast low cloud (%) at or above which BOTH arms of the EXTENSIVE BLANKET label fire. */
private static final int PROMPT_BLANKET_MIN_PCT = 50;

/** Observed far low cloud (%) at or below which the corridor really was open. */
private static final int OBS_CORRIDOR_OPEN_MAX_PCT = 30;

/** Observed far low cloud (%) at or above which the corridor really was blanketed. */
private static final int OBS_CORRIDOR_BLANKET_MIN_PCT = 50;
```

Javadoc: the first mirrors `PromptBuilder.SOLAR_LOW_CLOUD_SIGNIFICANT_PERCENT` / the blanket
label at ~:489 and must track it. The other two define the *observed* corridor states the label
is scored against: 30 is the prompt prose's own strip level ("beyond-horizon low cloud ≤30%"),
50 is the blanket's own arm. The 31–49 middle band is deliberately unbucketed — ambiguous skies
must not inflate either precision figure.

### 2. Three buckets appended after the existing twelve (existing keys and order untouched)

Parent is `withFar` (ALL pairs carrying both readings), NOT `clearer`/`cloudier` — the whole
point is to see the label's full firing population regardless of observed divergence class.

- `fcstBlanket` — `p.forecastGapLow() != null && p.forecastGapLow() >= PROMPT_BLANKET_MIN_PCT
  && p.forecastFarLow() != null && p.forecastFarLow() >= PROMPT_BLANKET_MIN_PCT`.
- `fcstBlanket&corridorOpen(obs<=30)` — fcstBlanket AND `p.observedFarLow() != null
  && p.observedFarLow() <= OBS_CORRIDOR_OPEN_MAX_PCT`. **The false-blanket count.**
- `fcstBlanket&corridorBlanketed(obs>=50)` — fcstBlanket AND
  `p.observedFarLow() >= OBS_CORRIDOR_BLANKET_MIN_PCT`. **The true-blanket count.**

Extract the fcstBlanket predicate to a private helper shared by all three (the #525 drift rule).
Invariant: `corridorOpen + corridorBlanketed <= fcstBlanket` (strict `<` whenever a middle-band
member exists) — assert it, with a middle-band fixture proving neither sub-bucket claims it.

Method javadoc gains a sentence: these buckets exist to read precision off the counts —
false-blanket rate = corridorOpen / fcstBlanket — which is the number
`blanket-confirmation-plan.md`'s pre-registered decision rule keys on (≥ ~25% ship the reworded
rule; well under ~10% narrow to language only; between → user's call).

`observedFarLow` and `forecastFarLow` are both on `CloudVerificationPair` already. No model,
entity, DTO, controller, or repository change.

## Tests

`CloudVerificationServiceTest` (read `docs/engineering/test-improvement-standards.md` first). The
`corridorPair(...)` overload taking `forecastFarLow` exists since #525; the observed far reading
is the existing `farLow` parameter. Fixtures must pin, per house rules:

- Forecast near exactly 50 / far 55, observed far 20 → `fcstBlanket` member AND `corridorOpen`
  member (pins the label's `>=` at the near arm).
- Forecast near 49 → non-member (one under the edge).
- Forecast near 90 / far exactly 50 → member (pins `>=` at the far arm); far 49 → non-member.
- Forecast near 55 (blanket member, `fcstBlocked` NON-member at >60) → pins that this family does
  not reuse the blocked band's threshold.
- Observed far exactly 30 → `corridorOpen` member; observed far 31 → middle band, in NEITHER
  sub-bucket (the invariant's strict case); observed far 49 → middle; observed far exactly 50 →
  `corridorBlanketed` member.
- A pair with null `forecastFarLow` → not `fcstBlanket` (the label cannot fire without a far
  figure).
- Update the key-order assertion to the fifteen-key list and hand-count the full sampleCount
  vector; assert the invariant from the report's own values (see #525's `bucketCount` helper).
- Mind the pre-existing fixtures: every `corridorPair` call carries forecast and observed far
  values that may now qualify for the new buckets — recompute their memberships by hand and fold
  them into the expected counts rather than adjusting their values.

## Verification ladder (gate on exit codes, NEVER grepped output)

```bash
cd backend && ./mvnw compile -q >/tmp/c.log 2>&1; echo "exit: $?"
```

```bash
cd backend && ./mvnw test -Dtest="CloudVerification*" -q >/tmp/t.log 2>&1; echo "exit: $?"
```

```bash
cd backend && ./mvnw checkstyle:check >/tmp/cs.log 2>&1; echo "exit: $?"
```

```bash
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"
```

No Docker for the first three. Checkstyle traps: 120-char lines, javadoc on public members, no
HTML tag on a `@param`'s first line.

## Commit

`feat(verification): measure the EXTENSIVE BLANKET label's precision` — conventional commit,
CHANGELOG entry under `[Unreleased]`. NEVER push; never create tags. Do not revert or
`git checkout --` anything you did not write.

## Out of scope — do not touch

- `PromptBuilder` — the blanket rule itself changes later, under `blanket-confirmation-plan.md`,
  in a supervised session; nothing here.
- Prompt regression tests (`src/test/java/.../regression/`) — user-owned, never edit.
- Sampling, backfill, `deleteIncompleteVerifications` — this adds no observation and must not
  trigger re-verification.
- The existing twelve bucket keys — pulls are compared across dates by key string.
