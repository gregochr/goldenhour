# Heat-scale series — per-stage kickoff prompts

Paste the stage's block into a **fresh Claude Code session** from the repo root. Each is
self-contained: it names its own reading list and traps, so no session needs another's history.
The plan is `docs/engineering/heat-scale-unification-plan.md`; update its Status line in the same
commit as the work.

---

## Stage 1 — Two ramps in one module, no visual change ✅ LANDED (#633)

> **Kept for the record. Its "Trap 3" below was WRONG and is corrected here.** `RAMP_STOPS` was
> imported by **two production files** (`MapView.jsx`, `WindowFirstHeatStrip.jsx` — both build a
> legend gradient from the stop list) plus **five** test files, not by three test files and no
> production code. The claim came from a `grep | head` whose truncation was not noticed. The
> implementing session's import-consistency lens caught it. **Lesson for every prompt below: a
> truncated grep is not a survey.**


> You are implementing **Stage 1** of `docs/engineering/heat-scale-unification-plan.md`. Read that
> plan's §1, §2 and Stage 1 before writing anything. Do not read the design bundle at
> `docs/design/temperature-scale/` as an instruction set — §2 of the plan lists five places where
> it is stale against this tree, and Stage 1 is one of them.
>
> **Goal.** `frontend/src/utils/scoreRamp.js` learns a second stop list and a mode, so a later
> stage can switch the app between a verdict ramp and a temperature ramp from one place. This
> stage changes **no pixels**: the mode defaults to the ramp that ships today.
>
> **⚠️ Trap 1 — the design brief names the wrong module.** Its "Change 1" says to edit the kernel,
> `frontend/src/utils/heatField.js`. That file contains no stop list; it imports `rampRgb` from
> `scoreRamp.js`, where `RAMP_STOPS` lives at line 29. Edit **`scoreRamp.js`**. Creating a second
> ramp definition in `heatField.js` is the exact failure this whole series exists to prevent.
>
> **⚠️ Trap 2 — the reference kernel's stop format is not this app's.** The kernel writes
> `[[1,[58,92,112]], ...]`; `scoreRamp.js` uses `[{ score, hex }, ...]` and precomputes
> `STOP_RGB` from it at module level. Keep the app's shape. The converted values are given below —
> do not convert them by hand.
>
> **⚠️ Trap 3 — `RAMP_STOPS` is imported by three test files.** `test/scoreRamp.test.js`,
> `test/MarkerIcon.test.jsx` and `test/WindowFirstHeatStrip.test.jsx`. Renaming the export breaks
> all three; update their imports. Their assertions stay correct unchanged, because the default
> mode keeps returning today's colours.
>
> **The change.**
>
> 1. Rename `RAMP_STOPS` to `STOPS_VERDICT`. Its five hexes are already byte-identical to the
>    reference kernel's `STOPS_VERDICT` — verified, so this is a pure rename with no colour drift.
> 2. Add `STOPS_TEMP`, exactly these eight stops, in this order:
>
>    ```js
>    { score: 1,   hex: '#3A5C70' },
>    { score: 2.2, hex: '#506878' },
>    { score: 2.8, hex: '#928C80' },
>    { score: 3,   hex: '#C49440' },
>    { score: 3.2, hex: '#C99230' },
>    { score: 3.9, hex: '#DF6B2A' },
>    { score: 4.3, hex: '#D63A26' },
>    { score: 5,   hex: '#F26034' },
>    ```
>
>    **The uneven spacing is load-bearing — do not regularise it.** Regional means occupy roughly
>    1.9–4.6, so evenly spaced stops spend the blue and the red on values that never survive the
>    blur and render every night the same orange. `2.2` is held dark so a marker label clears
>    4.5:1 against it. `3` exists as its own stop because `rating` is an integer and 3★ is the
>    commonest value; interpolating 2.8→3.2 put it on a dun khaki.
> 3. Add a module-level `MODE` with `setMode(m)` and `getMode()`. **`MODE` defaults to
>    `'verdict'`.** `setMode` accepts `'temp'` or `'verdict'` and treats anything else as
>    `'verdict'` — an unknown value must never silently select the not-yet-shipped ramp.
> 4. Route `rampRgb` through the active list. `STOP_RGB` is precomputed at module level today;
>    you now need one precomputed array per list, selected by mode — not a recompute per call.
> 5. Add `scoreFromPercent(value, lo, hi)`: maps a 0–100 metric onto the ramp's 1–5 score
>    domain — `1 + clamp((value - lo) / (hi - lo), 0, 1) * 4`. It returns a **number, not a
>    colour**: `lo` gives `1`, `hi` gives `5`, clamped outside. Callers compose it as
>    `rampHex(scoreFromPercent(v, lo, hi))`. ⚠️ The reference kernel calls this `rampPct` and
>    returns a colour from it — this app keeps domain-mapping and colour-lookup separate, because
>    `rampHex` / `rampRgb` already take a score. The different name is deliberate; do not
>    "restore" the kernel's. Nothing calls it yet; Stage 5 does.
>
> **Preserve exactly, all of it load-bearing and all of it commented in the file:**
>
> - `rampRgb`'s non-finite guard. A `NaN`/`undefined`/missing score resolves to the **bottom** of
>   the ramp, never the top. `clamp` alone does not do this and the existing comment explains why —
>   an unknown reading must never render as the best one.
> - The segment search that walks rather than falling out of a loop.
> - `rampHex`'s upper-casing and zero-pad.
> - `RAMP_MIN` / `RAMP_MAX` stay exported — `utils/windowFirstSpread.js` imports them. Both lists
>   span 1–5, so their values do not change.
> - `heatField.js`'s re-export of `rgba`.
>
> **Signatures of `rampHex`, `rampRgb` and `rgba` do not change.** Nothing downstream of the ramp
> changes in this stage. If you find yourself editing the blur, the kernel geometry or the canvas,
> stop — you have strayed.
>
> **Tests** (`frontend/src/test/scoreRamp.test.js`):
>
> - Every whole star returns today's colour in the default mode — this is the zero-visual-change
>   proof and the most important assertion in the stage.
> - `setMode('temp')` then each whole star returns `#3A5C70`, `#4C6677`, `#C49440`, `#DD5F29`,
>   `#F26034`. Note 2★ and 4★ are **interpolated**, not stops — that is what pins the uneven
>   spacing.
> - An unknown mode string falls back to verdict.
> - Both modes clamp outside 1–5 and both send a non-finite score to the bottom.
> - `scoreFromPercent(lo, lo, hi)` is the **number** `1` and `scoreFromPercent(hi, lo, hi)` is `5`,
>   clamping beyond both. Assert `toBe(1)` / `toBe(5)` — if you find yourself asserting a hex
>   string here, the function is returning the wrong type.
> - Restore the default mode between tests — `MODE` is module state and will leak across cases.
>
> **Gates, all four, before you push** (the audit step is the one nothing else runs and it has
> already cost a CI round):
>
> ```
> cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build
> ```
>
> **Before committing**, run an adversarial review of the diff — this project's standing rule for
> anything touching the UI (CLAUDE.md, "UI Work — Review Cadence"). Tell review agents to read
> only; a reviewer that mutates the tree can destroy unstaged work.
>
> **Commit and PR.** Conventional commit (`feat:`). Add a `CHANGELOG.md` entry under
> `[Unreleased]` — every PR does, so expect a conflict there and rebase rather than merge. Do
> **not** push until asked. Update the plan's Stage 1 line to record what landed.

