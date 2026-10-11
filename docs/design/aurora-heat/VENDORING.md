# What is in here, and what is deliberately not

Vendored verbatim from the owner's `PhotoCast Aurora Heat.zip` / `design_handoff_aurora_heat/`,
2026-10-11. **Do not edit these files.** Where they and the codebase disagree,
`docs/engineering/aurora-heat-plan.md` §1 (corrections), §4 (deliberate disagreements) and §5
(decisions) win.

| file | what it is |
|---|---|
| `README.md` | **The specification** — three tasks: aurora as a heat field with its own ramp, a dismiss on the best-location card, and the viewline investigation. Every hex, ramp stop and copy string is intended to be matched, except where the plan's §4 records a disagreement on purpose. ⚠️ Its "Current state" and "What the frontend tells us" paragraphs describe the codebase from memory and **six of their claims are wrong** — the plan's §1 lists each with the line that disproves it. The *design* is unaffected; do not build against the README's picture of the code. |
| `Map Aurora Heat.html` + `aurora-map.js` | The design, as a working Leaflet prototype. **Design reference, not production code.** The prose above and the notes below the map are explanation, not UI. `aurora-map.js`'s `ASTOPS`/`auroraRamp` is the constant to port; its `score()` is a **mock** and must not be ported (real scores are served). The `Aurora ramp / Shared ramp` segment is for review only and does not ship. |
| `heat-field.js` | The shared kernel as the bundle ships it. It differs from `docs/design/map-tab-v2/heat-field.js` by **exactly two lines** (verified with `diff` at vendoring time): the bloom colour `255,138,66` becomes `const bc=opts.bloomRGB||[255,138,66]` and is written through `bc`. That is the whole kernel change this bundle asks for. ⚠️ The in-repo kernel is `frontend/src/utils/heatField.js`, which has **already diverged** from both vendored copies (it reads its ramp from `utils/scoreRamp.js`'s module state and has no `opts.ramp` at all) — the plan's A1 says what to change there. |

**One file from the bundle is not copied here, on purpose.** `plan-data.js` ships only so the
prototype runs and is **byte-identical** to `docs/design/map-tab-v2/plan-data.js` (verified with
`diff`). To run the prototype, copy it in from there.
