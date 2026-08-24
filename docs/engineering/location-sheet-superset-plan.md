# Location sheet superset — implementation plan

**For an Opus/Sonnet build session. Written 2026-08-24 against `main` @ `1281e64a` (post-M5).**

---

## 0. The ask

Owner observation (2026-08-24, from two screenshots of the live v2 Plan tab):

Hovering a spot card inside the window popup shows a **peek** (`WindowSpotPeek`) carrying stars,
drive, leave-by, a **Fiery Sky bar (72)**, a **Golden Hour bar (76)** and a prose clause. Clicking
through opens the **location sheet** (`LocationFourDaySheet`, "THE NEXT 4 DAYS HERE") — the deeper
surface — and its expanded row shows *less*: only the prose summary and the leave-by line. Meanwhile
the **map marker popup** shows golden-hour and blue-hour clock times that no Plan surface shows at
all.

> "The pop-up when you've drilled down should display the **superset** of data for the solar event.
> Why would we leave out the golden hour / fiery sky bars? The Golden / Blue Hour times?"

**The principle this plan implements:** each step deeper into the drill-down shows a superset of
what the step above showed for that location + event. Two gaps close it:

1. **Phase 1 (frontend only):** the Fiery Sky / Golden Hour score bars join the location sheet's
   expanded row body.
2. **Phase 2 (backend + frontend):** per-location, per-event **golden/blue hour clock times** are
   served on the score payload and rendered in the same body.
