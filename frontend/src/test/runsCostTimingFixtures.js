// Fixtures for the Runs Cost trends and Timing views, shaped by ONE place so a test cannot invent a field the server
// never sends. They mirror AdminRunsController's /cost and /timing JSON field for field (RunCostResponse,
// RunTimingResponse): an Instant arrives with a trailing Z, a date as YYYY-MM-DD, money as a number of pounds.

import { shiftDateStr } from '../utils/mapDates.js';

const COST_TYPES = ['ASK_READY', 'SKY_EVAL', 'OVERNIGHT', 'AFTERNOON', 'EVENING', 'BRIEFING', 'OTHER'];
const LABELS = {
  ASK_READY: 'Ask ready answers',
  SKY_EVAL: 'Sky eval',
  OVERNIGHT: 'Overnight cycle',
  AFTERNOON: 'Afternoon refresh',
  EVENING: 'Evening refresh',
  BRIEFING: 'Daily briefing',
  OTHER: 'Other jobs',
};

/** One served day: every type present, zero when it spent nothing. */
export const costDay = (date, byType = {}, over = {}) => {
  const full = Object.fromEntries(COST_TYPES.map((type) => [type, byType[type] ?? 0]));
  return {
    date, byType: full, totalGbp: Object.values(full).reduce((a, b) => a + b, 0), costComplete: true, ...over,
  };
};

/** Two periods of daily spend ending the day before {@code lastDay}: {@code 2 * rangeDays} consecutive days. */
export const costDays = (rangeDays = 14, lastDay = '2026-11-01', perDay = {}) => Array.from(
  { length: 2 * rangeDays },
  (_, i) => costDay(shiftDateStr(lastDay, i - (2 * rangeDays - 1)), perDay),
);

/** One served per-type row. */
export const costType = (type, over = {}) => ({
  type,
  label: LABELS[type],
  lastGbp: 10,
  priorGbp: 9,
  deltaGbp: 1,
  deltaPercent: 11,
  lastRuns: 14,
  priorRuns: 14,
  runsChangePercent: 0,
  perRunChangePercent: 11,
  avgLocationsLast: null,
  avgLocationsPrior: null,
  costComplete: true,
  ...over,
});

/** The handoff's worked example: spend up 14%, Ask ready answers the top contributor, Sky eval 13% of spend. */
export const costResponse = (over = {}) => ({
  rangeDays: 14,
  firstDay: '2026-10-05',
  lastDay: '2026-11-01',
  allTime: false,
  days: costDays(14, '2026-11-01', { ASK_READY: 2, SKY_EVAL: 0.5, OVERNIGHT: 1.5, AFTERNOON: 1 }),
  types: [
    costType('ASK_READY', {
      lastGbp: 27.66, priorGbp: 20.55, deltaGbp: 7.1, deltaPercent: 35, perRunChangePercent: 35,
      avgLocationsLast: 24, avgLocationsPrior: 18,
    }),
    costType('SKY_EVAL', {
      lastGbp: 16.39, priorGbp: 12.16, deltaGbp: 4.24, deltaPercent: 35, lastRuns: 5, priorRuns: 4,
      runsChangePercent: 25, perRunChangePercent: 8,
    }),
    costType('AFTERNOON', { lastGbp: 22.94, priorGbp: 19.39, deltaGbp: 3.55, deltaPercent: 18 }),
    costType('OVERNIGHT', { lastGbp: 38.31, priorGbp: 37.09, deltaGbp: 1.22, deltaPercent: 3 }),
    costType('BRIEFING', { lastGbp: 3.57, priorGbp: 3.67, deltaGbp: -0.1, deltaPercent: -3 }),
    costType('OTHER', {
      label: 'Other', lastGbp: 4.37, priorGbp: 5.11, deltaGbp: -0.75, deltaPercent: -15, runsChangePercent: -6,
      perRunChangePercent: -9,
    }),
  ],
  totals: {
    lastGbp: 130.73,
    priorGbp: 114.83,
    deltaGbp: 15.91,
    deltaPercent: 14,
    lastUsd: 175.18,
    fxRate: 0.75,
    perDayLast: 4.36,
    perDayPrior: 3.83,
    costComplete: true,
  },
  verdict: {
    direction: 'UP',
    percent: 14,
    topType: 'ASK_READY',
    topLabel: 'Ask ready answers',
    topDeltaGbp: 7.1,
    totalDeltaGbp: 15.91,
    runsAsOften: true,
    perRunMultiple: 1.3,
    askLocations: { last: 24, prior: 18 },
    skyEval: { sharePercent: 13, gbp: 16.39, changePercent: 35 },
  },
  ...over,
});

