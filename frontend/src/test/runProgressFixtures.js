// Fixtures for the run-progress SSE feed, built from ONE place so a test cannot invent a field the
// server never sends or leave one out. They mirror RunProgressTracker field for field:
//   task-update  <- LocationTaskSnapshot (taskKey, locationName, targetDate, targetType, state,
//                   errorMessage, failedStep, lastUpdated)
//   run-summary  <- buildSummary: jobRunId, phase, total, completed, triaged, failed, inProgress,
//                   skipped, status, elapsedMs
//   run-complete <- buildRunCompleteEvent: jobRunId, status, phase, total, completed, triaged,
//                   failed, skipped, durationMs, failedTasks[{taskKey, locationName, errorMessage}],
//                   reason (null unless the run failed as a whole) (NO inProgress)
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
  { phase = 'FULL_EVALUATION', durationMs = 2100, reason = null } = {},
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
});

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
