# Handover: the promptable cut — reproduce all three triage predicates

Self-contained implementation plan. Backend-only, read-side only: no migration, no new sampling,
no scoring change. Seventh of the recut family (#512, #522, #525, #528, #529, #617) — same
files, same conventions, same guardrails.

## Why (all the context you need)

The fifth recut's "promptable" populations — the figures the veto demotion and blanket rewording
were sized on — are filtered on `forecastGapLow <= 80` only. That reproduces just the first of
`WeatherTriageEvaluator`'s **three** stand-down rules; the other two (precipitation > 2.0 mm,
visibility < 5,000 m) are not reproduced, so a slot stood down for rain or fog under an 80%
horizon still counts as "promptable". A cross-vendor review (2026-08-27) caught this; the §9
caveat in `cloud-approach-veto-fix.md` records it. The affected headline figures:

- `vetoFired&underTriageCut` = **545**, separation **+3.4pp** (observed 36.3 fired vs 32.9 not)
- blanket-label precision: **285 of 532 (53.6%)** promptable blanket calls over an observed-open
  corridor
- `fcstBlocked&underTriageCut` = **326**; `stripMissed&underTriageCut` = **192**

The counts can only shrink (removing rain/fog rows strengthens the small-footprint claims); the
separation and the precision can move either way. This cut re-pulls all four under the full
predicate so the §9 caveat becomes a measured number.

⚠️ **Do NOT filter on non-null `rating` instead** — it looks equivalent and is not. The batch
pipeline writes its ratings to `cached_evaluation`/`forecast_score`, so prompted-and-scored
slots are *also* rating-null in `forecast_evaluation` (measured 2026-08-03: ratings landed there
one day in fourteen). A non-null-rating filter discards nearly the whole genuinely-promptable
population. Reproducing the triage predicates is the only faithful cut.

## Changes

### 1. `backend/src/main/java/com/gregochr/goldenhour/model/CloudVerificationPair.java`

Add two components (appended — additive), sourced from the same `forecast_evaluation` row the
pair is already built from (`ForecastEvaluationEntity.precipitation`,
`ForecastEvaluationEntity.visibility` — both exist; this is read-side, it touches no sampling
and therefore cannot trigger re-verification):

- `precipitationMm` (BigDecimal, nullable)
- `visibilityMetres` (Integer, nullable)

### 2. `backend/src/main/java/com/gregochr/goldenhour/service/CloudVerificationService.java`

New predicate `promptable(pair)` that mirrors `WeatherTriageEvaluator.evaluate` **exactly** —
read the evaluator at implementation time and copy its comparison operators and null semantics
rather than paraphrasing them (as of this writing: stand down when cloud > 80, when
`precipitationMm != null && > 2.0`, or when visibility < 5,000 — but verify the visibility null
handling against the evaluator's own guard, and note that the evaluator's cloud term falls back
to observer low cloud when directional data is absent, where the existing cut uses
`forecastGapLow`; keep the existing cut's choice and say so in the predicate's javadoc, since
the two differ only for rows with no directional sample). Keep
`forecastGapUnderTriageCut` untouched — the old cut's buckets are the comparison baseline.

New bucket family `byPromptable` beside `byTriageCut`, carrying promptable variants of exactly
the four §9 figures: `vetoFired&promptable` (with fired/not-fired observed means, the
separation), `fcstBlanket&promptable` plus its observed-open precision split,
`fcstBlocked&promptable`, `stripMissed&promptable`. Same construction path
(`CloudVerificationBucket.of(...)`, so the #617 rating statistics come along free).

### 3. Pre-registered reading (decide the rule before the data)

- The four counts are expected to shrink or hold; **any increase is a bug in the predicate**,
  not a finding.
- The shipped prompt changes were *demotions* (absolute veto → bounded penalty; blanket
  confirmation → corroboration). Both moved in the safe direction, so no re-measured number
  triggers an automatic rollback. What the re-pull decides: if blanket precision under the full
  predicate falls **below the 25% rule** that originally fired the rewording, or the veto
  separation **changes sign**, §9 gets a correction and the owner decides whether anything else
  moves. Otherwise §9's caveat is replaced with the measured figures and closed.

## Tests

House rules apply (`docs/engineering/test-improvement-standards.md`): member + non-member per
claim, hand-counted literals — and per the 2026-08-16 lesson, member+non-member does **not** pin
a multi-clause predicate. `promptable` has three clauses; for each, hold the other two in their
passing state and sit a fixture on the band edge:

- gap exactly 80 → promptable (rule is `> 80`); gap 81 → not
- precip exactly 2.0 → promptable (rule is `> 2.0`); 2.01 → not; **null → promptable** (the
  evaluator's null semantics, pinned explicitly)
- visibility exactly 5,000 → promptable (rule is `< 5000`); 4,999 → not; null per the
  evaluator's own guard, pinned to whatever it does
- one fixture failing *only* the precip clause and one failing *only* the visibility clause —
  these are the rows the old cut wrongly counted, and they are the point of the whole recut
- the existing `byTriageCut` vectors stay byte-identical (baseline for the comparison)

JaCoCo is 80% per class; the null branches above give it real assertions.

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

## Commit

`feat(verification): promptable cut — reproduce all three triage predicates` — conventional
commit, CHANGELOG under `[Unreleased]`, this plan file included in the commit. NEVER push; never
create tags; never `git checkout --` anything you did not write.

## Out of scope — do not touch

- `WeatherTriageEvaluator` itself — this cut *mirrors* it; changing it is the separate
  triage-corridor design question.
- `PromptBuilder`, prompt regression tests (user-owned), sampling, backfill,
  `deleteIncompleteVerifications`.
- Every existing bucket key and the `byTriageCut` family — they are the baseline the new family
  is compared against.
