// The per-shard floor: the weekly workflow sets STRYKER_BREAK from each shard's matrix entry.
// Unset (every local run), there is no floor. Set but not a whole number 0-100, it throws, so a typo
// cannot quietly switch the floor off.
function breakFloor() {
  const raw = process.env.STRYKER_BREAK;
  if (raw === undefined || raw === '') return null;
  const value = Number(raw);
  if (!/^\d+$/.test(raw.trim()) || !Number.isInteger(value) || value < 0 || value > 100) {
    throw new Error(`STRYKER_BREAK must be a whole number from 0 to 100, got "${raw}"`);
  }
  return value;
}

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
  // Per-shard floors (plan Phase 2). thresholds.break applies to each Stryker process's own score, and
  // each CI shard is its own process with its own score, so one number would fail the weaker shards:
  // stryker.yml carries a floor per shard (the first full run's score rounded down) and passes it in
  // as STRYKER_BREAK. Ratchet rule: raise a floor, never lower one without the owner's say-so.
  thresholds: { high: 80, low: 60, break: breakFloor() },
  tempDirName: '.stryker-tmp',
  // In place, not the default sandbox copy: windowFirstTopics.test.js reads the backend's
  // PlanWindowProjector.java through a path that climbs out of frontend/, which the one-level-deeper
  // .stryker-tmp/ copy breaks (the dry run fails). Stryker backs the originals up and restores them
  // when it exits; a run killed mid-way can leave src/utils mutated, so run it on a clean checkout.
  inPlace: true,
};
