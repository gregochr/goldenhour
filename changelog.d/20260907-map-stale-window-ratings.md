### Fixed — the map no longer shows a stale run's stars for a window it has no forecast for

Reported from production on 2026-09-07, on a day no forecast had been generated. The Map tab opened
on a **date already past**, every label chip carrying a `4★`, over a counts footer reading
*133 of 253 shown · 130 rated* and a "★ PhotoCast-scored locations shown" badge — while the window
control beside them said **"No forecast"** with both steppers disabled and the map behind them was
blank.

Both halves were working as written, which is why it went unnoticed. `App`'s date resolution fell
through to `allDates[allDates.length - 1]` when nothing in the forecast domain was today-or-later —
reachable *only* in the "nothing ran" state, and there it returned the most recent date that had
been *scored*. Every surface that knows what a window is then went correctly quiet:
`WindowControl` found no matching EV row, `heatWindow` resolved to null so the field painted
nothing, the colour key was withheld, and `MapCallout` refused to mount — it is already gated on
`activeMapEvent`.

The ratings were the channel that never asked. `getRatingForLocation` reads two indexes keyed by an
arbitrary date — the briefing score index and each location's `forecastsByDate` — and both
legitimately carry rows the EV list excludes, since `GET /api/forecast` serves `today-2` onward. So
the stars, the pins, the 3★ rating floor and the "N rated" count all went on answering for a window
every other surface had gone silent about, with whatever run last scored that date.

Two fixes:

- **`mapDates.resolveMapDate`** — the map never *shows* a day that is over, on any branch except
  the one that is not a calendar question at all. The
  three-branch precedence moved out of `App` into one named function, because the "not past" rule
  used to sit on the last branch only and being last is what made it nearly unreachable: the two
  preferred branches were guarded by a bare `allDates.includes(...)`, which a past date passes.
  ⚠️ The auto-selection is **frozen at mount** (its clock is read inside a memo keyed on the
  location roster, and `useForecasts` fetches once with no interval and no focus listener), so a tab
  left open across UK midnight took that stale branch every time and never self-corrected — a route
  the fallback fix alone would not have closed, and one the rating gate would otherwise have turned
  from a confidently-wrong screen into a permanently blank one. ⚠️ One "past" date is legitimate and
  the first cut of the clamp refused it: a night runs dusk-to-dawn, so between UK midnight and dawn
  the night in progress is *yesterday's* date, and `handleAuroraViewOnMap` sets it deliberately so
  the aurora viewline lands on the night the banner is about. That fix has its own review history
  and its own comment in `App`; the clamp undid it silently for up to seven hours a night in
  midwinter, and `MapView`'s auto-jump cannot recover it. An explicit choice of the night in
  progress is now always honoured — scoped to the explicit choice, since the auto-selection is a
  calendar answer and has no business naming a night.
- **`mapEvents.solarRowPredicate`** — no rating may answer for a window the tab has no row for. It
  is the *same* rule `buildMapEvents` applies when deciding whether to emit the row, factored out
  rather than mirrored, and pinned by an agreement suite driving both functions from one input set.
  Every per-window claim on the tab now reads it: the rating and stand-down accessors, the
  tide-on-the-light fact, the scored-locations badge, and the medallion layer's own rating, arcs and
  stand-down glyph — that last block carried a private copy of the briefing-then-forecast precedence
  and now reuses the shared accessors instead.

⚠️ Two things it deliberately does **not** do. It is not a "has this window passed" test: a window
the briefing has retired while the map's own forecast domain still carries it keeps its rating,
because the rating is the real answer for it. And the frozen Plan-tab overlay is exempt — it builds
no EV list, so there is nothing there for a rating to contradict; the exemption is written on that
ground rather than on the fact that the overlay currently happens to be handed no forecast domain.
(The overlay does change in one way this fix did not set out to make: sharing `App`'s date, it now
opens on today rather than on the last scored past date when a trigger carries no date of its own.)

⚠️ Also unaddressed, and named so the scope is not mistaken for a clean bill: the aurora branch
falls back to `auroraScores`, fetched with **no date parameter at all** and gated only on a live
alert level, so it can still answer for a night the reader is not looking at. Same class of defect,
pre-existing, a different fix.

