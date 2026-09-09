### Docs — the plan-matrix spec's chrome and layering claims now match what ships

Three claims from the same audit, corrected in `docs/design/plan-matrix/README.md` because the code
is right and the spec was stale.

**The masthead is not sticky, and never was.** M3 added `position: sticky; top: 0; z-index: 45` and
every downstream note was written against that intent — the lens bar resting on the masthead's
bottom edge, A14's dropdown "anchored under the masthead", `--wf-mast-h` as the height of pinned
chrome. A sticky element cannot leave its containing block, and this one's holds only the masthead,
the tab bar and the tab rule, so the band pinned for ~46px and was then carried off the top. The
rule was removed 2026-09-05 by owner decision; the lens bar anchors at `top: 0` itself at
`z-index: 20`, not the spec's 30. `z-index: 45` survives on the masthead as a stacking context.

**There is no 70-over-60 layering.** Every dialog is one shared `Modal` at `fixed inset-0 z-50`, so
the layers are separated by DOM order. That is the mechanism behind the M5 stacking fix already
recorded two sections away — a third layer painted *underneath* the sheet that opened it precisely
because equal `z-index` makes paint order document order. The spec had been explaining the symptom
while still asserting the model that contradicts its cause.

**Two spec values are wrong and stay unimplemented.** The beyond line's `rgba(242,231,211,.34)`
measures **2.75:1** on `--bg` — a 1.4.3 failure on 10px text — against 7.06:1 for the `--ink-2` the
code uses; the spec now records the measurement rather than the value. And the matrix grid's
`repeat(var(--dc), 1fr)` is shipped as `repeat(var(--dc, 4), minmax(0, 1fr))`, because a bare `1fr`
keeps `min-width: auto` and a grid item holding a canvas cannot shrink below its 300px intrinsic
width — reverting it would overflow the column at narrow widths.

⚠️ **The iPad column of that spec has no implementation at all.** The arm has two breakpoints,
desktop and phone at 639px, so every iPad value in the document (`13px 20px 0`, a 20px gutter, and
the rest) resolves to the desktop one. A structural gap, not a value drift, and not decided here.
