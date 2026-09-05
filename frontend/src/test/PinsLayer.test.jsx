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

import PinsLayer from '../components/map/PinsLayer.jsx';
import { rampHex, setMode } from '../utils/scoreRamp.js';
import { readableInkOn } from '../utils/windowFirstSpots.js';
import { NO_DATA_COLOUR, STAND_DOWN_COLOUR } from '../components/markerUtils.js';

/**
 * Pins mode's HTML layer (map-tab-v2-plan.md §3 P10, `docs/design/map-tab-v2/README.md` §3) — the
 * WIRING suite, the same split `MapLabels.test.jsx` draws between its own React/Leaflet host and
 * `mapLabels.test.js`'s pure placement arithmetic: `homeLabelItems`/`placeLabelPass` are reused
 * verbatim here (their own tests already cover the maths), so this file proves the host projects,
 * sorts, renders and wires clicks/hover correctly — not that the shared placement functions work.
 *
 * <p>Modelled closely on `MapLabels.test.jsx`'s own map stub and `withMeasuredLabels` idiom — the
 * two layers are pane-hosted siblings that never mount together, so sharing one test shape keeps a
 * reviewer's mental model of "how do these Leaflet-pane hosts get tested" in one place.
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

function makeMap({
  zoom = 9, size = { x: 800, y: 500 }, onScreen = true,
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
  // The pin fill is the LIVE `scoreRamp` mode (`MapLabels.test.jsx`'s own precedent) — the module's
  // bootstrap default is 'verdict', so a test wanting the temperature scale's hex values must ask
  // for it explicitly.
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
    result = render(<PinsLayer spots={SPOTS} {...props} />);
  });
  return result;
}

describe('PinsLayer — mounting', () => {
  it('renders nothing when the map cannot make a pane', async () => {
    currentMap = makeMap();
    const { container } = await mount();
    expect(container.querySelector('[data-testid="pins-layer"]')).toBeNull();
  });

  it('creates its own pane at z-index 650, pointer-events none', async () => {
    currentMap = makeFullMap();
    await mount();
    const pane = currentMap.panes['wf-pins'];
    expect(pane).toBeTruthy();
    expect(pane.style.zIndex).toBe('650');
    expect(pane.style.pointerEvents).toBe('none');
  });

  it('renders nothing at all while the shell has the pane hidden', async () => {
    currentMap = makeFullMap({ onScreen: false });
    await mount();
    const pane = currentMap.panes['wf-pins'];
    expect(pane.querySelector('[data-testid="pins-layer"]')).toBeNull();
  });
});

describe('PinsLayer — one dot per location, no density ramp', () => {
  it('renders exactly one pin per spot handed to it, regardless of how many — the "honest comparison" has no budget to run out of', async () => {
    currentMap = makeFullMap({ zoom: 9 });
    const many = Array.from({ length: 40 }, (_, i) => ({
      name: `Spot ${i}`, lat: 55, lng: -2 + i * 0.01, rid: 'X', rating: (i % 5) + 1,
    }));
    await mount({ spots: many });
    expect(document.querySelectorAll('[data-testid="map-pin"]').length).toBe(40);
  });

  it('renders every spot even at a zoom where MapLabels\' own chip budget would have started dropping names', async () => {
    // z8.6 is `mapLabels.js`'s own budget FLOOR (6 candidates) — proof this layer takes no budget
    // input at all, unlike the chip layer it replaces.
    currentMap = makeFullMap({ zoom: 8.6 });
    const many = Array.from({ length: 25 }, (_, i) => ({
      name: `Spot ${i}`, lat: 55, lng: -2 + i * 0.01, rid: 'X', rating: 3,
    }));
    await mount({ spots: many });
    expect(document.querySelectorAll('[data-testid="map-pin"]').length).toBe(25);
  });
});

describe('PinsLayer — paint order (weakest first, so the best sit on top)', () => {
  it('renders pins in ASCENDING rating order — the strongest is LAST in the DOM and so paints over the rest', async () => {
    currentMap = makeFullMap({ zoom: 9 });
    await mount();
    const names = [...document.querySelectorAll('[data-testid="map-pin"]')]
      .map((el) => el.getAttribute('aria-label'));
    // Whitby (3★) < Buttermere (4★) < Bamburgh (5★).
    expect(names).toEqual([
      'Whitby, 3 star',
      'Buttermere, 4 star',
      'Bamburgh, 5 star',
    ]);
  });

  it('sorts a missing/non-finite rating as the WEAKEST value — an unrated pin never sits on top of a rated one', async () => {
    currentMap = makeFullMap({ zoom: 9 });
    await mount({
      spots: [
        { name: 'Rated Low', lat: 55, lng: -2, rid: 'X', rating: 1 },
        { name: 'Unrated', lat: 55, lng: -2, rid: 'X', rating: null },
      ],
    });
    const names = [...document.querySelectorAll('[data-testid="map-pin"]')]
      .map((el) => el.getAttribute('aria-label'));
    expect(names).toEqual(['Unrated', 'Rated Low, 1 star']);
  });
});

describe('PinsLayer — fill, ink and size', () => {
  it('fills a rated pin from rampHex and picks its ink via readableInkOn — never a fixed pair', async () => {
    currentMap = makeFullMap({ zoom: 9 });
    await mount();
    const pin = [...document.querySelectorAll('[data-testid="map-pin"]')]
      .find((el) => el.getAttribute('aria-label') === 'Bamburgh, 5 star');
    expect(pin).toBeTruthy();
    const expectedFill = rampHex(5);
    const expectedInk = readableInkOn(expectedFill);
    const probe = document.createElement('div');
    probe.style.background = expectedFill;
    probe.style.color = expectedInk;
    expect(pin.style.background).toBe(probe.style.background);
    expect(pin.style.color).toBe(probe.style.color);
  });

  it('carries the rating and a star glyph inside a named, rated pin', async () => {
    currentMap = makeFullMap({ zoom: 9 });
    await mount();
    const pin = [...document.querySelectorAll('[data-testid="map-pin"]')]
      .find((el) => el.getAttribute('aria-label') === 'Bamburgh, 5 star');
    expect(pin).toHaveTextContent('5');
    expect(pin.querySelector('.wf-pin-star')).not.toBeNull();
  });

  it('a plain unrated pin paints the app\'s shared no-data grey, not the ramp\'s bottom (1★) stop, and carries no star text', async () => {
    // `utils/scoreRamp.js`'s own doc comment: "Stand-down and no-data markers are not scores and
    // are not on this ramp at all" — `rampHex` would happily answer a colour for a null rating (it
    // clamps), which would read as a 1★ claim this pin has no evidence for.
    currentMap = makeFullMap({ zoom: 9 });
    await mount({
      spots: [{
        name: 'Unrated', lat: 55, lng: -2, rid: 'X', rating: null, isStandDown: false,
      }],
    });
    const pin = document.querySelector('[data-testid="map-pin"]');
    const probe = document.createElement('div');
    probe.style.background = NO_DATA_COLOUR;
    expect(pin.style.background).toBe(probe.style.background);
    expect(pin.textContent).toBe('');
    expect(pin).toHaveAttribute('aria-label', 'Unrated');
    expect(pin).not.toHaveAttribute('data-stand-down');
  });

  it('a STAND-DOWN pin paints the medallions\' own dark red, never the plain no-data grey (adversarial review C8)', async () => {
    // A triaged location is a decision the pipeline made about tonight, not "nothing scored yet" —
    // `resolveStandDown`/`STAND_DOWN_COLOUR` is the same distinction the medallion markers already
    // draw. Collapsing the two into one grey on the tab's own "honest comparison" view would lose
    // exactly the information that view exists to show.
    currentMap = makeFullMap({ zoom: 9 });
    await mount({
      spots: [{
        name: 'Triaged', lat: 55, lng: -2, rid: 'X', rating: null, isStandDown: true,
      }],
    });
    const pin = document.querySelector('[data-testid="map-pin"]');
    const probe = document.createElement('div');
    probe.style.background = STAND_DOWN_COLOUR;
    expect(pin.style.background).toBe(probe.style.background);
    expect(pin.textContent).toBe('');
    expect(pin).toHaveAttribute('data-stand-down', 'true');
  });

  it('sizes a NAMED pin at 26px', async () => {
    currentMap = makeFullMap({ zoom: 9 });
    await mount();
    const pin = [...document.querySelectorAll('[data-testid="map-pin"]')]
      .find((el) => el.getAttribute('aria-label') === 'Bamburgh, 5 star');
    expect(pin).toHaveAttribute('data-named', 'true');
    expect(pin.style.width).toBe('26px');
    expect(pin.style.height).toBe('26px');
  });

  it('sizes an UNNAMED pin (named: false) at 13px — dormant today, but not silently full-size if the catalogue ever grows one', async () => {
    currentMap = makeFullMap({ zoom: 9 });
    await mount({
      spots: [{
        name: 'Unnamed row', lat: 55, lng: -2, rid: 'X', rating: 3, named: false,
      }],
    });
    const pin = document.querySelector('[data-testid="map-pin"]');
    expect(pin).toHaveAttribute('data-named', 'false');
    expect(pin.style.width).toBe('13px');
    expect(pin.style.height).toBe('13px');
    // README §3: unnamed pins carry no rating text either, only named ones do.
    expect(pin.textContent).toBe('');
  });
});

describe('PinsLayer — selection: click and the selected marker', () => {
  it('clicking a pin calls onSelect with the location name — the SAME handler MapLabels\' chips call', async () => {
    currentMap = makeFullMap({ zoom: 9 });
    const onSelect = vi.fn();
    await mount({ onSelect });
    const pin = [...document.querySelectorAll('[data-testid="map-pin"]')]
      .find((el) => el.getAttribute('aria-label') === 'Bamburgh, 5 star');
    fireEvent.click(pin);
    expect(onSelect).toHaveBeenCalledWith('Bamburgh');
  });

  it('the selected location carries data-selected', async () => {
    currentMap = makeFullMap({ zoom: 9 });
    await mount({ selectedName: 'Buttermere' });
    const pin = [...document.querySelectorAll('[data-testid="map-pin"]')]
      .find((el) => el.getAttribute('aria-label') === 'Buttermere, 4 star');
    expect(pin).toHaveAttribute('data-selected', 'true');
  });
});

describe('PinsLayer — hover tooltip parity with the P8 chip', () => {
  it('shows name, event, rating+verdict, and region · drive · sky Bortle — reusing MapLabels\' own tooltip classes', async () => {
    currentMap = makeFullMap({ zoom: 9 });
    await mount({ eventLabel: 'Sunset · Tonight 19:58' });
    const pin = [...document.querySelectorAll('[data-testid="map-pin"]')]
      .find((el) => el.getAttribute('aria-label') === 'Bamburgh, 5 star');
    fireEvent.mouseEnter(pin);
    const tip = document.querySelector('[data-testid="map-label-tip"]');
    expect(tip).not.toBeNull();
    expect(tip.className).toBe('wf-maplab-tip');
    expect(tip).toHaveTextContent('Bamburgh');
    expect(tip).toHaveTextContent('Sunset · Tonight 19:58');
    expect(tip).toHaveTextContent('5★ Worth it');
    expect(tip).toHaveTextContent('North East');
    expect(tip).toHaveTextContent('1h 30min');
    expect(tip).toHaveTextContent('sky 4');
    fireEvent.mouseLeave(pin);
    expect(document.querySelector('[data-testid="map-label-tip"]')).toBeNull();
  });

  it('portals the tooltip to the chrome wrapper, never inside the pins pane', async () => {
    currentMap = makeFullMap({ zoom: 9 });
    await mount();
    const pin = [...document.querySelectorAll('[data-testid="map-pin"]')]
      .find((el) => el.getAttribute('aria-label') === 'Bamburgh, 5 star');
    fireEvent.mouseEnter(pin);
    const tip = document.querySelector('[data-testid="map-label-tip"]');
    expect(tip).not.toBeNull();
    const pinsPane = currentMap.panes['wf-pins'];
    expect(pinsPane.contains(tip)).toBe(false);
    expect(currentMap.wrap.contains(tip)).toBe(true);
  });
});

describe('PinsLayer — the home marker', () => {
  it('renders below the zoom gate (13), positioned at the home point, exactly like MapLabels\' own home item', async () => {
    restoreMeasure = withMeasuredLabels(30, 14);
    currentMap = makeFullMap({ zoom: 9 });
    await mount({ homeCoords: HOME });
    await act(async () => { runFrames(); });
    const home = document.querySelector('[data-testid="map-label-home"]');
    expect(home).not.toBeNull();
    expect(home).toHaveTextContent('HOME');
    const anchor = currentMap.latLngToContainerPoint([HOME.lat, HOME.lon]);
    expect(home.style.left).toBe(`${anchor.x - 15}px`);
    expect(home.style.top).toBe(`${anchor.y - 7}px`);
  });

  it('is absent at/above the zoom gate, or with no home coordinates', async () => {
    restoreMeasure = withMeasuredLabels(30, 14);
    currentMap = makeFullMap({ zoom: 13 });
    await mount({ homeCoords: HOME });
    await act(async () => { runFrames(); });
    expect(document.querySelector('[data-testid="map-label-home"]')).toBeNull();

    currentMap = makeFullMap({ zoom: 9 });
    await mount();
    await act(async () => { runFrames(); });
    expect(document.querySelector('[data-testid="map-label-home"]')).toBeNull();
  });

  // map-tab-v2-plan.md §3 P10's own brief: the design bundle's `drawPins()` places its home label
  // against an EMPTY obstacle list (a prototype shortcut, not a design) — this host uses the FULL
  // live-chrome obstacle list instead, the same one `MapLabels.jsx` seeds its own placement with.
  it('drops the home label under a live chrome obstacle — the full obstacle list reaches Pins mode too', async () => {
    restoreMeasure = withMeasuredLabels(30, 14);
    currentMap = makeFullMap({ zoom: 9 });
    const chrome = document.createElement('div');
    chrome.setAttribute('data-testid', 'wf-map-chrome-tl');
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
    expect(home).not.toBeNull();
    expect(home.style.display).toBe('none');
  });

  it('seeds the Legend chip/panel as obstacles too (P10\'s own chrome)', async () => {
    restoreMeasure = withMeasuredLabels(30, 14);
    currentMap = makeFullMap({ zoom: 9 });
    const chrome = document.createElement('div');
    chrome.setAttribute('data-testid', 'wf-map-chrome-bl');
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

  // map-tab-v2-plan.md §3 P11 — parity with `MapLabels.test.jsx`'s own case: the Regions jump
  // list's open dropdown is duplicated onto THIS file's own `OBSTACLE_SELECTOR` too (the two lists
  // never mount together, so the entry is repeated rather than imported — see that constant's own
  // class doc), and needs its own obstacle test for the identical reason `wf-win-menu`/
  // `wf-filters-panel` do: it overflows its trigger chip's own layout box.
  it('seeds the Regions jump menu as an obstacle too (P11)', async () => {
    restoreMeasure = withMeasuredLabels(30, 14);
    currentMap = makeFullMap({ zoom: 9 });
    const chrome = document.createElement('div');
    chrome.setAttribute('data-testid', 'wf-jump-menu');
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

describe('PinsLayer — re-paints on the same rAF-guarded cadence as the field', () => {
  it('re-projects on moveend/zoomend immediately, and coalesces move/zoom through one frame', async () => {
    currentMap = makeFullMap({ zoom: 9 });
    await mount();
    const before = [...document.querySelectorAll('[data-testid="map-pin"]')].map((el) => el.style.left);
    currentMap.latLngToContainerPoint = ([lat, lng]) => ({ x: (lng + 30) * 10, y: (56 - lat) * 10 });
    await act(async () => { currentMap.fire('moveend'); });
    const after = [...document.querySelectorAll('[data-testid="map-pin"]')].map((el) => el.style.left);
    expect(after).not.toEqual(before);
  });
});
