### Fixed — coastal OPEN_FELL bluebell locations no longer double-count tide in their rating

A coastal, OPEN_FELL-exposure bluebell site's served rating averaged in its tide score twice: once
via the sky task's own sky+tide combine, and a second time via the bluebell task's own tide+bluebell
combine, before the two were blended together at cache-merge time. The net effect was
`avg(avg(sky, tide), avg(tide, bluebell))` — tide entered the composite twice while sky and bluebell
each entered once, over-weighting a tide mismatch and under-weighting sky and bluebell for exactly
the locations that combine both features. `ForecastResultHandler.buildBluebellResult` no longer
re-derives a tide context for an OPEN_FELL bluebell combine — that location's paired sky task
already derives and averages the same tide context, and the merge-time recombination
(`BriefingEvaluationService.recombineBluebell`) folds it in from there, so tide now contributes
exactly once. WOODLAND bluebell sites are unaffected (their rating was never a tide peer) and still
derive tide for the `forecast_score` audit trail, since an in-season WOODLAND site has no sky call
to record it otherwise.
