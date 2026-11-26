### Changed — an unscored window now says why (backend)

The drive-tier cards (`/api/user/settings/reach-verdicts`) now follow the same rule the served briefing uses when a
region has nothing rated inside the ring. Before, a single stood-down place made a scoped "Poor unsettled" card; now
the same fold the served path runs (a triage majority, every slot accounted for, a tie going to "not scored at this
range") runs over the places in reach, so **fewer drive-tier cards say "unsettled" at T+2 and T+3** (some read "not
scored at this range" instead, and a blank with an unclear record at T+0 to T+2 is plain "not scored"). That is the
served rule applied on both paths, not a regression. Each window, served and scoped, now also carries `coverageReason`
and `fog` (omitted when null) naming why it has no rating, chosen by rule rather than by region name; a window a tier
empties by dropping far, never-attempted regions carries a reason only when they all share the same date-level one.
The card's wording follows in the frontend phase.
