### Changed — health status no longer re-renders the whole app every tick

The health stream's 30-second tick used to re-render `App` and everything under it for every role. Health now lives in its own `HealthStatusProvider`: the admin pill reads the full value (probe time included), while `App` reads only an `isDown` boolean, so a tick that changes nothing but `checkedAt` re-renders just the pill. The down banner, `contentDisabled`, recovery on tab wake, the expired-token session probe and the deferred stream start are unchanged.
