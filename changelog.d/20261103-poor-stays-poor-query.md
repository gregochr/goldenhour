### Docs — measure whether a poor sunrise ever turns good by the evening refresh

Adds `docs/engineering/cost-queries/poor-stays-poor.sql`, a read-only production query that compares
each 21:00 evening sunrise rating with the rating it replaced, and counts what a "skip slots already
rated 2★ or lower" gate would save and what it would hide. The Claude cost ledger records the question
as Phase 2b, with a proposed decision rule, pending the first run.
