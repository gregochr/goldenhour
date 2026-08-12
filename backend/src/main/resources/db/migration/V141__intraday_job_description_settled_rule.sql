-- V141: correct the intraday scheduler row's description.
--
-- V105 seeded it as "gated to TRANSITIONAL/UNSETTLED locations; settled locations are skipped
-- (SKIPPED_NO_REFRESH_NEEDED)". That is no longer what the cycle does: IntradayEligibilityPolicy
-- now skips SETTLED only where a later look is structurally guaranteed, which is tomorrow's sunset
-- alone. Tonight's sunset and tomorrow's sunrise are evaluated at every stability.
--
-- The old rationale — "the synoptic pattern has not moved since the morning, so the nightly
-- evaluation still holds" — was the same claim the 36h cache freshness threshold rested on, and
-- evaluation_delta_log refutes it: a SETTLED slot re-evaluated after 24h moved a full star 63% of
-- the time. Synoptic persistence is not rating persistence.
--
-- Why an UPDATE rather than editing V105 in place: V105's INSERT has already run in production,
-- and application-prod.yml sets `validate-on-migrate: false`, so amending the applied file would
-- change nothing on the box AND raise no checksum error — a silent no-op. Same reasoning and same
-- shape as V139__tide_refresh_description_horizon.sql.
--
-- Safe if the row is absent: job_key is NOT NULL UNIQUE, so this touches at most one row, and it
-- writes only `description` — the cron expression, status and fire/completion timestamps are left
-- exactly as the operator has them.

UPDATE scheduler_job_config
SET description = 'Re-evaluates the decision-window events (T sunset, T+1 sunrise, T+1 sunset) '
                  || 'mid-afternoon. TRANSITIONAL/UNSETTLED are always evaluated. SETTLED is '
                  || 'evaluated for T sunset and T+1 sunrise, and skipped only for T+1 sunset '
                  || '(SKIPPED_NO_REFRESH_NEEDED), which is guaranteed two further looks before '
                  || 'its event. Re-runs the briefing afterwards.'
WHERE job_key = 'intraday_forecast_refresh';
