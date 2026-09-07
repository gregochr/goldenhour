/**
 * Step 2 of the label-obstacle measurement (docs/engineering/map-landing-plan.md §4b.1).
 *
 * Runs the REAL placer (`mapLabels.placeLabelPass` over `regionLabelItems` + `chipCandidates`)
 * across obstacle configurations, using the boxes `measure.mjs` read out of Chromium, and reports:
 *
 *   · the tab's OWN opening framing (each viewport's real `fitBounds` zoom), where the question
 *     "does this change what a reader lands on" is actually decided;
 *   · a sweep of 5 centres x 7 zooms per viewport around it;
 *   · every drop split into COVERED (the label's old box lies under geometry the new configuration
 *     added — the obstacle doing its job) and COLLATERAL (it had clear air and lost it to the
 *     greedy pass reshuffling). Only collateral is a defect.
 *
 *     node scripts/measurements/label-obstacle/analyse.mjs        # after measure.mjs
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  ALWAYS_ON, FRONTEND, HERE, SHELL_CHROME_BAND, loadRoster, spotsFrom, VIEWPORTS, ZOOMS,
  centresFor, projector,
} from './lib.mjs';

const {
  chipCandidates, regionLabelItems, placeLabelPass, REGION_TINY_FRAME_WIDTH,
} = await import(resolve(FRONTEND, 'src/utils/mapLabels.js'));
const { seedObstacles } = await import(resolve(FRONTEND, 'src/utils/labelPlacement.js'));

const boxes = JSON.parse(readFileSync(resolve(HERE, 'out/boxes.json'), 'utf8'));
const SPOTS = spotsFrom(loadRoster());
const CENTRES = centresFor(SPOTS);

/**
 * ⚠️ The 334px arm is SYNTHETIC — it overrides the measured width to recreate a size the CSS no
 * longer produces. That is only meaningful where 334 and 504 are both widths this rule can emit.
 * Below 640px `index.css` releases the bound entirely (`left: 8px; right: 8px; max-width: none`),
 * so the box is a full-width bar and NEITHER figure renders: the phone is excluded from that
 * comparison rather than reported as a passing viewport.
 */
const PHONE_MEDIA_MAX = 639;
const widthComparable = (vp) => boxes.viewports[vp].width > PHONE_MEDIA_MAX;

function configsFor(vpName) {
  const s = boxes.viewports[vpName].surfaces;
  const tl = s.none.obstacles['wf-map-chrome-tl'];
  // Every always-on rect, as MEASURED — never modelled. Held constant in both arms.
  const chrome = ALWAYS_ON.map((t) => s.none.obstacles[t]).filter(Boolean);
  const at = (width) => ({ ...tl, width });
  return {
    'tl-334': [at(334), ...chrome],
    'tl-504': [tl, ...chrome],
    'tl+land': [tl, s.land.obstacles['wf-land'], ...chrome],
    'tl+win': [tl, s.win.obstacles['wf-win-panel'], ...chrome],
    'tl+win9': [tl, s.win9.obstacles['wf-win-panel'], ...chrome],
    'tl+reg': [tl, s.reg.obstacles['wf-reg-panel'], ...chrome],
  };
}

/**
 * Centres chosen by DENSITY rather than by fixed pans.
 *
 * ⚠️ A greedy-reshuffle collateral loss needs contention, and the first cut's five fixed pans
 * produced almost none — the frame was 96.8–99.95% empty in every swept state, so its null was
 * close to a null by construction. A review lens re-ran the identical pipeline with density-picked
 * centres and found three collateral drops the fixed pans missed. These are ordinary states: a
 * reader zoomed into a cluster.
 */
function denseCentres(vpName, zoom, n = 5) {
  const v = boxes.viewports[vpName].surfaces.none.frame;
  const lat = SPOTS.map((s) => s.lat);
  const lon = SPOTS.map((s) => s.lng);
  const bb = {
    n: Math.max(...lat), s: Math.min(...lat), e: Math.max(...lon), w: Math.min(...lon),
  };
  const cand = [];
  const STEP = 12;
  for (let i = 0; i <= STEP; i += 1) {
    for (let j = 0; j <= STEP; j += 1) {
      const c = {
        name: `d${i}-${j}`,
        lat: bb.s + ((bb.n - bb.s) * i) / STEP,
        lon: bb.w + ((bb.e - bb.w) * j) / STEP,
      };
      const to = projector(c, zoom, v.width, v.height);
      let inView = 0;
      for (const sp of SPOTS) {
        const [x, y] = to(sp.lat, sp.lng);
        if (x >= 0 && x <= v.width && y >= 0 && y <= v.height) inView += 1;
      }
      cand.push({ c, inView });
    }
  }
  cand.sort((a, b) => b.inView - a.inView);
  // Spread them out, so five near-identical views do not stand in for five states.
  const picked = [];
  for (const { c } of cand) {
    if (picked.every((p) => Math.abs(p.lat - c.lat) > 0.15 || Math.abs(p.lon - c.lon) > 0.25)) {
      picked.push(c);
    }
    if (picked.length === n) break;
  }
  return picked;
}

