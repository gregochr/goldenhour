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

## Stage 5a — The piecewise mapping, no visual change ✅ LANDED (#642)

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

---

## Stage 5b — One score bar, and the arcs ✅ LANDED (#643)

> You are implementing **Stage 5b** of `docs/engineering/heat-scale-unification-plan.md`. Read its
> **Stage 5b** section first — it carries the merged component's interface and all eight call sites
> mapped, and that spec was itself corrected twice by review, so do not re-derive it.
>
> **⚠️ Check your base.** Stages 1, 2, 3 and 5a have merged. Confirm `starFromScore` and `ANCHORS`
> exist in `frontend/src/utils/scoreRamp.js`; if they do not, branch from 5a's branch rather than
> `main`.
>
> **⚠️ This stage CHANGES PIXELS — the first one that does.** Stages 1, 2 and 5a were all provably
> zero-visual-change. This one replaces two four-bucket/gradient bars with a continuous solid fill
> sampled from the ramp, and it does so **in verdict mode, today**, not only after Stage 7. So:
>
> - "no visual change" is **not** an available assertion here; do not write one.
> - The project's UI cadence applies in full (CLAUDE.md, "UI Work — Review Cadence"), **including
>   browser verification**: `./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local`
>   (port **8083**) and `npm run dev`, sign in as `admin` / `golden2026`. A local DB with no
>   evaluation run has no ratings, so say plainly which states you saw and which you could not.
>
> **Goal, two halves.**
>
> ### 1. One score bar
>
> `PlanScoreBar` (Plan side, gradient fills, used by `LocationFourDaySheet` ×2 and `WindowSpotPeek`
> ×2) and `PopupScoreRow` (private to `MarkerPopupContent`, ×4) are the same component with
> different clothes. Collapse them into **`components/ScoreBar.jsx`** — the plan's §Stage 5b gives
> the exact prop list, the eight call sites and what each passes.
>
> Key points the spec settles, restated because they are the ones easy to get wrong:
>
> - **`fill` stops being a prop.** It becomes `rampHex(starFromScore(score, metric))` — a
>   **continuous solid** colour. Not a gradient: a bar has one value, and a gradient across a ramp
>   that starts cold is a five-hue rainbow. `PlanScoreBar`'s `FIERY_FILL` / `GOLDEN_FILL` exports
>   and `MarkerPopupContent`'s private copies are all deleted, along with `rampTint`, `FIERY_TINT`
>   and `GOLDEN_TINT`.
> - **`metric` is `'fiery'` or `'golden'`, and nothing else.** ⚠️ `starFromScore` **throws** on an
>   unrecognised metric (5a, deliberately). Do not pass the `label` string through as the metric —
>   `'Fiery Sky'` is not `'fiery'` and will throw at render.
> - **The two null behaviours are both deliberate and both survive.** Plan callers keep their
>   `score != null &&` guards and render *nothing*; the popup renders an em dash. A tooltip with a
>   stray dash is noise; a popup row that vanishes is a layout jump.
> - **The number is tinted to match, on both surfaces.** This deliberately closes `PlanScoreBar`'s
>   documented no-tint deviation. It does not reintroduce an SC 1.4.1 problem: the numeral states
>   the value in text, so nothing is encoded by colour alone.
>
> ### 2. The arcs — five sites, and NOT all the same quantity
>
> ⚠️ **This is the trap that a review already caught once in the spec.** `markerUtils.js` hard-codes
> `#f97316` / `#E5A00D` at five places, and applying one formula to all five is wrong:
>
> - **Four potential arcs** — `buildMarkerSvg`'s fiery/golden pair (~lines 161, 164) and
>   `createClusterIcon`'s pair (~238, 242). These carry the 0–100 metrics, so they take
>   `rampHex(starFromScore(v, metric))`, the same source as the bars.
> - **One rating ring** — `buildMarkerSvg`'s single full ring (~line 180), the Haiku path, whose
>   fill is `FULL_CIRC * (rating / 5)`. It receives a **1–5 rating**, not a 0–100 potential, and
>   there is no metric table for it. It takes **`rampHex(rating)`**. Routing it through
>   `starFromScore` would read a 5★ rating as the raw value **5** and map it to ≈**1.2★** — a
>   top-rated location painted at the ramp's cold end.
>
> Arcs and ring carry no label, so §2.1's whole-star rule does not constrain them: they may sample
> continuously.
>
> ⚠️ **One question to answer in the browser, not on paper.** The rating ring sits immediately
> outside a disc already filled with `ratingColour(rating)` — the same value through the same
> function. Colouring the ring from the ramp makes both the same hue, and the ring may stop reading
> as a gauge and start reading as a halo. **Look at it.** If it reads badly, say so and stop rather
> than inventing a second colour language for one ring.
>
> **Scope boundary.** `PopupScoreRow`'s inline styles move across as-is. CLAUDE.md's "Tailwind
> only, no inline styles" rule is violated by **both** components today; converting them is
> pre-existing debt and **not** this stage's job. Say so in the PR rather than silently expanding.
>
> **Tests.**
>
> - One component, eight call sites, both metrics.
> - The fill is the ramp's colour for that score — assert against `rampHex(starFromScore(...))`, not
>   a hard-coded hex, so it cannot drift from the ramp (the lesson from `heatTokens.test.js`, and
>   from four separate literal-drift incidents in the design bundle).
> - Both null behaviours pinned: Plan renders nothing, popup renders the dash.
> - The rating ring uses `rampHex(rating)` — assert a 5★ ring is **not** the colour
>   `starFromScore(5, 'fiery')` would give. That single assertion is what stops the regression the
>   spec was corrected for.
> - An invalid metric throws rather than rendering something plausible.
> - `planScoreConsistency.test.js` pins the *data* side of "two surfaces, one truth"; this stage
>   makes the *render* side structural. Check it still passes and consider whether it wants a
>   sibling.
>
> **Gates, all four:**
>
> ```
> cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build
> ```
>
> **Before committing**, run an adversarial review of the diff — read-only agents. Ask one lens:
> *does any surface still derive a score colour from anything other than the ramp?*
>
> **Commit and PR.** Conventional commit (`feat:`). `CHANGELOG.md` entry under `[Unreleased]` —
> expect a conflict, rebase rather than merge. Do **not** push until asked. Update the plan's
> Stage 5b section with what landed, including anything this prompt got wrong.

