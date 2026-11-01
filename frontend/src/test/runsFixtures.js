// Fixtures for the Runs tab, shaped by ONE place so a test cannot invent a field the server never sends. They mirror
// AdminRunsController's JSON field for field (RunListItem, RunListResponse, RunListSummary, RunJobDetail, JobRunDto,
// RunBatchInfo): an Instant arrives with a trailing Z, a job run's own LocalDateTime arrives bare (UTC).

/** A cycle row (RunListItem, kind CYCLE). */
export const cycleItem = (over = {}) => ({
  kind: 'CYCLE',
  id: 412,
  group: 'CYCLES',
  name: 'Afternoon refresh',
  startedAt: '2026-10-31T15:00:00Z',
  endedAt: null,
  durationSeconds: null,
  status: 'RUNNING',
  note: 'Waiting on 2 of 4 batches',
  noteTone: 'INFO',
  costMicroDollars: 770_000,
  costGbpRate: 0.79,
  batchesDone: 2,
  batchesTotal: 4,
  succeeded: null,
  failed: null,
  locationsProcessed: null,
  cycleType: 'INTRADAY',
  batchCount: 4,
  runType: null,
  triggeredManually: null,
  batchSummary: null,
  ...over,
});

/** A job-run row (RunListItem, kind JOB). */
export const jobItem = (over = {}) => ({
  kind: 'JOB',
  id: 9001,
  group: 'TIDE',
  name: 'Tide refresh',
  startedAt: '2026-10-31T06:00:00Z',
  endedAt: '2026-10-31T06:01:12Z',
  durationSeconds: 72,
  status: 'DONE',
  note: null,
  noteTone: null,
  costMicroDollars: 0,
  costGbpRate: 0.79,
  batchesDone: null,
  batchesTotal: null,
  succeeded: 61,
  failed: 0,
  locationsProcessed: 61,
  cycleType: null,
  batchCount: null,
  runType: 'TIDE',
  triggeredManually: false,
  batchSummary: null,
  ...over,
});

/** The summary block (RunListSummary). */
export const summary = (over = {}) => ({
  runs: 64,
  cycles: 21,
  other: 43,
  needAttention: 3,
  degraded: 1,
  partial: 1,
  failed: 1,
  spendMicroDollars: 23_310_000,
  spendGbpRate: 0.79,
  medianOvernightSeconds: 8340,
  medianOvernightSample: 7,
  ...over,
});

/** A page of GET /api/admin/runs (RunListResponse). */
export const runsPage = (items, over = {}) => ({
  items,
  nextCursor: null,
  total: items.length,
  summary: summary(),
  kindCounts: {
    ALL: 64, CYCLES: 21, BATCHES: 6, TIDE: 8, BRIEFING: 11, AURORA: 14, OTHER: 4,
  },
  attentionCount: 3,
  runningCount: 0,
  liveCycleId: null,
  nextOvernightAt: null,
  truncated: false,
  ...over,
});

/** The job run itself (JobRunDto): its LocalDateTime fields arrive bare. */
export const jobRun = (over = {}) => ({
  id: 9001,
  runType: 'TIDE',
  evaluationModel: null,
  startedAt: '2026-10-31T06:00:00',
  completedAt: '2026-10-31T06:01:12',
  durationMs: 72_000,
  locationsProcessed: 0,
  succeeded: 61,
  failed: 0,
  minTargetDate: null,
  maxTargetDate: null,
  totalCostMicroDollars: 0,
  exchangeRateGbpPerUsd: 0.8,
  activeStrategies: null,
  appVersion: null,
  notes: null,
  batchSummary: null,
  ...over,
});

/** GET /api/admin/runs/job/{id} (RunJobDetail). {@code top} overrides the served fields beside {@code run}. */
export const jobDetail = (runOver = {}, top = {}) => ({
  run: jobRun(runOver),
  status: 'DONE',
  group: 'TIDE',
  name: 'Tide refresh',
  note: null,
  noteTone: null,
  triggeredManually: false,
  batch: null,
  ...top,
});

/** A forecast batch's row (RunBatchInfo). */
export const batchInfo = (over = {}) => ({
  anthropicBatchId: 'msgbatch_01JBXKQ7Q7Q7Q7Q7Q7Q7',
  batchType: 'FORECAST',
  status: 'COMPLETED',
  pipelineRunId: null,
  ...over,
});