/** The host's own priority order, minus home and rings — see §4b.1's stated limitations. */
function itemsFor(vpName, centre, zoom, shellChrome = 0, selectedName = null) {
  const v = boxes.viewports[vpName].surfaces.none;
  const w = v.frame.width;
  // The app's map frame sits UNDER the masthead and tab strip; the harness's fills the viewport.
  // `shellChrome` is the band's current end — see `SHELL_CHROME_BAND`.
  const h = Math.max(200, v.frame.height - shellChrome);
  const to = projector(centre, zoom, w, h);
  // Leaflet rounds both the projected point and the pixel origin, so a real anchor is integral.
  const project = (s) => { const [x, y] = to(s.lat, s.lng); return [Math.round(x), Math.round(y)]; };
  const inView = new Set(SPOTS.filter((s) => {
    const [x, y] = project(s);
    return x >= 0 && x <= w && y >= 0 && y <= h;
  }).map((s) => s.name));
  const chipBox = new Map(v.chips.map((c) => [c.name, c]));
  const regBox = new Map((w < REGION_TINY_FRAME_WIDTH ? v.regionsTiny : v.regions)
    .map((r) => [r.name, r]));
  const items = [];
  for (const it of regionLabelItems(SPOTS, project, zoom)) {
    const b = regBox.get(it.rid);
    if (b && b.w > 0) items.push({ ...it, w: b.w, h: b.h });
  }
  // ⚠️ `selectedName` is not decoration: it keeps the selected chip AND moves it to the FRONT of
  // the priority order, which reorders the whole greedy arbitration this measurement is about. The
  // first cut passed null everywhere and never exercised it.
  for (const s of chipCandidates({ spots: SPOTS, inViewNames: inView, zoom, selectedName })) {
    const [x, y] = project(s);
    const b = chipBox.get(s.name);
    if (b && b.w > 0) items.push({ key: `chip:${s.name}`, x, y, w: b.w, h: b.h });
  }
  return { items, w, h, inView: inView.size };
}

const seed = (rects) => seedObstacles(rects, { left: 0, top: 0 }, 5);
const w0 = (vp) => boxes.viewports[vp.name].surfaces.none.frame.width;
const h0 = (vp) => boxes.viewports[vp.name].surfaces.none.frame.height;
const run = (items, w, h, rects) => placeLabelPass(items, w, h, seed(rects));
const hits = (b, o) => b.x < o.x + o.w && b.x + b.w > o.x && b.y < o.y + o.h && b.y + b.h > o.y;

