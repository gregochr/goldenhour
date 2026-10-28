-- V197: indexes for the repository queries that scan the tables which grow without limit
-- (performance-review-2026-10-plan.md P6a, finding F30, audit in section 7.1).
--
-- Context. The owner accepted on 2026-10-28 that api_call_log, forecast_evaluation, forecast_score,
-- job_run and friends have no retention job. What matters instead is that no query on them gets
-- slower as they grow. A read-only audit of every repository query found five that are served by
-- no existing index, i.e. scan the whole table, so each is indexed here.
--
-- Additive-only. Plain CREATE INDEX (not CONCURRENTLY — Flyway runs migrations inside a
-- transaction, and CONCURRENTLY cannot), following V160. A plain build blocks writes to the table
-- for the build's duration at startup; api_call_log and forecast_evaluation are the large ones.

-- I1: ApiCallLogRepository.findCustomIdsByBatchId, findBatchCallOutcomes and findFailedBatchCalls
-- all filter on batch_id (= :batchId or IN :batchIds). They run on every batch completion, every
-- pipeline cycle (CycleLocationOutcomeResolver) and every retry. No existing index contains
-- batch_id (V99 added the column without one), so each call is a near-full scan of the largest
-- table. Partial: batch_id is NULL for every non-batch call, which is most rows, and every
-- caller's predicate implies batch_id IS NOT NULL.
CREATE INDEX idx_api_call_log_batch_id
    ON api_call_log (batch_id)
    WHERE batch_id IS NOT NULL;

-- I2: ForecastEvaluationRepository.abandonPendingOlderThan, the hourly evaluation_abandonment_sweep
-- (V150): UPDATE ... WHERE batch_state = 'PENDING' AND forecast_run_at < :cutoff. batch_state is
-- @Enumerated(STRING), so the stored literal is 'PENDING'. V149 deliberately added no index and
-- said to add a (batch_state, forecast_run_at) one if EXPLAIN or growth called for it; V128's
-- index leads on location_id and this predicate has none. Partial on PENDING, which is a tiny
-- fraction of the table, so the index stays small and cheap to maintain.
CREATE INDEX idx_forecast_evaluation_pending_run_at
    ON forecast_evaluation (forecast_run_at)
    WHERE batch_state = 'PENDING';

-- I3: ForecastScoreRepository.findScoredComponentsByPipelineRun, once per pipeline cycle:
-- WHERE pipeline_run_id = :id AND forecast_type_id IN (...). V108's indexes lead on other columns,
-- so none serves it; it also backs the otherwise unindexed fk_score_pipeline_run.
CREATE INDEX idx_forecast_score_pipeline_run
    ON forecast_score (pipeline_run_id);

-- I4: ForecastEvaluationRepository.findByTargetDateAndTargetTypeIn, nightly (TopicDailyLogJob).
-- It has no location predicate, and every index on forecast_evaluation (V1's, V128's) leads on
-- location_id or location_name, so the query is a full scan of the biggest table.
CREATE INDEX idx_forecast_evaluation_date_type
    ON forecast_evaluation (target_date, target_type);

-- I6: JobRunRepository's run_type + started_at queries, and the job_run subquery in
-- ApiCallLogRepository.sumCostMicroDollarsByRunTypeStartedSince. V20's idx_job_run_name_started was
-- on (job_name, started_at); V29 renamed job_name to run_type and dropped it, and nothing replaced
-- it. The table is small today but unbounded, and the index is cheap.
CREATE INDEX idx_job_run_run_type_started
    ON job_run (run_type, started_at);
