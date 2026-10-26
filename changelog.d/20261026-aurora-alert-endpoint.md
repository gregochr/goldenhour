### Added — a role-free aurora alert endpoint for the Lite banner

`GET /api/aurora/alert` (any authenticated role) serves the alert level, whether it is active, the trigger
type, an `allOvercast` flag and the current night, and nothing a Pro reader pays for (no Kp, counts or
ratings). A simulation answers QUIET. It is ETag-revalidatable, `/api/aurora/status` stays Pro/Admin, and
"an active, alert-worthy live alert" is now defined once and shared with `/api/aurora/nights`.
