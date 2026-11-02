// Fixtures for the Sky Eval page, shaped by ONE place so a test cannot invent a field the server never sends. They
// mirror SkyEvalOverview (backend/.../model/skyeval/SkyEvalOverview.java) field for field: percentages 0 to 100,
// costs in pounds, instants ISO-8601 UTC, trigger SCHEDULED (shown as WEEKLY).

export const NOW_ISO = '2026-11-02T09:26:00Z';
export const HEAD_SHA = 'a91b0e2c4d5e6f708192a3b4c5d6e7f801234567';
export const PREV_SHA = 'c41e2d8f4d5e6f708192a3b4c5d6e7f801234567';

export const HEAD = {
  sha: HEAD_SHA, shortSha: 'a91b0e2', message: 'Prompt: stricter cirrus penalty', committedAt: '2026-11-02T07:51:00Z',
};
export const PREV = {
  sha: PREV_SHA, shortSha: 'c41e2d8', message: 'Scorer: per-fixture band tolerance', committedAt: '2026-10-24T09:00:00Z',
};

/** A served model row; the defaults are a fresh eval-only model with a baseline and no change. */
export function modelStats(overrides = {}) {
  return {
    model: 'HAIKU_55',
    label: 'Haiku 5.5',
    lanes: [],
    last4WeeklyPct: 96.3,
    last4Below: 6,
    last4Above: 5,
    last4Ratings: 320,
    onHeadPct: 90.4,
    onHeadRuns: 3,
    previousVersionPct: 95.6,
    deltaPts: -5.2,
    status: 'DROPPED',
    costPerRunGbp: 0.01,
    costPerRatingGbp: 0.000125,
    secondsPerRating: 0.5,
    lastTestedAt: '2026-11-02T08:36:00Z',
    fresh: true,
    ...overrides,
  };
}

/** A served run inside a session. */
export function run(overrides = {}) {
  return {
    id: 62, model: 'HAIKU', fixtureCount: 10, runsPerFixture: 8, ratings: 80, inBand: 76, below: 2, above: 2,
    inBandPct: 95, costGbp: 0.09, durationMs: 135000, status: 'COMPLETED', errorMessage: null, progress: null,
    ...overrides,
  };
}

/** A served session. */
export function session(overrides = {}) {
  return {
    id: 56,
    trigger: 'MANUAL',
    version: HEAD_SHA,
    startedAt: '2026-11-02T08:36:00Z',
    status: 'DONE',
    costGbp: 0.6,
    runs: [run({ id: 56, model: 'HAIKU_55', inBandPct: 96.3 }), run({ id: 57, model: 'SONNET_55', inBandPct: 98.8 })],
    byModel: [
      { model: 'HAIKU_55', runCount: 3, inBandPct: 90.4 },
      { model: 'SONNET_55', runCount: 2, inBandPct: 99.4 },
    ],
    ...overrides,
  };
}

