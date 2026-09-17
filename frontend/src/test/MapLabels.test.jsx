import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen,
} from '@testing-library/react';

const setPosition = vi.fn();
vi.mock('leaflet', () => {
  const DomUtil = { setPosition: (...args) => setPosition(...args) };
  return { default: { DomUtil }, DomUtil };
});

let currentMap = null;
vi.mock('react-leaflet', () => ({ useMap: () => currentMap }));

import MapLabels from '../components/map/MapLabels.jsx';
import { rampHex, setMode } from '../utils/scoreRamp.js';

/**
 * The Map tab's HTML label layer (map-tab-v2-plan.md §3 P8) — the WIRING suite. The placement
 * arithmetic itself (priority order, the density ramp, drop-not-stack, the ring-label wording
 * rule) is `mapLabels.test.js`'s job, run against pure functions with no DOM at all; this file
 * proves the React/Leaflet host calls them correctly, measures real DOM boxes, seeds obstacles
 * from the live chrome, and wires clicks/hover through to the caller.
 *
 * <h2>jsdom lays nothing out</h2>
 *
 * <p>Every element's `offsetWidth`/`offsetHeight` is 0 by default, and the component's own
 * zero-guard means an unstubbed run places nothing at all — so every test that needs a label
 * PLACED stubs both via `withMeasuredLabels`, the same idiom `WindowRowFieldMap.test.jsx` uses for
 * its own chips. `getBoundingClientRect` is similarly zero everywhere by default; obstacle tests
 * stub it per-element with `vi.spyOn`.
 */

function withMeasuredLabels(width, height) {
  const w = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
  const h = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get: () => width });
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get: () => height });
  return () => {
    if (w) Object.defineProperty(HTMLElement.prototype, 'offsetWidth', w);
    else delete HTMLElement.prototype.offsetWidth;
    if (h) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', h);
    else delete HTMLElement.prototype.offsetHeight;
  };
}

/**
 * A Leaflet map stubbed to what this layer touches. Distinct from `MapHeatLayer.test.jsx`'s own
 * `makeMap` (this layer reads `getBounds`/`getContainer().parentElement` neither of which that
 * file's stub carries), but the same shape otherwise.
 */
function makeMap({
  zoom = 9, size = { x: 800, y: 500 }, onScreen = true, boundsContainsAll = true,
} = {}) {
  const handlers = new Map();
  const wrap = document.createElement('div');
  const container = document.createElement('div');
  wrap.appendChild(container);
  document.body.appendChild(wrap);
  Object.defineProperty(container, 'offsetWidth', { value: onScreen ? size.x : 0, configurable: true });
  return {
    zoom,
    handlers,
    wrap,
    container,
    getZoom() { return this.zoom; },
    getSize: () => size,
    getContainer: () => container,
    // Read by `radiusFor` (`utils/heatField.js`) whenever a ring's px radius is computed.
    getCenter: () => ({ lat: 55, lng: -2 }),
    getBounds: () => ({ contains: () => boundsContainsAll }),
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 10, y: (56 - lat) * 10 }),
    containerPointToLayerPoint: (p) => ({ x: -40 + p[0], y: -25 + p[1] }),
    on(events, fn) { for (const e of events.split(' ')) handlers.set(e, [...(handlers.get(e) || []), fn]); },
    off(events, fn) {
      for (const e of events.split(' ')) {
        handlers.set(e, (handlers.get(e) || []).filter((h) => h !== fn));
      }
    },
    fire(event) { for (const fn of handlers.get(event) || []) fn(); },
  };
}

const SPOTS = [
  {
    name: 'Bamburgh', lat: 55.6, lng: -1.7, rid: 'North East', rating: 5, bortleClass: 4, driveMinutes: 90,
  },
  {
    name: 'Whitby', lat: 54.5, lng: -0.6, rid: 'North East', rating: 3, bortleClass: 5, driveMinutes: 120,
  },
  {
    name: 'Buttermere', lat: 54.5, lng: -3.1, rid: 'The Lakes', rating: 4, bortleClass: 3, driveMinutes: null,
  },
];

const HOME = { lat: 54.9, lon: -1.4 };

let frames = [];
let restoreMeasure;
// Saved and restored, matching `MapHeatLayer.test.jsx`. Symmetry, not a live fix: `isolate: true`
// keeps this out of every other file and `beforeEach` reinstalls the queue for every test in this one.
let originalRaf;
let originalCancel;

beforeEach(() => {
  frames = [];
  originalRaf = global.requestAnimationFrame;
  originalCancel = global.cancelAnimationFrame;
  global.requestAnimationFrame = (cb) => { frames.push(cb); return frames.length; };
  global.cancelAnimationFrame = (id) => { frames[id - 1] = null; };
  // The chip square's fill is the LIVE `scoreRamp` mode (`MapHeatLayer.test.jsx`'s own
  // precedent) — the module's own bootstrap default is 'verdict', so a test wanting the
  // temperature scale's hex values must ask for it explicitly.
  setMode('temp');
});

afterEach(() => {
  global.requestAnimationFrame = originalRaf;
  global.cancelAnimationFrame = originalCancel;
  currentMap = null;
  if (restoreMeasure) { restoreMeasure(); restoreMeasure = null; }
  document.body.innerHTML = '';
  setMode('verdict');
  vi.clearAllMocks();
});

const runFrames = () => {
  const due = frames;
  frames = [];
  for (const cb of due) if (cb) cb();
};

async function mount(props = {}) {
  let result;
  await act(async () => {
    result = render(<MapLabels spots={SPOTS} {...props} />);
  });
  return result;
}

/** A map stub that also supports Leaflet panes, for every test that needs the layer to render. */
function makeFullMap(opts = {}) {
  const base = makeMap(opts);
  const panes = {};
  return {
    ...base,
    getPane: (name) => panes[name] || null,
    createPane: (name) => {
      const el = document.createElement('div');
      panes[name] = el;
      base.container.appendChild(el);
      return el;
    },
    panes,
  };
}

