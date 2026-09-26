### Added — the phone tide rule: Auto/Always/Off, one gate, a one-shot pulse

Phase M5 of `docs/engineering/map-mobile-sheet-plan.md`. `utils/mapPeek.js#tideVisible({ mode,
tideAvailable, hasCoastalInView, tier })` is the pure Auto/Always/Off rule: `off` is always false;
`always` reads only `tideAvailable` (a served solar tide, ignoring the coast-in-view test and the
verdict — a night row still shows nothing); `auto` requires all three — a served tide, a named
coastal spot in the padded viewport, and the window's own served verdict at `WORTH_IT` or `MAYBE`
(a `null`/`AWAITING` verdict reads as "not Maybe or better", never as a pass). `mapTideFit.js` gains
`coastalInView(spots, bounds)`, the `bounds.pad(0.12).contains(...)` filter `stripModel` already ran
internally, lifted to its own export so there is one definition — `stripModel` now calls it too, and
`tideVisible`'s caller calls it a second time on the map's UNDECORATED spot list, before `tideTier`
exists, taking care to pass `.length > 0` rather than the array itself (an empty array is truthy).

`MapView` computes the three inputs from data available BEFORE the spot-build site — the block
computing `mapEvents`/`evVerdicts`/`activeMapEvent` moved earlier in the render for exactly this
reason, since the rule needs the active window's own verdict tier before `labelSpots` decorates
anything — and the single `tideTier` null at that site (§1 #6) now reads `tideCuesOn ? tierOf(tide)
: null`, with `tideShortfall`/`tideFitPhrase` following it; every consumer (`MapLabels`,
`PinsLayer`, both tooltips, the dimming CSS) is unchanged, because they all already keyed off that
one field. `tideCuesOn` is `!isMobile || tideVisible(...)`, so desktop/tablet are provably
untouched. The Tide peek button and its section now render exactly `iff tideVisible`, reusing M3's
close-and-rescue mechanism unchanged in shape — only the gate's source moved from
`stripModel.visible` to `tideCuesOn`.

A one-shot pulse (`.wf-map-peek-btn-pulse`, a `box-shadow` sweep, 1.2s, removed on its own
`animationend` rather than a timer) plays on a false → true transition of `tideCuesOn`, armed only
after the FIRST bounds-backed evaluation (`tideViewBounds` is `null` until `BoundsTracker`'s mount
effect reports the real viewport, so a coastal Worth-it window's first resolved visibility is itself
a post-mount transition that must not pulse). Dropped under `prefers-reduced-motion: reduce`.

The Layers section gains a Tide row — a `.wf-seg` Auto/Always/Off segment plus a fixed hint line —
wired to `useReaderSettings`' existing `mapTideMode`/`saveTideMode` (M4), which already serialises
saves and reverts to the last persisted mode on failure; `MapView` only reacts to the outcome,
announcing a failure through the pane's one `role="status"` region and clearing that message the
instant a fresh press is made.

**Two Codex retrospective findings on #927/#928, fixed here** (both land in code this phase already
edits):

- **#927 — the Layers Show (Heat | Pins) row rendered unconditionally on phone.** When
  `heatOffered` is false (no served heat data, or an astro/aurora window on screen), both `heatOn`
  and `heatPinsOn` are forced false and neither `MapLabels` nor `PinsLayer` mounts — the desktop
  toolbar cluster was already correctly gated on `heatOffered`; the phone Layers row now matches it.
- **#928 — the close-and-focus-rescue effect could never actually rescue focus.** It compared
  `document.activeElement === tidePeekBtnRef.current` from inside a `useEffect`, which runs strictly
  after the commit that unmounts the Tide button when its own gate turns false — by then the
  browser has already reset focus to `<body>` and React has already nulled the ref (proven with a
  plain jsdom `removeChild` call). M3's own close scenarios never happened to land on a
  focused-and-unmounting button in a real browser pass, so this stayed latent until M5's Auto rule
  made it routine. Fixed by tracking focus independently: `focus`/`blur` handlers on the Tide button
  itself maintain a `tideButtonHadFocusRef` (a `blur` event is not fired when a focused element is
  simply removed, so the ref's `true` survives the unmount intact), and the rescue effect reads and
  resets that ref instead of comparing `document.activeElement` after the fact.

A third finding surfaced by this phase's own adversarial review (not from Codex): **Always mode can
open the Tide section with NOTHING coastal in the padded viewport** (Always deliberately ignores the
coast-in-view test), which made `MapPeekTideSection` fall through to `footerModel`'s "no coastal
spot here has its water on this light" sentence — a sentence that, on the desktop strip, can only
mean "spots are in view but none carry a served alignment fact" (the desktop strip's own mount is
gated on `stripModel.visible`, so an empty viewport was structurally impossible there). The phone
section now checks `model.visible` itself and states the honest, distinct reason ("No coastal spot
in view — pan the map to see one.") rather than printing the ambiguous shared sentence for a case
the desktop strip can never reach.

**Tests**: `tideVisible`'s full 3×2×2×5 truth table (`mapPeek.test.js`); `coastalInView`'s own unit
tests including the empty-array-is-truthy trap (`mapTideFit.test.js`); new `MapPeekSheet`-level
pulse-class-present/absent tests; two new `MapPeekTideSection` tests for the `model.visible` fix; a
new `MapViewMobileTideRule.test.jsx` proving the WIRING a unit test cannot reach — a coastal spot in
view on a Poor window carries no `data-tide` on any chip or pin though its tide is served and
aligned, Off/Always invert that on the same fixture, panning the spot out of view hides it on an
otherwise Worth-it window, the pulse fires only on a genuine post-seed transition, the
close-and-rescue actually lands focus on Other windows (and does NOT steal it when the button never
held it — the `#928` fix's own negative case), the Layers segment calls `saveTideMode` and surfaces
a failure, and desktop/tablet carry `data-tide` on a Poor window regardless of the saved mode; two
new tests in `MapViewMobilePeekSheet.test.jsx` for the `#927` fix (Show row present/absent on
`heatOffered`); a cascade test reads the pulse keyframes and its reduced-motion override off the
real stylesheet.

⚠️ One test was written and removed: `fireEvent.animationEnd` reliably invoking a React
`onAnimationEnd` handler proved non-deterministically flaky the moment ANY other test file shares
its vitest worker — reproduced with a trivial `<div onAnimationEnd>` canary paired against
already-merged, unrelated files (`MapLabels.test.jsx`, `MapViewMobilePeekSheet.test.jsx`), with the
identical file combination passing on some runs and failing on others, in both directions. This is
a pre-existing jsdom/React event-delegation quirk in the suite, not a defect in the wiring it was
testing (a source read confirms `onAnimationEnd={onTidePulseEnd}`/`onTidePulseEnd={clearTidePulse}`
are both wired, and the pulse-class tests already exercise the class itself appearing/disappearing
with state); recorded here so the missing coverage reads as a decision.

**Browser (390 × 844, the seeded fixture with today's Worth-it sunset and tomorrow's Poor sunrise,
real coastal `tide_extreme` data, SEEN AND MEASURED)**: stepping the pill to the Poor window removes
the Tide button and every `data-tide` glyph on the coastal chips shown; zooming into an inland view
removes the button on the still-Worth-it window (Dunstanburgh Castle's dim count drops from 4 to 2
to 0 as the padded viewport narrows); zooming back out restores it; the Layers section's Tide row
renders with Auto pre-selected and the exact hint copy. Desktop (browser default width) is
unchanged: the landing card, the standing tide strip and its own footer render exactly as before, no
`.wf-map-peek` in the DOM. NOT independently confirmed this session: a mid-flight screenshot of the
pulse animation itself (the tool's round-trip latency exceeds the 1.2s window; the class's
appear/clear behaviour is TESTED, not photographed) and the Always/Off segment's live visual effect
or its persistence across reload — pressing Off/Always against the shared local backend on :8083
returned `500` from `PUT /api/user/settings/map-tide-mode` (confirmed directly with `curl`, and via
H2 introspection: `app_user` has no `map_tide_mode` column on that running instance's database,
though the source's V155 migration and `AppUserEntity` both declare it — the shared backend process
needs restarting to pick up schema changes made since it was last started, which this session's
rules withhold permission for). The FAILURE path was genuinely exercised and behaved correctly: the
segment stayed on Auto (never got stuck showing the failed choice) and the pane's `role="status"`
region read "Could not save tide mode — kept the last saved choice." — a real, unscripted proof of
`saveTideMode`'s own revert-on-failure rule, for what that is worth. The success/persistence path
is TESTED (`MapViewMobileTideRule.test.jsx`'s Layers-segment describe block, `useReaderSettings`'s
own M4 tests) but not SEEN live this session.
