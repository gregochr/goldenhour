-- P1 export (task T1): every SUNRISE/SUNSET forecast_evaluation row since 2026-02-01, every run.
-- Plan: docs/engineering/far-cloud-p1-plan.md section 3. READ-ONLY: a single SELECT inside COPY, writes nothing.
-- Run: docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f - < export_forecasts.sql > p1_forecasts.csv
-- Timestamps are naive UTC in forecast_evaluation / cloud_verification; they are emitted as ISO text with no zone.
COPY (
    SELECT
        fe.id                                   AS evaluation_id,
        fe.location_id                          AS location_id,
        fe.target_date                          AS target_date,
        fe.target_type                          AS target_type,
        to_char(fe.forecast_run_at, 'YYYY-MM-DD"T"HH24:MI:SS')  AS forecast_run_at,
        to_char(fe.solar_event_time, 'YYYY-MM-DD"T"HH24:MI:SS') AS solar_event_time,
        fe.azimuth_deg                          AS azimuth_deg,
        fe.days_ahead                           AS days_ahead,
        fe.evaluation_model                     AS evaluation_model,
        fe.batch_state                          AS batch_state,
        fe.triage_reason                        AS triage_reason,
        fe.triage_message                       AS triage_message,
        fe.rating                               AS rating,
        fe.sky_rating                           AS sky_rating,
        fe.fiery_sky_potential                  AS fiery_sky_potential,
        fe.golden_hour_potential                AS golden_hour_potential,
        fe.basic_fiery_sky_potential            AS basic_fiery_sky_potential,
        fe.basic_golden_hour_potential          AS basic_golden_hour_potential,
        fe.confidence                           AS confidence,
        fe.headline                             AS headline,
        fe.summary                              AS summary,
        -- observer-point cloud and weather
        fe.low_cloud                            AS low_cloud,
        fe.mid_cloud                            AS mid_cloud,
        fe.high_cloud                           AS high_cloud,
        fe.visibility                           AS visibility,
        fe.wind_speed                           AS wind_speed,
        fe.wind_direction                       AS wind_direction,
        fe.precipitation                        AS precipitation,
        fe.humidity                             AS humidity,
        fe.weather_code                         AS weather_code,
        fe.boundary_layer_height                AS boundary_layer_height,
        fe.shortwave_radiation                  AS shortwave_radiation,
        fe.pm2_5                                AS pm2_5,
        fe.dust                                 AS dust,
        fe.aerosol_optical_depth                AS aerosol_optical_depth,
        fe.temperature_celsius                  AS temperature_celsius,
        fe.dew_point_celsius                    AS dew_point_celsius,
        -- directional cone, antisolar, far field, dark hour, pressure levels
        fe.solar_low_cloud                      AS solar_low_cloud,
        fe.solar_mid_cloud                      AS solar_mid_cloud,
        fe.solar_high_cloud                     AS solar_high_cloud,
        fe.antisolar_low_cloud                  AS antisolar_low_cloud,
        fe.antisolar_mid_cloud                  AS antisolar_mid_cloud,
        fe.antisolar_high_cloud                 AS antisolar_high_cloud,
        fe.far_solar_low_cloud                  AS far_solar_low_cloud,
        fe.far_solar_mid_cloud                  AS far_solar_mid_cloud,
        fe.far_solar_high_cloud                 AS far_solar_high_cloud,
        fe.far_solar_low_cloud_dark             AS far_solar_low_cloud_dark,
        fe.far_solar_mid_cloud_dark             AS far_solar_mid_cloud_dark,
        fe.far_solar_high_cloud_dark            AS far_solar_high_cloud_dark,
        fe.far_solar_cloud_850hpa               AS far_solar_cloud_850hpa,
        fe.far_solar_cloud_700hpa               AS far_solar_cloud_700hpa,
        fe.far_solar_cloud_600hpa               AS far_solar_cloud_600hpa,
        fe.far_solar_cloud_500hpa               AS far_solar_cloud_500hpa,
        fe.far_solar_cloud_400hpa               AS far_solar_cloud_400hpa,
        fe.far_solar_cloud_300hpa               AS far_solar_cloud_300hpa,
        -- cloud approach risk (V51)
        fe.solar_trend_event_low_cloud          AS solar_trend_event_low_cloud,
        fe.solar_trend_earliest_low_cloud       AS solar_trend_earliest_low_cloud,
        fe.solar_trend_building                 AS solar_trend_building,
        fe.upwind_current_low_cloud             AS upwind_current_low_cloud,
        fe.upwind_event_low_cloud               AS upwind_event_low_cloud,
        fe.upwind_distance_km                   AS upwind_distance_km,
        -- location and region
        l.name                                  AS loc_name,
        l.lat                                   AS loc_lat,
        l.lon                                   AS loc_lon,
        l.enabled                               AS loc_enabled,
        l.is_coastal_tidal                      AS loc_is_coastal_tidal,
        l.region_id                             AS loc_region_id,
        r.name                                  AS region_name,
        (SELECT string_agg(llt.location_type, ',' ORDER BY llt.location_type)
           FROM location_location_type llt
          WHERE llt.location_id = l.id)         AS loc_types,
        -- cloud_verification (ERA5), NULL when not verified
        cv.id                                   AS cv_id,
        cv.horizon_sample_lat                   AS cv_horizon_sample_lat,
        cv.horizon_sample_lon                   AS cv_horizon_sample_lon,
        cv.horizon_low_cloud                    AS cv_horizon_low_cloud,
        cv.horizon_low_min                      AS cv_horizon_low_min,
        cv.horizon_low_max                      AS cv_horizon_low_max,
        cv.horizon_mid_cloud                    AS cv_horizon_mid_cloud,
        cv.horizon_high_cloud                   AS cv_horizon_high_cloud,
        cv.far_low_cloud                        AS cv_far_low_cloud,
        cv.far_mid_cloud                        AS cv_far_mid_cloud,
        cv.far_high_cloud                       AS cv_far_high_cloud,
        cv.far_low_cloud_dark                   AS cv_far_low_cloud_dark,
        cv.far_mid_cloud_dark                   AS cv_far_mid_cloud_dark,
        cv.far_high_cloud_dark                  AS cv_far_high_cloud_dark,
        cv.observer_low_cloud                   AS cv_observer_low_cloud,
        cv.observer_mid_cloud                   AS cv_observer_mid_cloud,
        cv.observer_high_cloud                  AS cv_observer_high_cloud,
        to_char(cv.observed_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS cv_observed_at,
        to_char(cv.verified_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS cv_verified_at
    FROM forecast_evaluation fe
    JOIN locations l ON l.id = fe.location_id
    LEFT JOIN regions r ON r.id = l.region_id
    LEFT JOIN cloud_verification cv ON cv.forecast_evaluation_id = fe.id
    WHERE fe.target_type IN ('SUNRISE', 'SUNSET')
      AND fe.target_date >= DATE '2026-02-01'
    ORDER BY fe.location_id, fe.target_date, fe.target_type, fe.forecast_run_at, fe.id
) TO STDOUT WITH CSV HEADER;
