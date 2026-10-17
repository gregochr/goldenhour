/**
 * `components/map/MapCallout.jsx` on a PAST window (outcome-rating-plan.md §2 #2, §3 O3a step 5).
 *
 * The card keeps the forecast star where it already is (the verdict strip — "the forecast star
 * stays where it already is"), hides Drive and Leave by (a trip still to make), keeps Dark sky (a
 * fact of the place), and withholds "Open in Plan" (the location sheet it opens is built from the
 * briefing's windows, which never hold a past one — §4 #14). "Zoom to it" stays. The card's height
 * changes when those facts go, and a window goes past with its row id UNCHANGED (a filler's id and a
 * past row's are the same `solar:DATE:TYPE`), so `event.past` must itself be a repaint dependency.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen } from '@testing-library/react';

let currentMap = null;
vi.mock('react-leaflet', () => ({ useMap: () => currentMap }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));

import MapCallout from '../components/map/MapCallout.jsx';
import { buildScoreIndex } from '../utils/locationSheet.js';

function withMeasuredCard(width, height) {
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

function makeMap() {
  // The card portals into the container's PARENT (`chromeRoot`), so the container needs one that is
  // not `<body>` itself — the same wrap `MapCallout.test.jsx`'s own `makeMap` builds.
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

const YESTERDAY = '2026-06-14';

const LOCATION = {
  id: 7,
  name: 'Bamburgh',
  lat: 55.6,
  lon: -1.7,
  regionName: 'North East',
  bortleClass: 3,
  tideType: ['HIGH'],
  locationType: ['SEASCAPE'],
};

const PAST_EVENT = {
  id: `solar:${YESTERDAY}:SUNSET`,
  kind: 'solar',
  eventType: 'SUNSET',
  date: YESTERDAY,
  past: true,
  served: false,
  label: 'Yesterday sunset',
  dayLabel: 'Yesterday',
  time: '21:10',
  badges: [],
};
// The same row id, before the clock elapsed it: a D-13 filler.
const FILLER_EVENT = { ...PAST_EVENT, past: undefined, label: 'Today sunset', dayLabel: 'Today' };

/** A scored row for the window, so the card has an event instant and a live window shows Leave by. */
const SCORE_INDEX = buildScoreIndex([{
  locationId: LOCATION.id,
  locationName: LOCATION.name,
  date: YESTERDAY,
  targetType: 'SUNSET',
  rating: 4,
  summary: 'A warm sky.',
  goldenHourStart: `${YESTERDAY}T19:30:00Z`,
  goldenHourEnd: `${YESTERDAY}T20:10:00Z`,
  blueHourStart: `${YESTERDAY}T20:10:00Z`,
  blueHourEnd: `${YESTERDAY}T20:50:00Z`,
}]);

let frames = [];
let originalRaf;
let originalCancel;
let restore;
beforeEach(() => {
  frames = [];
  originalRaf = global.requestAnimationFrame;
  originalCancel = global.cancelAnimationFrame;
  global.requestAnimationFrame = (cb) => { frames.push(cb); return frames.length; };
  global.cancelAnimationFrame = (id) => { frames[id - 1] = null; };
  currentMap = makeMap();
  restore = withMeasuredCard(286, 260);
});
afterEach(() => {
  restore();
  global.requestAnimationFrame = originalRaf;
  global.cancelAnimationFrame = originalCancel;
  currentMap = null;
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

async function mount(props = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapCallout location={LOCATION} event={PAST_EVENT} rating={4} {...props} />,
    );
  });
  return result;
}

describe('MapCallout — a past window', () => {
  it('keeps the forecast star in the verdict strip', async () => {
    await mount({ rating: 2 });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('2★ Poor');
    expect(screen.getByTestId('map-callout-verdict')).toHaveTextContent('Yesterday · 21:10');
  });

  it('hides Drive and Leave by even when the drive is measured, and keeps Dark sky', async () => {
    await mount({ driveMinutes: 65, distanceMiles: 12, scoreIndex: SCORE_INDEX });
    const facts = screen.getByTestId('map-callout-facts');
    expect(facts).toHaveTextContent('Dark sky');
    expect(facts.textContent).not.toMatch(/Drive/);
    expect(facts.textContent).not.toMatch(/Leave by/);
  });

  it('control: the same card on a LIVE window shows BOTH Drive and Leave by (so the absences above are the flag)', async () => {
    await mount({
      event: FILLER_EVENT, driveMinutes: 65, distanceMiles: 12, scoreIndex: SCORE_INDEX,
    });
    expect(screen.getByTestId('map-callout-facts')).toHaveTextContent('Drive');
    expect(screen.getByTestId('map-callout-facts')).toHaveTextContent('Leave by');
  });

  it('withholds "Open in Plan" and keeps "Zoom to it"', async () => {
    await mount({ onOpenInPlan: vi.fn() });
    expect(screen.queryByTestId('map-callout-open-in-plan')).not.toBeInTheDocument();
    expect(screen.getByTestId('map-callout-zoom')).toBeInTheDocument();
  });

  it('control: a live window keeps "Open in Plan"', async () => {
    await mount({ event: FILLER_EVENT, onOpenInPlan: vi.fn() });
    expect(screen.getByTestId('map-callout-open-in-plan')).toBeInTheDocument();
  });

  it('re-measures the anchor when the window GOES past with its row id unchanged', async () => {
    // The same id before and after: only `event.past` moved, so only it can trigger the repaint
    // that re-anchors a card whose Drive / Leave by facts have just gone. Counted on the one
    // `map.*` read `paint()` always makes (the shape MapCallout.test.jsx's event-id test uses).
    const { rerender } = await mount({ event: FILLER_EVENT, driveMinutes: 65 });
    const paintSpy = vi.spyOn(currentMap, 'latLngToContainerPoint');
    const before = paintSpy.mock.calls.length;

    await act(async () => {
      rerender(<MapCallout location={LOCATION} event={PAST_EVENT} rating={4} driveMinutes={65} />);
    });

    expect(PAST_EVENT.id).toBe(FILLER_EVENT.id);
    expect(paintSpy.mock.calls.length).toBeGreaterThan(before);
  });
});
