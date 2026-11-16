-- Broken-low Phase A export: readers' outcome ratings plus a non-personal by_admin flag, for bl_size.py --ratings
-- (broken-low-canvas-plan.md section 2, A2). Same columns as export_outcome_ratings.sql plus by_admin.
-- READ-ONLY: a single SELECT inside COPY, writes nothing. user_id, username and email are NOT exported:
-- by_admin is only whether the rating's account has the ADMIN role (app_user.role = 'ADMIN').
-- Run: docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f - < bl_export_ratings.sql > bl_ratings.csv
-- rating is empty for "didn't see it" (seen = NOT_SEEN). forecast_rating is the star the reader was shown.
COPY (
    SELECT
        o.location_id      AS location_id,
        o.event_date       AS event_date,
        o.event_type       AS event_type,
        o.rating           AS rating,
        o.seen             AS seen,
        o.forecast_rating  AS forecast_rating,
        (u.role = 'ADMIN') AS by_admin
    FROM outcome_rating o
    JOIN app_user u ON u.id = o.user_id
    ORDER BY o.event_date, o.location_id, o.event_type
) TO STDOUT WITH CSV HEADER;
