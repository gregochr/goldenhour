### Added — a wildlife hide's hourly comfort forecast on the Map tab, the phone and the four-day sheet

A pure wildlife hide is never scored for sky colour, so its callout on the Map tab said "Not scored
yet" for a place that never will be, and nothing showed its hourly comfort forecast anywhere but the
Plan-tab map overlay's popup. The callout now says "Comfort only" and "Wildlife hide: never scored
for sky colour", with a short summary of the selected window's day (temperature and feels-like
range, the strongest wind and the highest rain chance each with the hour it occurs, the daylight
hours covered), read straight off the served hourly rows. A day with no rows says "No hourly
forecast for this day yet." The summary is the way into the four-day sheet, which for a hide now
shows the full hourly table for every day in the served window in place of six empty window rows.
The phone shows the same callout, since the phone's peek sheet holds no selected-place facts. The
table is one shared component, a real table with column headers, used by the overlay popup and the
sheet; a wind speed with no direction no longer prints "undefined". The rows are visible to every
role, as the API already served them to LITE.

Also: `isWildlifeOnly` is now the one client definition of a pure hide (it was written out in
`MapView` three times and once in `PromptTestView`); the dead waterfall comfort branch in the
marker popup is removed (no waterfall has ever had hourly rows); and a date held up by hourly rows
alone no longer reaches the Map pane's date domain, where it would have drawn a Sunrise/Sunset
window nothing rates.
