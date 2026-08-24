# v1 Plan UI retirement — the flip, the flag, the estate, the compatibility layer, the settling commit

**Status: PLAN WRITTEN 2026-08-23 and adversarially reviewed 2026-08-23/24 (six prosecutor lenses,
41 charges, one refuter per charge — §10); the survivors are folded in below. No phase has landed.**
Written against `main` at `1281e64a` (v2.18.17). The owner's decision (2026-08-22) is the flip decision
this series executes: (1) the window-first v2 Plan becomes the default, (2) the layout toggle goes,
(3) the v1 UI and its tests are removed completely — the frontend ends **minimal yet complete**. The
owner made that call with `plan-matrix-plan.md` §11's residuals in view; this plan does not re-argue
them. Nothing here is pushed, tagged or released by the implementing session — the owner merges and
releases between phases.

### Phase log

Later phases append a row. `notes` is what a reader of a *later* phase needs and would not get
from the diff. The `commit` cell names the phase's **merge** commit on `main`, filled in after the
PR lands (a phase cannot name its own hash); until then it reads *(fill after merge)*.

| phase | branch | commit | date | notes |
|---|---|---|---|---|
| plan | `claude/v1-plan-ui-retirement-3e2398` | *(fill after merge)* | 2026-08-23 | The survey behind §3 ran as nine read-only lenses plus a completeness critic (≈2.4 M tokens, 813 tool calls); the plan was then prosecuted by six lenses (41 charges) with one refuter per charge — §10 records the tally and the two blocking finds (a surviving a11y pin inside a DELETE range; a BrandLockup instruction that trusted a docblock the tree contradicts). |

---

## 0. Process — branching, cadence, traps

**Per-phase kickoff prompts for the implementing sessions: `docs/engineering/v1-retirement-prompts.md`**
— written for Sonnet sessions (the owner's call: the judgement is encoded here at file:line
granularity, so implementation is faithful execution plus the cadence). Each prompt restates its
phase's traps inline, the stop-and-flag rule, and the review discipline (a zero-finding lens is
"not examined", never "clean"); the owner may run the D3/D4 review lenses on a stronger model.

