### Added — Coming up tide runs rank each day against the year

A tide-run entry's `dayRanges` now carry `rangePercentile` (how that day's range ranks among the past year's daily
ranges at the run's port, "top N%") and the entry carries `rangeAverageMetres` (the year's mean daily range), both
served only from a sample of at least 300 derivable days. The tide sparkline's delta is now measured against that
same average when it is served (a different statistic from the all-history figure it used, so deltas shrink), and a
merged supermoon-on-tide-run card's coincidence line reads "top N% of the year" in place of the metres on the
run's true-peak day. Backend only; the range bars draw them in the next change
(`docs/engineering/tide-range-rank-plan.md`, R1).
