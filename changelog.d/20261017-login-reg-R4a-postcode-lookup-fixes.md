### Fixed — an unknown postcode answered 500, and a looked-up postcode never carried its place name

`POST /api/user/settings/home/lookup` answered **500** for a postcode postcodes.io does not know (the
exception fell through to the catch-all). It now answers 400 for "no such postcode" and 502 with a fixed
"Postcode lookup is unavailable right now" for a transport failure, a 5xx or a rate limit, never echoing
the upstream's message; the client tells the two apart from the upstream status (only a 400 or 404 is
"not found"). The Settings dialog still shows "Invalid postcode" for any failure, as before.

postcodes.io's `admin_district` and `admin_ward` fields were never bound to the client's camelCase
record, so every place name fell back to the bare postcode ("DH1 3LE" where "Durham, County Durham"
was meant) in the Settings dialog and the masthead's place line; the fields are now annotated and a
contract test builds the name from the real wire shape.
