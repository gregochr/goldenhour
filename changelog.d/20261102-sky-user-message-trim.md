### Changed — Sky ratings send fewer unused lines to Claude

The per-location message sent with every sky rating no longer carries the precipitation probability, the shortwave
radiation reading or the "this location is best suited for sunrise photography" restatement of the orientation
line. No rule in the system prompt refers to any of them. This saves about 25 tokens per request; the system prompt,
output schema and cache prefixes are unchanged. See `docs/engineering/sky-user-message-trim-plan.md`.
