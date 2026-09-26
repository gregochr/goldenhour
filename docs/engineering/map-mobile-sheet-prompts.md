# Map peek sheet — kickoff prompts for the implementing sessions

One prompt per phase of `docs/engineering/map-mobile-sheet-plan.md`. Each is pasted verbatim into a
fresh **Sonnet** session started in its own worktree off up-to-date `main`. The orchestrating
session pushes, opens the PR, runs the CI + Codex loop and merges (plan §8); the implementing
session never pushes.

**Rules every prompt carries, and why** (from the lunar-eclipse series, Sep 2026):

- *Gates in the foreground.* Sessions that wait for a background-task or Monitor notification hang
  for hours. Run every Maven/npm gate as a foreground Bash call with an explicit `timeout`, or
  redirect to a log and wait with `until grep -q '^exit:' /tmp/x.log; do sleep 15; done`.
- *Gate on exit codes, never on output.* `; echo "exit: $?"` after every gate.
- *Review agents are read-only.* A reviewer that mutates the tree to probe a test will delete
  unstaged work. Give any mutating probe `isolation: 'worktree'`.
- *Browser step has a budget.* Forty minutes wall-clock; if the fixture cannot be reached, say
  which claims were seen and which were tested, and stop.
- *Never push, never tag, never `POST /api/forecast/run`.*
- *Re-verify every line number and symbol against the tree before editing.* The plan was written
  2026-09-25 against a 6,100-line `MapView.jsx`.
- *One commit, three things in it:* the code, the `changelog.d/` entry, and the plan's §0 row.

---

## M1 · Quiet start — no landing card, a fading toast

