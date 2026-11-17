### Added — Weather warnings: ids on briefing windows and location rows, Operations panel (off by default)

Second phase of the weather-warnings series (`docs/engineering/weather-warnings-plan.md`). When the feature flag is on,
the served briefing's windows and the forecast list's location rows now say which Met Office warnings apply to them
(`warningIds`), and each briefing window says how many of a region's places a warning covers. The ids are worked out
fresh on every serve from the stored warnings and the clock, are never saved with the briefing, and are the same for
Lite, Pro and Admin readers. They never change a rating, verdict, Best bet, count or the heat field: a golden test
serves the same briefing with and without warnings and checks that only the warning fields differ. Operations gains
a read-only Warnings tab (Testing group) showing the poll's health, stored counts, unknown weather types and the
warnings in force, with a form to simulate a warning for testing. No reader-facing screen shows warnings yet.
