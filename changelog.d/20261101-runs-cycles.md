### Changed — cycles open in the Runs panel, with a live strip

Operations → Runs now opens a cycle in its own panel (`#manage/runs/cycle/{id}`): what it is waiting on, why it failed or
degraded, how this afternoon's best bets compare with this morning's nightly, its phases as a timeline, and its batches,
each of which opens the batch's job-run panel and returns to the cycle. While a cycle is running a strip above the summary
shows its phase bar and what it waits on, and a count beside Runs in the navigation says how many runs are in flight.
Pipeline Runs is no longer a separate sub-tab: `#manage/pipelineruns` and `#manage/pipelineruns/{id}` now open Runs and the
cycle. Frontend only.
