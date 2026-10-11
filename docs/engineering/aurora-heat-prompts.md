# Aurora heat — kickoff prompts for the implementing sessions

Paste one prompt into a **fresh Claude Code session (Sonnet)**, in order (A4 may run beside A1 in
its own worktree). Each phase lands as its own reviewed commit and PR before the next session
starts — the plan's **§0 Status block is the source of truth between sessions**, updated in the
same commit as each phase, so a new session never needs this chat's history. If a session dies
mid-phase, start a new one with the same prompt: step one of every prompt is reading the current
state from the repo.

The plan: `docs/engineering/aurora-heat-plan.md`. The spec: `docs/design/aurora-heat/README.md`,
with `Map Aurora Heat.html` + `aurora-map.js` as the prototype. All vendored verbatim — **do not
edit them**; the plan's §1 corrections, §4 disagreements and §5 decisions **win** wherever the
spec and the code disagree. `docs/design/aurora-heat/VENDORING.md` says what is there and why.

**The three things every session must internalise before touching code**, because they are what
the spec gets wrong about this codebase:

1. **Astro mode already paints the heat field.** Aurora is a third arm beside `astroHeatPoints` /
   `astroConfidenceScalar` in `MapView.jsx`, not a new mode. The kernel's `opts.score` is never
   used — unscored spots are dropped *before* construction and the star rides at `r[0]`.
2. **Every colour on the tab reads `utils/scoreRamp.js` module state**, a persisted user
   preference. The aurora ramp is an event-type **prop** with a default equal to today's import,
   threaded to six consumers — never a third `setMode`. Plan §4 #1.
3. **There is no clustering and no heat/medallion toggle.** Medallions are plain Leaflet markers
   that the heat layer already hides (`applyMarkerFade(map, 0)`) whenever it mounts. Leave the
   marker loop alone.

**Multi-agent note.** These sessions need no special mode. The one multi-agent step is the
pre-commit adversarial review, which each prompt instructs explicitly — plain parallel subagents
(the Agent tool): ~6 prosecutor lenses over the diff (runtime behaviour, CSS and tokens, test
quality, accessibility, project conventions, what it makes harder for later phases), then one
refuter per charge defaulting to REFUTED without citable evidence, then a synthesis. Review agents
are **read-only** — one has already destroyed uncommitted work on this project with a `git checkout
--` cleanup; anything that must mutate gets its own worktree, and you commit before starting a
review that runs mutations. Paste the phase's plan section, the plan's §1/§4/§5, and the spec into
every reviewer's prompt.

**Between phases (owner):** review the PR in the browser, merge. Sessions never push and never
tag. Before starting the next session, `git rev-list --count HEAD..origin/main` says whether
anything else merged; `changelog.d/` files never conflict.

---

## A1 — ramp and bloom injection; `utils/auroraRamp.js`

```
Read docs/engineering/aurora-heat-plan.md in full, then docs/design/aurora-heat/README.md (Task 1,
"Aurora ramp — port exactly" and "Kernel change") and docs/design/aurora-heat/aurora-map.js lines
10–18. Confirm §0 Status shows A1 unbuilt and `gh pr list --state open` has no aurora/heat/ramp PR.

Build plan §3 A1 exactly: `frontend/src/utils/auroraRamp.js` (AURORA_STOPS ported byte for byte,
auroraRampRgb/Hex/GradientCss, AURORA_BLOOM, AURORA_TOGGLE_SWATCH, the AURORA_RAMP object) and the
two kernel hooks in `frontend/src/utils/heatField.js` (`opts.ramp` defaulting to the module's
rampRgb; `opts.bloomRGB` defaulting to [255,138,66], written through a local `bc`). Nothing else
in the kernel moves — read the kernel's own comments on the bloom gate before you touch the lines
around it. Tests as the plan lists them; the byte-equality test is the deliverable — prove it
bites by temporarily dropping the `|| [255,138,66]` default and watching it fail, then restore.

Then: `npm run lint && npm test && npm audit --audit-level=high && npm run build`, gated on exit
codes. Adversarial review of the diff (six lenses + refuters, read-only, with plan §3 A1, §4 #1/#4
and the spec pasted in). Fix survivors. Changelog entry `changelog.d/<date>-aurora-heat-a1.md`.
Append the A1 row to the plan's §0 table in the same commit. Commit on a branch
`feature/aurora-heat-a1`; do not push — report and stop.
```

## A2 — aurora mode paints the field

