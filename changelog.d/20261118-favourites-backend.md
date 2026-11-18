### Added — favourites backend (places and areas a Pro reader stars)

`/api/user/settings/favourites` (Pro and Admin only) now lets a reader keep up to 25 favourites, places and areas
together: `GET` returns them already merged (`locationIds` is the starred places plus the enabled members of the
starred areas, so the client never walks area membership), `PUT` replaces the whole list, and per-item `POST` /
`DELETE` under `places/{id}` and `areas/{id}` are idempotent. Unknown ids are 404, a new star over the cap is 409
`You can keep up to 25 places.`, and the cap holds under the reader's row lock when two requests race. Migration
V226 adds `user_favourite` with an exactly-one-target check, partial unique indexes and cascades on the account,
location and area. Favourites are independent of Lite sub-region picks, never ride an ETag-shared payload, and
nothing in the UI uses them yet (find-and-favourites F4).
