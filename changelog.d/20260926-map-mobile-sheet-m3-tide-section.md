### Added — the phone tide strip becomes the peek sheet's Tide section

Phase M3 of `docs/engineering/map-mobile-sheet-plan.md`. `components/map/MapTideStrip.jsx` is
split into three exported pieces the desktop/tablet strip keeps composing unchanged —
`TideStripHeader`, `TideDayChart` (the served curve, night shading, dashed HIGH/MID/LOW rules, the
light marker and its height) and `TideStripFooter` (the dimmed-count sentence and the next-fit
jump/denial) — a refactor pinned by a one-off `outerHTML` golden-string test (captured from the
pre-split component, deleted at M6) so the desktop strip's own rendered DOM is provably untouched.
`TideDayChart` gains a `tall` projection (viewBox height 92 instead of 32, algebraically identical
at the default via a `× (32/32)` no-op) that the new `components/map/MapPeekTideSection.jsx` reuses
for the sheet, alongside `TideStripFooter` unchanged — the section's own header line (key, phase,
height/time) is new layout, but the chart and footer read the identical served facts the desktop
strip already prints, so the two can never disagree.

The peek sheet gains its third button — `MapPeekSheet` mounts `Tide` (fixed key
`TIDE AT THIS LIGHT`, never swapping to `CLOSE` the way `Other windows` does) gated on
`stripModel.visible`, with a value line from a new `utils/mapPeek.js#tideSummary` (the served tide
state as one word, an arrow only when `MID`, and the strip's own licensed dimmed count — omitted
entirely at zero rather than printed as "0 dim"). `MapView` owns the close-and-rescue this button
needs: the `‹ ›` steppers can land on a window with no served tide (a night row, or a solar window
with no coastal spot in view) while the Tide section is open, unmounting its own trigger — the
section then closes in the same render and, if the vanishing button held focus, hands it to Other
windows. Because this component's early "no forecast data" return sits between where every hook
must run and where the tide state is actually computed, the close-and-rescue is a `useRef` hand-off:
an effect declared ahead of that return reads a `{active, tideVisible}` pair a plain (non-hook)
assignment further down writes fresh every render — the ref read always sees the render that just
committed, never a stale one. M5 replaces only the gate's source (`stripModel.visible` →
`tideVisible(...)`), reusing this mechanism unchanged.

The phone `MapTideStrip` mount and its `--tsh`-driven phone CSS
(`.wf-map-tab .wf-map-tide-strip`, `.wf-map-tab.wf-tide-strip-on .wf-map-chrome-bl`) are retired
outright — `--tsh`/`wf-tide-strip-on` are desktop/tablet-only from here, and the phone's
`.wf-map-chrome-bl` bottom is the plain M2 literal in every state.

**Tests**: the `outerHTML` pin; `MapPeekTideSection.test.jsx` (every line from one `stripModel`
fixture, the zero-dimmed sentence, the jump calling `onSelectEv` with the scanned row and focusing
the stable target *before* that call, the disappearing-link keyboard path, the beyond denial);
`tideSummary`'s five named cases; `MapPeekSheet.test.jsx`'s new Tide-button describe (the AND-gate,
the fixed key, ref attachment, one-body-at-a-time); `mapPhoneChromeCascade.test.jsx`'s phone
tide-strip describe rewritten to assert the retired rules' absence and the unconditional
`calc(74px + 27px + 8px)` literal; `MapViewTideStripCalloutWiring.test.jsx` re-pointed to assert no
`MapTideStrip` mounts on the phone at all (not merely that it's unreachable) and that
`tideStripHeight` therefore never leaves the `null` `MapCallout`'s `--psh`-based band reads instead.

**Adversarial review (three read-only lenses)**: no findings survived. One lens re-derived the
`yForAt`/`32÷32` floating-point identity by hand and confirmed the desktop strip's `outerHTML`,
CSS and focus-order are provably unchanged; one lens read every new computation
(`tideSummary`, the section's height/time join, the `tall` viewBox) against CLAUDE.md's
Backend-heavy rule and found each one a licensed filter/map/select over already-served facts, never
a new derivation; one lens verified the `useRef`/no-dep-array close-and-rescue effect is race- and
loop-safe, every prop MapView passes matches `MapPeekSheet`'s PropTypes, the chart stays
`aria-hidden` as a whole, and `eslint --max-warnings 0` passes clean.

**Browser (390 × 844 and 1280 wide, seeded with real coastal tide extremes, SEEN AND MEASURED)**:
phone — the Tide button shows `~Mid ↑ · 4 dim` on tonight's Worth-it sunset; opening it renders the
section against screenshot 03's structure (key, phase line, height/time, chart with HW/LW labels
and the light dot, dimmed sentence, next-fit line); stepping the pill to tomorrow's Poor sunrise
updates the section in place without hiding the button (M5's own rule, not this phase's); a map
touch collapses the open section back to 74px. Desktop at 1280 wide — the strip renders unchanged
(`TIDE AT THIS LIGHT`, the state/direction phrase, the chart, the same dimmed-count/beyond footer
text), no `.wf-map-peek` present. TESTED, not merely seen: the served fixture's tide never produces
a "jump" (only "beyond") case within its 4-day horizon, an artifact of the synthetic seed's phase —
the jump path itself is exercised at the unit level (`MapPeekTideSection.test.jsx`,
`MapTideStrip.test.jsx`), not in the browser this session.
