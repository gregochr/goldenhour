### Docs — a plan for giving Ask PhotoCast each window's rating distribution

`docs/engineering/ask-window-context-plan.md` sets out, for the owner to judge, how `list_windows`
would carry each window's spread (the count at each star, how many places are game on at 4★ or
better, the regions holding one, and how many regions were stood down whole or not scored), so that
Ask reads a window the way the owner reads the heat map and says plainly when a weekend is not worth
going out for instead of presenting a thin 4★ tail as a recommendation. It gives the population
(server-side, user-less, the combined rating, no fitted curve), the prompt's criteria and workflow
hint, how a quoted figure stays honest, the 3★-at-a-distance options for the owner to decide, two
real-API regression cases pending the owner's assertions, a read-only sub-region research recipe,
and the delivery phases. `scripts/measurements/ask-window-context/measure.py` is the token-cost
mock behind its figures. No prompt, tool or pipeline behaviour changed.
