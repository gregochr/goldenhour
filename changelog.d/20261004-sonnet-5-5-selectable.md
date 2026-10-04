### Added — Claude Sonnet 5.5 is a selectable evaluation model

`claude-sonnet-5-5` can now be chosen anywhere a model is chosen (the Models screen per run type, the
prompt test and sky-rating eval harnesses, the briefing advisor replay) as `SONNET_55`. It is not the
default or active model for any run type, and no data changed.

The model rejects `thinking: disabled`, so it is sent no `thinking` parameter (adaptive thinking is its
default) and instead `output_config.effort = low` alongside the existing JSON-schema format, with a
4096 max-token ceiling because thinking tokens count against it. Every other model's request is
unchanged and pinned by tests. Priced at $2.00 in, $10.00 out, $0.20 cache read and $2.50 5-minute cache
write per million tokens. A refusal (`stop_reason: refusal`) now fails the aurora and gloss calls and the
batch result cleanly instead of reaching the parser as an empty reply. On 2026-10-04 measurements it cost
about the same as Sonnet 4.6 (its tokeniser counts roughly 45% more input) and failed one regression case
that Sonnet 4.6 passes. The Models screen now labels the existing Sonnet "Sonnet 4.6".
