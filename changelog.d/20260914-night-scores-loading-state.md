### Fixed — the map callout says "Loading…" while a night's own scores load, never "Not scored yet"

The follow-up the astro night-fetch race fix named, for its own path and for the same gap #814
left on the aurora side. Those fixes drew nothing for a night on every night step until its own
request answered, so one night's stars could no longer stand in for another's — and for that round
trip the callout said "Not scored yet" in the definitive voice. A null rating reads "Loading…" only
while the flag it is handed is false, and it was handed `scoresKnown`, the SOLAR scores fetch's
flag, true on every night step once the solar scores had landed. It could sit directly above a strip
cell already showing that night's star, because the strip reads the window control's preview.

- **A night-aware flag.** `MapView` derives `ratingKnown` beside the rating accessor: the solar flag
  for a sunrise or sunset and, for a night, whether that night's own request has answered —
  `nightDate`, never `date`, since the two part company whenever the window control keeps a night
  row local. A night's answer is set by a successful response and by nothing else: not by a preview
  row, and not by a failure, which is not evidence that nothing was rated.
- **The preview's rows in the meantime, derived at render.** Each night kind now holds its last
  answer tagged with the night it answers for, and every reader draws `nightScoresFor`: that answer
  when it names the night on screen, otherwise the window control's preview rows for that night,
  otherwise nothing. So the pins, the labels, the counts, the astro field and the callout keep
  drawing a night through its round trip; a preview that lands after the step, or after the night's
  own request has failed, fills it in; stepping back to a night whose answer is still held shows it
  at once while the request goes out again; and no frame is left in which the old night's rows
  answer for the new one. The preview is never a dependency of the fetch, so its arrival re-renders
  the night and never re-requests it. A failed request writes nothing: it is asked again instead
  (below).
- **The strip.** A night cell asks whether its own night's preview has answered
  (`pendingNightRowIds`, built from the same list of nights the preview fetches), never the solar
  flag. The note there claimed that flag could only err towards "…"; with the solar scores in and a
  preview in flight, the cell printed "—". The cell for the window on screen now restates the
  headline, so the card cannot print two answers for one place and one window. Strip cells also
  carry `data-ev-id`, as the window control's rows do.
- **The preview's own failures.** Each run is merged into what the preview already had: a night
  whose request fails keeps its earlier rows, and one that never answered stays out of the map rather
  than posing as `[]`, the endpoint's own "nothing is rated".
- **A failed night request is asked again** (`askNightUntilAnswered`, which both single-night
  fetches share): 2s, 10s and a minute after the first three failures, then every ten minutes — the
  same interval as the briefing's poll, though on its own timer — until it answers or the night
  changes, including while the Map tab is hidden (its pane is never unmounted); and at once,
  whenever a retry is waiting and the reader comes back to the page (window focus, or the tab
  becoming visible), the way `createEventSource` reconnects. The retry timer is armed after an
  await, which is safe here only because the effect's cleanup stops it first or clears it after —
  `SchedulerView`'s timer fix, #818, records the version of that timer that leaked (the hole #809
  closed in `ModelSelectionView`). The frozen Plan-tab overlay still asks once, as it always has.

⚠️ **Not `useEffectEvent`, which was the first cut.** In react-dom 19.2.8 an Effect Event's
implementation is swapped in during the commit only for a plain function-component fiber, and
`React.memo(MapView)` renders as a simple-memo fiber, so the event kept its mount-time closure and
never saw a preview at all. The tests caught it; the derived shape needs neither it nor the ref that
briefly replaced it.

Not addressed, and stated rather than implied:

- While a night with no preview rows waits on its own request — in flight, or failed and waiting to
  be asked again — Heat view's "This event is not scored yet" (astro only) sits beside the callout's
  "Loading…", because that line reads the empty field; holding it back needs a third, loading state
  for the colour key it toggles against.
- Through a long outage the callout keeps saying "Loading…" between the ten-minute asks, when
  nothing is in flight. A failure wording of its own ("couldn't load — trying again") would say what
  is true; it is a product decision, and not made here.
- A night the preview never asks about — outside its solar horizon, which includes every past night
  and, after midnight, the night still in progress — reads "—" in the strip unless it is the window
  on screen. Nothing is loading for it, so "…" would be the false claim, but "—" still claims more
  than anything here knows.
- The window control's own `N★ best` and the Regions jump list read "—" for a night whose preview is
  still in flight, or has failed with no earlier rows to keep, where the strip now reads "…".
- On the aurora night in progress, while the live-state fetch itself is in flight, a place the
  stored run did not rate reads "Not scored yet" a moment early: the flag asks the stored request
  alone.
- The frozen Plan-tab overlay fetches no preview, so a night there still draws nothing through its
  round trip, and its popups meanwhile still say "No astro conditions data for this date" or "Not
  suitable for aurora photography" — unchanged.

Pinned by `MapViewNightScoresLoading.test.jsx` — 78 tests through a real `MapView` and the real
callout, every shared rule run for astro and for aurora off one table, the retry walked on fake
timers to the millisecond — and by fifteen new or rewritten cases in `MapCallout.test.jsx`, which
goes from 55 tests to 64. Fifty-three mutants were run one at a time — the derivation's three rules
and its night key, the late-answer guard and the failure path, the retry's arming, stop and
schedule (a cap, and a failure count outliving its night, among them), the early re-ask on return
and its listeners, both overlay gates, the fetch-dependency rule, every arm of `ratingKnown` (a
`date`-for-`nightDate` swap and a live-state shortcut among them), the pending set's scope, the
preview's merge and failure handling, the nameless-row guard and the callout's six rules — and all
fifty-three are killed. Six adversarial review lenses ran on the first cut, and three more on the
retry; their charges are fixed here or stated above, and the pull request lists the rest. Tested,
not seen in a browser: the Map tab sits behind sign-in.
