### Added — prompt replay harness gains a Batch API mode (half price)

`PromptReplayHarnessTest` (far-cloud P1 Part D, test-only, tagged `prompt-regression`, never run by CI) takes
`REPLAY_BATCH=true` to submit the same request bodies as one Message Batch per model at 50% of standard prices.
A one-request primer batch per system prefix is submitted first so the real requests can read a 1-hour cache
(`REPLAY_PRIMER_WAIT_SECONDS`, fail-open). Batch ids are written to `target/p0-replay/batches.json` as they are
created and `REPLAY_BATCH_RESUME=true` re-polls them instead of resubmitting. The run prints the request count and
an estimated cost first and refuses to submit unless `REPLAY_CONFIRM_COST` equals the printed request total.
`runs.csv` keeps its columns and gains `mode`, `batch_id` and token columns at the end, so
`scripts/far-cloud-p1/replay_set.py` reads direct and batch runs alike. No production code changed; the batch
path is covered by a WireMock-backed `PromptReplayBatchTest`.
