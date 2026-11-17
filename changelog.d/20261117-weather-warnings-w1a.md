### Added — Met Office weather warnings: ingest and storage (backend, off by default)

First phase of the weather-warnings series (`docs/engineering/weather-warnings-plan.md`). A new poll job reads the
Met Office National Severe Weather Warning Service feed, stores every warning version it has not seen in an
insert-only table (V224), and a pure timeline decides which warnings are in force at any instant, including the
rewound one, with a 24-hour flag when a warning's level has been raised. A ray-cast match against the enabled
locations says which places each warning covers. `GET /api/warnings` serves the warnings in force to every signed-in
reader (with a `stale` flag so an empty list is never read as an all-clear), and admin-only endpoints report the
poll's health and insert a clearly simulated warning for testing. Everything sits behind
`photocast.warnings.enabled`, which is off, and nothing changes a rating, verdict or pick. No screen shows warnings
yet. The feed shape the parser expects is documented in `docs/engineering/reference/weather-warnings.md`.
