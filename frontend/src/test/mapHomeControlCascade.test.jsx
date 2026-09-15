/**
 * The map's ⌂ control while it has nothing to say — what the REAL stylesheets make of it.
 *
 * <p>While the home is not known (`undefined`: the settings read unanswered or failed, which is not
 * "no postcode") and no origin is in force, `CentreOnHomeControl` renders nothing into its Leaflet
 * container. The container stays attached — Leaflet puts a re-added bottom-corner control above the
 * zoom bar — and keeps its box without painting it. Its border and ground are its own, so painted
 * empty it is a blank box in the corner; collapsed, it dropped the zoom bar above it 50px and raised
 * it again when the ⌂ came back, putting the ⌂ where "−" had just been.
 * `MapViewCentreOnHome.test.jsx` pins that it is empty; this file pins what empty computes.
 *
 * <p>jsdom lays nothing out but does resolve the cascade, so this injects slices of `index.css` and
 * of Leaflet's own sheet, in bundle order (Leaflet's is imported from `MapView.jsx` and lands
 * after), and asks what the container computes — the technique `mapEmptyStateCascade.test.jsx`
 * uses.
 */
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, beforeAll, afterAll,
} from 'vitest';

const APP_CSS = resolve(process.cwd(), 'src/index.css');
const LEAFLET_CSS = resolve(process.cwd(), 'node_modules/leaflet/dist/leaflet.css');

/**
 * Every top-level rule in {@code path} whose selector contains {@code needle}, comments stripped
 * first — the brace-depth walk `mapEmptyStateCascade.test.jsx` uses, since a regex over `{…}` would
 * mis-split a rule containing a nested block.
 */
function sliceRules(path, needle) {
  expect(existsSync(path), `${path} not found — run vitest from frontend/`).toBe(true);
  const css = readFileSync(path, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const rules = [];
  let selectorStart = 0;
  let depth = 0;
  let blockStart = -1;
  for (let i = 0; i < css.length; i += 1) {
    if (css[i] === '{') {
      depth += 1;
      if (depth === 1) blockStart = i;
    } else if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        const selector = css.slice(selectorStart, blockStart).trim();
        if (selector.includes(needle)) rules.push(`${selector} ${css.slice(blockStart, i + 1)}`);
        selectorStart = i + 1;
      }
    }
  }
  return rules.join('\n');
}

let styleEl;
beforeAll(() => {
  const app = sliceRules(APP_CSS, '.map-home-control');
  // Leaflet's container rules go in too, in bundle order. None sets `display` or `visibility` on the
  // container today; included so one that ever did would be weighed here rather than missed.
  const leaflet = ['.leaflet-control', '.leaflet-bar']
    .map((n) => sliceRules(LEAFLET_CSS, n))
    .join('\n');
  expect(app, 'no :empty rule for the ⌂ container in index.css')
    .toContain('.leaflet-control.leaflet-bar.map-home-control:empty');
  expect(leaflet, 'no Leaflet control rules extracted').toContain('.leaflet-control {');
  styleEl = document.createElement('style');
  styleEl.textContent = `${app}\n${leaflet}`;
  document.head.appendChild(styleEl);
});
afterAll(() => styleEl?.remove());

/**
 * Mounts the container where Leaflet puts it — the bottom-right corner of a touch map — with the
 * classes it carries once added, and returns its computed style.
 */
function styleOf(fill) {
  const map = document.createElement('div');
  map.className = 'leaflet-container leaflet-touch';
  const corner = document.createElement('div');
  corner.className = 'leaflet-bottom leaflet-right';
  const container = document.createElement('div');
  container.className = 'leaflet-bar map-home-control leaflet-control';
  fill(container);
  corner.appendChild(container);
  map.appendChild(corner);
  document.body.appendChild(map);
  const { display, visibility, minWidth, minHeight } = getComputedStyle(container);
  map.remove();
  return { display, visibility, minWidth, minHeight };
}

describe('the ⌂ control paints nothing while it has nothing to say, and keeps its place', () => {
  it('an EMPTY container paints nothing — no blank box in the corner', () => {
    expect(styleOf(() => {}).visibility).toBe('hidden');
  });

  it('an EMPTY container keeps its box, so the zoom bar above it never moves', () => {
    const style = styleOf(() => {});

    expect(style.display).not.toBe('none');
    // The button's 32×30 plus the container's 1px border, under the preflight's border-box.
    expect(style.minWidth).toBe('34px');
    expect(style.minHeight).toBe('32px');
  });

  it('control: with its button in it, the same container is painted', () => {
    // What tells a rule scoped to emptiness from one that hid the control outright.
    const style = styleOf((c) => c.appendChild(document.createElement('button')));

    expect(style.visibility).not.toBe('hidden');
    expect(style.display).not.toBe('none');
  });
});
