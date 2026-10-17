### Docs — same-cell rating spread: measurement and guard options

`scripts/measurements/same-cell-rating-spread/` measures how often the batch pipeline gives places
that see the same sky stars two or more apart in the same run, by model and horizon, over
production's `forecast_evaluation` (read-only SQL, run over ssh; results of 2026-10-16 in its
`results.md`). Haiku 4.5 disagrees on 3–8% of inland same-cell groups and 6–11% of near-twin pairs;
Sonnet 5.5 on about 0.3%. `docs/engineering/same-cell-rating-spread-plan.md` costs three responses
(the FAR bucket on Sonnet 5.5 at about $0.36 a day more, a serve-time neighbour guard, one prompt per
grid cell) and recommends the first. No prompt or pipeline behaviour changed.
