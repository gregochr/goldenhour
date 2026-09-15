### Fixed — one record of your settings: the tick line's home and the map's come from one answer, and a saved home or colour shows as the save lands

The reader's own settings reached the page through two reads of `GET /api/user/settings`: the Plan
provider's, for the tick line's home and the Coming up latch, and `App`'s own (`loadHomeCoords`),
for the map's HOME marker, reach rings and ⌂ control and for the colour ramp — each on mount and
again on every close of the settings dialog, both unguarded. Either could fail or land out of order
on its own, and the two split: the tick line on the new home beside a map on the old one, or one of
them on none. `App`'s, landing late, could put the old home back on the map; the provider's could
put a pre-save "Set a postcode" back on the tick line, or an older Coming up date back and the badge
with it. Traced in the code; not seen in a browser.

`App`'s `useReaderSettings` now holds one record of them. It reads once, on mount, and after that
takes the settings dialog's own answers, never a read of its own:

- **the dialog's read on opening** — so a home, or its drive times, changed elsewhere (on another
  device, by the nightly drive-time job, or by a save whose response was lost) reaches the page when
  the dialog opens. Closing it used to do that by re-reading everything, whether or not anything had
  changed;
- **a saved home's response**, named from the postcode lookup because the save itself does not
  geocode — so the new home is on the tick line and the map the moment the save lands, with no
  follow-up read to fail. The dialog now names it that way too, where it showed the bare postcode;
- **a recalculation's new drive-time stamp**, and only the stamp, set on the home on record: the
  server measures from the home it has stored, where the dialog's own copy of the home can be older
  than a postcode save that landed under the recalculation's spinner;
- **a saved colour's response** — so the ramp changes when the save lands. It used to wait for the
  dialog to close, and a read that failed there left the old scale on every surface.

**The newest answer asked wins, whichever lands last.** Each read is numbered as it is made — the
page's own, and each opening of the dialog — and each save as it lands, since its answer is the
server's state from then; an answer numbered below the newest one applied is dropped. The server
geocodes the postcode on every read, so a read can be slow, and a dialog closed before its read
answered still reports it. Without the order, that answer, landing after a save made in a later
opening, would put the old home back on the tick line and the map while the light and reach, asked
again of a server holding the new home, answered for it — or put the old ramp back on every surface.
The same rule keeps the mount read from overwriting anything the dialog has said, and settles a
StrictMode remount's two mount reads.

The provider takes the tick line's home and the Coming up latch as props and reads no settings
itself, so the tick line's home, the map's and the Plan tab's home dot come from one answer. (The
tick line's light row names the home from `GET /api/user/settings/light`, which is asked again when
the home changes, so for that round trip it can still name the old one.) The dialog's read fills the
Coming up date only while it is unknown, which also closes the old race in which a settings read
made before `Mark seen` landed brought an older date, and the badge, back. The two counters the
reach fetch and the light key on move only when an answer changes the home or its drive times (a
companion entry). The drive-time stamp is compared as an instant, not as a string: the server hands
a recalculation's stamp back from its clock — nanoseconds on its Linux host — and stores it to the
microsecond, so every later answer spells the same instant differently. A millisecond of slack
covers the database rounding it into the next one; Chromium 151, WebKit 26.5 and Firefox 153 all
read a six- or nine-digit fraction to the millisecond.

**"Not known" stays apart from "no postcode", all the way to the map.** The home is `undefined`
until the mount read answers, and after a failed one until the dialog does; `null` only when the
server says no postcode is saved. The map's ⌂ control answers `null` with "Set your home postcode in
Settings", so `WindowFirstMapPane` and `MapView` lose their `homeCoords = null` defaults, and the
control renders nothing while the home is unknown and no origin is in force. It used to show the
prompt to a reader who reached the Map tab before the settings answered, and after a read that
failed, until the settings dialog was next closed or the page reloaded. Its empty Leaflet container
keeps its box without painting it (`visibility: hidden`): its content is sized like the button, as a
content box, inside the same 1px border, so the two boxes match wherever that border lands —
including where WebKit snaps it to a device pixel at a fractional ratio. So the zoom bar above it
stays put, and a press there reaches the map; the phone layout still hides the control outright.
Measured on the built CSS in the same three engines at device-pixel ratios from 1 to 3 in quarter
steps: the empty box the size of the filled one to a thousandth of a pixel in every case (WebKit
drawing the border at 0.57 to 1px, and the empty box following it), the zoom bar not moving, a press
at the empty box's centre landing on the map, and `display: none` at 390px. (Collapsed, the box
would drop the zoom bar 50px and raise it again when the ⌂ came back, putting the ⌂ where "−" had
just been.)

