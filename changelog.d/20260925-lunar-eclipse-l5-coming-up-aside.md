### Added — Coming up: the dashed-top aside slot (lunar eclipse L5)

Phase L5 of `docs/engineering/lunar-eclipse-plan.md` §2.8: the Coming up entry renders the served
`aside` — the design's `why2` slot — as a dashed-top serif line after the facts and before the
threshold. It is a generic passthrough (`buildEntryView` in `utils/comingUpFeed.js` copies
`entry.aside`, defaulting to null), not a lunar branch: the lunar eclipse topic is its first
server-side writer, carrying the exposure note ("No filter needed — bracket, the shadow is ~10
stops under the lit edge"), but any later type may use it. `WindowComingUpEntry.jsx` renders it as
a new `<span className="wf-cu-aside">` (`.wf-cu-aside` in `index.css`: `font-family:
var(--font-serif)`, 13.5px/1.55, `color: var(--color-plex-text-secondary)`, its own `border-top: 1px
dashed var(--color-plex-border)`) — an entry with no `aside` renders no slot. The `Next from the UK`
line and the `SETS IN SHADOW` pick tag needed no client change — they arrive as the accent facts row
and the `superlative` L1 already serves, and `TYPE_GLYPHS`'s 🌘 was L3's.

A bare `{' '}` text-node sibling follows the new section, so the button's computed accessible name
does not glue the aside onto its neighbours (the P3a lesson, `CHANGELOG.md`'s "Coming up P3a" entry).

Tests: `comingUpFeed.test.js` (the `aside` passthrough, defaulting to null when absent);
`WindowComingUpEntry.test.jsx` (no slot when the server sends none; the slot renders between the
facts block and the threshold block, proven by DOM order, not just presence; the accessible-name
separator holds with `aside` in the mix; and an integration-shaped fixture built from the real
served shape `LunarEclipseAlmanacSourceTest` proves for the worked example — Bamburgh, 28 Aug 2026 —
covering the glyph, NEW flag, kind tag, pick tag, headline metric, all four fact rows including the
accent `next` row, the aside and the action together). Frontend gate green twice (after the review
fixes below): 6557/6557 tests, 0 vulnerabilities, build succeeds.

Five read-only adversarial review lenses (runtime, CSS/tokens, test quality, accessibility,
conventions/scope) ran against the diff. Four found nothing; test quality found one real defect — a
test titled "renders the three fact rows…" whose fixture and assertion both used four — fixed by
renaming it to "renders the four fact rows…" rather than trimming the fixture, since the fourth row
(the `figures for Bamburgh` attribution line) is real, server-sent coverage worth keeping.

Browser verification — **seen**: with a temporary, never-committed local `Clock.fixed(2026-06-01T12:00:00Z)`
override on `AppConfig.clock()` (reverted before commit; production stays `Clock.systemUTC()`) on
this branch's own backend (port 8083, H2, zero seeded locations) and frontend, `GET /api/almanac`
served the real `lunar-eclipse` entry with `aside` populated even with an empty location roster
(the topic's aside is unconditional in `ComingUpAssembler.enrichLunarEclipse`); the Coming up tab
rendered the "Deep partial lunar eclipse" card with the aside line directly under the `Next from the
UK` fact and above `See the plan for 28 Aug →`, confirmed both at desktop width and at 390px.
`getComputedStyle` on the live `[data-testid="coming-up-aside"]` node confirmed the exact rendered
values (`Newsreader, Georgia, ui-serif, serif` / 13.5px / `rgba(242,231,211,.66)` / `1px dashed
rgb(58,44,35)`) and DOM order (`coming-up-facts` → `coming-up-fact` → `coming-up-aside` →
`coming-up-action`, with no `coming-up-threshold` sibling on this served entry) — matching the spec
and ruling out token pruning. **Not tested in the browser**: an entry that carries both `aside` and
a served `threshold` together (no such wire fixture exists locally); covered instead by the
component-level DOM-order test using a hand-built fixture with both fields set.
