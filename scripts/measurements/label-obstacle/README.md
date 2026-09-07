# Label-obstacle measurement

The instrument behind **`docs/engineering/map-landing-plan.md` §4b.1**. It answers one question:

> When something is added to `MapLabels.jsx`'s `OBSTACLE_SELECTOR`, or an existing obstacle grows,
> which labels stop being placed — and were they labels the obstacle covers anyway, or labels that
> had clear air and lost it?

It exists because that question was once answered by a measurement nobody could re-run.
`map-tab-v2-plan.md` §4 #31 licensed widening `.wf-map-chrome-tl` by measuring that no label moved;
the obstacle then grew again and three panels joined the list within a day, and the licence was
carried forward without the measurement being repeated. **Committing the instrument is the fix for that**, and it is
the point of this directory — not the particular numbers, which will age.

## Running it

```bash
node scripts/measurements/label-obstacle/measure.mjs   # Chromium → out/boxes.json
node scripts/measurements/label-obstacle/analyse.mjs   # the real placer → a report on stdout
```

Needs `frontend/node_modules` installed and Playwright's Chromium in the usual cache (both are
already there if you have run the frontend suite). Neither script writes anything outside this
directory; `dist/` and `out/` are generated and git-ignored.

## What it actually does

**`measure.mjs`** builds `harness/` — a page that mounts the *real* `WindowControl`,
`MapLandingCard`, `MapWindowPanel` and `MapRegionPanel` inside the *real* ancestor chain
(`.wf-shell` › `.wf-body.wf-body--map` › `.wf-map-tab` › the relative frame) — then reads, in
headless Chromium against the built stylesheet, each obstacle's true rect and each label kind's true
`offsetWidth`/`offsetHeight`. Those are the same properties `MapLabels`' own measure pass reads.

**`analyse.mjs`** runs the *real* placer over those boxes: `mapLabels.regionLabelItems` +
`chipCandidates` + `placeLabelPass`, with obstacles seeded through the host's own
`labelPlacement.seedObstacles(rects, containerRect, 5)`, and Leaflet's own `CRS.EPSG3857`
projection. Nothing about the placement logic is reimplemented — only the projection and the
`fitBounds` zoom search are ported, and both are checked against Leaflet's source.

Every drop is classified:

- **covered** — the label's old box lies under geometry the new configuration added. The obstacle is
  doing its job; the label would have been invisible anyway.
- **collateral** — it had clear air and lost it to the greedy pass reshuffling around the obstacle.
  **Only collateral is a defect.**

## Reading a result honestly

- **A null result is only worth having under competition.** The top-right chrome cluster is held
  constant in both arms for this reason. Collateral counts rise with every axis that adds contention, which is why the sweep
  runs all of them rather than one.
- **The widening is not the same change on every frame.** `max-width: calc(100% - 308px)` clamps
  the control, so production's change was `334 → 504` on the wide frames and `334 → 480` on the
  788px one; `analyse.mjs` compares each frame against its own MEASURED control rather than against
  a fixed 504. Only the phone is skipped, and for a different reason: below 640px the bound is
  released entirely, so the box is frame-driven and there is no widening to license. ⚠️ Two earlier
  cuts got this wrong in opposite directions — one compared 334 against 504 everywhere, the other
  discarded the tablet altogether.
- **The home marker and the reach rings are excluded**, so this says nothing about `PinsLayer` —
  which places *only* the home label, and whose drop hides the home dot itself. Extending the
  harness to cover it is the obvious next improvement.
- **What is measured is the state after a placement pass runs with the obstacle open.** Opening or
  closing a panel does not itself trigger one (see `MapLabels.jsx`), so the measured "after" is what
  a reader sees on their next pan or zoom, not immediately.

## ⚠️ Two traps this harness has already fallen into

1. **Tailwind silently not running.** Vite looks for a PostCSS config beside its `root`, which here
   is `harness/`. Without `css: { postcss: FRONTEND }` the page renders with a different font stack
   and no utility classes — and it fails *plausibly*: the widths stay in a believable range while
   every panel reports a height of **0**, which is the reliable tell. ⚠️ Do not check a run against
   a remembered chip-width range; that range has already moved once, when the chip markup was
   corrected. Read the rects a run prints.
2. **The seed script's heredoc opener ends `|| true`**, which splits on `|` into a phantom anchor.
   The roster came out 220 across 5 regions instead of 210 across 4. `lib.mjs` drops the opener line
   explicitly; a run prints the roster size it loaded so this cannot pass unnoticed again.

## ⚠️ It is coupled to the app, on purpose, and nothing tells you when that breaks

`lib.mjs` and `analyse.mjs` import from `frontend/src/utils/`, and `harness/main.jsx` mounts four
components with hand-written fixtures. That coupling is the whole value — it is what makes this a
measurement of the shipped placer rather than of a model of it — but it means a rename or a prop
change breaks the harness, and **no test or CI job will tell you**, because it is deliberately not
wired into either. If it fails to run, fix the harness; the app is the source of truth.
