### Removed — admin and API endpoints with no caller (dead-code sweep, phase C)

Eight endpoints that nothing in the app, the scripts or the docs' runbooks called are gone, and
now answer 404: `GET /api/admin/stability/summary`, `GET /api/forecast/history`,
`GET /api/forecast/compare`, `GET /api/locations/grid-cells`, `GET /api/admin/build-info`,
`POST /api/admin/batches/force-submit` with `GET /api/admin/batches/force-result/{batchId}`,
`GET /api/user/settings/drive-times` and `GET /api/admin/sky-rating-eval/runs/{id}/results`.
Everything that only they used went with them: the `ForceSubmitBatchService` force-submit and
result-polling code (the JFDI route stays), `CustomIdFactory.forForceSubmit`, the grid-cell summary,
the two history finders on `ForecastEvaluationRepository`, the per-run results finder, and the
frontend's `getDriveTimes` and `getSkyRatingEvalResults`. The stability snapshot, the force-submit id
parser, `POST /drive-times/refresh`, `GET /api/user/settings/reach`, calibration and the
cloud-verification report are untouched. The endpoint reference and the integration-test strategy
note were updated to match.
