-- P1 export: readers' outcome ratings, for canvas.py --ratings (far-cloud-p1-plan.md section 17).
-- READ-ONLY: a single SELECT inside COPY, writes nothing. Personal data (user_id is NOT exported).
-- Run: docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f - < export_outcome_ratings.sql > outcome_ratings.csv
-- rating is empty for "didn't see it". forecast_rating is the star the reader was shown.
COPY (
    SELECT
        o.location_id      AS location_id,
        o.event_date       AS event_date,
        o.event_type       AS event_type,
        o.rating           AS rating,
        o.seen             AS seen,
        o.forecast_rating  AS forecast_rating
    FROM outcome_rating o
    ORDER BY o.event_date, o.location_id, o.event_type
) TO STDOUT WITH CSV HEADER;
