### Fixed — "Re-enable" on the Location Issues alert now actually re-enables the place

The alert's Re-enable button called `PUT /api/locations/reset-failures`, which cleared the failure
counter and the disabled reason but never touched `enabled`, so a place switched off by the
auto-disable would have stayed off after the admin pressed it. `LocationService.resetFailures` now
also sets `enabled = true` when the place carries a disabled reason (only the auto-disable writes
one), and leaves a place an admin disabled by hand (no reason) disabled, clearing only its counters.

On the Locations screen, the alert list now includes any place with a disabled reason even when its
counter is 0, printing the reason and the time of the last failure, and an auto-disabled place's row
carries an "auto-disabled" badge with the reason in its tooltip.