describe('MapLabels — mounting', () => {
  it('renders nothing when the map cannot make a pane', async () => {
    // The base `makeMap()` (unlike `makeFullMap()`) carries no `createPane` at all — the shape a
    // map without pane support has. `getSize`/`getContainer` are still answered, so the assertion
    // is specifically about the missing pane, not a crash on an incomplete stub.
    currentMap = makeMap();
    const { container } = await mount();
    expect(container.querySelector('[data-testid="map-labels"]')).toBeNull();
  });

  it('creates its own pane at z-index 650, pointer-events none, reusing one that already exists', async () => {
    currentMap = makeFullMap();
    await mount();
    const pane = currentMap.panes['wf-labels'];
    expect(pane).toBeTruthy();
    // 650, NOT the design bundle's own 420 (PR #733 review, a confirmed finding) — Leaflet's real
    // `markerPane` sits at its own built-in 600, on the exact same projected points as the chips.
    // ⚠️ The original reason given here ("this app fades its markers back to full opacity and
    // interactivity past the zoom handover") is gone: `MapHeatLayer` now holds those panes at zero
    // unconditionally. 650 stays because the markers are still THERE — restored on unmount, and
    // fully painted in aurora mode, where this layer never mounts at all — and a chip must not
    // render, or hit-test, under the very marker it names.
    expect(pane.style.zIndex).toBe('650');
    expect(pane.style.pointerEvents).toBe('none');
  });

  it('sits ABOVE Leaflet\'s own marker pane (600) and BELOW its popup pane (700) — PR #733 review', async () => {
    currentMap = makeFullMap();
    await mount();
    const pane = currentMap.panes['wf-labels'];
    const z = Number(pane.style.zIndex);
    expect(z).toBeGreaterThan(600);
    expect(z).toBeLessThan(700);
  });

  it('portals the label layer into that pane', async () => {
    currentMap = makeFullMap();
    await mount();
    const pane = currentMap.panes['wf-labels'];
    expect(pane.querySelector('[data-testid="map-labels"]')).not.toBeNull();
  });

  it('renders nothing at all while the shell has the pane hidden', async () => {
    currentMap = makeFullMap({ onScreen: false });
    await mount();
    const pane = currentMap.panes['wf-labels'];
    expect(pane.querySelector('[data-testid="map-labels"]')).toBeNull();
  });
});

describe('MapLabels — home marker', () => {
  it('renders below the zoom gate (13), positioned at the home point', async () => {
    restoreMeasure = withMeasuredLabels(30, 14);
    currentMap = makeFullMap({ zoom: 9 });
    await mount({ homeCoords: HOME });
    await act(async () => { runFrames(); });
    const home = document.querySelector('[data-testid="map-label-home"]');
    expect(home).not.toBeNull();
    expect(home).toHaveTextContent('HOME');
    expect(home).toHaveAttribute('aria-hidden', 'true');
    // The rendered STYLE is the placed BOX's top-left corner (anchor minus half the measured
    // size, at the first unblocked rung — dy=0/dx=0 here, nothing else on screen to collide
    // with), not the raw anchor point itself.
    const anchor = currentMap.latLngToContainerPoint([HOME.lat, HOME.lon]);
    expect(home.style.left).toBe(`${anchor.x - 15}px`);
    expect(home.style.top).toBe(`${anchor.y - 7}px`);
  });

  it('is absent at/above the zoom gate', async () => {
    restoreMeasure = withMeasuredLabels(30, 14);
    currentMap = makeFullMap({ zoom: 13 });
    await mount({ homeCoords: HOME });
    await act(async () => { runFrames(); });
    expect(document.querySelector('[data-testid="map-label-home"]')).toBeNull();
  });

  it('is absent with no home coordinates', async () => {
    restoreMeasure = withMeasuredLabels(30, 14);
    currentMap = makeFullMap({ zoom: 9 });
    await mount();
    await act(async () => { runFrames(); });
    expect(document.querySelector('[data-testid="map-label-home"]')).toBeNull();
  });
});

describe('MapLabels — ring labels', () => {
  // Both ring text cases need a radius small enough (relative to the frame) that neither ring's
  // label fails the y > 10 && y < frameHeight - 10 keep-in check — a low zoom (small real-world
  // metres-per-pixel ratio) and a tall frame, with home projected to the vertical centre rather
  // than the realistic-but-near-the-top point `makeMap`'s own projection would give it.
  function makeRingFriendlyMap() {
    const map = makeFullMap({ zoom: 6, size: { x: 800, y: 800 } });
    // Home itself still projects to the frame's centre (this block's original intent), but Y must
    // now genuinely VARY with latitude: `pxPerKmAtHome` (map-tab-v2-plan.md §3 P8 review) measures
    // a real 1° delta at home's own latitude, so a projection that ignores its input entirely
    // would give Δy=0 → 0 px/km → every ring radius 0, failing the RING_MIN_PX floor. 100px per
    // degree of latitude keeps both tiers comfortably inside [RING_MIN_PX, the off-frame ceiling]
    // for an 800×800 frame.
    map.latLngToContainerPoint = ([lat]) => ({ x: 400, y: 400 - (lat - HOME.lat) * 100 });
    return map;
  }

  it('states a DISTANCE by default, a DURATION only under reachMeasured', async () => {
    restoreMeasure = withMeasuredLabels(40, 12);
    currentMap = makeRingFriendlyMap();
    await mount({ homeCoords: HOME, rings: true, reachMeasured: false });
    await act(async () => { runFrames(); });
    const texts = [...document.querySelectorAll('[data-testid="map-label-ring"]')].map((n) => n.textContent);
    expect(texts).toEqual(expect.arrayContaining(['25 mi', '50 mi']));
  });

  it('switches to duration text when reachMeasured is true', async () => {
    restoreMeasure = withMeasuredLabels(40, 12);
    currentMap = makeRingFriendlyMap();
    await mount({ homeCoords: HOME, rings: true, reachMeasured: true });
    await act(async () => { runFrames(); });
    const texts = [...document.querySelectorAll('[data-testid="map-label-ring"]')].map((n) => n.textContent);
    expect(texts).toEqual(expect.arrayContaining(['45 min', '1h 30min']));
  });

  it('is absent when the rings toggle is off, even with home present', async () => {
    restoreMeasure = withMeasuredLabels(40, 12);
    currentMap = makeFullMap({ zoom: 9 });
    await mount({ homeCoords: HOME, rings: false });
    await act(async () => { runFrames(); });
    expect(document.querySelectorAll('[data-testid="map-label-ring"]')).toHaveLength(0);
  });

  it('is absent at/above the ring zoom gate (10.6)', async () => {
    restoreMeasure = withMeasuredLabels(40, 12);
    currentMap = makeFullMap({ zoom: 11 });
    await mount({ homeCoords: HOME, rings: true });
    await act(async () => { runFrames(); });
    expect(document.querySelectorAll('[data-testid="map-label-ring"]')).toHaveLength(0);
  });

  it('honours the shared RING_MIN_PX floor — an illegibly small ring gets no label either', async () => {
    restoreMeasure = withMeasuredLabels(40, 12);
    currentMap = makeFullMap({ zoom: 9 });
    // 0.1px per degree of latitude: 25mi and 50mi both project under RING_MIN_PX (18).
    currentMap.latLngToContainerPoint = ([lat]) => ({ x: 400, y: 400 - (lat - HOME.lat) * 0.1 });
    await mount({ homeCoords: HOME, rings: true });
    await act(async () => { runFrames(); });
    expect(document.querySelectorAll('[data-testid="map-label-ring"]')).toHaveLength(0);
  });

  it('honours the off-frame ceiling — a ring bigger than 1.15× the frame gets no label either', async () => {
    restoreMeasure = withMeasuredLabels(40, 12);
    currentMap = makeFullMap({ zoom: 9, size: { x: 100, y: 80 } });
    // 1000px per degree of latitude against a tiny 100×80 frame: both tiers land far past the
    // off-frame ceiling (1.15 × 100 = 115).
    currentMap.latLngToContainerPoint = ([lat]) => ({ x: 400, y: 400 - (lat - HOME.lat) * 1000 });
    await mount({ homeCoords: HOME, rings: true });
    await act(async () => { runFrames(); });
    expect(document.querySelectorAll('[data-testid="map-label-ring"]')).toHaveLength(0);
  });
});

