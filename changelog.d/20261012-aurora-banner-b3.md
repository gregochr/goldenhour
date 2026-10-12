### Added — the aurora banner's thumbnail carries the southern viewline (aurora banner, B3)

When tonight's aurora field is on the banner's thumbnail, a dashed amber line now marks how far south
the aurora is expected to be visible, with a small label (`Kp 6 line`). It uses the live line when the
live data has one and NOAA's forecast line otherwise, the same order as the Map tab. If the limit is
south of the picture the line sits on the bottom edge and reads `Kp 8 · all ↓` (your whole area is
inside it); if it is north of the picture the line sits at the top and reads `Kp 3 · line ↑` (your
whole area is south of it). The line is only asked for while the field is drawn, so a banner showing the
Kp meter, a simulated alert, a dismissed banner or the phone strip makes no viewline request. Frontend
only; the line is on for everyone who sees the field (owner decision, 2026-10-12).
