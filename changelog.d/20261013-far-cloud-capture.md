### Added — far-point mid/high and dark-hour cloud are recorded (data only)

V175 adds five nullable columns to `forecast_evaluation` (mid and high cloud at the 226 km far-solar point at the event's hour, and low, mid and high at the far point's dark-side hour) and the five matching ERA5 columns to `cloud_verification`. The forecast side is persisted through `DirectionalCloudDetails`; the ERA5 columns are filled by a later change. Existing rows stay null and nothing is backfilled. Nothing user-visible changes: the prompt, triage, ratings, API responses and the frontend are untouched.
