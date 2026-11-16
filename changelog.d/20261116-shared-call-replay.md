### Docs — One call per weather cell measured and rejected

Replaying 30 days of stored prompts shows places in the same weather cell almost never send the same prompt:
one shared call would have saved 1.25% of Sonnet near-term calls ($0.85 a month), or 4.3% with the event time cut
to the minute, both under the 8% bar the plan set. The fan-out is not built. The query is
`docs/engineering/cost-queries/shared-call-replay.sql`.
