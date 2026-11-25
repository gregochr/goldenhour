# Snow state: never run a place at the grid cell's height — plan

**Status:** revision 2 (2026-11-25), after one adversarial review (counts, accepted gap, rate limit folded into §2.3). The owner approved the snow-only fix on 2026-11-25 ("yes please").
**Roles:** Opus plans and reviews; Sonnet builds (CLAUDE.md, "How every code change is made"). One PR.

## 1. The defect, measured in production

`SnowStateRefreshJob.points()` sends a place with no `elevation_m` as `NaN`. `OpenMeteoClient.fetchSnowStateBatch`
then sends `elevation=nan` (`ELEVATION_MODEL_CELL`). Open-Meteo's documented meaning of `nan` is "disable the
statistical downscaling and use the averaged grid-cell height". That is the fault S2 set out to remove: Rydal at
59 m got a 346 m cell's snow.

Production after v2.42.2 (owner's queries, 2026-11-25 22:14 UTC run):

- 198 of 266 enabled places have no `elevation_m`.
- Of Friday 27 Nov's 17 "lying" places, 14 have a height and sit on fell ground (215–978 m). The other 3 have
  none: The Langdale Boulders, Great Langdale Green Lane (both on the Great Langdale valley floor, about 100 m)
  and Hareshaw Linn. They show 3.2–4.3 mm, as much as Side Pike at 362 m. These are false positives.

When Open-Meteo is given no `elevation` at all, it downscales to its own 90 m DEM. A batched request must give an
elevation for every point or for none, though, so the place's missing height can't simply be left out.

## 2. The fix (snow job only)

1. **Look the heights up.** Before the weather fetch, the job collects the enabled places whose `elevation_m` is
   null and whose surface height it does not already hold. It asks Open-Meteo's Elevation API for them (the same
   90 m DEM the forecast downscaling uses). It reuses `LocationEnrichmentService`'s batched lookup: make the
   private `fetchElevations(List<double[]>)` reachable through a new public method that chunks at 100 points
   (the API's limit). Each chunk is its own call, and a failed chunk fails only its own points.
2. **Remember them in memory.** A `ConcurrentHashMap<Long, Height>` on the job, keyed by location id. `Height`
   holds the lat, lon and metres it was looked up for. A cached height is reused only while the place's lat and
   lon are unchanged, and a moved place is looked up again. A restart costs at most 2 lookups. Nothing is written
   to `locations`.
3. **Never send `nan` for a place.** A place that has no recorded height and no looked-up height (lookup failed)
   is **left out of this run**. It writes nothing, so its previous row stays (the existing "partial runs never
   erase" rule), and it counts in the run's `failed` total. Summits are unchanged: they carry their OS heights.
4. **A recorded `elevation_m` always wins.** The job never looks up a place that has one.
   - **Counts (review).** The run's total is the points sent plus the places skipped, and `failed` is that total
     minus the points fetched. So `locationsProcessed`, `completeRun` and `statusFor` all see a skipped place as a
     failure, rather than losing it from the totals.
   - **Accepted gap (review).** A skipped place keeps its previous row, which is fresh for up to 36 h
     (`SnowStateReader.FRESH_HOURS`). After this fix every row is written at a real height, so the only stale
     grid-cell row possible comes from the first run after release, and only if that run's lookup fails. The
     next successful run overwrites it. We accept that rather than add a delete path.
   - **Rate limit (review).** The lookup is skipped while `OpenMeteoClient`'s cool-off is in force. In that case
     the weather fetch is refused anyway, so the places it skips are simply counted failed. A failed lookup,
     including a 429, logs and skips its places. It never starts or extends the cool-off.
5. **Bookkeeping.** Log each lookup chunk as an API call on the job run (`ServiceName.OPEN_METEO_FORECAST`,
   endpoint `elevation-lookup(N)`). That avoids adding an enum value, which would need a `LocalH2EnumWidener`
   line. The fire outcome's summary is unchanged.
6. **Leave `OpenMeteoClient`'s `nan` path alone.** It stays as a defensive fallback, but no caller sends `NaN`
   any more. Update its Javadoc and the job's class Javadoc ("a place with no recorded height runs at the
   model's own" becomes "...at its looked-up DEM height; with none, it is skipped").

Fix the stale copy while here:

- `SchedulerJobCatalogue` `snow_state_refresh` says "It only records the figures for now; nothing shows them
  yet". Since S3 the snow topics read it. Replace that sentence with "The snow topics read these figures."
- The class Javadoc's "**Record only**: no hot topic reads these rows yet" is stale for the same reason. Say the
  snow topics read them (`SnowStateReader`).

## 3. Deliberately unchanged

- `locations.elevation_m`. It feeds cloud-inversion eligibility (`InversionProfileEligibility`) and scoring
  (`ForecastService`). Filling it in would silently change inversion topics and scores. That is a separate owner
  decision.
- The snowpack model, thresholds, detectors and summits.

## 4. Tests

- `SnowStateRefreshJobTest` (pinned stubs, no `any()`, historical dates):
  - a place with no height is sent at its looked-up height;
  - a place with a recorded height is never looked up;
  - a second run reuses the cached height (one lookup across two runs);
  - a moved place (changed lat/lon) is looked up again;
  - a failed lookup leaves the place out (not sent, no row, counted failed) while the other points still run;
  - no request ever carries `NaN` for a place. Replace the existing "asks for the model's own" test.
- `LocationEnrichmentService`'s new public method:
  - chunks at 100 (a 198-point input is two calls);
  - a failed chunk returns no heights for its points only;
  - a wrong-sized or null-bearing answer fails that chunk.
- `SchedulerJobCatalogue`: whatever test pins the description text.

## 5. After release

Re-run the owner's Friday query. The three valley-floor places should drop out of "lying", and `new_lying` should
not rise at low places.
