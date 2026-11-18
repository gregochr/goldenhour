### Added — Met Office warnings in the Coming up feed (W3b)

Coming up gains an entry for each Met Office weather warning that reaches past the four scored days (Plan and At a Glance
already show the nearer ones), with its true span, the official level colour and level word, an "Updated" flag, a
"Raised from Amber" note while a level rise is fresh, the headline, the validity in UK time, a door to the plan where the
warning touches the scored days, and the Met Office attribution and link. An upgraded warning sorts first on its day. The
entries are merged on every request after the day-cached feed, for every role, from the stored warnings against the
server clock (so Rewind shows what had been received, and a simulated warning is never shown to anyone). The page checks each
entry against the warnings it polls every two minutes and refetches the feed when that set changes, so a cancelled or
expired warning leaves inside the Met Office licence's ten minutes; while the warnings feed is stale the pane says so
and never reads as an all-clear. A warning never changes a rating. Off by default (`photocast.warnings.enabled`): with
the flag off, or no warning in force, the feed and its ETag are byte-identical to before.
