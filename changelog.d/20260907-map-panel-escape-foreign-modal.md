### Fixed — Escape no longer operates the map drilldown behind an open dialog

With the four-day sheet over the map, one `Escape` reaching either drilldown panel stepped it back
or closed it **behind** the sheet. `MapView`'s pane-level handler has stood down for a foreign
`[role="dialog"][aria-modal]` since the panel-persistence work, but the two panels each carried a
subtree handler that did not — and both run on one press, since neither calls `stopPropagation`. So
the pane's rule stood down correctly and the panel's acted anyway.

The fix is one predicate rather than another guard. `foreignModalOver` moved out of `MapView`'s
module scope into `utils/mapForeignModal.js` — the panels cannot import it from `MapView` without a
cycle — and both panels consult it. That helper's own doc already said it was extracted "because two
Escape rules consult it and they must never disagree"; there are four on this tab, and the two that
did not read it were the two that got this wrong. The stand-down returns *before* `preventDefault`,
so the layer above still receives the press and a reader does not need a second Escape.

Each panel resolves its own pane with `closest('.wf-map-tab')` rather than taking the predicate as a
prop. ⚠️ **The prop design was built first and mutation testing killed it**: with either mount
unwired the predicate threw inside the event handler, the handler died before acting, the panel
stayed open — and every wiring test passed, asserting the right outcome for the wrong reason. A
crash is also a worse failure than the bug it guards. Resolving the pane locally leaves nothing to
forget.

Five mutants, all killed: the stand-down dropped from each panel, `preventDefault` moved above it,
and the pane selector broken. ⚠️ The last of those survived at first because the new util test built
its fixture from `MAP_PANE_SELECTOR` itself, so the fixture moved with the mutation — true by
construction for any value, the same shape as the lunar-epoch assertions this project has been
bitten by before. The class is a literal now and the constant is asserted separately.

Only the panels' arm of the wider issue is closed. Tabbing out of a non-trapping dialog onto the
pane behind it, and the phone bottom sheet that paints over one, are unchanged — those still want
the shell-root `inert` follow-on.