/** The whole overview: two lanes (Sonnet 5.5 held, Haiku 4.5 held), Haiku 5.5 dropped but not production. */
export function overview(overrides = {}) {
  const sonnet = modelStats({
    model: 'SONNET_55', label: 'Sonnet 5.5', lanes: ['BATCH_NEAR_TERM'], last4WeeklyPct: 98.8, last4Below: 1,
    last4Above: 3, onHeadPct: 99.4, onHeadRuns: 2, previousVersionPct: 97.5, deltaPts: 1.9, status: 'HELD',
    costPerRunGbp: 0.28, costPerRatingGbp: 0.0035, secondsPerRating: 1.4,
  });
  const haiku = modelStats({
    model: 'HAIKU', label: 'Haiku 4.5', lanes: ['BATCH_FAR_TERM'], last4WeeklyPct: 93.4, last4Below: 11,
    last4Above: 10, onHeadPct: 91.9, onHeadRuns: 2, previousVersionPct: 93.8, deltaPts: -1.9, status: 'HELD',
    costPerRunGbp: 0.08, costPerRatingGbp: 0.001, secondsPerRating: 0.8,
  });
  const haiku55 = modelStats({ lanes: [] });
  const opus = modelStats({
    model: 'OPUS', label: 'Opus', last4WeeklyPct: 97.8, onHeadPct: null, onHeadRuns: 0, previousVersionPct: null,
    deltaPts: null, status: 'UNCHECKED', costPerRunGbp: 0.61, costPerRatingGbp: 0.0076, secondsPerRating: 3,
    lastTestedAt: '2026-10-04T05:00:00Z', fresh: false,
  });
  return {
    head: HEAD,
    schedule: { label: 'weekly, Sun 04:00', jobStatus: 'ACTIVE', models: ['SONNET_55', 'HAIKU', 'HAIKU_55'] },
    lanes: [
      {
        runType: 'BATCH_NEAR_TERM', label: 'T and T+1 forecasts', purpose: 'Must be as accurate as possible',
        model: 'SONNET_55',
        compared: [
          {
            model: 'HAIKU', lanes: ['BATCH_FAR_TERM'], last4WeeklyPct: 93.4, deltaPts: -5.3, costMultiple: 0.3,
            fellToOnHeadPct: null,
          },
          {
            model: 'HAIKU_55', lanes: [], last4WeeklyPct: 96.3, deltaPts: -2.5, costMultiple: 0.04,
            fellToOnHeadPct: 90.4,
          },
          {
            model: 'OPUS', lanes: [], last4WeeklyPct: 97.8, deltaPts: -1, costMultiple: 2.2, fellToOnHeadPct: null,
          },
        ],
      },
      {
        runType: 'BATCH_FAR_TERM', label: 'T+2 and T+3 look-ahead',
        purpose: 'Cheaper, needs to be accurate enough to plan with', model: 'HAIKU',
        compared: [
          {
            model: 'SONNET_55', lanes: ['BATCH_NEAR_TERM'], last4WeeklyPct: 98.8, deltaPts: 5.3, costMultiple: 3.3,
            fellToOnHeadPct: null,
          },
        ],
      },
    ],
    fixtures: [
      { name: 'copt-hill-11mar-false-positive', bandLo: 1, bandHi: 2, firstSeenAt: '2026-07-01T04:00:00Z' },
      { name: 'seaham-28sep-low-sun-cirrus', bandLo: 3, bandHi: 5, firstSeenAt: '2026-09-28T04:00:00Z' },
    ],
    versions: [HEAD, PREV],
    sessions: [
      session(),
      session({
        id: 40, trigger: 'SCHEDULED', version: PREV_SHA, startedAt: '2026-11-01T04:00:00Z', costGbp: 0.37,
        runs: [run({ id: 40, model: 'SONNET_55', inBandPct: 97.5 })],
        byModel: [{ model: 'SONNET_55', runCount: 1, inBandPct: 97.5 }, { model: 'HAIKU', runCount: 1, inBandPct: 93.8 }],
      }),
    ],
    runFixtures: [],
    models: [sonnet, opus, haiku55, haiku],
    modelFixtures: [],
    verdict: {
      kind: 'HELD',
      headline: "Both production models held up on today's change.",
      model: null,
      lines: [
        { model: 'SONNET_55', label: 'Sonnet 5.5', beforePct: 97.5, afterPct: 99.4 },
        { model: 'HAIKU', label: 'Haiku 4.5', beforePct: 93.8, afterPct: 91.9 },
      ],
      droppedNonProduction: true,
    },
    comparisons: [
      {
        version: HEAD,
        rows: [
          {
            model: 'SONNET_55', lanes: ['BATCH_NEAR_TERM'], beforePct: 97.5, afterPct: 99.4, deltaPts: 1.9,
            notRun: false, moved: [],
          },
          {
            model: 'HAIKU_55', lanes: [], beforePct: 95.6, afterPct: 90.4, deltaPts: -5.2, notRun: false,
            moved: [{
              fixture: 'copt-hill-16mar-horizon-strip', beforePct: 94, afterPct: 67, direction: 'WORSE',
              missSide: 'BELOW',
            }],
          },
          {
            model: 'OPUS', lanes: [], beforePct: null, afterPct: null, deltaPts: null, notRun: true, moved: [],
          },
        ],
      },
      {
        version: PREV,
        rows: [
          {
            model: 'SONNET_55', lanes: ['BATCH_NEAR_TERM'], beforePct: 96, afterPct: 97.5, deltaPts: 1.5,
            notRun: false,
            moved: [{
              fixture: 'seaham-28sep-low-sun-cirrus', beforePct: 40, afterPct: 80, direction: 'BETTER',
              missSide: null,
            }],
          },
        ],
      },
    ],
    checkDefaults: {
      models: ['SONNET_55', 'HAIKU'],
      runsPerFixture: 3,
      missedLastTime: ['copt-hill-11mar-false-positive'],
    },
    ...overrides,
  };
}

/** A served per-run, per-fixture row (n attempts, how many in band, below and above, mean/min/max rating). */
export function runFixture(runId, fixture, overrides = {}) {
  return {
    runId, fixture, n: 8, inBand: 8, below: 0, above: 0, mean: 3, min: 3, max: 3, ...overrides,
  };
}

/** The selectable models the models endpoint serves. */
export const AVAILABLE = {
  available: [
    { name: 'HAIKU', version: '4.5' },
    { name: 'HAIKU_55', version: '5.5' },
    { name: 'SONNET', version: '4.6' },
    { name: 'SONNET_55', version: '5.5' },
    { name: 'OPUS', version: '4.6' },
  ],
};

/** The weekly schedule rows, every model ticked. */
export const SCHEDULE = AVAILABLE.available.map((m) => ({ model: m.name, scheduled: true }));
