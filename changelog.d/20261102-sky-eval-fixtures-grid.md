### Added — Sky Eval's Fixtures tab: each fixture's misses per run, and its rating history

Sky Eval's Fixtures tab now shows, for one model at a time, every fixture against that model's newest 13 completed
runs: all ratings in band as a soft green cell, misses as `↓n` / `↑n` on a tide-blue (below the band) or coral
(above) cell that deepens with the miss ratio, and `·` where the fixture did not exist yet. Fixtures are sorted by
misses in the last four runs, worst first, with the last-four in-band percentage on the right (coral below 90). A
version change between runs draws a gold line and the commit's first four characters above the column, and manual
runs are marked `·m`. Clicking a fixture opens its rating history: the expected band shaded, one dot per run at the
mean rating with a min-to-max line, and the versions marked. "See which fixtures" on a dropped lane and "Fixtures →"
on a run in the Runs tab open the tab on that model. The now-unused client calls for the old runs list and trend
were removed; the endpoints stay. Frontend only.
