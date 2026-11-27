import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Shared helpers for the tests that read `getComputedStyle` against slices of the REAL `index.css`.
 *
 * jsdom 31 resolves custom properties, so `getComputedStyle(el).color` is a colour (and `min-height`
 * a length), no longer the declared `var(--token)` text. A token that is not defined anywhere in the
 * test DOM resolves to nothing (black, or the property's initial value), which cannot tell
 * `--color-plex-text` from `--color-plex-text-secondary`. `injectRealTokens()` therefore copies the
 * stylesheet's own `@theme` declarations onto `:root`, so each token resolves to its REAL value and
 * `tokenColor()` gives the value a rule must resolve to when it picked the right token.
 */

const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/** The index.css text with comments stripped. */
export function readCssNoComments() {
  return readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
}

/** The body of every top-level `@theme ... { }` block in `css`, brace-aware. */
function themeBodies(css) {
  const bodies = [];
  let depth = 0;
  let selectorStart = 0;
  let blockStart = -1;
  for (let i = 0; i < css.length; i += 1) {
    if (css[i] === '{') {
      depth += 1;
      if (depth === 1) blockStart = i;
    } else if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        // The header can trail a brace-less `@import ...;`, so match the `@theme` at its end.
        if (/(^|;)\s*@theme\b[^;]*$/.test(css.slice(selectorStart, blockStart))) {
          bodies.push(css.slice(blockStart + 1, i));
        }
        selectorStart = i + 1;
      }
    }
  }
  return bodies;
}

/** Every `--name: value` declared inside the stylesheet's `@theme` blocks, as a name to value map. */
export function themeTokens() {
  const tokens = {};
  themeBodies(readCssNoComments()).forEach((body) => {
    body.split(';').forEach((decl) => {
      const m = decl.match(/^\s*(--[\w-]+)\s*:\s*([\s\S]+?)\s*$/);
      if (m) tokens[m[1]] = m[2];
    });
  });
  return tokens;
}

/**
 * Puts the real `@theme` tokens on `:root` for the duration of a suite. Returns the `<style>` element;
 * remove it in `afterAll`. Throws if the stylesheet yields no tokens, so a parser that stopped
 * matching cannot leave every colour unresolved and every comparison passing for the wrong reason.
 */
export function injectRealTokens() {
  const tokens = themeTokens();
  const names = Object.keys(tokens);
  if (names.length === 0 || !tokens['--color-plex-text'] || !tokens['--color-plex-text-secondary']) {
    throw new Error('index.css @theme tokens were not found');
  }
  const el = document.createElement('style');
  el.textContent = `:root { ${names.map((n) => `${n}: ${tokens[n]};`).join(' ')} }`;
  document.head.appendChild(el);
  return el;
}

/**
 * What `color: var(<name>)` resolves to once `injectRealTokens()` is in place, in jsdom's own
 * serialisation. Fails loudly when the token resolves to nothing.
 */
export function tokenColor(name) {
  const probe = document.createElement('span');
  probe.style.color = `var(${name})`;
  document.body.appendChild(probe);
  const value = window.getComputedStyle(probe).color;
  probe.remove();
  if (!value || value === 'rgb(0, 0, 0)') {
    throw new Error(`${name} did not resolve to a colour (got "${value}")`);
  }
  return value;
}

/**
 * jsdom 31 serialises a number to six significant digits (`51.96220373529105%` reads back as
 * `51.9622%`, `4.000000000000014px` as `4px`). Use this to build the string a style should read
 * back from a computed figure.
 */
export function cssNumber(n) {
  return String(Number(n.toPrecision(6)));
}
