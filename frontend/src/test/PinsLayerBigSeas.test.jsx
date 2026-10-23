import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen,
} from '@testing-library/react';

const setPosition = vi.fn();
vi.mock('leaflet', () => {
  const DomUtil = { setPosition: (...args) => setPosition(...args) };
  return { default: { DomUtil }, DomUtil };
});

let currentMap = null;
vi.mock('react-leaflet', () => ({ useMap: () => currentMap }));

import PinsLayer from '../components/map/PinsLayer.jsx';
import { bigSeasChipItems } from '../utils/mapLabels.js';
import { SATURDAY } from './bigSeasFixtures.js';

/**
 * The big-seas chips in the Pins view (big-seas plan F2, owner Q11): the same chip items, the same
 * tier (after the home marker; Pins has no other label to come before or after), the same words. The
 * pins themselves are untouched. Same jsdom scaffolding as `PinsLayer.test.jsx`: nothing is laid out,
 * so the boxes are stubbed. Saturday 24 Oct 2026, still BST.
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
    latLngToContainerPoint: ([lat, lng]) => ({ x: 200 + (lng + 1.5) * 300, y: 250 - (lat - 55) * 300 }),
    containerPointToLayerPoint: (p) => ({ x: p[0], y: p[1] }),
    on() {},
    off() {},
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
    // Roker is stood down on the storm day: it is a pin (grey), and has a chip from the roster.
    name: 'Roker Pier & Lighthouse', lat: 54.92, lng: -1.36, rid: 'North East', rating: null, isStandDown: true,
  },
];

const ROSTER = new Map([
  [243, {
    name: 'Roker Pier & Lighthouse', lat: 54.92, lon: -1.36, regionName: 'North East', driveMinutes: 45,
  }],
  [28, {
    name: "St. Mary's Lighthouse", lat: 55.07, lon: -1.44, regionName: 'North East', driveMinutes: 70,
  }],
  [21, {
    name: 'Amble', lat: 55.33, lon: -1.58, regionName: 'North East', driveMinutes: null,
  }],
]);
const SUNSET_ROW = { kind: 'solar', eventType: 'SUNSET', date: SATURDAY.date };
const SUNRISE_ROW = { kind: 'solar', eventType: 'SUNRISE', date: SATURDAY.date };
const chipsFor = (row = SUNSET_ROW) => bigSeasChipItems(SATURDAY, ROSTER, 9, row);

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

const MOUSE = { pointerType: 'mouse' };

async function mountPins(props = {}) {
  restoreMeasure = withMeasuredLabels(70, 17);
  currentMap = makeFullMap();
  let result;
  await act(async () => {
    result = render(<PinsLayer spots={SPOTS} bigSeasChips={chipsFor()} {...props} />);
  });
  await act(async () => { runFrames(); });
  return result;
}

const seasChips = () => [...document.querySelectorAll('[data-testid="map-label-chip"][data-kind="big-seas"]')];

describe('PinsLayer — big-seas chips', () => {
  it('draws one chip per served structure beside the pins, which are untouched', async () => {
    await mountPins();
    expect(seasChips().map((c) => c.textContent)).toEqual(['Roker Pier4.3 m', "St Mary's4.1 m", 'Amble piers3.9 m']);
    expect(seasChips().every((c) => c.closest('[data-testid="pins-layer"]'))).toBe(true);
    expect(seasChips().every((c) => c.style.display !== 'none' && c.style.left !== '-9999px')).toBe(true);
    // The stood-down structure is a (grey) pin AND has a chip: the pins are the honest comparison.
    expect(screen.getAllByTestId('map-pin')).toHaveLength(2);
  });

  it('is ringed on the aligned event only, with the same accessible name as in Heat', async () => {
    await mountPins();
    expect(seasChips()[0].dataset.aligned).toBe('true');
    expect(seasChips()[0]).toHaveAttribute(
      'aria-label',
      'Roker Pier, big seas, waves 4.3 metres, 15:30 to 18:15, high water on the sunset',
    );
    cleanup();
    await mountPins({ bigSeasChips: chipsFor(SUNRISE_ROW) });
    expect(seasChips()[0].dataset.aligned).toBeUndefined();
    expect(seasChips()[0]).toHaveAttribute('aria-label', 'Roker Pier, big seas, waves 4.3 metres, 15:30 to 18:15');
  });

  it('shows the tooltip lines on a mouse hover and selects the place on a press', async () => {
    const onSelect = vi.fn();
    await mountPins({ onSelect });
    fireEvent.pointerEnter(seasChips()[0], MOUSE);
    expect(Array.from(screen.getByTestId('map-label-tip').children).map((el) => el.textContent)).toEqual([
      'Roker Pier & Lighthouse',
      'Big seas · 15:30–18:15',
      'High water 16:58 · 49 min before sunset',
      'Waves 4.3 m from NNE, 11 s · faces NE, onshore',
      'North East · 45 min',
    ]);
    fireEvent.click(seasChips()[1]);
    expect(onSelect).toHaveBeenCalledWith("St. Mary's Lighthouse");
    expect(screen.queryByTestId('map-label-tip')).toBeNull();
  });

  it('carries the selected mark closeCallout looks for, and steps back while Ask\'s picks are shown', async () => {
    await mountPins({ selectedName: 'Roker Pier & Lighthouse' });
    expect(document.querySelector('[data-testid="map-label-chip"][data-selected="true"]'))
      .toBe(seasChips()[0]);
    expect(seasChips().every((c) => c.dataset.ask === undefined)).toBe(true);
    cleanup();
    await mountPins({
      spots: [{
        ...SPOTS[0], askPick: { rank: 1, shortWindow: 'Sat 17:47', rating: 4, verdict: 'MAYBE', label: 'Pick 1' },
      }],
    });
    expect(seasChips().every((c) => c.dataset.ask === 'fade')).toBe(true);
  });

  it('places after the home marker: home keeps a contested space over a chip', async () => {
    restoreMeasure = withMeasuredLabels(300, 17);
    currentMap = makeFullMap({ size: { x: 320, y: 24 } });
    currentMap.latLngToContainerPoint = () => ({ x: 160, y: 12 });
    await act(async () => {
      render(
        <PinsLayer
          spots={SPOTS}
          homeCoords={{ lat: 55, lon: -1.5 }}
          bigSeasChips={[chipsFor()[0]]}
        />,
      );
    });
    await act(async () => { runFrames(); });
    expect(document.querySelector('[data-testid="map-label-home"]').style.display).not.toBe('none');
    expect(seasChips()[0].style.display).toBe('none');
  });

  it('a touch pointerenter shows no tooltip (a tap must not leave one stuck over the callout)', async () => {
    await mountPins();
    fireEvent.pointerEnter(seasChips()[0], { pointerType: 'touch' });
    expect(screen.queryByTestId('map-label-tip')).toBeNull();
  });

  it('pressing a chip keeps it mounted, focused and selected, and first in the tier', async () => {
    function Selecting() {
      const [selected, setSelected] = React.useState(null);
      return <PinsLayer spots={SPOTS} bigSeasChips={chipsFor()} selectedName={selected} onSelect={setSelected} />;
    }
    restoreMeasure = withMeasuredLabels(70, 17);
    currentMap = makeFullMap();
    await act(async () => { render(<Selecting />); });
    await act(async () => { runFrames(); });
    const chip = seasChips()[2];
    await act(async () => { fireEvent.click(chip); });
    await act(async () => { runFrames(); });
    expect(seasChips()[0]).toBe(chip);
    expect(chip).toHaveAttribute('data-selected', 'true');
    expect(document.activeElement).toBe(chip);
    expect(document.querySelector('[data-testid="map-label-chip"][data-selected="true"]')).toBe(chip);
  });

  it('draws nothing without chips', async () => {
    await mountPins({ bigSeasChips: undefined });
    expect(seasChips()).toHaveLength(0);
  });
});
