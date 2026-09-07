### Fixed — closing a dialog no longer pulls a reader out of the panel they moved to

`useDialogFocus` restores focus to the control that opened a dialog. It did so unconditionally,
which is right when the reader closed the dialog from inside it and wrong when they had moved on:
with the four-day sheet open over the map, Tab out onto the pane, open the drilldown, press
`Escape` — the sheet closes, the drilldown correctly survives (#794), and the restore then pulled
focus out of the still-open panel onto the callout beneath it.

The fix is `Modal`'s own uncover-restore guard, mirrored: don't restore when focus is somewhere
real, on the reasoning `Modal` already states — *a reader who Tabbed out into the page while the top
layer was up has chosen where they are, and yanking them back is worse than leaving them*. This hook
refuses containment app-wide, so Tabbing out is supported; a restore that fires regardless takes
back with one hand what that refusal grants with the other.

⚠️ **Notably NOT the `inert` follow-on that `map-tab-v2-plan.md` O-20 named as this arm's cure.**
`Modal` already carried the guard. `docs/engineering/o20-shell-inert-plan.md` costs the `inert`
route and records why it is a poor fit regardless: it is a **silent no-op in this project's jsdom**
(measured — focus still reaches a button inside an `inert` div), so a test of it passes whether the
guard exists or not, and Playwright, which would see it, does not run in CI.

⚠️ The risk was the mirror-image defect the map-landing increment fixed five times — if focus were
still inside the closing dialog, the guard would skip the restore and strand the reader on `<body>`.
Measured in **both** environments rather than reasoned, because focus/blur timing is where this
project has been burned by the jsdom/browser difference before: detaching a focused node, or a
subtree containing focus, puts `activeElement` on `<body>` first, in jsdom and in Chromium alike.

⚠️ **The first cut of this guard shipped a regression on the Plan tab, found by review and fixed
here.** "Focus is somewhere real" is not the same as "focus is somewhere coherent". `Modal`'s guard
governs a dialog that stays OPEN; this cleanup governs one being DESTROYED, and on a stacked route
that also changes which layer claims modality. Measured: Plan popup open, `/` opens search (the
popup goes `stacked`/`inert`), Tab out onto the page, `Escape` — search closes, the popup re-claims
`aria-modal`, and both guards stood down, leaving the reader outside a dialog AT is told to treat
everything outside of as unavailable. The guard now restores when the reader is stranded outside a
layer still claiming modality, and stands down only when nothing claims it or they are inside the
thing that does. Arm C is unaffected: the map drilldown's panels carry no `aria-modal`, so nothing
claims modality when the sheet closes over them.

`useDialogFocus` had **no test file at all**; it has one now — ten cases, six mutants killed across
two rounds, including one that reintroduces the regression above. ⚠️ The two map integration cases
also assert `document.activeElement` now, but they do NOT cover this guard and an earlier wording of
this entry implied they did: measured, both pass with the guard removed, because their foreign modal
is a planted `div` and no consumer of the hook unmounts on that press. ⚠️ An earlier wording said "fourteen
consumers": that was `grep -rl`, which counts files mentioning the hook. There are **four** call
sites (`Modal`, `BottomSheet`, `MapOverlay`, `RegionsJump`); the blast radius is wider than four
because most dialogs reach it through the first two, but the list of callers is not.
