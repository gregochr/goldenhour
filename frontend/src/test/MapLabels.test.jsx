import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, fireEvent, render } from '@testing-library/react';

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

beforeEach(() => {
  frames = [];
  global.requestAnimationFrame = (cb) => { frames.push(cb); return frames.length; };
  global.cancelAnimationFrame = (id) => { frames[id - 1] = null; };
  // The chip square's fill is the LIVE `scoreRamp` mode (`MapHeatLayer.test.jsx`'s own
  // precedent) — the module's own bootstrap default is 'verdict', so a test wanting the
  // temperature scale's hex values must ask for it explicitly.
  setMode('temp');
});

afterEach(() => {
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
    // `markerPane` sits at its own built-in 600 and this app fades its markers back to full
    // opacity and interactivity past the zoom handover, so labels must clear it or a chip renders
    // (and hit-tests) underneath the very marker it names.
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
