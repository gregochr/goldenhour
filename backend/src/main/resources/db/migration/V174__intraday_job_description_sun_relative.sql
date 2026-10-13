-- V174: the intraday scheduler row's description no longer says it runs "mid-afternoon".
--
-- Since solar refresh P2a (V171) the cycle can run on a sun-relative schedule, a set time before
-- sunset (the plan's rule is four hours), which in December is about 11:40 UK: morning, not
-- afternoon. "mid-afternoon" was written for the fixed 14:00 UTC cron. The new wording names no time
-- of day, only the event the run must precede, so it is true in every season and under both
-- schedules, because the job can be switched between them from the Scheduler screen; the rule itself
-- is shown beside the description.
--
-- Guarded on V141's exact text, so a description an operator has since edited by hand is left alone.
-- Writes only `description` on at most one row (job_key is NOT NULL UNIQUE): the schedule type,
-- solar rule, cron, status and timestamps stay exactly as the operator has them. Same shape and
-- reasoning as V141 (an applied migration is never edited in place).

UPDATE scheduler_job_config
SET description = 'Re-evaluates the decision-window events (T sunset, T+1 sunrise, T+1 sunset) '
                  || 'ahead of tonight''s sunset (a set time before it on the '
                  || 'sun-relative schedule, else at its cron time). TRANSITIONAL/UNSETTLED are '
                  || 'always evaluated. SETTLED is evaluated for T sunset and T+1 sunrise, and '
                  || 'skipped only for T+1 sunset (SKIPPED_NO_REFRESH_NEEDED), which is guaranteed '
                  || 'two further looks before its event. Re-runs the briefing afterwards.'
WHERE job_key = 'intraday_forecast_refresh'
  AND description = 'Re-evaluates the decision-window events (T sunset, T+1 sunrise, T+1 sunset) '
                    || 'mid-afternoon. TRANSITIONAL/UNSETTLED are always evaluated. SETTLED is '
                    || 'evaluated for T sunset and T+1 sunrise, and skipped only for T+1 sunset '
                    || '(SKIPPED_NO_REFRESH_NEEDED), which is guaranteed two further looks before '
                    || 'its event. Re-runs the briefing afterwards.';
