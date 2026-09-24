### Added — per-location lunar eclipse dawn/dusk race sight (backend, L2)

Phase L2 of `docs/engineering/lunar-eclipse-plan.md`: `BriefingSlot.eclipse` — a nullable,
`@JsonInclude(NON_NULL)` `EclipseSight` record (deliberately **not** `@JsonUnwrapped`, unlike
`TideInfo`, so it nests as its own JSON object rather than flattening eleven more keys onto an
already-large slot) carrying the moon's altitude and bearing at maximum, the eclipse's own
unclipped umbral span (`umbraStart`/`umbraEnd` — the eclipse's `u1`/`u4`, never the per-location
`LunarEclipseSight.visibleUmbraStart/End`, so the popup's race geometry can still derive its own
umbra and hatch bands from the full span in a later phase), moonset/moonrise,
`setsInShadow`/`risesInShadow`, a `race` field (`"DAWN"`/`"DUSK"`/null) and four `LightStop`s keyed
with the frontend's `MastheadLight.RULE_COLOURS` strings. All times are London-local
`LocalDateTime`, the slot's `solarEventTime` convention.

A new `EclipseSightAssembler` (@Component, mirroring `TideExtremeRepository`'s own direct-dependency
shape in `BriefingSlotBuilder`) attaches the sight at the same build-time seam `TideInfo` rides,
only when the built date carries a catalogued `LunarEclipseCatalog` entry **and** that eclipse's own
window matches the slot's `TargetType` — the identical rule `LunarEclipseHotTopicStrategy` uses for
the topic pill, now extracted to a shared `LunarEclipseWording.eventType` so the topic and the
per-location sight can never disagree about which of a day's two windows an eclipse belongs to.
`race` is `DAWN` when the eclipse is a SUNRISE one and its umbral end falls after nautical dawn
minus an hour (the sky brightening while the Moon is still in shadow), `DUSK` for the SUNSET
mirror, else null — a high, leisurely eclipse races nothing.

**Simulation parity**: when `HotTopicSimulationService` has `LUNAR_ECLIPSE` active, every location's
SUNRISE slot on "today" (`ForecastHorizon.today`, gated like the aurora admin simulation) carries
the real 2026-08-28 Dunstanburgh reduction re-dated onto that day — the only way the dawn race can
be seen in a browser before the next live eclipse enters a forecast window, documented in
`EclipseSightAssembler`'s own javadoc as a verification affordance, never a product path. A real
catalogued eclipse always wins over simulation (the real branch short-circuits before the
simulation service is ever consulted).

`BriefingSlot` gains a legacy 17-argument constructor so every existing call site (production and
~100 test call sites) keeps compiling with `eclipse` defaulted to null; `withEvaluationGate` and the
6-argument `withClaudeScores` were updated to thread the field through explicitly rather than
through that legacy form, so a slot that already carries an eclipse sight cannot silently lose it
the next time Claude's rating or the evaluation gate is attached later in the pipeline — the same
"a wither is where a field quietly goes missing" defect class this file's own comments already
document for `evaluationGate`.

Tests: `EclipseSightAssemblerTest` (attachment gating on the eclipse's own window vs the day's other
event type; the unclipped umbra span; the four light-stop keys for both SUNRISE and SUNSET; the
DAWN/DUSK/null race boundary pinned at the exact 60-minute edge on **both** sides; simulation parity
including that a real eclipse wins without ever touching `HotTopicSimulationService`), new
`BriefingSlotTest.EclipseTests` (withers preserve/clear the field in isolation), a new
`BriefingSlotBuilderTest` nested class proving the seam wiring end to end (plus a woodland-routing
test proving a canopy slot never even reaches the seam), new `LunarEclipseWordingTest` coverage for
the extracted `eventType`/`toLondonLocal` helpers (including a BST-vs-UTC-hour boundary case), and a
legacy-cache-row deserialisation test plus a real Jackson-3 `JsonDateFormatContractTest` wire-format
check (`GET /api/briefing`, reaching into the nested `eclipse.stops[]` array) — the hand-built
Jackson 2 mapper in `DailyBriefingResponseJsonTest` deliberately asserts no raw date strings,
consistent with CLAUDE.md's two-Jackson-graphs warning.

Adversarial review of this diff found four real issues, all fixed before landing: (1) simulation
could have fired a fabricated sight on a date that already carries a real catalogued eclipse, on
whichever of that day's two windows the real eclipse itself does *not* claim — `forSlot` now
requires no real entry on the date at all, not merely a window mismatch, before considering
simulation; (2) the simulated path's `retime()` re-dates a fixed template by time-of-day alone, with
no next-day carry — safe only because 2026-08-28's whole contact chain is same-day, so a defensive
ordering check now fails loudly rather than silently misordering a sight if a future phase ever
repoints the template at a date that crosses midnight; (3) the hot-topic type string
`"LUNAR_ECLIPSE"` was three independent literals across `EclipseSightAssembler`,
`LunarEclipseHotTopicStrategy` and `HotTopicSimulationService` with no compile-time link — the
first now reads `LunarEclipseHotTopicStrategy.TYPE` (newly extracted) instead of its own copy, and a
new `HotTopicSimulationServiceTest` case bridges the third; (4) the DUSK side of the race boundary
had no boundary test at all (only DAWN's two edges were pinned) — three more tests now cover DUSK's
exact edge, one minute inside it, and its own null case.

Frontend: none this phase (L4 mounts the popup's dawn race component against this data).
