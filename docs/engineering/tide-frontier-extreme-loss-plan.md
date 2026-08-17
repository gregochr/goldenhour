# Tide refresh: frontier extreme loss — implementation brief

**Status: fixes 1 + 2 SHIPPED** (commit `b4b068ab`, branch `fix/tide-frontier-extreme-loss`,
reviewed and CI-equivalent-verified 2026-08-16). **Fix 3 (§8) is the open scope**, plus the
carry-over item in §7. Sections 1–6 are kept as the diagnosis record and the fix-1/2 spec.

Diagnosed 2026-08-16 against production. Do not re-derive the diagnosis; the evidence is below
and it is complete.

---

## 1. The defect, in one paragraph

The weekly tide refresh's **tail fetch** starts at the *frontier* — `MAX(event_time)` of stored
rows (`TideExtremeRepository.findLatestEventTimeFrom`), which is by construction always a tide
extreme's exact instant. `TideService.resolveFetchWindow` uses that instant as both the WorldTides
`start` parameter and the **inclusive** lower bound of the pre-insert delete
(`deleteByLocationIdAndEventTimeBetween`). The frontier row is therefore always deleted, and the
javadoc's stated assumption is that WorldTides returns an extreme at exactly `start`, restoring it.
**Production has now shown that assumption false**: WorldTides did not return the extreme sitting
exactly on `start` (an extremum at the window's first instant has no left-hand sample to be
detected against, and/or recomputation can shift it a second earlier), so the frontier extreme was
permanently destroyed. Every weekly tail is exposed; the new-code seam record is 1-for-1 on
destroying its frontier.

## 2. Evidence (production, St. Mary's Lighthouse, location ~55.0717,-1.4494)

- `tide_extreme` (via `GET /api/tides`): `HIGH 2026-08-16T17:48:22` → `HIGH 2026-08-17T05:56:32`
  with **no LOW between** — physically impossible (12.1 h high-to-high). The missing low is
  ~23:44 UTC Aug 16. It is the only hole and the only fetch seam in the entire forward window
  (scanned 2026-08-10 → coverage end 2026-11-14T17:54:19).
- `fetched_at` stamps: everything through `17:48` is `2026-08-03 02:00`; everything from `05:56`
  is `2026-08-10 02:00`. The `17:48` row keeping its **old** stamp proves the Aug 10 delete began
  *after* it — i.e. at the ~23:44 low, which was the frontier. Deleted, never restored.
