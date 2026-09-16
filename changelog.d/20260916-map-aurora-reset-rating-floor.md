### Fixed — an alert ending no longer empties the map's rating floor, or deletes the one you saved

When aurora mode stops being available under a reader — an alert ends while they are in it, and the
map's list of stored aurora nights is empty — the map goes back to Sunset. On the way it set the
rating floor to `null`, a value the floor has not otherwise held since June's filter-bar tidy
(7618a02c), and cleared the saved floor. On the Plan-tab overlay the context bar showed an empty chip
(React logged a missing key for it) and the drawer's hint read "showing ★ and above". On both surfaces
every star button read pressed, the floor counted as a filter — the overlay offered a Clear, the Map
tab's chip read "Filters (1)" — and every rated pin was drawn whatever the floor. The next visit
opened on 3★+, whatever floor the reader had saved.

No reader asks for that change of event, so it now leaves what the reader set alone. That is an owner
decision, taken over this change's first cut, which reset the floor to the 3★+ default the way the
explicit kind changes do. The rating floor and the stand-down lens keep their values and their saved
copies, the subject, drive-time and dark-sky filters stand, and only the admin "unknown" lens and a
kept-local night reset, as before. Entering aurora mode through a handoff already kept the floor, so
leaving it now matches. The explicit kind changes — a window picked on the Map tab, an event picked in
the overlay's drawer — still reset the floor and clear it; `map-landing-plan.md` §4 #21(a) records
the Map tab's (`selectEvRow`) as inherited, not chosen.

`setMinStars(null)` came in with a5a23f2e (March), when a null floor meant "no floor" and a cleared
key read back as null too. 7618a02c made the floor always hold a value and moved the event selector's
reset and the drawer's Clear to the default, but not this effect; b51af333 (#731) created
`selectEvRow` on the default and added a line to this effect without touching the null. It survived
because no test checked what this reset does: the tests that meet it arrange a live alert or a stored
night to avoid it, and #814 recorded "nulling the rating floor on the way out" as a side effect.

Pinned in `MapViewAuroraUnavailableFloor.test.jsx`, sixteen tests through the real
`AuroraStatusProvider` and a window focus, on the overlay and the Map tab. A saved 4★+ stays on the
context bar, pressed in the drawer and in the popover, in the drawer's hint, in the Clear and the
chip's count, in the pins, and in storage, where a fresh map mounted afterwards reads it back. A
reader with no saved floor stays on 3★+, with no Clear and nothing saved. A saved stand-down lens and
a drive-time filter chosen in aurora mode stand too, and an admin's "unknown" lens goes. The pressed
state is read from the two buttons either side of the floor, so it holds whichever pressed contract
that group settles on (below). The tests run on an afternoon clock, so the Sunset the map lands on is
still to come. Three explicit resets gain page-level tests: the Map tab's window control (whose test
read only storage), and the overlay drawer's event change and Clear (which had none). Each now reads
the page as well as storage, including the chip's count or the Clear: the context bar alone cannot
tell a floor of the wrong type, such as the string `'3'`, from 3.

Twenty-three mutants, all killed, each by the tests that name what it breaks, and none by a file
failing to load. At the bounce: main's own `null` and cleared storage (twelve of the sixteen tests
fail); the first cut's reset to the default, with and without its clear; the clear alone (only the
fresh-mount test); `null`, `undefined`, 1, 5 and the string `'3'`; three stand-down resets (only the
stand-down tests); a drive-time reset (only the drive-time test); and the "unknown" lens left on
(only its test). At each of the window control's, the drawer event selector's and the drawer Clear's
resets: a `null`, the string `'3'`, and a missing storage clear. Before these tests, a `null` at any
of those three resets passed all 706 tests in the 34 `MapView*`, `WindowFirstMapPane*` and `App*`
test files, which include every file that renders the real map.

Reviewed by six adversarial lenses (38 charges, many overlapping) and six refutation agents, then a
second round of three self-refuting lenses over the reworked change (five charges). Every upheld
charge on this change is fixed above, including history and route facts corrected from the refuters'
reading. Found on the same route, pre-existing, and left as named follow-ups:

- **A map mounted into aurora mode after an alert has ended leaves it before its stored-nights list
  answers**, even when a stored night exists — the first visit to the Map tab through the overlay's
  hatch, say. The fix that works waits for the list to answer, success or failure, and exempts LITE,
  which never asks. A Map tab whose list answered before a night was stored leaves too, and waiting
  for the answer does not reach that case: the tab's map asks once and stays mounted.
- **At night the overlay lands on a sunset already over**, under its "Aurora tonight" title. The
  auto-selection cannot fix it, because the overlay cannot change its date; it is a design question.
- **Focus falls to `<body>` when an alert ends** and removes the aurora card's "Centre map" button — on
  every alert end, bounce or not — and when the change of event re-keys an open popup; and the
  drawer's Aurora button turns `disabled` under focus.
- **The rating-floor buttons announce every star at or above the floor as pressed**, where the app's
  other toggle groups press one; and **the overlay's event selector exposes no selected state**.
- Nothing announces that an alert has ended. An owner call, closer to an enhancement than a defect.

Tested, not seen in a browser: the map sits behind sign-in.
