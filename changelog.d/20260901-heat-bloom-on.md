### Changed — the heat field glows ember above 3★, in temperature mode

The bloom kernel P1 ported inert is now switched on at all three heat surfaces — the Plan tab's
window thumbnails, the Plan popup's field map, and the Map tab's field — so a warm evening now
climbs steadily brighter from 3★ to 5★ instead of visibly dimming, which is what the temperature
ramp's own colours do on a dark ground without it (`docs/design/map-tab-v2/README.md`, "The heat
bloom (required on a dark ground)"; plan §3 P2). Each surface carries its own measured dials —
thumbnails 155/0.9, the popup map 170/2.0, the Map tab field the kernel's own 190/2.4 defaults —
and the gate stays fixed at 3★ everywhere, where the ramp's own luminance peaks.

The bloom is gated on the **temperature** colour mode only (decision D-1): the verdict ramp has no
such inversion, so nothing changes there, and switching modes now correctly repaints the Plan
popup's field map — a `colourMode` repaint key it never carried before, the same one the
thumbnails and the Map tab field already had.
