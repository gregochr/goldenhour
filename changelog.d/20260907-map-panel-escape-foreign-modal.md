### Fixed — Escape no longer operates the map drilldown behind an open dialog

With the four-day sheet over the map, one `Escape` reaching either drilldown panel stepped it back
or closed it **behind** the sheet. `MapView`'s pane-level handler has stood down for a foreign
`[role="dialog"][aria-modal]` since the panel-persistence work, but the two panels each carried a
subtree handler that did not — and both run on one press, since neither calls `stopPropagation`. So
the pane's rule stood down correctly and the panel's acted anyway.

The fix is one predicate rather than another guard. `foreignModalOver` moved out of `MapView`'s
module scope into `utils/mapForeignModal.js` — the components that need it cannot import from
`MapView` without a cycle — and **all eight** of the tab's Escape rules now read it: the pane
handler, the landing card's listener, the drilldown's two panels, `WindowControl`, `FiltersPopover`,
`RegionsJump` and `MapLegendPanel`. So does `useOutsideDismiss`, whose outside-press rule dismissed
the same surfaces invisibly when the press landed inside the sheet — the identical defect on the
pointer, unrecorded until now.

⚠️ **The first cut corrected only the two panels and claimed there were four rules.** Two review
lenses counted eight, and one showed `WindowControl` is on the very route that reaches the panels: a
keyboard reader gets to the drilldown by Tabbing out of the non-trapping sheet onto the pill, so the
pill's own dropdown answered the same press first. Fixing two of eight would have left the defect
live one control earlier.

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
