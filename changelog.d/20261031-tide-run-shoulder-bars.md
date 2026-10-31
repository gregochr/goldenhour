### Added — near-miss shoulder days beside Coming up tide runs

A spring or king tide run whose edge narrowly misses the spring bar now shows that day beside the
run as an outlined bar, so 8–10 Nov still reads "8–10 Nov" while 7 and 11 Nov are drawn outlined and
captioned "outlined · just short of spring". The server decides which days: the roster's best
`1.25 × highest high water ÷ spring threshold` must be at least 1.20 (`coming-up.scoring.shoulder`,
one day per side by default). A shoulder carries no "top N%" pill and no PEAK, is named "just short of
spring size" in its accessible name and tooltip, and is dropped once it is before today. The wire gains
`dayRanges[].shoulder` (omitted when false); the build-time `shoulderDates` key is never served.
