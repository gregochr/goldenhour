### Added — Map tab: windows that have gone are rows of the window control, tagged Past

The forecast list has served the last two days for a while, and the Map tab hid every one of them:
the briefing withdraws a window once its sunrise or sunset is over, and the tab's own list only kept
windows from today forward. A solar window the briefing no longer serves, that has forecast rows and
whose earliest event time has passed (the same instant and thirty minutes the briefing retires it
on, so the Map and the Plan tab retire a window at the same minute) is now a row: today's elapsed
sunrise, yesterday's two windows and the day before's. The pill wears a `PAST` tag where a verdict
word would sit, labelled `Today`, `Yesterday` or `Fri 16`, with the window's time narrowed to the
places in your scope like any other. Stepping onto one forwards it to `App` with a provenance flag
(`selectedDateIsPast`, beside the night one), because the map-date clamp refuses a date before today
without it; a stale past pick without the flag still falls through to today.

On a past window the chips, pins and the callout's star read the forecast row alone, never the
briefing score index (which serves the latest result and, for a passed slot, may be one that landed
after the event). The callout hides Drive and Leave by, keeps Dark sky and withholds "Open in Plan"
(the location sheet it opens holds only upcoming windows); the collapsed "Other windows" summary
never names a window that has gone. Nothing is rated or recorded yet and the window menu has no
Earlier group: those, and the older-day fetch, are the next phases of the outcome-rating series
(`docs/engineering/outcome-rating-plan.md`).