describe('MapLabels — region names', () => {
  it('one label per distinct region, marking the hottest', async () => {
    restoreMeasure = withMeasuredLabels(60, 12);
    currentMap = makeFullMap({ zoom: 9 });
    await mount();
    await act(async () => { runFrames(); });
    const regions = [...document.querySelectorAll('[data-testid="map-label-region"]')];
    expect(regions.map((r) => r.textContent).sort()).toEqual(['North East', 'The Lakes']);
    // North East's mean (5,3)=4 beats The Lakes' single 4 — a tie-break case is not what this
    // asserts; it asserts SOME region is marked hot and the other is not.
    const hot = regions.filter((r) => r.dataset.hot === 'true');
    expect(hot).toHaveLength(1);
  });

  it('is absent at/above the region zoom gate (11.2)', async () => {
    restoreMeasure = withMeasuredLabels(60, 12);
    currentMap = makeFullMap({ zoom: 12 });
    await mount();
    await act(async () => { runFrames(); });
    expect(document.querySelectorAll('[data-testid="map-label-region"]')).toHaveLength(0);
  });

  it('marks itself tiny under a 430px frame, for CSS truncation', async () => {
    restoreMeasure = withMeasuredLabels(60, 12);
    currentMap = makeFullMap({ zoom: 9, size: { x: 400, y: 500 } });
    await mount();
    await act(async () => { runFrames(); });
    const regions = [...document.querySelectorAll('[data-testid="map-label-region"]')];
    expect(regions.length).toBeGreaterThan(0);
    for (const r of regions) expect(r.dataset.tiny).toBe('true');
  });
});

