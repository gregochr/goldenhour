### Added — sub-regions: the server can now hold back a Lite reader's ratings outside their picks (switched off)

A new `LitePickShaper` shapes the briefing, its digest, the evaluation scores, the forecast list, the forecast
detail and the astro conditions for a reader who is not Pro or Admin. With `photocast.sub-regions.enforce` on,
every rating of a location outside the sub-regions the reader picked is removed and the place is marked
`gated`: the slot's verdict, stars, display verdict, stand-down reason, flags and neighbour mark; the region's
best, mean and mean-change stars and each window's best star; the bluebell topic's per-place scores, "Best"
badge, best score, conditions chips and summary; the astro stars and prose; and the forecast rating, stand-down
reason and bluebell summary of a list row or an id (an id outside the picks is served the stripped detail, never
the full one). Region and window verdicts, labels and prose, hot topics and the weather and tide facts stay
free. The shaped briefing also carries `allowedLocationIds`. Anything that is not positively Pro or Admin is
shaped, and a failed read of the picks shows no ratings rather than all of them. A shaped response is sent
`Cache-Control: no-store` with no ETag, because it reveals the picks.

The flag is off in every committed config, so nothing changes for any reader yet: Pro, Admin and flag-off bodies
carry none of the new fields and keep their ETags. The client does not yet draw a gated place; that is a later
phase.
