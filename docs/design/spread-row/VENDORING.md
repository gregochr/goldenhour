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

**Final count, S3 sweep (2026-10-15):** the port plan's §4 "Disagreements with the spec, on purpose"
closed at **14 entries** once every phase (S0–S3) had shipped — the original 10 written before any
code existed (#1–#10), plus #11 (S2: under `.sm` the well's side padding and 2px of the hatched gap
are given back, because the real stacked line is 112px at a 320px viewport, not the 116 the plan
assumed), #12 (S1: `formatSpreadCount` rounds tenths half-up in integers, where the prototype's
`toFixed(1)` prints 1150 as "1.1k") and #13 (S1: `spreadQualifier` treats an `undefined` threshold as
silence) and #14 (S2: the 205px stacking threshold is kept with its new slack). ⚠️ The prototype's
layout could not be applied as-is (plan §1 #7): the spec sizes its worst case on a 177px `.pls`,
and this card's value grid is 112–169px wide at eight of its ten measured shell geometries, so the
row needs a second, stacked state the prototype never had.
