### Fixed — the Map tab's window, Regions and legend panels fit the map frame too

The Filters fix above left three siblings with the same fixed 420px cap: the window menu (opens
down from the top-left), the Regions menu (opens down from the top-right cluster) and the colour
key's legend panel (opens up from the bottom-left). On a short map frame each ran past the frame
edge it grows toward — the bottom for the first two, the top for the legend — and was clipped with
its last rows unreachable. All four now share one hook, `useFitToFrame`, which measures the room
to that edge (bounded by the viewport) while the panel is open, caps the height there and scrolls
inside it. An open Regions or window menu also lifts its corner above the rest of the chrome, as
the Filters popover already did, so the tide strip and counts footer no longer paint over it.
Phone sheets are unchanged.
