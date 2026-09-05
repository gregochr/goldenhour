### Changed — "Four days here" is a peek now, not a departure

The Map tab's selection callout has always ended its clamped narrative with `Four days here ›`, and
pressing it took the reader off the Map tab entirely: the four-day sheet opened, but on the Plan
tab, so dismissing it left them on a screen they had not asked for with the map, the camera and the
selection all gone. Owner report: *"I'd like it to stay with the map behind, then I can back track
on my user journey."*

It now opens the same sheet **over the map**. The tab does not move, the callout stays mounted
underneath, and closing the sheet — `Esc`, the ✕, or the backdrop — puts the reader back on exactly
the selection they opened it from, with focus returned to the caption they pressed.

The callout's own `Open in Plan` button is unchanged and still goes to the Plan tab. The two used to
be the same action behind two labels; they are now the two things those labels say. One handoff
carries both, distinguished by a single `inPlan` flag, so neither route can seed a sheet the other
could not.

Four supporting fixes fell out of it:

- The map pane's own `Esc` rule (menus first, then the callout) now stands down entirely while a
  dialog from outside the pane is over it. Before that guard one press closed the sheet **and**
  deselected the location underneath, and dropped focus to the top of the document — the whole of
  what the peek exists to prevent. Measured in a browser before and after.
- The sheet's own `◍ Show on map →` footer no longer imports the Plan's lens when it is pressed from
  a sheet that is already over the map. It used to arrive as a full door: the map's rating floor was
  overwritten (and persisted), its reach tier overwritten, its scope snapped back to "My area" and
  its camera refitted — all from a press that asked only to change the window — under a landing
  strip reading "Where you came from · ← Plan" for a reader who had come from the Map tab. It now
  moves the window and the selection, and nothing else. It rides the map's *structured* handoff
  channel rather than the older per-field one, because only the structured route treats the
  handed-over event as an explicit choice: sent the other way, `Show on map → Tomorrow sunrise`
  landed on tomorrow's **sunset**, the window silently replaced a tick later by the map's own
  auto-selection.
- The caption takes focus on the press. macOS and iOS Safari do not focus a `<button>` on click
  unless Full Keyboard Access is on, so without it the sheet had no return address to hand focus
  back to and a keyboard reader was returned to the top of the page instead of to the place they
  were reading about. Its accessible name also gains the separator it was missing: the summary's
  last word and the place name were running together into one spoken token.
- The Map tab warms the sheet's lazy chunk when it opens. Between the press and that chunk landing
  there is no dialog in the document at all, so neither the sheet's own `Esc` listener nor the new
  map guard exists yet, and a press in the gap dropped the selection the sheet was about to describe.
