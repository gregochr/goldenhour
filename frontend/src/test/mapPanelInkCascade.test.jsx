import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * Increment §6 — panel text contrast inside the map frame, as ONE rule rather than a list of
 * selectors, asserted against the REAL `index.css`.
 *
 * <h2>What breaks if these fail</h2>
 *
 * <p>The map's panels go back to the recessive ink. Measured on this arm's own grounds: bone at
 * 0.42 (`--color-plex-text-muted`, the design's `--ink-3`) composites to 3.53:1 on the window
 * menu's plate and 3.56:1 on the callout's — under AA's 4.5:1 for the 9.5–11px type those panels
 * are set in. Two live map-chrome surfaces were sitting at that value and both are meaningful
 * words, not decoration: the window dropdown's `—` unscored marker and the callout strip cell's.
 *
 * <h2>What this file can and cannot prove</h2>
 *
 * <p>Source-level, deliberately. jsdom does not resolve `var()` (this project's own recorded
 * finding — a `getComputedStyle` read of a token-valued property answers with the literal
 * `var(...)` text), so a computed-style assertion here would prove nothing about the resolved
 * colour. What IS checkable, and is what the increment's rule actually claims, is that the override
 * exists on the map frame's own wrapper, that its value is the SAME literal as the passing token,
 * and that the two failing selectors still read the token rather than having been hard-coded past
 * it. The contrast arithmetic itself is recorded in `index.css` beside the rule.
 *
 * <p>⚠️ It also pins the SCOPE. `.wf-body--map` is the Map TAB's own wrapper; the Plan-tab overlay
 * mounts the same `MapView` and is a deliberately frozen pre-v2 surface (CLAUDE.md), so a rule that
 * reached it would be an unasked-for change to a surface this project has decided not to touch.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/** `index.css` with comments stripped, so a value quoted in prose can never satisfy a test. */
function css() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`)
    .toBe(true);
  return readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
}

/** The body of the FIRST rule whose selector is exactly `selector`. */
function ruleBody(selector) {
  const source = css();
  const re = new RegExp(`(^|})\\s*${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`, 'm');
  const m = re.exec(source);
  expect(m, `no rule found for "${selector}"`).not.toBeNull();
  return m[2];
}

/** The declared value of `prop` inside `body`, trimmed. */
function declared(body, prop) {
  const m = new RegExp(`(?:^|;)\\s*${prop}\\s*:\\s*([^;]+)`, 'm').exec(body);
  return m ? m[1].trim() : null;
}

describe('map panel ink — increment §6', () => {
  it('overrides the recessive token ONCE, on the map frame’s own wrapper', () => {
    const value = declared(ruleBody('.wf-body.wf-body--map'), '--color-plex-text-muted');
    expect(value).not.toBeNull();
  });

  it('sets it to exactly the passing token’s value — the increment’s "= --ink-2"', () => {
    // Compared as literals rather than by eye: the increment's whole point is that the map's
    // recessive ink BECOMES the secondary ink, not merely "something lighter".
    // Tailwind v4: the palette is declared in `@theme`, not `:root`.
    const theme = ruleBody('@theme');
    const panelInk = declared(theme, '--color-panel-ink');
    const secondary = declared(theme, '--color-plex-text-secondary');
    expect(panelInk).not.toBeNull();
    expect(panelInk).toBe(secondary);
    expect(declared(ruleBody('.wf-body.wf-body--map'), '--color-plex-text-muted'))
      .toBe('var(--color-panel-ink)');
  });

  it('pins the VALUE, not merely the equality — mutation testing found the hole', () => {
    // ⚠️ Asserting `mapValue === secondary` alone proves nothing about legibility: dim
    // `--color-plex-text-secondary` to the failing 0.42 and both stay equal, both stay green, and
    // both surfaces §6 exists for go back under AA. The measured figures lived only in comments.
    // 0.66 is the alpha that measures 7.24:1 on the window menu's plate and 7.15:1 on the callout's.
    const theme = ruleBody('@theme');
    expect(declared(theme, '--color-panel-ink')).toBe('rgba(242, 231, 211, 0.66)');
    expect(declared(theme, '--color-plex-text-muted')).toBe('rgba(242, 231, 211, 0.42)');
  });

  it('reaches the phone’s bottom sheets, which portal OUT of the map subtree', () => {
    // ⚠️ A review lens caught this: on <=639px `FiltersPopover` and `RegionsJump` — the two panels
    // §6 names — render through `BottomSheet`, which is a `createPortal(…, document.body)`. A
    // portalled node inherits nothing from `.wf-body--map`, so five selectors kept the failing 0.42
    // on the one screen where the type is smallest. "One rule" has to mean the rendered panel.
    expect(declared(ruleBody("[data-testid='bottom-sheet-root']"), '--color-plex-text-muted'))
      .toBe('var(--color-panel-ink)');
  });

  it('is scoped to the map TAB, never to MapView — the frozen overlay must not move', () => {
    // The compound `.wf-body.wf-body--map` is the selected Map tab's own panel wrapper. The Plan
    // tab's overlay mounts `MapView` inside the PLAN body, which this selector cannot reach.
    const source = css();
    expect(source).toMatch(/\.wf-body\.wf-body--map\s*\{[^}]*--color-plex-text-muted/);
    // No second, broader home for the same override.
    const overrides = source.match(/--color-plex-text-muted\s*:/g) || [];
    // Three: the `@theme` definition, the map tab's override, and the bottom-sheet host's.
    expect(overrides).toHaveLength(3);
  });

  it('gives the "unscored" markers their OWN recessive colour, not the flattened token', () => {
    // ⚠️ A review lens caught the consequence of setting muted == secondary: these three all sit
    // INSIDE a wrapper that is already secondary, and took their meaning from being quieter than
    // it. Reading the token made the "nothing is scored here" dash the same ink as a real score.
    // The increment's own §6 answers this — "recessiveness is opt-in" — so they name their own.
    for (const sel of ['.wf-win-row-unscored', '.wf-callout-strip-score.unscored',
      '.wf-jump-unscored']) {
      const colour = declared(ruleBody(sel), 'color');
      expect(colour).toBe('rgba(242, 231, 211, 0.52)');
      // Explicitly NOT the token: that is what re-flattens them.
      expect(colour).not.toBe('var(--color-plex-text-muted)');
    }
  });
});
