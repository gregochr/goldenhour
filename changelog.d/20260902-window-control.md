### Changed — one window control replaces the date strip and event pills (map-v2 P6)

The Map tab's date strip, its Sunrise/Sunset/Astro/Aurora pills, and the in-map forecast-window
select are replaced by a single chronological window control: a pill (kind chip · label · time ·
▾) with `‹ ›` steppers, opening a day-grouped dropdown that states each event's best achievable
score — choosing a window is now an informed act rather than a guess.

`utils/mapEvents.js` (new) builds the event list from the briefing's served solar windows, astro
and aurora night results, and the forecast endpoint's own date range for dates beyond the
briefing's rendered horizon (unscored, dim rows rather than a shrunken browsable domain). A solar
row's best score is always the server's own figure; only astro/aurora night rows — which carry no
served roster best — take a client max over that night's stars, the one licensed re-derivation
here. LITE accounts see no aurora rows at all (the aurora API cannot serve presence metadata a
LITE client could grey out honestly).

`components/map/WindowControl.jsx` (new) is the control itself. `‹`/`→` step and `Esc` closes the
dropdown, scoped to the control's own subtree rather than the document — the Map pane is never
unmounted, only hidden, so a global key listener would keep firing off-screen.

Astro mode now paints the heat field too, scored from that night's stars and filtered to rated
locations before the field ever sees them (an unrated spot would otherwise poison the field with a
NaN weight). Aurora's viewline gate moves from a raw date compare to "the selected event is that
night's aurora row", which also fixes the gate for a night picked but not yet forwarded to the
parent (see below).

Selection is pane-local: a picked row's date is forwarded to the parent only when the forecast
endpoint actually returned that date. A night row outside that range (an astro/aurora date with no
colour forecast at all) selects locally instead, so the tab still shows it correctly.

`DateStrip.jsx` is deleted (orphaned — its only mount was the Map pane). `ForecastTypeSelector`
survives unchanged on the Plan-tab map overlay, which keeps its own inherited event selector.

Hardened against adversarial review before landing: a kept-local night is now invalidated the
moment the map's date moves for any reason other than the pane's own forwarding (previously it
could survive stale across an aurora auto-jump, a handoff, or an external date change); the astro
field carries its own horizon-based confidence instead of always painting at full strength; a
served solar window forwards even when the separate `forecastDates` list omits its date; and D-13's
filler rows clip to the UK civil today, rather than a leftover key briefly leading the list with an
already-elapsed morning right after midnight.
