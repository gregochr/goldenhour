### Fixed — keyboard focus survives a row leaving the Map tab's open window menu or callout strip

The Map tab's window menu and the selection callout's *Every event here* strip both draw the event
list, which is rebuilt against the clock: yesterday's filler solar rows leave at UK midnight, and since
D-14 last night's rows leave at dawn (at UK midnight for LITE). A row removed that way took keyboard
focus with it. Focus fell to `<body>` with the menu or strip still open, and Escape and ←/→ — handled
on the control's own wrapper — stopped reaching anything until the reader found the control again. The
drilldown already had a recovery for the same shape; these two lists had none.

A new `useRowFocusRescue` hook hands that focus to the control that stays mounted: the window pill
for the menu, the strip's toggle for the strip. It acts only on the transition a removal causes —
the row that last took focus has gone and focus is now on `<body>` — and never on `<body>` focus
alone, which is ordinary on these surfaces (a click on text that cannot take focus, a WebKit button
click) and would otherwise steal focus on an unrelated render. Focus moving to a real element, a
press elsewhere in the list, or the list closing all end its record of the row, and it stands down
behind a dialog from outside the map pane, as the drilldown's recovery does.

**Eight new tests**, six on the menu and two on the strip, each firing its follow-up key at
`document.activeElement` — a key fired at the control passes while focus is lost. Seven mutants, each
killed: no focus move, a blur or a press that never ends the record, no closed-list gate, a
state-triggered version, and either list left unwired. Closing a menu from inside it still drops focus
as it always has; that is a separate residual this does not reach into. Not seen in a browser.
