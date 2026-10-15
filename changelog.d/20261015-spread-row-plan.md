### Docs — Plan card Spread row: the pool label and visible counts, port plan

`docs/engineering/spread-row-plan.md` is the port plan for the owner's Spread-row design bundle
(vendored verbatim at `docs/design/spread-row/`): a second label line naming the pool the
histogram was counted over (`(within 45m)` / `(within 1h30)` / `(within 2h30)` / `(any drive)`,
derived from the reach tier's own threshold, silent when no drive time was measured) and a count
printed under every bar, the hatched unrated bar's included, with one clamped column width per
card and a compact `1.2k` format. Three phases (S1 helpers, S2 the row, S3 docs); the dark well,
the sixth hatched bar and the minimum-sample gate the prototype lacks are all kept, and the
card's spoken sentence gains the non-zero band counts so the visible figures have an accessible
equivalent. No code in this change.
