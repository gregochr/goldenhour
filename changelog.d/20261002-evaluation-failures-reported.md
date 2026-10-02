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
content-filter 400), and *Evaluation failed (see server log).* for anything else. Never an exception's
message or class name. The completion sweep stays as the floor. Two refusals where no request was made
get their own phrases and do not stop the run: *Not attempted: Claude calls are paused after repeated
failures. Try again in a minute.* when the circuit breaker refuses a call (it opens after the first
wave of rejected calls and stays open for 60 s, so a rerun inside that minute meets it), and *Not
attempted: too many Claude calls were already waiting.* when the `claude` bulkhead gives up after its
wait.

The engine's `errorType` vocabulary changed, and this is stated plainly: a content-filter 400 is now
`content_filter` (it was `anthropic_400`), a refusal is `refusal` and a truncated, empty or no-text reply
is `reply_unreadable` (all three were `IllegalStateException`), a call the circuit breaker refuses is
`circuit_open` (it was `CallNotPermittedException`) and a bulkhead refusal is `bulkhead_full`. Refusal
and unreadable replies are their own exception types (still `IllegalStateException`s), and the aurora
and strategy engines' "no text" failures use the same type as the forecast engine's. The string is not
persisted for a synchronous call (`api_call_log.error_type` is written only by the batch path, from the
batch outcome) and nothing outside `EvaluationFailure` reads it, so no stored value or reader changes.

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

A finished run's panel header reads "(Stopped early)" for a PARTIAL run that carries a reason (a real
rejected-key run is PARTIAL, because triaged places count as an outcome, and used to read "(Complete)"
above a red "the run was stopped" line); FAILED still reads "(Failed)". A stopped run ends in phase
`EARLY_STOP` whichever exit the pipeline took.

The legacy wildlife path (no trigger reaches it) now closes its `job_run` with tasks, not hourly rows,
as its succeeded count.

The progress panel does not offer Retry when `retryable` is false (re-running the failed places would
fail them the same way) and says so in one line: "Retry is not offered: fix the API key, then start the
run again." (only on a run that has failed places; where Retry would have been). A payload without
the field keeps the button.
