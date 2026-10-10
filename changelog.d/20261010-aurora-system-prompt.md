### Fixed — live aurora scoring sent Claude the data without the instructions, so every location was 1★

The aurora poller's scoring call — the one that runs on every NOTIFY, through
`EvaluationServiceImpl`'s synchronous aurora path — built its request from the user message
alone and never attached `ClaudeAuroraInterpreter`'s system prompt, which is where the "output
ONLY a JSON array, `stars` 1–5, STRONG is base 4★" contract lives. The batch twin
(`submitAurora`) had the same omission. Without the contract Claude answered in prose, the JSON
parse failed, and every viable location fell to the 1★ "conditions could not be assessed"
fallback — so on 2026-10-10, a G3 storm (Kp 7) with 222 dark-sky locations clear, the Map drew
247 one-star pins. Only the admin preview/run (`AuroraForecastRunService`, which calls
`interpret()` directly) ever carried the prompt. The omission dates from the `evaluateNow`
refactor of April 2026.

Both requests now attach `ClaudeAuroraInterpreter.systemPrompt()` — one accessor, read by all
three call sites — and two tests pin the prompt onto each transport.
