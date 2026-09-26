# What is in here, and what is deliberately not

Vendored verbatim from the owner's `PhotoCast.zip` / `design_handoff_map_mobile_sheet/`, 2026-09-25.
**Do not edit these files.** Where they and the codebase disagree,
`docs/engineering/map-mobile-sheet-plan.md` §1 (corrections), §4 (deliberate disagreements) and
§5 (decisions) win.

| file | what it is |
|---|---|
| `README.md` | **The specification.** Every hex, size, copy string and rule in it is intended to be matched, except where the plan's §4 records a disagreement on purpose. |
| `CLAUDE_CODE_PROMPT.md` | The designer's working order. Superseded as an *order* by the plan's §3 phases; kept because its build order and its "Verify" list are the acceptance test. |
| `Map Mobile Minimised.html` | The design, as a working Leaflet prototype showing two options side by side. **Design reference, not production code** — build **Option A only** (`makePhone('pa','a')`, the `variant==='a'` branches, the `.sh` / `.pk` / `.pb` / `.pane` CSS). Option B (`#pb`) is the rejected alternative and is here as context. |
| `screenshots/01–06` | The six states the spec names, at the 390 × 844 reference frame. |

The prototype's spots, scores and tide extremes are mock data; the tide curve in it is a cosine
interpolation over mock extremes. **Nothing in it is a model to port** — the app already serves the
tide level, bands and fit (`TideCurveCalculator` / `TideWording`), and `utils/mapTideFit.js` already
holds the in-view gate. What is new is the sheet, the visibility rule's verdict clause, the Tide mode
and the toast's fade; see the plan.
