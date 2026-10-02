### Documentation — which places `slot_atmosphere` records, and why some never are

"Record conditions for every place" overclaimed. It means every candidate slot whose weather the
pipeline fetches through `ForecastService.fetchWeatherAndTriage`, not every row in `locations`.
WILDLIFE-only places, BLUEBELL-only places out of season, unregioned locations and disabled
locations are never candidates and so never recorded. The owner decided on 2026-10-02 that this
is deliberate: production has three WILDLIFE-only places, all in regions with many sky locations
and none with an elevation, and every topic that reads these rows is shown per region, so their
readings would change nothing a reader sees. The hand-started `POST /api/forecast/run` and
`ForceSubmitBatchService.forceSubmit` apply no location-type filter, so a place named there is
recorded whatever its type; that is recorded as known behaviour, not changed.

CLAUDE.md, `SlotAtmosphereWriter`'s class javadoc and `BriefingService.isColourLocation`'s javadoc
now say so. Documentation only, no code change.
