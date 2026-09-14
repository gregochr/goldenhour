### Fixed — the aurora status on screen is the newest one asked for, not the last to land

`AuroraStatusProvider` asks `GET /api/aurora/status` on mount, on a 5-minute poll and on every window
focus, and published whatever answered — so with two requests out at once (a poll and a focus, or
two focuses) the answer that *landed* last won, not the one *asked* last. Out of order is the
ordinary case rather than an exotic one: each status request can wait on up to three live NOAA calls,
which the backend caches separately for 1, 5 and 15 minutes, so a request that misses a cache can
take far longer than one made a moment later that hits it. Two directions, both traced in the code
rather than seen in a browser:

- **A status from before an alert ended, landing after the all-clear**, put the banner back up and
  switched the map's viewline back on — `GET /api/aurora/viewline` does not check the alert state —
  until the next poll or focus. The live-scores refetch it also set off came back empty, because the
  backend empties its scores on CLEAR.
- **A status from before an alert began, landing after the alert**, took the banner down mid-alert,
  cleared the map's live scores and — with no stored aurora run to keep the mode available — bounced
  the Map tab out of aurora mode to Sunset, clearing the saved rating floor as it went.

Each request is now numbered, and an answer older than the one already applied is dropped. A failed
fetch still writes nothing and leaves the status on screen. This closes the residual #827's entry
named for this provider — statuses published out of order, "one level up" from the live-scores fetch
that change fixed.

⚠️ **Deliberately not `useComingUpFeed`'s shape**, where only the most recently *made* request may
write. Under that rule an older answer that lands while a newer request is still out is dropped even
if the newer one then fails, leaving the status older than it needs to be until the next poll. Here
only an *applied* answer moves the mark, so a failed request blocks nothing — pinned by its own test,
which fails under the other rule.

The two counters are refs rather than locals in the effect, because StrictMode runs the effect twice
on mount in development — two status requests at once on every dev load — and the numbering has to
span both runs. Also pinned.

Pinned in a new `AuroraStatusContext.test.jsx`: the real provider, with the real `AuroraBanner` — and
its real viewline hook — as the consumer, the API module mocked, the out-of-order answers held by hand
and every late settle inside an awaited `act`. Against the unfixed provider the three race tests fail
(an alert after an all-clear, an all-clear after an alert, and StrictMode's two runs), while the
in-order, failed-newer and failed-refresh tests pass, as they should. Nine mutants of the guard: eight
killed, each by the tests that name what it breaks, and one equivalent (`<=` for `<` — request
numbers are unique, so the two never differ). With the settle helper's `await` removed, every test in
the file fails rather than letting a negative pass: each positive control settles through the same
helper, and the failed-refresh test gained one for exactly that reason, since its first form still
passed un-awaited.

⚠️ **Not fixed here, and named so it reads as known:** `useNlcSighting` has the identical race — its
comment says it mirrors this provider "so the two banners behave identically", which now holds for
everything but the ordering — and `WindowFirstBriefingContext`'s briefing and scores fetches have the
same shape, where an older briefing landing last is also written to the SWR cache.
