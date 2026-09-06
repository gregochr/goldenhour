### Fixed — a window that has already happened no longer carries a verdict

The map's window list carries a filler row for any forecast date the briefing served no window for,
and its only gate is that the date is not yesterday's. But the briefing withdraws a window once its
event has passed — so every morning after sunrise the list led with a filler for a window hours in
the past, and the pill coloured and tinted it from the region data still sitting in the wider payload.
Opening its drilldown showed served region verdicts above locations counted as zero.

The verdict now follows whether the briefing actually served that window, which is the rule the
window list's own code had already written down for its callers.