- Logs (`goldenhour.2026-08-03.0.log`, `goldenhour.2026-08-10.0.log`):
  - Aug 3: `Stored 54 tide extremes for St. Mary's Lighthouse (T+0 to T+13)` — the **old**
    14-day-window code; that is why coverage was only 6 days ahead on Aug 10 and the first
    new-code tail seam landed in the near-term window where the hole became user-visible
    (a 7-hour flat-at-high-water trace on the Plan tab's tide sparkline for Mon Aug 17).
  - Aug 10: `2026-08-16 to 2026-11-15 (tail — extending stored coverage)` … `347 … 12 credit(s)`
    — the first production run of the new windowed-merge code, and the run that ate the frontier.
- User-visible symptom (how this was found): the Plan tab's window tide sparkline drew a dead-flat
  line at HW level from 00:00 to ~07:00 for Mon Aug 17 — the cosine interpolating HIGH→HIGH across
  the hole.

**Not being repaired**: the Aug 16 23:44 hole itself. It ages out of the serve window after Monday,
its stats impact is negligible (the local day still holds a high and a low), and the ~90-day
re-seed refetches the whole forward window anyway. Holes created between re-seeds are the ongoing
cost this fix removes.

## 3. Fix 1 — buy the frontier back (resolveFetchWindow margin)

**File**: `backend/src/main/java/com/gregochr/goldenhour/service/TideService.java`

In the **tail branch only** of `resolveFetchWindow`, start the window slightly before the
frontier so the frontier extreme sits strictly *inside* the requested span and comes back in the
response regardless of WorldTides' boundary semantics:

```java
// inside the tail branch
LocalDateTime from = frontier.minusMinutes(TAIL_OVERLAP_MINUTES);
if (from.isBefore(startOfDay)) {
    from = startOfDay;          // preserves the "never reaches backwards" invariant
}
return new FetchWindow(from, windowEnd, "tail — extending stored coverage");
```

with a named constant (`private static final long TAIL_OVERLAP_MINUTES = 1;`) carrying a javadoc
that states the evidence: *on 2026-08-10 WorldTides did not return the extreme at exactly `start`,
and the inclusive delete had already removed it — see
`docs/engineering/tide-frontier-extreme-loss-plan.md`*.

Invariants that must hold (all are already pinned by `TideFetchWindowTest` and must stay green
or be updated knowingly):

- **`from` never before `startOfDay`** — the clamp above; `noBranchEverReachesBackwards` pins it.
  This protects the 12-month backfill from the delete.
- **The delete keeps using `window.from()`** — no separate delete bound. Deleting from
  `frontier − 1 min` removes nothing extra: the frontier is `MAX(event_time)`, so no other row can
  exist in that minute. The response's extremes all satisfy `dt ≥ start`, so delete-then-insert
  still cannot violate `uq_tide_extreme`.
- **The skip decision stays keyed on the frontier**, not the margined `from`:
  `if (!frontier.isBefore(windowEnd)) return null;` is unchanged (`alreadyAtHorizonSkipsTheCall`).
- **Seed and re-seed branches unchanged** — their `from` is `startOfDay`, a midnight, never an
  extreme; there is no frontier row to lose.
- **Credits**: the tail already spans 7 days + up to one inter-extreme gap and bills 2 credits
  under the per-7-days rule (see the comment in `coveredLocationFetchesOnlyTheTail`); +60 s cannot
  change the `ceil` in practice. No config or cost-model change.

**Javadoc**: rewrite the `resolveFetchWindow` paragraph that currently reads *"The tail
deliberately re-requests the frontier extreme … Inclusivity is therefore required for
correctness"*. That paragraph documents the assumption production just falsified. The replacement
should say: the tail starts `TAIL_OVERLAP_MINUTES` before the frontier because WorldTides does
**not** reliably return an extreme at exactly `start`; the frontier row is still deleted (the
bound is inclusive and now sits below it) and is restored from the response, which now genuinely
contains it.

**Existing tests that will need updating** (`TideFetchWindowTest`):

- `coveredLocationFetchesOnlyTheTail` — `window.from()` becomes `frontier.minusMinutes(1)`; the
  `lengthSeconds` band assertion still passes (60 s inside an 8-day ceiling).
- `aMissedWeekProducesALongerTail` — same `from` shift; the exact
  `lengthSeconds == 14 days` becomes `14 days + 60 s`.
- `tailBranchNarrowsTheDeleteToTheFetchedSpan` — the captured delete lower bound becomes
  `frontier.minusMinutes(1)`, and its inline comment ("the lower bound is the frontier") needs the
  same correction as the javadoc.

**New tests**:

1. *The margin exists to survive a boundary-exclusive API*: tail `from` is strictly before the
   frontier, so a response whose first extreme is the frontier's `dt` (now `> start`) re-inserts
   it. At minimum assert `window.from()` is before `frontier` and within the margin.
2. *Clamp*: a frontier within `TAIL_OVERLAP_MINUTES` after `startOfDay` yields
   `from == startOfDay` — the margin never reaches into yesterday.
3. Keep/extend `noBranchEverReachesBackwards` so it also covers the clamped-margin case.

## 4. Fix 2 — post-merge integrity check (make the next hole loud)

**File**: `TideService.fetchAndStoreTideExtremes`, immediately after the delete+`saveAll`.

Read back the merged span **including the seam with pre-existing rows** — from
`window.from().minusDays(1)` to `window.to()` — ordered by event time (reuse an existing ordered
between-query on `TideExtremeRepository`, or add one mirroring
`findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc` for a single location). Scan adjacent
pairs for **same-type adjacency** (`HIGH,HIGH` or `LOW,LOW`) — the physically impossible sequence
a lost extreme leaves behind — and log one WARN per anomaly:

```
Tide integrity: consecutive HIGH extremes at {} and {} for {} — an extreme is missing between
them (window {} to {}, {})
```

Design constraints:

- **Extract the scan as a package-private static pure function** (e.g.
  `static List<...> sameKindAdjacencies(List<TideExtremeEntity> ordered)`) and unit-test it
  directly: empty list, single row, clean alternating series, one `HIGH,HIGH` pair, a pair
  spanning the seam (older `fetched_at` then newer), multiple anomalies. Wire-level: one test that
  a merge producing an adjacency emits the WARN (Logback `ListAppender`, if an existing test in
  the repo needs a pattern to copy, search for `ListAppender` usage first; if none, asserting on
  the pure function is sufficient and the WARN wiring can be verified by a simple
  invocation-count/spy test).
- **Same-type adjacency only.** Do not add a time-gap heuristic (a long gap with alternating
  kinds is legal), and do not auto-repair — repair is a different feature with API-cost
  implications; this check exists so a hole can never again hide for months.
- One extra query per coastal location per weekly refresh — negligible; it stays inside the
  existing try/catch so an integrity-check failure can never fail the refresh itself.
- JaCoCo is 80% line coverage **per class**; the pure function plus its tests keeps `TideService`
  above water without gaming.

## 5. Verification ladder (gate on exit codes, never on grepped output)

```bash
cd backend && ./mvnw compile -q
cd backend && ./mvnw test -pl . -Dtest=TideFetchWindowTest,TideServiceTest -q
cd backend && ./mvnw checkstyle:check >/tmp/cs.log 2>&1; echo "exit: $?"
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress \
  -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"
```

The last command is the CI-equivalent (reaches `jacoco:check` + `spotbugs:check` without Docker).
Exit `0` is the gate. No frontend changes in this scope, so the frontend CI quartet is not needed.

## 6. Process

- Branch: `fix/tide-frontier-extreme-loss` (work in the worktree; do not touch `main`).
- Conventional commit, e.g.
  `fix(tide): tail fetch starts before the frontier so WorldTides returns the extreme the merge deleted`.
- Update `CHANGELOG.md` under `[Unreleased]`.
- **Never push**; the user pushes and PRs.
- The tide refresh job runs **Mondays 02:00 UTC** (dynamic scheduler). Each unfixed run loses one
  extreme at the far horizon (~97 days out); most such holes are healed by the ~90-day re-seed
  before they rotate into view, but mid-cycle ones are not — that near-miss is why this fix is
  worth landing this week, and also why there is no need for a same-night deploy.

## 7. Out of scope (recorded so nothing gets refiled as a bug)

- **Fix 3 — chart-side interior-gap defence.** `WindowTideRollupBuilder.seriesAround` (backend)
  and `TideRunRow.curvePath` (frontend) both cosine-interpolate straight across a same-kind gap,
  rendering a missing extreme as hours of confident slack water. The agreed direction: detect two
  consecutive same-kind points and synthesise the implied opposite extreme at their midpoint —
  *shape only*, the same licence `bracket()`'s bookend documents ("affects the trace's shape,
  never a stated number"). Deferred to its own session (Opus + adversarial review, UI cadence).
- **Repairing the Aug 16 23:44 hole** — ages out; negligible.
- **Auto-repair on integrity WARN** — deliberate non-goal for fix 2.
- **Any change to seed/re-seed windows, credits accounting, or the backfill.**

## 8. Fix 3 — chart-side interior-gap defence (this session's scope)

**Goal**: a missing tide extreme must never again render as hours of confident slack water. Two
drawing surfaces cosine-interpolate straight across a same-kind gap today; both get the same
defence: detect two consecutive same-kind extremes and synthesise the implied opposite extreme at
their midpoint. **Shape only** — the synthetic point may influence nothing but the drawn trace.
This is the licence `WindowTideRollupBuilder.bracket()`'s javadoc already documents for its
bookends ("affects the trace's shape, never a stated number"), extended to interior gaps.
Also in scope: the §7 carry-over (isolate `checkTideIntegrity` in its own try/catch).

### 8a. Backend — `WindowTideRollupBuilder`

In `seriesAround`, after sorting and **before** `bracket()` (bracket reads the first/last points,
so the fill must run first), insert for every consecutive same-kind pair a
`Point(!a.high(), midpointMinutes, counterpartHeight(points, a))` — reusing the existing
`counterpartHeight` rule (nearest opposite-kind height, mirror fallback), same as the bookends.
Trigger on **same-kind adjacency only**, exactly like `TideService.sameKindAdjacencies` — no
time-gap heuristic (a long gap between alternating kinds is legal).

Blast-radius analysis, to verify rather than re-derive (all reads of the series were traced at
diagnosis time):

- `shape()` / the curve, and `Shape.levelOf` for the window mark — the synthetic trough joins the
  normalisation span. That is the fix working as intended: the mark must sit on the drawn line.
- `directionAt` reads the kind of the next series point — inside a gap the synthetic point makes
  the answer *more* correct (production's hole reported RISING at 05:40 only because the next
  real extreme happened to be a HIGH).
- `heightAt` at the event minute feeds only `levelOf` — shape again.
- **Untouched by construction**: `rangeOn`, `meanRangeOn` (both read `pointsOn`, the day-only
  real rows), `nearestExtreme` (reads raw extremes), the state classification, and every worded
  fact. Assert this in review: no stated number may move.

Tests (`WindowTideRollupBuilderTest`): a fixture reproducing the production shape — previous-day
HIGH, then the day's first extreme also HIGH (the ~00:44 LOW absent), full alternation after —
must produce a curve that visibly descends between the two highs (e.g. the sampled value at the
gap midpoint sits materially below both endpoints, not within a few percent of them). A clean
alternating fixture must produce a byte-identical curve to before the change — the existing tests
passing **unedited** is the proof the fill is inert on healthy data, the same idiom the
`solarDayGeometry` extraction used. Cover the direction claim: an instant inside the gap reports
the direction the synthetic point implies.

### 8b. Frontend — `TideRunRow.curvePath`

Same defence, simpler data: `extrema` there is `{high, m}` with no heights (the curve maps
high/low to two fixed Y baselines). For every consecutive same-`high` pair insert
`{ high: !a.high, m: Math.round((a.m + b.m) / 2) }` before the bookend extension. The chart is
`aria-hidden` and decorative; the verdict string is the accessible answer and must not change.
Vitest per `docs/engineering/frontend-test-standards.md`; `TideRunRow.test.jsx` exists — existing
tests must pass unedited, plus a same-kind-gap fixture asserting the path dips between two highs
(and the mirror case for two lows).

### 8c. Process for this session

- Read `CLAUDE.md` §"UI Work — Review Cadence" and follow it: build → tests → **adversarial
  review of the diff before commit** → fix survivors → re-verify → commit. Review agents are
  read-only; paste this section into their prompts (they cannot see untracked context and should
  not hunt for it).
- Base the branch on wherever `b4b068ab` now lives: if the fix-1/2 commit is already on `main`,
  branch fresh from `main`; if not, stop and ask whether to stack on
  `fix/tide-frontier-extreme-loss`.
- Backend gate: the §5 ladder (exit codes, never grepped output). Frontend gate, all four:
  `npm run lint && npm test && npm audit --audit-level=high && npm run build`.
- `CHANGELOG.md` under `[Unreleased]`. Never push.