---

## Stage 2 — Tokens and legend

> You are implementing **Stage 2** of `docs/engineering/heat-scale-unification-plan.md`. Read that
> plan's §1, §2 and Stage 2 first. Stage 1 landed in #633 — `frontend/src/utils/scoreRamp.js`
> already exports `STOPS_VERDICT`, `STOPS_TEMP`, `setMode`, `getMode` and `scoreFromPercent`, and
> `MODE` defaults to `'verdict'`. Do not re-do Stage 1, and do not flip the default: that is
> Stage 7.
>
> **Goal.** Two things: give `index.css` the five discrete heat tokens, and make **both** ramp
> gradients follow the active mode instead of hard-coding the verdict stops.
>
> **⚠️ Trap 1 — there are TWO gradient sites and they are not alike.**
>
> - `components/MapView.jsx:2137` — the map legend, built **inline during render** from
>   `STOPS_VERDICT`. Re-renders, so it only needs its source changed.
> - `components/WindowFirstHeatStrip.jsx:78` — `RAMP_GRADIENT`, a **module-level `const`
>   evaluated once at import**. Its own doc comment says "Computed once at module load — it
>   depends on nothing that changes." That was true before Stage 1 and is **false now**: it
>   depends on `MODE`. Left as a module constant it will keep painting the verdict ramp forever
>   after Stage 7 flips the default, and **nothing will fail** — no test, no lint, no build.
>   Make it a function (or move it into the render) and **rewrite that comment**; a stale comment
>   asserting the old invariant is exactly how this defect comes back.
>
> **⚠️ Trap 2 — `--color-heat-sea` already exists and is unrelated.** It is the kernel's sea
> colour from the heat-field series. Do not touch it, and do not assume a `--color-heat-*` grep
> hit means the new tokens are already there.
>
> **⚠️ Trap 3 — do not touch `--color-verdict-*`.** Those are saturated web colours for verdict
> *words* and have nothing to do with the muted ramp, despite older handoff notes saying otherwise.
> Note `index.css` already carries a comment near them, added in #627 and updated by Stage 1, which
> explains that three of them double as verdict-ramp stops — read it before editing that block.
>
> **The change.**
>
> 1. Add to `index.css`, beside the verdict tokens rather than in a new block of their own:
>
>    ```css
>    --color-heat-1: #3A5C70;
>    --color-heat-2: #4C6677;
>    --color-heat-3: #C49440;
>    --color-heat-4: #DD5F29;
>    --color-heat-5: #F26034;
>    ```
>
>    These are `STOPS_TEMP` sampled at **whole stars**, for discrete uses; the field itself
>    interpolates and calls the ramp directly. ⚠️ **2★ and 4★ are interpolated points, not stops** —
>    you will not find `#4C6677` or `#DD5F29` in `STOPS_TEMP`, and that is correct. If you verify
>    them, verify by calling `rampHex(2)` / `rampHex(4)` with the mode set to `'temp'`, not by
>    grepping the stop list.
> 2. Both gradient sites read the **active** stop list rather than `STOPS_VERDICT`. `scoreRamp.js`
>    does not export an "active stops" accessor today — add one there (one line beside `getMode`)
>    rather than letting each call site branch on `getMode()` itself. Two call sites branching
>    independently is the same duplication this series exists to remove.
> 3. The legend's words — `poor → worth it` — **do not change on either scale.** The bar carries
>    the metaphor; the words carry the meaning.
>
> **This stage still changes no pixels** while `MODE` is `'verdict'`: both gradients must render
> byte-identically to today. That is the strongest assertion available to you — write it.
>
> **Tests.**
>
> - Both gradients are unchanged from today's output in the default mode.
> - After `setMode('temp')`, both gradients change **and agree with each other** — the point of
>   the stage is that Plan and Map cannot disagree about what a colour means.
> - ⚠️ A test that only asserts the *string changed* will pass even if `RAMP_GRADIENT` is still a
>   module constant, because module state is captured per test file. Assert the actual expected
>   temperature colours, and make at least one test set the mode **after** the module is imported
>   — that is what proves the value is not frozen at import time.
> - Reset the mode in `afterEach`; `MODE` is module state and leaks across cases.
>
> **Gates, all four, before you push:**
>
> ```
> cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build
> ```
>
> **Before committing**, run an adversarial review of the diff (CLAUDE.md, "UI Work — Review
> Cadence"). Tell review agents to read only — a reviewer that mutates the tree can destroy
> unstaged work. Given Trap 1, ask one lens specifically: *would this still be correct after
> `setMode('temp')` at runtime, not just at import?*
>
> **Commit and PR.** Conventional commit (`feat:`). Add a `CHANGELOG.md` entry under
> `[Unreleased]` — expect a conflict there and rebase rather than merge. Do **not** push until
> asked. Update the plan's Stage 2 section to record what landed, including anything this prompt
> got wrong.
