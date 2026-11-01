// Fixtures for the Scheduler tab, shaped by ONE place so a test cannot invent a field the server never sends. They
// mirror SchedulerController's job DTO field for field (including the R4 additions: runGroup, and lastRun, which is a
// RunListItem or null) and the solar preview's shape.

import { cycleItem } from './runsFixtures.js';

export const NIGHTLY = 'near_term_batch_evaluation';
export const INTRADAY = 'intraday_forecast_refresh';
export const EVENING = 'evening_sunrise_refresh';

const HOUR_MS = 3_600_000;

/** An ISO instant {@code h} hours from the real now (negative for the past). */
export const inHours = (h) => new Date(Date.now() + h * HOUR_MS).toISOString();

/** A served scheduler job: a nightly cycle job on a plain cron unless said otherwise. */
export function job(overrides = {}) {
  return {
    id: 1,
    jobKey: NIGHTLY,
    displayName: 'Nightly batch evaluation',
    description: 'The morning forecast cycle',
    scheduleType: 'CRON',
    timeZone: 'UTC',
    cronExpression: '0 0 1 * * *',
    solarEvent: '',
    solarOffsetMinutes: 0,
    fixedDelayMs: 0,
    initialDelayMs: 0,
    status: 'ACTIVE',
    lastFireTime: '',
    lastCompletionTime: '',
    nextFireTime: '2026-10-14T00:00:00Z',
    configSource: '',
    updatedAt: '2026-10-13T00:00:00Z',
    runGroup: null,
    lastRun: null,
    ...overrides,
  };
}

/** The tide refresh job (a weekly cron whose scheduled runs the server can attribute). */
export const tideJob = (overrides = {}) => job({
  id: 2,
  jobKey: 'tide_refresh',
  displayName: 'Tide Refresh',
  description: 'Refreshes tide extremes weekly',
  cronExpression: '0 0 2 * * MON',
  runGroup: 'TIDE',
  ...overrides,
});

/** The aurora poll (a fixed-delay job whose runs the server cannot attribute). */
export const pollingJob = (overrides = {}) => job({
  id: 3,
  jobKey: 'aurora_polling',
  displayName: 'Aurora Polling',
  description: 'Polls NOAA SWPC for aurora activity',
  scheduleType: 'FIXED_DELAY',
  cronExpression: '',
  fixedDelayMs: 300000,
  initialDelayMs: 60000,
  nextFireTime: '',
  ...overrides,
});

/** A sun-relative nightly cycle job. */
export const solarJob = (overrides = {}) => job({
  scheduleType: 'SOLAR_OFFSET',
  solarEvent: 'SUNRISE',
  solarOffsetMinutes: 255,
  nextFireTime: '2026-10-14T02:00:00Z',
  runGroup: 'CYCLES',
  ...overrides,
});

/** A sun-relative intraday cycle job. */
export const solarIntradayJob = (overrides = {}) => job({
  id: 4,
  jobKey: INTRADAY,
  displayName: 'Intraday refresh',
  scheduleType: 'SOLAR_OFFSET',
  solarEvent: 'SUNSET',
  solarOffsetMinutes: 240,
  runGroup: 'CYCLES',
  ...overrides,
});

/** A job run the scheduler wrote, as the list serves it (a cycle). */
export const lastRunCycle = (overrides = {}) => cycleItem({
  id: 412,
  status: 'DEGRADED',
  note: 'Briefing skipped',
  noteTone: 'WARN',
  startedAt: inHours(-3),
  endedAt: inHours(-1),
  durationSeconds: 7200,
  ...overrides,
});

/** One day's computed fire in the solar preview. */
export function day(overrides = {}) {
  return {
    date: '2026-10-13',
    eventInstant: '2026-10-13T06:40:00Z',
    fireInstant: '2026-10-13T02:25:00Z',
    clamped: false,
    fallback: false,
    fallbackReason: null,
    ...overrides,
  };
}

/** One cycle's entry of the solar preview (a proposed rule for a job still on cron). */
export function previewJob(overrides = {}) {
  return {
    jobKey: NIGHTLY,
    cycleType: 'NIGHTLY',
    scheduleType: 'CRON',
    status: 'ACTIVE',
    storedCron: '0 0 1 * * *',
    solarEvent: 'SUNRISE',
    offsetMinutes: 255,
    ruleSource: 'PROPOSED',
    today: day({ fireInstant: inHours(-1) }),
    tomorrow: day({ date: '2026-10-14', fireInstant: inHours(3) }),
    earliest: { date: '2027-06-18', fireLocal: '00:13', fireInstant: '2027-06-17T23:13:00Z' },
    latest: { date: '2026-12-29', fireLocal: '04:15', fireInstant: '2026-12-29T04:15:00Z' },
    medianDurationSeconds: 5400,
    ...overrides,
  };
}

/** The solar preview response. */
export function preview(jobs) {
  return {
    referencePoint: { lat: 54.776, lon: -1.575 },
    generatedAt: '2026-10-13T12:00:00Z',
    jobs,
  };
}

export const INTRADAY_PREVIEW = previewJob({
  jobKey: INTRADAY,
  cycleType: 'INTRADAY',
  solarEvent: 'SUNSET',
  offsetMinutes: 240,
  storedCron: '0 0 14 * * *',
});

export const BOTH_PREVIEW = preview([previewJob(), INTRADAY_PREVIEW]);
