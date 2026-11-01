### Changed — Start a run is a choice of runs, with its scope inline

Operations → Runs → Start a run no longer shows a card of buttons that each opened a confirm dialog. It is now three groups
of options: Forecast (scheduled batch, JFDI batch, briefing, aurora forecast), Data refresh (tide data, tide history,
light pollution) and Testing (the aurora simulation, with its On or Off state). Choosing an option shows what it will touch
right there: the region checkboxes for a batch, and the coastal locations grouped by region for the tide options. The
footer says what will run ("2 of 3 regions · 5 locations"), with Cancel and "Start {option}". A run that starts opens
its own panel at once, a refresh with no run of its own closes the panel and says so in a short message, and a run
already in progress is refused with one clear sentence.
