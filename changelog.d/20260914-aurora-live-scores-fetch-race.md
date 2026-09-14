### Fixed — the map's live aurora scores answer for the latest alert state

The live aurora scores (`getAuroraLocations()`, the NOAA-triggered state for the night in progress)
are fetched by an effect keyed on the shared aurora status — and that status is a fresh object on
every successful 5-minute poll and every successful window focus, because `AuroraStatusProvider`
publishes whatever the status endpoint answers. So the effect re-requested on each, with no
cancellation, and a superseded request could still write:

- **after the alert ended** — a poll saying the alert was over cleared the scores, and a request the
  previous poll had made, landing after that, wrote the ended alert's stars straight back into every
  live reader: the rating's live fallback, the aurora medallions, the overlay popup and the "🏆 best
  location" card, until the next poll or focus;
- **out of order** — two refreshes close together each made a request, and the older one landing
  last replaced the newer answer with its own.

The effect now carries the cancellation half of the fetch shape #814 gave the stored-results fetch
beside it: a `cancelled` flag set in the cleanup drops any response whose status has been superseded.
"Latest" means the latest status *published* — the provider does not order its own responses, a
separate and pre-existing race named below.

⚠️ **Only that half, on purpose — no clear before the request, and a failure writes nothing.** The
stored-results fetch clears on every change because it answers for a night the reader selected. This
is the backend's live cache, and nothing on the status marks a change in it that a clear could key
on: `detectedAt` moves on an escalation while the backend still holds the pre-escalation list (it
re-scores after the NOTIFY), so a clear keyed on it would blank the map and redraw the same list; the
night can roll while the backend holds last night's list, so a clear keyed on the night gains
nothing; and a clear on every re-poll would blank every reader of the live scores for a round trip
every five minutes. Any clear would also turn a place kept on the map by tonight's stored result into
a denial in the overlay's popup, which reads a missing live score as "Not suitable for aurora
photography" — the false negative #814 refused. The adversarial review challenged the decision twice,
proposing clears keyed on `detectedAt` and on the night; a refuter traced both through the backend and
the decision stood. Both halves of it are pinned by their own tests, so a tidy-up that "completes" the
shape fails loudly.

**Residuals, named so they read as known rather than overlooked:**

- A device that missed the gap between two alerts — asleep, offline, a throttled tab — and whose
  first refetch after it then fails, shows the earlier alert's answer until the next poll or focus.
- When the newer of two overlapping requests fails and the older succeeded, the older answer is still
  dropped, and what was on screen before both stays until the next poll or focus.
- The commit that brings the alert-ended status still renders the ended alert's stars once, before the
  effect clears them — the same one-commit window the per-night fetches have.
- On an alert's first request nothing is held yet, and the overlay's popup reads that as "Not suitable
  for aurora photography" until the answer lands. The popup has no "not known yet" state, which is a
  change to `MarkerPopupContent`, not to this fetch. Pre-existing.
- `AuroraStatusProvider` can publish statuses out of order: a poll and a focus in flight together land
  in either order, and whichever lands last is published. Pre-existing, one level up.

Pinned in a new `MapViewAuroraLiveFetch.test.jsx`, which drives the status through the real
`AuroraStatusProvider` and a window `focus` — the production trigger. The sibling files' ref-backed
status stub is read only when the memoised `MapView` happens to re-render for some other reason, so a
"re-poll" through it is whatever render the harness causes, not the route production uses. Both race
tests failed against the unfixed effect, each at its final negative. Each part was then mutated
alone: deleting the `.then` guard, or `cancelled = true` from the cleanup, fails the two race tests;
adding a clear before the request fails the two decision tests; clearing in the `.catch` fails only
the failed-refresh test. ⚠️ **The late requests settle inside an *awaited* `act`, and the `await` is
load-bearing — measured:** with the guard deleted, an un-awaited `act` — plain callback or async —
lets the alert-ended test pass, while `await act(() => …)` fails it.

Also corrects `utils/mapEvents.js`, whose note still said the live cache could answer for a night the
reader was not looking at — #814 closed that in `MapView` — and has the astro fetch's comment name the
stored-aurora fetch it refers to, since the live fetch above both now takes a different shape.
