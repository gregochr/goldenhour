// Mutation testing for the client logic in src/utils (weekly, never a PR gate).
// Plan: docs/engineering/frontend-mutation-testing-plan.md. Notes: reference/dev-and-testing.md.
/** @type {import('@stryker-mutator/api/core').PartialStrykerOptions} */
export default {
  testRunner: 'vitest',
  // 'forks', not the runner's default 'threads': the suite pins process.env.TZ, which a worker
  // thread (with a copy of process.env) may not honour. Do not set coverageAnalysis: the vitest
  // runner ignores it and always uses per-test coverage.
  vitest: { configFile: 'vite.config.js', pool: 'forks' },
  mutate: ['src/utils/*.js'], // the weekly workflow narrows this per shard with --mutate
  // Deliberate trade-offs, revisit once the first reports exist:
  //  - ignoreStatic: module-level tables are static mutants that re-run every covering test.
  //  - StringLiteral excluded: utils strings are mostly user-facing labels; low value, high cost.
  ignoreStatic: true,
  mutator: { excludedMutations: ['StringLiteral'] },
  // The runner forces maxWorkers 1, so the dry run is serial and slow.
  dryRunTimeoutMinutes: 180,
  timeoutMS: 30000,
  timeoutFactor: 2,
  concurrency: 4,
  incremental: true,
  incrementalFile: 'reports/stryker-incremental.json',
  reporters: ['clear-text', 'progress', 'html', 'json'],
  htmlReporter: { fileName: 'reports/mutation/mutation.html' },
  jsonReporter: { fileName: 'reports/mutation/mutation.json' },
  // break stays null until a first real measurement sets the floor (plan Phase 2).
  thresholds: { high: 80, low: 60, break: null },
  tempDirName: '.stryker-tmp',
  // In place, not the default sandbox copy: windowFirstTopics.test.js reads the backend's
  // PlanWindowProjector.java through a path that climbs out of frontend/, which the one-level-deeper
  // .stryker-tmp/ copy breaks (the dry run fails). Stryker backs the originals up and restores them
  // when it exits; a run killed mid-way can leave src/utils mutated, so run it on a clean checkout.
  inPlace: true,
};