Pinned in `useReaderSettings.test.jsx` — twenty-two tests on the record's rules, a pure reducer,
among them a recalculation's own action and the stamp compared as an instant at three precisions. In
`App.test.jsx`, twenty-three, through the hook's one consumer with the real dialog and the real
provider: one read on mount, feeding one home to both the provider and the Map pane, named by its
postcode when no place resolved; an unknown home handed down as unknown, never as "no postcode"; a
saved postcode taken from its response and named from the lookup, with no read after it; a re-save,
an opening on the same settings and a close asking for nothing; a recalculation asking for reach and
not the light, keeping the coordinates object, and not taken for a move when a save lands under its
spinner; one stamp read back at the database's precision not taken for a change; a remote change
picked up on every opening of the dialog, a move in longitude alone included; a closed dialog's read
dropped after a newer answer — a later opening's save, a save still out as it reopened, a colour save
still out as it reopened, and a newer read; a saved colour reaching the ramp from its response; the
mount read landing after the dialog's answer and changing nothing; a failed mount read leaving the
home unknown and the ramp alone, and retried by opening the dialog; the Coming up date filled from
the dialog only while unknown, and the tab's own write handed back; and a StrictMode remount's first
read dropped. In `UserSettingsModal.test.jsx`, nineteen, on the dialog's four reports: its read
started as it is asked and reported once it lands, and each save's report once, when its answer
lands, carrying it — none for a close or a failed save. In `WindowFirstBriefingContext.test.jsx`,
three on the provider's pass-through. For the ⌂, `MapViewCentreOnHome.test.jsx` stubs Leaflet as
1.9.4 behaves — a container per corner, and a re-added bottom-corner control above the zoom bar —
and `mapHomeControlCascade.test.jsx` resolves the kept box against the button's own rule, with
Tailwind's preflight in force so that the override is weighed. Every late answer a negative names is
settled by hand inside an awaited `act`, beside a control showing it landed.

Eighty-seven mutants: eighty-four killed, each by the tests that name what it breaks, and none by a
file that failed to load; three equivalent, named below.
- **the record's rules (twenty-one)** — an answer with nothing on record not counting as a change;
  the postcode, the latitude or the longitude left out of the comparison; the place name not carried
  for the same home, carried to a moved one, or not taken on its own; the stamp never a change, or
  not one from nothing; a new record for an identical answer; either counter set rather than
  counted; the mount read moving the counters; stamps compared as strings, or without the
  millisecond of slack; a recalculation moving its counter for the stamp on record, putting no stamp
  on record, moving the home counter, inventing a home while nothing is on record, setting its
  counter rather than counting it, or comparing its stamp as a string;
- **the order and the hook (twenty-one)** — the order never refusing an answer, or never recording
  one; the mount read not numbered; the dialog's read numbered as it lands; a saved home or colour
  taking no place in the order; a dropped read still reaching the ramp, or the mount read's still
  writing the date; the dialog's read overwriting a known last-seen date, not filling an unknown one,
  or not reaching the ramp; a saved colour not reaching it; the defaulted flag stuck at false; no
  postcode fallback for the place; an unknown home handed down as null; a failed mount read recorded
  as no home, or reaching the ramp; the coordinates not memoised, or memoised without the longitude.
  One is equivalent: a recalculation taking no place in the order, since no read can be out to lose
  to it while the dialog cannot be closed under its spinner;
- **the dialog (fifteen)** — its read unreported, or started as it lands; a saved home not named from
  the lookup, or reported on the press; a recalculation unreported, reported without its stamp,
  reported as the dialog's copy of the home, or reported on the press; a saved colour unreported,
  reported as a home too, or reported on the click; a radius save reported; a postcode save reported
  as a recalculation. Two are equivalent: the read started through the prop rather than its ref, or
  the ref never kept current — the read is made once, at mount, when the two are the same function;
- **the provider (seven)** — reach not keyed on the drive-time counter; its catch unguarded or
  writing nothing; its answer unguarded; no cleanup; the home, or the latch setter, it is handed
  dropped;
- **`App` (nine)** — the light keyed on the drive-time counter; the provider not handed the
  drive-time counter, the home or the latch setter; the pane handed null for an unknown home; each
  of the dialog's four reports left unwired;
- **the kept box (five)** — painted, collapsed, sized as a border box, wider than the button, or the
  button resized alone;
- **the ⌂ (nine)** — a `= null` default in the control, in `MapView` or in the pane; unknown ignoring
  an origin; the button rendered while unknown; null treated as unknown; the control re-added when
  the home becomes known; the control, or the zoom bar, in another corner.
