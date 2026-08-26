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

## Stage 2 — Tokens and legend ✅ LANDED (#637)

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
> 1. **First, correct the two hot stops Stage 1 shipped.** `STOPS_TEMP` in
>    `frontend/src/utils/scoreRamp.js` carries the pre-revision values; change them:
>
>    ```
>    { score: 4.3, hex: '#D63A26' }  ->  { score: 4.3, hex: '#DE4826' }
>    { score: 5,   hex: '#F26034' }  ->  { score: 5,   hex: '#C82820' }
>    ```
>
>    The ramp was revised on 2026-08-26 because 4.3★ read hotter than 5★ — luminance ran
>    0.264 → **0.175** → 0.275, a trough then a recovery. It now descends 0.264 → 0.203 → 0.139.
>    ⚠️ **Do not brighten `5` past `4.3`.** Gold at 3★ is already the ramp's brightest point, so a
>    bright top end gives a middling night and a great one the same visual weight; the top is the
>    ramp's *deepest* colour by design. Stage 1's tests assert the old whole-star colours for temp
>    mode and will fail — update them to `#3A5C70`, `#4C6677`, `#C49440`, `#DF6229`, `#C82820`.
> 2. Then add to `index.css`, beside the verdict tokens rather than in a new block of their own:
>
>    ```css
>    --color-heat-1: #3A5C70;
>    --color-heat-2: #4C6677;
>    --color-heat-3: #C49440;
>    --color-heat-4: #DF6229;
>    --color-heat-5: #C82820;
>    ```
>
>    These are `STOPS_TEMP` sampled at **whole stars**, for discrete uses; the field itself
>    interpolates and calls the ramp directly. ⚠️ **2★ and 4★ are interpolated points, not stops** —
>    you will not find `#4C6677` or `#DD5F29` in `STOPS_TEMP`, and that is correct. If you verify
>    them, verify by calling `rampHex(2)` / `rampHex(4)` with the mode set to `'temp'`, not by
>    grepping the stop list.
> 3. Both gradient sites read the **active** stop list rather than `STOPS_VERDICT`. `scoreRamp.js`
>    does not export an "active stops" accessor today — add one there (one line beside `getMode`)
>    rather than letting each call site branch on `getMode()` itself. Two call sites branching
>    independently is the same duplication this series exists to remove.
> 4. The legend's words — `poor → worth it` — **do not change on either scale.** The bar carries
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

---

## Stage 3 — Markers and clusters onto whole stars ✅ LANDED (#640)

