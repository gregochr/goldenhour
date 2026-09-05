import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import warmPlanChunks from './warmPlanChunks.js';

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');

/**
 * Every `lazy(() => import('./X.jsx'))` in a file, as the bare module name.
 *
 * <p>Comment-stripped first, for the reason `safeAreas.test.jsx` strips CSS comments: the shell's
 * prose quotes its own boundaries at length ("`App` imports this shell STATICALLY (unlike
 * `MapView`, `WindowFirstMapPane`, `MapOverlay` and `ManageView`, which are all `lazy()`)"), so an
 * unstripped scan would find names no `lazy()` call ever mentions and the guard would pass on
 * prose. Block comments only — `//` cannot appear inside one of these one-line calls without
 * ending it.
 */
const lazyImportsIn = (text) => [...text.replace(/\/\*[\s\S]*?\*\//g, '')
  .matchAll(/lazy\(\s*\(\)\s*=>\s*import\(\s*'\.\/([A-Za-z0-9_]+\.jsx)'\s*\)\s*\)/g)]
  .map((m) => m[1]);

/** The same shape on the helper's own side, where the specifiers are `../components/`-relative. */
const warmedIn = (text) => [...text.replace(/\/\*[\s\S]*?\*\//g, '')
  .matchAll(/import\(\s*'\.\.\/components\/([A-Za-z0-9_]+\.jsx)'\s*\)/g)]
  .map((m) => m[1]);

/**
 * ⚠️ The guard that stops {@code warmPlanChunks.js} rotting in the one direction it cannot fail
 * loudly in.
 *
 * <p>A module that is RENAMED fails on its own: the dynamic import rejects, the `beforeAll` fails,
 * and thirteen files go red at once. A **fifth** {@code lazy()} boundary added to the shell is
 * silent — the helper keeps warming four, the new one loads cold inside whichever test reaches it
 * first, and the flake this file's subject exists to fix comes back with no signal at all. That
 * asymmetry is the whole reason this test exists, and it is the same reason
 * {@code testEnvironmentTimezone.test.js} exists beside `setup.js`'s timezone pin.
 *
 * <p>Asserted as SET EQUALITY rather than containment, in both directions on purpose. A boundary
 * the helper does not warm is the rot above; a module the helper warms that the shell no longer
 * loads lazily is dead cost paid by thirteen files per run, which is exactly the defect an
 * adversarial review found in `AppOpenMapTabFromPlan.test.jsx` — a file warming four chunks it had
 * mocked away every route to.
 *
 * <p>It reads the shell as TEXT rather than importing it, because the thing under test is the set
 * of specifiers written in the source. Importing the module would resolve them and tell us nothing
 * about the list.
 */
describe('warmPlanChunks — pinned to the shell it warms', () => {
  const shell = read('../components/WindowFirstShell.jsx');
  const helper = read('./warmPlanChunks.js');

  it('warms exactly the boundaries WindowFirstShell declares, no more and no fewer', () => {
    expect(new Set(warmedIn(helper))).toEqual(new Set(lazyImportsIn(shell)));
  });

  // The population guard. Both extractors are regexes over source text, so a change to the shell's
  // formatting — or a typo in either pattern — could quietly reduce both sides to the empty set,
  // and `Set {} == Set {}` is the greenest test in the file. Naming a real member is what makes the
  // equality above mean something.
  it('finds the boundaries at all, so the equality is not two empty sets agreeing', () => {
    expect(lazyImportsIn(shell)).toContain('WindowFirstHeatStrip.jsx');
    expect(lazyImportsIn(shell).length).toBeGreaterThanOrEqual(4);
  });

  it('resolves every one of them, so a rename fails here rather than in thirteen files', async () => {
    const mods = await warmPlanChunks();
    expect(mods).toHaveLength(lazyImportsIn(shell).length);
    mods.forEach((m) => expect(m.default).toBeTypeOf('function'));
  });
});
