### Changed — the Map tab bleeds past the safe area instead of being letterboxed by it

Everywhere else in the app the root's `.app-safe` padding insets the whole page and nothing in
normal flow has to know about the notch, the sensor housing or the home indicator. The Map tab is
the one surface where that is the wrong trade: the map is scenery, and insetting it costs real
picture. Measured against the built stylesheet: a 34px band of page colour under the map on a
390×844 phone, and 63px of lost map on **each** side of an 844×390 landscape one (the 47px inset
plus the deliberate 16px `sm:px-4` gutter) — about 15% of the width, to keep coastline out of a
strip the reader can see through anyway.

The map frame is now pulled back out over the root's padding by exactly the padding, and every
control floating above it takes the inset as its own term instead. Measured before and after at
390×844 with a 34px bottom inset: the frame's foot moves from y=810 to y=844 — reaching the screen
edge — while the Regions/Heat-Pins/Filters bar stays at y=802, unchanged, 42px clear of the home
indicator. In landscape the map recovers 47px on each side and the chrome's left edge still lands
at 71px against a 47px housing.

⚠️ **The negative margins and the `calc()` terms are one mechanism.** Remove either half alone and
the result is worse than before this change: the margins without the terms put the bottom bar under
the home indicator — the exact defect the safe-area work existed to fix, reintroduced by the change
meant to improve the same surface — and the terms without the margins inset the chrome twice. A test
pins both halves together.

The whole tuned phone cascade (bar 8, attribution 76, counts 112, scored chip 152, upsell 192) keeps
its literals and gains the identical `+ var(--safe-b)`, so every pairwise clearance that
`mapPhoneChromeCascade.test.jsx`'s SWEEP pins is preserved by construction rather than by
re-tuning. That sweep now runs its full pairwise matrix at **two** insets, 0 and 34 — it could only
ever see zero before, which is the easy case.

Scoped to `.wf-map-tab`, the tab's own root. `MapView` mounts on both surfaces and the frozen
Plan-tab overlay renders no such class, so the overlay cannot inherit the bleed — it sits inside a
normally-inset page and must stay there. Scoping settles that without anyone having to audit which
chrome classes the overlay happens to render.

The masthead, the tab bar and the banners above the map are **not** bled. They are chrome with text
in them and keep the root's padding; only the picture extends.
