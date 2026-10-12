# What is in here, and what is deliberately not

Vendored verbatim from the owner's `PhotoCast Aurora Alert Banner.zip` /
`design_handoff_aurora_alert_banner/`, 2026-10-12. **Do not edit these files.** Where they and the
codebase disagree, `docs/engineering/aurora-banner-plan.md` §1 (corrections) and §4 (decisions)
win.

| file | what it is |
|---|---|
| `README.md` | **The specification** — one component, two layouts: a banner with a 96×118 aurora-field thumbnail (Kp + a nine-step meter until per-location scores exist) at 640px and up, and a 56px glow strip below it; severity on the aurora ramp instead of amber/red. Every hex, size and copy string is intended to be matched, except where the plan's §4 records a disagreement on purpose. ⚠️ Its picture of the code is approximately right; the plan's §1 lists each place it is not (the kernel dependency has landed; the ramp's stop 3 is `#1F7F78` by owner decision; the banner is mounted outside the provider that holds the planning area). |
| `Aurora Alert Banner.html` | The design, as a working HTML/JS reference. **Design reference, not production code.** The prose above each row is explanation, not UI. Its `score()` is a **mock** and must not be ported (real scores are served); its `ar()` ramp carries the spec's `#22867E` stop, which the repo's `utils/auroraRamp.js` deliberately does not. |

**Two files from the bundle are not copied here, on purpose.** `heat-field.js` is byte-identical
to `docs/design/aurora-heat/heat-field.js`, and `plan-data.js` to
`docs/design/map-tab-v2/plan-data.js` (both verified with `diff` at vendoring time). To run the
reference page, copy both in from there. The in-repo kernel is `frontend/src/utils/heatField.js`,
which already carries the `opts.ramp`/`opts.bloomRGB` hooks this bundle depends on.
