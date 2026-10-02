### Fixed — a run that finished with failures is not shown as a success

A run that finished with some places updated or triaged and some failed (PARTIAL, no run-level reason)
showed the green *Forecast run completed* banner, the panel header "(Complete)", and, in the map
popup's Run Forecast, "Forecast run failed" with no refresh although part of it had been written. They
now agree, through one rule (`completedWithFailures` in `utils/runOutcome.js`): the banner is amber,
*Forecast run completed with failures — N locations updated, T triaged, M failed.* with Refresh; the
panel header reads "(Completed with failures)"; and the popup refreshes once and says "Part of this
forecast failed." The triaged count is shown (and omitted when zero) in this banner and in the
stopped-early banner, so a run in which triage stood many places down no longer reads "0 locations
updated". A clean run stays green; a failed or stopped-early run is unchanged.
