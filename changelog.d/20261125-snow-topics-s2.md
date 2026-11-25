### Added — a point snowpack recorded per place and summit (snow S2, record only)

A new job, `snow_state_refresh`, runs three times a day and works out how much snow is lying at every enabled place
and at fifteen reviewed fell summits, from each point's own last seven days of weather (wet-bulb rain-or-snow split,
a settling gate, degree-day melt) instead of Open-Meteo's 9 km grid snow depth. It records the modelled water
equivalent, the snow added in the 24 hours before the slot, and the low cloud at that hour, for today and the next
four sunrises and sunsets, in a new `snow_state` table (V231). A point that could not be fetched keeps its previous
row, and a row more than 36 hours old reads as unknown, never as bare ground. Nothing reads the table yet: no hot
topic, rating or prompt changes until the detectors switch over in the next phase. It uses Open-Meteo only (no Claude
cost), asks for wind in m/s, has its own 150 second budget, and a minute-limit or concurrency refusal ends its run
without starting the cool-off the main fetch shares.
