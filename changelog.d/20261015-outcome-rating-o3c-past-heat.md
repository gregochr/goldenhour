### Added — Map tab: the heat field paints a window that has gone

A past window on the Map tab (today's elapsed sunrise, yesterday's and the day before's) used to be
a row with no picture behind it: the field is keyed on the briefing's rendered windows, and the
briefing withdraws a window once it is over, so the Heat view was empty and the Heat|Pins toggle had
nothing to toggle. The Map pane now builds a field for each past window from the locations' own
forecast rows and hands it to the map beside the briefing's. A past window shows the same heat
field, colour key and rated counts as a served one; a window nobody rated paints nothing and drops
the key, as a served one does.

The field reads the forecast row alone, the same rating the chips and the callout star already show
on a past window, never the scores endpoint (which serves the latest result and, for a passed slot,
may be one that landed after the event). The key is drawn only when a place the field actually paints
holds a rating: a past window whose only rated rows are a wood's or an unregioned place's has no key
over an empty field. The kernel's haze reads the inferred confidence (medium), since a past window
carries no served confidence. The Plan matrix is untouched: past windows are added in the Map pane,
never in the briefing context the Plan tab reads.