```
Read docs/engineering/aurora-heat-plan.md in full — §1 #3, #9, #10, #11, #12, §3 A2, §4 #1–#4,
§5 D1–D4 and §6 are load-bearing — then docs/design/aurora-heat/README.md Task 1. Confirm A1 is
merged (§0) and `git rev-list --count HEAD..origin/main` is 0 on your branch.

Before writing anything, read MapView.jsx's astro arm end to end: `isAstroMode`, `heatOffered`'s
javadoc, `astroHeatPoints`, `astroConfidenceScalar`, the `heatPoints` switch, the `windowUnscored`
arm, and the `<MapHeatLayer conf=…>` site. Aurora is a third arm of each. Then read
MapHeatLayer.jsx's `paint` and its repaint-key note, PinsLayer.jsx's fill selection, and
MapLabels.jsx's chip fill.

Build plan §3 A2. D1 decides the star: live `auroraScores` on the night in progress, else
`storedAuroraResults` — the medallion rule, not `getRatingForLocation`'s. Thread `ramp`/`bloom`
props with defaults equal to today's behaviour; every non-aurora render must be byte-identical.
Rewrite the one test the plan names (`MapViewHeat.test.jsx` "paints no field and offers no toolbar in aurora mode", now "offers the toolbar and the field in aurora mode")
into the astro triplet's shape and add the ones listed; re-word, never delete, the "keeps AURORA
honest" comments. A test must switch Aurora → Sunset → Aurora in the window control and assert a
repaint with the right ramp each time (§6, repaint key). Update the two CLAUDE.md sentences the
plan names in the same commit.

Gate: `npm run lint && npm test && npm audit --audit-level=high && npm run build` on exit codes.
Adversarial review (six lenses + refuters, read-only; one lens is "diff the rendered output of a
SUNRISE window before and after — did any pixel move?"). Fix survivors. Browser verification per
plan §9 — the owner must sign in and run the simulation; state what was seen vs tested. Changelog
`changelog.d/<date>-aurora-heat-a2.md`; §0 row; commit on `feature/aurora-heat-a2`; no push.
```

## A3 — legend, toolbar key, peek sheet

```
Read docs/engineering/aurora-heat-plan.md §1 #2, §3 A3, §4 #1/#5 and the spec's "Legend" section.
Confirm A2 is merged.

Build §3 A3: `MapLegendPanel` `variant` prop with the aurora strings and bar style (read the
panel's own header comment first — it records why the bar paints from `rampGradientCss()` and not
the bundle's HTML; the aurora variant paints from `auroraRampGradientCss()` for the same reason);
the "dashed line" sentence only when the viewline is mounted; the toolbar ramp key's aurora
swatch and 1★/5★ ends; the peek sheet's aurora-row swatch. The star-floor filter swatches do NOT
change. Snapshot the default-variant legend HTML before you start and assert it is unchanged
after.

Gate, review (one lens on the 390px phone peek sheet), browser check, changelog, §0 row, commit on
`feature/aurora-heat-a3`, no push.
```

## A4 — the best-location card's dismiss

```
Read docs/engineering/aurora-heat-plan.md §1 #4/#5, §3 A4, §4 #7 and the spec's Task 2. This phase
is independent of A1–A3; work in your own worktree on `feature/aurora-heat-a4` off origin/main.

Build §3 A4 in MapView.jsx: the ✕ after `Centre map` with the exact aria-label, testid, hit target
and classes; `bestAuroraDismissedKey` keyed on `nightDate|name` — nightDate, not date, and the plan
says why. Tests in MapViewAuroraLiveNight.test.jsx as listed; run MapViewAuroraLiveFetch.test.jsx
too (eight references to the card). Gate, review (the accessibility lens: focus after dismiss must
land somewhere sensible, not <body> — see CLAUDE.md's "a panel that REPLACES another must move
focus"), changelog, §0 row, commit, no push.
```

## A5 — viewline: reconstruction and owner questions (no code)

```
Read docs/engineering/aurora-viewline-investigation.md in full, then
docs/engineering/aurora-heat-plan.md §3 A5/A6 and §7. This phase changes NO source code.

Step 1: ask the owner (AskUserQuestion) to run the host grep in the findings' §5 and paste the
output. From it, fill §5 in: a table of every NOTIFY on 10 Oct (time, level, trigger, stored Kp,
the table row it maps to, the line latitude that implies), and whether a second NOTIFY followed
19:47. Resolve §2's inconsistency (stale hook vs a Kp in [6,7)) and say which.

Step 2: put the nine questions in §6 to the owner, one AskUserQuestion per question or grouped
sensibly, and record each answer under its question as a decision. Then rewrite §7 as the chosen
fix in the detail A6 needs (which table/system, interpolation yes/no, cap on the live line
yes/no, per-request vs stored Kp, label, colour).

Commit the updated findings doc (and the plan's §0 row) on `docs/aurora-viewline-a5`; no push.
```

## A6 — viewline fix

```
Read docs/engineering/aurora-viewline-investigation.md (§6 decisions and §7 as rewritten by A5
are the spec) and docs/engineering/aurora-heat-plan.md §3 A6, §7. Confirm A5 is merged.

Backend first (NoaaSwpcClient, AuroraController, AuroraHotTopicStrategy — the glow cap reads the
same table and must move with it; docs/pipeline-reference.html's "Kp Latitude Hard Caps"). Tests
named in the findings' §7, plus the gap the trace found: a FORECAST_LOOKAHEAD alert whose Kp rises
within a level. Run the backend gate exactly as CLAUDE.md's "Speeding Up the Dev Build Cycle"
says (JDK note in memory if 25 is absent; always `-Dtest='!**/integration/**'`). Then the frontend
(overlay label with source and age; colour per the A5 decision). Gate, review, browser check with
the simulation at several Kp values, changelog, §0 row, commit on `fix/aurora-viewline`, no push.
```
