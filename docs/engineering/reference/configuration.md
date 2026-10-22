# Configuration keys

> Moved verbatim from `CLAUDE.md` on 2026-10-22 to keep the always-loaded file small.
> Read the section you need; this file is not loaded automatically.

## Configuration

Never commit `application.yml`. Only `application-example.yml` is committed.

Key config: `anthropic`, `worldtides`, `spring.datasource`, `spring.flyway`, `spring.mail`, `notifications`, `forecast.locations`, `jwt`, `server.port`, `aurora` (enabled, poll-interval-minutes — unread, the interval is the `aurora_polling` scheduler row's, light-pollution-api-key, noaa.*, met-office.*, triggers.*, bortle-threshold.moderate/strong).

`photocast.scheduler.solar-reference.lat|lon` (the point `SOLAR_OFFSET` jobs are timed against; Durham 54.776 / -1.575 by default; both or neither; an invalid value logs ERROR and uses Durham, it never stops startup); `photocast.pipeline.safety-timeout` (ISO-8601, default PT3H30M: the one wait budget per forecast cycle, measured from its first batch submit and shared by WAIT and RETRY_FAILED; only `application-example.yml` sets it, and the production host's hand-edited config must be changed there too if it overrides it); `photocast.pipeline.evening-safety-timeout` (ISO-8601, default PT2H, bounds PT30M..PT3H30M, an out-of-range value fails startup: the evening refresh's own wait budget, measured and enforced like `safety-timeout`, and read by the forecast-cycle watchdog for its evening check).

`photocast.ask.*` (Ask PhotoCast, `AskProperties`; every key is bounded and a bad value fails startup, `application-example.yml` lists each with its range): `enabled` (false), `stub` (false; refused under `prod`), `seed-local-fixture` (false; local H2 only; not an `AskProperties` field — only `AskLocalFixtureSeeder`'s `@ConditionalOnProperty` reads it), `model` (HAIKU; `HAIKU | SONNET` only), `max-turns` (4), `max-tokens` (600), `call-timeout-seconds` (20; may not exceed the deadline), `deadline-seconds` (30), `rate-per-minute` (5), `limit-lite` (3), `limit-pro` (30; ADMIN takes it too), `engine-ceiling-multiplier` (3), `daily-spend-cap-usd` (0.50), `ready.max-cycles-per-day` (6), `cache.max-entries` (2000), `cache.ttl-minutes` (30), `log.retention-days` (90). Beside them: the `ask` instances under `resilience4j.retry|circuitbreaker|bulkhead` in every YAML set, and `notifications.admin-alerts.enabled`, which is the spend cap's only signal. The production host's config is hand-edited (the checkout is stale and dirty): read the RUNNING file, never this repo's, before deciding what is on.

---
