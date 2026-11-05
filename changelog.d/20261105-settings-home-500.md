### Fixed — saving a home postcode with a control character now gets 400 instead of 500

`PUT /api/user/settings/home` accepted a postcode containing a control character, such as a NUL byte.
PostgreSQL refuses to store a NUL in a text column, so on production the save failed in the database and
answered 500. `SaveHomeRequest` now rejects control characters in the postcode when the body is bound, so
the caller gets the generic 400 `{"error":"Invalid request"}` and nothing is written or echoed.

`POST /api/user/settings/home/lookup` had the matching gap: a body with no postcode reached the lookup
client as null and answered 500. `PostcodeLookupRequest` is now validated the same way (present, not blank,
no control characters), so these answer 400 as well.
