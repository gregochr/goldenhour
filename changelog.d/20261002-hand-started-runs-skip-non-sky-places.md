### Fixed — hand-started runs no longer send hides, woods and bluebell woods to the sky prompt

Two admin routes evaluated places that are not sky subjects with Claude's SKY prompt, spending
real money and writing a meaningless rating that `GET /api/forecast` then served.
`POST /api/forecast/run` (and `retry-failed`) always hands the engine an explicit location list,
and the engine's `hasColourTypes` filter only ran on a defaulted one; `ForceSubmitBatchService
.forceSubmit` filtered by region alone. Both now keep only places `LocationEntity.hasColourTypes()`
admits, in one place per engine, before any fetch — the same rule the three term endpoints and the
JFDI batch already applied. Neither route has a woodland or bluebell lane, so nothing that worked
is lost.

What the caller sees: a "run for all locations" drops the non-sky places and logs how many; naming
one non-sky place on `POST /api/forecast/run` answers 400 (`'<name>' is not a sky location: it has
no sunrise or sunset forecast`) and starts no run or `job_run` row; `retry-failed` answers 404 when
none of its failed places is a sky subject any more; `forceSubmit` on a region with no sky
locations reads as an empty region. A hide named on these routes no longer gets a `slot_atmosphere`
reading there, which closes the "known wrinkle" recorded in CLAUDE.md on 2026-10-02.