/** Classify one placed-set pair. */
function classify(a, b, fromRects, toRects) {
  const fromObs = seed(fromRects);
  const toObs = seed(toRects);
  /**
   * ⚠️ "Covered" is tested against the obstacle's REAL rect, not the seeded one. `seedObstacles`
   * inflates by 5px on every side, so classifying against the padded box would score a label
   * sitting in clear air beside a panel as "covered" — which is the opposite of what the word is
   * doing work for here. A review lens found exactly that case at the phone's own opening framing
   * (a chip 4px below `wf-reg-panel`, scored covered only because the halo reached it).
   *
   * ⚠️ And coverage is a FRACTION, not a boolean. A label the panel covers by 7% was visible and
   * is now gone. `coveredFrac` is reported so a reader can judge rather than take "covered" as a
   * synonym for "invisible anyway".
   */
  const realAdded = toRects.filter((r) => !fromRects.includes(r))
    .map((r) => ({ x: r.left, y: r.top, w: r.width, h: r.height }));
  const grew = fromRects.filter((r) => toRects.every((t) => t !== r));
  for (const g of grew) {
    const t = toRects.find((r) => r.left === g.left && r.top === g.top && r.width !== g.width);
    if (t) realAdded.push({ x: g.left + g.width, y: g.top, w: t.width - g.width, h: g.height });
  }
  const coveredFrac = (b) => {
    let best = 0;
    for (const o of realAdded) {
      const ox = Math.max(0, Math.min(b.x + b.w, o.x + o.w) - Math.max(b.x, o.x));
      const oy = Math.max(0, Math.min(b.y + b.h, o.y + o.h) - Math.max(b.y, o.y));
      best = Math.max(best, (ox * oy) / (b.w * b.h));
    }
    return best;
  };
  const addedCovers = (box) => coveredFrac(box) > 0 && !fromObs.some((o) => hits(box, o));
  const out = {
    placedFrom: a.size, placedTo: b.size, covered: [], collateral: [], moved: [],
  };
  for (const [k, boxA] of a) {
    const boxB = b.get(k);
    if (boxB) {
      if (boxA.x !== boxB.x || boxA.y !== boxB.y) out.moved.push(k);
      continue;
    }
    const frac = coveredFrac(boxA);
    (addedCovers(boxA) ? out.covered : out.collateral)
      .push({ key: k, at: [boxA.x, boxA.y], frac });
  }
  return out;
}

/** Leaflet's `getBoundsZoom` for the roster's bounds in a `w`x`h` frame with `pad` px of padding. */
function fitZoom(w, h, pad) {
  const lat = SPOTS.map((s) => s.lat);
  const lon = SPOTS.map((s) => s.lng);
  const bb = {
    n: Math.max(...lat), s: Math.min(...lat), e: Math.max(...lon), w: Math.min(...lon),
  };
  let best = 0;
  for (let z = 0; z <= 20; z += 0.01) {
    const p = projector({ lat: 0, lon: 0 }, z, 0, 0);
    const [x1, y1] = p(bb.n, bb.w);
    const [x2, y2] = p(bb.s, bb.e);
    if (Math.abs(x2 - x1) <= w - 2 * pad && Math.abs(y2 - y1) <= h - 2 * pad) best = z; else break;
  }
  return Number(best.toFixed(2));
}
const boundsCentre = () => {
  const lat = SPOTS.map((s) => s.lat);
  const lon = SPOTS.map((s) => s.lng);
  return {
    name: 'fitBounds',
    lat: (Math.max(...lat) + Math.min(...lat)) / 2,
    lon: (Math.max(...lon) + Math.min(...lon)) / 2,
  };
};

// ── 1. The tab's own opening framing ─────────────────────────────────────────────────────────────
console.log('══ 1. The opening framing — what a reader actually lands on ══');
console.log('   `MapContainer` reads `bounds` once at construction and fits the planning area.\n');
const centre0 = boundsCentre();
for (const vp of VIEWPORTS) {
  const { width: w, height: h } = boxes.viewports[vp.name].surfaces.none.frame;
  const cfg = configsFor(vp.name);
  const zooms = [fitZoom(w, h, 60), fitZoom(w, h, 28)];
  const comparable = widthComparable(vp.name);
  for (const zoom of zooms) {
    const { items } = itemsFor(vp.name, centre0, zoom);
    const counts = Object.fromEntries(
      Object.entries(cfg).map(([n, r]) => [n, run(items, w, h, r).size]),
    );
    const widthLine = comparable
      ? (counts['tl-334'] === counts['tl-504']
        ? (JSON.stringify([...run(items, w, h, cfg['tl-334'])]) === JSON.stringify([...run(items, w, h, cfg['tl-504'])])
          ? '334→504 IDENTICAL' : '334→504 same count, different positions')
        : '334→504 CHANGED')
      : '334→504 n/a (phone: the box is a full-width bar, neither width renders)';
    console.log(
      `   ${vp.name.padEnd(13)} z${String(zoom).padEnd(5)} ${String(items.length).padStart(2)} offered  `
      + Object.entries(counts).map(([n, c]) => `${n}=${c}`).join(' ') + `   ${widthLine}`,
    );
  }
}

// ── 2. The sweep ─────────────────────────────────────────────────────────────────────────────────
const PAIRS = [
  ['tl-334', 'tl-504'], ['tl-504', 'tl+land'],
  ['tl-504', 'tl+win'], ['tl-504', 'tl+win9'], ['tl-504', 'tl+reg'],
];

