### Fixed — pressing `clear` on the Map breadcrumb no longer drops a keyboard reader on `<body>`

A door from the Plan tab lands on the map with a strip above it that says what it carried
("carrying 4★+ · within 2h 30"), and `clear` resets all of it. That usually ends every clause, so
`clear` removed itself while it held focus. Focus fell to `<body>` in Chromium 151, WebKit 26.5 and
Firefox 153, with Enter and with Space (measured on development React, StrictMode active). That put
it outside the map pane's own key handler, so Escape stopped reaching the pane, and no ring showed
where the reader was. Tab itself was spared: each engine went on from where the button had been.

The strip is now a place focus can be put (`tabIndex={-1}`, never a Tab stop), and a `clear` that
leaves while holding focus hands that focus to it, in the same commit. The record is made in
`clear`'s ref cleanup by `watchDeparture`, the mechanism the tick line's ⌂ uses. It moves out of
`MastheadTickLine.jsx` into `utils/watchDeparture.js` now that it has a second owner, unchanged in
behaviour, with the rules an owner must keep now written into its doc. The strip draws its own ring
on `:focus-visible`, in the bone its two buttons' rings use (`--color-plex-gold`). The ring is
inset, because `.wf-body--map` clips overflow and the strip spans it edge to edge. It is an outline
rather than a box-shadow, so forced-colours mode keeps it: all four sides in Chromium and Firefox.

**Focus goes to the strip, not to `← Plan`, the one control that survives.** Measured with the
handoff aimed at `← Plan` instead, on a harness where `← Plan` only counts presses: a held Enter
pressed it on every key repeat, and a second Enter pressed it once, in all three engines. In the
app the first such press leaves the Map tab. Landing on the strip, neither pressed anything. The
price is one Tab stop in all three engines (WebKit reaching buttons with Option+Tab): from the
strip, Tab goes to `← Plan` and Shift+Tab to what precedes the strip. A narrower landing on the
window span ("Tonight sunset") avoids that stop, and was measured and passed over: it is announced
without the landmark's name, it needs a fallback when there is no row, and it adds a departure of
its own when the row retires.

The edges:
- **A clause can survive the press.** `clear` resets the floor to the map's own 3★ default, so a
  door that carried 3★+ still matches. The same button stays, and so does the reader.
- **Position is never taken.** A reader on another control when `clear` goes keeps their place, and
  so does focus another component placed in the same commit. The record is spent either way, so no
  later render pulls anyone onto the strip.
- **No gate on how the press arrived.** The signals a gate could read report a screen-reader or
  voice-control press as a mouse press (`detail` and `pointerType` in Chromium's source, `detail`
  in Firefox's, and likely `:focus-visible`), so a gate would strand exactly those readers.
  Chromium and Firefox focus a clicked button, so a mouse press hands off too. No ring shows at the
  press; a later key draws it in Chromium and WebKit, not Firefox, and it stays until focus moves,
  much as Leaflet's own map container does after a click. WebKit focuses the strip itself on the
  click, since it does not mouse-focus buttons. A click on the strip's own text now focuses the
  strip as well; before, in all three engines, it sent focus to `<body>`. After a pointer lands on
  the strip, Tab restarts at `← Plan` in Chromium and Firefox.
- **No guard for the four-day sheet over the map, unlike `useRowFocusRescue`.** That hook keeps its
  record after focus has gone; this one exists only for a `clear` that held focus as it left. With
  the sheet open, a keyboard reader who has Tabbed back onto `clear` and pressed it lands on the
  strip. Escape then closes the sheet, the pane standing down for it, and they stay on the strip.
  Before, the sheet's restore sent them back to the control that opened it. In WebKit that held
  only for a sheet opened from the keyboard; after a click, which focuses no button there, it sent
  them to `<body>`.
- **The confirmation is visual.** The strip's text loses the cleared clauses, but focus lands on a
  node named "Where you came from" before and after, with no live region.

An adversarial review (six lenses, then nine refuters) changed no design decision. It corrected the
WebKit Tab claims, the mouse route's ring, and the tick line's "jsdom never matches
`:focus-visible`": jsdom's `:focus-visible` is its selector engine's heuristic over the events it
has recorded. It also found test gaps where a broken handoff shipped green: the refused
foreign-modal guard, the record spent on a stand-down, clauses other than the floor, and overrides
of the ring.
The browser results come from a harness using the real breadcrumb and the real `Modal` inside the
map's real ancestor classes, in all three engines. They were not seen in the running app, which
needs a sign-in, and no screen reader was driven.

Tests: fifteen on the breadcrumb, four through the real `MapView` (Leaflet and the map's other
children mocked), and six on `watchDeparture` directly. The ring's rule is pinned as text: a jsdom
cascade test of it does not hold, because jsdom decides `:focus-visible` from the events it has
recorded, and a focus change clears neither its selector-match cache nor its computed-style cache.
Mutation-tested: twenty-nine mutants, twenty-seven killed. The two survivors are equivalent: a ref
callback recreated every render, whose re-attached node clears its own record before the handoff
reads it, and dropping `<html>` from the "focus is nowhere" check, where no route leaves focus.
