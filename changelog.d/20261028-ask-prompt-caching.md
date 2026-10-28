### Changed — Ask caches its tools, static prompt and each turn's tool results

Performance plan P5a (`docs/engineering/performance-review-2026-10-plan.md`). The Ask system prompt is now two
text blocks: the static rules (with the with-user or user-less text) carry an ephemeral `cache_control`
breakpoint, which also caches the tool definitions rendered before it, and the per-question tail (date, scope,
covered windows, context window, thread rules) carries none. Each turn also marks the latest tool-result message
with a rolling breakpoint, so earlier tool results are read from the cache on the next turn. Two breakpoints per
request at most. The prompt text is byte for byte unchanged; prompts below the model's minimum cacheable size
are ignored by the API at no cost.
