### Fixed — a finished run's panel no longer leaves rows on "Pending"; a retry leaves out yesterday's slots

- **Rows left unfinished in a run that had finished.** In a very fast run (every place failing at weather
  within ~100 ms) a few rows of the progress panel stayed on "Pending" or "Weather" although the summary
  said all of them had failed. Cause, in `RunProgressTracker.subscribe`: it registered the emitter and
  then replayed from a copy of the tasks frozen at that moment, while `onTaskEvent` took no lock, so a
  worker could send a task's live FAILED while the replay still held that task's older copy, which was
  then sent AFTER it, and the panel keeps the last event it receives. Each run now has one stream lock
  (`RunProgress.streamLock()`) held by `onTaskEvent` (update and broadcast), `setPhase` (its re-send)
  and `subscribe` (copy and replay), so a subscriber sees each task's states in order. Lock order: the
  global `completionLock` first, then the run's stream lock, never the reverse (`subscribe`,
  `completeRun` and `failRun` take both in that order; `onTaskEvent` and `setPhase` take only the stream
  lock; the expiry check only the completion lock). The panel also refuses an update that would move a
  finished row (complete, failed, skipped, triaged) back to an unfinished state.
- **A retry leaves out slots dated before today.** The executor's already-past gate guards only today, so
  a retry pressed just after midnight sent yesterday's failed slots to triage and Claude. They are now
  left out and listed in `skipped` ("The event has already happened."), by the UK civil date; when every
  failed slot is past the answer is the existing 404. A renamed place is reported as "The place is
  disabled, renamed or no longer exists."
- **The note on a retry's panel** ("Retrying N slots. Left out: ...") now survives a switch of Operations
  tab (it is held beside the active run id), names at most three left-out slots then "and N more", and
  never prints "null" for a missing date.
