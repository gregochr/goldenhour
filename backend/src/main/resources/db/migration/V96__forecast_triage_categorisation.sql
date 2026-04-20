-- V96: Categorise colour-forecast triage reasons.
--
-- Replaces the sentinel `rating=1 + summary="Conditions unsuitable — …"` pattern with
-- dedicated columns: `triage_reason` (enum name) and `triage_message` (formatted text
-- for display). Claude-scored rows leave both null. Triaged rows have rating=null and
-- summary=null so the API contract cleanly separates scored vs stand-down.

ALTER TABLE forecast_evaluation
    ADD COLUMN triage_reason VARCHAR(40);

ALTER TABLE forecast_evaluation
    ADD COLUMN triage_message TEXT;

-- Backfill existing sentinel rows. Categorise by substring match on the legacy summary;
-- everything not matched falls through to GENERIC. After the backfill, clear rating
-- and summary so consumers read the triage fields instead of the sentinel.

UPDATE forecast_evaluation
   SET triage_reason = 'HIGH_CLOUD',
       triage_message = REPLACE(summary, 'Conditions unsuitable — ', '')
 WHERE summary LIKE 'Conditions unsuitable —%'
   AND (summary LIKE '%low cloud%' OR summary LIKE '%sun blocked%');

UPDATE forecast_evaluation
   SET triage_reason = 'PRECIPITATION',
       triage_message = REPLACE(summary, 'Conditions unsuitable — ', '')
 WHERE summary LIKE 'Conditions unsuitable —%'
   AND summary LIKE '%Precipitation%';

UPDATE forecast_evaluation
   SET triage_reason = 'LOW_VISIBILITY',
       triage_message = REPLACE(summary, 'Conditions unsuitable — ', '')
 WHERE summary LIKE 'Conditions unsuitable —%'
   AND summary LIKE '%Visibility%';

UPDATE forecast_evaluation
   SET triage_reason = 'TIDE_MISALIGNED',
       triage_message = REPLACE(summary, 'Conditions unsuitable — tide not aligned — ', '')
 WHERE summary LIKE 'Conditions unsuitable — tide not aligned%';

-- Catch-all for any remaining sentinel rows (regional sentinel sampling, legacy rows).
UPDATE forecast_evaluation
   SET triage_reason = 'GENERIC',
       triage_message = REPLACE(summary, 'Conditions unsuitable — ', '')
 WHERE summary LIKE 'Conditions unsuitable —%'
   AND triage_reason IS NULL;

-- Null out sentinel rating and summary on triaged rows so the new contract holds.
UPDATE forecast_evaluation
   SET rating = NULL,
       summary = NULL
 WHERE triage_reason IS NOT NULL;