3. **Phase 3 (optional, owner's call):** the peek gains the same light-times line for parity.

**Interpretation note (stated assumption):** "the pop-up when you've drilled down into a region" is
read as the location sheet — it is the surface in the screenshots that shows less than the hover
that opens it. The window popup itself (`WindowSheetDialog`) already carries the region-level
superset (verdict, prose slot, topics, tide row, ranked strip); per-location detail lives in the
peek and the sheet. If the owner meant something else, stop and ask before Phase 2.

---

## 1. Target anatomy of an expanded sheet row

Header (unchanged): date box · event word · the location's **own** event time · rating chip or
absence label · leave-by line · confidence mark.

Body, when expanded (`.wf-loc-body`), top to bottom:

1. **Score bars** — Fiery Sky + Golden Hour, visually identical to the peek's bars. *(Phase 1)*
2. **Light line** — golden and blue hour windows for *that location, that date, that event*, in
   chronological order for the event side. *(Phase 2)*
3. **Prose summary** — existing (`location-sheet-why` / the three absence sentences).

Absence of any element is **silence** (plan-matrix §3 rule 6: "degrade is silence, never
synthesis"). Nothing currently rendered is removed, on the sheet or the peek.

---

## 2. Verified data map (file:line anchors checked 2026-08-24)

| Fact | Anchor |
|---|---|
| `GET /api/briefing/evaluate/scores` already serves `fierySkyPotential`, `goldenHourPotential`, `summary` per location × date × event | `backend/.../model/LocationEvaluationView.java:35-52`; built by `EvaluationViewService.buildViews` (`:244`), precedence cached_evaluation → scored forecast → triage |
| The peek's scores come from the provider's name-keyed index | `WindowFirstBriefingContext.jsx:276-299` (reducer at 288–295), re-keyed by `buildBriefingScoreIndex` (`utils/briefingScoreIndex.js:41-53`), read by `resolveSpotPeek` (`utils/windowSpotPeek.js:155-175`, scores at 157–158) |
| The sheet's index **deliberately narrows to `{rating, summary}`** — this projection is the whole Phase 1 gap | `frontend/src/utils/locationSheet.js:166-182` (value built at ~179) |
| The sheet may only extend its own id-first index, never read the peek's name-keyed one | plan-matrix-plan.md §3 rule 11: "the provider's name-keyed `scoreIndex` is not read by any new surface — raw `scoreRows` are" |
| Rows reach the sheet via `buildLocationSheet` | `locationSheet.js:360-444` (rating :374, summary :402, away-gate :371-373) |
| Expanded body render site (always mounted, `hidden` attr) | `LocationFourDaySheet.jsx:354-374` |
| The bar UI exists in three unshared copies; the peek's is the match | `PeekScoreBar` + `FIERY_FILL`/`GOLDEN_FILL`, `WindowSpotPeek.jsx:33-60` (comment at 58–60 records why the map popup's constants were copied, not imported). `MarkerPopupContent.jsx:288-318` is the map twin. `ScoreBar.jsx` is **orphaned** (only importer is its own test) and a different visual language — do not reuse it |
| Golden/blue hour times exist today only on the fat per-row `GET /api/forecast/{id}` | computed at map time in `ForecastDtoMapper.java:342-364` from `SolarService.goldenBlueWindow` (`SolarService.java:194`, `SolarWindow` semantics documented :156-186); served as **UTC `LocalDateTime`** on `ForecastEvaluationDto:157-160`; formatted client-side by `formatEventTimeUk` (`MarkerPopupContent.jsx:453-456`) |
| No solar library exists in the frontend bundle | CLAUDE.md backend-heavy rule; Phase 2 is therefore a backend change |
| `/evaluate/scores` is ETag-revalidated | `HttpCachingConfig.java:73` — fine for Phase 2, because per-location light times are **location-derived, not home-derived** (contracts §5.1 seam untouched) |

---

## 3. Phase 1 — the score bars (frontend only)

### 3.1 Extract the bar component

Move `PeekScoreBar` and the two fill constants out of `WindowSpotPeek.jsx:33-60` into a small shared
module (suggest `frontend/src/components/PlanScoreBar.jsx`), and have `WindowSpotPeek` import it.

- **Purity proof:** `WindowSpotPeek`'s existing tests must pass **unedited** — the same standard the
  `solarDayGeometry` extraction set ("tests passing unedited proves the extraction was pure").
- Do **not** import from `MarkerPopupContent` (pulls a ~1,300-line module graph into the Plan chunk —
  the in-code comment already records this decision) and do **not** resurrect `ScoreBar.jsx`.
- Optional tidy, separate commit at the end: delete the orphaned `ScoreBar.jsx` + its test.

### 3.2 Widen the sheet's index

`locationSheet.js` `buildScoreIndex`: add `fierySkyPotential` and `goldenHourPotential` to the value
object, validated the same way `rating` is (integer, in range 0–100; else `null`). Update the
`@returns` javadoc — it currently documents `{rating, summary}`.

### 3.3 Carry through the row map

`buildLocationSheet` (`locationSheet.js:379-414`): put the two scores on the row beside `rating` and
`summary`. Keep the away-gate at :371-373 — an away day carries no scores by construction.

**P8's load-bearing rule (heat-field-plan.md decisions 4 and 6): the scores must come from the SAME
score row the rating and summary come from** — one id-first join, never a second lookup path. A
second path is exactly the split-source defect P8 fixed.

### 3.4 Render

In `.wf-loc-body` (`LocationFourDaySheet.jsx:354-374`), bars **above** the prose (matching the
peek's order: bars, then clause). Render the bar block only when at least one score is non-null —
never an empty track. Testids: `location-sheet-fiery`, `location-sheet-golden` (or similar).

- **Dimming (plan-matrix §4 A25):** ≤2★ rows dim their text children to `opacity: .8`; apply the
  existing mechanism to the new block and pin it in a test.
- **A24 check for the review:** the bars are two *different served numbers* (0–100 model outputs),
  not the star restated, so they don't violate the kicker rule — but the adversarial review should
  confirm the expanded best row doesn't now say quality four ways. If it reads noisy, the fix is
  copy/spacing, not removing the bars (they are the ask).

### 3.5 Consistency invariant

Contracts §3's corrected claim: one payload reduced two ways must render the same numbers —
production has already shown "Worth it · best 4★" over a grid of "Poor" from exactly this class of
bug. Add a test that feeds **one** `LocationEvaluationView` fixture row through both reductions
(`buildBriefingScoreIndex` → `resolveSpotPeek`, and `buildScoreIndex` → `buildLocationSheet`) and
asserts the peek and the sheet row carry identical fiery/golden values.

### 3.6 Tests

- `frontend/src/test/locationSheet.test.js` — extend the `buildScoreIndex` block (:160-208: bounds
  edges 0/100, null, out-of-range, non-integer) and the row block (:209-289).
- `frontend/src/test/LocationFourDaySheet.test.jsx` — the fixtures at :33-37 carry **no scores
  today**; extend them. Cases: bars render with the right values; absent scores → no bar block; away
  row → no bars; dimming applies.
- Apply the project's pinning lesson (memory: member+non-member does not pin the clauses): sit a
  fixture on each band edge, hold the other axes constant, and mutate the guard to prove the test
  catches it.

---

## 4. Phase 2 — golden/blue hour times (backend + frontend)

### 4.1 Backend

1. **Widen `LocationEvaluationView`** with four components: `goldenHourStart`, `goldenHourEnd`,
   `blueHourStart`, `blueHourEnd`, as **UTC `LocalDateTime`** — the exact representation
   `ForecastEvaluationDto` already uses, so the client reuses `formatEventTimeUk` and there is one
   UTC→UK conversion rule, not a third formatting path. Checkstyle wants `@param` javadoc per record
   component. Every positional construction site of the record (service + tests) needs updating —
   grep `new LocationEvaluationView(`.
2. **Compute in `EvaluationViewService.buildViews` (`:244`)** — it already iterates
   `LocationEntity × date × TargetType` and holds lat/lon. Mirror `ForecastDtoMapper.java:342-364`
   exactly: null-lat/lon guard, try/catch → nulls (polar edge case), `SolarService.goldenBlueWindow`
   as the single calculator. Read `SolarService.java:156-186` first — the `SolarWindow` semantics
   (elevation-based, not ±60 min; which window precedes which per event side) are documented there.
3. **Cost:** ~61 locations × ~10 days × 2 events ≈ 1,200 Meeus calls per uncached serve, sub-ms
   each, on an ETag-revalidated endpoint. Acceptable; do not pre-optimise. If profiling ever says
   otherwise, memoise per `(lat, lon, date, type)`.
4. **Accepted degrade, stated up front:** the controller drops `source == NONE` rows, so a "Not
   forecast" sheet row gets **no light line**. Do NOT loosen the NONE-drop to smuggle times through —
   that changes payload size and semantics for every consumer of `/evaluate/scores`. Light times on
   unforecast rows is an almanac-shaped decision for the owner, out of scope here (§8).
5. **Do NOT put the times on `BriefingSlot`** — it serialises into `daily_briefing_cache`, where
   `FAIL_ON_UNKNOWN_PROPERTIES` makes a rollback throw on every cached row (heat-field-plan.md
   decision 8). Nothing in this phase is persisted; no migration.
6. **Backend tests:** `EvaluationViewService` — populated for an emitted row; null on missing
   lat/lon; exception → null, row still emitted. JaCoCo's 80%-per-class rule bites record/guard
   branches: cover them with real assertions rather than deleting the guards.

### 4.2 Frontend

1. Widen `buildScoreIndex`'s value again with the four times; carry through `buildLocationSheet`.
2. Render a light line in the body between the bars and the prose, e.g.
   `golden 20:47–21:26 · blue 21:26–21:58` — **chronological order for the event side** (sunrise:
   blue before golden; sunset: golden before blue). Format with the existing `formatEventTimeUk`
   (currently used at `MarkerPopupContent.jsx:453-456`; move/share it if it is module-private —
   same purity standard as §3.1).
3. Render only complete pairs; all-null → no line. Silence, never synthesis.
4. Tests both sides of the wire, same standards as §3.6.

---

## 5. Phase 3 (optional — owner's call) — peek parity

Once Phase 2's fields ride the payload, the peek can carry the same light line: widen the provider's
reducer (`WindowFirstBriefingContext.jsx:288-295`), `resolveSpotPeek`, and the peek render. Keep the
peek's existing null gate. Skippable: the peek is a glance and a *subset* there is acceptable — the
invariant this plan establishes is only that **the sheet ⊇ the peek**.

---

## 6. Invariants that bind this work

- **§3 rule 11** — the sheet extends its own id-first `buildScoreIndex`; the name-keyed provider
  index is never read by a new surface.
- **P8 decisions 4/6** — rating, summary and (now) scores from **one** score row, one join.
- **§3 rule 6** — degrade is silence, never synthesis. No fabricated bars, no guessed times.
- **§3 rule 5** — no "N of M scored" style counts creep in with the new content.
- **§4 A24/A25** — kicker anti-duplication and row dimming both apply to the new body content.
- **Contracts §3** — no new endpoint: both phases are "another view of the same snapshot"
  (`/evaluate/scores`); the light times are new data on an *existing* shared payload.
- **Privacy seam** — nothing home-derived touches `/evaluate/scores` (it is ETag-revalidated).
  Location light times are fine; leave-by (home-derived) stays client-side.
- **v1 arm untouched. Shared `Modal`/stacking untouched. No new dialog layers.**
- **Backend-heavy** — no solar math client-side; no solar library enters the bundle.

---

## 7. Verification & cadence

Per commit: **build → tests → adversarial review of the diff → fix survivors → re-verify → commit**
(CLAUDE.md UI review cadence — the review runs against the working tree, ~15 agents, six prosecutor
lenses + refuters + synthesis).

- ⚠️ **Review agents are read-only.** A reviewer that mutates and `git checkout --`s has already
  destroyed unstaged work once. Anything that must mutate gets `isolation: 'worktree'`.
- ⚠️ **Paste this plan into review prompts** — an untracked/branch-only doc does not exist in a
  reviewer's worktree, and a compliance lens with no spec returns zero findings and looks clean.
- Frontend CI equivalence (all four, not just the test run):
  `npm run lint && npm test && npm audit --audit-level=high && npm run build`.
- Backend ladder: `compile → single-class test → checkstyle:check → full verify` (Docker running for
  the last rung). **Gate on exit codes, never on grepped output.**
- Browser verification: backend `./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local`
  (port **8083**), `npm run dev`, `admin`/`golden2026`. A local DB has no ratings — use the seed SQL
  recipe in the heat-field handoff memory (fixed 2026-08-20). Check the sheet at **390px** too; the
  phone is a real surface and the sheet is used there.
- Suggested split: Phase 1 = one PR; Phase 2 = one PR (backend + frontend together, so the review
  sees the whole contract); Phase 3 optional third. `CHANGELOG.md` `[Unreleased]` entry per feat PR.

---

## 8. Out of scope

- Light times on **unforecast** rows (requires serving `source == NONE` rows or a separate almanac
  channel — an owner decision, not an implementation detail).
- `WindowSheetDialog`'s region-level content, the map popup, and the v1 arm.
- `ScoreBar.jsx` deletion — optional tidy, its own commit.
- The flag flip. Default stays v1; this work rides the v2 arm behind `usePlanLayout`.
