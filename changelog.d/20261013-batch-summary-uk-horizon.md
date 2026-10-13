### Fixed — the batch summary in admin views dates its horizon by the UK day

`BatchSummaryDeriver` measured a batch run's horizon from the UTC date of `job_run.started_at`. A midsummer morning run starting at 23:13 UTC is already the next UK day, so it would have shown T+1 to T+4 instead of T to T+3. It now uses the UK civil date through `ForecastHorizon`; runs started at 01:00 UTC are unchanged.
