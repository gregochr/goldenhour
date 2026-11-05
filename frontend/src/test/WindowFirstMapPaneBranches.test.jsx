import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render } from '@testing-library/react';

/**
 * The branches of the Map pane that the other pane files do not reach: a briefing shaped by the
 * reader's sub-region picks (`allowedLocationIds`), and a pane mounted before the briefing knows
 * today's date. `MapView` is stubbed; every assertion is about what it is handed.
 */

const MapStub = { lastProps: null };
vi.mock('../components/MapView.jsx', () => ({
  default: (props) => {
    MapStub.lastProps = props;
    return <div data-testid="stub-map" />;
  },
}));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'LITE_USER' }) }));
vi.mock('../api/outcomeRatingApi.js', () => ({
  fetchMyRatings: vi.fn(() => new Promise(() => {})),
  putRating: vi.fn(),
}));
let briefingValue = null;
vi.mock('../context/WindowFirstBriefingContext.jsx', () => ({
  useWindowFirstBriefing: () => briefingValue,
}));

import WindowFirstMapPane from '../components/WindowFirstMapPane.jsx';

const TODAY = '2026-08-11';
const T1 = '2026-08-10';

const SPOTS = [
  { id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, regionName: 'North East', rid: 'North East', bortleClass: 4, scores: [5, 4] },
  { id: 2, name: 'Wastwater', lat: 54.44, lng: -3.30, regionName: 'The Lakes', rid: 'The Lakes', bortleClass: 2, scores: [3, null] },
  { id: 3, name: 'Kelso', lat: 56.20, lng: -2.43, regionName: 'The Borders', rid: 'The Borders', bortleClass: 5, scores: [2, 2] },
];

function context(overrides = {}) {
  return {
    heatSpots: SPOTS,
    heatPointSets: new Map(),
    heatStripCards: [],
    reachById: new Map(),
    homePlace: null,
    todayStr: TODAY,
    ...overrides,
  };
}

function renderPane(ctx = context(), props = {}) {
  briefingValue = ctx;
  return render(
    <WindowFirstMapPane
      locations={[]}
      dates={[TODAY]}
      selectedDate={TODAY}
      onSelectDate={vi.fn()}
      {...props}
    />,
  );
}

beforeEach(() => { MapStub.lastProps = null; });
afterEach(() => { briefingValue = null; vi.restoreAllMocks(); });

describe('WindowFirstMapPane — a briefing shaped by the reader\'s sub-region picks', () => {
  it('hands the map the served list, its count, and the box of the places it names', () => {
    const allowed = [1, 2];
    renderPane(context({ allowedLocationIds: allowed }));

    const { heat } = MapStub.lastProps;
    expect(heat.shaped).toBe(true);
    expect(heat.allowedCount).toBe(2);
    expect(heat.allowedIds).toBe(allowed);
    // Bamburgh and Wastwater only (padded by the bundle's 0.12 deg): Kelso, further north, is outside it.
    // South-west then north-east, Leaflet's own order.
    expect(heat.allowedBounds[0][0]).toBeCloseTo(54.44 - 0.12, 5);
    expect(heat.allowedBounds[1][0]).toBeCloseTo(55.61 + 0.12, 5);
  });

  it('has no box when none of the served ids is on the roster', () => {
    renderPane(context({ allowedLocationIds: [999] }));

    expect(MapStub.lastProps.heat.shaped).toBe(true);
    expect(MapStub.lastProps.heat.allowedCount).toBe(1);
    expect(MapStub.lastProps.heat.allowedBounds).toBeNull();
  });

  it('treats an empty served list as shaped, with nothing to frame', () => {
    renderPane(context({ allowedLocationIds: [] }));

    expect(MapStub.lastProps.heat.shaped).toBe(true);
    expect(MapStub.lastProps.heat.allowedCount).toBe(0);
    expect(MapStub.lastProps.heat.allowedBounds).toBeNull();
  });

  it('leaves every shaped field null for an unshaped body', () => {
    renderPane(context({ allowedLocationIds: undefined }));

    const { heat } = MapStub.lastProps;
    expect(heat.shaped).toBe(false);
    expect(heat.allowedCount).toBeNull();
    expect(heat.allowedIds).toBeNull();
    expect(heat.allowedBounds).toBeNull();
  });

  it('paints a past window only for the places the picks name', () => {
    const loc = (id, name, rating) => ({
      id,
      name,
      lat: 55 + id / 10,
      lon: -1.5,
      regionName: 'North East',
      locationType: ['LANDSCAPE'],
      forecastsByDate: new Map([[T1, { sunset: { rating, solarEventTime: `${T1}T19:50:00` } }]]),
    });
    renderPane(context({ allowedLocationIds: [1] }), {
      locations: [loc(1, 'Bamburgh', 2), loc(2, 'Alnmouth', 5)],
    });

    const points = MapStub.lastProps.heat.pointsByKey.get(`${T1}:SUNSET`);
    expect(points.map((p) => p.name)).toEqual(['Bamburgh']);
  });
});

describe('WindowFirstMapPane — before the briefing knows today', () => {
  it('still mounts the map when the date list holds a blank entry and today is unknown', () => {
    renderPane(context({ todayStr: undefined }), { dates: [null, TODAY] });

    expect(MapStub.lastProps).not.toBeNull();
    expect(MapStub.lastProps.heat.enabled).toBe(true);
  });
});
