// Fixtures for the run-progress SSE feed, built from ONE place so a test cannot invent a field the
// server never sends or leave one out. They mirror RunProgressTracker field for field:
//   task-update  <- LocationTaskSnapshot (taskKey, locationName, targetDate, targetType, state,
//                   errorMessage, failedStep, lastUpdated)
//   run-summary  <- buildSummary: jobRunId, phase, total, completed, triaged, failed, inProgress,
//                   skipped, status, elapsedMs
//   run-complete <- buildRunCompleteEvent: jobRunId, status, phase, total, completed, triaged,
//                   failed, skipped, durationMs, failedTasks[{taskKey, locationName, errorMessage}],
//                   reason (null unless the run failed as a whole), retryable (false for a run stopped
//                   on a rejected API key and for a light-pollution run), retryBlockedReason (null when
//                   retryable; otherwise API_KEY_REJECTED, LIGHT_POLLUTION or NOT_FORECAST_SLOTS)
//                   (NO inProgress)
// and the counters and status are derived from the tasks the way RunProgress derives them.

const FINISHED = ['COMPLETE', 'FAILED', 'SKIPPED', 'TRIAGED'];

/** A task snapshot as the server serialises it. */
export const task = (taskKey, locationName, state, extra = {}) => ({
  taskKey,
  locationName,
  targetDate: '2026-10-02',
  targetType: 'SUNSET',
  state,
  errorMessage: state === 'FAILED' ? `Weather data fetch failed for ${locationName} SUNSET` : null,
  failedStep: state === 'FAILED' ? 'FETCHING_WEATHER' : null,
  lastUpdated: '2026-10-02T14:00:00Z',
  ...extra,
});

const count = (tasks, state) => tasks.filter((t) => t.state === state).length;

/**
 * RunProgress.getStatus(). With a run-level failure `reason`: PARTIAL when something completed or
 * was triaged, otherwise FAILED (a run with no tasks included); without one, the task-derived status,
 * where skipped slots beside failures do not soften FAILED.
 */
const statusOf = (tasks, reason = null) => {
  const total = tasks.length;
  const nothingLanded = count(tasks, 'COMPLETE') === 0 && count(tasks, 'TRIAGED') === 0;
  if (reason) return nothingLanded ? 'FAILED' : 'PARTIAL';
  if (total === 0) return 'COMPLETE';
  const finished = tasks.filter((t) => FINISHED.includes(t.state)).length;
  if (finished < total) return 'RUNNING';
  const failed = count(tasks, 'FAILED');
  if (failed === 0) return 'COMPLETE';
  return nothingLanded ? 'FAILED' : 'PARTIAL';
};

/** RunProgressTracker.buildSummary for the given tasks. */
export const summaryEvent = (jobRunId, tasks, { phase = 'FULL_EVALUATION', elapsedMs = 1200 } = {}) => ({
  jobRunId,
  phase,
  total: tasks.length,
  completed: count(tasks, 'COMPLETE'),
  triaged: count(tasks, 'TRIAGED'),
  failed: count(tasks, 'FAILED'),
  inProgress: tasks.filter((t) => !FINISHED.includes(t.state) && t.state !== 'PENDING').length,
  skipped: count(tasks, 'SKIPPED'),
  status: statusOf(tasks),
  elapsedMs,
});

/** RunProgressTracker.buildRunCompleteEvent for the given tasks. */
export const completeEvent = (
  jobRunId,
  tasks,
  {
    phase = 'FULL_EVALUATION', durationMs = 2100, reason = null, retryable = true,
    // A run that is not retryable is, by default, the one that was stopped on a rejected key.
    retryBlockedReason = retryable ? null : 'API_KEY_REJECTED',
  } = {},
) => ({
  jobRunId,
  status: statusOf(tasks, reason),
  phase,
  total: tasks.length,
  completed: count(tasks, 'COMPLETE'),
  triaged: count(tasks, 'TRIAGED'),
  failed: count(tasks, 'FAILED'),
  skipped: count(tasks, 'SKIPPED'),
  durationMs,
  failedTasks: tasks.filter((t) => t.state === 'FAILED').map((t) => ({
    taskKey: t.taskKey,
    locationName: t.locationName,
    errorMessage: t.errorMessage ?? '',
  })),
  reason,
  retryable,
  retryBlockedReason,
});

/** What the server says when a run stopped because Claude rejected the API key. */
export const KEY_REJECTED_RUN = 'Claude rejected the API key. The run was stopped; no further places were attempted.';
/** The per-place phrase for the places that did hit the rejection. */
export const KEY_REJECTED_PLACE = 'Claude rejected the API key.';
/** The per-place phrase for the places the stopped run never attempted. */
export const NOT_ATTEMPTED_PLACE = 'Not attempted: the run stopped because Claude rejected the API key.';

