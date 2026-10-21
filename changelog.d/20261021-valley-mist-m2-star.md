### Added — Valley mist: the sky prompt now judges a mist morning, instead of standing it down (M2)

For a place an admin has marked "Mist forms here", a calm, clear, near-saturated morning that the
valley-mist reading calls LIKELY is no longer stood down for low visibility before Claude sees it.
Rain and a blocked solar horizon still stand it down; a POSSIBLE or fogbound reading, and every
unmarked place, keep the 5 km rule exactly as before. Claude now reads a "valley mist conditions"
block for those places (the tightest overnight gap and when it falls, the calmest wind, the sky) and
is told, in one sentence, that thin mist under a clear sky is the shot there and a white frame is not.
Its own reading comes back as `mist_potential`, is stored on the forecast, and shows as "Valley mist
likely" or "Fogbound" on the map popup and the four-day sheet for Pro and Admin readers, the cloud
inversion badge's twin. The general system prompt is untouched, so the batch cache prefix is shared
as before. Where the valley-mist reading was never written for a slot, the hot topic and Coming up now
fall back to Claude's own reading; where it was, the reading always wins.

Cost: one extra Claude call per marked place per LIKELY sunrise or sunset that triage used to refuse. The M0
measurement found at least 145 refused signature mornings in 180 days across the whole roster (a
floor, from the top 60 places only), so a few calls a week on the near-term model once tens of places
are marked; nothing is spent for an unmarked place. Migration V186 adds
`forecast_evaluation.mist_potential` and the `MIST` score type. The valley-mist prompt regression
cases are written but not run; their assertions await the owner.
