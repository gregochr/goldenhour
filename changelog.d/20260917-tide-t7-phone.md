### Added — the Map tab's tide strip reaches the phone (T7 of the tide-window increment)

T6 gated the strip on `!isMobile`; this phase lifts that gate. On the phone the strip takes the
count footer's own row of the lifted chrome stack rather than sharing space with it — "the tide
sentence is the more useful line at that width" (design §6) — so the footer is hidden outright
while the strip is on, not merely lifted alongside it the way every other row in that stack is.
Everything above the strip (the scored-locations chip, the LITE viewline-upsell chip) now clears
its *real* published height (`--tsh`, T6) instead of the footer's assumed ~28px one: open and
collapsed are roughly 4x apart (T6's own measurement, 167px vs 38px), so a fixed offset would be
wrong for one of the two states by construction — the same argument that put `--tsh` on the strip
in the first place.

The strip is mounted as a plain **sibling** of `.wf-map-chrome-bl` on the phone rather than nested
inside it the way the desktop mount is (T6, §4 #6): it has to span `left: 8px; right: 8px` against
the frame itself, the same containing block the count footer and the scored-legend chip already
use, and `.wf-map-chrome-bl` is only ever left-anchored (no `right`), so a descendant of it could
never stretch edge to edge. `MapView.jsx` renders the same `<MapTideStrip>` a second time, gated on
`isMobile` alongside the existing `!isMobile`-gated desktop mount; the component itself needed no
change — it already returns `null` unless `model.visible`, on either mount.

Two knock-on fixes, both because the phone strip is no longer covered by `.wf-map-chrome-bl`'s own
existing obstacle/band seeding:

- `MapLabels.jsx`/`PinsLayer.jsx`'s `OBSTACLE_SELECTOR` gained `[data-testid="wf-tide-strip"]` — on
  desktop the strip is already covered as a descendant of the seeded `.wf-map-chrome-bl`, but on the
  phone it is a sibling with no obstacle entry of its own, so a label or pin could place itself
  underneath an uncovered strip on that one viewport.
- `MapCallout.jsx`'s `BAND_BAR_SELECTOR` gained the same testid. The phone query *hides* the count
  footer outright (rather than lifting it) while the strip is on, so `getBoundingClientRect` on a
  `display: none` footer returns a zero-size rect `calloutBand` already skips — with nothing else
  naming the strip's own floor, the callout card was free to grow down over it.

`frontend/src/test/mapPhoneChromeCascade.test.jsx` gains the strip's row: its own static geometry
(`position: absolute; left: 8px; right: 8px; bottom: 112px; width: auto; max-width: none`, and that
it reverts to the desktop flex-column placement without the `.wf-map-tab` ancestor); the count
footer hidden while `wf-tide-strip-on` and shown otherwise; and — since `calc(var(--tsh))` is
exactly what this suite's jsdom environment cannot resolve numerically (the same constraint
`mapChromeZLadderCascade.test.jsx`'s own `--tsh` test already documents) — a raw-rule-text
extraction of the scored-legend/chrome-bl lift formulas, replayed as arithmetic in JS with T6's own
measured heights (167px open, 38px collapsed) standing in for `--tsh`, proving every pairwise
boundary clears by at least 8px in both states. `MapLabels.test.jsx`/`PinsLayer.test.jsx` each gain
a case in their existing obstacle-seeding `it.each` table for the new selector.

**Adversarial review (5 lenses: runtime behaviour, CSS/tokens, test quality, accessibility, project
conventions) found and fixed three real issues:** a pre-existing, unconditional (no media query)
`.wf-map-tab.wf-tide-strip-on .wf-map-counts-footer { bottom: calc(...) }` rule still fires on the
phone, where the new `display: none` for the same selector already wins visually — harmless, but
dead arithmetic on that one viewport; fixed with a comment rather than a second media-query split.
`mapPhoneChromeCascade.test.jsx`'s new pairwise-clearance check hardcoded the bar's `bottom` as a
bare literal, unlike every other check in that file, which reads it live off the real CSS; fixed to
match. And no test anywhere exercised `MapCallout.jsx`'s `BAND_BAR_SELECTOR` at all — not even for
the pre-existing entries, a gap this phase inherited rather than introduced — closed with two new
`MapCallout.test.jsx` cases proving the callout's placement band clamps above a real `wf-tide-strip`
DOM sibling (and is a no-op without one); both passed on the first run. One accessibility finding
recorded as an owner item rather than fixed: hiding the counts footer outright removes its count/
rated/filtered figures from the accessibility tree while the strip shows, with no substitute
anywhere else on the tab — faithful to the design's own screen-space reasoning, never evaluated for
a non-visual reader; recorded as `tide-window-plan.md` §6 Q8.

`npm run lint && npm test && npm audit --audit-level=high && npm run build` all green — 251 files,
6413 tests, 0 vulnerabilities, build succeeded.

**Browser-verified at 390×844**, headless Chromium via `playwright-core` (`chromium-1208`'s own
binary, since the worktree's `playwright-core` resolved a revision the local cache did not have),
against a freshly seeded local backend and Vite dev server — the same three coastal locations/wants
as T5/T6 (Bamburgh Beach LOW, Dunstanburgh Castle HIGH, Tynemouth Priory HIGH+LOW) — with real
`boundingBox()`/`getComputedStyle` reads, no numbers assumed:

| check | open | collapsed |
|---|---|---|
| strip bottom edge vs bar top edge | 47.5px clear | 47.5px clear (strip `bottom` is a fixed anchor) |
| scored-legend bottom edge vs strip top edge | 12.47px clear | 12.5px clear |
| callout card bottom edge vs strip top edge | — | 8.0px clear, exactly `calloutBand`'s `BAND_EDGE_PAD` |
| counts footer `display` while the strip is on | `none` | `none` |

Strip's own real height: **166.53px open, 63.5px collapsed** — confirming a review-flagged risk was
real: the collapsed row's phone-width height (63.5px) is *not* T6's desktop 38px (open, 166.53px,
does match closely). The geometry held regardless, because everything above the strip is anchored
off its real `--tsh` rather than an assumed constant — proof by measurement of the reason that
mechanism exists. The callout (Bamburgh Beach, Pins mode) rendered fully above the collapsed strip
with no overlap; the strip itself rendered its full content correctly at this width, footer reading
"2 of 3 coastal spots are dimmed — they want low water" with the "beyond these four days" denial.
