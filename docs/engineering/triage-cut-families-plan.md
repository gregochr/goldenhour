# Handover: underTriageCut variants for the veto and blocked families

Self-contained implementation plan. Backend-only, read-side only: no migration, no new sampling,
no scoring change. Fifth and final recut (#512, #522, #525, #528) — same file, same conventions,
same guardrails. **This is the last measurement before the supervised prompt-change session; do
not extend it beyond what is specified.**

## Why (all the context you need)

#528 established that `WeatherTriageEvaluator` stands a slot down above 80% solar-horizon low
cloud before any prompt is built, and gave the EXTENSIVE BLANKET family `&underTriageCut(<=80)`
variants so its precision could be read over slots the prompt could actually have seen. Two other
report families have the same contamination and were not treated:

- **The veto family — and this one gates the next shipped change.** `vetoFired` (3,658; mean
  forecast gap 84.67) selects on the persisted trigger columns, not on whether a prompt ran, so
  many members were triaged before the veto rule could fire. The anti-selection finding
  (`vetoSeparation` −7.7pp) is currently a statement about the *signals*; the demotion plan needs
  it to hold over the *promptable* population too.
- **The strip family.** `farClearer&fcstBlocked(>60)` (6,281; mean gap 92.44) — #525's 21.7%
  headline, already quoted in the CHANGELOG — is likewise mostly never-prompted slots, and
  `stripMissed` (3,366) inherits the mix.

The cut constant (`TRIAGE_SOLAR_LOW_CLOUD_MAX_PCT = 80`) and the predicate
(`forecastGapUnderTriageCut`) already exist in `CloudVerificationService` from #528 — reuse both;
do not redefine either. The caveats on `corridorBuckets`' javadoc (the cut is neither necessary
nor sufficient for "prompted"; biases run in opposite directions) apply verbatim and should be
referenced, not restated.

## Changes

`backend/src/main/java/com/gregochr/goldenhour/service/CloudVerificationService.java` and
`backend/src/main/java/com/gregochr/goldenhour/model/CloudVerificationReport.java`.

### 1. One new report component

Add a `List<CloudVerificationBucket> byTriageCut` component to `CloudVerificationReport`,
positioned after `byCorridor`. Additive JSON; update the record's javadoc `@param` list. Fix the
constructor call sites the compiler finds — do not reorder existing components.

### 2. Four buckets in the new list, in this order

Build them in a new private `triageCutBuckets(List<CloudVerificationPair> pairs)` beside
`corridorBuckets`:

- `vetoFired&underTriageCut(<=80)` — the pair's own veto predicate AND
  `forecastGapUnderTriageCut`. **Reuse whatever predicate the existing `vetoFired` bucket uses**
  (it lives on `CloudVerificationPair` or in the service's veto-bucket builder — find it and call
  it; re-deriving `[BUILDING] && upwind ≥60` inline is how the parent and variant drift).
- `vetoNotFired&underTriageCut(<=80)` — NOT the veto predicate, AND under the cut.
- `farClearer&fcstBlocked&underTriageCut(<=80)` — the existing shared `forecastWasBlocked`
  predicate over `clearer`, AND under the cut.
- `farClearer&fcstBlocked&stripMissed&underTriageCut(<=80)` — the above AND NOT
  `thinStripPreconditionsHeld`.

Javadoc on the method: the under-cut veto separation is **derived by the reader** as
`vetoFired&underTriageCut.meanObservedGapLow − vetoNotFired&underTriageCut.meanObservedGapLow` —
deliberately no new scalar, so the record grows by one list and nothing else. State that the four
buckets exist to re-read §9's veto and #525's blocked headlines over the promptable population
before either is cited in the prompt-change session.

No change to any existing bucket, key, scalar, or the corridor list.

## Tests

`CloudVerificationServiceTest`, read `docs/engineering/test-improvement-standards.md` first. The
corridor fixtures do not carry veto triggers, so a new test with its own fixtures is cleaner than
extending the existing ones. Requirements, per house rules:

- Veto-fired pairs above and below the cut; veto-not-fired pairs above and below; a fired pair at
  gap exactly 80 (member — pins `<=` against `<`).
- A pair satisfying only ONE veto trigger (e.g. building true, upwind 59) under the cut → in
  `vetoNotFired&underTriageCut`, pinning that the variant uses the same two-trigger predicate as
  the parent, not a re-derivation.
- Blocked/strip fixtures: a blocked clearer pair under the cut with and without the strip drop
  (the second in both blocked-family variants), one above the cut (in neither variant), one at
  exactly 80 (member).
- Assert the new list's full key order and hand-counted sampleCount vector, and assert
  every variant ≤ its parent bucket's count from the report's own values.
- Assert `report.byCorridor()` is untouched in shape (its existing key-order assertions already
  do this — just don't weaken them).

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

No Docker for the first three. Checkstyle traps: 120-char lines, javadoc on public members and
record `@param`s, no HTML tag on a `@param`'s first line. JaCoCo is 80% per class — the new
record component and method are covered by the new test.

## Commit

`feat(verification): underTriageCut variants for the veto and blocked families` — conventional
commit, CHANGELOG entry under `[Unreleased]`; the entry should note plainly that #525's 21.7%
headline mixed triaged and prompted slots and these buckets separate them. NEVER push; never
create tags. Do not revert or `git checkout --` anything you did not write.

## Out of scope — do not touch

- `PromptBuilder`, `WeatherTriageEvaluator` — measurement only; the triage-corridor design
  question is the supervised session's, not this task's.
- Prompt regression tests (`src/test/java/.../regression/`) — user-owned, never edit.
- Sampling, backfill, `deleteIncompleteVerifications` — no new observation, no re-verification.
- Every existing bucket key and both existing scalars — pulls are compared across dates by key.
