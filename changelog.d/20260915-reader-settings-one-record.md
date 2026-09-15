### Fixed — one record of your settings: the tick line and the map can no longer name two homes, and a saved home or colour shows as the save lands

The reader's own settings reached the page through two reads of `GET /api/user/settings`: the Plan
provider's, for the tick line's home and the Coming up latch, and `App`'s own (`loadHomeCoords`),
for the map's HOME marker, reach rings and ⌂ control and for the colour ramp — each on mount and
again on every close of the settings dialog, `App`'s unguarded. Either could fail or land out of
order on its own, and the two split: the tick line on the new home beside a map on the old one, or
one of them on none. `App`'s, landing late, could put the old home back on the map; the provider's
could put a pre-save "Set a postcode" back on the tick line, or an older Coming up date back and the
badge with it. Traced in the code; not seen in a browser.

`App`'s `useReaderSettings` now holds one record of them. It reads once, on mount, and after that
takes the settings dialog's own answers, never a read of its own:

- **the dialog's read on opening** — so a home changed elsewhere (on another device, by the nightly
  drive-time job, or by a save whose response was lost) reaches the page when the dialog opens.
  Closing it used to do that by re-reading everything, whether or not anything had changed;
- **a saved home's response**, named from the postcode lookup because the save itself does not
  geocode — so the new home is on the tick line and the map the moment the save lands, with no
  follow-up read to fail. The dialog now names it that way too, where it showed the bare postcode;
- **a recalculation's new drive-time stamp**;
- **a saved colour's response** — so the ramp changes when the save lands. It used to wait for the
  dialog to close, and a read that failed there left the old scale on every surface.

The provider takes the tick line's home and the Coming up latch as props and reads no settings
itself, so the tick line, the map and the Plan tab's home dot cannot disagree. The mount read never
overwrites an answer from the dialog, which is newer however late the mount read lands. The dialog's
read fills the Coming up date only while it is unknown, which also closes the old race in which a
settings read made before `Mark seen` landed brought an older date, and the badge, back. The two
counters the reach fetch and the light key on move only when an answer changes the home or its
drive times (a companion entry).

**"Not known" stays apart from "no postcode", all the way to the map.** The home is `undefined`
until the mount read answers, and after a failed one until the dialog does; `null` only when the
server says no postcode is saved. The map's ⌂ control answers `null` with "Set your home postcode in
Settings", so `WindowFirstMapPane` and `MapView` lose their `homeCoords = null` defaults, and the
control renders nothing while the home is unknown and no origin is in force. It used to show the
prompt to a reader who reached the Map tab before the settings answered, and for good after a read
that failed. Its empty Leaflet container keeps its box without painting it (`visibility: hidden`,
sized like its button), so the zoom bar above it stays put and a press there reaches the map; the
phone layout still hides the control outright. Measured on the built CSS in Chromium 151, WebKit 26.5
and Firefox 153: the zoom bar at the same height with the ⌂ empty or filled, the empty box 34×32 and
unpainted with a press at its centre landing on the map, and `display: none` at 390px. (Collapsing it
instead, as this change first did, dropped the zoom bar 50px and raised it again when the ⌂ came back,
putting the ⌂ where "−" had just been.)

Pinned in `useReaderSettings.test.jsx` — thirteen tests on the record's rules, a pure reducer. In
`App.test.jsx`, fifteen, through the hook's one consumer with the real dialog and the real provider:
one read on mount, feeding one home to both the provider and the Map pane; an unknown home handed
down as unknown, never as "no postcode"; a saved postcode taken from its response and named from the
lookup, with no read after it; a remote change picked up when the dialog opens; the same settings,
and a close, asking for nothing; a saved colour reaching the ramp from its response; the mount read
landing after the dialog's answer and changing nothing; a failed mount read retried by opening the
dialog; the Coming up date filled from the dialog only while unknown, and the tab's own write handed
back; and a StrictMode remount's first read dropped. In `UserSettingsModal.test.jsx`, seventeen, on
the dialog's three reports: each once, when its answer lands, carrying it, and none for a close or a
failed save. In `WindowFirstBriefingContext.test.jsx`, three on the provider's pass-through. For the ⌂,
`MapViewCentreOnHome.test.jsx` now stubs Leaflet as 1.9.4 behaves — a re-added bottom-corner control
goes above the zoom bar — and `mapHomeControlCascade.test.jsx` resolves the kept box against slices
of the real stylesheets. Every answer a negative names is settled inside an awaited `act` beside a
control showing it landed. Eighteen mutants so far — the record's rules and the hook — all killed,
each by the tests that name what it breaks.
