### Changed — the landing masthead now uses the app's brand lockup

The landing page opened with an amber dot beside a Bricolage Grotesque wordmark, which
matched nothing in the product. The app's identity is `BrandLockup` — a film-perforation
spine, a Newsreader serif wordmark and a coral "Field guide to light" kicker — and that
component's own notes record that it *replaced* a `logo.png` and an extrabold sans
wordmark precisely for belonging to no part of the Kodachrome Field Guide system. The
landing page had reinvented the thing the app had already discarded.

All five mastheads now render a port of that lockup's `masthead` variant, with the four
brand values (`#F2E7D3` bone, `#E8593F` coral, `#3A2C23` spine rule, `#4A3A2E`
perforations) lifted verbatim from the app's `@theme` rather than re-tuned to the landing
palette. Measured against the component's spec, every value agrees: Newsreader 600 at
21/25/28px with −0.022em tracking, an IBM Plex Mono kicker at 8.5/9.5px, and the 7px-on-
15px spine gauge. The coral reproduces its documented contrast, 5.16:1 here against
5.24:1 in the app.

`DM Mono` is dropped for `IBM Plex Mono`, the app's own mono, so the kicker and the
page's other labels share one typeface rather than two.
