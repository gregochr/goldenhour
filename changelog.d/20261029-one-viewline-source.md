### Changed — one aurora viewline poll serves the banner and the map

The aurora banner and the Map tab used to each poll the live and forecast viewline endpoints every five minutes. One shared poll (`AuroraViewlineProvider`) now serves both: it runs while either wants the line (the banner's wide-layout field is on screen, or the Map reader is not Lite and the alert is worth showing), stops when neither does, and never runs for a Lite reader. A session with the banner and the map both showing the line makes half the viewline requests.

The map's viewline overlay also keeps its Leaflet coordinate arrays between renders (rebuilt only when the line itself changes), so an unrelated map re-render no longer rebuilds the line and its fill.
