### Fixed — a day with only a wildlife hide's hourly forecast no longer adds an empty window to the Map tab

Since the hourly comfort job was restored, wildlife hides carry forecast rows one day further than
the sunrise and sunset forecasts do. The Map tab treated every day with any forecast row as a day
to draw a Sunrise and Sunset window for, so the window control gained an empty pair for a day
nothing rates. A day now gets windows only when a sunrise or sunset forecast stands behind it. The
Map tab itself is still offered whenever any forecast exists, so a forecast made up of hides alone
keeps its map.
