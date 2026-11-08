### Fixed — a batch with no linked job run now says it will record no cost rows

`api_call_log.job_run_id` is `NOT NULL`, so a batch whose `jobRunId` is absent cannot write any
cost rows, and the result handlers dropped them without a word. `BatchResultProcessor` now logs one
WARN per batch, forecast and aurora separately, naming the Anthropic batch id and the consequence.
The aurora warning is emitted before that method's early returns, so a refused or truncated
response — which is billed — is covered rather than returning ahead of the warning. Each
per-result drop site adds a DEBUG line: the three handler sites separate "no result context was
supplied" from "the context carries no job run id", while `inlineFailureLog` states the one case
reachable there, since every caller passes the per-batch context.
