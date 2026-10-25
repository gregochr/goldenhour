### Changed — The API no longer measures or serves drive times from home for Lite accounts

`GET /api/user/settings/drive-times`, `GET /api/user/settings/reach` and `POST /api/user/settings/drive-times/refresh` now answer 403 to a Lite account (Pro and Admin are unchanged), and the nightly drive-time job, including the admin "Run now", measures only enabled Pro and Admin accounts with a home, so no OpenRouteService quota is spent on Lite homes. Saving a home postcode and `GET /api/user/settings` stay open to every role. Drive times already stored for Lite accounts are left in place.
