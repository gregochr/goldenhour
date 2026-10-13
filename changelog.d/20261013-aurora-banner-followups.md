### Fixed — the aurora strip's glow paints, and the card tells a screen reader how far south the aurora reaches (aurora banner, B4)

On a phone the aurora strip's lit bottom edge is no longer clipped: it was cut with `clip-path`, which
also clipped its own `box-shadow`, so the glow could not show. The lit fraction is now the edge's width
(62% Moderate, 92% Strong, unchanged), with the colour ramp still stretched across the whole strip.

On the desktop card, the southern viewline stays decorative on the thumbnail, but once the line is drawn
the alert carries one visually hidden sentence built from the server's own wording ("Visible as far
south as the Midlands.", with a full stop added), so a screen reader hears how far south the aurora
reaches. Nothing is spoken while the thumbnail has not drawn the line.

The plan records two earlier choices as owner decisions: the brighter secondary ink on the small labels
(the spec's muted ink fails AA) and breathing on a Strong, live, south-Bz alert only.
