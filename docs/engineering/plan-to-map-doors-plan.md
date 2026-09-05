# Doors from Plan to Map — implementation plan

**Source**: the design increment vendored at `docs/design/map-tab-v2/INCREMENT_plan_to_map_doors.md`
(the spec) and `CLAUDE_CODE_PROMPT_doors.md` (the designer's working order), against bundle rev 3 of
`plan-tab-v5.js` / `map-tab-v2.js` (the prototypes — vendored verbatim beside them; the doors live at
`plan-tab-v5.js:60–75, 355–364, 475–531, 585–587` and the receiving end at `map-tab-v2.js:27–40,
136–162, 302–310, 512–528`). Read the increment before any phase; this document is the *port* plan:
what the codebase already has, what is genuinely new, where the increment and the code disagree on
purpose, and how the work cuts into single-session phases for **Sonnet** sessions.

**The one-sentence version.** The Plan tab already looks like the map and had no route into it. Two
doors (the increment's third, the thumbnail glyph, was dropped by the owner at plan time — §6 Q1)
open the Map tab at the window you tapped from, carrying the window, the region focus, the rating
floor, the reach tier and — above all — the origin; a breadcrumb on the map names every carried fact
and offers to clear them. The increment's own two lessons are the plan's two hard rules: **never send a
parameter nothing reads**, and **never draw over a field without seeding the label placer's obstacle
array**.

**Cadence per phase** (CLAUDE.md "UI Work — Review Cadence"): build → tests → adversarial review of
the diff (~6 prosecutor lenses + one refuter per charge, all read-only) → fix survivors → browser
verification (§9 recipe) → commit. Frontend gate before any push-request: `npm run lint && npm test
&& npm audit --audit-level=high && npm run build`, gated on exit codes. Never push; never tag. Every
phase adds a `changelog.d/YYYYMMDD-<slug>.md` entry (never a direct `CHANGELOG.md` edit). Paste the
relevant section of THIS plan and the increment into every review agent's prompt — review agents
cannot see untracked context, and a compliance lens with no spec returns zero findings.

**Check open PRs first.** `gh pr list --state open` and grep the titles for *door*, *breadcrumb*,
*handoff*, *origin*, *driveOf*. The owner writes specs and sometimes builds against them in parallel;
the overlap only surfaces at merge, where it is most expensive. Checked 2026-09-04 at plan time: no
overlap (only #753, a Coming up label fix).

---

## §0 Status

**Status: COMPLETE — D1–D4 are all on `main` (D1 `dffd764a`, #762; D2 `399a7e36`, #763; D3
`f52a6013`, #764; D4 `a4964ed8`, #765). D5 was dropped unbuilt (§6 Q1) and D6 (this sweep) closes
the series.** All three owner decisions recorded 2026-09-04 (§6): Q1
drop door 3, Q2 land on the plan, Q3 re-point the sheet footer. Phase log (D1 creates the first row; every phase appends its own
in the same commit as its code):

| phase | branch | commit | date | notes |
|---|---|---|---|---|
| D1 | `feature/doors-d1-drive-accessor` | `dffd764a` (#762) | 2026-09-04 | Accessor unified (`driveMinutesFor` reads `reachById` on the tab, `userDriveTimes` overlay-only); home geography (HOME marker, reach rings, ring labels, legend rings toggle) gated on a new `origin` prop via derived `homeGeo`; `⌂` left present and un-gated (O-D5); `heat.beyondRegionNames` recorded as the one deliberate home-only read. Backend verified first: `/drive-times` and `/reach` both read `UserDriveTimeRepository.findByUserId` through the shared `DriveTimeResolver.getAllMinutes`. ⚠️ Adversarial review (5 lenses + refutation) found one real defect, fixed pre-commit: `CentreOnHomeControl` read raw `homeCoords` alone for its OWN enabled/label state, so a reader planning from an away origin who had never saved a home postcode still saw the disabled "Set your home postcode in Settings" prompt and a click opened Settings — for a reset action (`resetToMyArea`) that needs no home coordinate at all once an origin is in force. This is exactly the failure task 2 names ("do not surface a 'set your postcode' prompt … while away"). Fixed by giving the control its own `origin` prop and an `actionable = hasHome \|\| Boolean(origin)` gate; O-D5's doc block updated to state both the with- and without-postcode away cases. Test quality review independently found the same gap (presence-only assertions); rewritten as four state+behaviour tests (accessible name, `data-disabled`, click routing) covering all four home×origin combinations. Two lower-severity notes from the "harder for D2-D4" lens were reviewed and not actioned: `activeDriveMap`'s standalone expression is what task 1's own conditional recommends once the accessor moves onto `reachById` (refuted with the plan's own text); the three "is home known" reads (`Boolean(homeCoords)`, `homeGeo`, `hasHomeCoords`) answer genuinely different questions and now carry one added cross-referencing doc sentence rather than a merge. Accessibility and project-conventions lenses raised no charges. 19 new or rewritten tests total (away/home jump-row pairs, HOME/rings/legend-toggle present-and-absent pairs, `mapReachMeasured`'s away case both directions, the overlay negative test, and the ⌂ control's four home×origin state/behaviour cases). Gate green (lint, 4963 vitest, audit 0 vulnerabilities, build). |
| D2 | `feature/doors-d2-handover-breadcrumb` | `399a7e36` (#763) | 2026-09-04 | The payload (`App.openMapTabFromPlan`, reusing the existing `mapTabHandoff`/`tabRequest` nonce channel with a new `source:'plan'` field), the shell's `openMapTab(door)` wrapper (no caller yet — D3/D4 wire the buttons; its close-then-move-and-merge logic extracted to a pure, directly-tested `utils/mapDoors.js#openMapDoor` rather than left genuinely untested), the pane's `planHandoff` derivation (nulls the five old per-field `handoff*` props for a door so they cannot double-apply alongside the new effect), `MapView`'s one nonce-keyed door-landing effect (event, floor `?? 1` never null, tier `?? 0`, region via `jumpToRegion`/`resetToMyArea` — never `FitBoundsController` — location off the full roster), and `components/map/MapBreadcrumb.jsx` mounted above the map frame with its four derived-truth carrying clauses and four-callback `clear`. ⚠️ Adversarial review (5 lenses + refutation) found two real defects, both fixed pre-commit: the breadcrumb's top-level sibling spans had no `{' '}` between them, so "Plan" glued straight onto "/" and "sunset" onto "carrying" in the accessible name and rendered text (accessibility lens, HIGH — the exact trap `WindowFirstComingUpHandoff.jsx`'s own doc note names); and `.wf-map-breadcrumb-sep` read `--color-plex-text-muted`, under 4.5:1 against the bar's own background (accessibility lens, MEDIUM — switched to `--color-plex-text-secondary`, the correction this file has made repeatedly elsewhere). A third, convergent finding (runtime + test-quality + forward-compat lenses, independently) — `openMapTab`'s close-then-move-and-merge logic had zero test coverage since no door UI exists yet to call it through — was closed by extracting it to `utils/mapDoors.js#openMapDoor`, a pure function taking its collaborators as arguments, with a dedicated 9-test file (`test/mapDoors.test.js`) exercising ordering, the live lens-value overwrite, and every null-default path; `WindowFirstShell.jsx`'s `openMapTab` is now a thin wrapper. Four test-quality findings fixed: an "own nonce" assertion that only checked both nonces were numbers (now asserts genuine inequality); a hatch-forwarding test that checked 3 of 6 fields (now all 6); the rating/reach derived-truth tests only exercised the "carried > live" mismatch direction (added the "live raised/widened past carried" direction, mutation-tested against a `>=`/`<=` swap); the "no region resets to My area" test couldn't tell "ran" from "coincidentally already default" (rewritten to jump away first, on an earlier nonce, then prove the reset actually undoes it — mutation-tested against the branch being dropped). Every fix mutation-tested (temporary `cp` backups, never `git checkout --`) before restoring. Runtime, project-conventions and forward-compat lenses' remaining notes were either already-refuted-by-plan-text or informational (a factual correction to `openMapTab`'s own doc comment, which had wrongly claimed `WindowPickDialog`'s two routes shared its close-then-move ordering when they actually close second). Browser-verified (headless Chromium via a temporary `window.__doorsDebugOpenMapTab`/`__doorsDebugReturnToPlan` hook in `App.jsx`, removed before commit — confirmed absent from the diff): crumb height exactly 34px, the map frame shrinks by exactly that amount (606.75px → 572.75px at 1280×800); home crumb reads `carrying 4★+ · within 2h 30 · Northumberland & Tyneside` (no origin clause); away crumb (a real region base + the shipped masthead search, not the debug hook) reads `carrying drive times from Keswick · 4★+ · within 2h 30` (no region clause, per the increment's own "Door 1 carries a region only at home" rule); `clear` empties the carrying clause and (from away) returns the masthead to its no-postcode empty state on the Plan tab; `← Plan` moves both `aria-selected` and DOM focus to the Plan tab button; no horizontal scroll at 390×844, the carrying clause wraps onto its own line there; real Chromium accessibility-tree queries (`getByRole('button', {name:'Plan'/'clear', exact:true})`) both resolve to exactly one element; measured contrast of the carrying-clause ink over its composited background is 7.15:1, clearing 4.5:1 with room. Gate green (lint, 5020 vitest, audit 0 vulnerabilities, build). A follow-up coordinator review (a second, independent pass over the diff) found two more real defects, both fixed and mutation-tested before commit: the door effect copied the hatch's `setUserHasOverriddenEvent(false)`, which the auto-event effect's own dependency array turned into a silent revert for any reader who had already used the window control (§4 #12; the hatch's identical hole recorded as **O-D6**, pre-existing, not fixed here); and `WindowFirstMapPane`'s `onClearOrigin` was a fresh arrow literal every render into `React.memo(MapView)`, fixed with `useCallback`. 5022 vitest passing after both fixes. |
| D3 | `feature/doors-d3-door2-sheet` | `f52a6013` (#764) | 2026-09-04 | The location sheet's footer re-pointed: `WindowFirstShell.jsx`'s `LocationFourDaySheet` mount now passes `onShowOnMap={onOpenMapTab ? (date, targetType, name) => openMapTab({date, targetType, locationName: name, region: null}) : undefined}` — D2's `openMapTab`/`openMapDoor` does the close-then-move itself, so the shell no longer hand-rolls it (the old inline `openOverPopup(null); openWindow(null); onShowOnMap?.(...)` sequence is gone). Every other `onShowOnMap` producer (`WindowSpotSheet`'s ranked-spot-card route via `handleSpot`, `WindowPickDialog`'s `onShowRegion`/`onShowLocation`) is untouched and still opens the overlay. Withholding (task 2): `LocationFourDaySheet.jsx`'s footer gate changed from `handoff ?` to `(handoff && onShowOnMap) ?`, so a shell with no map door renders `location-sheet-nomap`'s sentence rather than a dead button — pinned at both the component level (`LocationFourDaySheet.test.jsx`) and the shell level (`locationSheetShell.test.jsx`, via a new `renderShell({}, {onOpenMapTab: undefined})` case). Two shell-level tests moved from asserting `onShowOnMap` to asserting `onOpenMapTab` with the full door shape (`{date, targetType, locationName, region: null, minRating, limitMinutes}`, both read live off the fixtures' own `RATING_LENS`/`LENS` — `minRating: null, limitMinutes: 45`, not the increment's 4★+/2h30 example) and now also assert `onShowOnMap` was NOT called, proving the re-pointing rather than an addition; the close-then-move call ORDER (`openOverPopup`, `openWindow`, `onOpenMapTab`) stays pinned at `mapDoors.test.js`'s `openMapDoor` suite, unchanged by this phase since D3 only changes which callback fills that pure function's `door`/`onOpenMapTab` slots, never its internals — both rewritten tests state this in a comment rather than re-deriving an order proof React's own batching makes unobservable at the rendered level. ⚠️ Adversarial review (4 lenses, all read-only, Explore agents): runtime behaviour, test quality and accessibility lenses raised zero charges; project conventions raised one low-confidence cosmetic wording nit (a new code comment said `onOpenMapTab` "is undefined" where the prop's own default parameter is `null` — true of `App`'s actual value reaching this callsite, imprecise as a general claim) — fixed pre-commit by naming `App` as the source of the `undefined` and noting the shell's own default explicitly, and by correcting a second stale reference in the same comment block to the footer's old `handoff ?` condition. No refuter round was needed — nothing survived to refute. Gate green: lint 0, vitest 5024 passing (211 files), build clean, `npm audit --audit-level=high` 0 vulnerabilities. Browser-verified (headless Chromium via Playwright, `playwright-core` driven directly rather than through `npm run test:e2e` — the interactive Browser pane hangs; backend on 8087 via `-Dspring-boot.run.jvmArguments=-Dserver.port=8087` since a bare `-Dserver.port` on the `spring-boot:run` Maven command line does not reach the forked app JVM; H2 seeded via `scripts/dev-seed-locations.sh` + a stopped-backend `RunScript` insert of `cached_evaluation`/`user_drive_time`/`region_drive_time` rows spanning today + two more days so a window after the first night exists, restart, `POST /api/briefing/run`): from a location sheet opened over the popup on a window whose own highest rating falls on a later day (Bamburgh Beach's "Tomorrow sunset", 2026-09-05), pressing "Show on map" landed the Map tab on that exact window (window control read `SUNSET Tomorrow 19:50`), with the popup and sheet both gone from the DOM, the crumb reading `← Plan / Tomorrow sunset carrying within 45 min clear` (no rating clause — the fixture's Any lens carries no clause to name, per plan §5 rule 2) and the callout showing Bamburgh Beach's `5★ Worth it`/`Clear horizon, high cloud lighting up.` (the exact seeded row) with `Drive 40 min` (the home arm). From an away origin (search → The Lake District, base Keswick, seeded `region_drive_time`), opening Derwentwater's sheet (meta line: `The Lake District · 12 min from Keswick`) and pressing "Show on map" landed on `Tomorrow sunrise` with the crumb reading `carrying drive times from Keswick · within 1h 30 clear` and the callout's `Drive 12 min` — the BASE figure, not Derwentwater's separately-seeded 150 min home figure — confirming the callout reads through the same origin the sheet itself already did. The withholding case (no map door at all) was TESTED only at the unit level, not SEEN in the browser — forcing `App`'s "nothing to map" gate (`allDates.length === 0`) needs an empty forecast the seeded fixture does not produce, and the unit coverage (component + shell level, both halves of the degrade asserted) was judged sufficient for a pure conditional-render gate. |
| D4 | `feature/doors-d4-door1-field` | `a4964ed8` (#765) | 2026-09-04 | Door 1: `WindowRowFieldMap.jsx` gains the `◍ Open in map →` button (`.wf-mopen`, top-right of `.wf-mapbox`, one text node accname per the sheet-footer's own lesson), rendered only when `onOpenInMap` is a function; `WindowSheetDialog` passes it straight through; the shell builds the door from the open window's date/type and `openField.selectedRegion` (already forced null under an away origin by D2), giving `openMapTab` its first real caller — the D2-era `// eslint-disable-next-line no-unused-vars` above it is gone, no longer needed. **The seed** (the increment's own recorded defect — its prototype drew this exact button without seeding it and covered a chip's rating in 4 of 6 windows): measured from the LIVE mounted element (`openRef.current.offsetLeft/Top/Width/Height`, never a constant — `HINT_BOX` stays a constant on purpose, per its own doc block, for the one fixed string that cannot change), pushed into the placement effect's `boxes` array after the hint/not-scored corners and before the ring labels, flagged `target: true` so the same 24px centre-separation rule already keeping two chips apart also keeps this control apart from every chip; `Boolean(onOpenInMap)` added to the effect's own dependency array. ⚠️ Adversarial review (5 lenses, all independent, all read-only) found **one real, low-severity finding**, fixed pre-commit: `playwright.config.js`'s `webServer.reuseExistingServer` had been hardcoded `true` (from `!process.env.CI`) to let the spec reuse a manually-started vite on a non-default port — broader than the task needed (only `baseURL`/`webServer.url` had to become env-configurable) and undocumented by the comment that justified only the `baseURL` half; reverted to `!process.env.CI` (inert either way, since CI never invokes this config — confirmed via `grep -rn "playwright\|test:e2e" .github/workflows/*.yml`, one comment only). Runtime, CSS, accessibility and test-quality lenses raised **zero charges** each — every one of the plan's own D4 acceptance points (live measurement, `target: true`, dependency array, click-gesture/hint untouched, insertion order, explicit `line-height: 1.2`, inset focus ring per the `.wf-mchip:focus-visible` precedent, phone `7px 10px`/`10px`, no inline styles, exact `Open in map →` accname with `◍` hidden, `HINT_BOX` left untouched, scope discipline — zero touches to `LocationFourDaySheet.jsx`/`onShowOnMap`) were independently confirmed by citation against the live file, not merely asserted. Test-quality lens hand-traced three of the new placement tests through `fits()`'s actual arithmetic (not just read the assertions) and confirmed each is genuinely mutation-sensitive to the change it claims to pin, including the 24px-vs-`target`-flag test's own claim that its geometry clears the ordinary `BOX_GAP` overlap test and only the flag drops the chip. Four new/updated Vitest files (`WindowRowFieldMap.test.jsx`'s "Door 1, seeded as a placement obstacle" describe block — 5 tests, including a from-scratch `withDoorAndChipBoxes` helper distinct from the file's existing `withChipBoxes` because the seed needs a SPECIFIC top-right rect rather than a uniform stub; `WindowSheetDialog.test.jsx` — 2 tests; new `WindowFirstShellDoor1.test.jsx` — 4 tests covering close-then-move wiring, the home-region-carried case, the away-origin-carries-null case with the rail confirmed absent rather than merely unpicked, and the no-door withholding rule). Gate green throughout (lint 0/0, vitest 5033/5033 — 212 files, build 0 errors, `npm audit --audit-level=high` 0 vulnerabilities). **Browser — the increment's own check 5, scripted** (`src/test/e2e/door1-obstacles.spec.js`, new; `frontend/playwright.config.js`'s `baseURL`/`webServer.url` made `PLAYWRIGHT_BASE_URL`-configurable for this, `forecast.spec.js`'s own hardcoded backend/default baseURL unaffected). Seeded a local stack (backend 8089, vite 5187, `scripts/dev-seed-locations.sh` catalogue already present in this worktree's H2 file; `cached_evaluation` rows inserted via `org.h2.tools.RunScript` for T+0..T+3 × SUNRISE/SUNSET × all 4 regions — 32 rows — backend stopped for the H2 lock, restarted, `POST /api/briefing/run`) giving all 6 matrix windows chips. Sampled `document.elementFromPoint` every 2px across every chip's width at its vertical centre, both viewports, all 6 windows:

  | viewport | window (DOM order) | chips | sample points | hits | overlap |
  |---|---|---|---|---|---|
  | 1280×800 | card 0 (Tomorrow sunrise) | 4 | 248 | 248 | none |
  | 1280×800 | card 1 (Sunday sunrise) | 4 | 248 | 248 | none |
  | 1280×800 | card 2 (Monday sunrise) | 4 | 237 | 237 | none |
  | 1280×800 | card 3 (Tonight Sunset) | 4 | 237 | 237 | none |
  | 1280×800 | card 4 (Tomorrow sunset) | 4 | 248 | 248 | none |
  | 1280×800 | card 5 (Sunday sunset) | 4 | 248 | 248 | none |
  | 390×844 | card 0 (Tonight Sunset) | 6 | 345 | 345 | none |
  | 390×844 | card 1 (Tomorrow sunrise) | 6 | 356 | 356 | none |
  | 390×844 | card 2 (Tomorrow sunset) | 6 | 356 | 356 | none |
  | 390×844 | card 3 (Sunday sunrise) | 6 | 353 | 353 | none |
  | 390×844 | card 4 (Sunday sunset) | 6 | 353 | 353 | none |
  | 390×844 | card 5 (Monday sunrise) | 5 | 289 | 289 | none |

  Zero overlaps in 12/12 window×viewport cells, re-run three times for stability (no flakes). Two
  measurement traps hit and fixed while writing the spec, worth recording since they will recur for
  any future `elementFromPoint` sweep: (1) `WindowRowFieldMap`'s two-pass chip placement briefly
  renders every candidate off-screen at `left: -9999px` before the greedy pass commits final
  positions — sweeping mid-measurement makes `elementFromPoint` sample coordinates outside the
  viewport, which returns `null` and reads as "the button overlaps everything"; fixed with a
  `waitForFieldToSettle` poll (two consecutive RAF-stable snapshots of every chip's + the button's
  own rect) before any position is read — a false positive from the race, never from the seed,
  confirmed by hand against the identical window once settled. (2) Playwright's `boundingBox()`
  returns `{x, y, width, height}`, not a DOMRect's `{left, top, right, bottom}` — a `disjoint()`
  helper written for the DOMRect shape and fed a `boundingBox()` result compares `undefined` to
  `undefined` on every side, which is never true, so EVERY pair reads as overlapping regardless of
  where either box actually sits; fixed by converting explicitly inside `disjoint()` rather than
  trusting call sites to agree. A third, minor artifact: the exact outer-boundary pixel of a chip's
  rounded corner anti-aliases against the canvas beneath it regardless of any obstacle, so the sweep
  insets 1px from each edge (sampled only at the chip's vertical centre, where a 16px-tall chip's
  corner curvature is negligible) — a reader cannot click that literal boundary pixel either.

  SEEN (not scripted, confirmed by hand via direct JS geometry reads and a screenshot, home origin,
  Northumberland & Tyneside picked on the rail): the button renders top-right, unobstructed; a press
  closes the popup (`window-sheet` gone) and lands the Map tab on "Tomorrow sunrise", scoped to the
  picked region, with `MapBreadcrumb` reading `← Plan / Tomorrow sunrise carrying within 45 min ·
  Northumberland & Tyneside clear` — the region clause present because the door was opened at home
  with a region picked, exactly as plan §5 rule 3 specifies. TESTED (via the Vitest suite): the
  away-origin case (`region: null`, rail absent) and the no-door withholding case. |
| D5 | — | — | — | Dropped unbuilt (§6 Q1). No session ran this phase; no branch, no commit. |
| D6 | `docs/doors-d6-sweep` | (this PR) | 2026-09-04 | Sweep and docs. §0 flipped to COMPLETE and the phase-log commit column filled in against `origin/main`'s tail; §4's cross-references re-checked against the tree (all 12 items still describe what shipped, no renumbering needed — #11 and #12 were already in the order they were found, at plan time and in D2's review respectively); §6's open items reframed as the list that survives the series (Q1–Q3 stay DECIDED, untouched); §7's "How" column filled in with what each phase actually ran and measured, row 6 confirmed still not applicable; §8 gained a PR-number column. CLAUDE.md's Map tab (v2) bullet gains the three-part handover (`App.openMapTabFromPlan`, the nonce-keyed door effect, `MapBreadcrumb`) and the `driveMinutesFor`/overlay-fetch split; the Plan tab bullet's dialog-stack paragraph gains the two doors beside the settings cog. `map-tab-v2-plan.md` §6's O-6 gains a sentence recording that the location sheet footer has moved off the overlay while every other producer stays. ⚠️ O-18 was REWRITTEN on the rebase, not appended to: this sweep was authored 2026-09-04 against D4, and #774 (`b9e987f7`, 2026-09-05) closed O-18 the other way — `Four days here ›` opens the sheet over the map. The draft note had read Q2 as a general precedent for landing on the destination tab plain, which that closure falsifies; it now records why the two routes differ (the destination decides) so the pair does not read as an inconsistency to harmonise. The Playwright spec (`src/test/e2e/door1-obstacles.spec.js`) is kept as a regression check, not wired into CI; a "how to run" note added to §9. ⚠️ Adversarial review (3 read-only lenses — truth against the tree, plan self-consistency, CLAUDE.md conventions): truth and conventions lenses raised zero charges (every code claim independently re-verified by file:line citation against `main`, including the four commit SHAs/PR numbers against `git log`). The consistency lens found one real defect and one refuted one: §7 row 3's Phase column read bare `D1`, but the row's own "How" text cites a browser check that only D3 actually ran (the file's own convention, used one row above, is to name every phase a check's evidence came from) — fixed to `D1 (unit), D3 (browser)`, upheld by a dedicated refuter. A second charge (O-D2 sits under a header just reworded to say every item "survives" as open, while O-D2's own pre-existing text says "Closed with Q2") was refuted: the header's claim is not false of a closed item, and §6 already mixes DECIDED/closed entries into an "OPEN items" list throughout, unchanged by this phase — not actioned. |

---

## §1 Corrections to the increment — where the codebase has already moved

The increment was written against the prototype, in which the two tabs are separate documents joined
by a URL hash, the Map tab has a hard-coded `HOMEPT` and no origin concept, and the location sheet's
`◍ Show on map →` button has no handler. **None of those three is true of the app**, and each changes
what a phase has to do. Re-verify every line number below against the tree before editing — the plan
was written 2026-09-04 and code moves.

1. **The Map tab already receives the origin, and most drive-time reads are already behind one
   accessor.** Origin is shared state on `context/WindowFirstBriefingContext.jsx:242`
   (`useState(null)`, in memory only), set through `setOrigin(region)` → `toOrigin` (`:524`), and
   published with `effectiveReachById = originReachMap(origin, regionMatrix) ?? reachById` (`:504–507`).
   `WindowFirstMapPane.jsx:121` reads both and hands `MapView` `driveOverrideById: origin ?
   effectiveReachById : undefined` (`:232`). `MapView.jsx:1101–1106`'s `driveMinutesFor(locId)` is
   the app's `driveOf(s)` — an *overwrite, never a fallback* — and the reach filter (`:1945`), the
   chip tooltips and chip ordering (`labelSpots` at `:2040` → `MapLabels.jsx:574`,
   `utils/mapLabels.js:271`), the pins tooltip (`PinsLayer.jsx:394`), the callout facts and leave-by
   (`:3297`, `utils/mapCallout.js:148–177` → `utils/leaveBy.js`) and the masthead statement
   (`MastheadTickLine.jsx:151–158`, `Lake District · from Keswick / drive times from here`) all
   already honour it. The prototype's `s.min`/`s.lmin` are the app's `reachById` (per-user
   `GET /api/user/settings/reach`) and `effectiveReachById` (the shared `GET /api/regions/drive-times`
   matrix through `planOrigin.originReachMap`), and the two must never be mixed
   (`utils/planOrigin.js:14–30`).

   **So the increment's step 1 is not "write `driveOf`"; it is "close the reads that bypass it".**
   Four do, and they are D1's whole job:

   | leak | where | why it is a leak |
   |---|---|---|
   | The Regions jump list's drive map | `MapView.jsx:2406` `jumpDriveMap = driveOverride \|\| reachById \|\| null` | a second precedence expression beside `driveMinutesFor`; at home it reads `reachById` where the accessor reads `userDriveTimes` — two endpoints for one journey |
   | `mapReachMeasured` | `MapView.jsx:2077` `Boolean(homeCoords) && Object.values(userDriveTimes).some(finite)` | home-only by construction: an away origin with a fully measured matrix reads "unmeasured", so the Filters popover's drive segment (reach-measured only) and every duration label go dim exactly when the reader has moved to a base whose drives ARE measured |
   | The home-drive source split | `driveMinutesFor` reads `userDriveTimes` (`GET /api/user/settings/drive-times`, fetched by `MapView` itself at `:1078`); `distanceMilesFor` (`:1120`) reads `reachById` (`/reach`) | the same home journey from two fetches; the Plan cards read `reachById`, so the tab's minutes and the Plan's can disagree after a drive-time refresh until the map's own fetch re-runs |
   | `heat.beyondRegionNames` | `WindowFirstMapPane.jsx:244` `origin ? [] : beyondRegions(heatSpots, reachById)` | home-only **by design** (an away scope is one region, so "beyond your area" names nothing) — not a defect, but it must be *recorded* as the one deliberately home-only read, or the next audit refiles it |

2. **The home marker, the reach rings, the rings toggle and the ⌂ control still draw and gate on
   `homeCoords` under an away origin.** `MapView.jsx:2994/3010/3023/3102` pass `homeCoords`
   unconditionally to `MapHeatLayer` (ring paint), `MapLabels` (HOME label + ring labels),
   `PinsLayer` (HOME) and `CentreOnHomeControl`; `MapLegendPanel`'s rings toggle gates on
   `hasHomeCoords` (`:2091`, `:3631`). So a reader planning from Keswick sees drive times from Keswick
   beside a HOME dot and 25/50-mile rings drawn round DH3 4NG. That is precisely the "two origins'
   journeys on one screen" the increment forbids, and it is the increment's step 2 in the app: **gate
   home geography on `origin == null`** (§3 D1). The increment's "do not invent a coordinate for an
   away base" is honoured for free — the app has no such coordinate to invent (`toOrigin` carries
   `{id, name, baseName}` and nothing more, `planOrigin.js:123`).

3. **Door 2's button is not a stub — it is wired to the wrong destination.** `LocationFourDaySheet.jsx:504–521`
   already calls `onShowOnMap(handoff.date, handoff.targetType, sheet.name)`, and the shell
   (`WindowFirstShell.jsx:1899–1903`) routes that to `App.jsx:263`'s `handleShowOnMap`, which opens the
   **frozen Plan-tab overlay** (`MapOverlay` + the pre-v2 `MapView` fork) rather than the Map tab. The
   only route to the actual tab today is the overlay's own hatch (`MapOverlay.jsx:181`, "Open the full
   Map tab →" → `App.jsx:324` `openFullMapTab`). So D3 is a **re-pointing**, and it takes one entry
   point away from the overlay. The overlay keeps every other producer (`WindowPickDialog`'s two
   actions at `:1924–1935`, `HeatmapGrid`, `WindowComingUpEntry`, `SlotLocationName`,
   `WindowFirstRegionalPanel`, the aurora banner) — convergence of the rest is map-tab-v2-plan.md's
   **O-6**, not this plan's. Recorded as decision **Q3** in §6, with a recommendation.

4. **The handover channel already exists, nonce-guarded, and the plan extends it rather than adding a
   second.** `App.jsx:302` `tabRequest {id, nonce}` + `:319` `mapTabHandoff` (the Map tab's own
   channel, deliberately not the overlay's — read the ⚠️ at `:307–318`: the pane is never unmounted,
   so a hidden map that acts on a handoff nobody sent it raises a phone `BottomSheet` over the Plan
   tab). The shell's `tabRequest` effect (`WindowFirstShell.jsx:636–672`) routes through `selectTab`
   (which tears every dialog down) and moves focus to the tab with a deferred `requestAnimationFrame`.
   `WindowFirstMapPane.jsx:290–295` forwards the handoff as five `handoff*` props, and `MapView` applies
   them in four separate effects (`:1296–1370`) whose doc comments record two real staleness defects
   from applying fields independently. **The reverse route is the template**: `App.jsx:354`
   `openLocationSheet` + `WindowFirstShell.jsx`'s sheet-handoff effect +
   `test/WindowFirstShellLocationSheetHandoff.test.jsx`
   ("lands as the ONLY dialog layer", nonce replay guards, "never leaves focus stranded at `<body>`").

5. **A window's identity is `card.key` = `` `${date}:${targetType}` ``, and the map already resolves
   it on its own side.** `utils/heatSpots.js:53` `windowKey(date, targetType)`; `heat.windows[].key`
   carries it (`WindowFirstMapPane.jsx:202`); `utils/mapEvents.js:393` `findEvIndex(events, eventType,
   date)` is the map's own resolver and `MapView.jsx:2322` derives `activeEvIndex` from it on every
   render — **there is no EV index in state at all** ("the pane owns the key, not a duplicate",
   `mapEvents.js:382–387`). The increment's "map the window index on the map's side" therefore
   translates to: the doors send `{date, targetType}` and nothing else about the window; the map sets
   `eventType` and `selectedDate` and `findEvIndex` does the rest. An EV *index* must never cross the
   seam, and neither must a Plan *index* — the app has no such thing (`buildWindowMatrix` positions
   cards by date and slot, `utils/windowFirstMatrix.js:85`).

6. **The Plan tab's defaults are not "4★+ within 2h30".** The rating floor defaults to *Any*
   (`utils/ratingLens.js:39` `ANY_RATING_ID`, `minRating: null`; persisted, no expiry). The reach tier
   is **day-derived** (`utils/reachLens.js:57–60`: `45` on a weekday, `150` at the weekend), overridden
   to `90` under an away origin (`planOrigin.js:48` `AWAY_TIER_ID`), stamped with the day in storage,
   and pinned to *Any* for LITE. The map's own floor defaults to **3★** (`MapView.jsx:1065`
   `DEFAULT_MIN_STARS`, persisted under `mapFilterMinStars`) and its drive filter to *Any* (`0`). The
   two tier vocabularies coincide — `FiltersPopover.jsx:298` `DRIVE_TIME_TIERS` `[0,45,90,150]` and
   `REACH_TIERS` `[45,90,150]+any` both label through `formatDriveDuration` — so `limitMinutes` maps
   to `driveTimeFilter` directly (`null → 0`). **The rating vocabularies do not coincide.** The map's
   floor is a true "this and above" control that always holds a value in 1–5 — **no Any state**, a
   deliberate P7 decision (`FiltersPopover.jsx:20–26`, `MapView.jsx:1062–1070`), with
   `STAR_THRESHOLD_LABELS` (`:2170`) keyed 1–5 only — and unrated locations are gated separately behind
   the admin-only `showUnrated` toggle (`:1941`), never by the floor. So `minRating: null` cannot be
   written into `minStars`: it would index an undefined summary label and leave the filter in a state
   the popover cannot draw. The exact equivalent exists, though: the Plan's Any admits every **rated**
   spot and drops unrated ones (`gateSpotsByRating`, `ratingLens.js:158–161`), which is precisely the
   map's **1★+** with `showUnrated` untouched. The consequence the increment did not have to face: a
   Plan carrying *Any rating* arrives at a map whose persisted floor is 3★, and honouring the carry
   **loosens** it to 1★+. §5 records the rule (carry it as 1★+, name only the narrowings, `clear`
   restores the map's own default). Found by the Codex review of the plan PR (#758), which caught the
   plan referring to a map value that does not exist.

7. **Region focus is a home-origin concept on the Plan tab.** `WindowFirstShell.jsx:857`
   `selectedRegion: origin ? null : focusedRegion` and `singleRegionScope: Boolean(origin)` — away, the
   rail is withheld because the origin has already answered "which region". So Door 1 carries a region
   only at home; away, the map's own `scopeSpots(heatSpots, reachById, origin)`
   (`WindowFirstMapPane.jsx:174`) already frames the origin's region and nothing further needs to be
   sent. The increment's "drops out of *my area* scope if that region sits outside it" is exactly
   `MapView.jsx:2442` `jumpToRegion` ("a jump is honest; a no-op is not"), which the door reuses —
   **not** the overlay-era `handoffRegion` effect at `:1389`, which fits bounds without touching scope.

8. **Both label placers already have the obstacle mechanism the increment describes, and neither has
   a top-right control today.** The popup field (`WindowRowFieldMap.jsx:647–748`) builds ONE `boxes`
   array in priority order — hint corner (`HINT_BOX`, a constant, `:160`), unscored corner, ring labels,
   home, region labels, chips — through `fits` (`:226`, with the WCAG 2.5.8 24px centre-separation
   test that applies only against boxes flagged `target: true`) and `placeWithNudges`
   (`utils/labelPlacement.js:90`). The Map tab seeds live rects from `OBSTACLE_SELECTOR`
   (`MapLabels.jsx:49–61`) via `seedObstacles` (`labelPlacement.js:131`). The only top-right thing on
   the popup field is nothing; `.wf-mapbox` has `line-height: 0` (`index.css:2031`), which any new
   text child must override (plan-matrix M4's `font: inherit` lesson applies).

9. **No test in the suite uses `document.elementFromPoint`**, and jsdom cannot lay anything out.
   Overlap is pinned structurally by stubbing `offsetWidth`/`offsetHeight`
   (`test/WindowRowFieldMap.test.jsx:679–687`, `:1311–1327` — the hint-corner test is the template
   for a seeded-control test). The increment's check 5 (sample `elementFromPoint` across each chip's
   width, in every window) is therefore a **browser** check, scripted in Playwright
   (`frontend/playwright.config.js`, `testDir: ./src/test/e2e`, baseURL 5173) — §7.

10. **The card is a `<button>`, so the prototype's Door 3 markup is invalid HTML in the app.**
    `WindowFirstHeatStrip.jsx:1061–1100` renders each cell as `<button class="wf-hc" style={place}>`
    (`place` carries the grid coordinates); a `role="button"` inside it is interactive content inside a
    button, which browsers and screen readers do not honour. §3 D5 places the glyph as a **sibling
    grid item in the same cell** instead, which also removes the need for `stopPropagation`.

11. **The masthead already states the origin on the Map tab.** `MastheadTickLine.jsx:194–207` renders
    a non-interactive statement (`Home · DH3 4NG` or `Lake District · from Keswick`, caption `drive
    times from here`) on the Map tab only. The breadcrumb's origin clause is therefore a second
    statement of the same fact. Kept, because the increment's reasoning ("origin is listed first,
    because it is the fact that changes every number") is about the *carrying* clause, and the crumb's
    `clear` is the control that resets it; the masthead has no such control on the Map tab. Recorded
    as §4 #3.

12. **Every map corner is claimed, and the phone makes the top-left full-width.** `wf-map-chrome-tl`
    (the window control) is `top:8px; left:60px` on desktop and `left:8px; right:8px` at ≤639px
    (`index.css:2862`, `:2938–3080`); the phone lifts every bottom element into measured rows whose
    pairwise disjointness `test/mapPhoneChromeCascade.test.jsx:538–608` pins. A floating breadcrumb
    would need a new corner, a `:has()` push on the window control, entries in `OBSTACLE_SELECTOR` and
    in the callout's `BAND_BAR_SELECTOR`, and a row in the phone sweep. The plan instead mounts the
    crumb **outside the Leaflet frame** (§3 D2), which satisfies the increment's seeding rule
    vacuously — nothing is drawn over the field — and is deterministic on the phone.

---

## §2 Strategy

- **Extend, never fork.** One accessor (`driveMinutesFor`), one handover channel (`mapTabHandoff` +
  `tabRequest`), one resolver (`findEvIndex`), one obstacle array per surface. Every phase reuses an
  existing seam; the only new component is the breadcrumb.
- **Origin is not carried — it is shared.** The payload deliberately omits it. Sending `origin` when
  the map reads the context's own would be the increment's `org`-in-the-URL mistake in reverse: a
  parameter that *looks* load-bearing and is not. The breadcrumb reads the context, so it can only ever
  name the origin actually in force.
- **The breadcrumb is a true statement, derived, never stored.** Each carried axis shows while the
  map's state still equals the carried value and drops out when the reader changes that axis on the
  map (§5 rule 3). The prototype prints the URL's facts regardless (`map-tab-v2.js:516–518`), so it
  can claim `4★+` after the reader has moved the floor — an honest-claim defect of exactly the class
  the increment exists to prevent.
- **Nonce-guarded, one effect.** The door payload is applied by ONE nonce-keyed effect in `MapView`,
  not by adding fields to the four existing per-field effects — those effects' own doc comments record
  the staleness defects of applying fields independently (`MapView.jsx:1331–1360`).
- **Door 3 is not built.** The owner dropped it at plan time (§6 Q1); the D5 phase below is retained
  as a record of how it would have been built, not as work.
- **Sonnet sessions, one phase each, review before commit.** The builder stops before committing;
  the adversarial review runs on the working tree (read-only agents); surviving findings are fixed;
  then the commit. §8 is the session map, `plan-to-map-doors-prompts.md` the kickoff prompts.

---

## §3 Phases

### D1 — One drive accessor, and home geography only at home — S/M

**Increment steps 1 and 2.** No user-visible change at home; under an away origin the Map tab stops
drawing DH3 4NG's marker and rings beside Keswick's drive times, and its jump list, filters and ring
labels read the origin's matrix.

1. **Make `driveMinutesFor` the tab's single reader.** In `MapView.jsx`:
   - `jumpDriveMap` (`:2406`) must be derived from the same precedence the accessor uses — not a
     second expression. Cleanest: build the jump map from `driveOverride ?? reachById` **only if** the
     accessor itself moves onto `reachById` (next bullet); otherwise expose the accessor's source as
     one memoised `Map` and hand that to `buildJumpRows`. Either way there is one place that answers
     "which map is in force".
   - **Unify the home source.** Verify in the backend that `GET /api/user/settings/drive-times`
     (`UserSettingsController.java:142`) and `GET /api/user/settings/reach` (`:162`, `ReachService`)
     read the same `user_drive_time` rows. If they do — expected — the **tab** stops fetching
     `getDriveTimes()` itself and reads minutes from the `reachById` prop the pane already passes
     (`WindowFirstMapPane.jsx:310`), so the map and the Plan cards cannot disagree about a drive. ⚠️
     The **overlay** mount (`App.jsx:604`) is outside `WindowFirstBriefingProvider` and receives no
     `reachById`; it is frozen and keeps `userDriveTimes` — gate on `overlayMode`, and say so in the
     accessor's doc block. Two sources split by *surface* is acceptable; two sources on one surface
     is the defect.
   - `mapReachMeasured` (`:2077`) becomes "the map in force has at least one finite `driveMinutes`",
     and at home additionally requires `homeCoords` (its existing reason: the ring labels). Under an
     away origin with a measured matrix it must read **true**, so the Filters popover's drive segment
     and the callout's duration wording are offered. Extend `test/MapViewReachMeasured.test.jsx`
     with the away case, both directions (measured matrix → true; empty matrix → false).
   - Read the survey table in §1 #1 as the checklist; every row marked ✅ there must still go
     through the accessor after the change (a grep for `userDriveTimes`, `reachById?.get`,
     `driveOverride` outside the accessor pair is the audit).
2. **Gate home geography on the origin.** `MapView` gains an `origin` prop (`{id, name, baseName}` or
   null; the pane passes the context's; the overlay never passes one). Derive once:
   `const homeGeo = origin ? null : homeCoords`, and feed `homeGeo` to `MapHeatLayer`, `MapLabels`,
   `PinsLayer` and the legend's `hasHome`. ⚠️ `CentreOnHomeControl` keeps `homeCoords` for its
   *presence* test but its action is `resetToMyArea`, which under an away origin refits to the
   origin's region (`heat.areaBounds` is scope-framed) — correct behaviour, misleading name; leave the
   control, add one sentence to its doc block, and do not surface a "set your postcode" prompt from it
   while away. `heat.beyondRegionNames` stays home-only (§1 #1, row 4) — record it in the accessor's
   doc block as the one deliberate home-only read.
3. **Tests.** `MapViewDriveOverride.test.jsx` gains: jump rows read the override map when away and
   the per-user map at home; no HOME label, no ring, no ring label and no legend rings toggle while
   away (each asserted as a `queryBy* → null`, and its home counterpart as present); the ⌂ control
   still present while away. `regionsJump.test.js` needs no change unless `buildJumpRows`' input shape
   moves. A **negative** test that the overlay still reads `userDriveTimes` (render with
   `overlayMode` and no `reachById`, assert a drive figure renders).
4. **Browser (§9).** Set an away origin on the Plan tab (needs a region with a base and a filled
   matrix — §9), switch to the Map tab: the masthead statement names the base, every callout Drive
   and leave-by measures from it, the jump list's distances change, no HOME dot, no rings, and the
   legend panel offers no rings toggle. Return home: everything comes back.
5. **Ledger.** Append the phase-log row; add §4 entries for anything that had to differ.

### D2 — The handover payload, the breadcrumb, `clear` and the return trip — M/L

**Increment steps 3 and 4, plus the return trip under §6 Q2's default.** Nothing user-visible until D3
opens the first door, but everything D3 and D4 need lands here, testable through a test-only caller.

1. **The payload.** `App.jsx` gains `openMapTabFromPlan(door)` beside `openFullMapTab`:
   ```js
   // door = { date, targetType, region: ?string, minRating: ?number, limitMinutes: ?number,
   //          locationName: ?string }
   setSelectedDate(door.date);                       // the same call handleShowOnMap makes
   tabRequestNonce.current += 1;
   setMapTabHandoff({ source: 'plan', eventType: door.targetType, date: door.date,
     region: door.region ?? null, minRating: door.minRating ?? null,
     limitMinutes: door.limitMinutes ?? null, locationName: door.locationName ?? null,
     nonce: handoffNonce.current++ });
   setTabRequest({ id: 'map', nonce: tabRequestNonce.current });
   ```
   Passed to the shell as `onOpenMapTab`, **withheld when `allDates.length === 0`** (the same rule
   that withholds `mapPane` and `onOpenFullMap`: a door onto no map is what §6 of the matrix plan
   bans). The shell exposes one internal `openMapTab(door)` that does `openOverPopup(null);
   openWindow(null); onOpenMapTab?.({ ...door, minRating: ratingLens.minRating, limitMinutes:
   reachLens.tier.limitMinutes })` — close-then-move, the order every existing map route already
   uses (`WindowFirstShell.jsx:1890–1935`), and the lens values read at the moment of the tap. Doors
   render only while `onOpenMapTab` is defined. **Origin is not in the payload** (§2).
2. **Applying it.** `WindowFirstMapPane` forwards a door handoff as ONE prop, `planHandoff`
   (`handoff?.source === 'plan' ? handoff : null`), and keeps forwarding the hatch's handoff exactly
   as today for `source !== 'plan'` — the hatch path is tested and frozen with the overlay. In
   `MapView`, ONE `useEffect` keyed on `planHandoff?.nonce`:
   - event: `setEventType`, `setUserHasOverriddenEvent(false)`, `setLocalNightDate(null)` — the same
     three writes `:1296–1308` makes, for the same reasons;
   - filters: `setMinStars(minRating ?? 1)` with the matching `writeMapFilter` — **1, never null**:
     the map has no Any state (§1 #6), 1★+ is its loosest floor and the exact equivalent of the
     Plan's Any (every rated spot); `showUnrated` and `showStandDown` are NOT touched, because the
     Plan's Any does not admit unrated spots either; `setDriveTimeFilter(limitMinutes ?? 0)`;
   - region: `region ? jumpToRegion(region) : resetToMyArea()` — the tab's own semantics (scope
     flip, `animate:false` refit, menu closed), never `FitBoundsController`;
   - location: `setSelectedLocationName(name)` + `setFlyTarget({lat, lon})` as `:1375–1386` does
     (the callout resolves off the full roster, `:1974–1988`, so a location the carried floor filters
     out still gets its callout — say so in the test name).
   The date needs no write here: `selectedDate` arrived through App and `findEvIndex` derives the
   row. Assert in `test/mapEvents.test.js` that a fixture with astro rows interleaved resolves
   tomorrow's sunset to the row whose id is `solar:<date>:SUNSET` — the app's form of the increment's
   "an EV index that differs from the Plan index" check (§7 #1).
3. **The breadcrumb.** New `components/map/MapBreadcrumb.jsx`, mounted by `MapView` **above the map
   frame, outside it** (§1 #12), tab-only (`!overlayMode && planHandoff`). Content, in order:
   `← Plan` (a `<button>`; the arrow `aria-hidden`), `/`, the active row's `dayLabel` in bold and
   its kind word (`Tonight sunset`), then `carrying` + the facts that still hold (§5 rule 3), joined by
   `·`, then `clear`. Copy per the increment: `drive times from Keswick` · `4★+` · `within 2h 30` ·
   `Lake District`. Accessible name traps: separators stay inside text nodes; JSX drops
   whitespace-only text between tags, so sibling spans need a literal `{' '}`
   (`WindowFirstComingUpHandoff.jsx`'s own note); the crumb is a `<nav aria-label="Where you came
   from">`, not a `role="status"`. Styling from the prototype (`Map Tab v2.html:248–261`): mono
   10.5px, `--ink-2`, gold `← Plan`, teal `clear`; phone wraps the carrying clause onto its own line.
   The frame below must shrink by the strip's height — check the `flex-1 min-h-0` accounting
   (`App.jsx:556`'s footer-overflow note is the trap) at 1280×800 and 390×844.
4. **`clear`** mirrors the prototype's `crumbclr` (`map-tab-v2.js:526`) exactly: rating → the map's
   own default (`DEFAULT_MIN_STARS`, storage cleared), reach → 0, scope → `resetToMyArea()`, origin →
   `onClearOrigin()` (pane passes `() => setOrigin(null)` from the context). **Not** subjects and
   **not** dark-sky — the prototype leaves them alone and they were never carried. ⚠️ Clearing the
   origin resets it for the Plan tab too, because there is one origin (§4 #4). The crumb stays after
   `clear` (it is the way back); only its carrying clause empties.
5. **`← Plan`** → App `returnToPlan()` = `setTabRequest({id:'plan', nonce})`. **Default: the plan
   itself, no dialog reopened** (§6 Q2 — DECIDED, the default stands) — so the payload carries no
   window key, per the increment's own "do not send a parameter nothing reads". Should that ever be
   reopened, the change is: add
   `windowKey` to a `planWindowHandoff` channel modelled on `locationSheetHandoff`, and the shell's
   effect calls `openWindow(key)` after `selectTab('plan')`. Focus follows the request the way the
   two existing effects do (`requestAnimationFrame(() => tab.focus())`).
6. **Tests.** `WindowFirstMapPane.test.jsx`: a `source:'plan'` handoff reaches `MapView` as
   `planHandoff` and a hatch handoff does not. A new `MapViewPlanHandoff.test.jsx`: each field applied
   (event, floor incl. `null → 1` with `showUnrated` still false, tier incl. `null → 0`, region jump incl. the scope flip, location
   selection with a filtered-out location); the nonce replay guard (same payload twice → applied
   twice; same nonce → once); a hidden pane (render, hand a handoff, assert nothing when
   `source !== 'plan'`). `MapBreadcrumb.test.jsx`: every clause present/absent by fixture; the
   derived-truth rule (move the floor → the `★` clause disappears, the rest stay); `clear` calls each
   reset once and in order; accname of `← Plan` and `clear`; the `queryBy → null` half for the
   overlay and for no handoff. `App`-level: `openMapTabFromPlan` sets the date and both nonces
   (extend whatever pins `openFullMapTab` today — `WindowFirstShellTabs.test.jsx` and
   `MapOverlay.test.jsx` are the neighbours).
7. **Browser.** Drive the payload from the console (`window.__photocastOpenMapTab` is NOT to be added
   — use a temporary test-only door in a scratch branch, or wait for D3). Measure: strip height,
   frame height before/after, no horizontal scroll at 390px, the crumb's contrast (≥4.5:1 on
   `--ink-2`, per the bundle's own panel-ink rule).

### D3 — Door 2: re-point the location sheet's `◍ Show on map →` — S

**Increment step 5.** The button, its copy and its accname stay byte-identical; only its destination
moves. §6 Q3 — DECIDED yes, 2026-09-04.

1. `WindowFirstShell.jsx:1899–1903`: the sheet's `onShowOnMap` becomes
   `openMapTab({ date, targetType, locationName: name, region: null })`. The popup and the sheet
   already close first (D2's `openMapTab` does it). The `onShowOnMap` prop stays on the shell for
   every other producer.
2. Withholding: when `onOpenMapTab` is undefined the sheet must render `location-sheet-nomap`'s
   sentence, not a dead button — pass `onShowOnMap` to the sheet only when the door exists, and pin
   it (the sheet already renders the note when `handoff` is null; extend that branch's test).
3. **Tests.** `WindowFirstShellSheet.test.jsx:576` (currently `expect(onShowOnMap).toHaveBeenCalledWith(TODAY,
   'SUNSET', 'Bamburgh Beach')`) and `locationSheetShell.test.jsx` move to the new callback with the
   full door shape, and add the ordering assertion the M4 tests use (sheet and popup closed *before*
   the callback). `LocationFourDaySheet.test.jsx` unchanged unless the prop gating moves.
4. **Browser (§7 checks 1–3).** From a location sheet opened over the popup, on a window after the
   first night: the map opens on that window with the location's callout up, the crumb names the
   lens facts, and — from an away origin — the callout's Drive reads from the base.

### D4 — Door 1: `◍ Open in map →` on the popup's field, seeded as an obstacle — M

**Increment step 6, and the increment's own defect.**

1. **The button.** In `WindowRowFieldMap.jsx`, inside `.wf-mapbox` after `.wf-mchips` and before
   `.wf-mhint`: `<button type="button" data-testid="wf-row-map-open" className="wf-mopen"
   ref={openRef} onClick={onOpenInMap}>` with `<span aria-hidden="true">◍ </span>` and ONE text node
   `Open in map →` (the sheet footer's accname lesson, `LocationFourDaySheet.jsx:511–518`). Rendered
   only when `onOpenInMap` is a function. CSS from `Plan Tab with Heat v5.html:371–373`
   (`position:absolute; right:7px; top:7px`, mono 9.5px, dark plate, gold hairline; phone `7px 10px`
   / 10px) — and `line-height: 1.2` explicitly, because `.wf-mapbox` is `line-height: 0`. Focus ring
   per the file's existing 2px bone rule.
2. **The seed — measured from the live element, `target: true`.** In the placement effect, after the
   two corner constants and before the ring labels:
   ```js
   const ob = openRef.current;
   if (ob && ob.offsetWidth > 0) {
     boxes.push({ ...mkBox(ob.offsetLeft, ob.offsetTop, ob.offsetWidth, ob.offsetHeight), target: true });
   }
   ```
   `target: true` because it is a control: the 24px centre-separation test must hold between it and
   every chip (the hint corner is a decoration and is not flagged; `:729–733` says why). Add
   `Boolean(onOpenInMap)` to the effect's dependency list. ⚠️ Do not turn `HINT_BOX` into a
   measurement to match — its doc block (`:151–159`) records why a constant is right for a fixed
   9px string; the button's copy is the thing that can change.
3. **Wiring.** `WindowSheetDialog` gains `onOpenInMap` (optional) and passes it through; the shell
   passes `() => openMapTab({ date: card.date, targetType: card.targetType, region:
   field.selectedRegion ?? null })`. The field's click gesture (`handleClick`, region pick) is
   untouched, and the hint stays bottom-left.
4. **Tests.** `WindowRowFieldMap.test.jsx`: (a) no prop → no button and the `boxes` array is the
   same as before (pin by a chip anchored top-right placing where it did); (b) with the prop, stub the
   button's `offsetLeft/Top/Width/Height` to a top-right rect, anchor a chip under it, assert the chip
   flips or drops and never overlaps — template `:1311–1327`; (c) a chip whose centre is within 24px
   of the button's box is dropped (the `target` flag); (d) accname `Open in map →` exactly, `◍`
   absent. `WindowSheetDialog.test.jsx`: the prop reaches the field map; absent → no button.
   Shell: close-then-move ordering, and the region carried is `field.selectedRegion` (null away).
5. **Browser — the increment's check 5, scripted.** Playwright, `src/test/e2e/door1-obstacles.spec.js`:
   sign in; for each of the six matrix cards, open the popup, wait for the chips, and for every
   `.wf-mchip` sample `document.elementFromPoint(x, cy)` at every 2px across the chip's width — the
   hit must be the chip or its descendant. Run at 1280×800 and 390×844. Report the six-window table in
   the phase log (the defect appeared in 4 of 6, so one window proves nothing). This needs a seeded
   DB with ratings on ≥ 6 windows (§9).

### D5 — Door 3: the thumbnail glyph — DROPPED, not built

**Owner decision 2026-09-04 (§6 Q1): drop.** No session runs this phase and there is no D5 prompt.
The design below is kept only so the reasoning is not rediscovered if the glyph is ever revisited;
its structural finding (§1 #10 — a control cannot nest inside the card's `<button>`) is the part
worth keeping.

1. **Structure.** A sibling grid item, not a nested control (§1 #10). After each non-away card,
   render `<button type="button" data-testid="wf-heat-tomap" className="wf-hc-tomap" style={place}>`
   with `<span aria-hidden="true">◍</span><span className="sr-only">Open {card.label} on the
   map</span>` — real hidden text, not `aria-label` (the house rule: an `aria-label` replaces content;
   here there is no visible text to keep in the name, but the sr-only span keeps the pattern one
   convention). Same `place` → same grid cell; `justify-self: end; align-self: start;
   margin: <top-of-thumbnail offset>` puts it on the thumbnail's top-right; `z-index` above the card;
   `pointer-events` only on itself. No door on an away cell (it is a `<div>`, not a control — matrix
   rule 14) and none when `onOpenMapTab` is undefined.
2. **Reveal.** Desktop: `opacity: 0`, shown on `button.wf-hc:hover + .wf-hc-tomap`,
   `.wf-hc-tomap:hover`, `.wf-hc-tomap:focus-visible`; the card's `translateY(-2px)` lift applied to
   the glyph on the same hover so the two move together; phone (`≤639px`) always visible at 40px.
   Sizes per the prototype: 34px desktop, 40px phone (`Plan Tab with Heat v5.html:376–379`). Both are
   under 44px and above WCAG 2.5.8's 24px; that is the fact §6 Q1 turned on.
3. **Wiring.** `WindowFirstHeatStrip` gains `onOpenInMap(cardKey)`; the shell passes
   `(key) => openMapTab({ date, targetType, region: null })` from the card's descriptor. No
   `stopPropagation` is needed — the glyph is not inside the card — but the check stays as a test.
4. **Tests.** `WindowFirstHeatStrip.test.jsx`: glyph rendered per non-away card, none on away cells,
   none without the prop; pressing it calls `onOpenInMap(key)` and **not** `onOpenWindow`; the
   sr-only name; Enter/Space work because it is a real button. A CSS-slicer test (the
   `mapChipFlipCascade.test.jsx` pattern) pinning that the reveal rule exists for hover, sibling-hover
   and focus-visible, and that the phone rule sets `opacity: 1`.
5. **Judge it.** This was to be a keep-or-drop PR with screenshots; the owner dropped it unbuilt.

### D6 — Sweep and docs — S

1. Reconcile §4 against what actually shipped (renumber against `origin/main`'s tail at rebase time,
   then grep your own cross-references — the map series' ledger moved three times in one day).
2. CLAUDE.md: the Map tab bullet gains one sentence on the doors and the breadcrumb; the Plan tab
   bullet's dialog-stack paragraph gains the doors as a fourth route that "closes every Plan dialog
   before it opens" (it already names the cog as the precedent).
3. `map-tab-v2-plan.md` §6: O-6 gains a line that the sheet footer now reaches the tab, and O-18's
   question is now partly answered by this plan's return-trip decision.
4. Delete the Playwright spec only if the owner does not want it kept as a regression check;
   otherwise leave it under `src/test/e2e/` and say in the README how to run it (§9).

---

## §4 Disagreements with the increment, on purpose

Numbered so a phase log can cite them. Each phase appends; D6 reconciles.

1. **The origin is not in the payload (2026-09-04, plan).** The increment carries `org` and honours it
   through `driveOf`. In the app the origin is shared state the map already reads, so carrying it would
   send a parameter nothing reads — the exact mistake the increment records. Honoured by sharing, not
   by copying.
2. **The window crosses the seam as `{date, targetType}`, not as a Plan window index.** The app has no
   Plan index and no EV index in state; `findEvIndex` on the map's side is the increment's "one source
   of truth for the mapping". Equivalent, and strictly less state.
3. **The breadcrumb sits above the map frame, not over it.** The increment's rule is "anything drawn
   over a field must be seeded as an obstacle"; the app draws the crumb over nothing. Consequences
   the increment could not see: no `OBSTACLE_SELECTOR` entry, no callout-band bar, no phone-stack
   row, no `:has()` push on the window control; the frame is ~34px shorter while the crumb shows. The
   masthead already states the origin on this tab (§1 #11); the crumb states it again because the
   crumb's `clear` is the control that resets it.
4. **`clear` resets the origin for the whole app, not for the map only.** There is one origin
   (`WindowFirstBriefingContext`), and the masthead is "the ONLY statement of where the plan is
   computed from". The prototype could not show this because its tabs are separate documents. The
   Plan tab's reach default follows the origin home (`useReachLens`'s override), as it does from the
   masthead's own ⌂.
5. **The carrying clause is derived from the map's live state, never stored.** The prototype prints
   the URL's facts even after the reader has changed them on the map. Here an axis shows only while the
   map still holds the carried value (§5 rule 3), so the crumb cannot assert a filter that is no longer
   in force.
6. **Rating and reach carry the Plan's actual lens values, which default to Any / a day-derived tier,
   not to 4★+ / 2h30.** The increment's example is a snapshot of the prototype's fixture; the app
   carries whatever the reader had. A carried *Any* rating lands as the map's **1★+** (the map has no
   Any state and the Plan's Any admits only rated spots, so the two are equivalent — §1 #6), which
   loosens a map whose persisted floor is 3★; the crumb names only narrowings, and `clear` restores
   the map's own default (§5 rule 2).
7. **Door 2 is a re-pointing, not a wiring** (§1 #3). The overlay loses the sheet footer as an entry
   point and keeps every other one; that is a step towards O-6, not its completion.
8. **Door 3 would have been a sibling grid item, not a control nested inside the card button**
   (§1 #10) — moot since the owner dropped the door (§6 Q1), recorded because the increment's own
   markup is invalid HTML in this app and the next thumbnail control will hit the same wall.
9. **The increment's `elementFromPoint` check is a Playwright script, not a Vitest test** (§1 #9),
   and the jsdom pin is structural (a stubbed rect and a chip anchored under it).
10. **The overlay's hatch does not raise the breadcrumb.** It is also a Plan→Map route, but its
    payload carries `filterAction`/`darkSky` facts this crumb has no vocabulary for, and the overlay is
    frozen. Recorded as **O-D3** in §6 rather than half-done.
11. **The map keeps its no-Any rating floor; the Plan's Any lands as 1★+ (2026-09-04, Codex review
    of #758).** The first cut of this plan wrote "the map's own Any value", which does not exist —
    the floor is always 1–5 by a P7 decision and unrated locations are admin-gated separately. Adding
    an Any state would reopen that decision for one handoff; 1★+ is semantically identical to the
    Plan's Any (rated spots only) and already has a label, a chip, and a persisted form.
12. **The door marks its window as the reader's own choice (`userHasOverriddenEvent = true`), where
    the hatch's effect sets it `false` (2026-09-04, D2 review):** the door-landing effect's first cut
    copied the hatch's `handoffEventType` effect verbatim, including its `false`. The auto-event
    effect (`MapView.jsx`, keyed on `[autoEventType, userHasOverriddenEvent]`) re-applies
    `autoEventType` whenever the flag is false — so for a reader who had ALREADY used the window
    control before opening a door (flag already `true`), the door's own `false` write was a genuine
    true→false CHANGE, which re-ran the auto effect on the next commit and silently pulled the event
    back onto `autoEventType`. The door landed on the wrong window, for exactly the reader who had
    shown the most intent. A reader who had never touched the control (flag already `false`) saw no
    change and no re-run, which is why this passed every test and the browser verification once
    already — the defect is conditional on session history, not visible on a fresh mount. A door
    names its window explicitly, the same as a manual press of the window control (`selectEvRow`,
    which already sets this flag true) — so it takes the SAME value, not the hatch's. See **O-D6**
    (§6) for the hatch's own version of this hole, which is pre-existing and out of scope here.

---

## §5 Decisions taken in this plan (challenge in review, not in code)

1. **Every door carries the lens.** Doors 1 and 2 both carry `minRating` and `limitMinutes`
   (increment "What travels" #3). Door 1 additionally carries the popup's region focus; Door 2 the
   location.
2. **Carry *Any* as 1★+.** A Plan lens at Any writes `minStars = 1` (loosening a persisted 3★ if
   there was one) so the two sets agree — the map has no Any state and must not grow one for this
   (P7's "always a value" decision stands), and 1★+ with `showUnrated` untouched is exactly what the
   Plan's Any admits (§1 #6). The crumb does not name it (there is nothing to act on); the Filters
   chip states `1★+` as it always states the floor; `clear` restores `DEFAULT_MIN_STARS`. The
   alternative — leave the map's floor alone when the Plan carries Any — would show a thinner set
   than the one tapped through from, which is the failure the crumb exists to prevent.
3. **A carried axis is shown while it still holds.** `origin` clause ⇔ `origin != null`; `★` clause ⇔
   `minStars === carried.minRating` and carried is non-null; `within` clause ⇔ `driveTimeFilter ===
   carried.limitMinutes` and carried is non-null; region clause ⇔ the carried region's jump is the
   scope in force (`jumpFitOverride` names it, or `!heatArea` after the flip); the window clause is
   always live (the active row). When no clause holds the `carrying` group is omitted entirely.
4. **The crumb persists until `← Plan`, a new handoff, or a sign-out.** Leaving the map by the tab bar
   and coming back keeps it — it is still a true statement about the map's state, and the reader can
   `clear`. Ending it on tab-bar leave needs a shell→App callback the shell does not have; **O-D1**.
5. **Copy is the increment's** (`◍ Open in map →`, `carrying …`, `clear`, `← Plan`), with one
   flag for review: the map's own callout says `Open in Plan` with the tab's capital; `Open in map`
   follows the increment. Either is defensible; the plan does not change the spec's string.
6. **Door 3 is not built** (§6 Q1). No feature flag, no `hidden` attribute, no half-state — and no
   dead code either: nothing of D5 lands.

---

## §6 Owner decisions / OPEN items

**All three decided by the owner, 2026-09-04, before any phase started.**

- **Q1 — Door 3, keep or drop? DECIDED: drop, unbuilt.** The increment asked for it to be built and
  judged (34px desktop hover-revealed, 40px phone always-on — under the 44px guidance, above WCAG
  2.5.8's 24px). The recommendation was to drop: doors 1 and 2 are where the question "where in this
  window?" is actually asked, and a hover-revealed control on a card that already opens a dialog is a
  second meaning on the surface the increment itself warns against elsewhere. The owner agreed without
  needing the build. D5 is retained as a record only.
- **Q2 — the return trip: reopen the window sheet, or land on the plan? DECIDED: the plan itself.**
  The increment's own argument (a round trip through the map leaves a modal over the answer you went
  to check) and the "do not send a parameter nothing reads" rule both point there. The Map→Plan door
  lands the *location* sheet as the only layer, but that sheet is the destination of that door, where
  here the destination is the plan. The payload carries no window key.
- **Q3 — Door 2 re-points the sheet footer from the frozen overlay to the Map tab. DECIDED: yes.**
  The increment's entire purpose is a route into the tab, the overlay's own hatch already reads "Open
  the full Map tab →" (i.e. the overlay is a waypoint), and O-6 names convergence as the overlay's
  destination. Consequence accepted: two buttons reading "Show on map" go to different surfaces until
  O-6 (the sheet footer → tab; `WindowPickDialog`'s → overlay).

**Open items — none block calling the series complete; this is what survives it:**

- **O-D1** End the crumb when the reader leaves the map by the tab bar (needs a shell→App
  `onTabSelected`).
- **O-D2** Closed with Q2: no dialog is reopened on the return trip, so the location-over-window
  stacking question never arises.
- **O-D3** Raise the breadcrumb for the overlay hatch's handoffs too (needs crumb vocabulary for
  `filterAction`/`darkSky`; §4 #10).
- **O-D4** Base coordinates for regions (`regions.base_lat/base_lon` already exist, V145) would let
  BOTH tabs draw an away-origin marker and rings; the increment says "both tabs should gain the marker
  together". Out of scope here; if taken, the popup field (`homePoint`) and the tab (`homeGeo`) gain it
  in one phase.
- **O-D5** The map's ⌂ control under an away origin refits to the base's region but is named for
  home (D1 task 2).
- **O-D6** The overlay hatch's `handoffEventType` effect still sets `setUserHasOverriddenEvent(false)`
  — the SAME conditional-on-session-history hole §4 #12 fixed for the door (a reader who has used the
  window control, then opens the hatch, has their event silently pulled back onto `autoEventType` on
  the next commit). Pre-existing, not introduced by D2, and not fixed here — the hatch's own handoff
  shape and its overlay-frozen surface are out of scope for this plan. If ever revisited, the fix is
  the identical one-line flip this phase made for the door.

  ⚠️ **It stopped being theoretical on 2026-09-05, and the fix that day routed AROUND it rather
  than closing it.** The Map callout's `Four days here ›` peek (map-tab-v2-plan.md §4 #25, O-18)
  gave the four-day sheet a second host, and its footer's `◍ Show on map →`, pressed while the sheet
  is over the map, first shipped as a source-less payload — i.e. down this very hatch. Codex caught
  it as a P1 and it reproduced in a browser: with the map manually stepped to a sunrise window,
  `Show on map → Tomorrow sunrise` landed on `Sunset Tomorrow`. The route now names
  `source: 'map'` and rides the STRUCTURED channel with the door, where the flag is already set
  true. So the hole is now demonstrated rather than inferred, and it has one live caller left —
  the overlay's own hatch — with the same one-line flip still available.

---

## §7 Verify — the increment's six checks, and how each is measured

| # | Increment check | How | Phase |
|---|---|---|---|
| 1 | Each door lands on the correct window, one whose EV index differs from its Plan index | `mapEvents.test.js`: fixture with astro rows interleaved, tomorrow's sunset resolves to `solar:<date>:SUNSET` — ran and passing. Browser: D3 landed on Bamburgh Beach's "Tomorrow sunset" (2026-09-05) from the location sheet; D4 landed on "Tomorrow sunrise" from the popup field, scoped to the picked region — both confirmed by the window control's own reading, not inferred | D2 (unit), D3/D4 (browser) |
| 2 | Region, rate, reach and origin arrive applied; the crumb names each; `clear` resets all | `MapViewPlanHandoff.test.jsx` + `MapBreadcrumb.test.jsx` — ran and passing (5022 vitest total after D2's fixes). Browser: crumb height 34px, the map frame shrinks by exactly that amount (606.75px → 572.75px at 1280×800); home crumb read `carrying 4★+ · within 2h 30 · Northumberland & Tyneside`, away crumb read `carrying drive times from Keswick · 4★+ · within 2h 30` (no region clause, per §5 rule 3); `clear` emptied the carrying clause | D2 |
| 3 | From a Lake District plan every number measures from Keswick; no marker, no rings | `MapViewDriveOverride.test.jsx` (away → jump rows, callout drive, no HOME/rings) — ran and passing. Browser (D3, away origin): Derwentwater's sheet opened onto "Tomorrow sunrise" with the crumb reading `carrying drive times from Keswick · within 1h 30 clear` and the callout's `Drive 12 min` — the base figure, not Derwentwater's own home-arm figure, confirming the callout reads through the shared origin | D1 (unit), D3 (browser) |
| 4 | `clear` resets filters *and* origin to home | `MapBreadcrumb.test.jsx` order-of-calls — ran and passing. Browser: from an away origin, `clear` returned the masthead to its no-postcode empty state on the Plan tab; `← Plan` moved both `aria-selected` and DOM focus to the Plan tab button | D2 |
| 5 | No overlay control covers a field label — `elementFromPoint` across each chip's width, every window | Playwright `door1-obstacles.spec.js` — 12 of 12 window×viewport cells (six matrix windows at 1280×800 and 390×844), zero overlaps, re-run three times for stability; full table in D4's phase-log row. jsdom pin (`WindowRowFieldMap.test.jsx`) with a stubbed rect ran and passing | D4 |
| 6 | Door 3 does not trigger the card's own open | Not applicable — door 3 dropped unbuilt (§6 Q1) | — |

Plus, for every phase: `npm run lint && npm test && npm audit --audit-level=high && npm run build`
green on exit codes; the adversarial review's surviving findings fixed; "seen" versus "tested" stated
in the phase log.

---

## §8 Phase → session map

| Phase | Size | Depends on | Ships user-visible | Owner decision needed first | PR |
|---|---|---|---|---|---|
| D1 accessor + home geography | S/M | — | away-origin map no longer draws home geography; jump/filters read the base | — | #762 |
| D2 payload + crumb + clear + return | M/L | D1 | nothing (no door yet) | Q2 decided: plan itself | #763 |
| D3 Door 2 | S | D2 | the sheet footer opens the Map tab | Q3 decided: yes | #764 |
| D4 Door 1 + obstacle seed + sweep | M | D2 | `◍ Open in map →` on the popup field | — | #765 |
| ~~D5 Door 3~~ | — | — | dropped unbuilt | Q1 decided: drop | — |
| D6 sweep + docs | S | all | docs | — | (this PR) |

One Sonnet session per phase; kickoff prompts in `docs/engineering/plan-to-map-doors-prompts.md`.
D3 and D4 are independent of each other and may run in parallel worktrees after D2 merges. Expect a `changelog.d` file per phase and no `CHANGELOG.md` conflicts.

---

## §9 Local verification recipe

- Backend `./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local` on **8083** (or 8085+
  from a worktree — the pane supervisor restarts the launch.json backend and wins the 8083 port race);
  frontend `npm run dev`; `frontend/.env.local` with `VITE_API_TARGET=http://localhost:<port>` or every
  request 502s; `admin` / `golden2026`.
- **Ratings**: a fresh H2 has none, so no field paints and no chips place. Seed per
  `docs/engineering/heat-field-plan.md` §7.3 + `scripts/dev-seed-locations.sh` (backend STOPPED for the
  H2 file lock, then restart — startup rehydration is the only DB read — then `POST /api/briefing/run`).
  Seed ratings on **tomorrow's** windows and beyond so the fixture does not age out mid-session, and on
  ≥ 6 windows for D4's sweep. The Gate 2 honesty filter strips every slot of a region with
  `scoredLocationCount = 0`, so stale seeded dates make every per-slot field invisible.
- **Astro rows** (for §7 #1's interleave): `astroAvailableDates` needs `astro_conditions` rows — run
  the astro job or seed the table for tomorrow's night.
- **An away origin** (D1/D3 check 3): a region needs a base (`PUT /api/regions/{id}/base`, ADMIN) and
  a filled matrix — trigger `region_drive_time_refresh` from the Operations tab's Scheduler sub-tab
  rather than waiting for 03:10; then choose the region from the masthead search on the Plan tab.
- **Browser pane traps** (recorded across the map series): `requestAnimationFrame` never fires while
  the pane reports `visibilityState: 'hidden'` (focus moves are jsdom-only verifiable there), a hidden
  pane starves ResizeObservers, and the pane's responsive size can be tiny — `resize_window` to a real
  viewport before measuring. Headless Chromium via Playwright is the instrument the pane cannot be;
  `npm run test:e2e` with the dev server already up (`reuseExistingServer`).
- **Review agents never write to the working tree.** Anything that must mutate gets its own worktree;
  commit before a review that runs mutations. Mutation-test with `cp` backups, never `git checkout --`.
- **Running the D4 obstacle sweep** (`frontend/src/test/e2e/door1-obstacles.spec.js`, kept as a
  regression check, not wired into CI — §3 D4 task 5). Needs the same seeded stack as the rest of
  this recipe (ratings on ≥ 6 windows), plus a dev server the spec can reach: either let
  `npm run test:e2e` start its own `npm run dev` on the default port (`reuseExistingServer` locally
  reuses one that is already up), or point the spec at a different port with
  `PLAYWRIGHT_BASE_URL=http://localhost:<vite-port> npm run test:e2e` when the pane supervisor's own
  instance already holds 5173. Run from `frontend/`; the spec samples `document.elementFromPoint`
  across every chip's width in all six matrix windows at 1280×800 and 390×844 and reports zero
  overlaps as its pass condition (D4's phase-log table records the last measured run).
