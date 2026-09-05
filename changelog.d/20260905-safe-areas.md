### Added — the app answers for the notch, the sensor housing and the home indicator

`index.html` now opts in with `viewport-fit=cover`, so `env(safe-area-inset-*)` reports something
other than zero on the hardware that has an unsafe zone, and the four elements that touch a viewport
edge each answer for it: the app root insets everything in normal flow, the Plan tab's sticky lens
bar names the top inset itself (a sticky element sticks to the viewport, so an ancestor's padding
cannot reach it), the bottom sheet insets its sides and pads its foot, and the modal resolves its
gutter and its inset with `max()` rather than summing them.

Every rule is a no-op where the insets are zero — `padding: env(x, 0px)` is `padding: 0`,
`max(1rem, env(x, 0px))` is `1rem` — so desktop, Android and the whole test suite see exactly the
geometry they saw before, and the change is confined to devices that report an inset. A test pins
that invariant by requiring a `0px` fallback on every `env()` in the stylesheet: without one the
declaration is simply invalid on a browser that does not know the variable, and would take the
`top` or `padding` it was carrying with it.

Live today: the **left/right** insets, where a landscape sensor housing currently clips content
outright, and the **bottom** inset, where the home indicator sits over the sheet and the map's
phone bar. The **top** inset stays zero in a home-screen web app until someone decides to add
`apple-mobile-web-app-status-bar-style: black-translucent`, which is a visual decision rather than a
safe-area one; the top rules are written and correct so that becomes a one-line change rather than a
second audit.

The map tab's phone chrome needed no term of its own — its tuned `bottom:` cascade is
`position: absolute` inside a frame the root's padding already insets, so the whole stack moves up
with it.
