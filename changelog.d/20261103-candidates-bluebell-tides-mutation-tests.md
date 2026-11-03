### Changed — Mutation tests for the candidate collector, bluebell gloss and WorldTides ingestion

Added tests that pin the per-gate slot counts and stability breakdown the briefing candidate collector reports, the exact user message and headline placement of the bluebell gloss service, and the WorldTides ingestion service's merge order, fetch-window boundaries, source-change alerts, backfill chunking and the eviction callback it fires once per tide_extreme write. Test code only; no production behaviour changed.
