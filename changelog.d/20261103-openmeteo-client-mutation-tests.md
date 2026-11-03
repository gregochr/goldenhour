### Changed — Open-Meteo client batch and retry behaviour is now pinned by tests

New `OpenMeteoClientResilienceTest` covers the Open-Meteo client's chunk splitting at the 20-coordinate boundary, retry then success, retry exhaustion, partial results when one chunk fails, slot-for-slot merging of results, empty input, cause-chain depth limits and the rate-limit backoff. A PIT run scoped to `OpenMeteoClient` goes from 61% to 79% of mutants killed. Test-only; no production code changed.