> You are implementing **Stage 3** of `docs/engineering/heat-scale-unification-plan.md`. Read that
> plan's §2.1 and Stage 3 before writing anything — §2.1 is the decision this stage implements and
> the reasoning matters more than the diff.
>
> **⚠️ Branch from Stage 2, not from `main`.** Stage 1 has merged, but **Stage 2 is still in review
> at #637** — `main` does not yet have the heat tokens, the corrected hot stops, or the mode-aware
> legends. Check first: if `git log origin/main --oneline | grep 'Stage 2'` finds nothing, branch
> from `claude/heat-scale-stage-2-86f10e` and target your PR at that branch. Branching from `main`
> and following the "do not redo Stage 2" instruction below would ship the marker change while the
> tokens and gradients are still absent.
>
> With Stage 2 in place, `scoreRamp.js` exports `STOPS_VERDICT`, `STOPS_TEMP`, `setMode`,
> `getMode`, `rampGradientCss` and `scoreFromPercent`, and `MODE` still defaults to `'verdict'`.
> **Do not flip the default** — that is Stage 7.
>
> **Goal.** One rule, applied at one place:
>
> > **Any fill that carries a label samples the ramp at whole stars. Only label-free surfaces
> > interpolate.**
>
> **Why it matters.** `readableInkOn` picks whichever of `#0F172A` / `#FFFFFF` contrasts better
> with the fill, which only clears WCAG AA where one of them reaches 4.5:1. Every ramp through
> mid-luminance has a band where neither does — the temperature ramp spends **10.2%** of its range
> there, in two runs (2.48–2.60★ and 4.21–4.48★). All five *whole* stars clear comfortably
> (7.13, 6.04, 6.51, 5.03, 5.56:1), so sampling only at whole stars makes the label safe **by
> construction** rather than by luck.
>
> **⚠️ Trap 1 — the existing guard passes by luck and will not catch a regression here.**
> `MarkerIcon.test.jsx`'s contrast sweep is `it.each([1, 2, 3, 4, 5])` — the only five values never
> at risk. It is green today and would stay green if you got this wrong. Do not treat it as
> coverage.
>
> **⚠️ Trap 2 — there are TWO labelled continuous fills, not one.** Both go through
> `markerUtils.scoreColour(avg)`, which is `avg == null ? NO_DATA_COLOUR :
> rampHex(starsFromAverage(avg))` with `starsFromAverage` being `avg / 20`:
>
> - the **cluster bubble** (`markerUtils.js:204`) — fill from `mean(ratings) × 20`, labelled with a
>   count;
> - an **individual marker** (`markerUtils.js:90`) — a location with both potentials but no rating,
>   fill from `Math.round((fierySky + goldenHour) / 2)`, labelled with that raw average. A marker
>   reading "62" paints from 3.1★.
>
> The `ratingColour` path already complies (`rating` is an integer 1–5) and needs no change.
>
> **The change.**
>
> 1. **Round inside `scoreColour`, at its single `rampHex(...)` call** —
>    `rampHex(Math.round(starsFromAverage(avg)))`. **Not at the two call sites**: a third would
>    eventually be added without it, and the failure is invisible — no test, lint or build fails
>    when a labelled fill drifts into the dead zone.
> 2. **Round, do not floor.** The nearest whole star is the honest reading of an average; flooring
>    systematically under-reports and would render an 89-average cluster as 4★ when it is nearer 5.
>    `rampHex` already clamps to 1–5, so `avg = 0` needs no special case.
> 3. **Do not touch `readableInkOn`.** It already derives ink per fill, which is better than the
>    hard 3★ threshold the design brief originally specified — implementing a threshold now would
>    be a regression. #627 is the commit that got this right.
>
> **Not in this stage:** the Fiery Sky / Golden Hour arcs. They hard-code `#f97316` / `#E5A00D` at
> five sites and the brief says to move them — but they are the same two metrics the score bars
> render, and the bars only reach the ramp in **Stage 5**. Moving the arcs now would leave the pin
> on the ramp while the popup is still on a gradient, which is the exact disagreement the brief
> wants to prevent. Leave them.
>
> **Tests.**
>
> - **Assert the invariant directly**: no label-bearing fill is ever produced from a fractional
>   star. The strongest form is a property-style sweep — for every `avg` from 0 to 100, assert
>   `scoreColour(avg)` is one of the five whole-star colours of the active mode. That is a claim
>   about the rule, not about a sample.
> - Assert it in **both** modes; the rule is not mode-specific.
> - Pin the two callers: a cluster whose ratings average to something fractional, and a marker
>   built from a fiery/golden pair that averages to a non-multiple of 20, both land on a whole-star
>   colour.
> - `scoreColour(null)` still returns `NO_DATA_COLOUR` and does not go near the ramp.
> - ⚠️ Existing assertions in `MarkerIcon.test.jsx` (`scoreColour(40)`, `scoreColour(60)` …) may
>   change value where the old fractional star rounded differently. Where one does, **check the new
>   value is right before updating it** — that is the stage working, not a test to silence.
> - Reset the mode in `afterEach`; `MODE` is module state and leaks across cases.
>
> **Gates, all four, before you push:**
>
> ```
> cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build
> ```
>
> **Before committing**, run an adversarial review of the diff (CLAUDE.md, "UI Work — Review
> Cadence"). Tell review agents to read only. Ask one lens specifically: *is there any path by
> which a labelled fill can still be produced from a fractional star?*
>
> **Commit and PR.** Conventional commit (`feat:`). `CHANGELOG.md` entry under `[Unreleased]` —
> expect a conflict and rebase rather than merge. Do **not** push until asked. Update the plan's
> Stage 3 section with what landed, including anything this prompt got wrong.

---

## Stage 5a — The piecewise mapping, no visual change

> ⚠️ **Stage 5 is split into 5a and 5b.** As written in the plan it was four jobs in one session:
> build Stage 4's mapping (never coded — Stage 4 was a *decision*), delete the superseded linear
> one, merge two score-bar components across eight call sites, and move five hard-coded arc
> colours. 5a is the mapping alone. It changes **no pixels**, because nothing calls it yet — the
> same shape as Stage 1, which is the point.
>
> You are implementing **Stage 5a** of `docs/engineering/heat-scale-unification-plan.md`. Read its
> **Stage 4** section first: it is the evidence for why this mapping is piecewise, and the two
> properties recorded there are the ones an implementer would otherwise "improve".
>
> **⚠️ Check your base.** Stages 1, 2 and 3 have merged. Confirm with
> `git log origin/main --oneline | head -5`; if you do not see Stage 3, branch from its branch
> rather than `main`.
>
> **Goal.** `frontend/src/utils/scoreRamp.js` gains `ANCHORS` and `starFromScore(value, metric)`,
> and loses `scoreFromPercent`.
>
> **Why piecewise.** The two 0–100 metrics are **bimodal** — measured over 19,832 production
> evaluations, fiery peaks at 10–19 and again at 70–79, golden at 20–29 and 70–79, both troughing
> at 50–59. No two-point linear map can spread a bimodal population: under the measured p05/p95
> pair, 51% of fiery readings landed in the 1★ band and **72, 85 and 100 all rendered identically**
> as 5★ — a good evening and a great one the same colour. `scoreFromPercent` is that map. It has no
> caller. Delete it, and its tests.
>
> **The tables**, verbatim from `docs/design/temperature-scale/heat-field.js`:
>
> ```
> fiery:  [[0,1],[20,1.9],[35,2.4],[50,2.8],[60,3.2],[72,4],[85,4.7],[100,5]]
> golden: [[0,1],[25,1.9],[40,2.4],[55,3],[70,3.8],[85,4.6],[100,5]]
> ```
>
> Two properties are load-bearing:
>
> 1. **The anchors are FROZEN CONSTANTS, not a running calibration.** Derived from one measurement
>    and then fixed — the same standing `STOPS_TEMP`'s uneven spacing has. Re-measure only to check
>    the physics has not moved; **do not re-anchor per season**, because that makes colour relative
>    to the population and a 3.0 must look like a 3.0 in every week.
> 2. **The spacing is deliberately NOT even-occupancy.** 70% of fiery readings sit below 30 and all
>    mean the same thing — *don't bother* — so they share 1.3 stars, while the top third of the
>    range holds ~15% of readings and every decision worth making, and gets 1.8. Colour goes where
>    the decision is, not where the readings pile up. Heavy concentration in the low bands is the
>    intended outcome, **not** a defect to tune away.
>
> **⚠️ Trap 1 — the reference implementation returns the TOP of the ramp for a non-finite input.**
> Its shape is `v = clamp(v, 0, 100)` then a loop then `return 5`. `Math.max/Math.min` propagate
> `NaN`, so a `NaN` fails every `v <= x1` test, falls out of the loop, and hits `return 5` — a
> missing potential painting as a **perfect** evening. That directly contradicts the invariant
> `rampRgb` in this very module already states and guards: *an unknown reading must never render as
> the best one; under-reporting is the safe direction.* **Guard it: non-finite resolves to 1**, and
> say why in a comment, because the next person diffing against the kernel will see a difference
> and want to "fix" it back.
>
> **⚠️ Trap 2 — the reference silently falls back to the fiery table for an unknown metric**
> (`ANCHORS[metric] || ANCHORS.fiery`). The tables genuinely differ — at v=80 fiery gives 4.43 and
> golden 4.33 — so a typo'd metric returns a plausible wrong answer with nothing failing. Decide
> deliberately: either throw, or default and log. Do not copy the silent `||`.
>
> **⚠️ Trap 3 — return a NUMBER, not a colour.** The kernel's equivalent (`rampPct`) returns a
> colour; this app keeps domain-mapping and colour-lookup separate, because `rampHex` / `rampRgb`
> already take a score. Callers compose `rampHex(starFromScore(v, metric))`. This is the same
> distinction that caused a defect in an earlier draft of the plan — assigning the number to a CSS
> `background` renders no fill at all.
>
> **Nothing calls it yet.** Stage 5b wires it to the score bars and the arcs. Do not touch
> `PlanScoreBar`, `PopupScoreRow` or `markerUtils` in this stage.
>
> **Tests.**
>
> - Every anchor point maps to its own star value exactly, for both metrics.
> - Midpoints interpolate linearly between the surrounding anchors.
> - Clamping: `< 0` gives 1, `> 100` gives 5.
> - **Non-finite gives 1, not 5** — the trap above, and the most important assertion here.
> - The two metrics genuinely differ: assert one value where fiery and golden disagree.
> - The tables are monotonic in both axes — a property test beats eight literals, and it is what
>   catches a mistyped anchor.
> - `scoreFromPercent` is gone: no import of it survives anywhere.
>
> **Gates, all four, before you push:**
>
> ```
> cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build
> ```
>
> **Before committing**, run an adversarial review of the diff (CLAUDE.md, "UI Work — Review
> Cadence"). Tell review agents to read only. Ask one lens specifically: *can any input produce a
> star value that is not between 1 and 5, or produce the top of the ramp without meaning to?*
>
> **Commit and PR.** Conventional commit (`feat:`). `CHANGELOG.md` entry under `[Unreleased]` —
> expect a conflict and rebase rather than merge. Do **not** push until asked. Update the plan's
> Stage 5 section to record the 5a/5b split and what landed.
