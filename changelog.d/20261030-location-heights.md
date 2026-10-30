### Added — editable elevation and valley-floor suggestions for locations

The admin location editor can now correct a site's elevation by hand and set a valley floor below it. A
**Suggest** button samples 96 points within 5 km in one Open-Meteo elevation call and offers the lowest
point in each compass direction that is at least 30 m below the site (height, distance and bearing); picking
one fills the floor's height and coordinates and shows the model cell Open-Meteo resolves it to, so a wrong
valley is visible before anything uses it. The floor is stored in three new nullable columns on `locations`
(V201: `valley_floor_m`, `valley_floor_lat`, `valley_floor_lng`) and is served on `GET /api/locations`.
`PUT /api/locations/{id}` takes `elevationMetres`, the three floor fields and `clearValleyFloor`; new
admin-only `GET /api/locations/valley-floor-candidates` and `GET /api/locations/valley-floor-cell`.

Nothing in the forecast path reads the valley floor yet (inversion v2 phase 1b will). Editing
`elevation_m` does move what already reads it (inversion eligibility at 200 m on a site that overlooks water,
the snow-tops topic, the neighbour guard), so the editor warns when an edit crosses 200 m on such a site.
The elevation backfill and the Add dialog are unchanged.
