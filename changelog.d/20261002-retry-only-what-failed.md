### Fixed — "Retry failed" re-runs exactly the slots that failed, under the run's own type

`POST /api/forecast/run/{id}/retry-failed` took the failed *places*, the failed *dates* and ran every
combination of them, both events, as a Short-Term run. A run in which one place's Saturday sunset and
another's Sunday sunrise failed re-evaluated eight slots instead of two, re-billing the ones that had
succeeded, and a Very-Short-Term, Long-Term or single-place run was retried under Short-Term's model
and strategy settings. A light-pollution run's failed tasks carry the date "–", which the endpoint
tried to parse as a date and answered 500; and a run stopped on a rejected API key could still be
retried by calling the endpoint directly.

- **Exact slots.** `ForecastCommand` gains an explicit `slots` set of (location, date, sunrise or
  sunset) triples. The executor builds a task only for a triple in that set, so the retry run's
  progress holds exactly the slots that were FAILED in the original run's tracker entry (a slot named
  twice is run once). A slot whose event has passed since is dropped by the executor's existing
  already-past gate and shows as *skipped* in the retry's panel; weather triage still applies, so a
  slot the weather now rules out is *triaged*, not evaluated.
- **The original run type.** `FailedSlotRetryService` reads the original run's type from its
  `job_run` row and starts the retry under it, so the model and strategies are Very-Short-Term's,
  Short-Term's or Long-Term's as the case may be. ⚠️ "Original settings" can only mean the **current**
  configuration of that run type: a hand-started run stores no `active_strategies` snapshot and a null
  `evaluation_model`, so what it ran under is not recoverable, and a model or strategy changed on the
  Run Config screen since the original run is picked up by the retry. Stability filtering stays
  bypassed (a manual run); there are no exclusions to carry, since an excluded slot was never a task.
- **Sentinel sampling does not apply to a retry of named slots.** Sentinel sampling evaluates a
  region's sentinel places and, when they all rate low, writes a canned low result for the region's
  other slots without calling Claude. Over a handful of named slots that would pick a sentinel from
  among them and answer for the rest, which is a guess recorded as a result, not a retry. Each named
  slot is evaluated. The other strategy, tide alignment, is a per-slot check and still applies.
- **A failed slot that cannot be run again is left out and said so.** If its place has been disabled or
  deleted, or is no longer a sky location, the 202 lists it under `skipped` with a reason and the new
  run's panel shows it after "Retrying N slots."; when nothing is left the answer is the existing 404
  ("Nothing to retry"). The 202 also carries `slots` (how many the new run holds) and the original run
  type in `runType` (it used to say `SHORT_TERM` always).
- **Refused, with a plain `{error}` and nothing started (409).** A run stopped on a rejected key:
  *This run was stopped because Claude rejected the API key. Fix the key, then start the run again.*
  A light-pollution run: *Light-pollution failures are retried by pressing Refresh Light Pollution
  again.* (any other run whose failed tasks are not forecast slots gets a generic sentence).
- **The panel knows why.** The `run-complete` payload gains `retryBlockedReason` (`API_KEY_REJECTED`,
  `LIGHT_POLLUTION`, `NOT_FORECAST_SLOTS`, null when retryable) beside #985's `retryable`, both read from
  the one `RunProgress.getRetryBlock()` the endpoint also uses, so the panel never offers a retry the
  server would refuse. A light-pollution run with failures shows *Retry is not offered: press Refresh
  Light Pollution again.* in place of the button; the rejected-key line is unchanged.

No migration, and the stop-on-rejected-key logic, failure classification, `job_run` counts, circuit
breaker, eviction and the batch pipeline are untouched.
