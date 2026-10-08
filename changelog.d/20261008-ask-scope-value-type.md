### Changed — Ask resolves a question's region scope once, as a value

Ask PhotoCast carried a question's regions as a bare list of names that seven call sites re-lower-cased and re-compared in
four different ways, and it read the region ids from the database up to four times per typed question (the guard, the
scope, the engine, and the answer cache). The scope is now one immutable `AskScope` value, resolved once where the question
is validated and carried on the question, so the tools, the validator, the prompt, the Ready freshness check, both engines
and the typed cache all ask the same `contains` question of the same object. An unknown or disabled region id still fails
the request with the same 400 `INVALID` rather than widening it, and nothing on the wire, in `ask_log`, `ask_usage` or
`ask_ready_answer`, or in the prompt sent to Claude changes. The admin dry-run and the Ready precompute now take their
normalised question form from the sanitiser like the typed endpoint instead of lower-casing it themselves.
