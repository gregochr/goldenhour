### Fixed — the NLC sighting banner applies answers in the order they were asked for, not the order they land

`useNlcSighting` asks `GET /api/nlc/sighting` on mount, on a 10-minute poll and on every window
focus, and published whatever answered — so with two requests out at once (a poll and a focus, or two
focuses) the answer that *landed* last won, not the one *asked* last. Two requests finish in whatever
order their server work does, and a sighting request that finds the backend's NLCNET cache stale
scrapes the page before it answers, so the older one can land second; how often requests overlap and
cross is not measured. Three ways it showed, all traced in the code rather than seen in a browser:

- **A report taken before it aged out, landing after the newer "nothing to show"**, put the banner
  back up until the next poll or focus.
- **The same late report, landing after a newer one the reader had dismissed**, put it back up too.
  The dismissal is keyed by `reportedAt` so that a newer report re-shows the banner — and an older
  report has a different key as well, so it read as one the reader had not seen.
- **A "nothing to show" taken before a report arrived, landing after it**, took the banner down.

Each request is now numbered, and an answer older than the one already applied is dropped. A failed
fetch still writes nothing and leaves the banner as it was. A null — what the API module answers for a
reader the banner is not for (401/403) — is an answer like any other here: applied, and it moves the
mark, unlike the Plan briefing's 204. It is the same race as `AuroraStatusProvider`'s, the provider
this hook is shaped after.

⚠️ **Deliberately not `useComingUpFeed`'s shape**, where only the most recently *made* request may
write: under that rule a failed newer request would have an older answer dropped too, leaving the
banner older than it needs to be until the next poll. Here only an *applied* answer moves the mark,
and to that answer's own number — not to the newest request made, or an older answer landing while two
newer requests are out would drop the one in between. The two counters are refs rather than locals in
the effect, so the numbering spans StrictMode's two runs of it — two sighting requests at once on
every dev load.

**Residual, accepted:** request order stands in for data order, and not perfectly. A newer request
whose scrape fails fast answers from the backend's cache as it stood, so it can land first and drop an
older request's slower, successful scrape until the next poll or focus. It needs a fast failure to race
a slow success, and its cure is one scrape at a time on the backend, not a rule in this hook.

Pinned in a new `useNlcSighting.test.jsx`: the real hook under its only consumer, the real
`NlcSightingBanner`, with the API module mocked, the out-of-order answers held by hand and every late
settle inside an awaited `act`. Against the unfixed hook five of its nine tests fail — a report after
"nothing to show", a report after a dismissed newer one, "nothing to show" after a report, an older
report after a newer null, and StrictMode's two runs — while the in-order, failed-newer, three-request
and failed-refresh tests pass, as they should. Eleven mutants of the guard: ten killed, each by the
tests that name what it breaks — deleting the check, deleting the mark, latest-issued-wins, moving the
mark on a failure, when the request is made, or to the newest request made, counters as effect
locals, the Plan briefing's null rule ported here, a null applied without moving the mark, and a
`catch` that clears the banner — and one equivalent (`<=` for `<`: request numbers are unique). With
the settle helper's `await` removed, all nine tests fail rather than letting a negative pass.
