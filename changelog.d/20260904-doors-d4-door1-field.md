### Added — Door 1, the popup field's `◍ Open in map →`, seeded so it never covers a chip

Phase D4 of `docs/engineering/plan-to-map-doors-plan.md`. The window popup's field map
(`WindowRowFieldMap.jsx`) gains a top-right button, rendered only when the shell has somewhere to
send it — a separate affordance from the field's own click gesture (region pick) and its
bottom-left hint, so two meanings never land on one tap. `WindowSheetDialog` passes the prop
straight through; the shell builds the door from the open window's own date/type and the popup's
own focused region (`field.selectedRegion`, already forced null under an away origin by D2's
`openField`), and hands it to D2's `openMapTab` — its first real caller.

The increment's own recorded defect (`INCREMENT_plan_to_map_doors.md`, "The defect worth knowing
about") was drawing this exact button without seeding it into the field's greedy label-placement
obstacle array, which covered a chip's rating in 4 of 6 prototype windows. Fixed here by measuring
the button from the LIVE mounted element (`openRef.current.offsetLeft/Top/Width/Height`, never a
guessed constant — `HINT_BOX` stays a constant on purpose, for the one fixed 9px string that cannot
change) and seeding it as a `target: true` obstacle, so the same 24px centre-separation rule that
already keeps two chips apart also keeps this control apart from every chip.

Verified in the browser with a Playwright sweep (`src/test/e2e/door1-obstacles.spec.js`, new):
`document.elementFromPoint` sampled every 2px across every chip's width, at both 1280×800 and
390×844, across all six matrix windows on a seeded local stack — zero overlaps in either
viewport. Pressing the button closes the popup and lands the Map tab on that window, framed to the
carried region, with the breadcrumb naming what it carried.
