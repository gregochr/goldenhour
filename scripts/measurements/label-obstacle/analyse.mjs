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
  ALWAYS_ON, FRAME_PAD_DEG, FRONTEND, HERE, ZOOM_GRIDS, centresFor, fitBoundsCentre,
  latLngToPoint, loadRoster, openingBounds, projector, spotsFrom,
} from './lib.mjs';

const {
  chipCandidates, regionLabelItems, placeLabelPass, REGION_TINY_FRAME_WIDTH,
} = await import(resolve(FRONTEND, 'src/utils/mapLabels.js'));
const {
  MAP_NUDGES, mapDxOffsets, placeWithNudges, seedObstacles,
} = await import(resolve(FRONTEND, 'src/utils/labelPlacement.js'));

const boxes = JSON.parse(readFileSync(resolve(HERE, 'out/boxes.json'), 'utf8'));
/**
 * Frames are keyed `<viewport>@<shell>` and each was MEASURED at that height — see `measure.mjs`'s
 * note on why the short case is not derived by subtracting from the tall one.
 */
const FRAMES = Object.keys(boxes.viewports);
const frameOf = (k) => boxes.viewports[k].surfaces.none.frame;
const ROSTER = loadRoster();
/**
 * Two rosters, differing only in whether a drive time exists.
 *
 * ⚠️ A free parameter until a review found it. `chipCandidates` sorts on rating, then tide, then
 * DRIVE — so whether drive minutes exist reorders the greedy pass and moves every count. A reader
 * with no postcode has none (`reachById` empty, `driveMinutesFor` returns null); one with a
 * postcode has them for the measured locations. Rather than pick, the sweep runs both and reports
 * the worst, which is how every other free parameter here is handled.
 */
const SPOTS_BY_DRIVE = { none: spotsFrom(ROSTER), some: spotsFrom(ROSTER, { driveTimes: true }) };
let SPOTS = SPOTS_BY_DRIVE.none;
const CENTRES = centresFor(SPOTS);   // geometry only — identical for both rosters

/**
 * ⚠️ The 334px arm is SYNTHETIC — it overrides the measured width to recreate a size the CSS no
 * longer produces. That is only meaningful where 334 and 504 are both widths this rule can emit.
 * Below 640px `index.css` releases the bound entirely (`left: 8px; right: 8px; max-width: none`),
 * so the box is a full-width bar and NEITHER figure renders: the phone is excluded from that
 * comparison rather than reported as a passing viewport.
 */
/** The width the control had when §4 #31 licensed the previous widening. */
const HISTORIC_WIDTH = 334;

/**
 * Whether a frame can host the widening comparison at all — and, if so, what the widening IS there.
 *
 * ⚠️ It is not always `334 → 504`. `max-width: calc(100% - 308px)` clamps the control, so on the
 * 788px frame the change production actually made was **334 → 480**. An earlier cut tested for
 * exactly 504 and so *discarded* the tablet, which excludes a real affected viewport rather than
 * measuring it; the cut before that tested the viewport width and silently kept the tablet at a
 * comparison the CSS never emits. The right test is neither: compare 334 against whatever the
 * control MEASURES on that frame, and skip only frames where 334 was never the width.
 *
 * The phone is the one such frame. Below 640px `index.css` releases the bound entirely
 * (`left: 8px; right: 8px; max-width: none`), so the control is frame-driven at 374px and there is
 * no widening to license — the box is whatever the frame is.
 */
function wideningOn(k) {
  const tl = boxes.viewports[k].surfaces.none.obstacles['wf-map-chrome-tl'];
  return boxes.viewports[k].width > 639 && tl.width > HISTORIC_WIDTH ? tl.width : null;
}

