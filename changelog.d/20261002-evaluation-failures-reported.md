### Fixed — a hand-started run reports Claude failures as they happen, and stops when the API key is rejected

A place whose Claude evaluation failed was only caught by the completion sweep, which gave it the
placeholder "Evaluation failed (see server log).", so the admin could not tell a rate limit from an
overload from a bad key. And when the key was rejected the run carried on and called Claude for
every remaining place, each failing the same way (65 places over 47 seconds against an invalid key
on 2026-10-02).

`ForecastService.evaluateAndPersist` now publishes FAILED, once, at the moment an evaluation fails,
on every path: an error answered by Claude, an exception thrown by the evaluation call, and a
failure persisting the result. The step is `EVALUATING` and the reason is a fixed phrase from one
mapping, `EvaluationFailure`: *Claude rejected the API key.* (401, 403), *Claude's rate limit was
reached.* (429), *Claude was overloaded.* (529), *Claude returned a server error.* (other 5xx),
*Claude could not be reached.* (the SDK's I/O exception, which does not tell a timeout from a dropped
connection), *Claude's reply could not be read.* (a reply the parser rejected, a truncated reply, a
reply with no text), *Claude declined to evaluate this place.* (a refusal stop reason, or the
content-filter 400), and *Evaluation failed (see server log).* for anything else, including an open
circuit breaker, which carries no HTTP status. Never an exception's message or class name. The
refusal and unreadable-reply cases used to be told apart only by message text; they are now their own
exception types (still `IllegalStateException`s). The completion sweep stays as the floor.

A 401 or 403 from Claude stops that run (and only that run): the failure is recorded on the run's
progress inside the `claude` bulkhead, before the permit is released, so each place that was waiting
for a permit sees it and is published FAILED as "Not attempted: the run stopped because Claude
rejected the API key." without calling Claude and without creating a child `job_run`. Calls already in
flight finish and report the key reason. The `run-complete` payload carries "Claude rejected the API
key. The run was stopped; no further places were attempted." and a new `retryable: false`; a run that
had completed places reads as stopped early, one that had not as failed. A rate limit, an overload or a
server error does not stop the run. A later run is unaffected. (`ClaudeRetryPredicate` already did not
retry a 401 or 403.)

The `job_run` row now closes with the tracker's completed and failed task counts on every completion
path, so the Job Runs grid and the progress panel cannot disagree. Before, a place that failed at
weather was never counted unless the weather prefetch itself failed (an all-triaged-or-failed run
closed 0/0), and a wildlife run counted rows rather than places. A triaged or skipped place is neither
succeeded nor failed. The run-level reason is written to the row's existing `notes` column, which the
Job Runs grid already shows.

`RunProgressTracker` now broadcasts the task an event changed, not "the task with the newest
timestamp". Events arrive from many threads at once, and two that each stored a task and then looked
for the newest both found the later one, so the earlier task's update was never sent: a finished run's
panel could show a place frozen on "Cloud". It showed up the moment the evaluation phase began
publishing FAILED from parallel threads.

The progress panel does not offer Retry when `retryable` is false (re-running the failed places would
fail them the same way) and says so in one line: "Retry is not offered: fix the API key, then start the
run again." A payload without the field keeps the button.
