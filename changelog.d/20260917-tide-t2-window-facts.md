### Added — the map tab tide strip's window facts (T2)

`BriefingWindowTide` (the Plan tab's per-window tide rollup, served on `GET /api/briefing`) gains
four additive fields the tide-window increment's strip chart needs and the existing sparkline did
not: `sunrisePosition`/`sunsetPosition` (where the representative coastal location's own sunrise and
sunset fall through the local day, 0.0–1.0 on the same axis as `windowPosition` — computed for both
events regardless of which one this window itself is, and null when `SolarService` reports no
sunrise or sunset for that day, the same defensive read `NlcTwilightWindowCalculator` already gives
the identical call), `extremes` (every high and low water in the representative's local day, each
positioned on that axis and timed on the Europe/London clock — real stored extremes only, never a
bracketed or gap-filled shape point), and `heightAtWindow` (the interpolated height at the window's
own instant, in metres — `windowLevel` restated as a real measurement rather than a normalised
position, for the chart's height label).

All four are `@JsonInclude(NON_NULL)` and computed at serve time inside `WindowTideRollupBuilder`
from data `rollup` already fetches — no new query, no migration, no client fetch (every curve in
this app is a Europe/London day; the client must never draw one from the UTC-day `/api/tides`
endpoint). A new legacy twelve-field constructor keeps every existing `BriefingWindowTide` call site
compiling unchanged, the same "unknown, not synthesised" convention `BriefingSlot.TideInfo`'s own
legacy constructor already uses.

No browser-visible change — nothing yet renders these fields; a later phase draws the strip itself.

Backend only. `WindowTideRollupBuilderTest` gains a nested `WindowFacts` suite (positions monotone
with clock time; an extreme at 23:59 never crosses 1.0; a spring-forward day still yields the
class's own 1440-unit axis; `heightAtWindow` at a genuinely interior point, not just a value that
happens to coincide with the nearest extreme; both solar-null guards independently); every
pre-existing assertion in that file is unchanged. `DailyBriefingResponseJsonTest` gains a full
`DailyBriefingResponse` round-trip and a legacy-shape deserialisation test for the four new fields.
