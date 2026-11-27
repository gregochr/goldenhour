### Fixed — a fogbound valley-mist morning is capped at 2★

A fogbound valley-floor sunrise (fog under 300 m with no clearing in the window) could still be rated 4★
or 5★, because the 2★ cap sat in prose beside a "may rate 4–5" permission and the model overrode it. A
FOGBOUND site now gets its own site sentence and a "no clearing in the window" line in place of the generic
ones, and the cap is restated on the output schema's `rating` and `mist_potential` descriptions. The
LIKELY, POSSIBLE and NONE messages are unchanged, and so is the system prompt. There are no extra Claude
calls; the schema text change re-warms the batch cache once. The ValleyMist prompt-regression step now runs
on the same model as the sky step instead of silently defaulting to Haiku.
