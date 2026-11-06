# Frontend mutation testing (Stryker): plan

**Goal.** Close the last half of the landing-page gap that reads "The app's tests have the same strict
coverage minimum … but not mutation testing yet." Coverage shows what the tests *run*. Mutation testing
shows whether they *notice* a fault.

The workflow mirrors the backend's weekly `pitest.yml`:
- it runs on a schedule and never blocks a PR;
- it raises a failure report (through `ci-failure-issue.yml`) when the score falls below its floor.

**Scope.** `frontend/src/utils/` comes first. It is flat: 152 source files and about 30.5k lines, and it
holds most of the client logic. Tests live in `frontend/src/test/**`, and any related test may kill a mutant.

**Reviewed** by an adversarial pass before the build (2026-11-06). The corrections it found are folded in
below and marked ⚠️.

## Phase 1: the workflow (one PR)

### 1. Dependencies
- Install `@stryker-mutator/core` and `@stryker-mutator/vitest-runner` at the same exact version (10.1.1).
  The runner pins core to its own version.
- The runner's peer range is `vitest >=2`, but Vitest 5 is not officially tested. **Verify it runs on
  Vitest 5.0.5 before anything else.** If it does not, stop and report.
- Add a Dependabot `stryker` group for `@stryker-mutator/*`, so both packages bump together.
- `npm audit --audit-level=high` must stay clean.

### 2. `frontend/stryker.config.mjs`

**Runner**
- `testRunner: 'vitest'`, with `vitest: { configFile: 'vite.config.js', pool: 'forks' }`.
  - ⚠️ The runner defaults to `threads`. The suite pins `process.env.TZ` in `src/test/setup.js` and in 16
    test files, and in a worker thread `process.env` is a copy, so the zone may not change. A failing dry
    run aborts the whole run. Confirm `testEnvironmentTimezone.test.js` passes in the dry run.
- ⚠️ Do **not** set `coverageAnalysis`. The vitest runner ignores it and always uses per-test coverage.
- `related: true` is the default; keep it.
- ⚠️ The runner forces `coverage.enabled: false`, so the Vitest per-file thresholds cannot fail Stryker.

**What gets mutated**
- `mutate`: `src/utils/*.js`.
- ⚠️ Set `ignoreStatic: true`. The module-level tables in `utils` are static mutants, which re-run every
  covering test and would dominate the run time.
- ⚠️ Set `mutator.excludedMutations: ['StringLiteral']`. Strings in `utils` are mostly user-facing labels,
  and their mutants are low-value and expensive.
- Write both of these into the docs as deliberate trade-offs that can be revisited.

**Timeouts**
- ⚠️ Set `dryRunTimeoutMinutes` from the measurement, with headroom (for example 120–240). The default of
  5 will time out, because the runner forces `maxWorkers: 1`, so the dry run is serial.
- Set `timeoutMS` / `timeoutFactor` generous enough for the cold `React.lazy` first tests (the suite's
  `testTimeout` is 20000).
- Size `concurrency` for a 4-vCPU `ubuntu-latest` runner.

**Incremental and reporting**
- `incremental: true`, with `incrementalFile: 'reports/stryker-incremental.json'` (per shard if sharded).
- `reporters: ['clear-text', 'progress', 'html', 'json']`.
- `thresholds: { high: 80, low: 60, break: null }`. `break` stays null in Phase 1, because the floor comes
  from the first real measurement (Phase 2) and is never guessed.

**Ignore files and script**
- ⚠️ Add `'**/.stryker-tmp/**'` to `test.exclude` in `vite.config.js`. A failed run leaves
  `.stryker-tmp/` behind, and `npm test` would otherwise pick up duplicate tests.
- Add `.stryker-tmp/` and `reports/` to `.gitignore` and to `frontend/.dockerignore`.
- Script: `"test:mutation": "stryker run"`.

### 3. Measure before choosing the shape
Run these locally:
- the dry run (`stryker run --dryRunOnly`), recording its serial time;
- a full run of 3–5 representative `utils` files.

Record the mutant count for all of `src/utils` (after `ignoreStatic` and the StringLiteral exclusion) and
the mutants per minute. Extrapolate a full first run on 4 vCPUs, **including one dry run per shard**.

Then pick the shape:
- **Under about 4 h:** a single job.
- **Otherwise:** a matrix of N shards.
  - ⚠️ `src/utils` is flat, so shard by first-letter character classes (for example
    `src/utils/[a-f]*.js`), balanced by line count.
  - A workflow step asserts that the shards' globs together match `ls src/utils/*.js` exactly once.
  - Each shard has its own incremental file and its own artifact name.
- ⚠️ Incremental mode saves state only when a run *finishes*. The first full run of each shard must fit
  inside the job cap on its own.

### 4. `.github/workflows/stryker.yml` (mirrors `pitest.yml`)