**Branching — sequential, exactly as the brief says.** Each phase is its own branch and PR **off
up-to-date `main`**, created only after the owner has merged (and, when they choose, released) the
previous one — the same model `plan-matrix-prompts.md` defines for the M-series ("Each phase lands as
its own reviewed commit before the next session starts"). The session builds a phase through the full
cadence, commits (one commit; review fixes amended in), reports the branch name, and **stops**; the
owner merges; the next phase begins from a fresh `git fetch` (`git rev-list --count HEAD..origin/main`
must be 0 before branching). Branch names: `claude/v1-retirement-d0-flip`, `-d1-flag`, `-d2-estate`,
`-d3-compat`, `-d4-settle`. The plan doc's own commit rides the current branch
(`claude/v1-plan-ui-retirement-3e2398`) and merges before D0, since every phase edits this file's
Phase log. Do **not** build ahead on stacked branches: the owner releases between phases, which
empties `[Unreleased]` and rewrites the exact CHANGELOG lines a stacked branch would have been
written against (memory: *CHANGELOG guarantees PR conflicts*).

**CHANGELOG, per phase.** A self-contained block inserted as the FIRST entry under `## [Unreleased]`:
`### Changed — …` for D0 (a default flip) and D4 (docs + sweep), `### Removed — …` for D1–D3 (the
repo's established heading, eight precedents). Written against whatever `[Unreleased]` contains on
up-to-date `main` at branch time.

**Cadence, per phase (CLAUDE.md → *UI Work — Review Cadence*).** build → gate (§6, exit codes only)
→ adversarial review of the diff (read-only agents; paste into every reviewer's prompt: §0 + §5 + §9,
plus the phase's own inventory section — D0: §3.0; D1: §3.1 + §4.1 + §4.4; D2: §3.2; D3: §3.3 + §4.2
+ §4.3; D4: §3.4 + §2/§7 + §8 — and the diff; the whole plan only for D4's full-surface review) →
fix what survives → browser-verify where observable → commit with the CHANGELOG entry and this plan's
Status + Phase log in the same commit. **The series' final summary — whichever session writes it —
ends, verbatim to the brief, with: §2/§7's before/after file and bundle numbers; §5's ledger (every
place a deletion forced a v2-visible change — expected: none beyond the instructed rows); the §4.3
modal ruling; and the §8 follow-on list.** D4's Phase-log row points at all four.

**Traps earned this month, non-negotiable.**

1. This is the codebase's largest deletion and both of M2's post-merge CodeQL alerts came from
   deletions in classes lint cannot see. ESLint has no `semi`, so the gate gains a zero-noise
   instrument: `npx eslint --rule 'semi: [2, always]' src` (today: exactly 1 hit, in
   `CloseToHome.test.jsx`, which D2 deletes; expected 0 from D2 on) — it covers the whole ASI class,
   where the quick `grep '])$'` after a scripted hook edit covers one shape. A deletion can also
   leave a caller passing only literals, deadening every branch of the callee — sweep every deleted
   call site's callee for newly-trivial conditionals. CodeQL runs on **every PR** (`codeql.yml` has
   `pull_request:`, unfiltered by base), so check the PR's code-scanning annotations **before** the
   owner merges, and after each merge run
   `gh api 'repos/gregochr/goldenhour/code-scanning/alerts?state=open&per_page=100' --jq length`
   (the default 30 reads as a count and lies). A CodeQL job failing in under a minute is a
   build-step fault — re-run it.
2. Scripted string replaces fail silently: assert the anchor matched, or use targeted edits. A
   no-match replace edits nothing and the suite stays green.
3. The Browser pane's document is `visibilityState: 'hidden'`: ResizeObserver / IntersectionObserver
   never deliver and `requestAnimationFrame` never fires. Behavioural claims about those need headless
   Chromium via Playwright (`chromium.launch()` from `frontend/`), and every verification statement
   says which claims were **seen** and which **asserted**.
4. Review agents never write to the working tree. Anything that must mutate gets its own worktree.
5. Reverse-import search before every deletion. The checklist this plan was handed said "v1-only"
   about things that were not (the 1,331-line `usePlanLayout.test.jsx` is 95 % `WindowFirstShell`
   tests); §3 records every such correction, and two of the brief's own phase boundaries move for the
   same reason: `App.test.jsx`'s dead-branch sections are D1 work, not D2 (the file imports
   `PLAN_LAYOUT_KEY`/`PLAN_V2` from the hook D1 deletes and asserts the v1 arm's testids), and
   `planOriginShell.test.jsx` has **no** dead-branch section at all (only a `:15` comment and two
   `onExit` harness props) — the checklist's hypothesis fails against the tree.
6. The design bundles under `docs/design/` are vendored byte-for-byte and two tests pin them against
   `frontend/src/utils/heatField.js` — a "v1" grep-and-edit must never reach them. `index.css:3940`
   and `WindowFirstComingUp.jsx:11` say "v1 mock" meaning the *design bundle's* first revision, not
   the Plan arm: leave them.
7. "byte-identical" and "arm(s)" are overloaded: nine sites use "byte-identical" for a region-name
   join or payload equality, and "arms" for message / home-vs-away branches. The sweep is by
   **meaning**, never by regex; §3.3's list names the false ones, §3.3's exclusions name the true ones.

---

## 1. What this is, and what it is not

The window-first Plan (matrix + popup + tick line + location sheet, M1–M5) is complete on `main`. The
v1 Plan — `DailyBriefing` and everything only it mounted — existed as the pilot's frozen comparison
control, behind `usePlanLayout` (`photocast.planLayout`, JSON-encoded, default `'v1'`). Every
"v1 is the frozen control" protection in the codebase is now **inverted**: the caller opt-ins that
kept v1 byte-identical (`scrollable`, `serverCellRating`, the marker `ramp`, `HealthIndicator`'s
variant, `BrandLockup`'s default, `useTodaysLight`'s gate, `NlcSightingBanner`'s `interactive`) are
what this series deletes — the *opted-in* behaviour becomes the only behaviour.

**This is a removal, not a redesign.** The one design decision in it is the error boundary's new
recovery (§4.1), which the owner asked for because its old recovery ("flip to v1") stops existing.
Where deleting v1 would force a change a reader of the v2 Plan could see, the rule is: do not absorb
it — stop that hunk, record it in §5, leave the v2 surface as it was, and flag it to the owner. §5 is
the ledger of exactly those cases; the owner named two changes in advance (the ramp consolidation and
the boundary redesign) and they are listed there as instructed, not forced.

**Out of scope, recorded as follow-ons in §8, not done here:** everything backend (the v1
`BestBetBanner` dies, but `bestBets`/`applyBestBetFallback`/`BriefingDay.peak` are backend and stay —
plan-verdict-consolidation Phase 4's backend half; `GET /api/briefing/close-to-home` and its service
lose their only client and stay); the LITE pricing decisions (`HotTopicStrip`'s fact blur, the score
split); anything in the v2 arm that works.

---

## 2. Measurements — before

Taken on `main` at `1281e64a`, `frontend/`, with `npm ci` then `npm run build` (2026-08-23):

| measure | before |
|---|---|
| source files (`src/**/*.{js,jsx}` excluding `src/test`) | 203 |
| test files | 179 |
| components (`src/components/*.jsx` + shared + chart) | 87 + 11 + 2 |
| hooks / utils / api modules | 24 / 45 / 27 |
| source LOC (non-test) | 50,217 |
| test LOC | 62,468 |
| `src/index.css` | 5,356 lines |
| production bundle (js + css, 26 files) | 1,704,714 bytes |
| entry chunk `index-*.js` | 340.52 kB (gzip 98.46) |
| `index-*.css` | 134.78 kB (gzip 23.12) |

D4 records the same table after, plus the chunk-by-chunk diff. The chunk graph should have
**shrunk**: v1 is entirely in the entry chunk today, so the entry is where the drop shows. **Two
lazy chunks are expected to GROW, and neither is a regression**: `DateStrip` moves into the
`WindowFirstMapPane` chunk at D1 (App's import was its only entry-chunk importer; the pane, a
`lazy()` boundary, becomes the sole one), and `astroApi` moves into the `MapView` chunk at D3 (the
regional panel's fetch was its only entry-chunk importer), offsetting some of what `MapView` loses
to the ramp merge. `manualChunks` names node_modules only, so membership follows the import graph.
"Entry shrank" is the gate; any **other** lazy-chunk growth is the thing to investigate.

---

## 3. Deletion inventory — verified against the tree

Every line number below was read from the tree at `1281e64a`. Where the 2026-08-20 checklist and the
tree disagree, the tree wins and the disagreement is stated. Verdicts: **DELETE** / **KEEP** /
**REWRITE** (survives, its comment or shape changes) / **COLLAPSE** (an opt-in becomes the default
and the prop dies).

### 3.0 D0 — the flip, alone

| item | verdict | evidence |
|---|---|---|
| `src/hooks/usePlanLayout.js` — **all three** `PLAN_V1` default sites flip: `:36` (the `useLocalStorageState` default), `:37` (the read fallback for a stored value that is not a layout) and `:38` (the write fallback) — one `DEFAULT = PLAN_V2` constant used at all three, so the bare-string/garbage path cannot quietly stay v1; Javadoc `:26-27` ("Default is PLAN_V1 … flipping the default is the last step of the build" — it is now) **and** `:29-31` (the unrecognised-value paragraph now says it resolves to the default, v2) rewritten | REWRITE | the hook's only production importer is `App.jsx:25`; flipping `:36` alone would ship a v2 default whose fallback is v1, and the `:49` test would pass unchanged — nothing would catch it |
| `src/test/usePlanLayout.test.jsx:26-64` — the hook's five tests: `:29` → "defaults to the window-first Plan"; `:49` → "falls back to the default (v2) when the stored value is not a layout" (store `'v99'`, expect `PLAN_V2`); `:55` keeps its refuses-garbage-write storage assertion with the expectation flipped; `:34`/`:41` unchanged in substance | REWRITE | the other 1,267 lines of that file test `WindowFirstShell` and are untouched by D0 |
| `src/test/App.test.jsx:161` "renders the v1 arm by default…" and `:200` "asks for no light on the v1 arm" | REWRITE | both **assert the default**, so the flip breaks them — a gate-forced touch beyond "nothing else in the diff", stated here; they become explicit-`PLAN_V1` tests (the arm still exists until D1) and the `:177` test ("…when the stored flag says v2") becomes the default test. Nothing else in the suite reads the default: `grep -rl 'planLayout\|PLAN_V\|usePlanLayout' src/test` → `usePlanLayout.test.jsx`, `App.test.jsx`, `UserSettingsModal.test.jsx` (passes the prop explicitly, unaffected), `planOriginShell.test.jsx:15` (a comment) |
| `CHANGELOG.md` `[Unreleased]` (`### Changed`), this plan's Status + Phase log | — | every phase |

**The rollback hatch, stated precisely.** A browser whose storage holds the JSON string `"v1"` under
`photocast.planLayout` (an explicit choice made through the settings toggle) keeps v1 after D0 —
`usePlanLayout.js:37` honours any valid stored value. That is deliberate: while D0 is the only phase
on `main`, "set the toggle off" is the per-user rollback, and the toggle is still on screen. The
hatch lasts exactly until **D1** lands (the owner's note says D2; D1 is the phase that deletes the
`App.jsx` branch and the hook, per the owner's own D1 list, so D1 is when the stored value stops
having a reader). Nothing clears the stale key afterwards; it is inert (§8).

### 3.1 D1 — flag machinery, toggle, boundary

**`src/App.jsx` (794 lines) — 13 flag sites.**

| lines | what | verdict |
|---|---|---|
| `:3,4,12,25` | imports `ViewToggle`, `DateStrip` (App's own mount only — the component survives in `WindowFirstMapPane.jsx:3,207`), `DailyBriefing`, `usePlanLayout`/`PLAN_V1`/`PLAN_V2` | DELETE |
| `:14` | `BrandLockup` import — its only App use is the v1 header's `variant="header"` (`:402`) | DELETE import; the variant itself collapses in D3 |
| `:107` | `username, sessionDaysRemaining` from `useAuth()` — read only by the v1 header's status line (`:425-429`) | DELETE from the destructure. ⚠️ The v2 masthead draws **no username and no admin session-days figure** (`grep username\|sessionDaysRemaining src/components/WindowFirst*.jsx` → nothing); the window-first design never had them and `SessionExpiryBanner` still warns on expiry. Accepted: porting them would be an addition, and v2 as shipped is the surface the owner accepted (§5) |
| `:108-112` | the `planLayout` state + its "held here because `useLocalStorageState` is per-instance" comment | DELETE |
| `:113` | `loading, error` from `useForecasts()` — read only inside the v1 Map tab (`:604-626,628,636,664`) | DELETE from the destructure; the hook keeps returning them (its own tests cover it). v2 has **never** surfaced a cold-load forecast error or a retry — it withholds the Map tab when `allDates` is empty (`:546-551`). Pre-existing v2 behaviour, not a change; named in §8 |
| `:124-130, 189-205` | `viewMode` state, `setViewMode`, the `hashchange` listener | DELETE — every reader is in the v1 arm (`:332,379,591,597,602,673,684`); `WindowFirstShell` neither reads nor writes `window.location.hash` (its tab state is `useState(TABS[0].id)`; `WindowFirstShellTabs.test.jsx:484` pins "starts on Plan again on the next visit"); `ManageView.jsx:59,92` keeps its own `#manage/<tab>` round-trip at mount. Consequence, pre-existing on v2 and stated not fixed: `#plan`/`#map`/`#manage` deep links mean nothing; `AuroraBanner.jsx:259`'s `#manage` on a simulated alert is a no-op (§8) |
| `:133, :294, :331` | `mapHandoff` — read only by the v1 `MapView` (`:645-649`); the v2 hatch reads `mapOverlay?.handoff` (`:371`) and the Map tab `mapTabHandoff` (`:357,563`) | DELETE (`:293-294`'s "keep the shared handoff in sync" comment is already false for v2). ⚠️ Three comments name the deleted identifier and must be rewritten to the surviving state ("App's overlay handoff, which every plan-card tap sets"): `App.jsx:343-355` (the `mapTabHandoff` docblock, "deliberately not `mapHandoff`" at `:344,:348`) and `WindowFirstMapPane.jsx:63-77,85` ("So `handoff` here is not `mapHandoff`") — the rule they record (a never-unmounted pane must not be wired to state that changes while it is hidden) survives; the name does not |
| `:162-173` | `useTodaysLight(planLayout === PLAN_V2, homeSettingsVersion)` + the "gated on the arm" docblock | REWRITE → `useTodaysLight(homeSettingsVersion)`; the hook's `enabled` parameter goes (below) |
| `:297-333` | `handleAuroraViewOnMap`: the v2 arm (`:322-326`) becomes the whole body; the v1 arm (`:327-333`) and the two-arms docblock go; the night-vs-calendar rule (`:313-319`) is the surviving fact | REWRITE |
| `:359-380` | `openFullMapTab`: v2 arm (`:373-377`) stays; `setViewMode('map')` (`:379`) and the "two arms, two mechanisms" docblock go | REWRITE |
| `:399-433` | the suppressed v1 `<header>` — `BrandLockup variant="header"`, default-variant `HealthIndicator`, cog (`settings-cog-btn`), Sign out, the username line | DELETE as a block |
| `:436-443` | `<NlcSightingBanner interactive={planLayout !== PLAN_V2} />` + comment | REWRITE → `<NlcSightingBanner />` (prop deleted, below) |
| `:475-506` | the ternary's opening, the "branches ABOVE the tab bar" comment, the "isDown is passed DOWN" comment (keep its first half), the provider-placement comment (its reason — "would poll for every v1 user" — is gone; the provider stays where it is because the boundary guards it, §4.1), the boundary-placement comment (rewritten with §4.1) | REWRITE |
| `:503, :514` | `PlanLayoutErrorBoundary onRecover={() => setPlanLayout(PLAN_V1)}` and `WindowFirstShell onExit={…}` | REWRITE / DELETE — §4.1 |
| `:583-691` | the whole v1 `<main>`: `ViewToggle`, `DailyBriefing` mount, the Map tab (loading skeleton, `error-message` card, `DateStrip`, `MapView`, "No forecasts loaded yet" card), the Manage tab | DELETE as a block (`ManageView` survives as `operationsPane`, `:575-579`) |
| `:721-722` | `UserSettingsModal planLayout={…} onPlanLayoutChange={…}` | DELETE |
| `:744-750` | `onOpenFullMap={planLayout === PLAN_V2 && allDates.length === 0 ? undefined : openFullMapTab}` | REWRITE → `allDates.length === 0 ? undefined : openFullMapTab` (same truth table; `MapOverlay.jsx:185` drops the button on `undefined`) |
| `:752-777` | the overlay's `<MapView>` mount | REWRITE — gains a comment in the form of its `onSelectDate` neighbour (`:755-761`): "Deliberately NO `heat`: this map opens focused on one spot from a card that already answered the question; `MapView` keys the field on the prop's presence (`heatOffered`), so the omission IS the mechanism — do not add one." The brief said to make the withholding explicit **at the mount**; the propType doc and `MapViewHeat.test.jsx:239` are the other two homes (D3) |
| `:139,143,149-161,170,257-262,481-483,492,545-550,557-560,594,727` | comments naming v1 surfaces ("lifted from DailyBriefing", "Close to home", "Best Bet, Hot Topic, region row, grid cell, strip pill", "parity with the v1 Map tab") | REWRITE |

**Everything else in App.jsx stays**: `selectedDate`/`effectiveDate`/`allDates`/`autoSelection`
(the v2 Map pane reads them, `:551-562`), `briefingScores`/`seasonalFeatures` lifts (the shell
writes them, the overlay reads them), `homeCoords`/`homeRadiusMiles`/`settingsFocus`/
`homeSettingsVersion`, `tabRequest`/`mapTabHandoff`, `isDown`, the run-complete banner.

**The flag itself.** `src/hooks/usePlanLayout.js` DELETE (sole importer `App.jsx:25`); comment-only
citations of `PLAN_LAYOUT_KEY` as the key-naming convention at `src/utils/reachLens.js:73,83` REWRITE.
`useLocalStorageState` KEEP (`WindowFirstRegionalPanel.jsx:5,80`).

**`src/test/usePlanLayout.test.jsx` (1,331 lines) — RENAME to `src/test/WindowFirstShell.test.jsx`,
do not delete.** `:26-64` (the hook) DELETE. `:75-79` "offers a way back to the current Plan" and
`:759-769` "keeps the way back working when the backend is DOWN" (both assert `window-first-exit`)
DELETE. `:68,334` `onExit: vi.fn()` DELETE. Comments at `:81-88, 90-102, 115, 138-149, 355, 681-682,
1017-1037` REWRITE (cite the flag, `ViewToggle:56`, "the v1 arm's 896px", "the OTHER arm"). Every
other test stays. `src/test/planOriginShell.test.jsx:15` ("exactly as `usePlanLayout.test.jsx` does
it") REWRITE to the new name.

**`onExit` harness props to sweep** (harmless but dead once the prop goes): `locationSheetShell.test.jsx:218`,
`WindowFirstShellSticky.test.jsx:79,206,214`, `WindowFirstResponsive.test.jsx:43`,
`WindowFirstShellSheet.test.jsx:146,215,244,254`, `WindowFirstShellTabs.test.jsx:110,253,750,787,820`
(and `:644`'s `getByTestId('window-first-exit')` inside "keeps the masthead, its tick line and the way
back out" — drop the line, rename), `WindowFirstShellRegion.test.jsx:157`,
`WindowFirstShellMasthead.test.jsx:73`, `planOriginShell.test.jsx:162,269`.

**`src/components/WindowFirstShell.jsx`.** `:296` `onExit` DELETE; `:1655` propType DELETE;
`:1369-1385` the `wf-exit-foot` block + button ("← Back to the current Plan") + its "OUTSIDE the
pane" comment DELETE; `:259-263` the `onExit`/`onOpenSettings` docblock REWRITE (the cog no longer
"owns the flag toggle"). `src/index.css:1305-1309` `.wf-exit-foot` DELETE (no other `wf-exit`
selector; no test guards it). Comments REWRITE: `:24-37` and `:47-56` — the two `lazy()` boundaries
(heat strip, `WindowSheetDialog`) were justified as "about the OTHER arm … 100 % who are on the v1
arm"; **they stay lazy** (the `geo` chunk still leaves the entry, `WindowSheetDialog` is still a
drill-down) with the true reason; `:109, 165-166, 276, 279, 283-287, 533-536, 583, 696-705, 1032,
1347, 1372-1373` (v1 header, "both arms", "pilot's core comparison gesture", "the cog owns the durable
toggle").

**`src/components/UserSettingsModal.jsx`.** `:23` `planLayout`/`onPlanLayoutChange` props, `:430-457`
the "Plan Layout / Window-first Plan" switch block (`settings-plan-layout-toggle`), `:467-470` propTypes
DELETE. `:329-331` local-radius copy "frames 'Close to home'" REWRITE (the radius survives — it frames
the map's centre-on-home, `App.jsx:157`). `.quality-toggle-track/-thumb` CSS (`index.css:351-380`)
**KEEP** — `HeatmapGrid.jsx:374-375`'s show-all switch uses it. Tests:
`src/test/UserSettingsModal.test.jsx:463-522` the whole "plan layout switch" describe DELETE.
`docs/engineering/frontend-test-standards.md:135-149` uses that switch as its role-query example —
REWRITE to a surviving control in the same commit.

**`src/components/NlcSightingBanner.jsx`** — the `interactive` prop. App passes
`interactive={planLayout !== PLAN_V2}`, so in v2 the banner is **already inert**: no cursor, no tab
stop, no handler, no "Show on map →" CTA. Inert becomes the only state; the prop dies. DELETE: `:110`
default param, `:142` the dismiss handler's `e.stopPropagation()` (dead once the banner has no click
handler to stop propagating to), `:157-162` className/onClick/onKeyDown/tabIndex, `:188-189` the
`nlc-div` + `nlc-cta` spans, `:149`/`:164` the `eslint-disable`/`enable` pair (⚠️ **not optional**:
ESLint 10 reports an unused disable directive as a warning and `npm run lint` is `--max-warnings 0`),
`:213-226` propTypes (and the `PropTypes` import), `.nlc-cta` in `NLC_A_STYLE` (`:85`; `.nlc-div`
`:84` stays — drawn twice elsewhere). `:107` docblock bullet REWRITE. Tests
`src/test/NlcSightingBanner.test.jsx`: `:188-191` "navigates to the map" DELETE, `:152-157`
dismiss-does-not-navigate DELETE (vacuous), `:210-244` "when it has no destination to offer": retitle
the describe ("ships inert") and rewrite its `:210-214` header comment — it claims "the window-first
arm has no Map tab", false since the shell gained a Map pane; the true reason is that v1's action was
a bare `#map` hash write nothing in v2 reads; the first two tests become the default (drop
`interactive: false`), the third ("still a control in the arm that has a Map tab") DELETE. Routing it
through the overlay like `handleAuroraViewOnMap` would be a **new** v2 behaviour — §8, not here.

**`src/hooks/useTodaysLight.js`** — `enabled` (`:35,45,62,65,81`, docblock `:26-31`) exists only for
the arm gate ("every v1 reader would pay for a request whose answer nothing renders"). COLLAPSE: the
parameter goes. Tests `src/test/useTodaysLight.test.jsx:28-29` Probe default, `:97-104` and `:183-191`
(the two "arm does not render a band" cases) DELETE/REWRITE, header `:22` REWRITE.
`src/test/App.test.jsx:192-214` ("today's light is fetched for one arm only") collapses to one test.

**`src/components/PlanLayoutErrorBoundary.jsx` → redesigned — §4.1.** Not deleted.

**`src/test/App.test.jsx`.** `:6` import, `:114-121` `renderApp`'s `layout` option + the
`localStorage.setItem(PLAN_LAYOUT_KEY, …)` DELETE; `:160-190` the flag-branch describe → two tests:
"renders the Plan shell" (the `view-toggle`/`settings-cog-btn` absence claims are vacuous once those
ids do not exist anywhere), and **"renders the Plan fallback when the provider throws"** — the
`providerSpy` at `:117` is already a passthrough spy on `WindowFirstBriefingProvider`;
`mockImplementation(() => { throw new Error('boom') })`, render, `findByRole('alert')`, assert the
fallback heading and that the Sign-out control is reachable. That is the test of §4.4's
provider-inside-the-boundary decision (the behavioural successor of the deleted `:161` placement
assertion) — without it a later "tidy" hoist regresses silently. `:218-283` drop `layout: PLAN_V2`
from five `renderApp` calls; header `:9-22`, `:131`, `:249-256` comments REWRITE. `:33,64,132` mock
`getCloseToHome` — stays until D2 deletes the export.

**Config / standards.** `src/test/setup.js:77` "The v2 Plan shell…" → "The Plan shell…" (the
`asyncUtilTimeout: 4000` stays: the shell's lazy boundaries stay). `vite.config.js:93-98` "every
v2 reader" wording (the e2e exclude is at `:129`). `src/test/e2e/forecast.spec.js` is **already
v1-shaped and already stale** (it expected `map-container` straight after login while v1 defaulted to
Plan; `:128,147` click the `ViewToggle` "Manage" button; `:163` asserts the v1 `error-message` card)
and is **not in CI** (`vite.config.js:129` excludes `e2e/`; `ci.yml:241-244` runs it only against a
live environment). **Salvage AND run, in D2**: rewrite by behaviour (login →
`getByRole('tab', {name:'Map'})` → `map-container`/`date-strip`; `tab` "Operations" → the `manage-*`
ids; the error-handling block dies with its v1-only card; port `:10` 8082→8083), then execute
`npm run test:e2e` against the §6 stack and record pass/fail per test in D2's Phase-log row — an
edited spec nothing executes is trap 2 in another form. Keep only tests that pass.

### 3.2 D2 — the v1 component estate

**Whole files — DELETE** (sole non-test importer in brackets; verified by reverse search):
`src/components/DailyBriefing.jsx` 1,614 lines [`App.jsx:12`]; `BriefingSummaryStrip.jsx` 305
[`DailyBriefing.jsx:16`]; `CloseToHome.jsx` 1,247 [`DailyBriefing.jsx:17`]; `CardHoverPreview.jsx` 151
[`CloseToHome.jsx:4`]; `ViewToggle.jsx` 69 [`App.jsx:3`]; `QualitySlider.jsx` 99 [**none** — already an
orphan since `b4b96d4b`; `index.css:313` says so; CLAUDE.md still claims a "quality slider" exists].
Their tests: `src/test/DailyBriefing.test.jsx` 2,776, `BriefingSummaryStrip.test.jsx` 270,
`CloseToHome.test.jsx` 1,037, `ViewToggle.test.jsx` 69, `QualitySlider.test.jsx` 584, and
`regionChipVerdictColour.test.jsx` 205 (renders `BriefingSummaryStrip` and slices `index.css` for
`.summary-region-chip` — its own header `:18-21` says "what remains is the v1 arm"; it **fails loudly**
the moment those CSS rules go, so it and the rules leave together). Partial:
`src/test/briefingDayStepDst.test.jsx` — keep `:135-160` (the fixture) and `:257-300` (the
`WindowFirstBriefingProvider` day step); delete `:162-255` (the two `DailyBriefing` describes) **and
their now-unused residue, each a lint failure under `--max-warnings 0` if left**: `:25` `fireEvent`
from the imports, `:80-112` `twoDayBriefing`, `:208` `TOMORROW_SUNSET_PICK`, the `:42` import. The
mock set, stated positively: keep `briefingApi.getDailyBriefing`, `briefingEvaluationApi`,
`settingsApi` (`getReach`/`getSettings`), `travelDayApi`, `AuthContext`; **add `regionApi`**
(`fetchRegions`, `fetchRegionDriveTimes` — the provider calls both and this file never mocked them;
the tests pass today only because both calls end in `.catch(() => {})`, i.e. a real axios attempt per
test); drop `getCloseToHome`, `getDriveTimes`, `astroApi`, `hotTopicSimulationApi`.

⚠️ **The DailyBriefing.test.jsx salvage is a whole-file pass keyed by behaviour, not a range.** For
every testid/role the file asserts, check whether a surviving component renders it and whether a
surviving suite asserts it. The survey found the named ports (behaviours whose only v2 renderer is
`HeatmapGrid` and whose only assertions are in this file): **(a)** the drill-down's drive time —
`:1111-1180` asserts `slot-drive-time` ("45 min", "1h 15min", "1h", absent-when-unknown), rendered by
`HeatmapGrid.jsx:245`, asserted nowhere else (every HeatmapGrid.test fixture passes
`driveMap={new Map()}`) → port into `HeatmapGrid.test.jsx`'s drill-down describes with a non-empty
`driveMap`; **(b)** the drill-down's type icon — `:1181-1214` asserts `slot-type-icon`, rendered via
the shared `SlotLocationName` at `HeatmapGrid.jsx:232` → port with a `typeMap` fixture; **(c)**
`toggleDrillDown`'s second-cell switch — `:2114` "clicking a second cell closes the first drill-down
and opens the new one" (`HeatmapGrid.jsx:944-949`) has no twin (no HeatmapGrid test clicks two cells)
→ port. Sampled twins that need no port: `:2089` STANDDOWN disabled (→ HeatmapGrid.test:715), `:2100`
GO opens drill-down (→ :1043/:1087), `:2182` "Worth it sunset" (→ :391), the visible-event-budget
trio (→ WindowFirstBriefingContext.test:572-661), `:1857`'s aurora pair (→ HeatmapGrid.test:266-274,
HotTopicStrip.test:775/923, WindowFirstDoors.test:280). The rest die with the component.

**Exports orphaned by the deletion — DELETE with their tests:** `src/api/briefingApi.js:31-43`
`getCloseToHome` (only `DailyBriefing.jsx:18`; mocked in `App.test.jsx:33,64,132`,
`briefingDayStepDst.test.jsx:29`, `DailyBriefing.test.jsx` — remove the mock keys too, an import of a
deleted export fails); `src/utils/briefingDisplay.js:50` `sortedSlotsByVerdict` (only
`DailyBriefing.jsx:464`; `briefingDisplay.test.js:6,49-57,73-78`; the `:44-45,81-83` docs that cite
it REWRITE; `VERDICT_ORDER` stays — `slotSortKey:70` reads it); `src/utils/tierUtils.js:14`
`TIER_LABELS` (only `QualitySlider.jsx`), `:12` `TIER_KEYS` (test-only, pre-existing), `:82-93`
`computeAuroraCellTier` (**zero consumers, zero tests** — checklist claim verified; last caller went
in `510a448e`), header `:1-11` ("the 6 slider positions") REWRITE; tests `tierUtils.test.js:178-202`.
⚠️ `computeCellTier` **KEEPS** — it still orders the Regional planner's rows by best tier
(`HeatmapGrid.jsx:953-968`); only `isCellVisible`/`qualityTier` are dead-in-practice (D3).
`LOCATION_TYPE_ICONS` re-export at `briefingDisplay.js:21` (no consumer either way) → D4 sweep.

**The stale aurora comment** the checklist named: `DailyBriefing.jsx:926` "Aurora is now rendered as
🌌 grid columns inside HeatmapGrid (not a separate row)." — false since `510a448e`; dies with the file
(nothing in `HeatmapGrid.jsx` repeats it).

**`src/index.css` — v1-only blocks, DELETE (exact ranges, boundary-read; two corrected by review):**
`:298-349` `.quality-slider*` (through its closing brace); `:818-974` the `.cth-*` block — head
comment `:818-823` through the `}` at `:974` closing the reduced-motion rule — **except** `:912-923`
the three `@keyframes cth-preview-fade/-rise/-drop`, which **`.wf-peek` reuses** (`:3631-3636` — "The
v1 peek's own keyframes, reused rather than renamed"): rename them `wf-peek-fade/-rise/-drop` and
update the three references + that comment in the same edit, or the v2 spot peek silently loses its
entry animation and no jsdom test can see it; `:2256-2355` the summary block — head comment,
`.summary-pill-clickable`, `.summary-region-chip*`, `.summary-region-tip*` (⚠️ **not** `:2358` —
`:2357-2364` is `@keyframes map-overlay-rise` + `.map-overlay-panel`, `MapOverlay`'s open animation);
`:2500-2513` `briefing-refresh`; `:2526-2681` the second `.cth-*` block (window grid, rail, rows,
filters); `:186-211` the alias block — `--color-marginal` (`:210`) and `--color-dust` (`:211`), whose
`var()` consumers are only `CloseToHome.jsx:48-49` and `CardHoverPreview.jsx:85` and whose own
comment says "a smell that dies with v1". Delete by **selector**, re-reading each boundary — one
review lens's ranges over-reached into `MapOverlay`'s keyframes and under-reached a closing brace.
⚠️ **A bare token name in a JS/JSX comment keeps a token emitted** (the scanner rule quoted at
`index.css:67-84`), so the comment-only mentions at `WindowSpotPeek.jsx:205-220`,
`shared/MastheadLight.jsx:4-14`, `test/WindowSpotStrip.test.jsx:227` are rewritten in the same
commit. `--color-pick-also` (`:56-59`) becomes v1-only (its `var()` consumers were
`DailyBriefing.jsx:773,776` and the summary chip; v2 paints the hue as rgba literals at `:5047-5048`)
→ DELETE and REWRITE `--color-badge-also`'s comment (`:153-159`, "The FILL stays --color-pick-also").
**KEEP**: `.quality-toggle-track/-thumb` (`:351-380`, HeatmapGrid), `--color-close-to-home(-light)`
(`:63-64` — thirteen v2 rule sites and `LocationFourDaySheet.test.jsx:530` pin them; only their
comment "the local-decision block's warm gold" is about the dead block → REWRITE), every `heatmap-*`,
`hot-topic-*`, `tide-*`, `tr-*`, `sr-*`, `map-*`, `wf-*` rule. Comments in surviving rules that cite
the deleted blocks by position or name — `:646-647, 1251, 1532, 1539, 2683-2684, 3449-3460,
3512-3513, 3598-3608, 3623-3629` — REWRITE.

**Token-audit guards that must stay green** (they read `index.css` as text): `WindowSheetDialog.test.jsx:416-432`
(every `var()` the dialog emits is defined), `MastheadTickLine.test.jsx:385-430` (slices by the string
markers `/* ── The tick line (M3) ──`, `.wf-tabs {`, `.wf-search-anchored {`, `/* ── P8 · the four-day
location sheet` — **never rename those four**), `LocationFourDaySheet.test.jsx:420-435,530-548`,
`mastheadColours.test.js`, `WindowFirstShellSticky.test.jsx:273-335`, `movementToneCascade.test.jsx:51-104`,
`markerInertCascade.test.jsx:50-85`. Run the token audit **both** ways after every CSS edit: the
var()-vs-defined guards, and a grep that every token still defined has a consumer.

**Survivors that look v1 — verified, KEEP:** `HeatmapGrid.jsx` (`WindowFirstRegionalPanel.jsx:4,135`);
`HotTopicStrip.jsx` (`WindowFirstDoors.jsx:3,224`) and with it `TideRunRow`, `SurgeRunRow`,
`CertaintyChip` + `topicCertainty.js`, `InfoTip` (eight other importers); `MapView`, `DateStrip`,
`MapOverlay`, `MapHeatLayer`, `BottomSheet`, `MarkerPopupContent`; `ManageView` and every Manage view;
`components/shared/*` — `BrandLockup` serves the login/register/password pages and the masthead
(`LoginPage.jsx:35`, `RegisterPage.jsx:195`, `ChangePasswordPage.jsx:75`, `WindowFirstShell.jsx:1030`),
`VerdictPill` (`HeatmapGrid.jsx:412`; its doc `:21-22` naming Best Bet and mobile region rows →
REWRITE), `ProvisionalMark` (four v2 sites), `SlotLocationName` (`HeatmapGrid.jsx:232`);
`useLocalStorageState`, `useIsCoarsePointer`, `useIsMobile`; `confidenceUtils` (header `:2-3` names
v1 surfaces → REWRITE), `briefingScoreIndex`, `conversions`, `mapOverlay` (`buildMapOverlay` —
`App.jsx:5,290`; its `:77-91` "both arms" doc and `test/mapOverlay.test.js:162-163` REWRITE),
`standDown`, `relativeTime` (`:4-5` cites `DailyBriefing` → REWRITE), `firstClause`;
`api/briefingApi.getDailyBriefing`, `briefingEvaluationApi`, `settingsApi.getDriveTimes`, `astroApi`,
`travelDayApi`. The `briefing:<role>` SWR key (`WindowFirstBriefingContext.jsx:153`) **stays** — it
is role-scoping, not arm-scoping; only the "same key v1 uses" wording goes (D3).

**Comments in survivors that cite the deleted files as their "copied-from" licence** → REWRITE to the
reason without the file name, or past tense: `WindowFirstRegionalPanel.jsx:13-14,21-30,37,77-79`;
`WindowFirstDoors.jsx:76-84,129-134,141-144,227-229`; `WindowFirstShell.jsx:28,201,696`;
`WindowFirstBriefingContext.jsx:25,28,133,143-157,169-174,347-350,414-415,571-573`;
`WindowSpotPeek.jsx:65-100,102,104,115,131,205-220` (⚠️ `:131` cites `frontend-test-standards.md:156`
for a claim that is at `:225`); `WindowSpotCard.jsx:17`; `WindowSpotStrip.jsx:77,129,200-207`;
`hooks/useSpotPeek.js:8,31,44,85,130`; `utils/windowSpotPeek.js:8-14,40,51,83,112,138,144,163`;
`utils/reachLens.js:34`; `utils/ratingLens.js:48`; `utils/windowFirstRows.js:23,33-36,73,195-196`;
`utils/windowFirstRegions.js:10-17,176` ("Reconverge after the flag default flips" — it is now the
only copy); `WindowProseSlot.jsx:36`; `utils/windowFirstCards.js:84`; `utils/planDoors.js:1-24`
(its rationale — "the reader flips between arms" — is false; its **behaviour** — doors survive a
remount within the session — stays, `WindowFirstDoors.test.jsx:286-310` pins it); `utils/mapDates.js:109`;
`HotTopicStrip.jsx:837-839,1227-1231`; `HeatmapGrid.jsx:17,491`; `ModelTestView.jsx:641`;
`api/lightApi.js:9`, `api/settingsApi.js:29`; `src/fonts.js:6-7` (the 40px wordmark is the header
variant); tests `WindowSpotPeek.test.jsx:35,47`, `windowSpotPeek.test.js:93,146`,
`WindowSpotStrip.test.jsx:78,482,591,686,727,762`, `WindowFirstRegionalPanel.test.jsx:150,163`,
`WindowFirstDoors.test.jsx:242,260-262,286-288,310`, `WindowFirstBriefingContext.test.jsx:503-504,863`,
`movementToneCascade.test.jsx:23`, `briefingDayStepDst.test.jsx:260`. `docs/engineering/frontend-test-standards.md:40`
(the `getCloseToHome` mock example), `:220-225` (`CardHoverPreview` as the aria-hidden example; the
"quality toggle") REWRITE in this phase.

**Stale browser storage left behind, harmless and unrecoverable:** `photocast.planLayout`,
`closeToHomeFilterDrive/Rating/Tide`, sessionStorage `briefing-dismissed-at`, `planGridExpanded`.
`showStanddownLocations` stays live (`WindowFirstRegionalPanel.jsx:80`). No one-off `removeItem` — a
clean-up write for keys nothing reads is a control acting unasked.

### 3.3 D3 — the compatibility layer collapses

**Three facts that reframe the checklist, found by survey:**

1. **`MapView` goes from three mounts to two, not one.** v1 Map tab (`App.jsx:638`, dies), the v2
   pane (`WindowFirstMapPane.jsx:209` — passes `heat` + `resizeNonce`), the Plan overlay
   (`App.jsx:752` — passes `overlayMode`, **no `heat`, no `resizeNonce`**). So `heat`, `resizeNonce`,
   `overlayMode` remain genuine two-caller props. The overlay never had heat and keeps not having it,
   **explicitly, in three homes**: the mount comment D1 adds at `App.jsx:752` (§3.1), the `heat`
   propType doc (`:2261-2264`, rewritten: the overlay passes nothing *deliberately* — it opens focused
   on one spot from a card that already answered the question; a field and toolbar over a modal would
   be a second plan), and `MapViewHeat.test.jsx:239`, which keeps pinning it. What dies is the
   justification text ("the v1 Map tab and the Plan overlay render byte-identically"), the
   `MapSizeSync` `enabled` guard (`:285-313,1829-1832` — both surviving mounts enable it;
   `MapViewSizeSync.test.jsx:103` "stays switched off for the v1 Map tab" DELETE), and the lazy
   `MapHeatLayer` comment (`:29-37` — the boundary stays, the overlay never fetches the layer; the
   reason is chunk hygiene, not arm protection). `MapViewHeat.test.jsx:229-236` ("renders no toolbar
   and no field for the v1 Map tab, which passes no heat prop") becomes a duplicate of `:239` under a
   false title — fold them into one overlay-named test; `:246-253` ("keeps v1's opening framing and
   its 60px padding") renders a no-`heat`, no-`overlayMode` mount that no longer exists — re-target
   at `overlayMode: true` (the overlay is what keeps the 60px framing) or delete.
2. **The marker `ramp` is keyed on the heat VIEW, not the arm** (`MapView.jsx:1081 heatOn =
   heatOffered && heatView === 'heat'`, threaded to `makeMarkerIcon` `:1936` and `createClusterIcon`
   `:1887`). So after v1 is gone `RATING_COLOURS` is still painted in two live v2 states — the Map
   tab's **Medallions** view and the **Plan overlay** (no `heat`), plus aurora/astro modes (heat
   withheld). The owner's instruction resolves it: `scoreRamp` becomes **the map's only colour
   language** — §4.2. Not a consequence of v1's deletion; an instructed product change, recorded in §5.
3. **`HeatmapGrid`'s ASTRO cell is a pre-existing dead path** — the one non-map reader of
   `RATING_COLOURS`. `ratingStyle()` (`HeatmapGrid.jsx:83-92`, `:1067`) paints the astro cell; that
   branch needs `events` to carry `targetType === 'ASTRO'`, and nothing produces one: the backend
   `TargetType` is SUNRISE/SUNSET/HOURLY, `BriefingEventSummary.targetType`/`PlanRenderedEvent` are
   typed on it, v2's `selectUpcomingEvents` (`WindowFirstBriefingContext.jsx:120-137`) re-lists served
   `eventSummaries`, and no frontend source builds an ASTRO grid event (the ASTRO literals in
   `ForecastTypeSelector`/`MapView` are the map's `eventType`, a different prop). ⚠️
   `HeatmapGrid.test.jsx:623-634` is **not** a pin of this — its `renderGrid()` default is a
   SUNSET-only fixture, so it cannot fail whether or not ASTRO is reachable; D3 writes the promised
   pin (the grid's sub-column titles are only Sunrise/Sunset over a fixture drawn from served
   `eventSummaries`). DELETE the whole path, inventoried in full: `HeatmapGrid.jsx` — `ratingStyle`
   `:83-92` + the `RATING_COLOURS` import `:13`, the astro cell branch `:1051-1080`, the ASTRO arm of
   the column-header ternary `:1249-1251` (label "Astro conditions" / 🌙), `getRegionLocationNames`'
   astro use, the `astroScoresByDate` prop `:835,1331`, the `:819` docline ("and astro (🌙) columns");
   `WindowFirstRegionalPanel.jsx:6,81,106-132` the `getAstroConditions` fetch + state;
   `index.css:726-730` drop the `[data-testid='astro-heatmap-cell']:focus-visible` selector from the
   focus rule. Tests: `WindowFirstRegionalPanel.test.jsx:6-8` the `astroApi` mock, `:71-72` its
   resets, `:183-221` the whole `describe('astro conditions')` (four tests, all red the moment the
   fetch goes), and drop `waitFor` from `:3` if it becomes unused; `HeatmapGrid.test.jsx` drop
   `astroScoresByDate={{}}` from its ~9 fixture sites and the `:28` comment; the dead `astroApi`
   mocks in `App.test.jsx:47,70,140` and `briefingDayStepDst.test.jsx` (already dropped in D2's mock
   list). Zero rendered change; one `GET /api/astro/conditions` per rendered date fewer on every Plan
   load — recorded in §5 as an invisible, network-only v2 change, taken because the astro cell was
   the last non-map `RATING_COLOURS` reader. The map's own ASTRO mode (`astroApi` via `MapView.jsx:18`)
   is untouched.

**`src/components/markerUtils.js` + `src/components/MapView.jsx` — one ramp.** DELETE `RATING_COLOURS`
(`:24-30`), the `ramp` parameter on `scoreColour`/`ratingColour`/`markerLabelAndColour`/
`createClusterIcon` (`:75,93,109,213`) and its five-bucket / `?? '#6B6B6B'` arms (`scoreColour` becomes
`avg == null ? NO_DATA_COLOUR : rampHex(starsFromAverage(avg))`; `NO_DATA_COLOUR` and
`STAND_DOWN_COLOUR` stay), the `ramp` threading in `MapView` (`:443-456` cache-key segment `|r`,
`:1587` swatch ternary → `rampHex(star)`, `:1874-1887` the `MarkerClusterGroup` `key` remount — see
§5's new row — and `createClusterIcon(c, role)`, `:1936`), the `RATING_COLOURS` import (`:217`).
Comments `:62-72, 88-91, 204` (markerUtils), `scoreRamp.js:16-18`, `windowFirstSpots.js:52-59,108-118`
REWRITE (keep the contrast arithmetic that justifies white ink; drop the dangling identifier). Tests —
**fold, do not just delete**: `markerRampOptIn.test.js` goes, but two of its eleven tests pin ramp
invariants that outlive the opt-in and have no twin — the ×20 round-trip (`scoreColour(4*20) ===
RAMP_STOPS[3].hex`, the inverse of `createClusterIcon`'s `mean(ratings)*20` that `starsFromAverage`'s
docblock calls load-bearing) and the interpolation between stops (`scoreColour(70) === rampHex(3.5)`)
— move both into `MarkerIcon.test.jsx`'s `scoreColour` describe as literal-stop assertions, and write
the `:23-52` rewrite in those terms (an assertion of `rampHex(starsFromAverage(x))` restates the code
and pins nothing). `MarkerIcon.test.jsx:405-413,424-426,450-467` REWRITE; **`:435-438`** ("returns
grey for unknown rating value", the `?? '#6B6B6B'` arm) is also red — its replacement claim is the
behaviour change: an out-of-range rating now clamps to the ramp's end. `MapViewHeat.test.jsx:255-260`
DELETE, `:637-671` invert to "same colour in both views"; the `RATING_COLOURS` keys in eleven
`vi.mock('…/markerUtils.js')` factories (`MapViewSunsetToggle:73`, `MapViewTypeFilter:79`,
`MapViewCentreOnHome:99`, `MapViewBriefingScoreWiring:80`, `MapViewAstro:107`, `MapViewStarFilter:81`,
`MapViewOverlayContext:79`, `MapViewViewline:123`, `MapViewSizeSync:68`, `MapViewAuroraNight:129`)
DELETE; `windowFirstSpots.test.js:5,277-284,314` literalise. The Heat/Medallions segment **survives
as a feature** (field off, pins on) with its D8 "honest before" comments rewritten;
`MapHeatLayer.jsx:129-148`'s restore-on-unmount stays (it is what keeps Medallions clean).

**`src/components/HeatmapGrid.jsx` — COLLAPSE `scrollable` and `serverCellRating` (both default
`false`, both passed `true` by the only surviving caller, `WindowFirstRegionalPanel.jsx:135-164`).**
`scrollable`: `:925` `colFloor` → `'96px'`; `:1149-1152` className/tabIndex/role/aria-label
unconditional; comments `:905-926,1137-1147`, propTypes `:1336-1346` DELETE. `serverCellRating`:
`:671-706` — the `else` is v1's client-side join (voting-slot filter, `canopyNames`, region-name
prefix join over `evaluationScores`, slot-tree fallback, `toFixed(1)` mean) DELETE; the 45-line
comment `:629-668` → two lines ("the backend's `meanRating`, derived from the same voting slots as
the verdict word"); `HeatmapCell`'s `evaluationScores`/`serverCellRating` params (`:507,1095,1099`)
DELETE (the grid still hands `evaluationScores` to `HeatmapDrillDown` `:1118` → `:341-347` KEEP);
propTypes `:1347-1361` DELETE. **`qualityTier`/`isCellVisible` — the retired slider's seam — DELETE,
spelled out because the collapse has consequences a range hides**: `qualityTier` dies at the grid
(`:827,1323`), at `HeatmapCell` (`:507` param, `:1092` pass-through) and at the caller
(`WindowFirstRegionalPanel.jsx:10-18,138`, `SHOW_ALL_TIER`); `isCellVisible` (`tierUtils.js:105`,
import `:4`) dies with its four sites, each collapsing to "always visible" — `:530-531` go and `:575`
becomes `cellDisabled = (isStanddown && !showAllLocations) || past`; the sort at `:960,964` keeps
`computeCellTier` (`if (t < bestA)`); ⚠️ **`isRegionFullyHidden` (`:971-979`) is NOT dead** — it
still returns true for a region whose in-view columns are all missing or `past`, driving `faded` /
`aria-hidden` / the 0.06 opacity at `:1016,1036` — keep it, minus its `isCellVisible` term.
`tierUtils.test.js:4` drop `isCellVisible` from the import, `:165-176` DELETE that describe. Stale
"both arms" comments `:562,570,1025,1301-1307` REWRITE. `WindowFirstRegionalPanel.jsx:139,151-163`
pass-throughs + comments DELETE/REWRITE. ⚠️ **Test fallout, split into what goes RED and what goes
silently quieter** (both need `meanRating` on their region fixtures — fix every fixture that carries
`claudeRating`, not only the failing ones): red — `:162` "carries the star rating into the name",
`:1523` "shows mean score badge in cell", `:1933` "leaves the star/quality badge untouched"; quieter
— `renderRatedCell`'s other users `:152,:178,:184`, the confidence fixtures `:1904,:1909,:1917,:1943`,
**Gate 2** (`:1619-1700`, `claudeRating` at `:1639/:1675`) and **lightly-evaluated** (`:1707`, used
`:1755-1790`), which the first survey missed. `:1510` (drill-down `score-badge` from
`slot.claudeRating`) is unaffected — the drill-down keeps reading slots. Dead-path tests: `:1302-1307`
("keeps the client-side derivation when the caller does not — the frozen v1 arm") DELETE; `:1322-1484`
"the v1 cell star excludes woods" (nine tests on the deleted join) DELETE — **re-home `:1465-1483`**
("still shows the wood its own row in the drill-down": it clicks a `WORTH_IT` cell and asserts the
panel from `evaluationScores`, which survives — move it into the drill-down describes unchanged);
`:2089-2244` drop `scrollable: true` and rename the `:2105` describe ("when the caller opts in" — it
is the only behaviour now). ⚠️ **`:2245-2263` is TWO tests and only one dies**: `:2256-2262` "is not
a tab stop when it is not a port" DELETE (the no-opt-in state stops existing); **`:2246-2254` "is
focusable and named when it is a port" KEEP** — drop its `scrollable: true`, retitle it and the
describe (always a port now) — it is the suite's only pin of `tabindex="0"`, `role="region"` and the
"scrolls sideways" accessible name, the keyboard route to columns 3–6 that `HeatmapGrid.jsx:1140-1146`
records; deleting the range whole was this review's first blocking find. `:2265-2300` "when the
caller does not opt in" DELETE. Helper `:52-84` drop both props; inline `qualityTier={5}` (9 sites)
and `astroScoresByDate={{}}` sweeps ride along. `WindowFirstRegionalPanel.test.jsx:126-131,143-165,
224-230` DELETE/REWRITE. `index.css:703-816` `.heatmap-scroller` rules KEEP (now always matched);
comments `:757-770,790-796,811-813` REWRITE. `.wf-door-panel .heatmap-cell-hoverable:hover
{z-index:10}` (`:2782-2800`) vs the base `:hover {z-index:40}` (`:680`): the base value is now never
effective — fold to **10** (the override records that 40 painted a hovered cell over the sticky lens
bar and swallowed its clicks), re-checking `:726-730`'s focus rule; `:2764-2770` and `:3212-3219`'s
"cannot take a `.wf-` class without editing the v1 arm" REWRITE.

**`src/components/HealthIndicator.jsx` — COLLAPSE `variant`.** The only surviving mount is the
masthead's (`App.jsx:529-539`, `variant={VARIANT_MASTHEAD}`). DELETE `VARIANT_HEADER`/`VARIANT_MASTHEAD`
(`:21-24`), the `variant` prop, the Tailwind-tone arms (`:168-180` dot/bg classes, `:191-192,232-238,
247-259,271-290`, `StatusBadge`'s non-masthead branch `:371-375` and its propType), propTypes
`:408-413`; comments `:91-100,112-131,247,274` REWRITE. Tests `HealthIndicator.test.jsx:15-37` (header
classes) → tones or DELETE (covered by `:220-248`); `:39-199` KEEP unchanged (variant-agnostic);
`:203-216` doc, `:218` `MASTHEAD` spread, `:220-231` the "none of the header classes" half (keep as a
regression guard against Tailwind tones creeping back), `:270` name REWRITE.

**`src/components/shared/BrandLockup.jsx` — the `header` variant and the default.** ⚠️ **The
component's own docblock (`:78-88`) is wrong about what is header-only, and this review's second
blocking find is that following it strips the tagline from the three auth pages.** The tagline `<p>`
(`:137-141`, "Golden hour, forecast and ranked by AI") is gated `!isCompact && !isMasthead`, so
**`auth` renders it** — `LoginPage.jsx:35`, `RegisterPage.jsx:195`, `ChangePasswordPage.jsx:75` all
mount `variant="auth"`, and `BrandLockup.test.jsx:71-78` pins "auth drops nothing". And
`KICKER.default` is rendered by the **masthead** (`:134`) — deleting the constant breaks the v2
Plan's kicker. The deletion list, derived from the JSX rather than the docblock: DELETE `isHeader`
(`:96`), the `text-[40px]` arm (`:127`), the `header` else-arm of the `:118` kicker ternary (leaving
`KICKER.auth` for auth; `KICKER.default` stays for the masthead), the `'header'` propType value and
the `'header'` default (`:95` → `'masthead'`). KEEP the kicker `<p>` (`:116-120`), the `mt-[7px]` arm
(`:126`) and the tagline block (`:137-141`). Add a pin: render `variant="auth"`, assert the tagline
and kicker are present. Tests `BrandLockup.test.jsx:7,16,59-64,70-94,115-120,172-191` REWRITE
accordingly; fix the `:84-88` docblock's false claim while rewriting it.

**`src/components/shared/Modal.jsx` — `stacked` STAYS.** It is a per-instance "another dialog covers
me" flag, not an arm opt-in: 4 of the `<Modal>` sites stack; collapsing it into the default would make
every settings / Manage dialog `inert` with no `aria-modal`. Only the prose is v1's: `:23-25` ("Every
v1 render site is such a caller"), `:38` ("exactly as they can in v1"), `:116-117`, `:152` REWRITE;
the stale counts at `:75-77` and `useDialogFocus.js:35` ("fifteen render sites") are rewritten
**without a number** ("every render site") — a fresh count goes stale one phase later when D4 deletes
`OutcomeModal`; `Modal.test.jsx:195-203,358-372` names/comments REWRITE.

**The comment sweep — every "v1 is the frozen control" marker.** The survey's sweep list (grouped by
file) is the execution list; the shape of it: `src/context/WindowFirstBriefingContext.jsx:25,28,133,
143-157,169-174,182,347-350,414-415,571-573` (the shared-SWR "both arms" notes — the **`briefing:<role>`
key stays**); `src/components/WindowFirstMapPane.jsx:6-9,36-42,88,110-126,168`; `MapOverlay.jsx:10
("the caller's `viewMode` is untouched"),22-24,178-184` + `MapOverlay.test.jsx:56-59`;
`useHeatCanvas.js:134`; `MapHeatLayer.test.jsx:38-39,174,521`; `WindowFirstMapPane.test.jsx:152-154,
191,259`; `WindowFirstMapPaneHeat.test.jsx:247`; `WindowFirstShellTabs.test.jsx:485,758-760,812-817`;
`WindowFirstShellMasthead.test.jsx:102`; `WindowFirstShellSticky.test.jsx:313-317`;
`WindowRowFieldMap.test.jsx:647`; `WindowFirstComingUp.test.jsx:58`; `utils/locationTypes.js:91`; CSS
`:1018-1023`. **Excluded on purpose (true, keep):** "byte-identical" as name-join / payload-equality
at `heatSpots.js:23`, `windowFirstTopics.js:42,219`, `windowFirstRegions.js:87,223,330`,
`planOrigin.js:173`, `windowFirstCards.js:274`, `WindowFirstBriefingContext.jsx:471,498`,
`WindowSpotStrip.jsx:151` and their tests; "arms" as branches at `planConflicts.js:54,118,125,170`
(+ test), `planOrigin.js`, `leaveBy.js`, `MastheadTickLine.jsx:116,190,194`,
`WindowFirstHeatStrip.jsx:332`; "frozen" as clock/fixture; the design-bundle "v1 mock" at
`WindowFirstComingUp.jsx:11-12` and `index.css:3940`. The ~270 bare "this arm / the arm" mentions
(vocabulary, not falsehood) are left alone — low value, high churn.

**The modal ruling** — made here, recorded in §4.3.

### 3.4 D4 — the settling commit

- **CLAUDE.md, rewritten truthful** (line numbers at `1281e64a`): `:8` the whole frontend line —
  "map view (Leaflet), plan heatmap, **forecast timeline, outcome recording**" is three dead terms
  out of four (the timeline and outcome UI went with the Feb-2026 chore `14fce598`; the Plan is the
  matrix) — rewrite to the real surface list (map view, Plan matrix + popup/sheet, Coming up,
  Operations; outcome recording is API-only); `:22` the Daily-Briefing bullet (heatmap grid /
  "quality slider" / "Claude best-bet recommendation" / "via SSE" — the slider has been an orphan
  since `b4b96d4b`, SSE is gone, the best-bet advisor still runs at build time but the `/api/briefing`
  `bestBets` field has **no renderer**); `:24-26` the "Plan tab v2" bullet (no flag, no default, no
  "frozen control" — it is the Plan tab); `:48` "Tab still leaves the topmost dialog exactly as it
  does in v1"; `:52` and `:184` the confidence-channel surface lists (Best Bet, summary-strip pills,
  mobile region rows die; grid cells, the grid's drill-down `VerdictPill`, the popup's and sheet's
  `ProvisionalMark`, the field/matrix/map `confidenceScalar` survive); **`:84, :281, :452` "Manage
  tab"** — the v1 `ViewToggle` label; the surviving tab is **Operations**, so `:84`'s path reads
  "Manage → Operations → Scheduler" for a tab that no longer exists and `:452`'s role-gating line
  names it too (the earlier "role-gating names no v1 surface" verdict was wrong on exactly this);
  **`:86` "URL hash navigation"** → the truth is ManageView's own `#manage/<tab>` round-trip at
  mount; `#plan`/`#map`/`#manage` deep links are no-ops (§8.9); `:163` the Backend-heavy bullet — the
  "Close to home proximity ranking" and "v1 arm's own copies of the roll-up and the cell derivation"
  debt entries are **discharged**; `:164` the panel-data-contracts bullet — principle stands, example
  becomes past tense (`/api/user/settings/reach` and `/light` are the live per-user contracts;
  `GET /api/briefing/close-to-home` survives with no client); `:183` the "Close to home carries every
  qualifier" bullet → a two-line backend note. New facts to record so nobody "cleans them up": the
  `DailyBriefingResponse` fields that lose their only client reader — `bestBets`/`bestBetStatus`/
  `bestBetsWithdrawn`/`bestBetModel`, `stale`, `partialFailure`, `failedLocationCount`, and
  `BriefingDay.peak` (already unread since the day rail went in P2 — CLAUDE.md's "the v2 day rail
  renders `BriefingDay.peak`" is stale); `auroraTonight/Tomorrow` keep a reader
  (`WindowFirstDoors.jsx:235-236`). ⚠️ Word the best-bet note precisely: the **field** loses its
  renderer; `BriefingBestBetAdvisor` itself keeps three live consumers (`BriefingModelTestService` →
  `BriefingModelTestView`, `PipelineRunPickEntity` → `PipelineRunsView`'s picks,
  `AdvisorReplayController`) — "the advisor has no renderer" is false and would misdirect Phase 4.
  Root `README.md:19` (v1 description + SSE), `:161` ("Manage tab"), `:253-256`.
- **Docs — strike the live clauses, not just a status line.** The principle: any doc section headed
  as **rules in force** gets its v1 clauses struck where they stand. `plan-matrix-plan.md`: §3 rule 2's
  second sentence ("v1's `markerUtils.RATING_COLOURS` stays frozen" — now the opposite of D3) struck,
  rule 10 struck, §10 risk 5 ("the flag is the page-level kill switch") annotated, §11 discharged,
  `:578` flag recipe struck, the §11c last bullet ticked. `heat-field-plan.md`: the scope guard
  (`:95-99`) discharged, the D2 row (`:121` "v1 keeps `markerUtils.RATING_COLOURS` untouched
  everywhere") and `:205` struck, `:638` (MapView mounted twice now). `window-first-redesign-plan.md:585-599`
  §4 status line. `plan-verdict-consolidation-plan.md:15` (Phase 4's frontend half discharged, backend
  half open). `plan-panel-data-contracts.md` — **rewritten as a whole where it is live guidance**
  (CLAUDE.md cites it as the record): Section 1 whole table (row 1's Best Bet panel dies with
  `BestBetBanner`; rows 2/4/5 as surveyed), the "five endpoints" paragraph under it (replaced with the
  provider's actual calls), Section 3's panel enumeration, Section 4 (the client module and then the
  panel are both gone; the endpoint exists, unconsumed), Section 5 item 1 marked done.
  `phone-heatmap-blast-radius.md` status line. `frontend-test-standards.md:269` (keep as history,
  "(since deleted)"). `window-first-p15-handover.md` status line. The p10–p14 handovers and the
  `*-prompts.md` are dated records — leave.
- **Dead-export / dead-file sweep — scope stated, then the list.** The brief's D4 parenthetical
  ("zero components … whose only consumer was v1") admits a v1-only and a general reading; the
  general one is taken, because the brief's own D3 clause ("plus any @theme token with **zero
  remaining consumers**") already licenses pre-existing dead code, "knip or grep" names a
  general-purpose instrument, and the headline is "aggressively … minimal yet complete". The
  Feb-2026 precedent `14fce598` ("remove 8 dead components") is the same chore. Two classes are
  **pulled out of the mechanical sweep** as product/ops questions, named in §8 instead:
  `OutcomeModal.jsx` (+ test) — the last UI of a feature CLAUDE.md still lists as a differentiator —
  and the dead **API bindings to live endpoints** (`forecastApi.fetchOutcomes`,
  `authApi.updateMarketingEmailOptIn`, `auroraApi.resetAuroraState`/`triggerAuroraRun`,
  `metricsApi.getBuildInfo`, `skyRatingEvalApi.getSkyRatingEvalResults`). The sweep proper, each
  re-verified with a reverse search at edit time: `StarRating.jsx` (+test), `ScoreBar.jsx` (+test),
  `WindIndicator.jsx`, `VisibilityIndicator.jsx`, `CloudCoverBars.jsx`, `LocationTypeBadges.jsx`,
  `PopoverHost.jsx` → `hooks/usePopoverHost.js` → `utils/popoverPlacement.js` (+test; orphaned by P2
  `caba0b17`). Exports: `briefingDisplay.LOCATION_TYPE_ICONS` re-export, `conversions.metresToKm`
  (only `VisibilityIndicator`), `conversions.formatShiftedEventTimeUk` (⚠️ `leaveBy.js:29,44` cites
  it by name in comments — rewrite them in the same hunk), `markerUtils.standDownColour` (the
  function; the const stays). Test-only exports kept as test seams (`swrCache.storageKey`,
  `mapDates.ukHour`). Tokens/rules with no consumer at all: `--color-sunrise-*`, `--color-sunset-*`
  (`index.css:29-39`), `.heatmap-cell-visible/-hidden` (`:633-643` — delete by selector; `:631` is
  the tide-row `@media`'s closing brace), `--color-verdict-awaiting` (`:51`, kept alive only by a
  comment at `WindowSheetDialog.jsx:45`). `--color-runbar-1..5` (`:143-147`, "deferred post-pilot")
  are a deliberate parking — left, comment reworded. `public/logo.png` and `public/facebook-cover.png`
  are referenced by nothing in the repo but may be linked from outside — left, noted. The sweep is
  its own reviewable hunk so the owner can revert it alone.
- **Full-surface adversarial review** — seven read-only lenses over the WHOLE frontend (runtime,
  CSS/tokens, test quality, accessibility, plan compliance, deletion completeness, copy), the M5 shape,
  this plan pasted in; one refuter per charge.
- **Bundle check** — §2's table after; chunk-by-chunk diff against §2's expected shape; the entry
  must have shrunk.
- **Final browser verification** — §6's recipe, at 1280 and 390, in headless Chromium for anything
  observer- or rAF-dependent.
- Memory notes (the session's own, out of tree) updated so the next session does not read "flag not
  flipped".

---

## 4. Decisions

### 4.1 The error boundary — redesign, not deletion

`PlanLayoutErrorBoundary` recovers by flipping the flag to v1; with the ternary gone the instance is
never discarded by a type change, so that recovery cannot exist. **The reload trap it closed is still
real without the flag**: the v2 shell hydrates a ~1.3 MB briefing payload from `photocast_swr:v2:`
(`WindowFirstBriefingContext.jsx:13,195`), `useForecasts` likewise, and `reachLens`/`ratingLens`/
`planDoors` read persisted state — a crash reproduced from any of them survives a plain reload. And
the cog and Sign out now live *inside* the guarded subtree (`WindowFirstShell.jsx:1049-1060`) with no
header outside it, so a boundary that offers no route out strands the reader exactly as the
original's docblock describes.

**Shape:** renamed `PlanErrorBoundary` (it is no longer about a *layout*), still a class (React 19 has
no hook form), still mounted inside `<main>` around the provider + shell (so a provider crash is
guarded too — not hoisted; the App test in §3.1 pins that composition). Fallback: `BrandLockup
variant="compact"` (so the page keeps an `h1`), the heading "The Plan stopped working", the error
message (stack to the console), and **two** controls. **"Clear cached data and reload"** does, by
name: `clearSwrCache()` (`swrCache.js:187`, the same call logout makes — removes only `photocast_swr:*`,
the hydrated briefing/forecast payloads), `localStorage.removeItem(PLAN_REACH_KEY)` and
`(PLAN_RATING_KEY)`, `sessionStorage` doors key — the persisted **selections**, cleared not because a
malformed key can throw (the readers are fail-soft) but because a *valid* stored selection re-selects
the same render path on every reload, so clearing the payload cache alone can land the reader straight
back in the crash; the auth keys are untouched and the reader **stays signed in** — say so in the UI
copy. `mapFilter*` and `showStanddownLocations` are deliberately left: bounded values, fail-soft
readers, and clearing a map preference for a Plan crash is collateral. A plain "Reload" is
deliberately not offered: on a crash that reproduces from persisted state it returns the reader to the
same panel, a control whose only effect is itself. **"Sign out"** (`onSignOut` prop) is the other
control — the subtree took the only sign-out with it. It still does not act on its own (the original's
argument holds). `recoverLabel` dies. Tests: the healthy pass-through; the fallback on a throw;
"never acts on its own"; the clear-and-reload action — seed `photocast_swr:` + both lens keys + the
doors key + `goldenhour_token`, click, assert the first four are gone, the token survives, and the
reload fired. ⚠️ **jsdom 30 cannot stub `window.location.reload` directly** (own non-configurable
property; `spyOn`/`defineProperty` throw) — use `vi.stubGlobal('location', { ...window.location,
reload: vi.fn() })` with `vi.unstubAllGlobals()` in afterEach (vitest defines window keys on the
global as configurable getters), or give the boundary an injectable `reload` prop defaulting to
`() => window.location.reload()`; the codebase has no precedent to copy (`RegisterPage.jsx:186` calls
it untested). The sign-out route; `role="alert"`; the heading.

### 4.2 One ramp — `scoreRamp` is the map's only colour language

Instructed by the owner, and it closes the design bundle's open question 2
(`docs/design/plan-matrix/README.md:299-302`: "Marker colours on the Map tab don't match the field …
Two systems for one quantity. Flag for a decision — don't unilaterally pick one"; the heat-map
README's recommended resolution at `:197-202` is "move markers to the verdict ramp so the map has one
colour language … Do not silently ship both ramps meaning the same thing"). The decision is now made:
`RATING_COLOURS` dies, and **every** marker, cluster bubble and star-filter swatch on the map — heat
view, Medallions view, the Plan overlay, aurora and astro modes — paints on `scoreRamp`. The Medallions
view survives as "field off, pins on". The visible consequence, stated so it is chosen and not
discovered: Medallions-view and overlay pins change colour (5★ `#3B6D11` bottle green → `#8AAE72`
sage; 3★ `#FAC775` → `#E0A542`). `buildMarkerSvg`'s dark ink (`#0f172a`) measures 2.96:1 at 1★ and
3.70:1 at 2★ on the ramp — already true of every v2 heat-view marker since P4, and noted at
`windowFirstSpots.js:44-49` as P4's marker rather than the badge; with one ramp it is every marker.
Not a change this series makes; a follow-on the owner may want (§8). The `HeatmapGrid` astro cell,
the one non-map `RATING_COLOURS` reader, is a dead path (§3.3 fact 3), so no non-map surface changes.

### 4.3 The modal ruling — route-by-route stays; rule 10 is discharged; the structural fix is a named follow-on

M5 deferred this here: the "at most one modal" property is held route by route (the masthead search
button refuses a third layer; the cog closes every Plan dialog before opening settings; the three map
handoffs close the popup first), and the structural alternative — `inert` on the shell's content root
while any dialog is open — reverses `useDialogFocus`'s app-wide settled decision. The question put
to D3 was whether v1's departure changes the constraint that forced per-route handling.

**Ruling: no, and so the regime stays.** The facts:

1. `useDialogFocus.js` never mentions v1. Its three reasons for refusing containment are (a) a live
   focusable query that must cope with Leaflet mutating tab stops inside `MapOverlay`, (b) its "two
   `document.body` portals" a containment rule would special-case — a count from the hook's own date
   (2026-08-05), when `BottomSheet` and the then-live `PopoverHost` were the candidates; at `1281e64a`
   the body portals are `BottomSheet`, `WindowSpotPeek` (aria-hidden), the `HeatmapGrid` cell tip
   (focus-free), `PopoverHost` (an orphan, swept in D4) and `BriefingSummaryStrip`'s tip (dies in D2)—
   after the series only `BottomSheet` is a focus-taking dialog a rule would special-case, which keeps
   the reason alive without the stale count, (c) a dialog with no focusable children
   (`UserSettingsModal`'s refresh spinner) — plus the empirical jsdom facts (`showModal` undefined,
   `'inert' in HTMLElement.prototype === false`, so a trap built on either fails as a silent no-op in
   tests). **The constraints survive v1.** Removing v1 deletes none of them.
2. What v1's departure *does* delete is plan-matrix §3 **rule 10** — "any edit to `Modal` must leave
   v1 byte-identical without the opt-in, with a pinning test per surface". That rule is now discharged
   (struck in D4 with rule 2's ramp clause): `Modal` may change without a per-surface v1 pin. That is
   a freedom, not a reason.
3. The per-route guards exist because three dialog surfaces live **outside** the shell root
   (`UserSettingsModal` and `MapOverlay` are siblings of the shell in `App`; `BottomSheet` is a body
   portal) and the shared hook has no containment — neither fact is v1's. A shell-scoped `inert`
   would not reach them, nor the `AuroraBanner` button, the run-complete banner, the footer links: it
   is "containment of the shell", not a trap, and Tab from the topmost dialog still exits to the
   footer and wraps. A true cycle needs `inert` on the App-level siblings too — i.e. the containment
   design the hook refused, now with portals to order.
4. It would also transfer the lazy-chunk hazard (`WindowFirstShell.jsx:69-94`): `inert` is synchronous
   and the covering dialog arrives when its chunk does, so "one dialog, zero live layers" becomes
   "zero dialogs, whole page inert" unless `stacked` is first gated on the covering layer having
   mounted — the prerequisite §11c already names and M5 left open.
5. This series is a removal. Cycling Tab inside every v2 dialog is a behaviour change to every v2
   dialog, must be browser-measured (jsdom cannot see `inert`), and is exactly the class of change
   the owner said to flag rather than absorb.

**So D3 records the ruling and changes nothing structural.** What it does: rewrites the v1 prose in
`Modal.jsx`/`useDialogFocus.js`/the shell's cog comment, strikes rule 10, and hands the structural
fix to §8 with its design facts (a new inner wrapper inside `shellRef` so the dialogs stay siblings
of the inert content; the predicate `openWindowKey != null || stackedOverPopup || searchSeed != null`
already exists; gate `stacked` on mount first; App-level siblings need the same treatment or the
cycle is partial; two unguarded keyboard routes the survey found — the doors' `HotTopicStrip` map
buttons reached from an open popup, and `AuroraBanner`'s View-on-map — which are §11c's "residual"
class and are listed there as **inferred from code, not browser-measured**).

### 4.4 Smaller decisions, recorded once

- **`usePlanLayout.test.jsx` is renamed, not deleted** (§3.1) — 95 % of it is the shell's main suite.
- **Renames in this series happen only where a deletion forces one** — the name belongs to code that
  is going (`usePlanLayout.test.jsx` is the shell's suite; the `cth-preview-*` keyframes live in the
  deleted `.cth` block and `.wf-peek` reuses them) — or where the owner licensed a redesign
  (`PlanLayoutErrorBoundary` → `PlanErrorBoundary`: "Layout" asserts a flag that no longer exists). A
  qualifier that is merely redundant (`WindowFirst*`, `wf-`) is neither, so it stays — §8.
- **`NlcSightingBanner` ships inert** (what v2 renders today); a v2 route is a new feature.
- **`useTodaysLight` loses `enabled`** — its only reason was the arm gate.
- **`WindowFirstShell`'s two `lazy()` boundaries stay** with a truthful reason (chunk hygiene / the
  dialog is a drill-down), not "the other arm's first paint".
- **`WindowFirstBriefingProvider` stays inside the boundary** (not hoisted): a provider crash is a Plan
  crash and the fallback is where it should land — pinned by the App test in §3.1.
- **`planDoors.js` keeps its behaviour** (session persistence of the doors) with its rationale
  rewritten; dropping the persistence because its old reason was v1 is a behaviour change.
- **`computeCellTier` stays; `isCellVisible`/`qualityTier` go; `isRegionFullyHidden` stays** (§3.3).
- **The D4 sweep's scope is the general reading, defended in §3.4**; `OutcomeModal` and the live-endpoint
  API bindings are pulled out as §8 owner questions; the sweep is its own reviewable hunk.
- **No clean-up write for stale storage keys** (§3.2).
- **The username / session-days line is not ported to the masthead** (§3.1) — v2 never drew it.

---

## 5. Forced v2-visible changes — the ledger

Rule: deleting v1 must not change what a reader of the v2 Plan sees; where it would, stop that hunk,
record it here, leave the v2 surface as it was, and flag it to the owner.

| change | status |
|---|---|
| Map markers, cluster bubbles and the star-filter swatch in Medallions view, the Plan overlay and aurora/astro modes recolour from `RATING_COLOURS` to `scoreRamp` | **Instructed by the owner** (the map's only colour language; closes open question 2). Not forced. |
| The Plan error boundary's fallback changes shape (clear-and-reload + sign out, instead of "back to the current Plan") | **Instructed** (redesign requested because the old recovery stops existing). Only visible when the Plan crashes. |
| A Heat↔Medallions toggle no longer remounts the marker/cluster layer — an open marker popup, a spiderfied cluster and the selected marker now **survive** the toggle, and markers no longer re-chunk on it | **Consequence of the instructed ramp merge, absorbed deliberately.** The `key` at `MapView.jsx:1883` existed solely so cluster bubbles re-ran `iconCreateFunction` when the palette flipped; with one palette its only remaining effect would be closing popups. Nothing pins either behaviour today; D3 adds the pin (popup stays open across the toggle). If the owner prefers today's teardown, keep the key with a comment saying teardown is now its purpose. |
| The Regional planner stops fetching `/api/astro/conditions` once per rendered date on every Plan load | **Invisible, network-only.** The astro grid path it fed is unreachable (§3.3 fact 3, verified on the enum + projector); zero rendered change. Taken because the astro cell was the last non-map `RATING_COLOURS` reader; not owner-named, recorded here rather than absorbed silently. The map's ASTRO mode keeps its own fetch. |
| everything else | **none.** The v1 header's username/session-days line, the v1 Map tab's error/retry card and "No forecasts loaded yet" card, the `NlcSightingBanner` CTA and `#map` hash navigation were never part of v2; v2 is unchanged by losing them. `MapSizeSync`'s `enabled` collapse, `useTodaysLight`'s gate, `BrandLockup`'s default (all surviving callers pass an explicit variant — and the corrected §3.3 list keeps the auth tagline and the masthead kicker), `HealthIndicator`'s variant, `HeatmapGrid`'s two opt-ins, the `.cth` keyframe rename and the CSS deletions are verified no-ops on the v2 surface (§3). |

If a phase finds a case not in this table, it stops that hunk, records it here, leaves the v2
surface as it was, and flags it to the owner in the phase report.

---

## 6. Verification recipe

Per phase, gated on exit codes only (echo `$?` as its own statement; never grep the output):

1. `cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build`
2. `npx eslint --rule 'semi: [2, always]' src` — the ASI class CodeQL catches and ESLint's config
   does not (today: 1 known hit in `CloseToHome.test.jsx`, gone at D2; expected 0 after).
3. Before the owner merges: the PR's code-scanning annotations (CodeQL runs on `pull_request:`).
   After each merge: `gh api 'repos/gregochr/goldenhour/code-scanning/alerts?state=open&per_page=100' --jq length`
   and compare with the pre-phase count. A CodeQL job failing in under a minute is a build-step
   fault — re-run it.

Then, where the phase is observable: **create** `frontend/.env.local` with
`VITE_API_TARGET=http://localhost:8083` (gitignored, absent in a fresh worktree; without it every
request 502s at login); backend `cd backend && ./mvnw -Plocal-dev spring-boot:run
-Dspring-boot.run.profiles=local` (port **8083**, H2 — no Docker; detect startup by `Started
GoldenHourApplication` in the log); `scripts/dev-seed-locations.sh` (stop → RunScript → start around
direct inserts; save the home postcode in the UI **before** inserting `user_drive_time` /
`region_drive_time` rows); sign in `admin` / `golden2026`; drive the page in headless Chromium via
Playwright for anything the pane cannot see. D2 additionally runs `npm run test:e2e` against this
stack (§3.1). Each phase's Phase-log row says which claims were seen in a browser, which asserted in
jsdom, and which only reviewed.

The token audit runs both ways after every `index.css` edit (§3.2).

---

## 7. Measurements — after

Filled in by D4. Same table as §2 plus the chunk diff against §2's expected shape (entry shrinks;
`DateStrip` → pane chunk; `astroApi` → MapView chunk) and the count of files, tests, LOC, CSS lines
and tokens removed.

---

## 8. Follow-ons — named, not done

Backend / product, out of this series' scope by the owner's instruction:

1. **plan-verdict-consolidation Phase 4, backend half** — the `/api/briefing` `bestBets` /
   `bestBetStatus` / `bestBetsWithdrawn` / `bestBetModel` fields and `applyBestBetFallback` lose
   their only renderer in D2. ⚠️ `BriefingBestBetAdvisor` itself keeps three consumers with live
   admin UIs (`BriefingModelTestService` → `BriefingModelTestView`, `PipelineRunPickEntity` →
   `PipelineRunsView` picks, `AdvisorReplayController`) — the consolidation plan's "conditional"
   line still applies; do not read "no renderer" as licence to delete the advisor.
2. **`BriefingDay.peak`** — write-only since P2 retired the day rail; clean up with the above.
3. **`GET /api/briefing/close-to-home`** + `CloseToHomeService`/`CloseToHomeResponse` + tests — a live
   endpoint with zero callers once `getCloseToHome` goes in D2. Delete, or wire to v2 (the location
   sheet's "windows here" answers a neighbouring question).
4. **`DailyBriefingResponse.stale` / `partialFailure` / `failedLocationCount`** — lose their only client
   reader in D2; v2 shows the forecast's age only. A v2 surface for "this briefing is partial" is a
   product call.
5. **Backend Javadocs that cite the v1 arm as live** (comment-only; a docs-only backend commit or
   Phase 4 cargo): `PlanRenderLimits.java:21-25` — which says of itself "Keep the two equal by hand
   until the v1 arm is removed, at which point this note goes with it"; `BriefingDayPeak.java:37-38`;
   `BriefingService.java:118` ("DailyBriefing.jsx caps the grid…"); `DisplayVerdict.java:5` and
   `HotTopic.java:13` (the "quality slider").
6. **LITE pricing** — `HotTopicStrip`'s fact blur on the pill vs readable on the card; the LITE score
   split. Parked.
7. **Outcome recording has had no UI since 2026-02-27** (`OutcomeModal`'s mount went with
   `ForecastCard` in `14fce598`). **Owner question, deliberately NOT in D4's sweep**: delete
   `OutcomeModal.jsx` (+ test) and the orphaned `forecastApi.fetchOutcomes`, or keep them as the seed
   of a v2 outcome route (the popup? the location sheet?). CLAUDE.md still lists the feedback loop as
   a differentiator and LITE's one write permission.
8. **Dead API bindings to live endpoints** — `authApi.updateMarketingEmailOptIn`,
   `auroraApi.resetAuroraState`/`triggerAuroraRun`, `metricsApi.getBuildInfo`,
   `skyRatingEvalApi.getSkyRatingEvalResults` — zero callers; deleting a client for a live admin
   endpoint is an ops call, not v1 retirement. One-line cleanups when the owner nods.
9. **The structural "one modal" fix** — `inert` on the shell's content root while any dialog is open,
   with §4.3's design facts and prerequisites (gate `stacked` on the covering layer having mounted;
   App-level siblings; browser measurement). Plus the two unguarded keyboard routes found by survey
   (doors' map buttons, `AuroraBanner`), unmeasured.
10. **A forecast-fetch error / retry surface for the Map tab** — v1's Map tab had one; v2 withholds
    the tab on empty and on error alike (`App.jsx:546-551`), and `useForecasts` keeps returning
    `loading`/`error` to nobody.
11. **`#plan`/`#map`/`#manage` deep links and `AuroraBanner`'s simulated-alert `#manage` jump** —
    no-ops on v2 today; route through `tabRequest` if wanted.
12. **`NlcSightingBanner` destination** — inert on v2; a route through the overlay like the aurora
    banner's is plumbing that does not exist (`buildMapOverlay` has no dark-sky trigger).
13. **Marker ink contrast on the ramp** (2.96:1 at 1★) — pre-existing on every v2 heat-view marker;
    with one ramp, every marker.
14. **Lens readout squeeze, the chips' duplicate tab stops, `PlanSearch`'s undebounced `role="status"`,
    `MastheadLight`'s double scope announcement, the two 403s per LITE session** — §11c items the flip
    inherits unchanged.
15. **`WindowFirst*` / `wf-` naming** — redundant qualifier now; a rename is churn for no behaviour.
16. **`HotTopicStrip.jsx:77,88`'s private copies of `weatherCodeToIcon`/`msToMph`** — pre-existing
    duplication of `briefingDisplay`'s.
17. **`--color-runbar-1..5`** — parked tokens with no consumer; an owner call.
18. **The username / admin session-days line** — v1's header had it; v2 never did. Port if wanted.
19. **A v2 e2e smoke in CI** — the salvaged spec (§3.1) still runs only by hand.

---

## 9. Risks worth watching

1. **The HeatmapGrid fixture trap** (§3.3): collapsing `serverCellRating` makes the fixtures that
   ride the deleted slot-tree fallback lose their star — three tests go red, roughly ten more go
   silently quieter. Fix every fixture that carries `claudeRating` (add `meanRating`), including
   Gate 2 and lightly-evaluated; do not weaken the assertions.
2. **CSS ranges**: two lens-supplied ranges were off at a boundary (one reached into `MapOverlay`'s
   keyframes, one started on the wrong block's closing brace) and the `.wf-peek` keyframes live
   inside the `.cth` block. Delete by selector, re-read each boundary, run the token audit both
   ways, and open the page.
3. **The comment sweep by regex** would damage nine correct "byte-identical" and several "arms" sites
   (§0 trap 7, §3.3 exclusions). Sweep by meaning.
4. **Orphaned `eslint-disable` directives and newly-unused imports/helpers fail lint** under
   `--max-warnings 0` (the NLC banner's pair; `fireEvent`/`twoDayBriefing` in the DST test; every
   deleted describe's private fixtures; any `set-state-in-effect` disable whose effect moves).
5. **Sequential phases mean a fresh `main` every time**: `git fetch` + a zero
   `rev-list --count HEAD..origin/main` before branching; the CHANGELOG block is written fresh under
   whatever `[Unreleased]` then holds.
6. **Scope creep in D4's sweep**: the scope argument is §3.4's opening; every "dead" claim is
   re-verified with a reverse search at edit time; the sweep is its own reviewable hunk so the owner
   can revert it alone; `OutcomeModal` and the live-endpoint bindings are excluded by name.

---

## 10. Review of this plan — the tally

Run 2026-08-23/24, before the plan commit: six read-only prosecutor lenses (scope fidelity;
completeness against the tree — ~60 citations re-checked, independent reverse-import and CSS-class
sweeps; the v2-visible-change ledger; test-salvage discipline; process/CI traps; docs/CLAUDE.md
truthfulness) produced **41 charges**; each got its own refuter, defaulting to REFUTED. 25 refutations
ran as independent agents; a provider quota interrupted the rest, and the remaining 16 were refuted by
the implementing session directly against the tree (each claim re-read at its cited lines; the
verdicts below say which). **Every charge was upheld at least in part; none was refuted outright** —
a measure of how much a 63 KB plan can still get wrong against a 50 kLOC tree.

The two **blocking** finds, both fixed above: TS-1/C2 — the plan's DELETE range
`HeatmapGrid.test.jsx:2245-2263` contained the suite's only pin of the scroll port's keyboard
reachability (now split: `:2246-2254` KEEP); L1 — the BrandLockup instruction was lifted from the
component's own docblock, which is wrong about the tagline (auth pages render it) and ambiguous about
`KICKER.default` (the masthead renders it) — the deletion list is now derived from the JSX.

The upheld majors and where they landed: SF-1/PROC-1 → §0 branching rewritten sequential (the brief's
own words and the M-series precedent; stacking was a reinterpretation, and the owner's
release-between-phases makes it conflict-guaranteed); SF-2 → §3.4's sweep scope defended, `OutcomeModal`
and live-endpoint bindings pulled to §8; TS-2 → the DailyBriefing.test salvage is a whole-file pass
with three named ports; TS-3 → the two ramp invariants fold into MarkerIcon.test, `:435-438`'s clamp
change named; TS-4 → §4.1 names the jsdom reload mechanism and the storage assertions; TS-5 → the
provider-throw App test; TS-6 → the e2e spec is salvaged AND run in D2; DOC-1 → rules-in-force
clauses struck, not status-lined; DOC-2 → CLAUDE.md `:8/:84/:281/:452/:86` + README `:161`; DOC-3 →
the CHANGELOG convention paragraph; L2/C1/C4 → the astro path's full fallout and the honest pin;
L3 → the cluster-remount and astro-fetch ledger rows. The upheld minors are folded where they point
(C3's ranges, C5's `isRegionFullyHidden`, C6's `mapHandoff` comments, L4's enumerated clears, L5's
three literals, L6's portal count, TS-7..10, PROC-2..6, DOC-4..7, SF-3..6). Lens "not examined"
declarations are recorded in the workflow transcript; the notable one: no lens executed the suite or
the build — every "stays green" claim above is by reading, and the phase gates are where execution
happens.
