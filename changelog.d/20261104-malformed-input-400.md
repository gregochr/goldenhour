### Fixed — malformed request parameters get 400, and admin paths are refused at the filter

A query or path parameter that cannot be converted to its declared type (a non-date where a date is
expected, text where a number is expected, an unknown enum constant) now gets a 400 with the generic
body `{"error": "Invalid request parameter"}` instead of a 500. The body never repeats the supplied
value. Separately, `/api/admin/**` is now refused with 403 for non-admins at the security filter,
before any argument binding, as a first layer ahead of the existing method-level role checks.
