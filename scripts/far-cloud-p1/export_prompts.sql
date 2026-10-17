-- P1 export (task T1): stored batch sky prompts joined to their evaluation rows.
-- Plan: docs/engineering/far-cloud-p1-plan.md section 3. READ-ONLY: a single SELECT inside COPY, writes nothing.
-- Prompts are pruned after 30 days: run this one FIRST.
-- Run: docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f - < export_prompts.sql > p1_prompts.csv
COPY (
    SELECT
        p.evaluation_id                         AS evaluation_id,
        fe.location_id                          AS location_id,
        l.name                                  AS loc_name,
        fe.target_date                          AS target_date,
        fe.target_type                          AS target_type,
        to_char(fe.forecast_run_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS forecast_run_at,
        fe.days_ahead                           AS days_ahead,
        fe.evaluation_model                     AS evaluation_model,
        fe.rating                               AS rating,
        fe.sky_rating                           AS sky_rating,
        fe.batch_state                          AS batch_state,
        to_char(p.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS') AS prompt_created_at_utc,
        p.user_message                          AS user_message
    FROM forecast_evaluation_prompt p
    JOIN forecast_evaluation fe ON fe.id = p.evaluation_id
    JOIN locations l ON l.id = fe.location_id
    ORDER BY fe.location_id, fe.target_date, fe.target_type, fe.forecast_run_at, p.evaluation_id
) TO STDOUT WITH CSV HEADER;
