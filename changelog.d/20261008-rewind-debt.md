### Changed — Rewind: the debt it introduced, paid

Four pieces of the admin Rewind (#998) were written beside code that already did the same job,
and one of its rules lived in the wrong place. The forecast serve window's past edge is now one
constant, `ForecastHorizon.SERVE_PAST_DAYS`, read by `GET /api/forecast`, the scores endpoint and
the Rewind menu alike (it was `ForecastController.PAST_WINDOW_DAYS` with a second copy on
`RewindEventService`, a service reading a controller's constant). The `ROLE_` authority test the
JWT filter's convention implies is one helper, `Authorities.hasRole`, shared by the Rewind filter's
ADMIN check and `ForecastController`'s LITE check. `RewindFilter` is registered once, inside the
security chain, with its servlet-container registration disabled outright rather than kept second
by bean ordering, and a test pins it. The `ROLE_` prefix itself is one constant,
`Authorities.ROLE_PREFIX`, where the JWT filter, `AppUserEntity` and the status endpoint each
spelt it out. The three-day bound on a rewind is `Rewind.MAX_AGE` and is served to the Rewind view
as `maxAgeDays`; the view's own `3` survives only as the fallback while the events are loading or
after a failed load. On the client, the "never read or write the SWR cache while rewound" rule moved
into `swrCache` itself, where the data lives, instead of a guard at each consumer; the rewind's UK
formatters became `conversions.formatDayClockUk` beside the other UK formatters (the clock was
already `formatEventTimeUk`); `briefingDisplay`'s private London calendar reads became
`mapDates.ukDateStr`/`ukHour`; and the Operations Rewind view no longer repeats the bar the page
already shows above it. No behaviour changes.
