# Handover: forecast-conditioned corridor buckets on the cloud-verification report

Self-contained implementation plan. Backend-only, read-side only: no migration, no new sampling,
no scoring change. Sibling of the `&midCanvas` re-cut that landed as PR #512 — same shape, same
files, same conventions.

## Why (all the context you need)

`docs/engineering/cloud-approach-veto-fix.md` §9 (measured 2026-08-16, 29,016 evaluations) found
that `farCloudier&midCanvas` — meant to size the "ideal scenario fires over a blanketed corridor"
false-optimism case — cannot do so: its mean member has `forecastGapLow` ≈ 87, i.e. the forecast
called the near gate *blocked*, so the ideal-scenario rule never fired for it. The bucket
conditions on **observed** structure; the rule fires on **forecast** state. This task adds the
forecast-conditioned cuts so both prompt rules' actual firing populations get sized:

- The prompt's IDEAL rule fires when forecast solar low cloud is **< 20%** (`PromptBuilder`,
  "IDEAL scenario: solar horizon low cloud <20% …").
- The prompt's BLOCKED hard ceiling fires when forecast solar low cloud is **> 60%**
  ("Solar horizon low cloud >60% = light is BLOCKED … rating 1-2").

## Changes

All in `backend/src/main/java/com/gregochr/goldenhour/service/CloudVerificationService.java`,
method `corridorBuckets(...)` (currently ~line 578; it builds seven
`CloudVerificationBucket.of(...)` entries from `withFar` / `clearer` / `cloudier` lists).

### 1. Two named constants (class level, beside `FAR_DIVERGENCE_PP`)

```java
/** Forecast solar-horizon low cloud (%) below which the prompt's IDEAL-scenario band applies. */
private static final int PROMPT_CLEAR_BAND_MAX_PCT = 20;

/** Forecast solar-horizon low cloud (%) above which the prompt's BLOCKED hard ceiling applies. */
private static final int PROMPT_BLOCKED_BAND_MIN_PCT = 60;
```

Javadoc on each must say it mirrors the prompt band in `PromptBuilder` and must track it if the
prompt ever changes. Checkstyle requires the javadoc; no magic numbers inline.

### 2. Three new buckets, appended after the existing seven (keep existing keys and order intact)

- `farCloudier&fcstClear(<20)` — over `cloudier`, filter
  `p.forecastGapLow() != null && p.forecastGapLow() < PROMPT_CLEAR_BAND_MAX_PCT`.
  **This is the ideal-scenario guard's target population**: forecast said the gate was clear, the
  observed far corridor was blanketed.
- `farClearer&fcstBlocked(>60)` — over `clearer`, filter
  `p.forecastGapLow() != null && p.forecastGapLow() > PROMPT_BLOCKED_BAND_MIN_PCT`.
  The blocked-rule's over-pessimism population: forecast tripped the hard ceiling while the
  observed corridor beyond the gate was clear.
- `farCloudier&fcstIdeal` — over `cloudier`, filter forecast gap `< PROMPT_CLEAR_BAND_MAX_PCT`
  **and** `p.forecastCanvasMid() != null && p.forecastCanvasMid() < 50`
  **and** `p.forecastCanvasHigh() != null && p.forecastCanvasHigh() >= 20`.
  Approximates the IDEAL rule's full preconditions (clear gate, mid < 50, high canvas present).
  Javadoc must state the approximation honestly: the prompt says "high cloud present on either
  horizon" but only the overhead forecast high is persisted, so overhead ≥ 20% stands in for it.
  Use inline `50` / `20`? No — add two more constants (`PROMPT_IDEAL_MID_MAX_PCT = 50`,
  `PROMPT_IDEAL_HIGH_MIN_PCT = 20`) with the same mirror-the-prompt javadoc.

Update the `corridorBuckets` method javadoc: one added paragraph saying the observed-structure
buckets answer "how often does the sky diverge" while the `fcst*` buckets answer "how often does
each prompt rule *fire* over a divergent sky" — the §9 lesson that the two are different
populations.

The relevant fields all exist on `CloudVerificationPair`
(`backend/src/main/java/com/gregochr/goldenhour/model/CloudVerificationPair.java`):
`forecastGapLow`, `forecastCanvasMid`, `forecastCanvasHigh` (record components, lines ~58–61).
No model, entity, DTO or controller change — additive JSON on a `List<CloudVerificationBucket>`;
the only consumer is curl.

## Tests

`backend/src/test/java/com/gregochr/goldenhour/service/CloudVerificationServiceTest.java`,
method `report_bucketsCorridor` (~line 314 after #512). Read
`docs/engineering/test-improvement-standards.md` first (project rule).

The fixture helper `corridorPair(nearLow, farLow, canvasMid, canvasHigh)` (~line 380) does not
currently parameterise the forecast gap — check what it sets `forecastGapLow` to, then extend it
with an explicit `forecastGapLow` parameter (updating the four existing call sites mechanically —
choose values that keep every existing assertion true, i.e. mirror whatever the helper hard-coded
before).

New fixture coverage must make each new bucket assert **both a member and a non-member** (the
#512 lesson, recorded in that test's own comments: a bucket asserted only at zero passes even
when wired to return nothing):

- A `cloudier` pair with forecast gap 10 → in `farCloudier&fcstClear(<20)`; a `cloudier` pair
  with forecast gap 85 → out.
- A `clearer` pair with forecast gap 85 → in `farClearer&fcstBlocked(>60)`; one at 10 → out.
- For `farCloudier&fcstIdeal`: the gap-10 pair above gets forecast canvas mid 30 / high 40 → in;
  assert a second gap-10 pair with high 0 → out (canvas precondition discriminates, not just the
  gap).
- Update the bucket-key order assertion to the new ten-key list and the sampleCount list to match.
  Count the expected memberships by hand and write them as literals — do not derive them in test
  code.

Boundary semantics: the filters are strict (`<` 20, `>` 60), matching the prompt's wording
"<20%" / ">60%". A pair at exactly 20 or 60 belongs to neither band — acceptable to leave
untested, but do not flip the operators to `<=`/`>=`.

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

No Docker needed for the first three; the fourth reaches jacoco:check (80% line coverage per
class — the new constants and filters live in an already-covered class, so this should be free)
and spotbugs:check. Checkstyle traps: 120-char lines; javadoc on anything public; no HTML tag on
the first line of a `@param`.

## Commit

`feat(verification): size each prompt rule's firing population over a divergent corridor` —
conventional commit, CHANGELOG entry under `[Unreleased]` (conflicts there are normal). The
working tree may already carry uncommitted §9 edits to `docs/engineering/cloud-approach-veto-fix.md`
and a memory-file change — commit the veto-doc edits with this change if present (they document
the finding this implements); NEVER revert them, NEVER `git checkout --` anything you did not
write. NEVER push; never create tags.

## Out of scope — do not touch

- `PromptBuilder` or any prompt text (the demotion is a separate, gated plan).
- Prompt regression tests (`src/test/java/.../regression/`) — user-owned, never edit.
- Sampling geometry, the backfill, or `deleteIncompleteVerifications` — this change adds no
  observation and must not trigger re-verification. If you find yourself editing the service's
  sampling or self-heal methods, stop: you are off-plan.
- The existing seven bucket keys — external readers compare across pulls by key string.
