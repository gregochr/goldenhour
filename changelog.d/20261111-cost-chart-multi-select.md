### Changed — Cost trends: pick several types at once

The legend chips and table rows under Operations, Runs, Cost trends now pick any number of types instead of one, so Overnight cycle, Afternoon refresh and Evening refresh can be shown together to see what forecasting costs. The bars for the other types fade, the 7-day line follows the picked types, and a line under the chips totals them (with a "Selected" row in the day tooltip once two or more are picked).

The address carries the picked types as a comma-separated list, for example `type=OVERNIGHT,AFTERNOON,EVENING`; old single-type links still work.
