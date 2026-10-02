### Fixed — failed synchronous Claude calls are logged with their real status and an error type

A failed synchronous Claude call (the forecast run, the aurora evaluation, and the legacy evaluation
decorator) was written to `api_call_log` with a hard-coded status 500 and no `error_type`. It now
records the HTTP status Anthropic actually answered with (401, 403, 429, 529, 5xx, 400) and the
`error_type` the run panel already uses (`anthropic_401`, `content_filter`, `refusal`,
`reply_unreadable`, `circuit_open`, ...). A failure with no HTTP status of its own (a connection
failure, an unreadable reply, a refusal, an open breaker) records a null status, the same as the batch
path and the weather clients; every reader decides failure from `succeeded`, never from the status.

Rows written before this change keep their placeholder 500 and no error type; that 500 is not the real
status. The Job Runs detail therefore shows no status for a Claude row without an error type: its Failed
Calls heading reads `ANTHROPIC · anthropic_401` for a new row (the type already carries the status),
`ANTHROPIC` for an old one, and `OPEN_METEO · 503` for a weather row, whose status is always real. The
metrics endpoint now serves `statusCode`.
