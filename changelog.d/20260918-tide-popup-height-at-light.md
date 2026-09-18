### Added — the window popup's tide row states the water's height at the light

Owner-requested follow-up to the tide-plan-card series (Q7, `docs/engineering/tide-plan-card-plan.md`
§6, decided): the Plan/Map tab's window popup tide row gains a fifth fact, `"<height> at the light"`
(e.g. `"2.6 m at the light"`), reading the already-served `BriefingWindowTide.heightAtWindow` — no
backend change, since the field has been served since T2 (#876). It sits directly after the existing
state/direction fact, ahead of the nearest-extreme fact, and is dropped (never approximated) on a
payload from before that field existed.

Measured at 390×844 (a real-browser fixture built from this branch's own compiled CSS and fonts,
reproducing `WindowSheetDialog.jsx`'s exact DOM chain — jsdom cannot render text wrap): the row's
three visible facts wrap to 2 lines without the new fact, four to 3 lines with it. Sea state stays
the row's one phone-droppable fact, as before; the new fact is never dropped.

See `docs/engineering/tide-plan-card-plan.md` §4 #9/#11 and §6 Q7.