describe('MapLabels — location chips: ink, click, tooltip', () => {
  it('the star numeral is --ink (the app\'s --color-plex-text), the square is rampHex at the whole star — never ramp ink on the numeral', async () => {
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeFullMap({ zoom: 13 });
    await mount();
    await act(async () => { runFrames(); });
    const chip = [...document.querySelectorAll('[data-testid="map-label-chip"]')]
      .find((el) => el.textContent.includes('Bamburgh'));
    expect(chip).toBeTruthy();
    const square = chip.querySelector('.wf-maplab-chip-m');
    const star = chip.querySelector('.wf-maplab-chip-r');
    expect(star).toHaveTextContent('5★');
    // The star's colour comes ONLY from the `.wf-maplab-chip-r` CSS rule (index.css:
    // `color: var(--color-plex-text)`), never an inline style — asserting NO inline colour is
    // the jsdom-provable half of "never ramp ink on the numeral" (the cascade itself is a browser
    // claim, per CLAUDE.md, and index.css's own rule comment records the ink source).
    expect(star.style.color).toBe('');
    // The 5px square IS a fill, and README's own ink rule permits ramp colour there — asserted
    // against the SAME `rampHex` function the app ships (round-tripped through jsdom's own
    // hex-to-rgb serialisation of an inline `background`, which is why the comparison is an
    // `rgb(...)` string rather than the hex `rampHex` itself returns), not a duplicated hex
    // literal computed independently.
    const probe = document.createElement('div');
    probe.style.background = rampHex(5);
    expect(square.style.background).toBe(probe.style.background);
  });

  it('carries a real accessible name — the text equivalent for the aria-hidden canvas beneath it (map-tab-v2-plan.md §3 P12)', async () => {
    // The heat/pins canvas is `aria-hidden` (`MapHeatLayer.test.jsx`'s own pin); a location chip is
    // this layer's own interactive surface, and its `aria-label` — not merely visible text inside
    // an `aria-hidden` ancestor — is what a screen reader actually announces for it.
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeFullMap({ zoom: 13 });
    await mount();
    await act(async () => { runFrames(); });
    const chip = [...document.querySelectorAll('[data-testid="map-label-chip"]')]
      .find((el) => el.textContent.includes('Bamburgh'));
    expect(chip).toHaveAttribute('aria-label', 'Bamburgh, 5 star');
  });

  it('names an unrated chip by its bare name — no false star claim in the accessible name', async () => {
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeFullMap({ zoom: 13 });
    await mount({
      spots: [{
        name: 'Unrated', lat: 55, lng: -2, rid: 'X', rating: null,
      }],
    });
    await act(async () => { runFrames(); });
    const chip = document.querySelector('[data-testid="map-label-chip"]');
    expect(chip).toHaveAttribute('aria-label', 'Unrated');
  });

  it('clicking a chip calls onSelect with the location name', async () => {
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeFullMap({ zoom: 13 });
    const onSelect = vi.fn();
    await mount({ onSelect });
    await act(async () => { runFrames(); });
    const chip = [...document.querySelectorAll('[data-testid="map-label-chip"]')]
      .find((el) => el.textContent.includes('Bamburgh'));
    fireEvent.click(chip);
    expect(onSelect).toHaveBeenCalledWith('Bamburgh');
  });

  it('the selected location carries data-selected and is always among the chips', async () => {
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeFullMap({ zoom: 13 });
    await mount({ selectedName: 'Buttermere' });
    await act(async () => { runFrames(); });
    const chip = [...document.querySelectorAll('[data-testid="map-label-chip"]')]
      .find((el) => el.textContent.includes('Buttermere'));
    expect(chip).toBeTruthy();
    expect(chip).toHaveAttribute('data-selected', 'true');
  });

  it('a spot with no rating for this window renders no star segment, and an unrated ink square', async () => {
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeFullMap({ zoom: 13 });
    await mount({
      spots: [{
        name: 'Unrated', lat: 55, lng: -2, rid: 'X', rating: null,
      }],
    });
    await act(async () => { runFrames(); });
    const chip = document.querySelector('[data-testid="map-label-chip"]');
    expect(chip).toBeTruthy();
    expect(chip.querySelector('.wf-maplab-chip-r')).toBeNull();
  });

  describe('the tide-fit glyph (tide-window-plan.md §3 T4) — the chip now answers the preference axis', () => {
    it('a MATCH renders the plain glyph, data-tide="match", and extends the aria-label', async () => {
      restoreMeasure = withMeasuredLabels(50, 14);
      currentMap = makeFullMap({ zoom: 13 });
      await mount({
        spots: [{
          name: 'Bamburgh', lat: 55.6, lng: -1.7, rid: 'North East', rating: 5, tideTier: 'match',
        }],
      });
      await act(async () => { runFrames(); });
      const chip = document.querySelector('[data-testid="map-label-chip"]');
      expect(chip).toHaveAttribute('data-tide', 'match');
      const glyph = chip.querySelector('[data-testid="map-label-chip-tide"]');
      expect(glyph).toBeInTheDocument();
      // The plain wave — no arrow path, no widened box — for a match, same shape as before T4.
      expect(glyph).not.toHaveAttribute('data-wide');
      expect(glyph.querySelectorAll('path')).toHaveLength(1);
      // Extends the star announcement rather than replacing it (aria-label REPLACES rendered
      // content, so the glyph's meaning has to be spelled out here for a screen-reader user).
      expect(chip).toHaveAttribute('aria-label', 'Bamburgh, 5 star, tide right here');
    });

    it('a MISS wanting HIGHER water draws the up-arrow variant and the matching aria clause', async () => {
      restoreMeasure = withMeasuredLabels(50, 14);
      currentMap = makeFullMap({ zoom: 13 });
      await mount({
        spots: [{
          name: 'Bamburgh',
          lat: 55.6,
          lng: -1.7,
          rid: 'North East',
          rating: 5,
          tideTier: 'miss',
          tideShortfall: 'HIGHER',
        }],
      });
      await act(async () => { runFrames(); });
      const chip = document.querySelector('[data-testid="map-label-chip"]');
      expect(chip).toHaveAttribute('data-tide', 'miss');
      const glyph = chip.querySelector('[data-testid="map-label-chip-tide"]');
      expect(glyph).toHaveAttribute('data-wide', 'true');
      // Wave path + the up-arrow path (`map-tide-v5.js:159`'s `M17.5 6.8V1.2…`).
      expect(glyph.querySelectorAll('path')).toHaveLength(2);
      expect(glyph.querySelectorAll('path')[1]).toHaveAttribute('d', expect.stringContaining('M17.5 6.8V1.2'));
      expect(chip).toHaveAttribute('aria-label', 'Bamburgh, 5 star, wants the water higher');
    });

    it('a MISS wanting LOWER water draws the down-arrow variant', async () => {
      restoreMeasure = withMeasuredLabels(50, 14);
      currentMap = makeFullMap({ zoom: 13 });
      await mount({
        spots: [{
          name: 'Bamburgh',
          lat: 55.6,
          lng: -1.7,
          rid: 'North East',
          rating: 5,
          tideTier: 'miss',
          tideShortfall: 'LOWER',
        }],
      });
      await act(async () => { runFrames(); });
      const chip = document.querySelector('[data-testid="map-label-chip"]');
      const glyph = chip.querySelector('[data-testid="map-label-chip-tide"]');
      expect(glyph.querySelectorAll('path')[1]).toHaveAttribute('d', expect.stringContaining('M17.5 1.2V6.8'));
      expect(chip).toHaveAttribute('aria-label', 'Bamburgh, 5 star, wants the water lower');
    });

    it('⚠️ a MISS with no served shortfall (a straddling want) draws the PLAIN wave, never a guessed arrow', async () => {
      restoreMeasure = withMeasuredLabels(50, 14);
      currentMap = makeFullMap({ zoom: 13 });
      await mount({
        spots: [{
          name: 'Bamburgh',
          lat: 55.6,
          lng: -1.7,
          rid: 'North East',
          rating: 5,
          tideTier: 'miss',
          tideShortfall: null,
        }],
      });
      await act(async () => { runFrames(); });
      const chip = document.querySelector('[data-testid="map-label-chip"]');
      expect(chip).toHaveAttribute('data-tide', 'miss');
      const glyph = chip.querySelector('[data-testid="map-label-chip-tide"]');
      expect(glyph).not.toHaveAttribute('data-wide');
      expect(glyph.querySelectorAll('path')).toHaveLength(1);
      expect(chip).toHaveAttribute('aria-label', 'Bamburgh, 5 star, wrong water');
    });

    it('the star numeral is drawn on a miss chip — dimming is a container opacity, never a re-colour (§7 check 12)', async () => {
      restoreMeasure = withMeasuredLabels(50, 14);
      currentMap = makeFullMap({ zoom: 13 });
      await mount({
        spots: [{
          name: 'Bamburgh', lat: 55.6, lng: -1.7, rid: 'North East', rating: 3, tideTier: 'miss',
        }],
      });
      await act(async () => { runFrames(); });
      const chip = document.querySelector('[data-testid="map-label-chip"]');
      const star = chip.querySelector('.wf-maplab-chip-r');
      expect(star).toHaveTextContent('3★');
      // Same assertion `MapLabels.test.jsx`'s own ink test makes for an ordinary chip — the star's
      // colour comes ONLY from the `.wf-maplab-chip-r` CSS rule, never an inline style, on a miss
      // exactly as on a match: the rule dims the whole `.wf-maplab-chip[data-tide='miss']` box via
      // CSS `opacity`, so nothing here re-colours the numeral.
      expect(star.style.color).toBe('');
      // The swatch half of §7 check 12 — "the swatch's background equals the ramp's for the same
      // rating" — not only the star's ink. A mutation that fell back to the unrated
      // `--color-plex-border-light` fill (or any other tide-keyed swatch colour) for a miss would
      // pass every OTHER assertion in this describe block while failing only this one.
      const square = chip.querySelector('.wf-maplab-chip-m');
      const probe = document.createElement('div');
      probe.style.background = rampHex(3);
      expect(square.style.background).toBe(probe.style.background);
    });

    it('renders no glyph and no data-tide attribute when there is no served tide fact at all (inland, or no stored extremes)', async () => {
      restoreMeasure = withMeasuredLabels(50, 14);
      currentMap = makeFullMap({ zoom: 13 });
      await mount({
        spots: [{
          name: 'Bamburgh', lat: 55.6, lng: -1.7, rid: 'North East', rating: 5, tideTier: null,
        }],
      });
      await act(async () => { runFrames(); });
      const chip = document.querySelector('[data-testid="map-label-chip"]');
      expect(chip).not.toHaveAttribute('data-tide');
      expect(chip.querySelector('[data-testid="map-label-chip-tide"]')).toBeNull();
      expect(chip).toHaveAttribute('aria-label', 'Bamburgh, 5 star');
    });

    it('extends an UNRATED chip\'s aria-label with the tide clause too — the two clauses are independent', async () => {
      restoreMeasure = withMeasuredLabels(50, 14);
      currentMap = makeFullMap({ zoom: 13 });
      await mount({
        spots: [{
          name: 'Alnmouth', lat: 55.4, lng: -1.6, rid: 'North East', rating: null, tideTier: 'match',
        }],
      });
      await act(async () => { runFrames(); });
      const chip = document.querySelector('[data-testid="map-label-chip"]');
      expect(chip).toHaveAttribute('aria-label', 'Alnmouth, tide right here');
    });

    it('a GATED miss (no rating at all) still draws the glyph, the dim attribute and the tide clause — the two branches are independent', async () => {
      // The combination §4 #5/§5 #2 single out: a gated coastal slot has no star for the chip to
      // draw (`hasRating` false) AND a served miss (`tideTier` true) — `MapLabels.jsx` branches on
      // each independently, so a coupling bug (e.g. the glyph/aria clause only appearing alongside
      // a star) would slip past every OTHER test in this file, which always pairs a miss with a
      // real rating.
      restoreMeasure = withMeasuredLabels(50, 14);
      currentMap = makeFullMap({ zoom: 13 });
      await mount({
        spots: [{
          name: 'Bamburgh',
          lat: 55.6,
          lng: -1.7,
          rid: 'North East',
          rating: null,
          tideTier: 'miss',
          tideShortfall: 'LOWER',
        }],
      });
      await act(async () => { runFrames(); });
      const chip = document.querySelector('[data-testid="map-label-chip"]');
      expect(chip.querySelector('.wf-maplab-chip-r')).toBeNull();
      expect(chip).toHaveAttribute('data-tide', 'miss');
      const glyph = chip.querySelector('[data-testid="map-label-chip-tide"]');
      expect(glyph).toHaveAttribute('data-wide', 'true');
      expect(chip).toHaveAttribute('aria-label', 'Bamburgh, wants the water lower');
    });
  });

  it('hover shows the tooltip with name, event, rating+verdict, and region · drive · sky Bortle', async () => {
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeFullMap({ zoom: 13 });
    await mount({ eventLabel: 'Sunset · Tonight 19:58' });
    await act(async () => { runFrames(); });
    const chip = [...document.querySelectorAll('[data-testid="map-label-chip"]')]
      .find((el) => el.textContent.includes('Bamburgh'));
    fireEvent.mouseEnter(chip);
    const tip = document.querySelector('[data-testid="map-label-tip"]');
    expect(tip).not.toBeNull();
    expect(tip).toHaveTextContent('Bamburgh');
    expect(tip).toHaveTextContent('Sunset · Tonight 19:58');
    expect(tip).toHaveTextContent('5★ Worth it');
    expect(tip).toHaveTextContent('North East');
    expect(tip).toHaveTextContent('1h 30min');
    expect(tip).toHaveTextContent('sky 4');
    fireEvent.mouseLeave(chip);
    expect(document.querySelector('[data-testid="map-label-tip"]')).toBeNull();
  });

  it('adds a third, teal-inked line — "Tide lands on the light — <phrase>" — for a MATCH', async () => {
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeFullMap({ zoom: 13 });
    await mount({
      spots: [{
        name: 'Bamburgh',
        lat: 55.6,
        lng: -1.7,
        rid: 'North East',
        rating: 5,
        tideTier: 'match',
        tideFitPhrase: 'high water, falling · HW 19:52 · 36m before sunset · 3.9 m',
      }],
    });
    await act(async () => { runFrames(); });
    const chip = document.querySelector('[data-testid="map-label-chip"]');
    fireEvent.mouseEnter(chip);
    const tideLine = document.querySelector('[data-testid="map-label-tip-tide"]');
    expect(tideLine).not.toBeNull();
    expect(tideLine).toHaveTextContent(
      'Tide lands on the light — high water, falling · HW 19:52 · 36m before sunset · 3.9 m',
    );
    expect(tideLine).toHaveClass('wf-maplab-tip-t');
  });

  it('adds the same third line for a MISS, headed "Wrong water, not wrong light" and with no teal ink', async () => {
    // ⚠️ No `.wf-maplab-tip-t` here: the served `fitPhrase` already opens with its own "wants …"
    // clause, so this line takes the tooltip's base `.wf-maplab-tip-s` ink rather than the
    // match-only teal — see `index.css`'s own note on `.wf-maplab-tip-t`.
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeFullMap({ zoom: 13 });
    await mount({
      spots: [{
        name: 'Bamburgh',
        lat: 55.6,
        lng: -1.7,
        rid: 'North East',
        rating: 5,
        tideTier: 'miss',
        tideFitPhrase: 'wants low water · mid tide, rising at 05:42 · 2.6 m of 4.3 m',
      }],
    });
    await act(async () => { runFrames(); });
    const chip = document.querySelector('[data-testid="map-label-chip"]');
    fireEvent.mouseEnter(chip);
    const tideLine = document.querySelector('[data-testid="map-label-tip-tide"]');
    expect(tideLine).not.toBeNull();
    expect(tideLine).toHaveTextContent(
      'Wrong water, not wrong light — wants low water · mid tide, rising at 05:42 · 2.6 m of 4.3 m',
    );
    expect(tideLine).not.toHaveClass('wf-maplab-tip-t');
  });

  it('adds no tide line when there is no served tide fact at all, even with a phrase carried', async () => {
    // Defensive: the gate is `tideTier`, never mere phrase presence.
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeFullMap({ zoom: 13 });
    await mount({
      spots: [{
        name: 'Bamburgh',
        lat: 55.6,
        lng: -1.7,
        rid: 'North East',
        rating: 5,
        tideTier: null,
        tideFitPhrase: 'wants low water · mid tide, rising at 05:42 · 2.6 m of 4.3 m',
      }],
    });
    await act(async () => { runFrames(); });
    const chip = document.querySelector('[data-testid="map-label-chip"]');
    fireEvent.mouseEnter(chip);
    expect(document.querySelector('[data-testid="map-label-tip-tide"]')).toBeNull();
  });

  it('omits the drive segment from the tooltip when this location has no measured drive time', async () => {
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeFullMap({ zoom: 13 });
    await mount();
    await act(async () => { runFrames(); });
    const chip = [...document.querySelectorAll('[data-testid="map-label-chip"]')]
      .find((el) => el.textContent.includes('Buttermere'));
    fireEvent.mouseEnter(chip);
    const tip = document.querySelector('[data-testid="map-label-tip"]');
    expect(tip.textContent).not.toMatch(/\bmin\b/);
    expect(tip).toHaveTextContent('sky 3');
  });

  it('portals the tooltip to the CHROME wrapper, never inside the label pane (map-tab-v2-plan.md §3 P8 review)', async () => {
    // `.leaflet-map-pane` carries a CSS transform (Leaflet's own panning mechanism), which
    // establishes a stacking context — a z-index declared on a descendant of it (like this
    // layer's own pane at 650) can never outrank real chrome outside that context regardless of
    // its own number. The tooltip's z1400 is only meaningful once it lives OUTSIDE the pane, in
    // the same wrapper `MapView` renders the chrome siblings into.
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeFullMap({ zoom: 13 });
    await mount();
    await act(async () => { runFrames(); });
    const chip = [...document.querySelectorAll('[data-testid="map-label-chip"]')]
      .find((el) => el.textContent.includes('Bamburgh'));
    fireEvent.mouseEnter(chip);
    const tip = document.querySelector('[data-testid="map-label-tip"]');
    expect(tip).not.toBeNull();
    const labelPane = currentMap.panes['wf-labels'];
    expect(labelPane.contains(tip)).toBe(false);
    expect(currentMap.wrap.contains(tip)).toBe(true);
  });

  it('clamps the tooltip horizontally against the frame edge, and never above y=6', async () => {
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeFullMap({ zoom: 13 });
    await mount();
    await act(async () => { runFrames(); });
    // `positionTip` measures `map.getContainer()`'s OWN rect (PR #733 review) — the label layer's
    // root has no width/height of its own (every child is absolutely positioned, so nothing
    // contributes to its box), and stubbing THAT element used to mask the exact bug this test now
    // exists to catch. The container is glued to the same origin, so its rect is the correct
    // (and, in a real browser, the only non-zero) source for this clamp.
    vi.spyOn(currentMap.container, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, width: 800, height: 500,
    });
    const chip = [...document.querySelectorAll('[data-testid="map-label-chip"]')]
      .find((el) => el.textContent.includes('Bamburgh'));
    // Hover right at the frame's own right edge — with no clamp, `clientX - left + 13` would push
    // the card's left edge past `width - tipWidth - 8`.
    fireEvent.mouseEnter(chip, { clientX: 795, clientY: 250 });
    const tip = document.querySelector('[data-testid="map-label-tip"]');
    // The node has not measured a real `offsetWidth` in jsdom (0), so the clamp falls back to
    // `TOOLTIP_WIDTH_FALLBACK` (240, the CSS `max-width`) — the same fallback a browser's very
    // first frame (before layout) would also need.
    expect(parseFloat(tip.style.left)).toBeCloseTo(800 - 240 - 8, 5);

    // And the vertical floor: hovering near the very top must never push the card above y=6.
    fireEvent.mouseEnter(chip, { clientX: 100, clientY: 2 });
    const tipAgain = document.querySelector('[data-testid="map-label-tip"]');
    expect(parseFloat(tipAgain.style.top)).toBe(6);
  });

  it('reads the CONTAINER rect for the clamp, never the zero-sized label-layer root (PR #733 review, a confirmed regression)', async () => {
    // Pins the clamp's INPUT SOURCE, not merely its output. The label layer's root
    // (`[data-testid="map-labels"]`) is deliberately left UNSTUBBED here — jsdom answers its
    // default zero-everywhere rect for it, exactly like a real browser would (that element has no
    // intrinsic size: `position: absolute; left: 0; top: 0` with every child also absolutely
    // positioned). Only the container gets a real width. If the implementation ever regresses to
    // reading the label layer's rect instead, `wrapRect.width` would be 0, the clamp would compute
    // `0 - tipWidth - 8` (a large negative number), and the assertion below — which requires the
    // tooltip to land INSIDE the container's real bounds — would fail.
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeFullMap({ zoom: 13 });
    vi.spyOn(currentMap.container, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, width: 800, height: 500,
    });
    await mount();
    await act(async () => { runFrames(); });
    const chip = [...document.querySelectorAll('[data-testid="map-label-chip"]')]
      .find((el) => el.textContent.includes('Bamburgh'));
    fireEvent.mouseEnter(chip, { clientX: 400, clientY: 250 });
    const tip = document.querySelector('[data-testid="map-label-tip"]');
    const left = parseFloat(tip.style.left);
    // A mid-frame hover with a real 800px-wide container: the raw offset (400 - 0 + 13 = 413) sits
    // nowhere near either clamp, so an unregressed implementation reports it UNCHANGED. The old,
    // zero-width bug would instead report `0 - 240 - 8 = -248` here — well outside the container
    // and impossible to confuse with the correct answer.
    expect(left).toBeCloseTo(413, 5);
    expect(left).toBeGreaterThanOrEqual(0);
    expect(left).toBeLessThanOrEqual(800);
  });
});

