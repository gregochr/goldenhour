### Docs — the lunar eclipse port plan and its design bundle

`docs/engineering/lunar-eclipse-plan.md` plans the extension of the `ECLIPSE` almanac topic to
lunar eclipses, against the design handoff now vendored at `docs/design/lunar-eclipse/` (design of
record `Lunar Eclipse.html`; `VENDORING.md` records what was copied). Eight single-session Sonnet
phases with paste-ready prompts in `docs/engineering/lunar-eclipse-prompts.md`: a seeded
`LunarEclipseCatalog` of UTC contacts, a per-location sight from solar-utils' moon geometry, the
topic and almanac source under a distinct `LUNAR_ECLIPSE` type sharing the solar channel, the
sight on `BriefingSlot`, a clocked Plan-card chip, the popup's one new component (the dawn race),
the Coming up row, and an independent plain-language copy sweep that retires every user-facing
"bits" string.

The plan's §1 records where the spec is stale against this repo, and the defaults it takes in
consequence: the promoted strip the spec builds on was retired at plan-matrix M5 and is not
rebuilt (the evening-before need is met by the card and the popup); the `clearToDeg` horizon score
it reuses has never existed, so every clear-to-the-west surface is dropped as the solar handoff
dropped it, not faked; and the worked example (28 Aug 2026) is already past, so verification runs
on the simulation template. Nine owner decisions are listed in §6 with their costs. Documentation
only; no behaviour change.
