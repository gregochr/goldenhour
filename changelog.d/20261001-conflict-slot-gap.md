### Fixed — the Plan pane's conflict slot no longer adds a 10px gap when empty

The page-level conflict slot (`window-first-conflict-slot`) has been an always-mounted
`role="status"` live region since it was built — the pane's whole-plan conflict message cannot wait
for content to arrive before it is announceable, the same reasoning the pending/empty status
wrapper's own fix (#964, v2.22.6) already applied one phase later. Nobody had cancelled its own flex
gap: it is the pane's FIRST flex child inside `.wf-body` (`gap: 10px`), so an empty slot pushed every
reader's first visible content — the safety line, the heat strip, or the pending/empty status line —
down by the 10px flex gap on top of the pane's 13px padding. Verified live against a local build:
23px from the pane's top to its first visible child before this fix, 13px after, with the conflict
slot's computed `margin-bottom` reading `-10px` once empty. On a phone, where `.wf-body`'s own
padding-top is 12px, the same measurement reads 12px.

The fix mirrors `.wf-pane-status:empty`'s mechanism exactly: `.wf-conflict-slot:empty` cancels the
one gap this slot, being first, can ever add — the one AFTER it. It declares `margin-bottom` rather
than the status wrapper's `margin-top`, but that side is a naming convention, not a structural
requirement: an empty flex item is 0px tall and flex margins never collapse, so the next sibling
lands at the same pixel whichever side carries the cancelling margin (confirmed live by swapping the
rule's property to `margin-top` and re-measuring — unchanged at 13px, then restored). What matters
is declaring exactly one cancelling margin, never both.
