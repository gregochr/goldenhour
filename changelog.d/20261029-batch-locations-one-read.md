### Changed — batch result processing reads locations once

`BatchResultProcessor` now loads all locations with a single `findAll()` before streaming a forecast batch's results,
instead of one `findById` per result (performance review finding F27, phase P5e). A result whose location id is not
found is still logged as a `lookup_error` and counted as errored, exactly as before.
