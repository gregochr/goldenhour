-- V212: the intraday scheduler row's description now says that tomorrow's sunrise is left for the
-- evening refresh when that is due.
--
-- V174's wording says SETTLED is evaluated for T sunset and T+1 sunrise. Since the afternoon cycle
-- began leaving an already-rated T+1 sunrise to tonight's 21:00 evening refresh (when that refresh is
-- ACTIVE and due before midnight UK time), that is no longer the whole truth. A T+1 sunrise with no
-- stored rating is still rated, so no region waits until 21:00 for a score.
--
-- Guarded on V174's exact text, so a description an operator has since edited by hand is left alone.
-- Writes only `description` on at most one row (job_key is NOT NULL UNIQUE). Same shape and
-- reasoning as V141 and V174 (an applied migration is never edited in place).

UPDATE scheduler_job_config
SET description = 'Re-evaluates the decision-window events (T sunset, T+1 sunrise, T+1 sunset) '
                  || 'ahead of tonight''s sunset (a set time before it on the '
                  || 'sun-relative schedule, else at its cron time). TRANSITIONAL/UNSETTLED are '
                  || 'always evaluated. SETTLED is evaluated for T sunset, and skipped for T+1 sunset '
                  || '(SKIPPED_NO_REFRESH_NEEDED), which is guaranteed two further looks before its '
                  || 'event. T+1 sunrise is evaluated, except that when tonight''s evening sunrise '
                  || 'refresh is due, a T+1 sunrise that already has a rating is left for it '
                  || '(SKIPPED_NO_REFRESH_NEEDED). Re-runs the briefing afterwards.'
WHERE job_key = 'intraday_forecast_refresh'
  AND description = 'Re-evaluates the decision-window events (T sunset, T+1 sunrise, T+1 sunset) '
                    || 'ahead of tonight''s sunset (a set time before it on the '
                    || 'sun-relative schedule, else at its cron time). TRANSITIONAL/UNSETTLED are '
                    || 'always evaluated. SETTLED is evaluated for T sunset and T+1 sunrise, and '
                    || 'skipped only for T+1 sunset (SKIPPED_NO_REFRESH_NEEDED), which is guaranteed '
                    || 'two further looks before its event. Re-runs the briefing afterwards.';
