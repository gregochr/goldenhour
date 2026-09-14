### Fixed — the map says "Couldn’t load — trying again" once a night's own request has failed

The gap the night-scores loading-state entry left open, and named: through a long outage the map
went on saying "Loading…" between the ten-minute asks, when nothing was in flight. A failed astro or
aurora night request is asked again (2s, 10s, a minute, then every ten minutes, and at once when the
reader comes back to the page), and from its first failure the tab now says "Couldn’t load — trying
again". "Loading…" claims a load under way; "trying again" claims only that the asking goes on — a
retry waiting or one in flight — which is true for as long as the night is on screen, so the line
does not flicker back to "Loading…" as each retry goes out.

- **Where it shows.** The callout's null-rating headline on an astro or aurora night; and Heat
  view's key slot on an astro night with nothing to draw, which said "This event is not scored yet"
  — a claim about the forecast that a failed request is no evidence for — beside the callout's
  failure line, the two contradicting each other on one screen. Both print one shared string.
- **Whose failure, and for how long.** It belongs to the night on screen, for this visit: a failure
  that arms a retry records it against that night, and leaving the night takes it back, so a
  return reads "Loading…" while the fresh request goes, until that fails in turn. The first cut
  kept it until the night's own next answer, which was false twice over: an answer that lands after
  the reader has left is dropped like any late response, so a night whose last request loaded read
  "Couldn’t load" on the return; and another night's failure overwrote it anyway. The record is
  tagged with its night because a step renders the new night before the old one's cleanup runs, and
  on a step React does not flush synchronously that frame is painted.
- **What outranks it.** A rating — the night's answer, the window control's preview rows, the live
  aurora state — shows as before; so does a night's answer still in hand through a failed refresh,
  "Not scored yet" included, because a failure takes nothing away. The strip's cell for the window
  on screen reads "…" for both still-to-come lines, never "—".
- **Heard, not only seen.** A status region in the callout announces the failure — and only the
  failure, so stepping between windows does not chatter. It is always mounted, since a live region
  announces a change and has to exist before the sentence arrives.
- **The card follows its headline.** The callout re-measures and re-places itself when its headline
  changes in place — a night's answer landing, or its request failing — not only on a new window or
  selection. The failure line (≈207px) can never share the verdict row with the kind chip, so it
  always adds a line, ≈24px, and a card placed above its point grew down over the ring and the dot,
  or one clamped to the band's floor into the chrome below, until an unrelated pan or zoom.
  "Loading…" → "Not scored yet" could already do the same.
- **Enlarged text wraps.** The unscored headline lines may now wrap (the rated pill still may not).
  Under text enlarged on its own — a minimum font size, or Zoom Text Only — the unwrappable line
  overran the phone card's 220px row from about 111% and was clipped.
- The words are the ones asked for, set with the typographic apostrophe the Map tab's other copy
  uses. Also corrects a `MapView` comment that still said `useEffectEvent` cannot work in a memo
  component: react-dom 19.3.0 (#832) swaps an Effect Event in for memo and forwardRef fibers too.

Not addressed, and stated rather than implied:

- While an astro night's FIRST request is in flight with no preview rows to draw, Heat view's key
  slot still says "This event is not scored yet" beside the callout's "Loading…" — the
  loading-state entry's limit, unchanged: holding it back needs a third, loading state for the
  colour key.
- A sunrise or sunset whose scores fetch fails still reads "Loading…" until the briefing's next poll
  answers: that fetch exposes no failure (its `.catch` keeps what is on screen), so there is nothing
  to say one with. Giving it one is a change to the briefing context.
- The frozen Plan-tab overlay asks once and never again, so it has nothing to be "trying"; it
  renders no callout, and its popups are unchanged.
- The widths above are reasoned from the stylesheet (IBM Plex Mono at 11.5px, a 0.6em advance) and
  not measured in a browser: the Map tab sits behind sign-in.

Pinned by twenty new tests in `MapViewNightScoresLoading.test.jsx` (78 → 98), through a real
`MapView` and the real callout, every shared rule run for astro and for aurora: the line walked
through the backoff into the ten-minute beat on fake timers, and the three rules that live only in
the frame before a cleanup runs — whose night the record speaks for, a left night's late failure,
and a night's record on a sunrise or sunset — read commit by commit through a `React.Profiler`,
since `act` has run the cleanup by the time it returns. Fourteen more in `MapCallout.test.jsx`
(64 → 78), the precedence, the status region and the re-measure among them; two in
`MapViewHeat.test.jsx` for the key slot; two in a new `mapCalloutVerdictWrap.test.js` for the
stylesheet. Four existing assertions that read "Loading…" straight after a failure now read the new
line. Sixty-six mutants were run one at a time: every new rule; the four gaps a reviewer found by
reasoning — a record keyed to `date`, the live aurora night, drawn rows hiding a failure — which
survived the first cut and are killed now; and the 27 older mutants on code this touched. Sixty-five
are killed, each by the test written for it and none by a timeout. The survivor keeps a repeating
failure's record the same object; without it each failed retry re-renders the map once more, which
nothing on screen shows. Three read-only review lenses ran on the first cut — runtime, test quality,
and copy, accessibility and docs — and every charge is fixed above or answered: a copy lens asked for
other words ("will retry", or naming what failed), and the ones asked for stand, with the claims
made about them now exact. Tested, not seen in a browser: the Map tab sits behind sign-in.
