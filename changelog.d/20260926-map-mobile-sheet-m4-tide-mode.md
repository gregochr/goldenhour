### Added — user settings: persisted map tide mode (auto / always / off)

A new per-user preference, `map_tide_mode` (V155), copying the map-colour preference's shape
exactly: nullable with no default (null = never chosen = Auto on the client), written only through
a column-scoped repository update, never a whole-entity save. `PUT /api/user/settings/map-tide-mode`
validates against `auto`/`always`/`off`, `UserSettingsResponse.mapTideMode` rides the existing
settings payload, and `hooks/useReaderSettings.js` exposes `mapTideMode` (defaulting to `'auto'`)
and a `saveTideMode(mode)` action serialised through its own line of saves — one save in flight at a
time, a newer choice queued behind an older one supersedes it, and a failed save reverts to the last
mode the server is known to hold rather than to a discarded queued choice or the pre-session value
(reusing `colourSaveQueue.js`'s mechanics, which already generalise over the save function).

This is Phase M4 of the map mobile sheet plan (`docs/engineering/map-mobile-sheet-plan.md`): no UI
ships in this phase. `mapTideMode`/`saveTideMode` are threaded through `App.jsx` →
`WindowFirstMapPane` → `MapView` on the same route as `mapColourScale`, wired and tested here so
Phase M5 (the Auto rule and the Tide mode control) needs no new plumbing of its own.
