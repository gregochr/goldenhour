### Added — Plan headline follows the drive tier

The At a Glance card's verdict word, its mean, the pick (BEST BET / ALSO GOOD), the confidence haze and the `· N rated`
count now describe the places inside the drive tier you chose, not the whole roster. Change the tier from 45 minutes to
an hour and a half and the headline moves with the Spread row beside it. The scope is printed under the word (`within
45m`) and read out in the card's spoken sentence. Under Any nothing changes, and neither does the Map (its pill follows
the tier in the next change).

The server does the arithmetic: `GET /api/user/settings/reach-verdicts` (Pro and Admin) prunes the served briefing to the
places in reach and re-runs the same verdict, ranking and pick code, returning all three tiers in one response, so a tier
tap is instant. `/api/briefing` gains `briefingVersion`, a content hash that lets the page tell its answer was computed
from the briefing it holds. A place with no road route (unroutable) is out of every finite tier, a still-unmeasured one is
kept. When every rated place of the leading region lies beyond the tier, the card says so by naming the region-wide verdict
("Northumberland is Worth it; its rated places are beyond 45 min").
