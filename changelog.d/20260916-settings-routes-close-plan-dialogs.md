### Fixed — settings no longer opens over a Plan dialog or the map overlay, whichever control opened it

The settings dialog is a sibling of the Plan shell in `App`, so it cannot be stacked against one of
the shell's dialogs. It can only open with none of them up; otherwise two elements claim
`aria-modal="true"`, and the lower dialog's Escape listener, still armed, closes the dialog the
reader cannot see. M5 made the ⚙ cog take every Plan dialog down first. Three other routes did not
hold:

- **The tick line's "set a postcode" nudge** was wired straight to `App`'s handler. With a window
  popup open the tick line keeps its tab stops, and the popup is not a focus trap, so a keyboard
  reader could Tab out of it onto the nudge and open settings over the popup.
- **The Map tab's ⌂** in its no-postcode state reaches `App` through the map pane, never through the
  shell. The four-day sheet that `Four days here ›` opens over the map leaves the map behind it
  reachable by Tab (O-20 arm A), so the same thing happened over the sheet.
- **App's map overlay** — opened by the aurora banner, a Plan door, a pick or Coming up — is
  `aria-modal`, no trap, and painted at `zIndex: 200`. A reader who Tabbed out of it and pressed ⚙,
  the nudge or the ⌂ got settings underneath it, holding focus in a dialog they could not see.

The cog's own close had a gap too: it took down the popup and the layers stacked over it, but not
search, and the cog is reachable from an open search box. `selectTab` had the same gap, so **search
now closes on a tab switch** — before, a reader who Tabbed out of the box onto the tab bar could
arrow to another tab with search still open, and over the map the four-day peek then opened `inert`
beneath it.

**The fix.** The shell has one close list, `selectTab`, which now carries search. Called with the
tab already in force it moves nothing, the form the map's peek already used. The cog and the nudge
call it before opening settings, and the nudge forwards its handler's arguments untouched. `App`
tells the shell when settings is open (`settingsOpen`, the value the dialog mounts on). On the rising
edge the shell calls the same `selectTab` during render, so the close lands in the commit that mounts
settings; that covers the ⌂ and any later route. On that edge `App` also closes its own map overlay.
Neither closes on the level, and neither moves the tab — the peek's back-track (O-18) still works.

Where focus lands when settings closes: if a dialog was open, its cleanup runs in the commit that
mounts settings and restores focus to that dialog's own recorded opener, which settings then records
as its own. So closing settings returns focus wherever the covered dialog would have: the matrix card
a popup was opened from, the ⌕ button for search or a popup picked from it, the map's peek control
(the callout's `Four days here ›`, or the window pill when the region panel opened it), or the aurora
banner for the overlay it opened. With nothing open, a keyboard press returns to the control pressed.
The cog has landed this way since M5. If that opener has gone by the time settings closes — a window
that passed meanwhile — focus is left where the browser put it.

**Tested, not seen.** `AppSettingsRoutes.test.jsx` drives the routes through the real `App` and
counts `aria-modal` dialogs against the real settings dialog. On the parent commit the nudge and ⌂
cases counted two; now each counts one, including a second opening. It also pins where focus lands,
that no tab moves, and that the Plan has not crashed behind settings. `WindowFirstShellSheet.test.jsx`
pins the shell's half without `App`: the nudge's and cog's own closes, the arguments, and the
`settingsOpen` edge (including its re-arm). `WindowFirstShellTabs.test.jsx` pins search closing on a
tab switch. A mutation sweep of 22 mutants over the fix killed all 22; three needed tests added after
the first run (a cog or nudge that moved the tab, and `App`'s overlay close latching). Nothing was
checked in a browser — the routes sit behind sign-in — and the ⌂'s Tab-reachability from the peek comes
from O-20's record, not a new measurement.

An adversarial review (six lenses, nine refuters) ran on the first cut. It found the overlay route,
search surviving a tab switch, a second close list beside `selectTab`, an untested second opening,
tests that a crashed Plan would pass, and several overclaiming docs. All of those are fixed here.

Not addressed, and traced rather than tested:

- **The map overlay opened OVER a Plan dialog.** The regional planner's 🗺 and the aurora banner
  still open it without closing the dialog under it — unguarded keyboard routes that
  `v1-retirement-plan.md` §8 item 9 already records.
- **An Operations-tab admin `Modal` under settings.** It is left open on purpose, because it can hold
  data a close would lose (a generated password).
- **The reverse route.** Tab out of the open settings dialog and open a Plan dialog or the overlay
  behind it.
- **Search from the Coming up tab.** The ⌕ still works there, and a window picked from it opens a Plan
  popup over Coming up.
- **Phone sheets.** At ≤639px the Map tab's Filters and Regions sheets (non-modal, `z-index: 10000`)
  paint over settings opened from ⚙ or the nudge.
