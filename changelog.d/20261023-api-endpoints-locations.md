### Docs — api-endpoints.md lists the real location routes

`docs/engineering/reference/api-endpoints.md` documented `PUT /api/locations/{name}/reset-failures` where the code maps `PUT /api/locations/reset-failures?name=`, and omitted several `LocationController` routes (`PUT /{id}`, `PUT /{id}/enabled`, `GET /grid-cells`, `GET /enrich`); it now lists them all, with the new named-area and alias endpoints and their status codes.
