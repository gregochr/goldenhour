### Added — the app answers for the notch, the sensor housing and the home indicator

`index.html` now opts in with `viewport-fit=cover`, so `env(safe-area-inset-*)` reports something
other than zero on hardware that has an unsafe zone. The insets are declared once as `--safe-t/r/b/l`
(plus the `--safe-v`/`--safe-h` sums a dialog cap needs, since a `max-height` competes with both
insets on its axis), and every element that touches a viewport edge reads them: the app root insets
everything in normal flow, the Plan tab's sticky lens bar names the top inset itself (a sticky
element sticks to the viewport, so an ancestor's padding cannot reach it), the bottom sheet insets
its sides and pads its foot, the modal resolves its gutter and its inset with `max()` rather than
summing them, and `MapOverlay` — inline-styled, so no rule could reach it — does the same for its
own 24px gutter.

Every rule is a no-op where the insets are zero. Measured in Chromium against the built stylesheet:
at zero insets the root computes `0px`, the modal `16px` on all four sides (exactly the `p-4` it
replaces) and the phone popup `0px`; with a 34px bottom inset the popup's foot moves from flush to
34px clear. A test pins the invariant by requiring a `0px` fallback on every `env()` in the
stylesheet — without one the declaration is simply invalid on a browser that does not know the
variable, and takes its `top` or `padding` with it.

**Two ways an inset gets silently cancelled, both found by adversarial review and both now guarded
by tests.** A descendant can beat the ancestor that set it: `[data-testid="window-sheet"]` was
`padding: 0` — deliberately, to drop the popup's frame on a phone — and being unlayered, equal in
specificity and thousands of lines further down, it won on source order and cancelled the modal's
safe padding outright, on the one device class that has an unsafe zone, for the Plan tab's flagship
dialog. And a `max-height` can reconstruct the gutter arithmetically: five sites encoded the modal's
`p-4` as a literal `32px`, so each ate the whole safe-area padding and left its panel's foot exactly
where it was. Both classes now have a sweep in `safeAreas.test.jsx`, and the sweep found a third
`scroll-margin-top` reservation site that two hand fixes had missed.

Live today: the **left/right** insets, where a landscape sensor housing clips content outright, and
the **bottom** inset under the home indicator. The **top** inset stays zero in a home-screen web app
until someone decides on `apple-mobile-web-app-status-bar-style: black-translucent`, which is a
visual decision rather than a safe-area one. Every CSS consumer of the top inset now carries its
term, but that change is **not** the one-line edit an earlier draft of this work claimed:
`useStuckSentinel` is called with `offset = 0` and would need the inset too. That is recorded where
someone making the change will read it.

The map tab's phone chrome needed no term of its own — its tuned `bottom:` cascade is
`position: absolute` inside a frame the root's padding already insets, so the whole stack moves up
with it.
