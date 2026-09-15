### Fixed — the Map's "Couldn’t load" announcement waits for the reader to be looking at the page

The status region #848 added to the Map tab announces "Couldn’t load — trying again" to a
screen-reader user when the line appears. It has to be empty whenever the reader cannot perceive
it, and fill — a change, which is what a live region announces — when they can. #848 gated it on the
app's own tab panel; Codex's post-merge review found the layer above that, and this change's own
review the one beside it. With the whole browser tab in the background, or with another app in front
of a still-visible window (side by side, a second monitor), the Map panel keeps its box, so the pane
still counted as on screen: a night's request that failed then filled the region while the screen
reader was presenting something else, and the reader's return found it already full, announced to
nobody. That return fires `focus` for the window case, not `visibilitychange`, so it could not even
be noticed.

- `WindowFirstMapPane` now counts itself on screen only while its panel is shown AND the page is in
  front of the reader — the document visible and the window focused — and hands `MapView` the two
  together as `paneVisible`. A failure while the reader is elsewhere leaves the region empty and
  fills it on the return, which is announced.
- The page's state is a `useSyncExternalStore` on `visibilitychange`, `focus` and `blur`, not a
  value read at render plus a listener added afterwards: the pane is `lazy()` behind a Suspense
  fallback whose commit React can hold back, and a change in that gap reached no listener. The store
  reads the page again once it has subscribed. A map mounted with the reader elsewhere starts off
  screen.

The layers that can hide the region, listed rather than left for review to find one at a time: the
callout (the region's first home, mounted by the selection — fixed in #848), the app's tab panel
(#848), the document and the window's focus (here). Not addressed, and stated rather than implied:

- **A modal dialog over the Map tab** — the four-day sheet opened as a peek from the callout, or
  search, or settings, each `aria-modal="true"`, over a pane that is not `inert` behind them (O-20).
  WebKit is understood to drop everything outside a visible `aria-modal` dialog from the
  accessibility tree, so in Safari with VoiceOver a failure while the sheet is open would fill the
  region out of the screen reader's reach, and closing the sheet would reveal it already full —
  unverified here, and other engines may simply announce it over the sheet. Gating on it needs a
  live "a modal is over the pane" signal, which the pane has no route to (its `sheetSpot`
  plumbing is the residual its own docs already name). If O-20's shell-root `inert` is ever adopted
  (`docs/engineering/o20-shell-inert-plan.md`), this becomes a hiding layer in every browser, and
  will need that signal.
- **A return that races its own early re-ask.** Coming back sends a waiting retry at once, in the
  same moment the region fills, so the reader hears "Couldn’t load — trying again" while that
  request is in flight; if it succeeds a moment later, the rating that replaces the line is not
  announced — no rating is. The line is true when it is spoken (the last request failed and it is
  being asked again), and holding it through a re-ask is #848's deliberate rule; delaying the
  announcement until the re-ask settles would need timing this does not add.
- **The browser's own chrome.** A trip to the address bar blurs the window, so a failure still on
  screen is announced again on the way back. Accepted as the cost of the focus layer.

Pinned by nine tests in `WindowFirstMapPane.test.jsx`, grouped as their own block: each layer alone —
the panel (#848's test, moved here), the document, the window's focus — and each against the
others, since one flag that every layer wrote to, the last write winning, passed every single-layer
test (the panel hidden while the page comes and goes; the page away while the box changes); a mount
with the reader elsewhere, both ways; the page read again after subscribing; and every listener
removed with the arguments it was added with. Page state is faked through spies on jsdom's own
getters, `visibilityState` and `hidden` together. Nine mutants were run one at a time against the
new lines — each half of `paneVisible` and of the page test dropped, each subscription removed, one
removal left out, and the first cut's read-once-then-listen shape in place of the store — and all
nine are killed, each by the test written for it (the last by the re-read test alone), none by a
timeout. Two lenses reviewed the first cut, runtime and test quality; every charge is fixed above or
stated as a limit. `MapView` is unchanged but for its
`statusLine` and `paneVisible` docs. Tested, not seen in a browser: the Map tab sits behind sign-in,
and no screen reader was run.
