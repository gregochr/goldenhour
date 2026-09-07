/**
 * Step 1 of the label-obstacle measurement (docs/engineering/map-landing-plan.md §4b.1).
 *
 * Builds the harness page, then reads — in headless Chromium, against the BUILT stylesheet, with
 * the real components inside the real ancestor chain — the true rect of every seeded obstacle and
 * the true box of every label kind. Writes `out/boxes.json`.
 *
 *     node scripts/measurements/label-obstacle/measure.mjs
 *
 * Needs `frontend/node_modules` installed (`npm ci`) and Playwright's Chromium in the usual cache.
 */
import { createRequire } from 'node:module';
import {
  readFileSync, writeFileSync, mkdirSync, readdirSync, existsSync,
} from 'node:fs';
import { homedir } from 'node:os';
import { createServer } from 'node:http';
import { extname, relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { FRONTEND, HERE, SHELL_CHROME_BAND, loadRoster, spotsFrom } from './lib.mjs';

// Resolve the frontend's own toolchain — this harness lives outside that package on purpose (it is
// not build surface), so it borrows rather than duplicating a dependency set.
const req = createRequire(resolve(FRONTEND, 'package.json'));
const load = async (spec) => import(pathToFileURL(req.resolve(spec)).href);

const { build } = await load('vite');
const react = (await load('@vitejs/plugin-react')).default;
const pw = await load('playwright-core');
const chromium = pw.chromium ?? pw.default?.chromium;
if (!chromium) throw new Error('playwright-core did not export `chromium`');

const OUT = resolve(HERE, 'out');
const DIST = resolve(HERE, 'dist');
mkdirSync(OUT, { recursive: true });

/**
 * The harness lives OUTSIDE `frontend/`, deliberately — it must not be build surface — so a bare
 * `import 'react'` has no `node_modules` above it to resolve against. This hands every bare
 * specifier to the frontend package's own resolver, so the harness links against exactly the
 * React, PropTypes and Leaflet the app is built with rather than a second copy.
 */
const resolveFromFrontend = {
  name: 'resolve-from-frontend',
  resolveId(id) {
    if (id.startsWith('.') || id.startsWith('/') || id.startsWith('\0')) return null;
    try { return req.resolve(id); } catch { return null; }
  },
};

console.error('building the harness page…');
await build({
  root: resolve(HERE, 'harness'),
  plugins: [resolveFromFrontend, react()],
  // ⚠️ Load the frontend's PostCSS config explicitly. Vite looks for one beside its `root`, which
  // here is the harness directory — so without this, Tailwind never runs and the page renders with
  // a DIFFERENT font stack and no utility classes. It fails silently and plausibly: chips measured
  // 56-107px instead of 71-128px and every panel reported a height of 0. Any run whose numbers
  // look "a bit off" should suspect this line first.
  css: { postcss: FRONTEND },
  logLevel: 'warn',
  build: { outDir: DIST, emptyOutDir: true },
});

const roster = loadRoster();
// The SPOTS, not the bare roster: a chip's box depends on its rating and tide flag, so the box
// measured here must belong to the same spot `analyse.mjs` will hand the placer.
const spots = spotsFrom(roster);
console.error(`roster: ${roster.length} locations, ${new Set(roster.map((r) => r.region)).size} regions`);

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };

/**
 * The built files, enumerated once and served from a Map keyed by exact URL path.
 *
 * ⚠️ Deliberately builds NO path from the request. An earlier cut did `join(DIST, req.url)` and
 * then, when CodeQL flagged it, added a prefix check — which is a correct guard but still a path
 * constructed from user data, and still flagged. Serving from a fixed allow-list removes the taint
 * rather than filtering it, which is both safer and honest about what this server is: it needs to
 * serve exactly one page and its two assets.
 */
const ROUTES = new Map();
for (const rel of readdirSync(DIST, { recursive: true, withFileTypes: true })) {
  if (!rel.isFile()) continue;
  const abs = resolve(rel.parentPath ?? rel.path, rel.name);
  const url = `/${relative(DIST, abs).split(sep).join('/')}`;
  ROUTES.set(url, abs);
}
ROUTES.set('/', ROUTES.get('/index.html'));

