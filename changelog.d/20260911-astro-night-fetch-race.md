### Fixed — the map's astro scores answer for the night they were fetched for

The single-night astro fetch had the race #814 fixed in the stored-aurora fetch beside it — the
"astro twin" that #814's own entry named as a known open item. Its scores answer for one night, and
every astro reader takes them as the night on screen's: the rating accessor (and through it the
tab's pins, labels, callout and counts), the astro heat field, and both overlay popups. One night's
stars could stand in for another's two ways: a **stale window** (switch night A → B and, until B's
request resolved, A's scores were drawn as B's) and a **late response** (A's request finishing after
B's wrote A's stars in as B's, where they stayed until the next selection).

The effect now takes #814's shape exactly: it clears on every night or mode change, before the new
request is made, and a `cancelled` flag drops any response — or failure — whose night is no longer
the one on screen.

⚠️ **While B loads, the tab says nothing is rated for it — and one surface says so too firmly.** It
is what first entry into astro mode already showed, until B's answer lands: in Heat view the "This
event is not scored yet" line and, with a location selected, the callout's "Not scored yet" — which
can sit beside a strip cell already showing B's star from the window control's preview. The
callout's own rule is "Loading…" while a fetch is in flight, but its flag follows the solar fetch
alone, so an astro night step now reads "unscored" for one round trip where it used to show the
previous night's star. #814 left the same gap on the aurora side. A night-aware loading state for
both is a follow-up, not part of this change.

Pinned by three tests in `MapViewAstroNightFetch.test.jsx`, each asserting a marker count rather
than a `markerLabelAndColour` spy — `makeMarkerIcon`'s module-level cache is keyed on the location
and its rating among other things, so a stale 4★ can reuse a cached icon and never reach the spy.
⚠️ **The third goes past #814's pair on purpose.** A late *failure* for night A ran the unguarded
`.catch`, which cleared night B's scores after B had answered, and neither of the other two tests
reaches that branch. Mutation-checked: reverting the clear fails only the stale-window test;
reverting the cancellation fails the late-response and late-failure tests; dropping the `.then`
guard alone fails only the late-response test, the `.catch` guard alone only the late-failure test,
and the cleanup both; and the pre-fix effect fails all three. ⚠️ **Each test settles its late
request inside an *async* `act`, and that is load-bearing — measured:** with a synchronous `act`,
both late tests pass with their guard deleted, because the late write lands after `act` has returned
and is never committed before the assertion.
