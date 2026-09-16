### Fixed — opening settings from the postcode nudge or the map's ⌂ no longer leaves a Plan dialog open under it

The settings dialog is a sibling of the Plan shell in `App`, so it cannot be stacked against one of
the shell's dialogs. It can only open with none of them up; otherwise two elements claim
`aria-modal="true"`, and the lower dialog's Escape listener, still armed, closes the dialog the
reader cannot see. M5 made the ⚙ cog take every Plan dialog down first. Two other routes open the
same dialog, and neither closed anything:

- **The tick line's "set a postcode" nudge** was wired straight to `App`'s handler. With a window
  popup open the tick line keeps its tab stops, and the popup is not a focus trap, so a keyboard
  reader could Tab out of it onto the nudge and open settings over the popup.
- **The Map tab's ⌂** in its no-postcode state reaches `App` through the map pane, never through
  the shell. The four-day sheet that `Four days here ›` opens over the map leaves the map behind it
  reachable by Tab (O-20 arm A), so the same thing happened over the sheet.

The cog's own close had a gap too. It took down the popup and the three layers that stack over it,
but not search, and the cog is Tab-reachable from an open search box: the tick line leaves the tab
order under search, and the cog does not.

The shell now has one close for "a dialog I do not own is opening": `yieldToForeignDialog` closes
the popup, the stacked layers and search, and moves no tab. The cog and the nudge call it before
opening settings; the nudge forwards whatever argument the tick line hands its handler. `App` also
tells the shell when its settings dialog is open (`settingsOpen`, the same value the dialog mounts
on). On the rising edge the shell runs the same close during render, so it lands in the commit that
mounts settings. That covers the ⌂, and any later route into settings. On the map the tab does not
move, so the peek's back-track (O-18) still works.

Where focus lands when settings closes: if a Plan dialog was open, it closes in the same commit that
mounts settings. Its own cleanup runs first and, because the settings dialog already claims the
modal, restores focus to that dialog's trigger. Settings then records the trigger as its opener. So
closing settings lands on the matrix card the popup was opened from, or on the callout control the
peek was opened from. The cog has behaved this way since M5, and it is now pinned by test. With
nothing open, focus lands back on the control that was pressed.

Tested, not seen. `AppSettingsRoutes.test.jsx` drives the routes through the real `App` and counts
`aria-modal` dialogs against the real settings dialog: two on main for the nudge and the ⌂, one now.
It also pins where focus lands. `WindowFirstShellSheet.test.jsx` pins the shell's half, including
search and the fallback handler. The sweep ran twelve mutants against the fix and killed all twelve.
Nothing was checked in a browser, because the routes sit behind sign-in, and the ⌂'s
Tab-reachability from the peek comes from O-20's record rather than a new measurement.

Not addressed, and traced rather than tested:

- **The reverse route**: Tab out of the open settings dialog and open a Plan dialog behind it
  (plan-matrix §11c's residual).
- **`App`'s other sibling dialog, `MapOverlay`**: the aurora banner's "View on map" opens it without
  closing a Plan dialog.
