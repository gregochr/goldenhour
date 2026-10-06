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

import MapLabels from '../components/map/MapLabels.jsx';
import { setMode } from '../utils/scoreRamp.js';

/**
 * Ask PhotoCast's picks as the label layer draws them (F3, plan §2.7) — the WIRING suite, on the same
 * stubbed map and measured-box idiom `MapLabels.test.jsx` uses. What a pick chip IS (the pick's own
 * window's facts, no tide, no tooltip), what it does (chooses the card), what the placer does for it
 * (first, then a bare rank circle, never nothing), and what happens to every other chip (fades).
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

/** A map stub with panes, whose projection the test can replace to put anchors exactly where it wants. */
function makeMap({
  size = { x: 800, y: 500 }, project = null,
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
    getZoom: () => 9,
    getSize: () => size,
    getContainer: () => container,
    getCenter: () => ({ lat: 55, lng: -2 }),
    getBounds: () => ({ contains: () => true }),
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
    fire(event) { for (const fn of handlers.get(event) || []) fn(); },
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
const WHITBY = spot('Whitby', 54.5, -0.6, { rating: 2, tideTier: 'match', tideState: 'HIGH', tideFitPhrase: 'HW at sunset' });
const SALTBURN = spot('Saltburn', 54.58, -0.97, { rating: 4 });

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
const chips = () => [...document.querySelectorAll('[data-testid="map-label-chip"]')];
const chipFor = (name) => chips().find((c) => c.textContent.includes(name) || c.getAttribute('aria-label')?.includes(name));
const hidden = (el) => el.style.display === 'none';

describe('a pick chip — built from the pick\'s own window', () => {
  it('draws the rank circle, the name, the short window and that window\'s rating, and carries data-ask="pick"', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    await mount({ spots: [BAMBURGH, { ...WHITBY, askPick: PICK(1) }] });

    const chip = chips().find((c) => c.dataset.ask === 'pick');
    expect(chip).toHaveTextContent('1');
    expect(chip).toHaveTextContent('Whitby');
    expect(chip).toHaveTextContent('Sat AM');
    // The PICK window's 4★, not the active window's 2★ the spot itself carries.
    expect(chip).toHaveTextContent('4★');
    expect(chip).not.toHaveTextContent('2★');
  });

  it('names itself for a screen reader the way the card does: "Pick 1, Whitby, Saturday sunrise, 4 stars"', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    await mount({ spots: [{ ...WHITBY, askPick: PICK(1) }] });

    expect(screen.getByRole('button', { name: 'Pick 1, Whitby, Saturday sunrise, 4 stars' })).toBeInTheDocument();
  });

  it('⚠️ carries no tide glyph, no tide attribute and no tooltip, even on a coastal spot with a served tide fact', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    await mount({ spots: [{ ...WHITBY, askPick: PICK(1) }] });
    const chip = chips()[0];

    expect(chip).not.toHaveAttribute('data-tide');
    expect(chip.querySelector('[data-testid="map-label-chip-tide"]')).toBeNull();
    fireEvent.pointerEnter(chip, { pointerType: 'mouse' });
    expect(screen.queryByTestId('map-label-tip')).toBeNull();
  });

  it('(control) the SAME coastal spot as an ordinary chip does carry the tide glyph and attribute', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    await mount({ spots: [WHITBY] });

    expect(chips()[0]).toHaveAttribute('data-tide', 'match');
    expect(chips()[0].querySelector('[data-testid="map-label-chip-tide"]')).not.toBeNull();
  });

  it('colours the rating by the pick window\'s verdict tier, not the ramp', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    await mount({ spots: [{ ...WHITBY, askPick: PICK(1, { rating: 2, verdict: 'MAYBE' }) }] });

    expect(chips()[0].querySelector('em')).toHaveAttribute('data-tier', 'MAYBE');
  });

  it('draws no rating when the pick\'s slot had none (the card says "Not scored")', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    await mount({ spots: [{ ...WHITBY, askPick: PICK(1, { rating: null, verdict: 'AWAITING' }) }] });

    expect(chips()[0].querySelector('em')).toBeNull();
  });

  it('marks the chosen pick (aria-current and data-ask-selected)', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    await mount({ spots: [{ ...WHITBY, askPick: PICK(1, { selected: true }) }] });

    expect(chips()[0]).toHaveAttribute('data-ask-selected', 'true');
    expect(chips()[0]).toHaveAttribute('aria-current', 'true');
  });

  it('a hover that is resting on a chip when it BECOMES a pick does not leave a tooltip hanging', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    const view = await mount({ spots: [WHITBY] });
    fireEvent.pointerEnter(chips()[0], { pointerType: 'mouse' });
    expect(screen.getByTestId('map-label-tip')).toBeInTheDocument();

    await act(async () => { view.rerender(<MapLabels spots={[{ ...WHITBY, askPick: PICK(1) }]} />); });
    await act(async () => { runFrames(); });

    expect(screen.queryByTestId('map-label-tip')).toBeNull();
  });
});

describe('the DOM order of the chips is their tab order', () => {
  it('⚠️ picks come first, in RANK order — choosing one does not reorder them', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    const spots = (selected) => [
      BAMBURGH,
      { ...WHITBY, askPick: PICK(2, { selected: selected === 2 }) },
      { ...SALTBURN, askPick: PICK(1, { selected: selected === 1 }) },
    ];
    const view = await mount({ spots: spots(null) });
    const order = () => chips().map((c) => c.dataset.askRank ?? 'other');
    expect(order()).toEqual(['1', '2', 'other']);

    await act(async () => { view.rerender(<MapLabels spots={spots(2)} />); });
    await act(async () => { runFrames(); });

    expect(order()).toEqual(['1', '2', 'other']);
  });
});

