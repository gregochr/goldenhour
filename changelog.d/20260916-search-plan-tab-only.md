### Fixed — search opens from the Plan tab only, so a pick can no longer open a Plan dialog over Coming up

On Coming up and Operations the masthead still offered two ways into search: the ⌕, and the origin
button beside it, which opens search too. Search finds only Plan things (the six windows, regions to
plan from, places), and its picks act on the Plan. A window picked on Coming up opened the window
popup over the almanac feed, and a place opened the four-day sheet there, with the tab left on Coming
up. That is the state the shell's tab switch closes every one of its dialogs to prevent. The `/`
shortcut had refused off the Plan tab since M3, and the ⌕ draws `/` as its keyboard hint, so on Coming
up the button advertised a key that did nothing there. Reproduced in jsdom through both buttons.

**Two ways to fix it, and why this one.**

- **Search on the Plan tab only** (this change): withhold both buttons on every other tab, as the Map
  tab already did.
- **Search from any tab, with every pick moving to Plan.** Rejected. The ⌕'s `/` hint would still name
  a key that does nothing off Plan, unless that recorded decision were reversed too. The box would
  search nothing on the tab it opens over. And each kind of pick would become a tab change with focus
  handling of its own. The Map tab's design already calls choosing an origin a Plan question, and the
  Coming up design draws no search in its masthead.

The cost is one press: a reader on Coming up selects Plan before searching.

**The fix.** The shell hands the tick line its search handler on the Plan tab only. With no handler
the tick line withholds both search controls rather than drawing buttons that do nothing, which
plan-matrix §3 rule 14 bans. The origin becomes the plain statement the Map tab already draws, and the
⌕ and its separator go. The Map's "drive times from here" caption stays on the Map alone, since Coming
up and Operations show no drive time. The Map keeps its own rule as well — `isMapTab` withholds search
whatever a caller hands over — so the Map's decision does not come to depend on the shell's. The
postcode nudge and the away ⌂ stay on every tab: one opens settings and the other moves the origin,
and neither searches. The Plan tab is unchanged.

**Tested, and seen only in a harness.** `WindowFirstShellTabs.test.jsx` drives the real shell across
tabs. It pins the masthead's exact controls on Coming up (at home, away, and with no postcode saved),
on Operations, and on Map with its caption. It checks that pressing the drawn place on Coming up opens
nothing, after a control press on Plan has loaded search's lazy chunk, and that both buttons come back
on Plan. `MastheadTickLine.test.jsx` pins the handler rule, the Map's own rule and the caption rule.
Six of the new shell tests failed before the fix. The signed-in app was not checked, because the tabs
sit behind sign-in. Instead the real shell's masthead for each tab was rendered in jsdom and
screenshotted in headless Chromium with the built stylesheet, at 1280px and 390px. There the tick line
keeps its height on every tab, and the pill looks the same as the Map tab's, without the caption.
