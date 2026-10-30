### Removed — dead frontend modules and exports

Deleted the old map popup (`MarkerPopupContent`) and its `TideIndicator`, plus `solarEventTimes`,
`VerdictPill`, `SlotLocationName` and `useLocalStorageState`, none of which any live code imported,
along with their tests, the 57 stale `MarkerPopupContent` mocks and `LocationSheetFactsParity` (the
sheet's own tests cover its facts). Also dropped a dozen unused exports and API functions
(`forecastApi` tide helpers, `tideApi.fetchTidesForDate`, the moon and Bortle helpers and others),
and reworded comments that cited the deleted names. `prop-types`, imported in 165 files but only
installed as a transitive dependency, is now declared in `package.json`. Nothing on screen changes.
