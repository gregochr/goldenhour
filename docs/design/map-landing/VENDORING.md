# What is in here, and what is deliberately not

Vendored verbatim from the owner's `PhotoCast.zip` / `design_handoff_map_landing/`, 2026-09-05.
**Do not edit these files.** Where they and the codebase disagree, `docs/engineering/map-landing-plan.md`
§1 (corrections), §4 (deliberate disagreements) and §5 (decisions) win.

| file | what it is |
|---|---|
| `README.md` | **The specification.** Every hex, threshold, copy string and layout constraint in it is intended to be matched, except where the plan's §4 records a disagreement on purpose. |
| `CLAUDE_CODE_PROMPT.md` | The designer's working order. Superseded as an *order* by the plan's §3 phases; kept because its emphases (which checks caught which defect) are evidence. |
| `Map Landing.html` + `map-tab-v4.js` | The design, as a working Leaflet prototype. **Design reference, not production code** — the notes column down the right of the HTML is part of the spec, and its rejections are load-bearing. |
| `Map Verdict Options.html` + `map-tab-v3.js` | The three-way comparison this was chosen from. Context and rejected alternatives; **build only option A**, which is what `Map Landing.html` is. |

**Two files from the bundle are not copied here, on purpose.** `heat-field.js` and `plan-data.js`
ship in the bundle only so the prototype runs, and both are **byte-identical** to the copies already
vendored at `docs/design/map-tab-v2/heat-field.js` and `docs/design/map-tab-v2/plan-data.js`
(verified with `diff` at vendoring time). A second copy would be a second thing to keep in step for
no gain. To run the prototype, symlink or copy them in from there.

The kernel is unchanged from the Map tab v2 bundle. **Do not re-port or re-tune it.**
