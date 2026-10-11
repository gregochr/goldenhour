### Fixed — the aurora viewline drew over North Yorkshire through a G3 storm seen from the south coast

On the night of 10 October the map's aurora line sat at about 54°N while the storm was being photographed from
the south coast. Four faults compounded. The forecast line came from a hard-coded table whose rows were NOAA's
*geomagnetic* latitudes read as geographic, about 2° too far north over the UK. It truncated Kp to an integer
(6.9 read as 6). It was drawn for the Kp stored when the alert fired, which only changed when the alert level
escalated, so a revised forecast never moved it. And the same table clamped the live OVATION line as well, so it
could only ever move the line north.

The forecast line is now a rule, `AuroraViewlineRule`: NOAA's oval edge at 66 − 2×Kp geomagnetic latitude,
converted to geographic latitude per longitude with a centred dipole, then 4° further south for what a camera
sees low on the horizon. It is continuous in Kp, and it dips further south to the west (Kp 6.67 is about 46.5°N
at 2°W). The forecast endpoint reads NOAA's Kp forecast on every request, with the poller's own rest-of-tonight
figure, so the line follows NOAA's revisions without waiting for an alert to escalate; it falls back to the
alert's Kp if the forecast is unavailable. The live line is the OVATION 5% contour, no longer clamped.

The map now polls both lines and draws one: the live line when OVATION has one, else the forecast. Live is solid
pale cream (the aurora field is green, so a green line disappeared on it), the forecast dashed amber. The line
is labelled with its source, the forecast's Kp and the UK time ("Forecast · Kp 6.7 · 19:40", "Live · 22:40"), and
the legend says what it means. Each served line is logged at INFO. The old Kp-to-latitude pins in
`NoaaSwpcClientTest` are deleted with the table: they pinned the defect.
