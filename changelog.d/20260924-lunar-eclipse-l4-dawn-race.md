### Added — The dawn race, and the popup's topic-note aside (frontend, L4)

Phase L4 of `docs/engineering/lunar-eclipse-plan.md` §2.7, §3: the window popup's dawn/dusk race
timeline, the generic `badge.note` aside renderer, and the `LUNAR_ECLIPSE` plumbing that mounts
the race for the chosen spot's served sight.

**`utils/dawnRace.js`** (new): `raceModel(sight)` — pure filter/map/select over the served
`BriefingSlot.EclipseSight` (floor a track start, clip a track end, decide the umbra/hatch bands
from the already-served `setsInShadow`/`risesInShadow` booleans, turn instants into `[0,1]`
positions and a CSS gradient via `MastheadLight.buildRuleGradient`); `raceSentence(sight)` builds
the one accessible sentence from the same model; `formatRaceTime(ms)` is the display formatter.
DAWN and DUSK are handled as genuine mirrors (DUSK ceils the track's far edge and clips the near
one toward moonrise, rather than re-deriving DAWN's rule blind).

**`components/DawnRace.jsx`** (new): the track (`aria-hidden`), the umbra/hatch bands, three
line-and-dot marks (maximum, the race event, the horizon transition), the phase ticks, the label
row, the legend, and the one visible accessible sentence. Renders nothing when there is no race to
draw. Phone (< 560px) hides the track-start and race-event labels and every phase tick.

**`components/WindowTopicRows.jsx`**: a new full-width aside renders `badge.note` for *any* badge
that carries one, in the safety-note's own shape (`.wf-trow-warn`), prefixed `◑` for the eclipse
channel only. `badge.note` has been served and unread since the promoted strip was deleted at M5 —
this is the honest fix, and it reveals the **solar** eclipse's own note too, not only the lunar
one, since the renderer is channel-agnostic by design (plan §4 #8).

**`components/WindowSheetDialog.jsx`**: mounts `DawnRace` between the topic rows and the tide row,
gated on both a `LUNAR_ECLIPSE` topic row being present on the window *and* the chosen spot's slot
carrying a served `eclipse.race`. The spot is the window's `bestReach`, else the first ranked spot
(`card.spots[0]`) — `WindowSpotStrip` exposes no per-card selection callback, so the race is drawn
for one fixed spot and captioned with its name (plan §4 #11).

**`utils/locationSheet.js`**: new `buildEclipseIndex(days)`, structurally identical to
`buildTideAlignmentIndex` — `{byId, byName}`, id-first lookup through the shared `lookupForWindow`,
a slot with no `eclipse` simply skipped (never indexed as an absence).

**`components/WindowFirstShell.jsx`**: builds `eclipseIndex` via `buildEclipseIndex(briefing?.days)`,
gated on `openCard` (the popup being open) — the same "nothing reads it while closed" gate
`slotIndex`/`sheetTideAlignmentIndex` use for their own dialog.

**`index.css`**: a new `.wf-race*` block for the track, bands, marks, labels, legend and sentence.

#### ⚠️ A real timezone bug, found by adversarial review and fixed before commit

Two independent review lenses (and a live check against a running backend) found that the first
cut ran every `EclipseSight` instant through `formatTime` — the formatter `BriefingSlot.solarEventTime`
genuinely needs, because that field is a bare-serialised UTC instant. `EclipseSight`'s own instants
are a **different** convention: `EclipseSightAssembler` passes every one of them through
`LunarEclipseWording.toLondonLocal` before serialising, so the served digits are already
Europe/London wall-clock time. Running them through `formatTime` applied that conversion a second
time, printing every eclipse label and the accessible sentence an hour late for the seven months of
BST — invisible in GMT, where the two conventions coincide, and invisible to the first draft's own
tests, which compared `raceSentence`'s output against `formatTime(sight.X)` — the same wrong
conversion on both sides of the assertion. Confirmed against a live local backend (2026-09-24, BST):
a served `eclipse.stops[SUNRISE].time` of `06:54:56` sits exactly one hour ahead of that window's
`solarEventTime` of `05:55:25` for the identical real sunrise — the gap *is* the already-applied
offset. Fixed: `formatRaceTime` now reads the served digits back on the **UTC** calendar (the exact
instant `toMs` parsed them onto), which is a lossless round trip to what the backend sent, never a
second Europe/London conversion. `raceSentence`'s tests now assert **literal** `HH:mm` strings
copied off each fixture's own digits, not the formatter under test, so the regression cannot hide
behind a self-fulfilling assertion again — and a component-level test renders `DawnRace` and asserts
the same literal label text in the DOM.

#### A second real defect, found by algebraic proof against the formulas

The hatch band's outer edge used the **track's own boundary** (`trackEnd`/`trackStart`) rather than
the eclipse's true umbra boundary (`umbraEnd`/`umbraStart`). The track boundary's own `+15 min`/
`-15 min` pad can, in a narrow but physically plausible timing window (moonset, the race stop and
`umbraEnd` landing close together), overshoot the true end by several minutes — the hatch would then
claim the moon is still below the horizon, in shadow, after the eclipse had actually finished (and
the DUSK mirror, understating the start symmetrically). Fixed by clamping each hatch band to
`Math.min(trackEnd, umbraEnd)` / `Math.max(trackStart, umbraStart)` — a change that can only ever
shorten the band, never lengthen it past what the track already allows. Pinned by two new regression
tests reproducing the exact counterexample in both directions.

#### Review findings addressed without a functional change

- The class doc's "the sentence is the entire accessible answer" claim was scoped too broadly — the
  sentence covers the plan's own five-item label row (minus the bare track-start time, which names
  no event); the three phase ticks are the design's own secondary annotation layer and were never
  meant to be spoken. Corrected the doc rather than stuffing three extra clock times into an already
  dense sentence.
- `.wf-race-cap`'s extra `opacity: 0.75` stacked multiplicatively on `--color-plex-text-secondary`'s
  own 0.66 alpha, landing at ≈4.43:1 against the panel — just under the 4.5:1 AA floor for this 9px
  text. Removed; the inherited token's own alpha already reads as quiet against the title beside it.

#### Accepted, not fixed

- `raceSpot = card.bestReach || card.spots[0]` can caption a location the rating floor has since
  excluded from the visible spot strip below it. This mirrors an existing precedent elsewhere on the
  same card (the matrix's own best-reachable line reads `bestReach` independent of the floor, per
  CLAUDE.md's Backend-heavy bullet) and the plan's own §2.7 text does not license a different rule
  for this one popup-local case — left as-is rather than inventing a narrower selection the plan
  never asked for.
- A negative `moonAltAtMax` (the moon below the horizon at maximum, which `EclipseSight` does not
  gate against) prints as `"the moon -3° up"` — a plausible but unhandled phrasing for an edge case
  the seeded catalogue's eligibility rule (3° for 30 min, or 3° at maximum) makes rare.
- The phone media query hides a whole `.wf-race-tick` (dashed line plus its label) rather than only
  its label text, a literal deviation from the plan's exact phrasing — kept as the defensible reading
  (an orphaned dashed line with no label serves no purpose).

Tests: `dawnRace.test.js` (geometry to the boundary — track-start floor, track-end clip, the hatch
clamp regression in both directions, DUSK mirror, positions monotone in `[0,1]`, literal-string
`raceSentence` assertions), `DawnRace.test.jsx` (mount conditions, the track's `aria-hidden`,
literal clock labels regression, phone-hidden roles, a full DUSK render pass — labels, hatch
direction, the "leaves shadow" wording, the sentence's "Rises" opening), `WindowTopicRows.test.jsx`
(the aside for lunar and solar badges, the channel-gated glyph, both notes coexisting with the
pre-existing safety note), `WindowSheetDialog.test.jsx` (the race's two-part mount gate, the
best-reach/first-ranked spot fallback, DOM ordering between the topic rows and the tide row, the
real join from raw `days` through `buildEclipseIndex`, and the `(i)` click leaving the dialog open —
the plan's own named exit criterion), `locationSheet.test.js` (`buildEclipseIndex`'s id-first
lookup, the name-keyed fallback, the "no eclipse on this slot" skip, and that an eclipse lands on
its own window only). Frontend gate: `npm run lint` clean, all Vitest tests pass, `npm audit`
reports 0 vulnerabilities, `npm run build` succeeds.

Adversarial review: three read-only agents (general runtime/CSS/test/accessibility/conventions
sweep; an independent DUSK-geometry-and-backend-contract pass; a focused accessibility pass) found
the timezone double-conversion (two lenses, independently), the hatch-overshoot defect (algebraic
proof plus a constructed counterexample), the accessible-sentence scope overclaim, and the
`.wf-race-cap` contrast shortfall. All four fixed and re-tested; the two accepted findings above are
recorded rather than silently dropped.

Browser verification: **seen** — a real local backend (2026-09-24, BST) with the `LUNAR_ECLIPSE`
admin simulation active, seeded locations and cached ratings for Northumberland & Tyneside's
SUNRISE window, served a genuine `BriefingSlot.eclipse` payload for Dunstanburgh Castle. Today's
own Plan-tab cell reads "this morning has gone" (the sunrise window has already elapsed) and cannot
be opened — the known limitation the plan's §7 names. Per that section's own fallback, the captured
served payload was mounted directly (`DawnRace` and `WindowTopicRows`, a temporary, never-committed
dev entry) at desktop and 390px: the track renders with the correct twilight gradient, `aria-hidden`
confirmed via `getComputedStyle` in a real browser (not jsdom), the umbra/hatch bands and all three
line-and-dot marks draw correctly, the accessible sentence reads
"In shadow from 03:33, maximum 05:12 with the moon 8° up, sunrise 06:54, sets 06:18 still in
shadow." — the **served digits verbatim**, confirming the timezone fix in a real browser rather
than only in jsdom; the phone width correctly hides the track-start/sunrise labels and every phase
tick; both the lunar and solar eclipse asides render with the `◑` glyph and coexist with the solar
badge's pre-existing safety warning; the `(i)` tip opens on click without navigating or closing
anything. `.wf-race-cap`'s computed opacity confirmed `1` (the contrast fix took effect) via
`getComputedStyle`. **Not seen**: the race mounted inside the live `WindowSheetDialog` popup itself
(today's own window is unopenable, per the limitation above) — substituted by the direct-render
check above plus full Vitest component coverage of the dialog's mount gate.
