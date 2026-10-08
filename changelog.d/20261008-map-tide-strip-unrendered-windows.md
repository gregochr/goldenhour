### Fixed — the Map tab's tide strip no longer vanishes for windows the Plan tab does not draw

The briefing serves a tide rollup, and a slot-level tide fact for every coastal spot, for every
sunrise and sunset of its four owned days, but the Map tab built its window list from the six
events the Plan tab renders. The rest became unscored "beyond the briefing" filler rows with no
tide and no clock time, so the strip disappeared for them — the Sunday sunrise, seen on a Thursday
morning, is the seventh event. Tides and light times do not depend on the forecast being scored, so
a filler row now borrows the briefing's own served tide and event time for its window
(`mapTideFit.buildWindowTideIndex`, lent in `mapEvents.solarRow`, the time formatted by the same
formatter served rows use). No tide, height or clock time is computed on the client — the tide and
event time are the briefing's own, forwarded as served — and a window the server drew no tide for
still shows none. The window menu now shows the briefing's clock time for those windows too. A filler whose window has already elapsed borrows nothing, by a test that
mirrors `PlanWindowProjector.hasPassed` (30-minute afterglow) and runs on the app clock, so a Rewind
behaves. The strip's per-spot fit is unchanged where spots carry a served tier for the window. Where none
does, on a window the pane's rendered list does not carry, it now says there is no per-spot tide fit
for the window instead of claiming no coastal spot has the water it wants (a served window keeps its
old wording). The strip's "next high water" line now names the earliest window that fits, as the
callout does: a button only when the strip can show that window, plain text when it cannot, and
"beyond" only when nothing fits. The strip still needs a coastal spot in view after the reader's
filters. A backend test pins that an unrendered window carries its own tide rollup.
