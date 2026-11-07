### Fixed — Map no longer opens at maximum zoom after Plan is clicked first

On desktop and tablet, a reader who clicked Plan before the Map pane had finished loading got a Map stuck at zoom 16
with almost every location chip hidden. The Map had been built inside a hidden tab, where Leaflet measures a 0x0 box,
and the framing controller treated that broken opening fit as already applied. It now refits to the planning area (or
the catalogue box when there is no heat field) the first time the pane is shown. Maps that open visible, including
every phone, behave exactly as before.
