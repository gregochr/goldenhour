### Removed — the callerless Close-to-home endpoint and its second assembly path

`GET /api/briefing/close-to-home`, `CloseToHomeService`, `CloseToHomeResponse` and
`BriefingService.getServedBriefing()` are deleted, with their tests. Nothing has called them since
the Close-to-home panel died in v1 retirement D2. The Plan payload is unchanged: slot tide is now
stripped on every API-path accessor, and `ServedBriefingAssembler.assembleWithoutPlan` survives only
as the package-private first stage of `assembleForPlan`. The `photocast.close-to-home.radius-miles`
property keeps its name (Ask's `nearHome` still reads it), as do `getHomeLocation` and the local
radius setting. Comments that pointed at the deleted code were reworded.
