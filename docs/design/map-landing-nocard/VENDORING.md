# What is in here, and what is deliberately not

Vendored verbatim from the owner's `PhotoCast Map Landing.zip` / `design_handoff_map_landing_nocard/`,
2026-10-25. **Do not edit these files.** Where they and the codebase disagree,
`docs/engineering/map-landing-nocard-plan.md` §1 (corrections) and §4 (decisions) win.

| file | what it is |
|---|---|
| `README.md` | **The specification.** Remove the landing card; add the "is better" line to the window pill. |
| `CLAUDE_CODE_PROMPT.md` | The designer's working order. Superseded as an order by the plan's §3 phases. |
| `Map Landing v2.html` + `map-tab-v4-nocard.js` | The prototype. See `betterNext()` and `paintWin()` for the rule. `S.demo` and the "Walk it" buttons are demo-only and are **not** ported. |

**Two files from the bundle are not copied here, on purpose.** `heat-field.js` and `plan-data.js` ship in
the bundle only so the prototype runs, and both are byte-identical to the copies already vendored at
`docs/design/hot-topics-by-day/` (checked with `cmp` at vendoring time). To run the prototype, copy them
in from there.

The earlier spec this one amends is `docs/design/map-landing/`; everything it says that this README does
not remove still holds.
