import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, fireEvent, render } from '@testing-library/react';

vi.mock('leaflet', () => {
  const DomUtil = { setPosition: () => {} };
  return { default: { DomUtil }, DomUtil };
});

let currentMap = null;
vi.mock('react-leaflet', () => ({ useMap: () => currentMap }));

import MapLabels from '../components/map/MapLabels.jsx';
import { setMode } from '../utils/scoreRamp.js';

/**
 * The stood-down PLACE labels as the label layer draws them (stood-down-place-labels-plan.md): the
 * WIRING suite, on the stubbed map and measured-box idiom `MapLabelsAsk.test.jsx` uses. The pure
 * candidate filter is `mapLabels.test.js`'s job; this file proves the host renders, gates, places
 * (last, never displacing) and reports a press.
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
  size = { x: 800, y: 500 }, project = null, zoom = 12, inView = true,
} = {}) {
  const handlers = new Map();
  const wrap = document.createElement('div');
  const container = document.createElement('div');
  wrap.appendChild(container);
  document.body.appendChild(wrap);
  Object.defineProperty(container, 'offsetWidth', { value: size.x, configurable: true });
  const panes = {};
  return {
    handlers,
    panes,
    container,
    getZoom: () => zoom,
    getSize: () => size,
    getContainer: () => container,
    getCenter: () => ({ lat: 55, lng: -2 }),
    getBounds: () => ({ contains: () => inView }),
    latLngToContainerPoint: project ?? (([lat, lng]) => ({ x: (lng + 3) * 100, y: (56 - lat) * 100 })),
    containerPointToLayerPoint: (p) => ({ x: p[0], y: p[1] }),
    getPane: (name) => panes[name] || null,
    createPane: (name) => {
      const el = document.createElement('div');
      panes[name] = el;
      container.appendChild(el);
      return el;
    },
    on(events, fn) { for (const e of events.split(' ')) handlers.set(e, [...(handlers.get(e) || []), fn]); },
    off(events, fn) {
      for (const e of events.split(' ')) handlers.set(e, (handlers.get(e) || []).filter((x) => x !== fn));
    },
  };
}

const stoodPlace = (name, over = {}) => ({
  key: `@${name}`, name, lat: 55.0, lng: -1.4, reason: 'LOW_VISIBILITY', driveMinutes: 30, ...over,
});

const spot = (name, lat, lng, over = {}) => ({
  name, lat, lng, rid: 'North East', rating: 3, bortleClass: null, driveMinutes: null, ...over,
});

const PICK = (rank) => ({
  rank, shortWindow: 'Sat AM', rating: 4, verdict: 'WORTH_IT', label: `Pick ${rank}`, selected: false,
});

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
  await act(async () => { result = render(<MapLabels spots={[]} {...props} />); });
  await act(async () => { runFrames(); });
  return result;
}
const stoods = () => [...document.querySelectorAll('[data-testid="map-label-stood"]')];
const chips = () => [...document.querySelectorAll('[data-testid="map-label-chip"]')];
const hidden = (el) => el.style.display === 'none';
const SAME_POINT = () => ({ x: 80, y: 15 });

describe('a stood-down place label', () => {
  it('names the place, says "stood down · visibility", carries a hatch swatch and no star', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    await mount({ stoodPlaces: [stoodPlace('Tynemouth')] });

    const [label] = stoods();
    expect(label).toHaveTextContent('Tynemouth');
    expect(label).toHaveTextContent('stood down · visibility');
    expect(label).toHaveAttribute('aria-label', 'Tynemouth, stood down: poor visibility');
    expect(label).toHaveClass('wf-maplab-chip', 'wf-maplab-stood');
    expect(label.querySelector('.wf-legend-swatch-standdown')).toHaveAttribute('aria-hidden', 'true');
    expect(label.querySelector('.wf-maplab-chip-r')).toBeNull();
    expect(label.querySelector('.wf-maplab-chip-m')).toBeNull();
    expect(hidden(label)).toBe(false);
  });

  it.each([
    ['HIGH_CLOUD', 'stood down · cloud', 'Tynemouth, stood down: cloud'],
    ['PRECIPITATION', 'stood down · rain', 'Tynemouth, stood down: rain'],
    ['GENERIC', 'stood down', 'Tynemouth, stood down'],
    ['SOMETHING_NEW', 'stood down', 'Tynemouth, stood down'],
    [null, 'stood down', 'Tynemouth, stood down'],
  ])('reason %s reads "%s" (a generic or unknown cause names no cause)', async (reason, words, aria) => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    await mount({ stoodPlaces: [stoodPlace('Tynemouth', { reason })] });

    const label = stoods()[0];
    expect(label.querySelector('.wf-maplab-stood-w').textContent).toBe(words);
    expect(label).toHaveAttribute('aria-label', aria);
  });

  it('reports a press as onSelect(name), and has no tooltip', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    const onSelect = vi.fn();
    await mount({ stoodPlaces: [stoodPlace('Tynemouth')], onSelect });

    fireEvent.pointerEnter(stoods()[0], { pointerType: 'mouse' });
    fireEvent.click(stoods()[0]);
    expect(onSelect).toHaveBeenCalledWith('Tynemouth');
    expect(document.querySelector('.wf-maplab-tip-n')).toBeNull();
  });

  it('appears at zoom 11.2 and not at 11.19 (the region stand-down label owns the zoomed-out map)', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap({ zoom: 11.19 });
    const { unmount } = await mount({ stoodPlaces: [stoodPlace('Tynemouth')] });
    expect(stoods()).toHaveLength(0);
    unmount();
    document.body.innerHTML = '';

    currentMap = makeMap({ zoom: 11.2 });
    await mount({ stoodPlaces: [stoodPlace('Tynemouth')] });
    expect(stoods()).toHaveLength(1);
  });

  it('draws nothing for a place outside the map\'s bounds (the same unpadded test chips use)', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap({ inView: false });
    await mount({ stoodPlaces: [stoodPlace('Tynemouth')] });
    expect(stoods()).toHaveLength(0);
  });

  it('on a phone-width frame reads "stood down" without the cause (the cause stays in the accessible name)', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap({ size: { x: 390, y: 700 } });
    await mount({ stoodPlaces: [stoodPlace('Tynemouth')] });

    const label = stoods()[0];
    expect(label).toHaveAttribute('data-tiny', 'true');
    expect(label.querySelector('.wf-maplab-stood-w').textContent).toBe('stood down');
    expect(label).toHaveAttribute('aria-label', 'Tynemouth, stood down: poor visibility');
  });

  it('is not drawn twice for a place that already has a chip', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    await mount({
      spots: [spot('Tynemouth', 55.0, -1.4, { id: 1 })],
      stoodPlaces: [stoodPlace('Tynemouth', { key: '#1' })],
    });
    expect(stoods()).toHaveLength(0);
    expect(chips()).toHaveLength(1);
  });

  it('keeps the selected stood place as its stood label, marked selected, with no chip', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    await mount({ stoodPlaces: [stoodPlace('Tynemouth')], selectedName: 'Tynemouth' });

    expect(stoods()[0]).toHaveAttribute('data-selected', 'true');
    expect(chips()).toHaveLength(0);
  });
});

describe('a stood-down place label never displaces anything', () => {
  it('loses the only free spot to a rated chip, even when the stood place is first on drive time', async () => {
    restoreMeasure = withMeasuredLabels(150, 14);
    currentMap = makeMap({ size: { x: 160, y: 30 }, project: SAME_POINT });
    await mount({
      spots: [spot('Bamburgh', 55.6, -1.7, { rating: 5 })],
      stoodPlaces: [stoodPlace('Tynemouth', { driveMinutes: 1 })],
    });

    expect(hidden(chips()[0])).toBe(false);
    expect(hidden(stoods()[0])).toBe(true);
  });

  it('loses the only free spot to an Ask pick', async () => {
    restoreMeasure = withMeasuredLabels(150, 14);
    currentMap = makeMap({ size: { x: 160, y: 30 }, project: SAME_POINT });
    await mount({
      spots: [{ ...spot('Whitby', 54.5, -0.6), askPick: PICK(1) }],
      stoodPlaces: [stoodPlace('Tynemouth')],
    });

    expect(hidden(chips()[0])).toBe(false);
    expect(hidden(stoods()[0])).toBe(true);
  });

  it('loses to the bare dot a pick falls back to', async () => {
    restoreMeasure = withMeasuredLabels(150, 14);
    currentMap = makeMap({ size: { x: 160, y: 30 }, project: SAME_POINT });
    await mount({
      spots: [
        { ...spot('Whitby', 54.5, -0.6), askPick: PICK(1) },
        { ...spot('Saltburn', 54.58, -0.97), askPick: PICK(2) },
      ],
      stoodPlaces: [stoodPlace('Tynemouth')],
    });

    const second = chips().find((c) => c.dataset.askRank === '2');
    expect(second).toHaveAttribute('data-compact', 'true');
    expect(hidden(second)).toBe(false);
    expect(hidden(stoods()[0])).toBe(true);
  });

  it('is blocked by the phone peek sheet\'s bottomInset obstacle', async () => {
    restoreMeasure = withMeasuredLabels(150, 14);
    currentMap = makeMap({ project: () => ({ x: 400, y: 450 }) });
    vi.spyOn(currentMap.container, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, width: 800, height: 500,
    });
    await mount({ stoodPlaces: [stoodPlace('Tynemouth')], bottomInset: 470 });
    expect(hidden(stoods()[0])).toBe(true);
  });

  it('is placed when nothing is in the way (so the test above proves the obstacle)', async () => {
    restoreMeasure = withMeasuredLabels(150, 14);
    currentMap = makeMap({ project: () => ({ x: 400, y: 450 }) });
    vi.spyOn(currentMap.container, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, width: 800, height: 500,
    });
    await mount({ stoodPlaces: [stoodPlace('Tynemouth')], bottomInset: 0 });
    expect(hidden(stoods()[0])).toBe(false);
  });
});

describe('the density budget a stood-down place label shares with the chips', () => {
  const manyChips = (n) => Array.from({ length: n }, (_, i) => spot(`P${i}`, 55 + i * 0.001, -1.5, { id: i + 100 }));

  it('offers none when the budget-spending chips already use it all (35 at zoom 11.2)', async () => {
    restoreMeasure = withMeasuredLabels(20, 8);
    currentMap = makeMap({ zoom: 11.2 });
    await mount({ spots: manyChips(35), stoodPlaces: [stoodPlace('Tynemouth')] });
    expect(stoods()).toHaveLength(0);
  });

  it('offers exactly the one left when 34 are used', async () => {
    restoreMeasure = withMeasuredLabels(20, 8);
    currentMap = makeMap({ zoom: 11.2 });
    await mount({ spots: manyChips(34), stoodPlaces: [stoodPlace('Tynemouth'), stoodPlace('Seaham')] });
    expect(stoods()).toHaveLength(1);
    expect(stoods()[0]).toHaveTextContent('Tynemouth');
  });
});