---

## Stage 6 — The preference, full-stack ✅ LANDED (#650)

> You are implementing **Stage 6** of `docs/engineering/heat-scale-unification-plan.md`. Read its
> Stage 6 section first. Stages 1, 2, 3, 5a and 5b have merged; `MODE` still defaults to
> `'verdict'` and **this stage does not flip it** — that is Stage 7.
>
> **Goal.** A user can choose between the two colour scales, and the choice persists. Two settings:
> `mapColourScale` (`'temp' | 'verdict'`) and `markersFollowScale` (boolean).
>
> **⚠️ This is the only backend stage in the series.** The design brief does not say so out loud.
> Persisting through `settingsApi` rather than `localStorage` means the whole chain: migration,
> entity, DTO, service, controller, API module, UI.
>
> **⚠️ Read the migration number off `main` — never from a written-down one.** Two `V136`s
> collided in this repo once. `ls backend/src/main/resources/db/migration/ | sort -V | tail -1`.
>
> **⚠️ There is no `user_settings` table.** Every user setting is a column on **`app_user`**
> (`V67` for the home location, `V136` for `local_radius_miles`); `user_drive_time` is the only
> side table. A migration written against `user_settings` fails at deploy.
>
> **Follow `localRadiusMiles` as the precedent — it is the closest thing to what you are adding**,
> and it threads through exactly five backend files: `AppUserEntity`, `SaveHomeRequest`,
> `UserSettingsResponse`, `UserSettingsService`, and its consumer `ReachService`. Read
> `V136__user_local_radius.sql` before writing the migration: it is **nullable with no backfill**,
> deliberately, so `NULL` means "never chosen" and the service applies the default. That
> distinction matters here too — it is what lets Stage 7 change the default for people who never
> chose, without overriding anyone who did. **A `DEFAULT` in the DDL would erase it.**
>
> **⚠️ Do NOT put these on `PUT /home`.** `localRadiusMiles` rides that endpoint because it *is*
> home-derived. A colour preference is not: a rename body carrying only the colour fields would
> deserialise the home fields to null and wipe someone's postcode. Give it its own endpoint — the
> controller is `/api/user/settings` and already has `/home`, `/drive-times`, `/reach`, `/light`,
> so follow that shape.
>
> **⚠️ `HttpCachingConfigTest.personalDataPathsAreNeverFiltered` is parameterised per path, and a
> new route under `/api/user/settings` must be added to it.** Everything under that prefix must
> stay `no-store`: ETag revalidation needs `Cache-Control: private, no-cache`, which persists the
> body to a browser HTTP cache JavaScript cannot evict on logout. That is why the prefix exists.
>
> **Frontend.**
>
> - `settingsApi.js` gains the getter/setter pair.
> - A new **Map Colours** section in `components/UserSettingsModal.jsx`. Follow the existing
>   section shape — a `<section>` with an uppercase `text-xs font-medium text-plex-text-muted
>   tracking-wide` heading, matching Profile / Home Location / Drive Times.
> - ⚠️ **The modal has no toggle or checkbox pattern today** — only text inputs and `btn-primary`
>   buttons. The control is new work. Keep it keyboard-operable and labelled; do not reach for a
>   bare `<div onClick>`.
> - ⚠️ **Leave it OUTSIDE the `isPro` gate.** Reading the map is not a Pro feature.
> - Wire the loaded setting into `setMode()` at **one** place, so Plan and Map can never disagree —
>   the rule that put `MODE` in `scoreRamp.js` rather than in each consumer.
>
> **⚠️ Defaults stay `'verdict'` and markers-follow-on in this stage.** The point of landing the
> preference before the flip is a dogfooding window: the owner can switch their own account to
> temperature and look at it against real production forecasts before it becomes everyone's
> default. Do not pre-empt that.
>
> **Tests.** Backend: the migration applies; null round-trips as "never chosen"; the service
> defaults; the controller rejects a bad value; the new path is in the caching test's list.
> Frontend: the setting round-trips through `settingsApi`; the control is keyboard-operable; a
> loaded `'temp'` reaches `setMode`; the section renders for a LITE user.
>
> **Gates.** Frontend, all four:
> `cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build`.
> Backend: `cd backend && ./mvnw checkstyle:check` first (fails fast, ~15s), then
> `./mvnw clean verify` — ⚠️ **needs Docker running**, five Testcontainers classes execute in the
> ordinary `test` phase. **Gate on the exit code, never on a grep of the output** — `-q` suppresses
> violation lines and `$?` after a pipe is grep's status, which has reported a false green twice in
> this repo. Redirect to a file and `echo "exit: $?"` as its own statement.
> ⚠️ **JaCoCo requires 80% line coverage per class**, which bites small new records — cover the
> defensive branches with real assertions rather than deleting the guards.
>
> **Before committing**, run an adversarial review of the diff (CLAUDE.md, "UI Work — Review
> Cadence"), read-only agents. Ask one lens: *can any path here leak a personal setting into a
> cacheable response?*
>
> **Commit and PR.** Conventional commit (`feat:`). `CHANGELOG.md` entry under `[Unreleased]` —
> expect a conflict, rebase rather than merge. Do **not** push until asked. Update the plan's
> Stage 6 section with what landed, including anything this prompt got wrong.

---

## Stage 7 — Flip the default, and tell people

> You are implementing **Stage 7** of `docs/engineering/heat-scale-unification-plan.md` — the last
> stage. Read its Stage 7 section first. Stages 1, 2, 3, 5a, 5b, 6 and 8 have merged, and the score
> number's tint was removed as this stage's prerequisite.
>
> **Goal.** Two things: a reader who has never chosen gets the temperature scale, and a reader who
> was using the old one is told the colours changed.
>
> **⚠️ Trap 1 — "never chosen" and "invalid" are different, and today they both land on verdict.**
> This is the whole difficulty of the stage.
>
> `setMode(m)` is `MODE = m === 'temp' ? 'temp' : 'verdict'`. That fallback is a **safety guard**
> from Stage 5a — *"an unrecognised value must never silently select the not-yet-shipped ramp"* —
> **not** the product default for a user who has never chosen. `V147` deliberately stores
> `map_colour_scale` nullable with no `DEFAULT`, and `UserSettingsResponse` passes it through raw
> rather than defaulting, **precisely so this stage can tell the two apart**. That was the whole
> point of following `V136`'s precedent.
>
> After the flip they must diverge: **null (never chosen) → `temp`**, while a corrupt or
> unrecognised stored value should still resolve to something deliberate rather than to the new
> default by accident. Decide which and say why in a comment; do not collapse the two cases just
> because `setMode` currently does.
>
> **⚠️ Trap 2 — the default is currently resolved in three places.** `setMode`'s fallback,
> `App.jsx`'s `setMode(s?.mapColourScale)`, and `UserSettingsModal.jsx`'s
> `useState('verdict')` — the radio's pre-load state. Flip one and the modal will show "Verdict"
> selected while the map paints temperature, which is the same class of disagreement rule 1 exists
> to prevent, just between the map and its own settings screen.
>
> **Resolve it in ONE place.** The natural shape is a `DEFAULT_MODE` constant plus a
> `resolveMode(stored)` in `scoreRamp.js` that both `App.jsx` and the modal call, leaving `setMode`
> as the low-level setter with its guard intact. Nothing else in this series is allowed two answers
> to "which ramp is live", and neither is this.
>
> **⚠️ Trap 3 — do not change anyone's explicit choice.** Someone who deliberately picked
> `verdict` stores the string `'verdict'` and must keep it. Only `null` moves. If you find yourself
> writing a migration that backfills the column, stop — that is the distinction `V147` exists to
> protect, and a backfill destroys it permanently.
>
> **The notice.**
>
> A one-time, dismissible line on the map: **"Colours now run cold to hot."** It is the only part
> of this work that reaches the person who was misreading the old map — the preference does not,
> because they will never open Settings to discover they were wrong. **It is not a setting; it is a
> sentence.**
>
> - Dismissal persists in `localStorage`. ⚠️ **Use `MapView.jsx`'s existing fail-soft helpers**
>   (`readMapFilter` / `writeMapFilter`) or the same `try/catch` shape: a storage-denied browser
>   throws `SecurityError` on bare access, and several of these reads happen inside `useState`
>   initialisers — i.e. during render, where an unguarded throw crashes the whole app rather than
>   the map. That convention is already documented in that file; follow it.
> - `MapView` has no toolbar. Its only overlays are a bottom-left upsell chip and a bottom-centre
>   legend over a fixed 500px map — place the notice without fighting either.
> - Someone who has explicitly chosen `verdict` is not being told anything changed, because for
>   them nothing did. Consider whether the notice should show at all in that case.
>
> **The legend's words do not change.** `poor → worth it` reads correctly on either scale — the bar
> carries the metaphor, the words carry the meaning.
>
> **⚠️ This stage changes what every reader sees.** Browser-verify it:
> `./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local` (port **8083**) and
> `npm run dev`, sign in as `admin` / `golden2026`. Check the map, the Plan matrix and the legend on
> both scales, and the notice's appear-once-then-dismiss behaviour. A local DB with no evaluation
> run has no ratings — say plainly which states you saw and which you could not.
>
> **Tests.** A never-chosen user resolves to `temp`; an explicit `'verdict'` still resolves to
> `verdict`; an unrecognised stored value resolves as decided; the modal's pre-load radio matches
> whatever `resolveMode` says, so the two can never disagree; the notice renders once, dismisses,
> and stays dismissed; a storage-denied browser neither crashes nor shows it forever.
>
> **Gates.** Frontend all four: `cd frontend && npm run lint && npm test && npm audit
> --audit-level=high && npm run build`. Backend only if you touch it: `./mvnw checkstyle:check`
> then `./mvnw clean verify` (⚠️ needs Docker), gating on the **exit code**, never a grep of the
> output.
>
> **Before committing**, adversarial review of the diff, read-only agents. Ask one lens: *can the
> map and the settings modal ever disagree about which scale is active?*
>
> **Commit and PR.** Conventional commit (`feat:`). `CHANGELOG.md` under `[Unreleased]` — expect a
> conflict, rebase rather than merge. Do **not** push until asked. Update the plan's Stage 7
> section, and mark the series complete.
