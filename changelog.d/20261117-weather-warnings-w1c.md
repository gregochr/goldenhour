### Changed — Weather warnings read the real Met Office feed shape (off by default)

Third phase of the weather-warnings series (`docs/engineering/weather-warnings-plan.md`). The warnings poller was
written before a key existed and guessed the feed wrong; it now reads the real NSWWS v1.1 shape recorded from the
live feed. Each feed entry is a change event whose update document holds several warnings, versions are decimals such
as "3.0" (stored as NUMERIC by migration V225 and compared numerically), likelihood and impact are raw integers, and
EXPIRED is treated like CANCELLED (the warning has ended). On a cold start or after a gap the poller also loads the
issued snapshot. Warnings are ordered as the Met Office style guide requires (level, impact, likelihood, start time),
a re-issued warning (version 2.0 or later) is flagged "updated" alongside the existing upgrade flag, and
`/api/warnings` now carries the public Met Office link and the "Met Office Weather Warnings © Crown Copyright 2026"
attribution. The base URL and the key header name (default `apikey`) are configurable. Nothing is live: the flag
`photocast.warnings.enabled` stays off, the feed is never called in tests, and no rating, verdict, prompt or heat field
changes.
