### Fixed — bluebell rating recombination now keys on the location's actual exposure, not the shape of the cache

`BriefingEvaluationService.recombineBluebell` decided whether an incoming bluebell rating should
average onto a prior sky rating (OPEN_FELL) or stand alone (WOODLAND) by checking whether the
prior cache entry for that location *looked* sky-scored (non-null `fierySkyPotential`), rather
than by reading the location's own `BluebellExposure`. Currently unreachable in production — all
15 real canopy sites are WOODLAND+BLUEBELL only, with no sky `LocationType`, so no prior sky entry
can exist for them — but latent: a WOODLAND-exposure bluebell site that also carries a sky-eligible
`LocationType` (so it is not `isWoodlandOnly()` and is still sky-scored off-season) would have a
stale, months-old sky entry averaged into its rating the moment bluebell season starts, the same
"averaged across the wrong axis" defect class as the OPEN_FELL tide double-count fixed earlier.

`recombineBluebell` and `mergeBluebellFromBatch` now take the location's real `BluebellExposure`
and only withhold the sky peer when it is explicitly `WOODLAND` — mirroring
`RatingCombiner.selectRatingPeers`'s own rule. The exposure is captured in `BatchResultProcessor`
at the one point in the pipeline where the real `LocationEntity` is still in hand, threaded through
`ForecastResultHandler.mergeBluebellCacheKey` alongside the existing per-cache-key result grouping.
A location missing from the map (or carrying a `null` exposure) defaults to not-WOODLAND, matching
`RatingCombiner`'s own default.
