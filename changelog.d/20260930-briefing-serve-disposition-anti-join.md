### Fixed — `GET /api/briefing` took tens of seconds per request, so the Plan tab rendered nothing and the map drew no heat field

Since #943 (2026-09-29, the verdict-minimum-sample rule's "examined" evidence, P1-A round 2) every
`GET /api/briefing` and `GET /api/briefing/digest` serve — and every briefing build — ran
`ForecastRunDispositionRepository#findLatestNonCachedDispositions`, a correlated scalar subquery
(`created_at = (SELECT MAX(created_at) … WHERE <same slot>)`) over `forecast_run_disposition`.
V101's only indexes on that table are `(job_run_id)` and `(disposition, created_at)`, neither of
which serves the slot correlation, and Postgres cannot decorrelate a scalar subquery in `WHERE`: it
ran the subplan once per outer row as a full scan of the whole 30-day retention window. Measured on
a Postgres 16 with the table sized to production's own rate (13,120 rows per five days of
`evaluation_date`, ~78k rows at retention): **36 s per request** — and every open client re-issued
it on focus and every 10 minutes while the previous request still held a pool connection.

On the phone this looked like two unrelated defects, and both were this one: the briefing never
arrived before Cloudflare's 100 s limit, so `WindowFirstShell` had no window cards (no matrix, no
Regional planner door, no lens count line, and no "No forecast to show" line either, since the
request was still loading), and the Map tab fell back to filler window rows — no verdict word on
the pill, no served windows to build heat point sets from, hence no field — while its chips still
showed stars, because those come from `GET /api/briefing/evaluate/scores`, which never ran this
query.

**The query is rewritten as a `NOT EXISTS` anti-join** ("no non-cached row for the same slot is
strictly later"), which Postgres decorrelates into a hash anti join: 34 ms on the same data with no
new index at all, returning the identical rows — the tie rule is unchanged, since two rows at the
same max instant have no strictly-later row and both survive, and `SKIPPED_CACHED` is still
excluded on both sides. **V160 adds `idx_forecast_run_disposition_slot`** on
`(location_name, evaluation_date, event_type, created_at)` as belt and braces, so a future per-slot
"latest" read on this table cannot reintroduce the scan whichever shape it takes.

Tests: `ForecastRunDispositionRepositoryTest` gains an H2 nest pinning the rewritten query's
answers (latest wins in both orders, cached excluded on both sides, cached-only slot absent,
`SUBMISSION_FAILED` supersedes an older triage, a tie returns both rows, slots independent and
range-bounded) — until now those semantics were proven only by the CI-only
`DispositionWriteIntegrationTest`, which still runs unchanged against Postgres.
`DispositionSlotIndexMigrationTest` (CI-only, Testcontainers) proves V160's index and its column
order.
