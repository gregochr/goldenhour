### Added — lunar eclipse catalogue and per-location sight (backend, L0)

Phase L0 of `docs/engineering/lunar-eclipse-plan.md`: a static `LunarEclipseCatalog`
(`service/LunarEclipseCatalog.java`, the same static-table pattern as the solar `EclipseCatalog`)
seeding every umbral lunar eclipse from March 2025 through 2030 whose umbral phase is above the
horizon somewhere in the British Isles — seven entries, verified row by row against NASA's decade
lunar eclipse tables and eclipsewise.com's per-eclipse detail pages (the class javadoc records the
sources and the verification method). Three umbral eclipses in range (2026-03-03, 2028-07-06,
2030-06-15) were checked and excluded because the Moon never clears the astronomical horizon
anywhere in Britain during their umbral phase, and every penumbral eclipse is excluded outright.
The record's compact constructor enforces contact ordering and kind/magnitude agreement structurally,
so a transcription error fails at class-load time rather than shipping.

`util/LunarEclipseCalculator.java` (a `@Component`, following `SupermoonHotTopicStrategy`'s call
pattern for solar-utils' `LunarCalculator`/`MoonriseMoonsetCalculator`) computes the per-location
`LunarEclipseSight` — altitude/bearing at greatest eclipse, moonrise/moonset interaction with the
umbral span, and the 3°/30-minute visibility eligibility rule, sampling the Moon's altitude once a
minute across the span. A midnight-crossing umbral span is resolved by checking both the London
civil date of U1 and (when it differs) of U4.

No wiring into any live path this phase — the catalogue and calculator are unused outside their own
tests, per the plan's L0 scope.
