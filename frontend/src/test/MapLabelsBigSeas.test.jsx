import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen,
} from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const setPosition = vi.fn();
vi.mock('leaflet', () => {
  const DomUtil = { setPosition: (...args) => setPosition(...args) };
  return { default: { DomUtil }, DomUtil };
});

let currentMap = null;
vi.mock('react-leaflet', () => ({ useMap: () => currentMap }));

import MapLabels from '../components/map/MapLabels.jsx';
import { bigSeasChipItems } from '../utils/mapLabels.js';
import { SATURDAY } from './bigSeasFixtures.js';

/**
 * The big-seas chips in the Heat label layer (big-seas plan F2): render, accessible name, tooltip,
 * selection, the Ask fade, suppression of the ordinary chip, the collision tier and the focus
 * target `MapView.closeCallout` looks for. Split from `MapLabels.test.jsx` as `MapLabelsAsk.test.jsx`
 * is, with the same jsdom scaffolding: jsdom lays nothing out, so the boxes are stubbed.
 *
 * <p>The chip list itself (the join, the zoom and solar gates, the ring) is `mapLabels.test.js`'s job;
 * the rule that withholds a selected or Ask-picked place's chip is `MapView`'s, covered in
 * `MapViewBigSeasChips.test.jsx`. Saturday 24 Oct 2026, still BST: 14:30Z is 15:30 on the UK clock.
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

/** A Leaflet map stubbed to what the layer touches, with panes. */
function makeFullMap({ zoom = 9, size = { x: 800, y: 500 } } = {}) {
  const handlers = new Map();
  const wrap = document.createElement('div');
  const container = document.createElement('div');
  wrap.appendChild(container);
  document.body.appendChild(wrap);
  Object.defineProperty(container, 'offsetWidth', { value: size.x, configurable: true });
  const panes = {};
  return {
    zoom,
    getZoom() { return this.zoom; },
    getSize: () => size,
    getContainer: () => container,
    getCenter: () => ({ lat: 55, lng: -2 }),
    getBounds: () => ({ contains: () => true }),
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 10, y: (56 - lat) * 10 }),
    containerPointToLayerPoint: (p) => ({ x: p[0], y: p[1] }),
    on(events, fn) { for (const e of events.split(' ')) handlers.set(e, [...(handlers.get(e) || []), fn]); },
    off(events, fn) {
      for (const e of events.split(' ')) {
        handlers.set(e, (handlers.get(e) || []).filter((h) => h !== fn));
      }
    },
    getPane: (name) => panes[name] || null,
    createPane: (name) => {
      const el = document.createElement('div');
      panes[name] = el;
      container.appendChild(el);
      return el;
    },
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

let frames = [];
let restoreMeasure;
let originalRaf;
let originalCancel;

beforeEach(() => {
  frames = [];
  originalRaf = global.requestAnimationFrame;
  originalCancel = global.cancelAnimationFrame;
  global.requestAnimationFrame = (cb) => { frames.push(cb); return frames.length; };
  global.cancelAnimationFrame = (id) => { frames[id - 1] = null; };
});

