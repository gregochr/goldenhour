# What is in here, and what is deliberately not

Vendored verbatim from the owner's `PhotoCast Spread Control.zip` / `design_handoff_spread_row/`,
2026-10-15. **Do not edit these files.** Where they and the codebase disagree,
`docs/engineering/spread-row-plan.md` §1 (corrections), §4 (deliberate disagreements) and §5
(decisions) win.

| file | what it is |
|---|---|
| `README.md` | **The specification.** Sizes, colours, copy and the count format are intended to be matched exactly, except where the plan's §1/§4/§5 record why this codebase already answers the question differently (the well, the sixth hatched bar, the minimum-sample gate, the reach-measured rule, derived tier labels). |
| `CLAUDE_CODE_PROMPT.md` | The designer's working order. Superseded as an *order* by the plan's §3 phases; kept because its five steps and the "fake 999 / 1.2k on the narrowest card" check are the designer's own account of what will bite. |
| `Plan Tide Summary.html` + `plan-tide-v6.js` | The design, as a working prototype. **Design reference, not production code.** The Spread row is `plan-tide-v6.js:140` (`RSHORT`), `:208–218` (`fmtN`, `histOf`) and `:467` (the row inside `card()`); the CSS is `Plan Tide Summary.html:242–266` (`.pls`, `.sprow`, `.k2.sk`, `.hist`, `.hb`) and `:202` (the "sideways" grid rows). The rest of both files is the tide-plan prototype this one was built on (`docs/design/tide-plan/`), already ported. |
| `screenshots/01–03` | The three states README §Screenshots names: reach 45 min (single-digit counts), reach 2h30 (two-digit counts), reach Any (`(any drive)`). |

**Two files from the bundle are not copied here, on purpose.** `heat-field.js` is **byte-identical**
to `docs/design/aurora-heat/heat-field.js` and `plan-data.js` to `docs/design/map-tab-v2/plan-data.js`
(verified with `cmp` at vendoring time). To run the prototype, copy them in from there.

⚠️ The prototype's histogram has no dark well, no sixth hatched "not yet rated" bar and no
minimum-sample gate — all three exist in the codebase (plan-matrix-plan.md A27, 2026-09-29) and are
kept; the plan's §4 says how the spec's counts and label are laid over them.
