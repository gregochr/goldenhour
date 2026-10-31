### Added — admins can set or clear a location's tide point

The location editor has a "Tide point" latitude/longitude pair (coastal locations), with a Clear button, for locations whose own sea area WorldTides models badly. `PUT /api/locations/{id}` accepts `tideLat`, `tideLng` and `clearTidePoint` (both coordinates or neither; within 25 km of the location). When the point changes, tide times are re-read as soon as the edit is saved instead of waiting for Monday's refresh; if WorldTides fails the edit is kept and the weekly refresh retries.
