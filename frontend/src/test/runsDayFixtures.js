// Fixtures for the Runs Day view, shaped by ONE place so a test cannot invent a field the server never sends. They
// mirror AdminRunsController's /day, /calendar and /calls JSON field for field (RunDayResponse, RunDayRow, RunFacts,
// RunDaySummary, RunCalendarResponse, RunCallsResponse): an Instant arrives with a trailing Z, a date as YYYY-MM-DD.

/** A finished job row (RunDayRow, kind JOB). Defaults to Tide refresh, job 9001, at 06:00 UTC on 2026-11-02. */
export const dayRow = (over = {}) => ({
  id: 'job-9001',
  kind: 'JOB',
  type: 'OTHER',
  typeLabel: 'Other jobs',
  name: 'Tide refresh',
  subline: 'Scheduled',
  startedAt: '2026-11-02T06:00:00Z',
  endedAt: '2026-11-02T06:01:12Z',
  estimated: false,
  durationSeconds: 72,
  costMicroDollars: 0,
  costComplete: true,
  costGbp: 0,
  costUsd: 0,
  status: 'DONE',
  note: null,
  noteTone: null,
  slow: false,
  parentCycleId: null,
  cycleId: null,
  jobRunId: 9001,
  facts: {
    startedAt: '2026-11-02T06:00:00Z',
    finishedAt: '2026-11-02T06:01:12Z',
    fasterThanPercent: null,
    batches: null,
    requests: null,
    answersReadyAt: null,
    expectedStart: null,
    expectedFinish: null,
  },
  ...over,
});

/** A finished cycle row (RunDayRow, kind CYCLE): the afternoon refresh, cycle 412. */
export const cycleDayRow = (over = {}) => dayRow({
  id: 'cycle-412',
  kind: 'CYCLE',
  type: 'AFTERNOON',
  typeLabel: 'Afternoon refresh',
  name: 'Afternoon refresh',
  subline: 'Cycle #412 · intraday · 3 batches',
  startedAt: '2026-11-02T12:41:00Z',
  endedAt: '2026-11-02T13:09:41Z',
  durationSeconds: 1721,
  costMicroDollars: 970_000,
  costGbp: 0.77,
  costUsd: 0.97,
  cycleId: 412,
  jobRunId: null,
  facts: {
    startedAt: '2026-11-02T12:41:00Z',
    finishedAt: '2026-11-02T13:09:41Z',
    fasterThanPercent: 3,
    batches: 3,
    requests: 216,
    answersReadyAt: '2026-11-02T13:15:00Z',
    expectedStart: null,
    expectedFinish: null,
  },
  ...over,
});

/** A scheduler fire still to come (RunDayRow, kind SCHEDULED). */
export const scheduledRow = (over = {}) => dayRow({
  id: 'sched-wildlife-1793640000',
  kind: 'SCHEDULED',
  type: 'OTHER',
  typeLabel: 'Other jobs',
  name: 'Wildlife comfort',
  subline: 'Scheduled · 3 locations',
  startedAt: '2026-11-02T15:30:00Z',
  endedAt: null,
  estimated: false,
  durationSeconds: 37,
  costMicroDollars: 30_000,
  costGbp: 0.03,
  costUsd: 0.04,
  status: 'SCHEDULED',
  jobRunId: null,
  facts: {
    startedAt: null,
    finishedAt: null,
    fasterThanPercent: null,
    batches: null,
    requests: null,
    answersReadyAt: null,
    expectedStart: '2026-11-02T15:30:00Z',
    expectedFinish: '2026-11-02T15:30:37Z',
  },
  ...over,
});

/** A sky-rating eval row (RunDayRow, kind SKY_EVAL). */
export const evalRow = (over = {}) => dayRow({
  id: 'eval-5',
  kind: 'SKY_EVAL',
  type: 'SKY_EVAL',
  typeLabel: 'Sky eval',
  name: 'Sky eval',
  subline: 'Weekly',
  startedAt: '2026-11-02T04:00:00Z',
  endedAt: '2026-11-02T05:24:00Z',
  durationSeconds: 5040,
  costMicroDollars: 4_400_000,
  costGbp: 3.48,
  costUsd: 4.4,
  jobRunId: null,
  ...over,
});

