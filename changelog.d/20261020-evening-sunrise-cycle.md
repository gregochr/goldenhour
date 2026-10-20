### Added — evening sunrise refresh cycle (dormant)

A third forecast cycle, `evening_sunrise_refresh`, re-evaluates tomorrow's sunrise alone at 20:00 UTC (every stability, bypassing the cache gate, its own `photocast.pipeline.evening-safety-timeout` wait budget of PT2H, bounds PT30M..PT3H30M). It is seeded PAUSED (V184), so nothing runs until it is resumed from the Scheduler screen. The cycle gives no failure evidence: its settle claims the run and applies no count or reset, and it never blocks or reorders the other cycles' failure settling.
