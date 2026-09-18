### Fixed — coastal OPEN_FELL bluebell locations no longer double-count tide in their rating

A coastal, OPEN_FELL-exposure bluebell site's served rating averaged in its tide score twice: once
via the sky task's own sky+tide combine, and a second time via the bluebell task's own tide+bluebell
combine, before the two were blended together at cache-merge time. The net effect was
`avg(avg(sky, tide), avg(tide, bluebell))` — tide entered the composite twice while sky and bluebell
each entered once, over-weighting a tide mismatch and under-weighting sky and bluebell for exactly
the locations that combine both features.

`ForecastResultHandler.buildBluebellResult` now re-derives a tide context for an OPEN_FELL bluebell
combine only when the paired sky task has not already been cached this cycle. Sky and bluebell are
separate Anthropic batches that complete independently, so the common case — sky already scored and
cached — suppresses tide here and lets the merge-time recombination
(`BriefingEvaluationService.recombineBluebell`) fold it in from the sky side instead, so tide
contributes exactly once. When no sky entry is cached yet (the sky task is still in flight, or
failed outright this cycle), tide is still derived here as the sole available signal, so a
misaligned tide is never silently dropped from the rating. WOODLAND bluebell sites are unaffected
(their rating was never a tide peer) and still always derive tide for the `forecast_score` audit
trail, since an in-season WOODLAND site has no sky call to record it otherwise.
