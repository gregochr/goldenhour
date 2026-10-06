### Added — Ask PhotoCast B4: the typed endpoint and its guards

`POST /api/ask` (every role, 404 while `photocast.ask.enabled` is false) answers a typed question, and `GET /api/user/settings/ask`
(always 200; `enabled:false` and zeros while Ask is off) reports the caller's allowance and whether typed questions are available.
The guards run cheapest first: a 5-a-minute sliding-window rate limit applied by an interceptor before the request body is even converted (so a malformed or oversized body counts too; the body is capped at 8 KiB); a strict
sanitiser that refuses (never strips) invisible, control, emoji and symbol characters and anything outside letters, digits, spaces and
`? ! ' ’ , . - : / & ( )`, caps at 200 characters and derives the normalised cache key; the daily spend cap ($0.50 by default, one admin
email per UK day) and the accounting latch, both 503 `TYPED_UNAVAILABLE`; then one atomic reservation of the allowance (LITE 3, PRO and
ADMIN 30 a UK day) and of a never-refunded engine-call ceiling (3 times the allowance), so "refund an unanswerable question" cannot
become free Claude calls. An answer is charged; an honest "not in the forecast" and a failure are refunded on the date they were
reserved, never below zero. Every error is `{"error","code"}` with the codes of the plan's table (`INVALID`, `RATE_LIMITED`,
`ALLOWANCE_EXHAUSTED`, `DAILY_LIMIT`, `ENGINE_FAILED`, `TYPED_UNAVAILABLE`). Migration V166 adds `ask_usage` with `ON DELETE CASCADE`.
The pre-filter, Ready intent match, typed cache and question log are wired as no-op `@Fallback` seams for B5.
