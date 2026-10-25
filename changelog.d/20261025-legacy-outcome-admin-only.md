### Security — Legacy outcome API is admin-only

`GET /api/outcome`, `GET /api/outcome/all` and `POST /api/outcome` now require the ADMIN role. They had no role gate, so any signed-in
account could read every `actual_outcome` row (free-text notes included) and write rows that feed the calibration report. The reader-facing
rating (`/api/outcome/ratings*`) is unchanged, and the privacy page now says only administrators can read the older records.