/** A FAILED task at the evaluating step, as the server publishes it for a Claude failure. */
export const evaluationFailed = (taskKey, locationName, errorMessage) => task(
  taskKey,
  locationName,
  'FAILED',
  { errorMessage, failedStep: 'EVALUATING' },
);

/**
 * A run stopped on a rejected key where nothing was triaged and nothing completed: one place rejected,
 * two never attempted. Its status is FAILED. A REAL run is rarely this shape (see KEY_REJECTED_TRIAGED).
 */
export const KEY_REJECTED_NOTHING_TRIAGED = [
  evaluationFailed('hill|a', 'Test Hill', KEY_REJECTED_PLACE),
  evaluationFailed('east|b', 'East Fell', NOT_ATTEMPTED_PLACE),
  evaluationFailed('west|c', 'West Fell', NOT_ATTEMPTED_PLACE),
];

/**
 * What production sends for a rejected key: places the weather triage stood down count as an outcome
 * (a triaged row is written), so with nothing completed the status is PARTIAL, not FAILED. Taken from a
 * real local run (completed 0, triaged 34, failed 66, skipped 20) at a size a test can read.
 */
export const KEY_REJECTED_TRIAGED = [
  task('tri|a', 'Triaged Hill', 'TRIAGED', { errorMessage: null, failedStep: null }),
  evaluationFailed('hill|b', 'Test Hill', KEY_REJECTED_PLACE),
  evaluationFailed('east|c', 'East Fell', NOT_ATTEMPTED_PLACE),
  task('skip|d', 'Skipped Fell', 'SKIPPED', { errorMessage: null, failedStep: null }),
];

/** The same stop after one place had already completed: stopped early, not an outright failure. */
export const KEY_REJECTED_AFTER_ONE = [
  task('done|a', 'Done Hill', 'COMPLETE'),
  evaluationFailed('hill|b', 'Test Hill', KEY_REJECTED_PLACE),
  evaluationFailed('east|c', 'East Fell', NOT_ATTEMPTED_PLACE),
];

/** A light-pollution (Bortle) task as the server registers it: no date, event BORTLE. */
export const bortleTask = (locationName, state, extra = {}) => task(
  `${locationName}|BORTLE`,
  locationName,
  state,
  {
    targetDate: '–',
    targetType: 'BORTLE',
    errorMessage: state === 'FAILED' ? 'API returned no data' : null,
    failedStep: null,
    ...extra,
  },
);

/** A light-pollution run in which one place got no answer from the light-pollution service. */
export const LIGHT_POLLUTION_ONE_FAILURE = [
  bortleTask('Test Hill', 'COMPLETE'),
  bortleTask('East Fell', 'FAILED'),
];

/** What the server sends for that run: not retryable, because its failed tasks are not forecast slots. */
export const LIGHT_POLLUTION_COMPLETE_OPTIONS = { retryable: false, retryBlockedReason: 'LIGHT_POLLUTION' };

/** The server's 202 for a retry that was given two slots and left one failed slot out. */
export const RETRY_ACCEPTED = {
  status: 'Retry run started',
  runType: 'VERY_SHORT_TERM',
  jobRunId: 6,
  slots: 2,
  skipped: [{
    locationName: 'West Fell',
    date: '2026-10-03',
    targetType: 'SUNRISE',
    reason: 'The place is disabled or no longer exists.',
  }],
};

/** A run with one rated place and two that failed to fetch weather. */
export const TWO_FAILURES = [
  task('hill|a', 'Test Hill', 'COMPLETE'),
  task('east|b', 'East Fell', 'FAILED'),
  task('west|c', 'West Fell', 'FAILED'),
];

/** A run in which every task finished cleanly. */
export const NO_FAILURES = [
  task('hill|a', 'Test Hill', 'COMPLETE'),
  task('east|b', 'East Fell', 'COMPLETE'),
  task('west|c', 'West Fell', 'SKIPPED'),
];

/** A run with exactly one failure. */
export const ONE_FAILURE = [
  task('hill|a', 'Test Hill', 'COMPLETE'),
  task('east|b', 'East Fell', 'FAILED'),
];

/**
 * A fake SSE feed: records the handlers `subscribeToRunProgress` was given for each run id (the
 * newest wins) and counts subscriptions, so a test can play a run into the panel the way the
 * server does — task updates, then run-summary, then run-complete — or replay a finished one.
 */
export const createFeeds = () => {
  const feeds = {};
  const subscriptions = [];
  const subscribe = (id, onTask, onSummary, onComplete, onError, onExpired) => {
    feeds[id] = { onTask, onSummary, onComplete, onError, onExpired };
    subscriptions.push(id);
    return () => {};
  };
  return { feeds, subscriptions, subscribe };
};
