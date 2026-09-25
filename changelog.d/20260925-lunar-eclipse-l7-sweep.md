### Added — Plan/Map: the per-location eclipse spot line, and the lunar eclipse docs sweep (L7)

Phase L7 (final) of `docs/engineering/lunar-eclipse-plan.md`: a new shared component,
`components/map/EclipseSpotLine.jsx`, mounted as a sibling block right after `TideFitBlock` in both
`LocationFourDaySheet` (the location sheet, one row per solar window) and `MapCallout` (the Map
tab's selection callout) — the same "one look, two hosts" pattern `TideFitBlock` already
established. It prints one location's own moon geometry at maximum and whether it sets or rises
still in shadow: `◑ moon 7° up WSW at max · sets 06:16 in shadow`, or `· rises 15:36 in shadow`, or
`· above the horizon throughout` — no horizon-clearance claim (the dropped `clearToDeg`, §4 #3).

Fed by a new pure formatter, `utils/dawnRace.js#eclipseSpotLine` — a third filter/map/select over
the already-served `EclipseSight` alongside `raceModel`/`raceSentence`, not a fourth derivation
class — through the SAME `buildEclipseIndex`/`lookupForWindow` join L4 built for the popup's
`DawnRace`. `LocationFourDaySheet` takes a new `eclipseIndex` prop (built in `WindowFirstShell.jsx`
as `sheetEclipseIndex`, gated on `sheetSpot` exactly like `sheetTideAlignmentIndex`, and suppressed
on an away row exactly like the tide-fit block); `MapCallout` takes a new `eclipseSight` prop (one
resolved sight, computed in `MapView.jsx`'s `getEclipseForLocation`, gated on a solar window on
screen exactly like `getTideOnLightForLocation`, fed by a new `eclipseIndex` built in
`WindowFirstMapPane.jsx`).

Tests: `dawnRace.test.js` gains the formatter's own boundary cases (null sight, missing
altitude/bearing, all three tail shapes, the fallback when a flag has no instant behind it, the
unworded negative-altitude print, and a sight with no served `race` at all — a high-moon eclipse
still has an altitude and a set/rise answer); a new `EclipseSpotLine.test.jsx` covers the
component's own render/omit contract; a new describe block in each of
`LocationFourDaySheet.test.jsx` and `MapCallout.test.jsx` proves the HOST wires the resolved sight
through, including the away-row suppression pinned against a fixture that deliberately DOES index
the away date (the tautology guard this codebase's own test standards doc names). Frontend gate
green: 6651/6651 tests, 0 vulnerabilities, build succeeds.

Three read-only adversarial review lenses (runtime behaviour, CSS/tokens + accessibility, test
quality + docs accuracy) ran against the working-tree diff.

Browser verification: with the admin `LUNAR_ECLIPSE` hot-topic simulation active on this branch's
own local backend (port 8083, H2, a seeded 21-location roster, one `cached_evaluation` row for
Bamburgh Beach/Dunstanburgh Castle), `POST /api/briefing/run` served a real `BriefingSlot.eclipse`
sight on 2026-09-25's SUNRISE window — **seen** on the wire (`GET /api/briefing`) for six locations,
matching the plan's own worked-example figures (8° up WSW, sets 06:18 in shadow) to the minute.
**Not seen rendered as pixels**: today's own sunrise had already elapsed in real wall-clock time
when this phase ran, so the Plan tab correctly showed "this morning has gone" (a `div`, never a
button, per plan-matrix's own rule) and the Map tab's window control offered only Tonight/Tomorrow —
neither surface opens an elapsed window's sheet or callout, so `EclipseSpotLine` could not be
screenshotted live this session. A `Clock.fixed` override on the backend (the L5 phase's own
precedent) did not help, because the elapsed check the Plan matrix renders against reads real
wall-clock time on the client, not the injected server clock; the override was reverted before
committing (confirmed by `git diff` on `AppConfig.java` being empty). Verified instead by a
second, independent proof: the EXACT `BriefingSlot.eclipse` JSON captured from the live local
backend above was fed straight into the shipped `eclipseSpotLine()`, in a scratch, never-committed
test file, and it printed the correct line verbatim — closing the gap between "the backend serves
the right shape" and "the shipped formatter reads it correctly" without needing a live pixel.

Docs: `docs/engineering/lunar-eclipse-plan.md` — §0 status table (L5 → merged, L7 → built, in
review; Status line → COMPLETE), a new §4a ("Found only during the phases") with items 17–23
closing out every disagreement surfaced but not recorded during L0–L6, and §6's nine owner
decisions each marked taken or still open against what actually shipped. `CLAUDE.md` — the Almanac
section's "six implementations" → seven, naming `LunarEclipseAlmanacSource`; a new **Lunar
eclipse** bullet under What's Built; the Backend-heavy bullet gains a note that `dawnRace.js` is
the same already-licensed filter/map/select class `comingUpSparkline.js`/`comingUpFeed.js` are, not
an eighth licensed class of its own.
