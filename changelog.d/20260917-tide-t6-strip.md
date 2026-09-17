### Added — the Map tab's tide strip (T6 of the tide-window increment)

Bottom-left of the Map tab, mounted as the last child of `.wf-map-chrome-bl` so the legend and
viewline-upsell chips clear it by flex order — the day's tide curve with HIGH/MID/LOW ruled across
it, night shaded, sunrise and sunset marked, the light on the curve with its height, and a footer
naming what is dimmed and jumping to the next window that fits. Visible only when the window on
screen is solar, the briefing served a window tide fact, and at least one coastal location sits in
the viewport padded 12% — never a mode, never a toggle; the only control is collapse, held in
`MapView` state so it survives a window change. Publishes its own real height as `--tsh` on the
`.wf-map-tab` root so the bottom-centre counts footer clears it (`calc(var(--tsh, 120px) + 16px)`);
the legend and upsell chips above it need no such arithmetic, since flex order already clears them.

**A real wiring gap between the served payload and the map was found and fixed.** T3's own
`mapEvents.js#solarRow` already read `served.tide`, but nothing between `GET /api/briefing`'s real
`window.tide` and that `served` object ever copied it forward: `buildWindowCards` (Plan tab
matrix), `buildHeatStripCards` (the heat strip's thumbnails) and `WindowFirstMapPane`'s own
`heat.windows` mapper each build their own reduced shape of a window, and none of the three carried
`tide` through. Every layer's own unit tests fed the next one a hand-built fixture that already had
`.tide` on it, so the gap was invisible to a fully green suite and only surfaced once the strip was
rendered against a live payload in browser verification. Fixed by threading `tide: win?.tide ?? null`
through all three folds, with a targeted test at each hop (`windowFirstCards.test.js`,
`windowFirstStrip.test.js`, `WindowFirstMapPaneHeat.test.jsx`) — the last named for exactly this
failure mode, mirroring the file's own precedent test for a prior instance of the same defect class
(`pickKind`).

New `components/map/MapTideStrip.jsx`; `utils/mapTideFit.js` gains `dominantWantCount` (the
footer's "N of them want" population, computed once and shared with the next-fit scan so the two
can never disagree) and `siblingEventTime` (a lookup, not a formula, for the chart's sunrise/sunset
clock labels — `BriefingWindowTide` states where the sun rises/sets on the tide axis but not the
clock time itself, and the sibling solar EV row already carries it, served). `windowFirstRows.js`
exports its existing `STATE_WORD`/`DIRECTION_WORD` tables for reuse rather than a second copy.
`MapView.jsx` gains the mount, a `tideStripCollapsed` state, a second `BoundsTracker` for the tab's
own Leaflet viewport (the overlay's own stays plain-array; the strip needs a real `pad`/`contains`
pair), and the `stripModel`/sibling-time wiring. New CSS block in `index.css`, tokens throughout
(`--color-tide` for the curve stroke, `--color-badge-tide` for the state word/dot/band-hit label,
`--color-verdict-marginal` for the sunrise/sunset rules, `--color-plex-text-muted`, `--color-plex-border`,
`--font-mono`) — no bare hex.

**Adversarial review (6 lenses: runtime behaviour, CSS/tokens, test quality, accessibility, project
conventions, impact on the next phone phase) found and fixed:**

- The curve stroke and the sunrise/sunset rules hardcoded their hex values instead of referencing
  `--color-tide`/`--color-verdict-marginal` (the values happened to match, so nothing looked wrong
  on screen) — fixed to reference the tokens (the marginal rule via `stroke-opacity`, since the
  design's alpha isn't baked into that token).
- **The chart's `aria-hidden` was on the `<svg>` alone, not the whole overlay.** The sunrise/sunset,
  extrema and light-height labels are real HTML text siblings of the svg, so a screen reader
  landing on that block by linear navigation read disconnected fragments ("↑ 05:44", "HW 08:47",
  "2.6 m") with none of the sentence structure the header and footer already state them in — exactly
  what "the whole chart is aria-hidden" (the design's own rule, and this file's own header doc) was
  supposed to prevent. Fixed by moving `aria-hidden` onto the chart's wrapping div.
- The collapsed row's "Open ▴" button had no accessible-name treatment for its glyph, unlike the
  open state's "Collapse" and unlike `MapCallout`'s identical ▾/▴ toggle, which wraps the caret in
  its own `aria-hidden` span. Fixed to match that precedent.
- No focus management on the next-fit jump: activating it commonly resolves the very fact the
  button existed for, so it unmounts on the next render with nothing to catch focus, silently
  dropping a keyboard/screen-reader user to `<body>`. Fixed by giving the strip's root a
  programmatic `tabIndex={-1}` and moving focus onto it after the jump, and by adding
  `role="group" aria-label="Tide"` so the strip is a landmark a screen reader can jump to as one
  unit in the first place.
- A tautological test: the dot-on-curve self-consistency check built its fixture's `windowLevel`
  by calling the exact same interpolation function the test then used to verify it, so two calls to
  one deterministic function could never disagree regardless of a real bug. Fixed by deriving the
  fixture's `windowLevel` from an independent continuous formula and sampling the discrete `curve`
  against it — mirroring `TideSurfaceAgreementTest`'s own two-independent-computations shape.
- An `offsetHeight` stub was restored inline at the end of each test body rather than in `afterEach`,
  so a failed assertion earlier in any of those three tests would leave
  `HTMLElement.prototype.offsetHeight` pinned globally for every later test in the file. Fixed.
- A `siblingEventTime` guard test used a night row whose own `time` field was already empty, so
  removing the `kind === SOLAR` filter it existed to prove would have passed anyway (the `|| null`
  fallback coerces empty strings regardless). Fixed by giving that row a real time.
- Missing coverage the phase's own task list named directly: a night-rect-widths test, a rendered
  (not just pure-function) assertion of the footer's leading count sentence, and the
  `windowLevel`-not-finite half of the dot-placement guard. Added.
- One `container.querySelector('svg')` reach-into-DOM-structure, against this project's own test
  standards — swapped for a `data-testid` on the element.

`npm run lint && npm test && npm audit --audit-level=high && npm run build` all green (6355 tests,
0 vulnerabilities, build succeeded).

**Browser-verified, not merely asserted** — headless Chromium via `playwright-core` (the Browser
pane would not composite in this session), against a locally seeded fixture: three SEASCAPE
locations with real `tide_extreme` rows and distinct wants (Bamburgh Beach HIGH, Dunstanburgh
Castle LOW, Tynemouth Priory HIGH+LOW, reaching the null-shortfall arrow case), a built briefing.
Measured at 1280×800: strip height 167px open / 38px collapsed; `--tsh` and the counts footer's
computed `bottom` agreed exactly in both states (167+16=183px; 38+16=54px); the light dot's
`left`/`top` matched `windowPosition·100`/`TY(windowLevel)` to the pixel, and the night rects'
`x`/`width` matched the served `sunrisePosition`/`sunsetPosition` exactly; every text node's
contrast against the actual rendered (blurred, composited) background ranged 7.13:1–15.42:1, clear
of the 4.5:1 floor. Panned to the Lake District and zoomed in tight on it: the strip disappeared,
`--tsh` and the `wf-tide-strip-on` class were removed, and the counts footer's `bottom` returned to
exactly `8px`. **Not seen live**: a night/astro row hiding the strip — the seeded fixture offered no
astro or aurora EV rows to pick without additional dark-sky seeding outside this phase's scope; that
branch is covered by the automated suite (`mapTideFit.test.js`'s `stripModel` visibility tests, and
`MapTideStrip.test.jsx`'s own visibility suite) rather than observed on screen.