describe('every other chip steps back, and only while picks are on the map', () => {
  it('marks the rest data-ask="fade", and no chip at all without a pick', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    const view = await mount({ spots: [BAMBURGH, { ...WHITBY, askPick: PICK(1) }] });
    expect(chipFor('Bamburgh')).toHaveAttribute('data-ask', 'fade');
    expect(chips().find((c) => c.dataset.ask === 'pick')).toBeDefined();

    await act(async () => { view.rerender(<MapLabels spots={[BAMBURGH, WHITBY]} />); });
    await act(async () => { runFrames(); });

    expect(chips().every((c) => !c.hasAttribute('data-ask'))).toBe(true);
  });
});

describe('choosing a pick from the map', () => {
  it('a pick chip calls onSelectAskPick with its RANK, and never onSelect', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    const onSelect = vi.fn();
    const onSelectAskPick = vi.fn();
    await mount({ spots: [BAMBURGH, { ...WHITBY, askPick: PICK(3) }], onSelect, onSelectAskPick });

    fireEvent.click(chips().find((c) => c.dataset.ask === 'pick'));

    expect(onSelectAskPick).toHaveBeenCalledWith(3);
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('an ordinary chip still selects its location, as it always did', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    const onSelect = vi.fn();
    const onSelectAskPick = vi.fn();
    await mount({ spots: [BAMBURGH, { ...WHITBY, askPick: PICK(1) }], onSelect, onSelectAskPick });

    fireEvent.click(chipFor('Bamburgh'));

    expect(onSelect).toHaveBeenCalledWith('Bamburgh');
    expect(onSelectAskPick).not.toHaveBeenCalled();
  });

  it('⚠️ focus stays on the pick chip across the press and the selection it causes — never <body>', async () => {
    restoreMeasure = withMeasuredLabels(60, 14);
    currentMap = makeMap();
    const onSelectAskPick = vi.fn();
    const view = await mount({
      spots: [{ ...WHITBY, askPick: PICK(1) }, { ...SALTBURN, askPick: PICK(2) }], onSelectAskPick,
    });
    const chip = chips().find((c) => c.dataset.askRank === '1');
    chip.focus();
    fireEvent.click(chip);

    // The answer re-renders with the chosen pick marked and the map repaints (a camera move would).
    await act(async () => {
      view.rerender(
        <MapLabels
          spots={[{ ...WHITBY, askPick: PICK(1, { selected: true }) }, { ...SALTBURN, askPick: PICK(2) }]}
          onSelectAskPick={onSelectAskPick}
        />,
      );
    });
    await act(async () => { currentMap.fire('moveend'); });
    await act(async () => { runFrames(); });

    const after = chips().find((c) => c.dataset.askRank === '1');
    expect(after).toBe(chip);
    expect(document.activeElement).toBe(chip);
    expect(after).toHaveAttribute('data-ask-selected', 'true');
  });
});

describe('the label budget never costs the answer a pick', () => {
  // Every spot projects to ONE point in a frame with room for one chip only: 160 × 30, labels 150 × 14.
  const SAME_POINT = () => ({ x: 80, y: 15 });

  it('places the pick FIRST even when a better-rated chip would otherwise win the only free spot', async () => {
    restoreMeasure = withMeasuredLabels(150, 14);
    currentMap = makeMap({ size: { x: 160, y: 30 }, project: SAME_POINT });
    await mount({ spots: [BAMBURGH, { ...WHITBY, askPick: PICK(1) }] });

    expect(hidden(chipFor('Whitby'))).toBe(false);
    expect(hidden(chipFor('Bamburgh'))).toBe(true);
  });

  it('⚠️ a pick the placer cannot fit falls back to its bare rank circle, shown on its own point — never dropped', async () => {
    restoreMeasure = withMeasuredLabels(150, 14);
    currentMap = makeMap({ size: { x: 160, y: 30 }, project: SAME_POINT });
    await mount({ spots: [{ ...WHITBY, askPick: PICK(1) }, { ...SALTBURN, askPick: PICK(2) }] });

    const second = chips().find((c) => c.dataset.askRank === '2');
    expect(second).toHaveAttribute('data-compact', 'true');
    expect(hidden(second)).toBe(false);
    expect(second.style.left).not.toBe('');
    // ...and the pick that fitted is the chip, not a circle.
    expect(chips().find((c) => c.dataset.askRank === '1')).not.toHaveAttribute('data-compact');
  });

  it('puts the bare circle on the nearest clear air when there is some (the ladder, not the last resort)', async () => {
    // A 790px label cannot fit anywhere on an 800px frame from x = 780 (every rung of the ladder runs off
    // one edge); a 25px circle can.
    restoreMeasure = withMeasuredLabels(790, 14);
    currentMap = makeMap({ size: { x: 800, y: 500 }, project: () => ({ x: 780, y: 250 }) });
    await mount({ spots: [{ ...WHITBY, askPick: PICK(1) }] });

    const chip = chips()[0];
    expect(chip).toHaveAttribute('data-compact', 'true');
    expect(chip.style.left).toBe(`${780 - 12.5}px`);
    expect(chip.style.top).toBe(`${250 - 12.5}px`);
  });

  it('a pick whose point is outside the frame has nowhere to be drawn and is left out', async () => {
    restoreMeasure = withMeasuredLabels(150, 14);
    currentMap = makeMap({ project: () => ({ x: -400, y: 250 }) });
    await mount({ spots: [{ ...WHITBY, askPick: PICK(1) }] });

    expect(hidden(chips()[0])).toBe(true);
  });
});