describe('MapLabels — the hover tooltip answers for the window on screen, not the one it opened on', () => {
  // The defect these pin: the chip's hover handler stored a SNAPSHOT of the spot object, and the
  // tooltip printed that snapshot's rating beside the LIVE `eventLabel`. A reader who rested the
  // pointer on a chip and stepped the window with the keyboard read the old window's star under
  // the new window's name — a chip that stays mounted under a still pointer gets no `mouseleave` —
  // and a chip that UNMOUNTED under the pointer left its tooltip hanging over nothing, since a
  // removed node gets no `mouseleave` either. So every case hovers once and then fires NO mouse
  // event at all: it only changes the props, as `MapView` does once a window step's figures are in
  // (a solar step's at once; an astro night's when its request lands). That silence is the
  // scenario, not an omission.

  const SAT = 'Saturday night';
  const SUN = 'Sunday night';

  /**
   * A map whose projection spreads its spots far enough apart that every chip is PLACED. The role
   * queries below only find a chip the placer put on screen (an unplaced one is `display: none`) —
   * the only kind of chip a reader can rest a pointer on.
   */
  function makeSpreadMap(opts = {}) {
    const map = makeFullMap({ zoom: 13, ...opts });
    map.latLngToContainerPoint = ([lat, lng]) => ({ x: (lng + 3.5) * 200, y: (56 - lat) * 200 });
    return map;
  }

  /**
   * `SPOTS` as `MapView` hands them over after a window step: the same places under the same names,
   * as FRESH objects carrying the new window's ratings (and no tide claim unless one is given).
   */
  const reRated = (ratings) => SPOTS.map((s) => ({ ...s, rating: ratings[s.name] ?? null }));

  /** Mounts on Saturday and rests the pointer on Bamburgh's chip — the one hover every case makes. */
  async function hoverBamburgh(spots = SPOTS) {
    restoreMeasure = withMeasuredLabels(50, 14);
    currentMap = makeSpreadMap();
    const result = await mount({ spots, eventLabel: SAT });
    await act(async () => { runFrames(); });
    const chip = screen.getByRole('button', { name: /^Bamburgh, 5 star/ });
    fireEvent.mouseEnter(chip);
    expect(screen.getByTestId('map-label-tip')).toHaveTextContent(`${SAT} · 5★ Worth it`);
    return { result, chip };
  }

  /** The props a window step changes — `rerender` replaces them wholesale, so both are passed. */
  async function showWindow(result, spots, eventLabel) {
    await act(async () => { result.rerender(<MapLabels spots={spots} eventLabel={eventLabel} />); });
  }

  it('reads the new window\'s star after a keyboard step, beside the new window\'s name', async () => {
    const { result, chip } = await hoverBamburgh();

    await showWindow(result, reRated({ Bamburgh: 3, Whitby: 3, Buttermere: 4 }), SUN);

    // The premise: the chip under the pointer is the SAME element (keyed by name), still placed —
    // a role query skips a `display: none` chip — so the pointer never left it and no `mouseleave`
    // is owed.
    expect(screen.getByRole('button', { name: 'Bamburgh, 3 star' })).toBe(chip);
    const tip = screen.getByTestId('map-label-tip');
    expect(tip).toHaveTextContent(`${SUN} · 3★ Maybe`);
    expect(tip).not.toHaveTextContent('5★');
    expect(tip).not.toHaveTextContent('Worth it');
  });

  it('carries no star at all when the new window has none for this place — never the old window\'s', async () => {
    const { result, chip } = await hoverBamburgh();

    await showWindow(result, reRated({ Bamburgh: null, Whitby: 3, Buttermere: 4 }), SUN);

    expect(screen.getByRole('button', { name: 'Bamburgh' })).toBe(chip);
    const tip = screen.getByTestId('map-label-tip');
    expect(tip).toHaveTextContent(SUN);
    expect(tip).not.toHaveTextContent('★');
  });

  it('drops the tide line when the new window has no served tide fact at all', async () => {
    const saturday = SPOTS.map((s) => (s.name === 'Bamburgh'
      ? { ...s, tideTier: 'match', tideFitPhrase: 'HW 19:52 · 36m before sunset' }
      : s));
    const { result } = await hoverBamburgh(saturday);
    expect(screen.getByTestId('map-label-tip-tide'))
      .toHaveTextContent('Tide lands on the light — HW 19:52 · 36m before sunset');

    // Every star held where it was, so the tide claim is the only thing this step moves.
    await showWindow(result, reRated({ Bamburgh: 5, Whitby: 3, Buttermere: 4 }), SUN);

    // The card itself is still up — without this, a tooltip that vanished outright would pass the
    // tide assertion below for the wrong reason.
    expect(screen.getByTestId('map-label-tip')).toHaveTextContent(`${SUN} · 5★ Worth it`);
    expect(screen.queryByTestId('map-label-tip-tide')).toBeNull();
  });

  it('closes when its location leaves the pool — the chip unmounts, and nothing else would close it', async () => {
    const { result, chip } = await hoverBamburgh();

    // e.g. the night's scores still loading, so the location drops out of the rated set.
    await showWindow(result, SPOTS.filter((s) => s.name !== 'Bamburgh'), SUN);

    expect(chip).not.toBeInTheDocument();
    expect(screen.queryByTestId('map-label-tip')).toBeNull();
  });

  it('does not reopen by itself when the location comes back — the pointer may have moved on since', async () => {
    const { result } = await hoverBamburgh();
    await showWindow(result, SPOTS.filter((s) => s.name !== 'Bamburgh'), SUN);
    expect(screen.queryByTestId('map-label-tip')).toBeNull();

    // The scores land and the chip returns. Only a fresh `mouseenter` — the browser's report that
    // the pointer really is on it — may reopen the card; a remembered hover would reopen it at a
    // position the reader left while the chip was gone, over a map nobody is pointing at.
    await showWindow(result, SPOTS, SUN);

    expect(screen.getByRole('button', { name: 'Bamburgh, 5 star' })).toBeInTheDocument();
    expect(screen.queryByTestId('map-label-tip')).toBeNull();
  });

  it('stays open through a repaint that keeps its chip', async () => {
    await hoverBamburgh();

    // A pan settling re-projects every label into a fresh frame and re-runs the placement pass.
    await act(async () => { currentMap.fire('moveend'); });

    expect(screen.getByTestId('map-label-tip')).toHaveTextContent(`${SAT} · 5★ Worth it`);
  });

  it('stays open through a fresh pool that still carries its location — it is found by name, not by object', async () => {
    const { result } = await hoverBamburgh();

    // Same window, same figures, new objects: what any `MapView` re-render that rebuilds
    // `labelSpots` hands over.
    await showWindow(result, SPOTS.map((s) => ({ ...s })), SAT);

    expect(screen.getByTestId('map-label-tip')).toHaveTextContent(`${SAT} · 5★ Worth it`);
  });

  describe('when the chip leaves the map while its location stays in the pool', () => {
    // `chipCandidates` draws the best place in each region plus the best `chipBudget(zoom)` in view,
    // ranked by rating — six at county zoom (8.6). So one region of seven places draws six chips,
    // and which six depends on the window's stars. Checking the pool alone is not enough here: the
    // place is still in it, carrying the new window's figures, while its chip is gone.
    const COUNTY = ['Craster', 'Seahouses', 'Alnmouth', 'Warkworth', 'Amble', 'Druridge', 'Cresswell']
      .map((name, i) => ({
        name, lat: 55.6, lng: -3.2 + i * 0.55, rid: 'North East', rating: 3,
      }));
    const rateCraster = (rating) => COUNTY.map((s) => (s.name === 'Craster' ? { ...s, rating } : s));

    it('closes when a window step re-ranks the chip out of the zoom budget', async () => {
      restoreMeasure = withMeasuredLabels(50, 14);
      currentMap = makeSpreadMap({ zoom: 8.6 });
      const result = await mount({ spots: rateCraster(5), eventLabel: SAT });
      await act(async () => { runFrames(); });
      const chip = screen.getByRole('button', { name: 'Craster, 5 star' });
      fireEvent.mouseEnter(chip);
      expect(screen.getByTestId('map-label-tip')).toHaveTextContent(`${SAT} · 5★ Worth it`);

      // Sunday: Craster falls to 1★ below six 3★ neighbours — neither the region's best nor one of
      // the six best in view, so it loses its chip while staying in the pool.
      await showWindow(result, rateCraster(1), SUN);

      expect(chip).not.toBeInTheDocument();
      expect(screen.queryByTestId('map-label-tip')).toBeNull();
    });

    it('does not reopen when a step back re-ranks the chip into the budget — the pointer may have moved on since', async () => {
      restoreMeasure = withMeasuredLabels(50, 14);
      currentMap = makeSpreadMap({ zoom: 8.6 });
      const result = await mount({ spots: rateCraster(5), eventLabel: SAT });
      await act(async () => { runFrames(); });
      fireEvent.mouseEnter(screen.getByRole('button', { name: 'Craster, 5 star' }));
      await showWindow(result, rateCraster(1), SUN);
      expect(screen.queryByTestId('map-label-tip')).toBeNull();

      // Back to Saturday: Craster is the region's best again and its chip returns. The place never
      // left the pool, so a hover that was only HIDDEN while the chip was gone would reopen here —
      // at a pointer position nothing has reported since the chip unmounted.
      await showWindow(result, rateCraster(5), SAT);

      expect(screen.getByRole('button', { name: 'Craster, 5 star' })).toBeInTheDocument();
      expect(screen.queryByTestId('map-label-tip')).toBeNull();
    });

    it('closes when a zoom-out shrinks the budget under a resting pointer', async () => {
      restoreMeasure = withMeasuredLabels(50, 14);
      // Street-level zoom: a budget of 54, so all seven places are chipped — Craster, at 1★, last.
      currentMap = makeSpreadMap({ zoom: 13 });
      await mount({ spots: rateCraster(1), eventLabel: SAT });
      await act(async () => { runFrames(); });
      const chip = screen.getByRole('button', { name: 'Craster, 1 star' });
      fireEvent.mouseEnter(chip);
      expect(screen.getByTestId('map-label-tip')).toHaveTextContent(`${SAT} · 1★ Poor`);

      // A wheel zoom over the chip reaches Leaflet (`disableClickPropagation` stops clicks, not the
      // wheel) and settles at county zoom, where only six of the seven are drawn.
      currentMap.zoom = 8.6;
      await act(async () => { currentMap.fire('zoomend'); });

      expect(chip).not.toBeInTheDocument();
      expect(screen.queryByTestId('map-label-tip')).toBeNull();
    });
  });
});

