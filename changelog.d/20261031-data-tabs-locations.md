### Changed — the Locations tab opens a panel to view, edit and add

Manage → Places → Locations is rebuilt on the same list-and-panel shell as Users, Regions, Tides and the Waitlist (Data
tabs, last phase). The list has a search (name or sub-region), type chips with counts, Needs attention and Disabled chips
and an All regions select (which a Regions "Show them" link also sets), a Mist tag, light and tide tokens, and a status
for every place: Active, failed runs, Disabled, or Disabled after failures. Rows hold no buttons and the status is no
longer a toggle. The amber alert names one failing and one turned-off place, and Show them applies the chip; the
Locations sub-tab carries an amber count of the places that need attention (sub-tab, sidebar and phone menu).

A row opens a panel: a failure box with Reset failure count (new, for an enabled place with failures) or Turn back on
(for one the server turned off), Shooting, Place, Sky, a Tides section with the range bar for seascapes (in place of the
Tides modal), and Location actions to turn the place off or on, which show the server's sentence when it refuses (such as
Turn {region} on first.). Edit and Add take over the panel, with the validation message under each field and no confirm
step when adding. Every field the inline editor had is kept, and so is its save sequence; the inline editor, the confirm
modal and the tide statistics modal are gone. Adding a location by coordinates now also takes the Overlooks water,
Coastal tidal and Mist-prone choices. A failed load of the locations now says so instead of reading as empty.
