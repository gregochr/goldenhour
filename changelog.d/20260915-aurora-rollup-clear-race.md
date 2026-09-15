### Fixed — a CLEAR landing mid-briefing no longer fails that cycle's best bet

`BriefingRollupBuilder.buildRollupJson` checked whether an aurora alert was active and
alert-worthy, made a DB call (`TravelDayService.isTravelDay`) to decide whether tonight is excluded
as a travel day, and only then wrote the alert into the rollup — re-reading
`AuroraStateCache.getCurrentLevel()` a second time inside `appendAuroraEvent`. The cache's getters
are independent unlocked volatiles with no lock spanning the two reads, so a real alert clearing or
an admin's reset landing during that DB round trip nulled the level in between: the second read
threw a `NullPointerException`, which `BriefingBestBetAdvisor.advise` catches and reports as that
cycle's best bet FAILED, in place of the mechanical headline fallback the class exists to guarantee.
The two `getCurrentLevel()` calls inside the eligibility check itself are nanoseconds apart with
nothing between them and were never the exploitable window — the DB read is.

The alert level, trigger Kp, dark-sky count and clear count are now read once, before the
travel-day check, and passed into `appendAuroraEvent` rather than it re-reading the cache. A CLEAR
landing mid-check now either excludes the alert (when it lands before the snapshot) or writes it as
the alert that was running when the decision to include it was made (when it lands after) — one
coherent state either way, never a crash and never a level from one moment paired with counts from
another.

Pinned in a new `BriefingRollupBuilderAuroraSnapshotTest`, a plain unit test with a real
`AuroraStateCache` whose CLEAR is made from inside the stubbed `isTravelDay` call — the same
"transition inside the stub" technique `AuroraControllerStatusSnapshotTest` used for the sibling
bug in `AuroraController.getStatus`. Against the old code the race test fails with exactly this
`NullPointerException`; a second, non-racing test pins the ordinary case unchanged.
