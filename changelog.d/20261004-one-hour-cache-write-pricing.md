### Fixed — 1-hour prompt-cache writes are priced at the 1-hour rate

Since the batch cache primer, warmed scheduled SKY requests carry a 1-hour cache lifetime, billed at
2x the input rate rather than the 1.25x of a 5-minute write, but `CostCalculator` priced every
cache-creation token at the 5-minute rate, under-recording those writes. `TokenUsage` now carries
the 1-hour portion (`cacheCreationOneHourTokens`, a subset of the total, clamped to it) read from
the API's `usage.cache_creation.ephemeral_1h_input_tokens` at every site that builds one from an SDK
response, and `CostProperties` gains per-tier `*-cache-write-1h-usd-per-mtok` rates (Haiku 2.00,
Sonnet 6.00, Opus 10.00). The 1-hour portion is priced at that rate and the rest at the existing
rate; a usage with no 1-hour portion costs exactly what it did. No migration: only
`cost_micro_dollars` on new rows changes.
