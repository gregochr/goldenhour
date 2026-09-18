# What is in here, and what is deliberately not

Vendored verbatim from the owner's `PhotoCast.zip` / `design_handoff_tide_plan/`, 2026-09-17.
**Do not edit these files.** Where they and the codebase disagree, `docs/engineering/tide-plan-card-plan.md`
§1 (corrections), §4 (deliberate disagreements) and §5 (decisions) win.

| file | what it is |
|---|---|
| `README.md` | **The specification.** Every threshold, gate, hex and copy string in it is intended to be matched, except where the plan's §1/§4/§5 record why this codebase already answers the question differently. Its §5 (the cut `Tide` border legend, and "not folded into any score") is load-bearing. |
| `CLAUDE_CODE_PROMPT.md` | The designer's working order. Superseded as an *order* by the plan's §3 phases; kept because its eight steps and seven checks are the designer's own account of what will bite. |
| `Plan Tide Summary.html` + `plan-tide-v6.js` | The design, as a working prototype. **Design reference, not production code** — the notes column is part of the spec and its two `cut` notes are the reasoning. The tide model, gate and ranking sit at `plan-tide-v6.js:15–95` (`tideStats`, `tdShowOf`, `tideLive`, `bestTideWin`), the two marks inside `card()` at `:424–460`, the popup line at `:571`, the CSS at `Plan Tide Summary.html:493–503`. The `〜 Best tide, emphasised` toolbar toggle is a design control, not a feature. |
| `screenshots/01–03` | The three states README §8 names. |

**Two files from the bundle are not copied here, on purpose.** `heat-field.js` and `plan-data.js`
are **byte-identical** to `docs/design/map-tab-v2/heat-field.js` and `docs/design/map-tab-v2/plan-data.js`
(verified with `cmp` at vendoring time). To run the prototype, copy them in from there.

⚠️ The prototype duplicates the Map-tab tide model (`TIDEX`, `HALFC`, `WANT`, `tideFitP`) so it runs
standalone, and its README says so ("do not port it twice"). In this codebase that model is already
**time-based and served** — `BriefingSlot.TideInfo.tideAligned`, `tideState`, `nearestSolarOffsetMinutes`
(see `docs/engineering/tide-window-plan.md` §1 #2) — so nothing of it is ported at all; the plan's
§1 #1 and §4 #1–#2 say what replaces `fit`, `tier` and `meanFit`.

**Final count, C4 sweep (2026-09-18):** the port plan's §4 "Disagreements with the spec, on purpose"
closed at **10 entries** once every phase (C0–C3) had shipped — the original 8 written before any code
existed, plus #9 (C3's declined `heightAtWindow` popup fact) and #10 (the chip's tooltip states the
served window tide's state and direction only, never a height or a clock time) found true only once
the code was read back against this spec.
