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

`useDialogFocus` had **no test file at all**; it has one now — seven cases, the guard's three
mutants killed — and both map integration cases now assert `document.activeElement` rather than only
that the panel is still in the document. ⚠️ An earlier wording of this entry said "fourteen
consumers": that was `grep -rl`, which counts files mentioning the hook. There are **four** call
sites (`Modal`, `BottomSheet`, `MapOverlay`, `RegionsJump`); the blast radius is wider than four
because most dialogs reach it through the first two, but the list of callers is not.
