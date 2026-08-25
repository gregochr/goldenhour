# Handover: rating statistics on the cloud-verification buckets

Self-contained implementation plan. Backend-only, read-side only: no migration, no new sampling,
no scoring change. Sixth of the recut family (#512, #522, #525, #528, #529) — same files, same
conventions, same guardrails. The smallest of the family.

## Why (all the context you need)

The veto-demotion prompt change (merged #548/#549, deployed 2026-08-19) pre-registered its
post-deploy instrument as: *the rating distribution inside the `vetoFired` and `stripMissed`
populations — the 1–2★ share should fall toward the demoted cap of 3, and anything above 3★ in
the veto population means the prompt wording failed to hold.* The report cannot serve that:
`CloudVerificationBucket` carries cloud statistics only, even though every verified pair already
carries a `rating`. This cut adds rating statistics to every bucket so the comparison becomes two
windowed pulls (`?from=2026-02-01&to=2026-08-18` vs `?from=2026-08-19&to=...`).

**The one subtlety previous recuts did not have: most ratings are null.** A triaged slot persists
`rating = null` (stood down before any prompt was built), and the heavy-cloud buckets are
mostly triaged — `stripMissed`'s mean forecast gap is 92%, far above the 80% triage cut, so most
of its members have no rating at all. Rating stats computed naively over such a bucket would be
dominated by a tiny rated minority while implying they describe the whole population. The design
below makes the denominator explicit rather than hiding it.

## Changes

### 1. `backend/src/main/java/com/gregochr/goldenhour/model/CloudVerificationBucket.java`

Add three components to the record (appended after the existing ones — additive JSON):

- `ratedCount` (int) — members with a non-null `rating`. **This, not `sampleCount`, is the
  denominator for every rating statistic**, and serving it beside them is what keeps a
  null-heavy bucket honest.
- `meanRating` (Double, nullable) — mean over rated members only; null when `ratedCount == 0`
  (never 0.0 — a zero would read as a rating).
- `ratingCounts` (int[5] or `List<Integer>` of size 5) — counts of ratings 1..5 over rated
  members. **Use `List<Integer>`, not `int[]`** — this repo has been bitten by array components
  in records (identity equals broke a cache-equality check; see the CLAUDE.md hot-topics carrier
  bullet). The list always has exactly 5 entries, index 0 = rating 1.

Update `CloudVerificationBucket.of(...)` to compute all three from the pair list
(`CloudVerificationPair.rating()` is the existing component). Javadoc on each component states
the null-rating rule and why `ratedCount` is the denominator.

### 2. `CloudVerificationService` / `CloudVerificationReport`

No structural change — the buckets compute their own stats in `of(...)`, so every existing
bucket (corridor, triage-cut, veto, cone, wind) gains the fields automatically. Verify the
top-level populations (`overall`, `vetoFired`, `vetoNotFired`, `vetoUncapped`, `vetoCapped`,
`byWindSunAngle` entries) are also built through `CloudVerificationBucket.of(...)` — if any are
constructed differently, route them through the same path rather than duplicating the
computation. Update `CloudVerificationReport`'s javadoc `@param` lines for the changed bucket
shape and add one sentence naming this as the demotion's pre-registered instrument.

## Tests

`CloudVerificationBucketTest` (create if absent) and the existing `CloudVerificationServiceTest`.
Read `docs/engineering/test-improvement-standards.md` first (project rule). House rules: member +
non-member per claim, hand-counted literals, and every edge pinned:

- A bucket mixing null and non-null ratings: `sampleCount` counts all, `ratedCount` only the
  rated, `meanRating` ignores nulls, `ratingCounts` sums to `ratedCount` (assert the sum
  invariant from the values).
- An all-null bucket: `ratedCount == 0`, `meanRating` **null** (pin against 0.0), `ratingCounts`
  all zeros.
- An empty bucket: same as all-null with `sampleCount == 0`.
- Each band lands in its own slot: fixtures rated exactly 1 and exactly 5 pin the index mapping
  (off-by-one here would silently swap the bands the instrument reads).
- The existing corridor/triage-cut test fixtures carry ratings via their helper constructors
  (`corridorPair` passes a hard-coded rating today) — extend the helpers with a rating parameter
  only if a fixture needs a specific value; otherwise recompute the expected stats from the
  hard-coded value by hand and assert them on one or two representative buckets, not every
  vector (the key/sampleCount vectors must stay untouched).

JaCoCo is 80% per class — the null branches need real assertions, which the fixtures above give.

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
record `@param`s, no HTML tag on a `@param`'s first line.

## Commit

`feat(verification): rating statistics on every bucket — the demotion's post-deploy instrument`
— conventional commit, CHANGELOG under `[Unreleased]`. **Include this plan file
(`docs/engineering/rating-stats-cut-plan.md`) in the commit** so the working tree is clean after
merge and the plan joins the recut paper trail. NEVER push; never create tags; never
`git checkout --` anything you did not write.

## Out of scope — do not touch

- `PromptBuilder`, `WeatherTriageEvaluator` — no prompt or triage changes.
- Prompt regression tests (`src/test/java/.../regression/`) — user-owned, never edit.
- Sampling, backfill, `deleteIncompleteVerifications` — this adds no observation and must not
  trigger re-verification.
- Every existing bucket key and both scalars — pulls are compared across dates by key string.
- The existing key-order and sampleCount vectors in the tests — extended assertions sit beside
  them, never replace them.
