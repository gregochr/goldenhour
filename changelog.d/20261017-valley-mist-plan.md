### Docs — valley mist plan: the morning PhotoCast cannot see

`docs/engineering/valley-mist-plan.md` records why a riverside radiation-mist sunrise under a clear sky is
stood down by weather triage's 5 km visibility rule before Claude ever sees it, and why no surface names
it the night before: mist is modelled from above (the inversion calculator), from inside a wood (the canopy
lane) and as a haze modifier in the sky prompt, never as a subject from beside it. The plan proposes a
`mist_prone` location attribute, a `ValleyMistCalculator` recorded on `slot_atmosphere` with a `MIST` hot
topic and Coming up condition (M1, no Claude cost), a triage routing exemption plus a prompt block and
Claude echo for flagged sites (M2), a `seen` tag on the outcome rating and an ERA5 saturation proxy for
verification (M3), and a UKV 2 km side-fetch only if the measurement phase justifies it (M4). The
measurement SQL is `docs/engineering/valley-mist-phase0.sql`. Six owner decisions are listed; nothing is
built.
