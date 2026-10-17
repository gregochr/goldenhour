### Docs — neighbour guard: the plan

`docs/engineering/neighbour-guard-plan.md` plans a serve-time guard that stops a place from being
named as the single best place (an Ask pick, the BEST BET destination, the Plan card's
best-reachable line) when its sky rating sits at least 2 stars above every place within 5 km in the
same window, and at least two of those places are at a similar height. The star itself stays on the
map and in every mean. Two read-only measurements back the defaults: `guard-tuning.sql` over stored
rows and `serve-guard.sql` over the served cache. At the defaults the rule flagged no place in 18
served windows and none in Sonnet 5.5's 36 runs, while Haiku's FAR bucket raised 6 in 16 runs, the
Caw Gap 4★ among them.
Phases G1a–G4 follow; G1b ships switched off until the Ask and client phases land.
