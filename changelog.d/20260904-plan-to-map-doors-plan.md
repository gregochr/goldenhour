### Docs — doors from Plan to Map: implementation plan, with the design increment vendored

`docs/engineering/plan-to-map-doors-plan.md` is the port plan for the design bundle's
`INCREMENT_plan_to_map_doors.md` (three doors from the Plan tab into the Map tab, the handover
payload, the breadcrumb, and the origin rule), cut into six single-session phases with kickoff
prompts in `plan-to-map-doors-prompts.md`. Its §1 records where the codebase has already moved past
the increment: the Map tab already receives the shared origin and reads drive times through one
accessor (so step one is closing four reads that bypass it, not writing `driveOf`), the home marker
and reach rings still draw round home under an away origin, the location sheet's `◍ Show on map →`
is wired to the frozen overlay rather than unwired, the nonce-guarded `mapTabHandoff` channel
already exists, and a window's identity is `date:targetType` with `findEvIndex` on the map's side.
The increment's two open questions and one new one (re-pointing the sheet footer) are recorded as
owner decisions with defaults. Bundle rev 3 (`plan-tab-v5.js`, `map-tab-v2.js`, both HTML pages, the
README's panel-ink section and the two new files) is vendored verbatim under `docs/design/map-tab-v2/`.
