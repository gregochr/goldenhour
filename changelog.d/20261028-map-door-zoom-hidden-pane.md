### Fixed — a Map door from Coming up or At a Glance no longer lands zoomed right in

A door that opened the Map from another tab (Coming up's Inversion WX chip, an At a Glance card) could
land zoomed in to street level around one place instead of framing every pin. The Map is hidden between
visits and Leaflet had cached a zero size for it, so the camera's fit clamped to its maximum zoom. The
door cameras now wait until the Map is on screen, re-measure it, then move. A framing change that
arrives while the Map is hidden now applies when the reader returns to it.
