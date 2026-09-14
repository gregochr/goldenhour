### Fixed — the Plan tab's briefing and ratings are applied in the order they were asked for, not the order they land

`WindowFirstBriefingProvider` asks `GET /api/briefing` and `GET /api/briefing/evaluate/scores` on
mount, on a 10-minute poll and on every window focus, and published whatever answered — so with two
requests out at once (a poll and a focus, or two focuses) the answer that *landed* last won, not the
one *asked* last. Two requests finish in whatever order their server work does, and the briefing's is
real work (assembled at serve time: hot topics recomputed live, the cached ratings re-enriched), so the
older one can land second; how often requests overlap and cross is not measured. Both ways it showed
are traced in the code rather than seen in a browser:

- **An older briefing** put the older windows back on every surface the provider feeds — a "Worth
  it" the forecast had since withdrawn, or a "Poor" it had since lifted — until the next poll or
  focus. And it was **written to the SWR cache**, so the next cold start painted it first.
- **Older ratings** put the older rows back under the heat field and the map handoff: a re-scored
  window back at its old rating, or a window the batch had just rated blank again — the same symptom,
  "best spot 5★" over a blank thumbnail, that the provider's comment records from production for a
  different cause (the ratings fetch once ran on mount only).

Each fetch now numbers its requests and drops an answer older than the one already applied, and the
briefing's check sits before its cache write, so a dropped briefing is never cached. The request's own
number is what counts, not the briefing's `generatedAt`, which two serves of one build share while
differing in content. It composes with the provider's two existing rules rather than replacing either:
the cache generation is still taken *before* the await, so a briefing in flight across a logout is
still refused by the sweep; and a null (204) is still ignored — which also means it moves nothing, so
it cannot block an older real briefing that lands after it. An empty ratings response is treated the
same way for the same reason: it withdraws no rows, so it blocks none.

⚠️ **Two numberings, not one.** The two requests go out together on every refresh and land in either
order; numbered as one, the ratings landing first would count as newer than the briefing asked for
beside them, and that briefing would be dropped. Nor are the ratings ordered *through* the briefing —
they are separate fetches, each ordered on its own. As for the NLC banner beside it, only an *applied*
answer moves a mark, and to that answer's own number — not `useComingUpFeed`'s latest-issued-wins, and
not the newest request made. The counters are refs, so the numbering spans StrictMode's two runs of the
effect. The provider's comment also says why an effect-cleanup flag cannot do this job — every poll and
focus request is made inside one run of the effect — and which of its fetches such a flag does fit.

Pinned in a new `WindowFirstBriefingFetchOrder.test.jsx`: the real provider under a probe — it has no
single natural consumer, so the probe prints the slices the ordering guards protect — with the API
modules mocked, the real SWR cache, the out-of-order answers held by hand and every late settle inside
an awaited `act`. Against the unfixed provider eight of its twenty tests fail (both directions for each
fetch, the cache write, a same-build serve, and StrictMode's two runs for each), while the in-order,
failed-newer, null-or-empty-newer, three-request, failed-refresh, logout and two-numberings tests pass,
as they should. Twenty-six mutants: twenty-two killed, each by the tests that name what it breaks —
among them the cache write moved above the check, the generation taken after the await, a null or
empty answer moving the mark, the mark moved to the newest request made, ordering by `generatedAt`,
one count shared by both fetches, counters held per run of the effect, and a `catch` that clears what
is on screen. Four survive: `<=` for `<` in each guard, equivalent because request numbers are unique;
and two that differ from the shipped refs only if the role-scoped cache key changed while the provider
is mounted — counter objects rebuilt every render, and the ratings counters reset on each run of the
effect — which cannot happen, since `AuthGate` renders the provider only while signed in and every
change of role is a sign-in or a sign-out. With the settle helper's `await` removed, all twenty tests
fail.

The same provider's reach and settings fetches, keyed on `homeSettingsVersion`, have the other shape of
this race: a newer request there follows a settings change, so the older one answers a superseded
question. That wants the effect-cleanup kind of fix, not this one, and is a change of its own.