**Triggers and runner**
- A weekly `schedule`, at a different time from PIT's Monday 06:00 UTC.
- `workflow_dispatch` with a boolean `force` input that runs `stryker run --force`.
  - ⚠️ Incremental mode cannot see changes to `setup.js`, `vite.config.js` or dependency bumps.
  - The scheduled run also forces itself on the first run of each month, whenever the day of the month is
    7 or less.
- `runs-on: ubuntu-latest`. Not the self-hosted runner: that one is shared with production deploys.
- Node 22, then `npm ci`.

**Timeouts**
- The job's `timeout-minutes` comes from the measurement and stays under 360.
- ⚠️ The Stryker *step* gets its own `timeout-minutes`, shorter than the job's, so the upload and summary
  steps always run.

**Keeping incremental state between runs**
- ⚠️ Use artifacts, not `actions/cache`. GitHub evicts caches unused for 7 days, which is exactly the
  weekly boundary, and a re-run reuses the same `run_id` key.
- At the start of the run, fetch the previous incremental file from the most recent **completed** run of
  this workflow on `main`, whatever its conclusion. Use `gh run list` / `gh run download` with
  `permissions: actions: read`.
- If no previous run exists, run from scratch.
- At the end, upload the incremental file as an artifact with `retention-days: 30` and `if: always()`.

**Reporting**
- Write a summary line to `$GITHUB_STEP_SUMMARY`: the score, plus killed, survived, timeout and
  no-coverage counts, per shard.
- Upload `frontend/reports/mutation/` as an artifact with `if: always()`.
- Add a `notify-failure` job that calls `ci-failure-issue.yml` with
  `workflow-name: Stryker Mutation Testing` and `permissions: issues: write`, exactly as `pitest.yml` does.

**Concurrency**
- Use a `concurrency` group with `cancel-in-progress: false`.

### 5. Docs
- Add a "Frontend mutation testing" paragraph to `docs/engineering/reference/dev-and-testing.md` covering:
  - what it is and where it lives;
  - how to run it locally;
  - that it runs weekly and does not block PRs;
  - how incremental state is persisted, and the forced run;
  - `ignoreStatic` and the StringLiteral exclusion;
  - possible false survivors in code that runs first inside a `React.lazy` import.
- Do not change CLAUDE.md.
- Add a changelog entry.

### 6. Landing copy
None in Phase 1. The claim is not true until a run has completed and a floor exists.

## Phase 1 as built (2026-11-06)

**Measurements** (a loaded laptop, four runners; the rate comes from the alphabetically first files only):
- dry run: 17m52s for 15,426 tests, serial;
- 18,902 mutants to test (22,884 before `ignoreStatic` and the StringLiteral exclusion);
- about 18 mutants a minute (range 8–28), so about 17.5 h extrapolated for a single job.

**Shape:** ten shards plus a `plan` job that asserts the globs cover `src/utils/*.js` exactly once; about 1.9k
mutants each; the Stryker step has `timeout-minutes: 320` against the job's 350.

**Deviations from the plan:**
- `inPlace: true`. The default sandbox copy sits one level deeper, which breaks `windowFirstTopics.test.js`
  (it reads a backend source file by a path that climbs out of `frontend/`) and fails the dry run.
- `stryker-setup-*.js` is also git- and docker-ignored (an interrupted run leaves them behind).
- Schedule Wednesday 03:00 UTC; `dryRunTimeoutMinutes` 180, `timeoutMS` 30000, concurrency 4.
- The incremental fetch walks the last 10 completed runs rather than taking only the latest.

**Unverified until the first CI run:** whether each shard fits in its job cap on `ubuntu-latest`; incremental
reuse between runs; the force logic (`--force`, the day-of-month rule).

## Between phases

After Phase 1 merges, dispatch `stryker.yml` on `main` and wait for it. Read the score per shard (or
overall) and the list of survivors.

## Phase 2: the floor and the landing copy (one PR)

1. **The floor.** Set `thresholds.break` to the measured score rounded **down** to a whole percent, per
   shard if sharded or overall otherwise. The ratchet rule is the same as for coverage: raise it, and
   never lower it without the owner's say-so.
2. **Landing copy.** Update `landing/how-its-built.html` and its copy in
   `docs/engineering/how-its-built-plan.md`:
   - **"Tests that are tested":** extend the mutation sentence to cover the app's client logic as well as
     the server's core forecasting code. It runs weekly, does not block changes, and opens a failure
     report if the score falls below its threshold.
   - **"Where it's still improving":** say the app now has mutation testing for its client logic. The
     honest remaining gap is that its screens (components) are not mutation-tested yet.
   - Keep the page's voice: measured, British English, no overclaiming. Every claim must match the merged
     config.
3. **Docs.** Add the floor numbers to `dev-and-testing.md`, and add a changelog entry.

## Not in scope
- Mutating components or hooks. That is a later widening, once `utils` is stable and its run time is known.
- Making mutation testing a PR gate.
- Killing survivors. The first report will list them; writing tests for them is follow-up work.