/** A served run of the Timing view: a day apart, ending 2026-11-01, each {@code durationSeconds} long. */
export const timingRuns = (durations, lastDay = '2026-11-01', hour = 12) => durations.map((durationSeconds, i) => {
  const date = shiftDateStr(lastDay, i - (durations.length - 1));
  return {
    id: `cycle-${100 + i}`,
    startedAt: `${date}T${String(hour).padStart(2, '0')}:00:00Z`,
    date,
    durationSeconds,
    sunsetAt: `${date}T16:40:00Z`,
  };
});

/** The served stats for a list of durations (the tests pass the figures the server would have computed). */
export const timingStats = (over = {}) => ({
  count: 10,
  medianSeconds: 1180,
  p90Seconds: 2250,
  slowest: { seconds: 3170, date: '2026-10-26' },
  fastest: { seconds: 440, date: '2026-10-20' },
  firstHalfMedianSeconds: 1120,
  secondHalfMedianSeconds: 1190,
  changePercent: 6,
  skewed: true,
  manualExcluded: false,
  ...over,
});

const TYPED_MEDIANS = {
  OVERNIGHT: 1393, AFTERNOON: 1187, EVENING: 974, ASK_READY: 106, BRIEFING: 40, SKY_EVAL: 5640,
};

/** An Afternoon refresh timing with a sunset trigger four hours ten before sunset. */
export const timingResponse = (over = {}) => ({
  type: 'AFTERNOON',
  typeLabel: 'Afternoon refresh',
  rangeDays: 60,
  measure: 'ready',
  runs: timingRuns([440, 700, 900, 1000, 1100, 1180, 1250, 1400, 2250, 3170]),
  stats: timingStats(),
  typeMedians: TYPED_MEDIANS,
  withoutAnswersLink: 0,
  trigger: { solarEvent: 'SUNSET', offsetMinutes: 250 },
  todaySunset: '2026-11-02T16:49:00Z',
  startsAt: null,
  typicalReadyAt: null,
  p90ReadyAt: null,
  worstReadyAt: null,
  readyBasis: null,
  sunriseToday: null,
  slackAtP90Minutes: null,
  ...over,
});

/** An Overnight cycle timing: a fixed 02:55 start, ready by 03:28 nine nights in ten, sunrise 06:50. */
export const overnightTiming = (over = {}) => timingResponse({
  type: 'OVERNIGHT',
  typeLabel: 'Overnight cycle',
  trigger: null,
  todaySunset: null,
  startsAt: '2026-11-02T02:55:00Z',
  typicalReadyAt: '2026-11-02T03:18:00Z',
  p90ReadyAt: '2026-11-02T03:28:00Z',
  worstReadyAt: '2026-11-02T03:52:00Z',
  readyBasis: 'ready',
  sunriseToday: '2026-11-02T06:50:00Z',
  slackAtP90Minutes: 202,
  ...over,
});

/** An all-time answer: {@code n} consecutive days up to 2026-11-01, one period, nothing before it. */
export const costAllTime = (n = 400, over = {}) => ({
  rangeDays: n,
  firstDay: shiftDateStr('2026-11-01', 1 - n),
  lastDay: '2026-11-01',
  allTime: true,
  days: Array.from({ length: n }, (_, i) => costDay(shiftDateStr('2026-11-01', i - (n - 1)), { SKY_EVAL: 1, OVERNIGHT: 0.5 })),
  types: [
    costType('SKY_EVAL', { lastGbp: 400, priorGbp: 0, deltaGbp: 400, deltaPercent: null, priorRuns: 0 }),
    costType('OVERNIGHT', { lastGbp: 200, priorGbp: 0, deltaGbp: 200, deltaPercent: null, priorRuns: 0 }),
  ],
  totals: {
    lastGbp: 600, priorGbp: 0, deltaGbp: 600, deltaPercent: null, lastUsd: 800, fxRate: 0.75,
    perDayLast: 1.5, perDayPrior: 0, costComplete: true,
  },
  verdict: {
    direction: 'FLAT', percent: null, topType: 'SKY_EVAL', topLabel: 'Sky eval', topDeltaGbp: 400,
    totalDeltaGbp: 600, runsAsOften: false, perRunMultiple: null, askLocations: null, skyEval: null,
  },
  ...over,
});
