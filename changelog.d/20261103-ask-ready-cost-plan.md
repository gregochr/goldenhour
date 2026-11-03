### Docs — plan: cut the cost of Ask ready answers

Adds `docs/engineering/ask-ready-cost-plan.md` and `cost-queries/ask-ready-cache.sql`. A measured run shows
the shared prompt is never cached (each first turn is ~3,600 tokens, under Haiku 4.5's 4,096 minimum) and
every task opens with the same tool call. The plan ranks four levers: run Ready answers on Haiku 5.5 after
a quality check (about 90% cheaper), answer the opening tool call in the prompt (~25–30%), fix the cache,
and reuse still-fresh answers. Planning only; nothing is built yet.
