### Chore — Best bet advisor retirement: leftover extended-thinking, dead types and docs removed

Admin-only wire change: `PUT /api/models/extended-thinking` is removed and `extendedThinkingConfigs` is dropped from
`GET /api/models` (the advisor was the only caller that honoured the flag; the `model_selection.extended_thinking`
column stays until the schema phase). Also removed: the unused `ModelSelectionService` extended-thinking methods and
the front end's `setExtendedThinking`, `DiffersBy` and `Relationship`, and the unread `requestUrl` on
`ApiCallCostRow`. `cost-queries/measured-costs.sql` gains a per-day query for the retired advisor's
`briefing-best-bet` calls, and the cost ledger, Javadoc and plan notes that mis-described them are corrected. A test
of the honesty filter's no-clock path is restored and the legacy-payload tests now assert the stored headline and
stale flag.
