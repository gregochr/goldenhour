### Fixed — the Map tab's window list no longer carries every past astro and aurora night

The Map tab's window control built a night row for every date the astro and aurora available-date
endpoints returned. Both endpoints are a `SELECT DISTINCT forecastDate` over tables that nothing
prunes, so they answer with every night ever stored, and the list opened on that history. Each past
night appeared under a bare weekday — *Thursday night* beside a *THU 13* heading, with no month to
tell April from next week — read "—" because the preview fetch never covered it, and could be walked
into with the `‹` stepper. The selection callout's *Every event here* strip carried a cell for each
one too. Solar rows were never affected: the briefing withdraws an elapsed window, and D-13's filler
rows were already clipped to the UK today.

A night row is now offered only while its night is not over: tonight and later, plus the night in
progress, which between UK midnight and dawn is still yesterday's date. The night in progress comes
from the backend's `currentNightDate`. The status provider keeps the last status when a later fetch
fails, so a status can outlive its night — Codex found the case that matters, a status taken before
dawn naming yesterday's night all day. So `GET /api/aurora/status` now also sends
`currentNightEndsAt`, the instant that night ends, from the same read of the backend's clock as the
date, and `resolveAuroraNight` believes the date only until then. The provider re-renders the page
once that instant has passed, because while the polls fail the status never changes and the
memoised map would not ask again. It looks at the wall clock at least once a minute, so a device
asleep at dawn catches up within a minute of waking. A status without the field is still believed
only as yesterday. `mapDates.isNightOver` is now the one answer to "is this night over", read by the
list, by `App`'s `resolveMapDate`, and by a new `mapEvents.isForwardableRow`, which decides which
picked rows the pane hands to `App`: exactly the ones `App` will take. That moved one behaviour. The
night in progress used to be kept in the pane when it was picked after UK midnight, so it stayed on
screen past dawn, while the same night picked before midnight was moved on by `App`'s clamp at dawn.
It is now handed over like any other night, and every night ends at dawn the same way. The
past-dated rows the list keeps are in the preview fetch as well, so they show a best and a time.
Recorded as owner decision D-14 in `map-tab-v2-plan.md` §5. The comments that called the unclipped
list deliberate had been describing the code; no decision existed.

**One exception: the night the map is already showing** keeps its row after it ends, until the map
leaves it, so the pill never reads *No forecast* over stars still painted for that night (#803's
shape). For a night `App` holds it is only a bridge — one render at dawn, or until `App`'s next render
after UK midnight for LITE, about 30 seconds while its health stream is connected. It lasts longer
only for a night the pane kept local, and picking that one again keeps it local, since `App` would
refuse it.

**Two costs, both accepted by the owner.** LITE cannot read aurora status, so for LITE yesterday's
astro night now drops off at UK midnight rather than dawn: a LITE reader can no longer reach the
night still running over them, which the old list offered. The exit, a night-in-progress signal LITE
can read, is §6 O-21. And astro rows are written only by hand-started colour runs, so on a day past the
last run's horizon the tab offers no astro row at all; a scheduled producer is §6 O-22.

**63 new frontend tests and nine backend cases**, counting each `it.each` row: eight for
`isNightOver` and `resolveMapDate`'s stale-status case, four for `resolveAuroraNight`'s end,
twenty-nine in `mapEvents.test.js` — including agreement checks that drive the list and
`isForwardableRow` against `resolveMapDate` from one set of inputs, over every kind of row the list
can build — sixteen in `MapViewPastNights.test.jsx`, which moves the map's date through a parent
running the real `resolveMapDate` so the dawn cases go through the real clamp, one new #803 case in
`MapViewAuroraNight.test.jsx`, which computes `App`'s refusal in the test rather than asserting it
in a comment, three in `AuroraStatusContext.test.jsx` — every poll failing across the end of a
night, a device asleep through it, and a night ending between the render and the provider's first
look — and two in `App.test.jsx` that take the real `App` and provider past a night's end. The
backend cases pin that the end is the instant `currentNightDate` moves on and the dawn of the
night's own window, with dawn moving by the day, and that the controller reads the night before its
NOAA calls. Thirty-nine mutants of this change, each killed on the final code: dropping the clip,
ignoring the night in progress or believing a stale one, ignoring the night's end or misjudging it
at the boundary, a provider that never looks at a status's end, looks without re-rendering, trusts
one long timer, re-renders before the end or skips an end that already looks past, `App` reading the
night without its end or once per status, an end or a window taken from the wrong morning, a
controller that reads the date and its end with two calls, after its NOAA calls, or drops the end,
dropping the on-screen exception or widening it to a range or to either kind, keying it on the
parent's date, reverting the forwarding rule to the calendar or letting it forward any night, an
off-by-one boundary, and each way of leaving a past row out of the preview, among them.

**Eight existing frontend tests failed against the change, each because it used a past night as a
convenient fixture**, and each is re-anchored rather than loosened. Three are #829's, in
`MapViewNightScoresLoading.test.jsx`, each run for astro and aurora: they named a past night as the
one the preview never asks about, and now name a night beyond the forecast's dates — the case D-14
leaves — still failing if the headline restatement, the pending set's preview check or the
night-date split is removed. In `MapViewAuroraNight.test.jsx` the kept-local pair now uses a night
beyond the forecast dates, the stepper-ticks case uses tonight — which brackets its aurora row with a
solar window on both sides for the first time — and the #803 case is split in two: the night in
progress is now forwarded (`App` takes it), and an ended night picked again is kept local (`App`
refuses it). `MapViewSelectionOrdering`'s strip-switch case ran a fixed January date against the real
clock, and now pins it. `MapViewAstro.test.jsx` read the wall clock on the runner's UTC calendar:
measured in the hour after UK midnight under BST, three of its nine tests already failed there and
this change made it four, so it now pins its clock too. One backend test changed with the API:
`AuroraControllerTest`'s status case stubbed `currentNightDate()`, which the controller no longer
calls, and now stubs `currentNight()` and checks the date and its end. Not seen in a browser;
covered by tests only.
