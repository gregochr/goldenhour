### Added — far-cloud P1: replay of rated forecast-deck-under-canopy analogues (plan §20)

- `scripts/far-cloud-p1/canopyreplay.py` selects the stored prompts of rated slots with a forecast low-cloud deck under a
  forecast mid/high canopy (the slots the §19 rule would release have no stored prompt, so they cannot be replayed) and
  reports a replay of them against ERA5. One Sonnet 5.5 batch of 428 prompts was run (about $1.2); plan §20 records the
  counts. Measurement only; production scoring is unchanged.
