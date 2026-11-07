#!/usr/bin/env node
// Folds test-results/a11y/<project>-<scan>.json (written by the a11y specs) into a markdown table: rule, impact, scans
// affected, total nodes. Saved as test-results/a11y/summary.md and, in CI, appended to $GITHUB_STEP_SUMMARY.
// Always exits 0: the job's verdict is the specs' (serious and critical fail them), never this report's.
import { appendFileSync, existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { renderSummary } from './a11y-summary-lib.mjs';

const dir = join(dirname(fileURLToPath(import.meta.url)), '..', 'test-results', 'a11y');
const records = existsSync(dir)
  ? readdirSync(dir)
    .filter((name) => name.endsWith('.json'))
    .sort()
    .map((name) => JSON.parse(readFileSync(join(dir, name), 'utf8')))
  : [];

const markdown = renderSummary(records);
if (existsSync(dir)) writeFileSync(join(dir, 'summary.md'), markdown);
if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${markdown}\n`);
console.log(markdown);