afterEach(() => {
  global.requestAnimationFrame = originalRaf;
  global.cancelAnimationFrame = originalCancel;
  currentMap = null;
  if (restoreMeasure) { restoreMeasure(); restoreMeasure = null; }
  document.body.innerHTML = '';
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

const MOUSE = { pointerType: 'mouse' };
const TOUCH = { pointerType: 'touch' };

describe('MapLabels — big-seas chips', () => {
  // None of these places is in the label pool (`spots`): the chip comes from the roster, which is the
  // point, since the storm-day places are the stood-down ones the pool drops.
  const ROSTER = new Map([
    [243, { name: 'Roker Pier & Lighthouse', lat: 54.92, lon: -1.36, regionName: 'North East', driveMinutes: 45 }],
    [28, { name: "St. Mary's Lighthouse", lat: 55.07, lon: -1.44, regionName: 'North East', driveMinutes: 70 }],
    [21, { name: 'Amble', lat: 55.33, lon: -1.58, regionName: 'North East', driveMinutes: null }],
  ]);
  const SUNSET_ROW = { kind: 'solar', eventType: 'SUNSET', date: SATURDAY.date };
  const SUNRISE_ROW = { kind: 'solar', eventType: 'SUNRISE', date: SATURDAY.date };
  const chipsFor = (row = SUNSET_ROW) => bigSeasChipItems(SATURDAY, ROSTER, 9, row);

  /** A map whose projection spreads the three structures well apart inside the 800x500 frame. */
  function spreadMap() {
    const map = makeFullMap();
    map.latLngToContainerPoint = ([lat, lng]) => ({ x: 200 + (lng + 1.5) * 300, y: 250 - (lat - 55) * 300 });
    return map;
  }

  async function mountSeas(props = {}) {
    restoreMeasure = withMeasuredLabels(70, 17);
    currentMap = spreadMap();
    const result = await mount({ bigSeasChips: chipsFor(), ...props });
    await act(async () => { runFrames(); });
    return result;
  }

  const seasChips = () => [...document.querySelectorAll('[data-testid="map-label-chip"][data-kind="big-seas"]')];

  it('draws one chip per served structure, in the label layer, with the name and the height', async () => {
    await mountSeas();
    const chips = seasChips();
    expect(chips.map((c) => c.textContent)).toEqual(['Roker Pier4.3 m', "St Mary's4.1 m", 'Amble piers3.9 m']);
    expect(chips.every((c) => c.closest('[data-testid="map-labels"]'))).toBe(true);
    // Placed: none left in the off-screen measuring spot, none dropped.
    expect(chips.every((c) => c.style.left !== '-9999px' && c.style.display !== 'none')).toBe(true);
    expect(chips[0].querySelector('[data-testid="wf-bolt"]')).toHaveAttribute('aria-hidden', 'true');
  });

  it('is ringed only on the aligned event', async () => {
    await mountSeas();
    expect(seasChips().every((c) => c.dataset.aligned === 'true')).toBe(true);
    cleanup();
    await mountSeas({ bigSeasChips: chipsFor(SUNRISE_ROW) });
    expect(seasChips()).toHaveLength(3);
    expect(seasChips().every((c) => c.dataset.aligned === undefined)).toBe(true);
  });

  it('carries everything in its accessible name, and the ring\'s meaning only when ringed', async () => {
    await mountSeas();
    expect(seasChips()[0]).toHaveAttribute(
      'aria-label',
      'Roker Pier, big seas, waves 4.3 metres, 15:30 to 18:15, high water on the sunset',
    );
    cleanup();
    await mountSeas({ bigSeasChips: chipsFor(SUNRISE_ROW) });
    expect(seasChips()[0]).toHaveAttribute('aria-label', 'Roker Pier, big seas, waves 4.3 metres, 15:30 to 18:15');
  });

  it('shows the handoff tooltip lines on a real mouse hover, and none on a touch', async () => {
    await mountSeas();
    const chip = seasChips()[0];
    fireEvent.pointerEnter(chip, TOUCH);
    expect(screen.queryByTestId('map-label-tip')).toBeNull();
    fireEvent.pointerEnter(chip, MOUSE);
    const tip = screen.getByTestId('map-label-tip');
    expect(tip.className).toContain('wf-maplab-tip');
    expect(Array.from(tip.children).map((el) => el.textContent)).toEqual([
      'Roker Pier & Lighthouse',
      'Big seas · 15:30–18:15',
      'High water 16:58 · 49 min before sunset',
      'Waves 4.3 m from NNE, 11 s · faces NE, onshore',
      'North East · 45 min',
    ]);
    fireEvent.pointerLeave(chip, MOUSE);
    expect(screen.queryByTestId('map-label-tip')).toBeNull();
  });

  it('leaves the high-water line out of the tooltip when not ringed, and the drive when unmeasured', async () => {
    await mountSeas({ bigSeasChips: chipsFor(SUNRISE_ROW) });
    fireEvent.pointerEnter(seasChips()[2], MOUSE);
    const lines = Array.from(screen.getByTestId('map-label-tip').children).map((el) => el.textContent);
    expect(lines).toEqual([
      'Amble',
      'Big seas · 15:30–18:15',
      'Waves 3.9 m from NNE, 10 s · faces NE, onshore',
      'North East',
    ]);
  });

  it('a press selects the place by its location name, like an ordinary chip', async () => {
    const onSelect = vi.fn();
    await mountSeas({ onSelect });
    fireEvent.click(seasChips()[1]);
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith("St. Mary's Lighthouse");
  });

  it('steps back (data-ask="fade") while Ask\'s picks are on the map, and not otherwise', async () => {
    await mountSeas();
    expect(seasChips().every((c) => c.dataset.ask === undefined)).toBe(true);
    cleanup();
    await mountSeas({
      spots: [{
        ...SPOTS[0], askPick: { rank: 1, shortWindow: 'Sat 17:47', rating: 4, verdict: 'MAYBE', label: 'Pick 1' },
      }],
    });
    expect(seasChips()).toHaveLength(3);
    expect(seasChips().every((c) => c.dataset.ask === 'fade')).toBe(true);
  });

  it('draws no ordinary chip for a place a big-seas chip stands for, and keeps the rest', async () => {
    await mountSeas({
      spots: [
        ...SPOTS,
        { name: 'Roker Pier & Lighthouse', lat: 54.92, lng: -1.36, rid: 'North East', rating: 2 },
      ],
    });
    const ordinary = [...document.querySelectorAll('[data-testid="map-label-chip"]:not([data-kind])')];
    expect(ordinary.map((c) => c.getAttribute('aria-label')).sort()).toEqual([
      'Bamburgh, 5 star', 'Buttermere, 4 star', 'Whitby, 3 star',
    ]);
  });

  it('keeps the ordinary chip of a place whose big-seas chip was withheld (the selection, an Ask pick)', async () => {
    // `MapView` withholds those chips from the list; the layer then draws the place as ever.
    await mountSeas({
      bigSeasChips: chipsFor().filter((c) => c.name !== 'Roker Pier & Lighthouse'),
      spots: [
        ...SPOTS,
        { name: 'Roker Pier & Lighthouse', lat: 54.92, lng: -1.36, rid: 'North East', rating: 2 },
      ],
      selectedName: 'Roker Pier & Lighthouse',
    });
    expect(seasChips()).toHaveLength(2);
    const selected = document.querySelector('[data-testid="map-label-chip"][data-selected="true"]');
    expect(selected.getAttribute('aria-label')).toBe('Roker Pier & Lighthouse, 2 star');
  });

  /** A frame with room for exactly one 300px box: every candidate is anchored on its one point. */
  async function mountContested(zoom, props) {
    restoreMeasure = withMeasuredLabels(300, 17);
    currentMap = makeFullMap({ zoom, size: { x: 320, y: 24 } });
    currentMap.latLngToContainerPoint = () => ({ x: 160, y: 12 });
    await mount(props);
    await act(async () => { runFrames(); });
  }
  const here = { lat: 55, lng: -1.5 };
  const contestedSeas = () => [{ ...chipsFor()[0], ...here }];

  it('a region name keeps its space over a big-seas chip, which keeps it over an ordinary chip', async () => {
    await mountContested(9, {
      spots: [{ name: 'Ordinary', rid: 'Elsewhere', rating: 3, ...here }],
      bigSeasChips: contestedSeas(),
    });
    expect(document.querySelector('[data-testid="map-label-region"]').style.display).not.toBe('none');
    expect(seasChips()[0].style.display).toBe('none');
    expect(document.querySelector('[data-testid="map-label-chip"]:not([data-kind])').style.display).toBe('none');
  });

  it('a big-seas chip keeps its space over an ordinary chip', async () => {
    // Zoom 11.5 is past the region names' gate, so only the two chips contest the ground.
    await mountContested(11.5, {
      spots: [{ name: 'Ordinary', rid: 'Elsewhere', rating: 3, ...here }],
      bigSeasChips: contestedSeas(),
    });
    expect(seasChips()[0].style.display).not.toBe('none');
    expect(document.querySelector('[data-testid="map-label-chip"]:not([data-kind])').style.display).toBe('none');
  });

  it('draws nothing without chips', async () => {
    await mountSeas({ bigSeasChips: undefined });
    expect(seasChips()).toHaveLength(0);
  });

  describe('closeCallout\'s focus return', () => {
    // MapView's `closeCallout` focuses the first node matching this selector. Pinned against the
    // source below, so a change to either side fails here rather than dropping focus to <body>.
    const SELECTOR = '[data-testid="map-label-chip"][data-selected="true"]';

    it('a selected big-seas chip is found by that selector and takes focus', async () => {
      await mountSeas({ selectedName: 'Roker Pier & Lighthouse' });
      const found = document.querySelector(SELECTOR);
      expect(found).not.toBeNull();
      expect(found).toHaveAttribute('data-kind', 'big-seas');
      found.focus({ preventScroll: true });
      expect(document.activeElement).toBe(found);
    });

    it('is the selector MapView.closeCallout queries', () => {
      const source = readFileSync(resolve(process.cwd(), 'src/components/MapView.jsx'), 'utf8');
      expect(source).toContain(`'${SELECTOR}`);
    });
  });
});