const server = createServer((req_, res) => {
  const file = ROUTES.get(req_.url.split('?')[0]);
  if (!file) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
  res.end(readFileSync(file));
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const port = server.address().port;

/**
 * `playwright-core` ships no browsers of its own, so a bare `launch()` only works when one happens
 * to be on the default path. Find the cached Chromium instead — discovered rather than hardcoded,
 * because the build number changes with every Playwright bump.
 */
function cachedChromium() {
  const root = process.env.PLAYWRIGHT_BROWSERS_PATH
    || (process.platform === 'darwin'
      ? resolve(homedir(), 'Library/Caches/ms-playwright')
      : resolve(homedir(), '.cache/ms-playwright'));
  let names;
  try { names = readdirSync(root); } catch { return undefined; }
  const builds = names.filter((n) => /^chromium-\d+$/.test(n))
    .sort((a, b) => Number(b.split('-')[1]) - Number(a.split('-')[1]));
  for (const b of builds) {
    for (const rel of [
      'chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
      'chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing',
      'chrome-linux/chrome',
    ]) {
      const p = resolve(root, b, rel);
      if (existsSync(p)) return p;
    }
  }
  return undefined;
}

const executablePath = cachedChromium();
const browser = await chromium.launch(executablePath ? { executablePath } : {});
const { VIEWPORTS } = await import('./lib.mjs');
/** `none` is the baseline (chrome only); the other three each open one of the seeded surfaces. */
/** `win9` is the SAME panel at a production-shaped region count — see the harness's own note. */
const SURFACES = ['none', 'land', 'win', 'win9', 'reg'];
const REGION_COUNT_FOR = { win9: 9 };
const OBSTACLE_TESTIDS = [
  // The transient surfaces under test…
  'wf-map-chrome-tl', 'wf-land', 'wf-win-panel', 'wf-reg-panel',
  // …and the chrome that is on screen in every ordinary state, measured rather than invented.
  'wf-map-chrome-tr', 'wf-map-chrome-bl', 'wf-map-counts-footer', 'photocast-scored-legend',
  // Leaflet's own corner — seeded by the host from a SECOND root, so easy to forget.
  'r5-leaflet-corner',
];

const out = { viewports: {} };
/**
 * ⚠️ Every frame height is MEASURED, never derived by subtracting from a taller one.
 *
 * The sweep compares a full-viewport frame against one shortened by the app's masthead and tab
 * strip. An earlier cut modelled the short case by shrinking the placer's height while reusing the
 * TALL frame's obstacle rects — which left every bottom-anchored obstacle (`wf-map-chrome-bl`, the
 * counts footer, the scored legend, the phone bottom bar) stranded near the old bottom edge,
 * partly or wholly off-frame. Those cells then had LESS collision pressure than reality, in
 * exactly the cells several worst cases came from. Measuring both heights costs one more pass and
 * removes the modelling entirely.
 */
const FRAMES = VIEWPORTS.flatMap((vp) => SHELL_CHROME_BAND.map((shell) => ({
  ...vp, shell, key: `${vp.name}@${shell}`, height: Math.max(320, vp.height - shell),
})));

for (const vp of FRAMES) {
  out.viewports[vp.key] = {
    name: vp.name, shell: vp.shell, width: vp.width, height: vp.height, surfaces: {},
  };
  for (const surface of SURFACES) {
    const page = await browser.newPage({ viewport: { width: vp.width, height: vp.height } });
    const errs = [];
    page.on('pageerror', (e) => errs.push(String(e)));
    await page.addInitScript(([r, sp, s, rc]) => {
      window.__R5_ROSTER__ = r; window.__R5_SPOTS__ = sp;
      window.__R5_SURFACE__ = s === 'win9' ? 'win' : s; window.__R5_REGIONS__ = rc;
    }, [roster, spots, surface, REGION_COUNT_FOR[surface] ?? null]);
    await page.goto(`http://127.0.0.1:${port}/`, { waitUntil: 'networkidle' });
    await page.waitForSelector('#r5-frame', { state: 'attached', timeout: 15000 });
    await page.evaluate(() => document.fonts.ready.then(() => true));

    const data = await page.evaluate((testids) => {
      const frame = document.getElementById('r5-frame');
      const fr = frame.getBoundingClientRect();
      const read = (attr) => [...document.querySelectorAll(`[${attr}]`)].map((el) => ({
        name: el.getAttribute(attr), w: el.offsetWidth, h: el.offsetHeight,
      }));
      const obstacles = {};
      for (const t of testids) {
        // ⚠️ `r5-leaflet-corner` is not a testid — it is Leaflet's own corner, selected by the
        // SAME selector the host uses (`LEAFLET_CORNER_SELECTOR`), because the harness now mounts
        // a real map rather than reproducing one.
        const el = t === 'r5-leaflet-corner'
          ? document.querySelector('.leaflet-bottom.leaflet-right')
          : document.querySelector(`[data-testid="${t}"]`);
        if (!el) continue;
        const r = el.getBoundingClientRect();
        obstacles[t] = {
          left: r.left - fr.left, top: r.top - fr.top, width: r.width, height: r.height,
        };
      }
      return {
        frame: { width: fr.width, height: fr.height },
        chips: [...document.querySelectorAll('[data-r5-chip]')].map((el) => ({
          name: el.getAttribute('data-r5-chip'), w: el.offsetWidth, h: el.offsetHeight,
        })),
        regions: read('data-r5-region'),
        regionsTiny: read('data-r5-region-tiny'),
        home: read('data-r5-home')[0] || null,
        rings: read('data-r5-ring'),
        obstacles,
      };
    }, OBSTACLE_TESTIDS);
    if (errs.length) console.error(`⚠️ [${vp.name}/${surface}] page errors:`, errs.slice(0, 3));
    out.viewports[vp.key].surfaces[surface] = data;
    await page.close();
  }
  const b = out.viewports[vp.key].surfaces.none;
  const ws = b.chips.map((c) => c.w);
  console.error(
    `${vp.key}: chips ${b.chips.length} w=${Math.min(...ws)}..${Math.max(...ws)} `
    + `h=${[...new Set(b.chips.map((c) => c.h))].join(',')}  `
    + Object.entries(out.viewports[vp.key].surfaces)
      .flatMap(([s, d]) => Object.entries(d.obstacles)
        .filter(([k]) => k !== 'wf-map-chrome-tl' || s === 'none')
        .map(([k, r]) => `${k} ${Math.round(r.width)}x${Math.round(r.height)}@(${Math.round(r.left)},${Math.round(r.top)})`))
      .join(' '),
  );
}

await browser.close();
server.close();
writeFileSync(resolve(OUT, 'boxes.json'), JSON.stringify(out, null, 1));
console.error(`\nwrote ${resolve(OUT, 'boxes.json')}`);
