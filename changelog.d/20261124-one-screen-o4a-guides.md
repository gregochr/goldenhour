### Changed — the help guides describe one screen, not tabs

The user guides (`docs/engineering/one-screen-plan.md`, phase O4a) now describe PhotoCast as one screen: This week, with
Further ahead under it, and the map as a full-screen view over both.

- `getting-started.html` is the workflow: when (Further ahead), which morning (This week), where (the map), go (the
  callout and Leave by).
- `planning.html`, "Planning a shoot", replaces `at-a-glance.html` and `coming-up.html` and carries every section id the two
  had, plus `#this-week`, `#further-ahead`, `#your-places` and `#watch-a-date` (watched dates: the toggle, the chips,
  stopping, the 25-place-and-date limit, the 14-day expiry, no NLC season). The two old addresses stay as meta-refresh
  stubs (a small `moved.js` keeps the old `#section` and `?embed=1`), because links to them exist in the wild.
- `map.html` is rewritten for the map view: how it opens and closes, its own `#map/...` address, Back, the header's window
  control, the callout, "How was it?" and the phone layout.
- `your-places.html` gains watched dates; `finding-places.html`, `settings.html`, `ask-photocast.html`, `night-sky.html`,
  `reading-the-forecast.html`, `weather-warnings.html` and the hub lose "tab", "Coming up", "At a Glance" and the ⚙ button.
- `utils/guide.js`: `GUIDE_PAGES` drops `at-a-glance` and `coming-up` for `planning`; `TAB_GUIDE` is now `SCREEN_GUIDE`
  (`plan` and `map`); `GUIDE_SECTIONS` points at `planning`, and its `comingUp*` keys are `furtherAhead*`.
  `guideSections.test.js` also checks that every internal guide link and anchor resolves.
- New screenshots taken from a local build (no tab bar): the top of the screen, the days ahead, the warning lane, the
  Your places row, the menu, Further ahead's list and filters, the map view, its window control, Ask beside the map, and
  a phone screen and map. The old tab-bar shots (`glance-top`, `coming-up-december`, `coming-up-filters`) are deleted.
- Review round 1: the Ask shot is re-taken with the question in the box and no answer; the old aurora, astro and hot-topics shots (which showed a real home marker) are removed or re-taken, and the tide panel shot is cropped to the panel; home framing, toast text, rating floors, watched-date chips, the stub redirects (3 s meta refresh so the script keeps the section and `?embed=1`) and a `moved.js` test are tightened.

**Deploy order.** The landing site must go out first (the owner runs `release-landing.sh`), before any app build whose
in-app guide links open `planning.html`; until then those links 404.
