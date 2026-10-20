### Fixed — the watchdog finds an evening run whose deadline falls after UK midnight

The evening refresh fires at 20:00 UTC, so with a long budget its deadline or timeout failure can land after UK midnight, when the watchdog's today-only run query no longer returned it. The evening check now looks back to the earlier of the UK day start and now minus the evening budget minus an hour.
