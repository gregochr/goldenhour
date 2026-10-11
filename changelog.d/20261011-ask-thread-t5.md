### Added — Ask: follow-up questions (the thread) — docs and the regression script

Ask's follow-up questions (the four entries above: the contract and guards, the prompt and validator, "close to home" as the
saved radius with the turn-down rule, and the stacked client) are now written up as one feature in CLAUDE.md and the thread
plan, and the owner's four-question script is a regression case of its own: `AskThreadRegressionTest` drives the real engine
through "anything for sunrise with tide alignment this weekend?", "anything closer to home?", "neither of those take my
fancy, what's the best sunset option?" and "rank those options", asserting what a good answer to each must still be. It runs
only on demand (the `prompt-regression` profile, a real API key, a few pence) and its assertions are pending the owner's
approval; the four entries can be folded under this one at release. Still behind `photocast.ask.enabled`.
