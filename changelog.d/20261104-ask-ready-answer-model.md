### Changed — stored Ask ready answers record the model that wrote them

`ask_ready_answer` gains a nullable `evaluation_model` column (V219). Every precompute upsert writes the model
the run pinned from the `ASK_READY` row, overwriting it when an answer is replaced, so once Ask's ready model
is switched on Run Config the stored answers show which model wrote each. Existing rows are backfilled `HAIKU`
(every answer so far was written on Haiku 4.5). Nothing a reader is served changes.
