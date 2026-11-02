### Changed — Weekly PIT mutation run is capped at 3 hours

The weekly PIT mutation workflow now has a 3-hour job timeout instead of GitHub's 6-hour default. It shares the single self-hosted runner with Deploy to Production, and the last four Monday runs ran into the 6-hour limit and were cancelled, one of them blocking a release deploy for over an hour. The slowest successful run took about 2h23m, so 3 hours leaves headroom without letting a stuck run hold the runner.
