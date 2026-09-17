# What is in here, and what is deliberately not

Vendored verbatim from the owner's `PhotoCast.zip` / `design_handoff_tide_window/`, 2026-09-17.
**Do not edit these files.** Where they and the codebase disagree, `docs/engineering/tide-window-plan.md`
§1 (corrections), §4 (deliberate disagreements) and §5 (decisions) win.

| file | what it is |
|---|---|
| `README.md` | **The specification.** Every hex, threshold, copy string and layout constraint in it is intended to be matched, except where the plan's §1/§4/§5 record why this codebase already answers the question differently. Its §7 rejection (do not shade the sea) is load-bearing. |
| `CLAUDE_CODE_PROMPT.md` | The designer's working order. Superseded as an *order* by the plan's §3 phases; kept because its ten build steps and eight checks are the designer's own account of what will bite. |
| `Map Tide Window.html` + `map-tide-v5.js` | The design, as a working Leaflet prototype. **Design reference, not production code** — the notes column down the right of the HTML is part of the spec. The tide model sits at `map-tide-v5.js:84–176` (`TIDEX`, `LVL`, `bandOf`, `tideFitOf`, `nextFitEv`, `tideBlock`), the strip at `:1207–1293` (`tideChart`, `renderTide`), the strip and chip CSS at `Map Tide Window.html:538–584`. |
| `screenshots/01–04` | The four states the README §11 names. The only visual record of the design; the HTML renders them live. |

**Two files from the bundle are not copied here, on purpose.** `heat-field.js` and `plan-data.js`
ship in the bundle only so the prototype runs, and both are **byte-identical** to the copies already
vendored at `docs/design/map-tab-v2/heat-field.js` and `docs/design/map-tab-v2/plan-data.js`
(verified with `cmp` at vendoring time). To run the prototype, copy them in from there.

The kernel is unchanged from the Map tab v2 bundle. **Do not re-port or re-tune it.**

⚠️ The prototype's tide model (`TIDEX`, the `HALFC = 372.5` cosine anchor, the `WANT` table, the
`0.75/0.25` bands and the `0.80/0.62` fit tiers) is scaffolding the README itself says not to port
(§1 "In production you do not need the cosine anchor"). The plan goes further: this codebase's tide
axis is already **time-based** (`TideService.classifyTideState`, `TideFactDeriver`,
`BriefingGatingPolicy`, `TideVisitor`) and pinned across surfaces by `TideSurfaceAgreementTest`, so the
level-based fit is not ported at all — plan §1 #2 and §4 #2 say why.
