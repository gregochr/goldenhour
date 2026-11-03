// Fixtures for the Scheduler's Day's jobs tab, shaped as SchedulerDayService serves GET /api/admin/scheduler/day.

import { NIGHTLY } from './schedulerFixtures.js';

/** One fire in the served day, a succeeded 55-second run at 01:00 UTC unless said. */
export function dayFire(overrides = {}) {
  return {
    at: '2026-11-03T01:00:00Z',
    status: 'SUCCEEDED',
    summary: null,
    startedAt: '2026-11-03T01:00:00Z',
    completedAt: '2026-11-03T01:00:55Z',
    trigger: 'SCHEDULED',
    fallback: false,
    runLinked: false,
    ...overrides,
  };
}

/** A scheduled job's entry in the served day ({@code kind: SCHEDULED}), one fire unless {@code fires} is given. */
export function dayJob(overrides = {}) {
  return {
    jobKey: NIGHTLY,
    displayName: 'Nightly batch evaluation',
    kind: 'SCHEDULED',
    fires: [dayFire()],
    ...overrides,
  };
}

/** A repeating job's entry in the served day: no fires, a tally and its problems. */
export function dayRepeatingJob(overrides = {}) {
  return {
    jobKey: 'aurora_polling',
    displayName: 'Aurora Polling',
    kind: 'REPEATING',
    schedule: { scheduleType: 'FIXED_DELAY', cronExpression: null, delayMs: 300000, timeZone: null },
    expectedCount: 144,
    counts: { RUNNING: 0, SUCCEEDED: 144, PARTIAL: 0, FAILED: 0, SKIPPED: 0 },
    problems: [],
    problemsOmitted: 0,
    ...overrides,
  };
}

/** The served {@code /day} answer: 3 Nov 2026 (a 24-hour GMT day) at noon, one scheduled job, nothing repeating. */
export function dayData(overrides = {}) {
  return {
    date: '2026-11-03',
    isToday: true,
    now: '2026-11-03T12:00:00Z',
    dayStart: '2026-11-03T00:00:00Z',
    dayEnd: '2026-11-04T00:00:00Z',
    zoneLabelAtStart: 'GMT',
    zoneLabelAtEnd: 'GMT',
    sunrise: '2026-11-03T07:03:00Z',
    sunset: '2026-11-03T16:30:00Z',
    jobs: [dayJob()],
    repeatingTally: { failedRuns: 0, jobsWithFailures: 0 },
    watchdog: [],
    notOnThisDay: [],
    ...overrides,
  };
}
