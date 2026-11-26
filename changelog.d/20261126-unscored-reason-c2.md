### Changed — the card says why a window was not scored

The This week card now words an unscored window by the reason the server serves (`coverageReason`, with `fog`), in
the quiet slot beside the verdict: "unsettled" for a weather stand-down (unchanged), "fog" over a fog forecast, "too
far out" where the forecast does not reach that range, and "run failed" where the run failed. An unclear record or a
travel day stays silent, as before. The same words are the card's `title` and spoken sentence, taken from the labels
the region rail already shows ("Too unsettled to forecast", "Fog forecast", "Not scored at this range", "Forecast
unavailable"), so the card and the rail cannot differ. Where the word beside the note would have been "Not scored",
the note replaces it: the two together are 136px against a 70 to 175px value column, so the note would have
collapsed to nothing at every phone width. The reason is read from the window the card wears (the tier window while a
drive tier is selected, else the served window), never worked out on the client, and a payload without the field keeps
today's behaviour.
