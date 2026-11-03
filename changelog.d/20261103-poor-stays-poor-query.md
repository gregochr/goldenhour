### Docs — a poor sunrise does turn good by the evening refresh, so it keeps re-rating them

Adds `docs/engineering/cost-queries/poor-stays-poor.sql`, a read-only production query that compares
each 21:00 evening sunrise rating with the rating it replaced. Run on 3 November 2026 over 14 evening
nights, it showed that 6.6% of sunrises already rated 2★ or lower came back at 4★ or higher, so a gate
that skipped them (saving about $5 a month) would have hidden real good mornings. The Claude cost ledger
records the result as Phase 2b, rejected.