function configsFor(vpName) {
  const s = boxes.viewports[vpName].surfaces;
  const tl = s.none.obstacles['wf-map-chrome-tl'];
  // Every always-on rect, as MEASURED — never modelled. Held constant in both arms.
  // ⚠️ Drop zero-area rects. On a phone the Legend wrapper renders empty (`!isMobile`), and
  // `seedObstacles`' 5px pad would inflate a 0x0 box into a 10x10 obstacle the app never has.
  const chrome = ALWAYS_ON.map((t) => s.none.obstacles[t])
    .filter((r) => r && r.width > 0 && r.height > 0);
  const at = (width) => ({ ...tl, width });
  return {
    'tl-334': [at(HISTORIC_WIDTH), ...chrome],
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
  const v = frameOf(vpName);
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
function itemsFor(vpName, centre, zoom, selectedName = null) {
  const v = boxes.viewports[vpName].surfaces.none;
  const w = v.frame.width;
  const h = v.frame.height;
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
const run = (items, w, h, rects) => placeLabelPass(items, w, h, seed(rects));
const hits = (b, o) => b.x < o.x + o.w && b.x + b.w > o.x && b.y < o.y + o.h && b.y + b.h > o.y;

/** Classify one placed-set pair. */
function classify(a, b, fromRects, toRects) {
  const fromObs = seed(fromRects);
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
  // ⚠️ For an obstacle that GREW, only the strip it gained is new geometry. Taking the whole new
  // rect inflates `coveredFrac` with area that was already there in both arms, which understates
  // Result 3's under-half-covered count for that pair.
  const grewFrom = new Map(fromRects.map((r) => [`${r.left},${r.top}`, r]));
  const realAdded = [];
  for (const r of toRects) {
    if (fromRects.includes(r)) continue;
    const g = grewFrom.get(`${r.left},${r.top}`);
    if (g && r.width > g.width && r.height === g.height) {
      realAdded.push({ x: g.left + g.width, y: g.top, w: r.width - g.width, h: g.height });
    } else {
      realAdded.push({ x: r.left, y: r.top, w: r.width, h: r.height });
    }
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
    const gap = Math.min(...realAdded.map((o) => Math.max(
      0, o.x - (boxA.x + boxA.w), boxA.x - (o.x + o.w), o.y - (boxA.y + boxA.h), boxA.y - (o.y + o.h),
    )), Infinity);
    (addedCovers(boxA) ? out.covered : out.collateral)
      .push({ key: k, at: [boxA.x, boxA.y], frac, gap });
  }
  return out;
}

/**
 * Leaflet's `getBoundsZoom` for the roster's bounds in a `w`x`h` frame with `pad` px of padding.
 *
 * ⚠️ CONTINUOUS, not stepped. With `zoomSnap: 0` — which the tab sets — `getBoundsZoom` skips its
 * `if (snap)` branch entirely and returns the raw `getScaleZoom(scale)`. An earlier cut searched a
 * 0.01 grid and so tested a camera up to half a step from production's; across a 500px offset that
 * is several pixels, which is material to the edge-sensitive collisions this harness exists for.
 * For EPSG3857 `getScaleZoom(scale, z0) = z0 + log2(scale)`, so it closes in one expression.
 *
 * ⚠️ The bounds are the WHOLE roster's, PADDED as production pads them (`openingBounds`).
 * That is the no-postcode case. Production narrows them
 * through `scopeSpots(heatSpots, reachById, origin)`, so a reader with saved drive times or an away
 * origin opens on a different camera — but with no postcode `reachById` is empty, every region
 * counts as in-area, and `framed` is the whole catalogue (`WindowFirstMapPane`'s own comment says
 * so). Since this harness excludes the home marker and the reach rings anyway, that is the
 * configuration it is measuring throughout; §4b.1's Result 1 says so.
 *
 * ⚠️ And they are PADDED, as production pads them — see `openingBounds` in `lib.mjs` for why that
 * is `heatGeometry`'s helper rather than Leaflet's, and for the false claim an earlier cut made
 * about it.
 */
function fitZoom(w, h, pad, padDeg) {
  const b = openingBounds(SPOTS, padDeg);
  const z0 = 10;
  const nw = latLngToPoint(b.north, b.west, z0);
  const se = latLngToPoint(b.south, b.east, z0);
  const scale = Math.min((w - 2 * pad) / Math.abs(se.x - nw.x), (h - 2 * pad) / Math.abs(se.y - nw.y));
  return z0 + Math.log2(scale);
}

// ── 1. The tab's own opening framing ─────────────────────────────────────────────────────────────
console.log('══ 1. The opening framing — what a reader actually lands on ══');
console.log('   `MapContainer` reads `bounds` once at construction and fits the planning area.\n');

for (const fk of FRAMES) {
  const { width: w, height: h } = frameOf(fk);
  const cfg = configsFor(fk);
  /**
   * The 28px arm only — over the PADDED area bounds
   * (`heatGeometry.latLngBounds(framed, FRAME_PAD_DEG)`), which is what `openingBounds` carries.
   *
   * ⚠️ The 60px fallback is REACHABLE but cannot bear on this question, and both of my earlier
   * treatments of it were wrong. First I dropped it as unreachable — false: `heat.enabled` keys on
   * the full catalogue, so a reader whose saved reach puts every region beyond `GLANCE_MINUTES`
   * gets an empty `framed`, a null `areaBounds` and that branch while `MapLabels` still mounts.
   * Then I restored it fitting all 210 spots — also false, because in exactly that state the label
   * pool is empty: `scopedVisibleLocations` filters `visibleLocations` through the empty
   * `heat.areaSpots`, leaving at most the appended selection. Nor can the reader widen scope out of
   * it — `hasHome` requires `framed.length > 0`, so `FiltersPopover` withholds the scope segment
   * entirely.
   *
   * So that camera carries at most one chip and its region label. It is a real state; it simply
   * cannot demonstrate anything about whether a wider obstacle moves a label, which is what this
   * section asks. Measuring it over the whole roster would be a fiction, and measuring it over one
   * chip would be a null with no content.
   */
  const zooms = [[fitZoom(w, h, 28, FRAME_PAD_DEG), FRAME_PAD_DEG]];
  const comparable = wideningOn(fk);
  for (const [zoom, padDeg] of zooms) {
    const { items } = itemsFor(fk, fitBoundsCentre(SPOTS, zoom, padDeg), zoom);
    const counts = Object.fromEntries(
      Object.entries(cfg).map(([n, r]) => [n, run(items, w, h, r).size]),
    );
    const widthLine = comparable
      ? (counts['tl-334'] === counts['tl-504']
        ? (JSON.stringify([...run(items, w, h, cfg['tl-334'])]) === JSON.stringify([...run(items, w, h, cfg['tl-504'])])
          ? `334→${Math.round(comparable)} IDENTICAL` : `334→${Math.round(comparable)} same count, different positions`)
        : `334→${Math.round(comparable)} CHANGED`)
      : '334→? n/a (phone: the bound is released; the box is a full-width bar)';
    console.log(
      `   ${fk.padEnd(17)} z${zoom.toFixed(3).padEnd(7)} ${String(items.length).padStart(2)} offered  `
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
for (const drive of ['none', 'some']) {
  for (const grid of Object.keys(ZOOM_GRIDS)) {
    for (const centres of ['pans', 'dense']) {
      for (const selected of [false, true]) AXES.push({ drive, grid, centres, selected });
    }
  }
}

console.log('\n══ 2. The sweep — centres x selection, over every measured frame ══');
console.log('   (each viewport measured at BOTH frame heights: full, and minus the app shell)\n');
const worst = {};
for (const [from, to] of PAIRS) worst[`${from} → ${to}`] = { collateral: -1 };

for (const axis of AXES) {
  const label = `drv:${axis.drive.padEnd(4)} z${axis.grid} ${axis.centres.padEnd(5)} sel:${axis.selected ? 'y' : 'n'}`;
  SPOTS = SPOTS_BY_DRIVE[axis.drive];
  for (const [from, to] of PAIRS) {
    const key = `${from} → ${to}`;
    const t = {
      states: 0, changed: 0, covered: 0, collateral: 0, moved: 0, partial: 0, at: [], gap: [],
    };
    for (const fk of FRAMES) {
      if (from === 'tl-334' && !wideningOn(fk)) continue;
      const cfg = configsFor(fk);
      const { width: w, height: h } = frameOf(fk);
      for (const zoom of ZOOM_GRIDS[axis.grid]) {
        const centres = axis.centres === 'pans' ? CENTRES : denseCentres(fk, zoom);
        for (const centre of centres) {
          let sel = null;
          if (axis.selected) {
            const pr = projector(centre, zoom, w, h);
            sel = SPOTS.find((sp) => {
              const [x, y] = pr(sp.lat, sp.lng);
              return x > 0 && x < w && y > 0 && y < h;
            })?.name ?? null;
          }
          const { items } = itemsFor(fk, centre, zoom, sel);
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
            t.at.push(`${c.key} @(${Math.round(c.at[0])},${Math.round(c.at[1])}) ${fk}/z${zoom}`);
            t.gap.push(c.gap);
          }
        }
      }
    }
    if (t.collateral > worst[key].collateral) worst[key] = { ...t, axis: label };
    console.log(
      `   ${label} | ${key.padEnd(18)} ${String(t.states).padStart(4)} pairs · ${String(t.changed).padStart(3)} changed `
      + `· drops ${String(t.covered).padStart(4)} covered / ${String(t.collateral).padStart(2)} COLLATERAL `
      + `· ${String(t.moved).padStart(3)} moved · ${String(t.partial).padStart(3)} under-half-covered`,
    );
  }
  console.log('');
}

console.log('══ Worst case per pair, across every cell ══\n');
for (const [pair, t] of Object.entries(worst)) {
  console.log(`   ${pair.padEnd(18)} ${t.collateral} collateral  (at ${t.axis})`);
  // Distinct labels, named ones first — "a scatter point" and "a real destination" are different
  // costs, and the sliced sample used to hide which this was.
  // ⚠️ How far each lost label actually sat from the panel. "Had clear air" is a claim about
  // distance, and a label 2px outside an opaque plate is a much weaker example than one 100px out.
  const far = t.gap.filter((g) => g > 30).length;
  const halo = t.gap.filter((g) => g <= 5).length;
  console.log(`        └ clearance from the panel: ${halo} within 5px, ${far} beyond 30px`);
  const distinct = [...new Set(t.at.map((x) => x.split(' @')[0]))];
  const named = distinct.filter((k) => !/^chip:Scatter /.test(k));
  console.log(`        └ ${distinct.length} distinct labels; ${named.length} named: ${named.map((k) => k.replace('chip:', '')).join(', ') || '—'}`);
}

// ── 3. Magnitude ─────────────────────────────────────────────────────────────────────────────────
console.log('\n══ 3. Magnitude — how much of the placed set each panel removes ══\n');
// ⚠️ Pin the roster, for the same reason section 4 does: the sweep reassigns `SPOTS` per axis.
SPOTS = SPOTS_BY_DRIVE.none;
for (const fk of FRAMES) {
  const cfg = configsFor(fk);
  const { width: w, height: h } = frameOf(fk);
  const tot = {};
  for (const n of Object.keys(cfg)) tot[n] = 0;
  for (const centre of CENTRES) {
    for (const zoom of ZOOM_GRIDS.a) {
      const { items } = itemsFor(fk, centre, zoom);
      for (const [n, rects] of Object.entries(cfg)) tot[n] += run(items, w, h, rects).size;
    }
  }
  const base = tot['tl-504'];
  console.log(
    `   ${fk.padEnd(17)} tl-504=${String(base).padStart(3)}  `
    + ['tl+land', 'tl+win', 'tl+reg']
      .map((n) => `${n} ${(((tot[n] - base) / base) * 100).toFixed(1)}%`).join('  '),
  );
}

// ── 4. The escape ladder ─────────────────────────────────────────────────────────────────────────
/**
 * How often an anchor INSIDE the seeded top-left obstacle still finds a rung.
 *
 * ⚠️ Emitted by the instrument rather than derived by hand, and reported with its population —
 * an earlier cut quoted this figure from one cell of the sweep as though it were general, and
 * quoted it from a superseded run at that.
 */
console.log('\n══ 4. Escape ladder — anchors inside the seeded top-left obstacle ══\n');
{
  // ⚠️ Pin the roster. The sweep above reassigns `SPOTS` per axis, so inheriting it here would
  // report against whichever drive-time arm happened to run last.
  SPOTS = SPOTS_BY_DRIVE.none;
  let total = 0;
  let escaped = 0;
  for (const fk of FRAMES) {
    const v = boxes.viewports[fk].surfaces.none;
    const { width: w, height: h } = v.frame;
    const cfg = configsFor(fk);
    const obs = seed(cfg['tl-504']);
    const tl = obs[0];
    const within = (x, y) => x >= tl.x && x <= tl.x + tl.w && y >= tl.y && y <= tl.y + tl.h;
    for (const zoom of ZOOM_GRIDS.a) {
      for (const centre of CENTRES) {
        const { items } = itemsFor(fk, centre, zoom);
        const out = run(items, w, h, cfg['tl-504']);
        for (const it of items) {
          if (!within(it.x, it.y)) continue;
          total += 1;
          if (out.has(it.key)) escaped += 1;
        }
      }
    }
  }
  console.log(
    `   ${escaped} of ${total} placed anyway (${((escaped / total) * 100).toFixed(0)}%)`
    + `  — population: every frame x grid a x the five fixed pans`,
  );
  console.log('   So "an obstacle that swallows a label\'s anchor cannot be escaped" is false.');
}

// ── 5. Coverage is a fraction, not a boolean ─────────────────────────────────────────────────────
console.log('\n══ 5. Coverage is a fraction ══\n');
console.log('   Each sweep row above reports its own "N under-half-covered" against its own covered');
console.log('   total. A label the newly-opened obstacle covers by less than half was visible, and');
console.log('   is now gone — so "covered" is not a synonym for "was invisible anyway".');
console.log('   ⚠️ Read those per-row; summing the column across rows double-counts states, because');
console.log('   the axes revisit the same viewports and zooms and win/win9 are one panel twice.');

// ── 6. The two cures R7 rules out ────────────────────────────────────────────────────────────────
/**
 * ⚠️ These ran as throwaway scripts when R7 was written, and the plan quoted their numbers while
 * the instrument could not produce them — the exact defect this whole directory exists to prevent,
 * one level down. They are committed here so the residual's reasoning is re-runnable.
 *
 * Population for both: the no-drive roster, zoom grid a, the five fixed pans, every measured frame,
 * and each of the three panels — printed below so it is never inferred.
 */
console.log('\n══ 6. The two cures R7 rules out ══\n');
{
  SPOTS = SPOTS_BY_DRIVE.none;
  const PANELS = [['wf-land', 'land'], ['wf-win-panel', 'win'], ['wf-reg-panel', 'reg']];
  let states = 0;
  let dropped = 0;
  let recovered = 0;
  let seededPlaced = 0;
  let culledPlaced = 0;
  let rescued = 0;
  let churned = 0;
  let cullCollateral = 0;
  for (const fk of FRAMES) {
    const v = boxes.viewports[fk].surfaces;
    const { width: w, height: h } = frameOf(fk);
    const tl = v.none.obstacles['wf-map-chrome-tl'];
    const chrome = ALWAYS_ON.map((t) => v.none.obstacles[t]).filter((r) => r && r.width > 0);
    for (const [testid, surface] of PANELS) {
      const panel = v[surface].obstacles[testid];
      const withPanel = [tl, panel, ...chrome];
      const withoutPanel = [tl, ...chrome];
      for (const zoom of ZOOM_GRIDS.a) {
        for (const centre of CENTRES) {
          const { items } = itemsFor(fk, centre, zoom);
          states += 1;

          // (a) A RETRY pass after the greedy one. `placeLabelPass` only ever grows `boxes` and
          //     `placeWithNudges` rejects on any overlap, so a dropped item must fail against every
          //     later superset. This is a proof; the run is corroboration.
          const placed = run(items, w, h, withPanel);
          const obs = seed(withPanel);
          const finalBoxes = [...obs, ...placed.values()];
          for (const it of items) {
            if (placed.has(it.key)) continue;
            dropped += 1;
            const box = placeWithNudges(
              { x: it.x, y: it.y }, { w: it.w, h: it.h }, finalBoxes, w, h,
              { dy: MAP_NUDGES, dx: mapDxOffsets },
            );
            if (box) recovered += 1;
          }

          // (b) PLACE-THEN-CULL: place as if the panel were not there, then drop whatever it
          //     covers. Collateral-free by construction — the question is what it costs in labels
          //     that seeding would have RELOCATED rather than lost.
          const bare = run(items, w, h, withoutPanel);
          const panelRect = {
            x: panel.left, y: panel.top, w: panel.width, h: panel.height,
          };
          let kept = 0;
          for (const [, b] of bare) if (!hits(b, panelRect)) kept += 1;
          seededPlaced += placed.size;
          culledPlaced += kept;
          // ⚠️ A relocation only counts as a WIN if the label would otherwise have been under the
          // panel. An earlier cut counted every position change, which folds in labels that were
          // clear in both arms and merely shifted — churn, not a benefit of seeding — and so
          // inflated the case for seeding. Split three ways instead.
          for (const [k, b] of placed) {
            const before = bare.get(k);
            if (!before || (before.x === b.x && before.y === b.y)) continue;
            if (hits(before, panelRect)) rescued += 1; else churned += 1;
          }
          for (const [k, b] of bare) {
            if (placed.has(k) || hits(b, panelRect)) continue;
            cullCollateral += 1;
          }
        }
      }
    }
  }
  console.log(`   population: ${states} states — no-drive roster x grid a x 5 fixed pans x ${FRAMES.length} frames x 3 panels\n`);
  console.log(`   (a) retry after the greedy pass: ${recovered} of ${dropped} drops recovered`);
  console.log('       → nothing, and by construction: `boxes` only grows, `placeWithNudges` rejects');
  console.log('         on any overlap, so a drop must fail against every later superset.\n');
  console.log(`   (b) place-then-cull vs seeding: ${culledPlaced} labels placed vs ${seededPlaced} when seeded`);
  console.log(`       seeding RESCUES ${rescued} label(s) that culling would simply lose (their`);
  console.log('           un-seeded position is under the panel; seeding moves them into the clear)');
  console.log(`       seeding COSTS ${cullCollateral} label(s) that culling would keep (the collateral)`);
  console.log(`       churn — moved but clear in both arms, neither a gain nor a loss: ${churned}`);
  console.log(`       → net ${seededPlaced - culledPlaced >= 0 ? '+' : ''}${seededPlaced - culledPlaced} labels in favour of seeding.`);
}
