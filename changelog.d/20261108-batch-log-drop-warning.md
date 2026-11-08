### Fixed — a batch with no linked job run now says it will record no cost rows

`api_call_log.job_run_id` is `NOT NULL`, so a batch whose `jobRunId` is absent cannot write any
cost rows, and the result handlers dropped them without a word. `BatchResultProcessor` now logs
one WARN per batch (forecast and aurora separately) naming the Anthropic batch id and the
consequence, and each per-result drop site logs a DEBUG line that separates "no result context
was supplied" from "the context carries no job run id".
