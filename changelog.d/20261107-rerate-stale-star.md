### Fixed — re-rating returns the new star

`PUT /api/outcome/ratings` on an existing rating answered with the previous star (and previous seen tag), so "How was it?" snapped back until a reload. The column-scoped bulk updates bypassed the request's persistence context, so the service's re-read returned the stale managed row; the three updates now flush and clear the context (`clearAutomatically`), so the re-read hits the database.
