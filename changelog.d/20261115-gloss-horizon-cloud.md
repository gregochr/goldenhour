### Fixed — Region summaries no longer call a sky clear when the ratings saw a cloud canvas

The one-line summary above each region used to read cloud only at the observer's own grid point, so a
clear sky overhead with a full cloud canvas on the horizons produced prose like "essentially cloudless"
beside four-star ratings. The summary now reads the low, mid and high cloud about 110 km out on the sun
side and the canvas on the opposite side, counts a region as clear only when most of its sky locations are
clear on those horizons, ignores woodland locations when describing the sky, and is told never to explain
the ratings away. A well-covered region averaging 3.5 stars or more is never called clear.
