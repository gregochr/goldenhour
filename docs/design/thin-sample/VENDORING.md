# What is in here

Vendored verbatim from the owner's `PhotoCast.zip` / `design_handoff_thin_sample/`, 2026-10-22.
**Do not edit these files.** Where they and the codebase disagree, `docs/engineering/thin-sample-plan.md`
§1 (corrections) and §5 (decisions) win.

| file | what it is |
|---|---|
| `brief.md` | The brief sent to Claude Design (written in this repo, not part of the bundle). |
| `README.md` | **The specification** for the unknown-ground hatch, the verdict-line count and the legend. |
| `CLAUDE_CODE_PROMPT.md` | The designer's working order. Superseded as an order by the plan's §3 phases. |
| `PICK_ELIGIBILITY.md` | The companion backend brief (a forced sample sets its verdict but cannot take a pick). Plan phase B1. |
| `Thin Sample Field.html` + `heat-field.js` | The design canvas, as a working prototype. **Design reference, not production code.** The new layer is `unknownHatch()` in the HTML's inline script. It loads d3, topojson and the world atlas from CDNs. Its place catalogue is synthetic. |
| `screenshots/01–08` | Strip before/after (sunrise, sunset), phone card ×3, legend, Map tab zoomed out and in. |

⚠️ The prototype treats every place with no score as "not rated yet". The codebase has two more kinds of
place with no score — woods and hides (never sky-rated) and weather stand-downs (looked at, judged poor) —
and the plan's §1 #1–#2 say how each is drawn.
