import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen,
} from '@testing-library/react';

vi.mock('leaflet', () => {
  const DomUtil = { setPosition: () => {} };
  return { default: { DomUtil }, DomUtil };
});

let currentMap = null;
vi.mock('react-leaflet', () => ({ useMap: () => currentMap }));

import PinsLayer from '../components/map/PinsLayer.jsx';
import { rampHex, setMode } from '../utils/scoreRamp.js';

/**
 * Ask PhotoCast's picks in Pins mode (F3, plan §2.7) — the dot is drawn from the PICK'S window, carries
 * its rank, sits on top, speaks for itself to a screen reader and a pointer, and every other dot steps
 * back. `PinsLayer.test.jsx`'s map stub, trimmed to what this layer reads.
 */

function makeMap() {
  const handlers = new Map();
  const wrap = document.createElement('div');
  const container = document.createElement('div');
  wrap.appendChild(container);
  document.body.appendChild(wrap);
  Object.defineProperty(container, 'offsetWidth', { value: 800, configurable: true });
  const panes = {};
  return {
    getZoom: () => 9,
    getSize: () => ({ x: 800, y: 500 }),
    getContainer: () => container,
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 100, y: (56 - lat) * 100 }),
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

const PICK = (rank, over = {}) => ({
  rank,
  shortWindow: 'Sat AM',
  rating: 4,
  verdict: 'WORTH_IT',
  label: `Pick ${rank}, Whitby, Saturday sunrise, 4 stars`,
  selected: false,
  ...over,
});
const spot = (name, lat, lng, over = {}) => ({
  name, lat, lng, rid: 'North East', rating: 3, bortleClass: null, driveMinutes: null, ...over,
});

const BAMBURGH = spot('Bamburgh', 55.6, -1.7, { rating: 5 });
const WHITBY = spot('Whitby', 54.5, -0.6, {
  rating: 2, tideTier: 'miss', tideShortfall: 'HIGHER', tideFitPhrase: 'wants HW',
});
const SALTBURN = spot('Saltburn', 54.58, -0.97, { rating: 4 });

let frames = [];
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
  document.body.innerHTML = '';
  setMode('verdict');
  vi.clearAllMocks();
});

async function mount(props = {}) {
  let result;
  await act(async () => { result = render(<PinsLayer spots={[]} {...props} />); });
  await act(async () => { const due = frames; frames = []; due.forEach((cb) => cb && cb()); });
  return result;
}
const pins = () => [...document.querySelectorAll('[data-testid="map-pin"]')];
const picks = () => pins().filter((p) => p.dataset.ask === 'pick');

describe('a pick pin', () => {
  it('shows its rank where the star would be, in the colour of the PICK window\'s rating', async () => {
    currentMap = makeMap();
    await mount({ spots: [{ ...WHITBY, askPick: PICK(2, { rating: 5 }) }] });

    const [pin] = picks();
    expect(pin).toHaveTextContent('2');
    expect(pin).not.toHaveTextContent('★');
    // 5★ from the pick, not the 2★ the spot carries for the window on screen.
    expect(pin.style.background).toBe(hexToRgb(rampHex(5)));
  });

  it('names itself like the card and the chip: "Pick 2, Whitby, Saturday sunrise, 5 stars"', async () => {
    currentMap = makeMap();
    await mount({ spots: [{ ...WHITBY, askPick: PICK(2, { label: 'Pick 2, Whitby, Saturday sunrise, 5 stars' }) }] });

    expect(screen.getByRole('button', { name: 'Pick 2, Whitby, Saturday sunrise, 5 stars' })).toBeInTheDocument();
  });

  it('carries no tide cue and no tooltip, though the spot is a coastal miss on the window on screen', async () => {
    currentMap = makeMap();
    await mount({ spots: [{ ...WHITBY, askPick: PICK(1) }] });
    const [pin] = picks();

    expect(pin).not.toHaveAttribute('data-tide');
    fireEvent.pointerEnter(pin, { pointerType: 'mouse' });
    expect(screen.queryByTestId('map-label-tip')).toBeNull();
  });

  it('⚠️ comes FIRST in the DOM in rank order (the tab order) and sits on top by z-index, best-ranked highest', async () => {
    currentMap = makeMap();
    await mount({
      spots: [
        { ...WHITBY, askPick: PICK(2) },
        BAMBURGH,
        { ...SALTBURN, askPick: PICK(1) },
      ],
    });

    expect(pins().map((p) => p.dataset.ask ?? '-')).toEqual(['pick', 'pick', 'fade']);
    expect(picks().map((p) => p.dataset.askRank)).toEqual(['1', '2']);
    const [one, two] = picks();
    expect(Number(one.style.zIndex)).toBeGreaterThan(Number(two.style.zIndex));
    expect(Number(two.style.zIndex)).toBeGreaterThan(0);
  });

  it('prints its rank in an ink that can be read, rated or not', async () => {
    currentMap = makeMap();
    await mount({ spots: [{ ...WHITBY, askPick: PICK(1, { rating: null, verdict: 'AWAITING' }) }] });

    const [pin] = picks();
    expect(pin).toHaveTextContent('1');
    // Never the same colour as its own fill (the first cut set both to the no-data grey).
    expect(pin.style.color).not.toBe(pin.style.background);
  });

  it('marks the chosen pick', async () => {
    currentMap = makeMap();
    await mount({ spots: [{ ...WHITBY, askPick: PICK(1, { selected: true }) }] });

    expect(picks()[0]).toHaveAttribute('data-ask-selected', 'true');
    expect(picks()[0]).toHaveAttribute('aria-current', 'true');
  });

  it('chooses the card with its RANK, never the location', async () => {
    currentMap = makeMap();
    const onSelect = vi.fn();
    const onSelectAskPick = vi.fn();
    await mount({ spots: [BAMBURGH, { ...WHITBY, askPick: PICK(3) }], onSelect, onSelectAskPick });

    fireEvent.click(picks()[0]);
    expect(onSelectAskPick).toHaveBeenCalledWith(3);
    expect(onSelect).not.toHaveBeenCalled();

    fireEvent.click(pins().find((p) => p.dataset.ask === 'fade'));
    expect(onSelect).toHaveBeenCalledWith('Bamburgh');
  });

  it('a pin that BECOMES a pick under a resting pointer does not leave a tooltip hanging', async () => {
    currentMap = makeMap();
    const view = await mount({ spots: [WHITBY] });
    fireEvent.pointerEnter(pins()[0], { pointerType: 'mouse' });
    expect(screen.getByTestId('map-label-tip')).toBeInTheDocument();

    await act(async () => { view.rerender(<PinsLayer spots={[{ ...WHITBY, askPick: PICK(1) }]} />); });

    expect(screen.queryByTestId('map-label-tip')).toBeNull();
  });
});

describe('every other pin steps back, and only while picks are on the map', () => {
  it('marks them data-ask="fade", and none once the answer is gone', async () => {
    currentMap = makeMap();
    const view = await mount({ spots: [BAMBURGH, { ...WHITBY, askPick: PICK(1) }] });
    expect(pins().find((p) => p.getAttribute('aria-label')?.startsWith('Bamburgh'))).toHaveAttribute('data-ask', 'fade');

    await act(async () => { view.rerender(<PinsLayer spots={[BAMBURGH, WHITBY]} />); });

    expect(pins().every((p) => !p.hasAttribute('data-ask'))).toBe(true);
  });
});

/** The browser reports an inline `#rrggbb` fill as `rgb(r, g, b)`. */
function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`;
}
