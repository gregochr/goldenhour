# v1 retirement — kickoff prompts for the implementing sessions

Paste one prompt into a **fresh Claude Code session running Sonnet**, in order. That model choice
is deliberate: the plan (`docs/engineering/v1-retirement-plan.md`, adversarially reviewed before
landing — 41 charges, all folded in) encodes the judgement at file:line granularity, so the
implementing session's job is faithful execution plus the cadence, not re-derivation. The two rules
that make Sonnet safe here, repeated inside every prompt because they are the whole bargain:

1. **The plan wins; the tree wins over the plan; you never improvise.** Re-verify every cited line
   against the tree before editing (the plan was read from `1281e64a`; code moves). Where the tree
   merely drifted, follow the plan's intent at the new lines. Where a deletion turns out to have a
   consequence the plan does not name — anything a reader of the v2 surface could see — **stop that
   hunk**, leave the surface as it was, record the case in the plan's §5 ledger, and flag it in
   your final summary for the owner. Do not absorb it, do not invent a workaround.
2. **A review that finds nothing is "not examined", never "clean".** The adversarial review lenses
   run per phase (read-only agents, one refuter per charge defaulting to REFUTED). If a lens
   returns zero findings, report it as unexamined coverage — this project's record says silent
   lenses are how defects pass. The owner may choose to run the D3 and D4 review lenses on a
   stronger model; the implementing session stays Sonnet either way.

Each phase lands as its own reviewed commit before the next session starts — **sequential, never
stacked**: the owner merges (and may release) between phases, so every session begins by fetching
and requiring `git rev-list --count HEAD..origin/main` to be 0 on its new branch. The plan's Status
block and Phase log are the source of truth between sessions; each phase updates them **in the same
commit**. If a session dies mid-phase, start a new one with the same prompt — step one is always
reading the current state from the repo. Sessions never push, never create or delete tags.

**The gate, every phase, on exit codes never output** (echo `$?` as its own statement):
`cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build`, then
`npx eslint --rule 'semi: [2, always]' src` (the ASI class CodeQL catches and lint doesn't; 1 known
hit until D2 deletes `CloseToHome.test.jsx`, 0 after). CodeQL runs on the PR itself — the owner
checks the PR's code-scanning annotations before merging, and after each merge:
`gh api 'repos/gregochr/goldenhour/code-scanning/alerts?state=open&per_page=100' --jq length`.

**Reviewer prompts:** paste the plan's §0 + §5 + §9 into every review agent, **plus the phase's own
§3.x** (D1 also §4.1+§4.4; D3 also §4.2+§4.3; D4 also §2/§7+§8) and the diff. An untracked or
unmentioned plan is invisible to agents.

**Between phases (owner):** review in the browser, merge, optionally release. Expect the next
phase's `CHANGELOG.md` block to be written fresh against whatever `[Unreleased]` then holds.

---

## D0 · The flip, alone

