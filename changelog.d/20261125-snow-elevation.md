### Fixed — snow state runs every place at its real height

The snow state job no longer sends a place with no recorded height to Open-Meteo as `nan` (the 9 km grid cell's
own height), which gave valley-floor places such as the Great Langdale ones a fell's snowpack and a false
"lying" topic. It now looks the height up from Open-Meteo's 90 m elevation model, in chunks of 100, and keeps it
in memory by place (reused while the place has not moved). A recorded height always wins. A place whose height
cannot be found, or whose lookup is skipped while Open-Meteo is cooling off, is left out of that run and counted
as failed; its previous row stays. Nothing is written to `locations`. The Scheduler screen's description of the
job no longer says nothing reads its figures.