/** The "today" summary group (RunDaySummary); the past group is null. */
export const todaySummary = (over = {}) => ({
  finished: 5,
  running: 0,
  stillToRun: 8,
  runs: null,
  cycles: null,
  jobs: null,
  needAttention: 0,
  partial: 0,
  failed: 0,
  attentionNames: [],
  spendGbp: 1.8,
  spendComplete: true,
  projectedGbp: 3.83,
  avg30Gbp: 4.36,
  vsAvgPercent: null,
  nextUp: { name: 'Afternoon refresh', at: '2026-11-02T12:39:00Z', estimated: false },
  slowestCycle: null,
  ...over,
});

/** The past-day summary group (RunDaySummary); the today group is null. */
export const pastSummary = (over = {}) => ({
  finished: null,
  running: null,
  stillToRun: null,
  runs: 14,
  cycles: 3,
  jobs: 11,
  needAttention: 2,
  partial: 1,
  failed: 1,
  attentionNames: [],
  spendGbp: 7.43,
  spendComplete: true,
  projectedGbp: null,
  avg30Gbp: 4.36,
  vsAvgPercent: 71,
  nextUp: null,
  slowestCycle: { name: 'Afternoon refresh', durationSeconds: 1721, medianSeconds: 930 },
  ...over,
});

/** Counts per chip, from the rows given. */
export function countsOf(rows) {
  const cycle = (r) => ['OVERNIGHT', 'AFTERNOON', 'EVENING'].includes(r.type);
  return {
    ALL: rows.length,
    CYCLES: rows.filter(cycle).length,
    ASK_READY: rows.filter((r) => r.type === 'ASK_READY').length,
    BRIEFING: rows.filter((r) => r.type === 'BRIEFING').length,
    SKY_EVAL: rows.filter((r) => r.type === 'SKY_EVAL').length,
    OTHER: rows.filter((r) => r.type === 'OTHER').length,
    ATTENTION: rows.filter((r) => ['PARTIAL', 'FAILED'].includes(r.status)).length,
  };
}

/** GET /api/admin/runs/day (RunDayResponse): today (2026-11-02, 09:26 UTC), the rows given. */
export const dayResponse = (runs = [], over = {}) => ({
  date: '2026-11-02',
  isToday: true,
  earliestDay: '2026-09-20',
  sunrise: '07:42',
  sunset: '16:49',
  sunriseAt: '2026-11-02T07:42:00Z',
  sunsetAt: '2026-11-02T16:49:00Z',
  dayStartUtc: '2026-11-02T00:00:00Z',
  dayEndUtc: '2026-11-03T00:00:00Z',
  now: '2026-11-02T09:26:00Z',
  runs,
  summary: todaySummary(),
  filterCounts: countsOf(runs),
  ...over,
});

/** A past day (2026-11-01) of the rows given. */
export const pastDayResponse = (runs = [], over = {}) => dayResponse(runs, {
  date: '2026-11-01',
  isToday: false,
  now: null,
  dayStartUtc: '2026-11-01T00:00:00Z',
  dayEndUtc: '2026-11-02T00:00:00Z',
  summary: pastSummary(),
  ...over,
});

/** GET /api/admin/runs/calendar (RunCalendarResponse). */
export const calendarResponse = (days = [], over = {}) => ({
  month: '2026-11',
  days,
  maxSpendGbp: Math.max(0, ...days.map((d) => d.spendGbp)),
  earliestDay: '2026-09-20',
  ...over,
});

/** One calendar day (RunCalendarResponse.CalendarDay). */
export const calendarDay = (date, over = {}) => ({
  date, runs: 14, spendGbp: 4.12, spendComplete: true, attention: 0, ...over,
});

/** One service of a run's calls (RunCallsResponse.CallService). */
export const callService = (over = {}) => ({
  service: 'open-meteo',
  label: 'Open-Meteo',
  purpose: 'Forecast · cloud, visibility, wind',
  calls: 27,
  durationMs: 304,
  batches: null,
  requests: null,
  costMicroDollars: 0,
  costGbp: 0,
  costUsd: 0,
  errors: 0,
  status: 'OK',
  errorSummary: null,
  ...over,
});

/** GET /api/admin/runs/calls (RunCallsResponse). */
export const callsResponse = (services = [callService()], over = {}) => ({
  runId: 'job-9001', logged: true, services, ...over,
});