**Verified against a running app, not only in jsdom.** With the local forecast domain doctored to
hold yesterday alone — the production state — `main` draws `Bamburgh Beach 5★`, `Derwentwater 5★`,
`Dunstanburgh Castle 4★`, `Wastwater 4★` over a blank map, footer *5 of 21 shown · 4 rated*, badge
present. On the same data the fix draws one chip (`Wallington Woods`, a woodland site with no sky
rating by design and therefore correctly starless), footer *1 of 21 shown · 0 rated*, no badge, and
the window control sitting on today. ⚠️ Worth watching: the resulting screen is quiet. With no
briefing at all the window control does not render either (`WindowControl` returns null on an empty
list), so a reader gets a near-empty map whose only account of itself is the absence. That
suppression rule was calibrated when the same screen still had pins on it; whether it now needs a
sentence is a product call, not a bug.

Twenty-two mutants, all killed — but six of them survived a first pass, and each was a test passing
for the wrong reason. The overlay's exemption was proven by a mount carrying no forecast domain, so
the predicate's empty-domain fail-open answered instead and deleting the exemption changed nothing.
The UK-midnight test rebuilt its props as fresh literals in the rerender, which busts a
`[heat, forecastDates]` memo by itself. The stand-down gate had no observable until the assertion
moved onto the admin toggle's disabled state, and the medallion's none until `markerLabelAndColour`
became a spy. The gate never had to consult `eventType` at all, because every case was decided at
date granularity — a hardcoded `'SUNSET'`, and the whole predicate replaced by `date >= today`, both
passed the entire suite until a case was added where one event of a date has a row and the other
does not. And `App`'s own wiring was unpinned: the rule had unit tests, but dropping `nightDate:`
from the call site left them all green. ⚠️ The mutation *run* itself lied once before any of that: a
multi-file vitest filter silently matched nothing and every mutant "passed" in silence, so the whole
sweep was re-run gated on the process exit code rather than on a grep of its output.

Four `MapView` test files were mounting the tab with a `date` but no `forecastDates`, a combination
the tab's only production caller cannot produce. They now pass one, so they exercise the gated path
rather than the empty-domain fail-open.

⚠️ **Codex found one more instance of the aurora class, in the same clamp.** `buildMapEvents`
deliberately clips no NIGHT row to today-forward (only the D-13 solar filler is clipped) and is
handed the raw available-date lists, while `GET /api/forecast` serves `today-2` onward — so last
night's astro/aurora row is both offered in the window control AND `inForecastDomain`. Selecting it
therefore took `selectEvRow`'s forward branch, which cleared `localNightDate` and asked `App` to
adopt a past date; the clamp refused, and the row became one that could be selected and went
nowhere — a control that opens onto nothing, reachable daily since astro conditions are written
nightly. The pane's forwardability test mirrors the parent's *acceptance* rule (that is what
`localNightDate` is for), so it gained the same today-forward clause: the night is kept local, and
its own fetches and the viewline gate land on it, which is all a night row needs the date for. Inert
for solar by construction — a served window is never past and the filler branch already requires
today-forward. One earlier review lens had flagged this route and framed it as a *ratings* concern,
which the rating gate does cover; that it also made the row dead was missed.

⚠️ **And a second Codex pass found the clamp defeated by its own exemption.** The never-past
exemption for the aurora night matched on the date's VALUE, not the selection's provenance — and in
the small hours those coincide: `currentNightDate` is yesterday until dawn, so a reader who picked
yesterday evening's ordinary SUNSET and left the tab open across UK midnight had a stale *solar*
`selectedDate` exactly equal to the night in progress, and it was exempted. The map then held a day
that was over, which with the solar-row gate live is a persistent "No forecast" blank rather than a
merely stale screen — the exact failure the clamp exists to prevent, re-entered through its own
escape hatch. `selectedDateIsNight` now rides beside the date, written by the one call site that
makes a night selection (`kind: 'aurora'`) through a single `selectDate` setter so the two cannot
drift. ⚠️ Mutation testing then caught the sequel: a flag that is only ever *set* lets a solar pick
made after the banner inherit the licence, so the licence is per-pick and the setter clears it.

One scope correction came out of the same pass. `solarRowPredicate` documents itself as solar-only —
`served` holds no night keys — but `isStandDownLocation` early-returns for AURORA alone, so an astro
night was reaching it and being judged by the *solar* domain. It could only suppress, never invent,
and the slot it suppressed was already that day's sunset triage; but a category error that reads as
deliberate is worse than one that reads as a bug, so the scope is now enforced at `MapView`'s own
boundary and the night paths are left exactly as they were.