/**
 * The sweep runs across four axes rather than one configuration, because the first cut's single
 * configuration turned out to be doing the work: its fixed pans produced almost no contention, and
 * its invented top-right rect was load-bearing. Anything that survives every cell here is a result;
 * anything that does not is reported as sensitive, with the worst case named.
 */
const AXES = [];
for (const centres of ['pans', 'dense']) {
  for (const shell of SHELL_CHROME_BAND) {
    for (const selected of [false, true]) AXES.push({ centres, shell, selected });
  }
}

console.log('\n══ 2. The sweep — every cell of centres x frame-height x selection ══\n');
const worst = {};
for (const [from, to] of PAIRS) worst[`${from} → ${to}`] = { collateral: -1 };

for (const axis of AXES) {
  const label = `${axis.centres.padEnd(5)} shell:${String(axis.shell).padStart(3)} sel:${axis.selected ? 'y' : 'n'}`;
  for (const [from, to] of PAIRS) {
    const key = `${from} → ${to}`;
    const t = {
      states: 0, changed: 0, covered: 0, collateral: 0, moved: 0, partial: 0, at: [],
    };
    for (const vp of VIEWPORTS) {
      if (from === 'tl-334' && !widthComparable(vp.name)) continue;
      const cfg = configsFor(vp.name);
      for (const zoom of ZOOMS) {
        const centres = axis.centres === 'pans' ? CENTRES : denseCentres(vp.name, zoom);
        for (const centre of centres) {
          const { items, w, h } = itemsFor(
            vp.name, centre, zoom, axis.shell,
            axis.selected ? (SPOTS.find((sp) => {
              const pr = projector(centre, zoom, w0(vp), h0(vp) - axis.shell);
              const [x, y] = pr(sp.lat, sp.lng);
              return x > 0 && x < w0(vp) && y > 0 && y < h0(vp) - axis.shell;
            })?.name ?? null) : null,
          );
          const a = run(items, w, h, cfg[from]);
          const b = run(items, w, h, cfg[to]);
          const r = classify(a, b, cfg[from], cfg[to]);
          t.states += 1;
          if (r.covered.length || r.collateral.length || r.moved.length) t.changed += 1;
          t.covered += r.covered.length;
          t.collateral += r.collateral.length;
          t.moved += r.moved.length;
          t.partial += r.covered.filter((c) => c.frac < 0.5).length;
          for (const c of r.collateral) {
            t.at.push(`${c.key} @(${Math.round(c.at[0])},${Math.round(c.at[1])}) ${vp.name}/z${zoom}`);
          }
        }
      }
    }
    if (t.collateral > worst[key].collateral) worst[key] = { ...t, axis: label };
    console.log(
      `   ${label} | ${key.padEnd(18)} ${String(t.states).padStart(4)} pairs · ${String(t.changed).padStart(3)} changed `
      + `· drops ${String(t.covered).padStart(4)} covered / ${String(t.collateral).padStart(2)} COLLATERAL `
      + `· ${String(t.moved).padStart(3)} moved · ${String(t.partial).padStart(2)} under-half-covered`,
    );
  }
  console.log('');
}

console.log('══ Worst case per pair, across every cell ══\n');
for (const [pair, t] of Object.entries(worst)) {
  console.log(`   ${pair.padEnd(18)} ${t.collateral} collateral  (at ${t.axis})`);
  for (const a of [...new Set(t.at)].slice(0, 4)) console.log(`        └ ${a}`);
}

// ── 3. Magnitude ─────────────────────────────────────────────────────────────────────────────────
console.log('\n══ 3. Magnitude — how much of the placed set each panel removes ══\n');
for (const vp of VIEWPORTS) {
  const cfg = configsFor(vp.name);
  const { width: w, height: h } = boxes.viewports[vp.name].surfaces.none.frame;
  const tot = {};
  for (const n of Object.keys(cfg)) tot[n] = 0;
  for (const centre of CENTRES) {
    for (const zoom of ZOOMS) {
      const { items } = itemsFor(vp.name, centre, zoom);
      for (const [n, rects] of Object.entries(cfg)) tot[n] += run(items, w, h, rects).size;
    }
  }
  const base = tot['tl-504'];
  console.log(
    `   ${vp.name.padEnd(13)} tl-504=${String(base).padStart(3)}  `
    + ['tl+land', 'tl+win', 'tl+reg']
      .map((n) => `${n} ${(((tot[n] - base) / base) * 100).toFixed(1)}%`).join('  '),
  );
}
