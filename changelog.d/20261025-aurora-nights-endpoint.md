### Added — a role-free aurora nights endpoint for the Lite door

`GET /api/aurora/nights` answers, for any signed-in role, which nights a Pro reader would be offered an
aurora row for (stored scoring-run nights from the current night on, plus the current night while a
real MODERATE or STRONG alert is live) and when the current night ends. It carries dates only, never a
rating, location or summary, and never counts a simulation. It lives in its own controller so the Pro
gate on the rest of `/api/aurora/*` is untouched, and it revalidates by ETag. Nothing in the app calls
it yet: it is the signal the Lite "aurora is Pro" door will read next.
