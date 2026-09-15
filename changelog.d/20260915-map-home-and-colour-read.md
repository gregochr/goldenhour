### Fixed — the map's home marker and rings answer the newest settings, and a colour choice reaches the ramp when it is saved

`App` read `GET /api/user/settings` for itself — the home's coordinates, for the map's HOME marker,
reach rings, the ⌂ control and `mapReachMeasured`, and the map-colour preference, for the ramp every
heat surface paints with — on mount and again on every close of the settings dialog, unguarded. With
the Plan provider's reads now guarded by the companion fetch-order fix, an older answer to App's read
landing last could leave the map's marker and rings on the previous home — or absent, the ⌂ asking
for a postcode — beside the tick line's new home and drive times. Traced in the code; not seen in a
browser.

The read moves into `useHomeAndMapColour`, which asks again only when one of its answers may have
changed — on a home save (`homeSettingsVersion`, which the companion change moves only on saves) or a
colour save (a new `mapColourVersion`, moved by the dialog's new `onMapColourChanged` report) — and
never on a close alone, with the same effect cleanup as the provider: a request a newer save has
superseded writes nothing, wherever it lands. The colour radios used to rely on the close-time read,
so the dialog now reports a colour save itself, from the save's own continuation, so a save that
lands after the dialog closes still reports. The new ramp arrives when the save lands rather than when
the dialog closes, and a colour save moves nothing the provider keys on.

The price is the one the counter change already took: a read that failed at page load is retried by
the next save or a reload, no longer by closing the dialog. A failure still writes nothing — the
settings are optional here — so after a move whose re-read fails, the marker stays on the old home,
the same open decision as the reach figures'.

Pinned in `useHomeAndMapColour.test.jsx` — six tests: the mount's answer landing after a home save's,
a superseded answer landing first, a superseded answer after the newest failed, no re-read on a
re-render that moves neither counter, a colour save reaching the ramp, and two quick colour choices
ending on the second. In `UserSettingsModal.test.jsx`, five: a colour save reports once, when it
lands, and not as a home change; a failed colour save, a close and a postcode save do not; a save
landing after the dialog closed still does. In `App.test.jsx`, two, through the real dialog: closing
asks nothing more, and a colour chosen in the dialog reaches the ramp when the save lands while moving
nothing the provider keys on. Every negative settles its request inside an awaited `act`. Fourteen
mutants, all killed, each by the tests that name what it breaks: the hook's guard or cleanup removed
(two); its dependencies cut to the home counter, the colour counter, or mount only (three); a
request-number guard in the cleanup's place (one — killed by exactly the two tests where the rules
part company); the close re-reading, the colour report unwired, the colour report moving the home
counter, and the hook given no colour counter (four, in `App`); and the dialog's colour report
removed, sent from a failed save, sent on the click, or sent from a postcode save (four).
