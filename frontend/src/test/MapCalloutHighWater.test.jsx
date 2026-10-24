/**
 * `MapCallout` and `OutcomeRatingBlock` on the daylight High-water row (hot-topics-by-day-plan §3 M3,
 * the sweep): the row is neither a sunset nor a night. The callout names it, says "Big seas" instead of a
 * score (never "Not scored yet", never a sunset's stars), prints the lead copy, leaves it out of the
 * every-window strip, and never mounts the rating block (which would have read `'SUNRISE' ? … : sunset`).
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen } from '@testing-library/react';

let currentMap = null;
vi.mock('react-leaflet', () => ({ useMap: () => currentMap }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../api/outcomeRatingApi.js', () => ({
  fetchMyRatings: vi.fn(),
  putRating: vi.fn(),
  fetchAllRatingsForSlot: vi.fn(),
}));

import MapCallout from '../components/map/MapCallout.jsx';
import OutcomeRatingBlock from '../components/map/OutcomeRatingBlock.jsx';

function makeMap() {
  const wrap = document.createElement('div');
  const container = document.createElement('div');
  wrap.appendChild(container);
  document.body.appendChild(wrap);
  Object.defineProperty(container, 'offsetWidth', { value: 800, configurable: true });
  return {
    zoom: 9,
    getZoom() { return this.zoom; },
    getSize: () => ({ x: 800, y: 500 }),
    getContainer: () => container,
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 100, y: (56 - lat) * 100 }),
    panInside() {},
    flyTo() {},
    on() {},
    off() {},
  };
}

const NOW = new Date('2026-10-22T09:40:00Z');
const FRI = '2026-10-23';

const LOCATION = {
  id: 7,
  name: 'Roker Pier',
  lat: 54.92,
  lon: -1.36,
  regionName: 'North East',
  bortleClass: 3,
  tideType: ['HIGH'],
  locationType: ['SEASCAPE'],
  // A sunset forecast the High-water row must never borrow.
  forecastsByDate: new Map([[FRI, {
    sunset: { rating: 5, solarEventTime: `${FRI}T16:40:00` },
    sunrise: { rating: 2, solarEventTime: `${FRI}T06:20:00` },
  }]]),
};

const HW = {
  id: `hw:${FRI}:HIGH_WATER`, kind: 'hw', eventType: 'HIGH_WATER', date: FRI, label: 'Tomorrow high water',
  dayLabel: 'Tomorrow', time: '12:40', served: false, scored: false, bestRating: null, badges: [],
};
const SUNSET = {
  id: `solar:${FRI}:SUNSET`, kind: 'solar', eventType: 'SUNSET', date: FRI, label: 'Tomorrow sunset',
  dayLabel: 'Tomorrow', time: '17:40', served: true, scored: true, bestRating: 5, badges: [],
};
const LEAD = 'High water at 12:40 with 3.4 m waves from NNE. Not a light window: this is for spray over the piers, not for colour.';

const ratingsProp = () => ({
  loaded: true,
  covers: () => true,
  canRate: true,
  mine: new Map(),
  saving: new Set(),
  saveError: new Set(),
  tagSaving: new Set(),
  tagSaveError: new Set(),
  rate: vi.fn().mockResolvedValue(true),
  rateTag: vi.fn().mockResolvedValue(true),
  clearSaveError: vi.fn(),
});

let originalRaf;
let originalCancel;
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  originalRaf = global.requestAnimationFrame;
  originalCancel = global.cancelAnimationFrame;
  global.requestAnimationFrame = () => 1;
  global.cancelAnimationFrame = () => {};
  currentMap = makeMap();
});
afterEach(() => {
  vi.useRealTimers();
  global.requestAnimationFrame = originalRaf;
  global.cancelAnimationFrame = originalCancel;
  currentMap = null;
  document.body.innerHTML = '';
});

async function mount(props = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapCallout
        location={LOCATION}
        event={HW}
        rating={null}
        ratingKnown
        scoresKnown
        userRole="PRO_USER"
        ratings={ratingsProp()}
        evRows={[SUNSET, HW]}
        highWaterLead={LEAD}
        {...props}
      />,
    );
  });
  return result;
}

describe('MapCallout on the High-water row', () => {
  it('names the row, says "Big seas" instead of a score, and borrows no sunset stars', async () => {
    await mount();
    const verdict = screen.getByTestId('map-callout-verdict');
    expect(verdict).toHaveTextContent('High water');
    expect(verdict).toHaveTextContent('Tomorrow · 12:40');
    expect(screen.getByTestId('map-callout-hw-word')).toHaveTextContent('Big seas');
    expect(screen.queryByTestId('map-callout-score')).toBeNull();
    expect(verdict.textContent).not.toMatch(/★|Not scored|Loading/);
    // The kind chip is the tide-light variant, not a sunset's.
    expect(verdict.querySelector('.wf-hc-sun')).toHaveClass('hw');
  });

  it('prints the lead copy in the body, and no region gloss or reason button', async () => {
    await mount();
    expect(screen.getByTestId('map-callout-hw-lead')).toHaveTextContent(LEAD);
    expect(screen.queryByTestId('map-callout-reason')).toBeNull();
  });

  it('leaves the row out of the every-window strip (it holds no figure for a place)', async () => {
    await mount();
    act(() => { screen.getByTestId('map-callout-strip-toggle').click(); });
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    expect(cells.map((c) => c.getAttribute('data-ev-id'))).toEqual([SUNSET.id]);
  });

  // A PIN, not a mutation-proof guard: `ratingKey` is already null for any non-solar row (`kind === 'solar'`),
  // so the block cannot mount for the High-water row even on a "past" one. This fixes that behaviour in
  // place; the block's own refusal is the describe below.
  it('never mounts the rating block for it, even marked past with ratings offered', async () => {
    await mount({ event: { ...HW, past: true } });
    expect(screen.queryByTestId('outcome-rating-block')).toBeNull();
  });

  it('a hide gets no "comfort forecast covers daylight hours" night line on it', async () => {
    await mount({ location: { ...LOCATION, locationType: ['WILDLIFE'] } });
    expect(screen.queryByTestId('map-callout-comfort-night')).toBeNull();
    expect(screen.queryByTestId('map-callout-comfort')).toBeNull();
  });
});

describe('OutcomeRatingBlock refuses the High-water row', () => {
  it('renders nothing for it (it would otherwise read it as a sunset)', () => {
    const { container } = render(
      <OutcomeRatingBlock
        event={{ eventType: 'HIGH_WATER', dayLabel: 'Tomorrow', time: '12:40' }}
        userRole="PRO_USER"
        onChangingChange={() => {}}
        onRate={async () => true}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('still renders for a sunset (the guard is the High-water row\'s alone)', () => {
    render(
      <OutcomeRatingBlock
        event={{ eventType: 'SUNSET', dayLabel: 'Yesterday', time: '18:10' }}
        userRole="PRO_USER"
        onChangingChange={() => {}}
        onRate={async () => true}
      />,
    );
    expect(screen.getByTestId('outcome-rating-block')).toHaveAttribute('data-kind', 'sunset');
  });
});