> You are implementing **Phase M1** of `docs/engineering/map-mobile-sheet-plan.md`. First: read that
> plan **in full** (§1 #3, #4, #16, §2, §3 M1, §4 #14, §5 D-3/D-4, §7 and §9 are binding), then the
> spec `docs/design/map-mobile-sheet/README.md` (the *Interactions* list, items 1 and 5, and the
> *Frame* section's toast line), then `gh pr list --state open` and grep the titles for *landing*,
> *toast*, *phone*, *mobile*, *sheet* — stop and report if anything overlaps. Re-verify every file
> and line M1 names against the tree. Never push, never create or delete tags. Create
> `feature/map-mobile-sheet-m1-quiet-start` off up-to-date `main`, in a worktree.
>
> Scope is §3 M1's three tasks and **nothing visible on desktop or tablet**: on the phone
> (`hooks/useIsMobile.js`) the landing card never opens, `mapLandingSeenRun` is never written, the
> pill's `↺ Back to …` row is withheld; the `★ PhotoCast-scored locations shown` toast on the tab
> (not the overlay's copy) moves to `top: 62px` centred in the phone media block and gains a
> `wf-map-scored-legend-gone` class 3,000 ms after its key last changed, with `aria-hidden` once
> gone and no transition under reduced motion. Update `test/mapPhoneChromeCascade.test.jsx`'s
> literal for the toast; do not touch the other lifted-stack literals (M2 rewrites them).
>
> Tests per §3 M1 with fake timers (2,999 vs 3,000 ms), the desktop invariance case on the SAME
> fixture, and the `localStorage` assertion. Gate on exit codes: `cd frontend && npm run lint &&
> npm test && npm audit --audit-level=high && npm run build; echo "exit: $?"`. Then the adversarial
> review per CLAUDE.md § *UI Work — Review Cadence* (agents read-only; paste M1, §1, §4, §5 and the
> spec's interactions list into every reviewer). Fix survivors, re-run the gate. Browser: §9 fixture
> at 390 × 844 — README Verify 1's first half (no card, toast fades) seen; at 1280 wide the card
> still opens once per run. Commit (conventional message,
> `changelog.d/YYYYMMDD-map-mobile-sheet-m1-quiet-start.md`, §0 row in the same commit). Report the
> branch, the commit, and what you saw versus tested.

---

## M2 · The sheet — shell, peek row, Windows and Layers, map-touch collapse

> You are implementing **Phase M2** of `docs/engineering/map-mobile-sheet-plan.md`. Read the plan
> **in full** — §1 #1, #2, #7, #8, #9, #11, #12, #13, #14, #15, §2, §3 M2, §4 #1–#5, #7–#9, #11, §5
> D-1/D-5/D-7/D-8, §7 and §9 are binding — then the spec `docs/design/map-mobile-sheet/README.md`
> in full and the *Peek sheet*, *Section: Windows* and *Section: Layers* CSS in `Map Mobile
> Minimised.html` (the `.sh`/`.pk`/`.pb`/`.pane`/`.wr`/`.seg`/`.fr` rules — **Option A only**).
> `gh pr list --state open`; grep for *sheet*, *peek*, *phone*, *mobile*, *window control*,
> *layers* — stop and report on overlap. Re-verify every line number. Never push. Branch
> `feature/map-mobile-sheet-m2-sheet` off up-to-date `main`, in a worktree.
>
> Scope is §3 M2's eight tasks. **Do not use `components/BottomSheet.jsx` for the sheet** — §1 #2
> gives the five reasons; build `components/map/MapPeekSheet.jsx` in-frame, backdrop-free, not a
> dialog, always mounted on the phone. The open section is a value of `openMapMenu` (`'peek:win' |
> 'peek:tide' | 'peek:lay'`, D-1) — no second piece of state. The Tide button is **not rendered in this phase** — its
> body arrives in M3 and its rule in M5, and a released control must never open an empty panel;
> the peek row is two buttons here and the strip stays mounted until M3. The Windows
> heading is `landingCardModel().header`, never a fixed string (§4 #2); night rows show `N★ best`
> (§4 #3); the last row is the drilldown door (§4 #4); Layers carries Show / Regions / Filters /
> Legend, and Regions/Filters open their existing phone `BottomSheet`s with the peek collapsing by
> exclusivity (§4 #5). Map-touch collapse is a `useMapEvents` child on Leaflet's `mousedown` /
> `touchstart` / `dragstart` / `zoomstart` — **not** `useOutsideDismiss` (§1 #11) — AND an effect on
> `selectedLocationName` that collapses any open section whenever a selection is installed, because
> selection has several writers — chips and pins stop click propagation, the marker handler and the
> handoff effects set it directly (M2 task 7; test a chip, a pin, a drag and a Plan handoff). The pill override carries popup semantics (`aria-controls`/`aria-expanded` on
> the peek body, no listbox popup type — task 4). The Regions/Filters hosts restore focus to the
> Layers peek button on close (task 6). No safe-area term on the sheet (task 1). `.wf-map-chrome-tr`
> is not rendered on the phone. Rewrite the phone lifted-stack literals and `mapPhoneChromeCascade
> .test.jsx` (§1 #1) — never delete it. Publish `--psh: 74px` and point the callout's phone band at
> it, and make a peek press clear the selection on the phone so the callout and an open sheet never
> coexist in either order (D-7, both orders pinned by test). The sheet's `z-index` is an explicit
> `1120` on the map's own ladder — never the prototype's 520 (§3 M2 task 1). Clamp the open height
> (§4 #7). Tokens per §1 #12 and §4 #8/#9.
>
> Tests per §3 M2 — fire key events at `document.activeElement`, never at a node (map-landing's
> four-times lesson), and pin desktop invariance on the SAME fixture. Gate:
> `cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build; echo
> "exit: $?"`. Adversarial review per CLAUDE.md (read-only agents; include an accessibility lens
> that MEASURES the 8.5 px key labels' contrast at ink-2 and reports the ratio — §6 Q5). Fix
> survivors, re-gate. Browser at 390 × 844 on the §9 fixture: README Verify 1, 4, 5 and 6 per plan
> §7 — measure, do not eyeball (`getBoundingClientRect().height`, computed `flex` on the verdict
> span, a real drag). Commit with `changelog.d/YYYYMMDD-map-mobile-sheet-m2-sheet.md` and the §0
> row. Report branch, commit, measured numbers, seen vs tested.

---

## M3 · The Tide section

> You are implementing **Phase M3** of `docs/engineering/map-mobile-sheet-plan.md`. Read the plan in
> full — §1 #5, #6, #12, #15, §2, §3 M3, §4 #6, #8, #11, #12, §5 D-5, §7, §9 binding — then the
> spec's *Section: Tide* and the *Tide* button row of the peek-row table, then
> `docs/engineering/tide-window-plan.md` §3 T6/T7 (the strip's own rules — its geometry constants,
> `--tsh`, the footer copy) and `docs/design/tide-window/README.md` §6 (the phone row this spec
> supersedes). `gh pr list --state open`; grep *tide*, *strip*, *sheet*, *phone*. Re-verify every
> line number. Never push. Branch `feature/map-mobile-sheet-m3-tide-section` off up-to-date `main`
> (M2 merged), in a worktree.
>
> Scope is §3 M3's five tasks. The first is a **refactor with a pin**: split `MapTideStrip.jsx` into
> `TideStripHeader` / `TideDayChart` / `TideStripFooter` that the strip keeps composing unchanged,
> and BEFORE touching it capture the desktop strip's `outerHTML` on the §9 fixture into a test
> string; the test must pass after the split. `TideDayChart` gains a `tall` projection for the
> section (§4 #6) reading the same served curve and extremes — no cosine, no re-derived level, no
> new threshold. `MapPeekTideSection.jsx` lays the strip's content out vertically per the spec's
> copy; the dimmed line and the next-fit link are `footerModel`/`nextFitCopy`'s own, and the
> extracted footer takes a stable focus target (the Tide peek button) that it focuses BEFORE
> `onSelectEv`, because the link can unmount itself on the jump (M3 task 3; keyboard test required). The Tide
> button's value is `utils/mapPeek.js#tideSummary` (High/Mid/Low, arrow only on Mid, `· N dim` only
> when N > 0) after the existing `TideWave` glyph, in `--color-badge-tide` (§4 #8). Remove the phone
> `MapTideStrip` mount and the phone block's strip/`wf-tide-strip-on` rules; the desktop mount and
> its `--tsh` effect are untouched. Re-point `MapViewTideStripCalloutWiring.test.jsx`'s phone band
> at `--psh`.
>
> Tests per §3 M3. Gate: `cd frontend && npm run lint && npm test && npm audit --audit-level=high
> && npm run build; echo "exit: $?"`. Adversarial review per CLAUDE.md (read-only; one lens on
> "did the desktop strip change at all" with the pin as evidence, one on the chart's served-facts
> discipline against CLAUDE.md's Backend-heavy bullet). Fix survivors, re-gate. Browser at 390 × 844:
> the Tide section against screenshot 03 (structure, not pixels); the next-fit link moves the pill;
> at 1280 wide a before/after screenshot of the strip on the same fixture. Commit with
> `changelog.d/YYYYMMDD-map-mobile-sheet-m3-tide-section.md` and the §0 row. Report.

---

## M4 · Tide mode — the persisted setting (backend + api + hook, no UI)

> You are implementing **Phase M4** of `docs/engineering/map-mobile-sheet-plan.md`. Read the plan in
> full — §1 #10, §3 M4, §5 D-2, §6 Q1 binding — then CLAUDE.md's *User Settings* API section, its
> *Database Migrations* table's "latest" row and the Docker note (**there is no local Docker; the
> migration is proven only in CI**), `V147__user_map_colour_preference.sql`,
> `UserSettingsService.saveMapColourPreferences`, `AppUserRepository.updateMapColourScaleByUsername`,
> `AppUserEntity`'s class Javadoc (why every settings column is `updatable = false`),
> `HttpCachingConfigTest.personalDataPathsAreNeverFiltered`, and `hooks/useReaderSettings.js`.
> `ls backend/src/main/resources/db/migration | sort -V | tail -1` — the new migration is the next
> number (expected V155; use what the tree says). `gh pr list --state open`; grep *settings*,
> *user*, *migration*, *V15*. Never push. Branch `feature/map-mobile-sheet-m4-tide-mode-setting`
> off up-to-date `main`, in a worktree. This phase touches no file M1–M3 touch; if `main` moves
> under you, `git merge origin/main` — never rebase.
>
> Scope is §3 M4's five tasks, copying the `map-colours` shape exactly: migration (nullable, no
> default, no backfill, V147's header reasoning), entity column (`updatable = false`), column-scoped
> repository writer, request record, service save validating `Set.of("auto","always","off")` with
> the null-before-`Set.of` guard, `@PutMapping("/map-tide-mode")`, response field, caching-test
> path, `settingsApi.saveMapTideMode`, `useReaderSettings` exposing `mapTideMode` (`'auto'` when
> null), threaded as props `App` → `WindowFirstMapPane` → `MapView` on the `mapColourScale` route
> (unread until M5, but wired and tested here — the hook has one instance, owned by `App`), and a
> serialised `saveTideMode` action — one save in flight, a newer press supersedes a queued
> older one, a response applied only if it answers the newest request (§3 M4 task 4, a Codex
> finding on M0); reuse `createColourSaveQueue`'s mechanics if the generalisation is small,
> otherwise a sibling line with the same rules, and pin the out-of-order case by test.
> CLAUDE.md's API section gains the endpoint beside `map-colours`. No UI.
>
> Tests per §3 M4 (service: three valid, invalid and null → 400, writes through the column-scoped
> update and never `save`; controller round-trip; an `IntegrationTestBase` assertion the column
> exists — it will not run here; say so in the report). Backend gate, foreground, gated on the exit
> code: `cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress
> -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"`
> (run `./mvnw checkstyle:check` first). Frontend gate as in the other phases. Adversarial review
> per CLAUDE.md with a lens on concurrency against the entity Javadoc's rule (a whole-entity save
> anywhere on this path is a defect). No browser step. Commit with
> `changelog.d/YYYYMMDD-map-mobile-sheet-m4-tide-mode.md` and the §0 row. Report, stating plainly
> that the migration is pending CI.

---

## M5 · Tide visibility — the Auto rule, the mode control, one gate for every cue

> You are implementing **Phase M5** of `docs/engineering/map-mobile-sheet-plan.md`. Read the plan in
> full — §1 #5, #6, #7, §2, §3 M5, §4 #9, #12, #13, §5 D-5/D-6, §6 Q2, §7, §9 binding — then the
> spec's *Interactions* items 6, 7, 8, its *State* block, the *Tide button appearing* pulse, and the
> *Section: Layers* Tide row and hint. `gh pr list --state open`; grep *tide*, *mode*, *sheet*,
> *phone*. Re-verify every line number. Never push. Branch `feature/map-mobile-sheet-m5-tide-rule`
> off up-to-date `main` (M2, M3 and M4 merged), in a worktree.
>
> Scope is §3 M5's five tasks. The rule is pure (`utils/mapPeek.js#tideVisible`, with
> `tideAvailable` and `coastalInView` as SEPARATE inputs — never `stripModel.visible`, §3 M5 task 1)
> and exhaustively tabled; `hasCoastalInView` is `coastalInView(spots, bounds).length > 0` — a new exported helper on
> `mapTideFit.js` returning the filtered ARRAY (empty is truthy; pass the boolean), computed from
> the UNDECORATED spot list before `tideTier` is set (`stripModel` returns `namedCoastal`, not
> `coastalInView`, and is built from the decorated list — reading it there is a cycle; §3 M5 task 1's
> order-of-evaluation note is binding, and a real-`MapView` wiring test pins it); "Poor" is `STAND_DOWN` on the pill's own scope verdict (§1 #7) and a null/`AWAITING`
> verdict hides in Auto (D-6). **Every tide cue is gated by ONE null** at the spot-build site's
> `tideTier` (§1 #6) — do not thread a flag to `MapLabels`, `PinsLayer` or the tooltips. The Tide
> button renders iff the rule; an open Tide section closes in the same render the rule turns false,
> with focus rescued to the Other windows button. The pulse plays once on a false → true transition
> after mount, never on mount, never under reduced motion. The Layers Tide segment (Auto | Always |
> Off, 220 × 36) saves through the hook's serialised `saveTideMode` and reverts on failure with the
> pane's `role="status"` line. Off does **not** touch `TideFitBlock` (§4 #13). Desktop: no change of
> any kind — `data-tide` still present on a Poor window whatever the saved mode.
>
> Tests per §3 M5 (the truth table; phone Poor/Maybe/Off/Always cases counting `[data-tide]`; the
> close-and-rescue; the pulse's transition-only rule; desktop invariance; cascade reads of the
> keyframes and the reduced-motion override). Gate: `cd frontend && npm run lint && npm test && npm
> audit --audit-level=high && npm run build; echo "exit: $?"`. Adversarial review per CLAUDE.md
> (read-only; one lens hunting for a SECOND tide gate anywhere in the diff — there must be exactly
> one). Fix survivors, re-gate. Browser at 390 × 844 on the §9 fixture with both a Worth-it and a
> Poor window: README Verify 2 and 3 per plan §7, measured; reload keeps the mode. Commit with
> `changelog.d/YYYYMMDD-map-mobile-sheet-m5-tide-rule.md` and the §0 row. Report.

---

## M6 · Sweep, docs, the measured Verify list, and the OPEN answers

> You are implementing **Phase M6** of `docs/engineering/map-mobile-sheet-plan.md`. Read the plan in
> full and every §0 row M1–M5 wrote, then the spec's *Verify* list, then CLAUDE.md's *Map tab (v2)*,
> *Map tab — the verdict, the picks and the landing card*, the tide-window paragraph inside the
> Map tab bullet, and the *Backend-heavy* bullet. Never push. Branch
> `feature/map-mobile-sheet-m6-sweep` off up-to-date `main`, in a worktree.
>
> Scope is §3 M6's five tasks and **no user-visible behaviour change**. Run the six Verify checks
> at 390 × 844 AND 1280 wide on one §9 fixture and write the measured numbers into §0's M6 row
> (sheet heights, visible map height collapsed vs the spec's ~440 px claim). Delete M3's `outerHTML`
> pin and record it. Correct every stale line number in §1. CLAUDE.md: the new *Map tab on a phone —
> the peek sheet* bullet; the Backend-heavy bullet's three filter/map/select reads added to the
> ALREADY-licensed class with the sentence saying they are not a new numbered class; the tide-window
> phone paragraph and the "bottom bar" sentence corrected. `map-tab-v2-plan.md` §6 O-20,
> `tide-window-plan.md` §0 and `map-landing-plan.md` §0 each get the one note §3 M6 task 4 names.
> Answer §6 Q1–Q6 against what shipped; restate whatever is still open. Then have two read-only
> lenses review the sweep itself for FALSE STATEMENTS in authoritative files (the map-landing L7
> lesson: five of ten defects were in the docs just written) and fix them.
>
> Gate: the frontend four-step gate (docs-only diffs still lint) with `; echo "exit: $?"`. Commit
> with `changelog.d/YYYYMMDD-map-mobile-sheet-m6-sweep.md` and the §0 row flipped to
> ✅ CODE-COMPLETE. Report.