> You are implementing **Phase D0** of `docs/engineering/v1-retirement-plan.md`. First: read that
> plan **in full** — §0 (process), §3.0 (binding), §5 (the ledger and its stop-and-flag rule), §9.
> Confirm the plan itself is on `main` (its Phase log's first row is filled). Never push. Create
> `claude/v1-retirement-d0-flip` off up-to-date `main` (`git fetch`;
> `git rev-list --count HEAD..origin/main` must be 0).
>
> Scope is the flip and nothing else: `usePlanLayout.js` — **all three** `PLAN_V1` default sites
> (`:36` the storage default, `:37` the read fallback, `:38` the write fallback; one
> `DEFAULT = PLAN_V2` constant used at all three, so a garbage or bare-string key cannot quietly
> stay v1) and **both** Javadoc paragraphs (`:26-27` "flipping the default is the last step of the
> build" — it is now; `:29-31` an unrecognised value now resolves to the default, v2). Tests: the
> hook describe in `usePlanLayout.test.jsx:26-64` only (`:29` → "defaults to the window-first
> Plan"; `:49` → store `'v99'`, expect `PLAN_V2`; `:55` keeps its storage assertion with the
> expectation flipped — touch nothing past line 64, the rest of that file is the shell's suite),
> and the two `App.test.jsx` tests that pin the default (`:161`, `:200` — they become
> explicit-`PLAN_V1` tests, since the v1 arm still exists until D1, and `:177` becomes the default
> test). A stored JSON `"v1"` still renders v1 — that is the rollback hatch, deliberate until D1;
> do not "fix" it.
>
> Gate on exit codes. Run a small adversarial review of the diff (~3 read-only lenses — runtime,
> tests, scope-fidelity-to-§3.0 — one refuter per charge; a zero-finding lens is reported as
> unexamined, not clean). Browser-verify per the plan's §6 recipe: a fresh profile (no stored key)
> lands on the window-first shell; `localStorage.setItem('photocast.planLayout',
> JSON.stringify('v1'))` + reload lands on v1. Say what was seen versus asserted. Commit once:
> conventional message, a `### Changed` block first under `CHANGELOG.md` `[Unreleased]`, and the
> plan's Status + Phase-log row (commit cell "*(fill after merge)*") in the same commit. Do not push.

---

## D1 · Flag machinery, toggle, boundary

> You are implementing **Phase D1** of `docs/engineering/v1-retirement-plan.md`. First: read the
> plan in full — §3.1, §4.1 and §4.4 are binding; §0/§5/§9 always. Confirm D0 is on `main` (Phase
> log). Re-verify every cited line against the tree. Never push. Create
> `claude/v1-retirement-d1-flag` off up-to-date `main` (rev-list count 0).
>
> Scope is §3.1's table, exactly. The traps, restated: **`usePlanLayout.test.jsx` is RENAMED to
> `WindowFirstShell.test.jsx`, not deleted** — 95 % of its 1,331 lines are the shell's main suite;
> delete only the hook describe (`:26-64`) and the two exit tests (`:75-79`, `:759-769`), fix
> `planOriginShell.test.jsx:15`'s citation of the old name, and sweep the dead `onExit: vi.fn()`
> from the nine harness files §3.1 lists. In `App.jsx`, delete only what the table deletes — the
> KEEP list (`selectedDate`/`effectiveDate`/`allDates`/`autoSelection`, both lifts, the home
> state, `tabRequest`/`mapTabHandoff`, `isDown`, the run banner) is load-bearing for the v2 Map
> pane and overlay — and rewrite the three comments that name the deleted `mapHandoff`
> (`App.jsx:343-355`, `WindowFirstMapPane.jsx:63-77,85`): the rule survives, the identifier does
> not. Add the "Deliberately NO `heat`" comment at the overlay's `<MapView>` mount (`:752`), in
> the shape of its `onSelectDate` neighbour. `NlcSightingBanner`: the `eslint-disable`/`enable`
> pair at `:149`/`:164` must go with the handlers — an orphaned disable directive **fails lint**
> under `--max-warnings 0` — and the dismiss handler's `e.stopPropagation()` (`:142`) dies with
> the click handler it stopped propagating to. `useTodaysLight` loses its `enabled` parameter
> (its only reason was the arm gate); trim its tests per §3.1. The boundary is a **redesign per
> §4.1, not a deletion**: rename to `PlanErrorBoundary`, keep it a class, keep it inside `<main>`
> wrapping the provider + shell, two controls ("Clear cached data and reload" — `clearSwrCache()`
> + both lens keys + the doors sessionStorage key, auth keys untouched, the copy says the reader
> stays signed in; and "Sign out" via a new `onSignOut` prop). ⚠️ jsdom 30 cannot stub
> `window.location.reload` (`spyOn`/`defineProperty` throw): use `vi.stubGlobal('location',
> { ...window.location, reload: vi.fn() })` with `vi.unstubAllGlobals()` in afterEach, or an
> injectable `reload` prop. Its tests seed `photocast_swr:` + both lens keys + the doors key +
> `goldenhour_token`, click, and assert the first four gone, the token surviving, the reload
> fired. Add the **provider-throw test** to `App.test.jsx` (the `providerSpy` at `:117` already
> exists: `mockImplementation(() => { throw … })` → `findByRole('alert')` → fallback heading +
> reachable Sign out) — it pins §4.4's provider-inside-the-boundary decision. Rewrite
> `frontend-test-standards.md:135-149`'s role-query example (its subject, the plan-layout switch,
> dies here) against a surviving control. Delete `.wf-exit-foot` (`index.css:1305-1309`) with the
> exit button.
>
> Gate on exit codes. Adversarial review (~5 lenses: runtime, tests, a11y, scope-vs-§3.1,
> comments/copy; refuters; zero findings = unexamined). Browser-verify per §6: the app boots with
> no flag machinery, the settings modal has no "Window-first Plan" section, the NLC banner (if
> displayable) draws no CTA and takes no tab stop; the crash fallback is jsdom-asserted — say so
> rather than claiming it was seen. Commit once: `### Removed` CHANGELOG block + the plan's
> Status/Phase-log row in the same commit. Do not push.

---

## D2 · The v1 component estate

> You are implementing **Phase D2** of `docs/engineering/v1-retirement-plan.md`. First: read the
> plan in full — §3.2 binding; §0/§5/§9 always. Confirm D0+D1 are on `main` (Phase log).
> Re-verify every cited line. Never push. Create `claude/v1-retirement-d2-estate` off up-to-date
> `main` (rev-list count 0).
>
> Scope is §3.2. Delete the six components and their suites exactly as listed — and **only**
> those: the survivors that look v1 (`HeatmapGrid`, `HotTopicStrip` + `TideRunRow`/`SurgeRunRow`/
> `CertaintyChip`/`InfoTip`, `MapView`/`DateStrip`/`MapOverlay`/`MapHeatLayer`/`BottomSheet`/
> `MarkerPopupContent`, `ManageView`, everything in `components/shared/`) each have a named v2
> importer in §3.2; re-run the reverse search before every file delete anyway. The traps,
> restated: **`DailyBriefing.test.jsx` salvage is a whole-file pass keyed by behaviour** — port
> the three named orphaned behaviours before deleting (the drill-down's `slot-drive-time` with a
> non-empty `driveMap`, its `slot-type-icon` with a `typeMap`, and `toggleDrillDown`'s
> second-cell switch — no surviving suite asserts any of them). `briefingDayStepDst.test.jsx`
> keeps `:135-160` and `:257-300` only, deletes the residue that would fail lint (`fireEvent`,
> `twoDayBriefing`, `TOMORROW_SUNSET_PICK`), and takes the **positive mock set** §3.2 states —
> including **adding `regionApi`** (`fetchRegions`, `fetchRegionDriveTimes`), which the file
> never mocked. **CSS is deleted by selector, not by range** — re-read every boundary; the block
> ends at `:2355`, NOT `:2358` (`:2357-2364` is `MapOverlay`'s open animation), and the three
> `@keyframes cth-preview-*` inside the `.cth` block are **reused by `.wf-peek`** — rename them
> `wf-peek-*` and update `:3631-3636` in the same edit or the v2 peek loses its entry animation
> invisibly. Never rename the four string markers the token-guard tests slice by (§3.2 lists
> them). The Tailwind scanner rule: a bare token name in a **JS/JSX comment** keeps a token
> emitted — the `--color-marginal`/`--color-dust` deletion must sweep the comment mentions in
> `WindowSpotPeek.jsx`, `MastheadLight.jsx`, `WindowSpotStrip.test.jsx` in the same commit.
> `regionChipVerdictColour.test.jsx` dies **with** the `.summary-region-chip` rules (it fails
> loudly otherwise). `getCloseToHome` goes with its mock keys in three test files (an import of
> a deleted export fails; a dead mock key is just noise). `computeCellTier` is NOT deleted —
> only `TIER_KEYS`/`TIER_LABELS`/`computeAuroraCellTier` here. Rewrite the "copied from
> CloseToHome/DailyBriefing" licence comments in survivors to past tense (§3.2's list), including
> `WindowSpotPeek.jsx:131`'s citation, whose target is `frontend-test-standards.md:225`, not
> `:156`. Run the token audit **both ways** after the CSS edits. Salvage the e2e spec by
> behaviour (v2 tabs; the error-handling block dies; port 8083) **and run it**:
> `npm run test:e2e` against the §6 stack, recording pass/fail per test in the Phase-log row —
> an edited spec nothing executes is the silent-replace trap in another form.
>
> Gate on exit codes. Adversarial review (~6 lenses: runtime, CSS/tokens, test-salvage
> completeness, a11y, scope-vs-§3.2, deletion completeness; refuters; zero findings =
> unexamined). Browser-verify per §6 at 1280 and 390: the Plan tab, both doors open (the
> regional panel's grid and the hot-topic strip are the re-parented survivors — this phase is
> when a missed CSS dependency would show), the spot peek still animates in, login page still
> carries the tagline. Commit once: `### Removed` CHANGELOG block + Status/Phase log. Do not push.

---

## D3 · The compatibility layer collapses

> You are implementing **Phase D3** of `docs/engineering/v1-retirement-plan.md`. First: read the
> plan in full — §3.3, §4.2 and §4.3 are binding; §0/§5/§9 always. Confirm D0–D2 are on `main`
> (Phase log). Re-verify every cited line. Never push. Create `claude/v1-retirement-d3-compat`
> off up-to-date `main` (rev-list count 0).
>
> Scope is §3.3. This is the phase the plan's review flagged hardest — its traps, restated:
> **`HeatmapGrid.test.jsx:2245-2263` is TWO tests and only one dies**: `:2256-2262` goes with the
> opt-out state; `:2246-2254` ("is focusable and named when it is a port") is the suite's ONLY
> pin of the scroll port's `tabindex=0`/`role=region`/"scrolls sideways" name — KEEP it, drop its
> `scrollable: true`, retitle. **The fixture fallout has two lists**: three tests go red
> (`:162`, `:1523`, `:1933`) and roughly ten go silently quieter — add `meanRating` to **every**
> fixture that carries `claudeRating`, including Gate 2 (`:1619-1700`) and lightly-evaluated
> (`:1707`), not only the failing ones; `:1510` is unaffected (drill-down reads slots); re-home
> `:1465-1483` into the drill-down describes unchanged. `isRegionFullyHidden` is **NOT dead**
> when `isCellVisible` goes — keep it minus that term; `computeCellTier` stays (row order). The
> **astro path** is deleted on the full inventory in §3.3 fact 3 — including the panel test
> file's `describe('astro conditions')` at `:183-221` with its mock and resets (all four tests go
> red the moment the fetch goes), the ASTRO arm of the column-header ternary (`:1249-1251`), and
> the `astro-heatmap-cell` selector in `index.css:726-730` — and `HeatmapGrid.test.jsx:623-634`
> is NOT a pin of unreachability (SUNSET-only fixture); write the promised pin from served
> `eventSummaries`. **BrandLockup's deletion list comes from the JSX, not its docblock — the
> docblock lies**: the tagline (`:137-141`) renders on the three auth pages and the masthead
> renders `KICKER.default` (`:134`); delete only `isHeader`, the `text-[40px]` arm, the `header`
> arm of the `:118` ternary, the `'header'` propType value and default; add the auth-page pin
> (tagline + kicker present). The **ramp merge** (§4.2, owner-instructed): when deleting
> `markerRampOptIn.test.js`, first fold its two surviving invariants into `MarkerIcon.test.jsx`
> as literal-stop assertions (the ×20 round-trip → `RAMP_STOPS[3].hex`; interpolation →
> `rampHex(3.5)`) — an assertion of `rampHex(starsFromAverage(x))` restates the code and pins
> nothing; `MarkerIcon.test.jsx:435-438` goes red and its replacement claim is the behaviour
> change (out-of-range clamps to the ramp's end, no grey fallback); sweep the `RATING_COLOURS`
> keys from the eleven `vi.mock` factories §3.3 lists. Removing the `MarkerClusterGroup` remount
> key changes behaviour: an open popup now **survives** the Heat↔Medallions toggle — that is the
> §5 ledger's third row, absorbed deliberately; add the pinning test (popup stays open across the
> toggle). Rewrite the Modal/useDialogFocus counts **without a number**. Fold
> `MapViewHeat.test.jsx:229-236` into `:239` (the overlay is the no-heat mount) and re-target or
> delete `:246-253` (its mount shape no longer exists). The **comment sweep is by meaning, never
> regex**: §3.3 lists the false sites AND the excluded true ones ("byte-identical" as name-join,
> "arms" as branches, "v1 mock" as the design bundle) — touch nothing on the exclusion list. The
> **modal ruling is already made** (§4.3: route-by-route stays; rule 10 discharged) — record it,
> do not re-litigate it, change nothing structural.
>
> Gate on exit codes. Adversarial review (~6 lenses: runtime, CSS/tokens, test quality, a11y,
> scope-vs-§3.3, copy/comments; refuters; zero findings = unexamined — the owner may run this
> phase's lenses on a stronger model). Browser-verify per §6 at 1280 and 390: the Map tab in heat
> AND medallions view (one colour language — same marker colours in both), the Plan overlay from
> a card (ramp colours, no heat toolbar), a popup surviving the view toggle, the regional
> panel's grid (stars from `meanRating`, keyboard-focusable scroll port), the login page
> unchanged. Commit once: `### Removed` CHANGELOG block + Status/Phase log. Do not push.

---

## D4 · The settling commit

> You are implementing **Phase D4** of `docs/engineering/v1-retirement-plan.md` — the final
> phase. First: read the plan in full — §3.4, §2/§7 and §8 are binding; §0/§5/§9 always. Confirm
> D0–D3 are on `main` (Phase log). Never push. Create `claude/v1-retirement-d4-settle` off
> up-to-date `main` (rev-list count 0).
>
> Scope is §3.4. CLAUDE.md is rewritten truthful per the enumerated list — including the three
> sites the first survey missed (`:84`/`:281`/`:452` "Manage tab" → the Operations tab; `:86`
> "URL hash navigation" → ManageView's own sub-tab round-trip; `:8`'s frontend line has three
> dead terms of four) — and the best-bet note is worded precisely: the `/api/briefing` `bestBets*`
> **fields** lose their renderer; `BriefingBestBetAdvisor` keeps three live admin consumers — "the
> advisor has no renderer" is false and would misdirect Phase 4. Docs: **strike the clauses that
> stand in rules-in-force sections** (plan-matrix §3 rule 2's RATING_COLOURS sentence, rule 10,
> §10 risk 5; heat-field's D2 row and `:205`), not just status lines; `plan-panel-data-contracts.md`
> is rewritten wholesale where live (Section 1 table incl. row 1, the five-endpoints paragraph,
> Section 3's enumeration, Section 4, Section 5 item 1). The **dead sweep is its own commit-hunk**
> with the scope §3.4 defends: the listed orphan components/exports/tokens only, each re-verified
> by reverse search at edit time; **`OutcomeModal` and the five live-endpoint API bindings are
> EXCLUDED by name** (they are §8 owner questions); `formatShiftedEventTimeUk`'s deletion sweeps
> the `leaveBy.js:29,44` comments that cite it; `.heatmap-cell-visible/-hidden` is `:633-643` by
> selector (`:631` is another block's closing brace). Then: the bundle check (§7 — fill the after
> table; the entry must have shrunk; the two expected lazy-chunk growths are named in §2, any
> OTHER growth is investigated), and the **full-surface adversarial review** — ~7 read-only
> lenses over the WHOLE frontend (runtime, CSS/tokens, test quality, accessibility, plan
> compliance, deletion completeness, copy — the M5 shape), one refuter per charge, this plan
> pasted whole; fix survivors; re-gate. Final browser verification per §6 at 1280 and 390,
> headless Chromium for anything observer/rAF-dependent. If a memory note about this series
> exists in your auto-memory, update it to COMPLETE.
>
> Commit once: `### Changed` CHANGELOG block + the plan's Status flipped to COMPLETE with D4's
> Phase-log row. Do not push. **End your final summary with, verbatim to the owner's brief: the
> before/after file and bundle numbers (§2 vs §7); every place a deletion forced a v2-visible
> change (§5's ledger — expected none beyond the instructed and absorbed-deliberately rows); the
> D3 modal ruling (§4.3); and what the follow-on list contains (§8).**
