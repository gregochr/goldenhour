### Added — the Runs screen's Cost trends and Timing endpoints

Two admin endpoints, `GET /api/admin/runs/cost` and `GET /api/admin/runs/timing`, serve what the Runs deep dive's
Cost trends and Timing views need. Cost trends compares the last 14, 30 or 60 full UK days with the same number
before them: spend by day and by run type, the biggest movers with their run count and cost per run, the dollar
total at the stored exchange rate, and the verdict (up, down or flat, the top contributor, whether it runs as often
as before, and the sky eval's share) as structured fields. Spend comes only from the recorded run costs, never from
the call log, and a cost still accruing marks its day incomplete instead of counting as nothing. Timing serves one
run type's durations over 30, 60 or 120 days with median, 9-in-10, slowest, fastest, first-half and second-half
medians and a skew flag, each run's own-day sunset, the Afternoon refresh's sun-relative trigger, and for the fixed-time
cycles when their results are typically ready. Cycles can be measured to the end of their ask-ready answers. No screen
reads the endpoints yet; the views arrive in a following change.
