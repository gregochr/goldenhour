### Docs — map peek sheet series complete

`docs/engineering/map-mobile-sheet-plan.md`'s M6 sweep closes out the phone peek-sheet series
(M1–M6, all six phases now landed): §1's line-number citations re-verified against the tree
(`MapView.jsx` grew to 6,729 lines) and corrected, with the handful that had been semantically
overtaken by M1/M2/M3/M5 themselves flagged rather than silently renumbered; the §0 phase log gains
a measured M6 row (collapsed sheet 74px exactly, open 356px clamped to `calc(100% - 64px)`, the
map's own visible height above the collapsed sheet measured 591px against the spec's ~440px
estimate) at both 390×844 and 1280×900, plus a tablet spot-check; the §0 table's `commit` column
now carries every phase's PR (M1 #924, M2 #927, M4 #925/#929, M3 #928, M5 #930); §6's six owner
questions answered against what shipped; M3's one-off `outerHTML` golden-string pin deleted from
`MapTideStrip.test.jsx` now that the split it proved has stood. CLAUDE.md gains a new **Map tab on
a phone — the peek sheet** bullet, the Backend-heavy bullet's three new client reads join the
existing filter/map/select licence rather than opening an eighth numbered class, the stale "Phone
layout moves Regions/Heat-Pins/Filters into a bottom bar" and tide-strip-phone-mount sentences are
corrected, and a stale API-doc parenthetical on `PUT /api/user/settings/map-tide-mode` (still
claiming "no frontend caller" after M5 wired it up) is fixed. `map-tab-v2-plan.md`, `tide-window-
plan.md` and `map-landing-plan.md` each gain a short cross-reference note recording what this
series retired or superseded in their own territory. No user-visible behaviour change.
