### Docs — Map tab on a phone: the peek sheet, planned

The owner's `design_handoff_map_mobile_sheet` bundle is vendored at `docs/design/map-mobile-sheet/`
(spec, prototype, six screenshots, `VENDORING.md`), and `docs/engineering/map-mobile-sheet-plan.md`
is the port plan: sixteen places the codebase already differs from the spec (`BottomSheet.jsx`
cannot be the sheet; the phone "bottom bar" is CSS on `.wf-map-chrome-tr`; the toast has no
timer; every tide cue already keys off one `tideTier` field), six single-session phases M1–M6, the
deliberate disagreements, the decisions taken, and the README's Verify list mapped to measurements.
`docs/engineering/map-mobile-sheet-prompts.md` carries one kickoff prompt per phase. No code.
