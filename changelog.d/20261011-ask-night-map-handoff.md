### Fixed — the Map follows an Ask pick on a night window

`MapView`'s Ask window-follow effect told the app a night pick was a solar date (`isNight: false`), which
`App` refuses once it is past — every pick of the night in progress before nautical dawn, when that night is
yesterday's. It now passes the row's own provenance and keeps a night `App` would refuse local, as the
window control does. And it no longer spends the choice before the night's row exists: a freshly mounted Map
loads its astro and aurora rows from fetches still in flight when "Show on map ›" fires, so the choice used
to be consumed with nothing to apply it to. It now waits for the row and applies it the moment it appears.
In the other direction, a question asked while the Map shows an astro or aurora row now carries that night's
window id ("is this any good?" on tomorrow's night row means that night).
