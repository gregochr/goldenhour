### Fixed — the aurora status on screen never steps back to an older answer

`AuroraStatusProvider` asks `GET /api/aurora/status` on mount, on a 5-minute poll and on every window
focus, and published whatever answered — so with two requests out at once (a poll and a focus, or
two focuses) the answer that *landed* last won, not the one *asked* last. Nothing in the app kept
them in order: each status request can wait on up to three live NOAA fetches of its own (four HTTP
requests — solar wind is two), which the backend caches separately for 1, 5 and 15 minutes and does
not share with a request already in flight. Two directions, both traced in the code rather than seen
in a browser:

- **A status from before an alert ended, landing after the all-clear**, put the banner back up and
  switched the viewline back on — `GET /api/aurora/viewline` does not check the alert state, and the
  map draws the line wherever it is still in aurora mode — until the next poll or focus. The
  live-scores refetch it also set off came back empty, because the backend empties its scores on
  CLEAR.
- **A status from before an alert began, landing after the alert**, took the banner down mid-alert,
  cleared the map's live scores and, on a Map tab showing aurora with no stored aurora run to keep
  the mode available, bounced it to Sunset, clearing the saved rating floor as it went.

**Which browsers it reaches was measured, and Chrome is not one of them.** A throwaway server set
this endpoint's caching headers (a body-derived ETag, `Cache-Control: private, no-cache`) and held
the first of two requests sent 50 ms apart, with an `Authorization` header as axios sends one.
Chromium's HTTP cache kept the second request back until the first was answered — for the whole
1.5 s hold — so on Chrome and Edge the two arrived, and landed, in order. WebKit and Firefox sent
both at once, and in both the second overtook the first. WebKit here is Playwright's, not an iPhone;
how long Chromium would hold a request back, and how often two overlap in real use, were not
measured.

Each request is now numbered, and an answer older than the one already applied is dropped. A failed
fetch still writes nothing and leaves the status on screen. A 401 or 403, which `getAuroraStatus`
answers as null, is an answer like any other: it applies, and it outranks anything asked before it.
This closes the residual #827's entry named for this provider — statuses published out of order,
"one level up" from the live-scores fetch that change fixed.

⚠️ **Deliberately not `useComingUpFeed`'s shape**, where only the most recently *made* request may
write — and that shape is right there, for a reason to check before copying either one. Its requests
can ask different questions (two overlap only across a date roll, so the older one asks for
yesterday's feed and its answer is wrong, not merely older). Every status request asks the same
question, so an older answer is only older, and dropping it while a newer request is out would leave
the status older than it needs to be if that request then failed. Here only an *applied* answer
moves the mark, so a failed request blocks nothing — pinned by its own test. Both rules rest on the
effect having no dependencies and the catch writing nothing, and the provider's comment says so: an
effect keyed on anything would need a per-run cancel as well, because its cleanup cancels nothing.

The two counters are refs rather than locals in the effect, because StrictMode runs the effect twice
on mount in development — two status requests at once on every dev load — and the numbering has to
span both runs. Also pinned.

Pinned in a new `AuroraStatusContext.test.jsx`: the real provider, with the real `AuroraBanner` — and
its real viewline hook — as the consumer, the API module mocked, the out-of-order answers held by hand
and every late settle inside an awaited `act`. Against the unfixed provider the five ordering tests
fail (an alert after an all-clear, an all-clear after an alert, an alert after a "no access" answer,
two stale answers after the newest, and StrictMode's two runs), while the in-order, failed-newer and
failed-refresh tests pass, as they should. Twelve mutants of the guard: eleven killed, each by the
test that names what it breaks, and one equivalent (`<=` for `<` — request numbers are unique, so the
two never differ). With the settle helper's `await` removed, every test in the file fails rather than
letting a negative pass.

⚠️ **Two adversarial reviews of the first version found three holes in that file, all real.** With no
test holding more than two requests out, a mutant setting the mark to the newest request *made*
instead of the landed answer's own number passed every test, and so did one where a dropped answer
lowered the mark to its own number — which lets the second of two stale answers back in, the very
defect this fixes, one answer late. A third mutant, ignoring null answers, passed every file that
reaches the real provider, because nothing resolved one. There are now three-request tests landing
in order and newest-first, and one ordering a "no access" answer; each fails under its mutant. The
reviews also found that a request no test expected was answered `undefined` — which the provider
*applies*, taking the banner down, the very state several negatives assert. It is now refused, and
a refused request writes nothing.

Also corrects two comments #827 left behind, in `MapView.jsx` and `MapViewAuroraLiveFetch.test.jsx`,
which still said the provider publishes whatever answers. The `cancelled` guard they explain stays:
both races it stops are between locations requests, which no order of statuses prevents.

⚠️ **Not fixed here, and named so it reads as known:** `useNlcSighting` has the identical race — it
names `useAuroraStatus` as its model, though it already differed in cadence and in being a hook per
consumer rather than a shared provider — and `WindowFirstBriefingContext`'s briefing and scores
fetches have the same shape, where an older briefing landing last is also written to the SWR cache.
Both are being fixed separately.
