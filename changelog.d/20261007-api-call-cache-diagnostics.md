### Added — Anthropic prompt-cache diagnostics on every logged call

Every Claude call written to `api_call_log` now keeps the response's `diagnostics` beside its token
counts (V168, `cache_diagnostics TEXT`, compact JSON, null when the response carried none), so a
batch cache miss can be explained per request instead of inferred from `cache_read_input_tokens`
dropping to zero. The batch result processor, the synchronous engine, the glosses, the best-bet
advisor and the Ask engine all pass it through; the token and cost maths are untouched. Operations →
Job Runs shows a "Prompt-cache diagnostics" tally and a per-call badge for any run that has them.

The API only reports a diagnostic for a request that names a previous message to compare with, so
the request side is a switch, off by default (`photocast.batch.cache-primer.diagnostics`): when on,
the cache primer opts in and every warmed sky batch request names its primer as the previous message.
Whatever the switch says, the primer now reads its own result once and its per-cycle INFO line gains
what it wrote to the cache (total and one-hour tokens), what it read and its diagnostics. CLAUDE.md's
*Batch cache primer* bullet carries the query that answers the 2026-10-04 read-versus-write question.