describe('MapLabels — obstacle seeding from the live chrome', () => {
  it('drops a label whose only nudge lands under a live chrome obstacle', async () => {
    restoreMeasure = withMeasuredLabels(30, 14);
    currentMap = makeFullMap({ zoom: 9 });
    // A chrome sibling the component queries for (`OBSTACLE_SELECTOR`), placed as a SIBLING of the
    // Leaflet container inside the SAME wrapper `MapView` renders both into.
    const chrome = document.createElement('div');
    chrome.setAttribute('data-testid', 'wf-map-chrome-tl');
    currentMap.wrap.appendChild(chrome);

    // The container itself sits at a stubbed (0,0) origin; cover the whole frame with the
    // "chrome" obstacle so every nudge for every candidate collides with it.
    vi.spyOn(currentMap.container, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, width: 800, height: 500,
    });
    vi.spyOn(chrome, 'getBoundingClientRect').mockReturnValue({
      left: -1000, top: -1000, width: 5000, height: 5000,
    });

    await mount({ homeCoords: HOME });
    await act(async () => { runFrames(); });
    // The home label had a candidate box and was measured (30x14, non-zero), but every rung — the
    // whole MAP_NUDGES × mapDxOffsets ladder — sits inside the obstacle's padded box, so it must
    // be DROPPED rather than rendered at some fallback position. Dropped is `display:none`, not
    // DOM absence (the element stays mounted so its ref survives to the next measure pass, the
    // same reasoning `WindowRowFieldMap`'s own placed/unplaced states follow) — assert the style,
    // not presence in the tree.
    const home = document.querySelector('[data-testid="map-label-home"]');
    expect(home).not.toBeNull();
    expect(home.style.display).toBe('none');
  });

  // map-tab-v2-plan.md §3 P8 review: the selector was missing four pieces of chrome that are LIVE
  // TODAY (P7), not merely a future phase's — each covered here individually so a regression on
  // any one piece fails its own case rather than a single combined test that could pass by
  // accident on the others.
  it.each([
    ['colour-scale-notice', 'colour-scale-notice'],
    ['the LITE viewline upsell chip', 'viewline-upsell-chip'],
    ['the scored-locations legend', 'photocast-scored-legend'],
    // map-tab-v2-plan.md §3 P11 — the Regions jump list's own open dropdown, added to
    // `OBSTACLE_SELECTOR` alongside `wf-win-menu`/`wf-filters-panel` for the identical reason: it
    // overflows its trigger chip's own layout box, so `wf-map-chrome-tr`'s rect does not cover it.
    ['the Regions jump menu', 'wf-jump-menu'],
    // map-landing-plan.md §3 L4/L5/L6 — the landing card and BOTH drilldown levels. ⚠️ Neither
    // panel was in this list's coverage when it was added; a review lens measured that deleting
    // both entries from `MapLabels` and `PinsLayer` left every one of their specs green, so the
    // placer would happily put a chip under a several-hundred-pixel panel with nothing failing.
    ['the landing card', 'wf-land'],
    ['the window panel', 'wf-win-panel'],
    ['the region panel', 'wf-reg-panel'],
  ])('seeds %s as an obstacle', async (_label, testid) => {
    restoreMeasure = withMeasuredLabels(30, 14);
    currentMap = makeFullMap({ zoom: 9 });
    const chrome = document.createElement('div');
    chrome.setAttribute('data-testid', testid);
    currentMap.wrap.appendChild(chrome);
    vi.spyOn(currentMap.container, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, width: 800, height: 500,
    });
    vi.spyOn(chrome, 'getBoundingClientRect').mockReturnValue({
      left: -1000, top: -1000, width: 5000, height: 5000,
    });
    await mount({ homeCoords: HOME });
    await act(async () => { runFrames(); });
    const home = document.querySelector('[data-testid="map-label-home"]');
    expect(home.style.display).toBe('none');
  });

  it('seeds Leaflet\'s OWN bottom-right corner (zoom + ⌂) — a CHILD of the container, not a sibling', async () => {
    restoreMeasure = withMeasuredLabels(30, 14);
    currentMap = makeFullMap({ zoom: 9 });
    // Unlike the React-rendered chrome above, Leaflet's own zoom control and `CentreOnHomeControl`
    // are appended INSIDE `map.getContainer()`, both sharing one `.leaflet-bottom.leaflet-right`
    // corner div — `OBSTACLE_SELECTOR` (queried from the container's PARENT) cannot reach this;
    // it is queried from the container itself, separately.
    const corner = document.createElement('div');
    corner.className = 'leaflet-bottom leaflet-right';
    currentMap.container.appendChild(corner);
    vi.spyOn(currentMap.container, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, width: 800, height: 500,
    });
    vi.spyOn(corner, 'getBoundingClientRect').mockReturnValue({
      left: -1000, top: -1000, width: 5000, height: 5000,
    });
    await mount({ homeCoords: HOME });
    await act(async () => { runFrames(); });
    const home = document.querySelector('[data-testid="map-label-home"]');
    expect(home.style.display).toBe('none');
  });
});

describe('MapLabels — re-paints on the same rAF-guarded cadence as the field', () => {
  // Spies on `latLngToContainerPoint` — called once per paint (for the home point) — rather than
  // inspecting rendered DOM: React reconciles by KEY, so a repaint that produces an
  // unchanged-looking tree reuses the existing nodes, and a manual `innerHTML` probe would be
  // fooled by that reuse (it can only ever detect a subtree REPLACEMENT, not a genuine re-render
  // that leaves familiar-looking output). Counting the underlying projection call is what
  // `MapHeatLayer.test.jsx` does too, via its own spied `drawTiles`.
  it('coalesces a burst of move/zoom events into one paint, on the next frame', async () => {
    restoreMeasure = withMeasuredLabels(30, 14);
    currentMap = makeFullMap({ zoom: 9 });
    const projectSpy = vi.spyOn(currentMap, 'latLngToContainerPoint');
    // No spots and no home: the ONLY thing left for `paint()` to call the projection for is
    // nothing at all, so a bare per-paint invocation count would be zero either way — home alone
    // gives exactly one call per paint, an unambiguous signal.
    await mount({ spots: [], homeCoords: HOME });
    await act(async () => { runFrames(); });
    projectSpy.mockClear();

    await act(async () => {
      currentMap.fire('move');
      currentMap.fire('move');
      currentMap.fire('zoom');
    });
    // Nothing repainted yet — the first event scheduled a frame and the other two were dropped.
    expect(projectSpy).not.toHaveBeenCalled();
    await act(async () => { runFrames(); });
    expect(projectSpy).toHaveBeenCalledTimes(1);
  });

  it('paints immediately on moveend/zoomend, in the calling tick', async () => {
    restoreMeasure = withMeasuredLabels(30, 14);
    currentMap = makeFullMap({ zoom: 9 });
    const projectSpy = vi.spyOn(currentMap, 'latLngToContainerPoint');
    await mount({ spots: [], homeCoords: HOME });
    await act(async () => { runFrames(); });
    projectSpy.mockClear();
    await act(async () => { currentMap.fire('moveend'); });
    expect(projectSpy).toHaveBeenCalledTimes(1);
  });

  it('stops listening on unmount', async () => {
    restoreMeasure = withMeasuredLabels(30, 14);
    currentMap = makeFullMap({ zoom: 9 });
    const { unmount } = await mount({ homeCoords: HOME });
    await act(async () => { runFrames(); });
    await act(async () => { unmount(); });
    // No throw on a post-unmount event is the assertion — the listeners were removed.
    expect(() => currentMap.fire('moveend')).not.toThrow();
  });
});

