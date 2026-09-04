/**
 * `MapView.jsx`'s `driveMinutesFor` under an away origin (`heat.driveOverrideById` set) — a
 * pre-existing bug, fixed on owner request alongside the PR #734 selection-vs-filters round.
 *
 * The override branch returned `driveOverride.get(Number(locId))` — the WHOLE
 * `{driveMinutes, distanceMiles}` entry `originReachMap` builds (`utils/planOrigin.js`) — instead
 * of its `driveMinutes` NUMBER. Every arithmetic consumer misbehaved silently under an away
 * origin: the drive-time filter's `mins <= driveTimeFilter` compared a number against an object
 * (always `false`), and any duration rendering fed with the raw object rather than the number
 * would have carried the wrong shape all the way to the DOM. It predates map-tab-v2-plan.md §3
 * P9 — P9's own `distanceMilesFor` was built to read `reachById` directly rather than reuse this
 * function, which is what kept the selection callout's own miles fact from ever tripping over it.
 *
 * Three cases: (a) an away origin's override returns the NUMBER; (b) a location absent from the
 * override map reads null — the "overwrite, never a fallback" rule holds even for the bug's own
 * fix, so an absent entry must not quietly resolve to the home figure; (c) the drive-time filter
 * itself, which is the actually-broken PATH (a number-vs-object comparison is always false, so
 * the filter matched nothing at all under an away origin before this fix).
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, render, fireEvent, screen,
} from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

let fakeMarker;

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Marker: React.forwardRef(function MockMarker({ children }, ref) {
    React.useImperativeHandle(ref, () => fakeMarker);
    return <div data-testid="marker">{children}</div>;
  }),
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    getZoom: () => 9,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
  }),
}));


vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div data-testid="map-heat-layer" /> }));

/** The probe: a button that selects a named spot, exactly like `MapViewSelectionOrdering.test.jsx`'s
 * own mock. */
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => (
    <div>
      {['Near', 'Far-home-only'].map((name) => (
        <button key={name} type="button" data-testid={`probe-chip-${name}`} onClick={() => props.onSelect(name)}>
          {name}
        </button>
      ))}
    </div>
  ),
}));

/** The callout, mocked to a probe exposing exactly what this file asserts on — `driveMinutes`,
 * read straight off the SAME `driveMinutesFor(selectedLoc.id)` call the real `MapCallout`'s own
 * Drive fact renders from (`MapView.jsx`'s `<MapCallout driveMinutes={...} />`). */
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: (props) => (
    <div data-testid="probe-callout">
      <span data-testid="probe-callout-name">{props.location?.name ?? ''}</span>
      <span data-testid="probe-callout-drive-minutes">{JSON.stringify(props.driveMinutes)}</span>
    </div>
  ),
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
/** The HOME fetch — controllable per test so (b) can prove the override never falls back to it. */
let driveTimesResponse = {};
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn(() => Promise.resolve(driveTimesResponse)) }));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

const NEAR_ID = 1;
const FAR_HOME_ONLY_ID = 2;

function makeLocations() {
  return [
    {
      id: NEAR_ID,
      name: 'Near',
      lat: 55.61,
      lon: -1.71,
      regionName: 'North East',
      locationType: ['LANDSCAPE'],
      forecastsByDate: new Map([[TODAY, {
        sunset: { rating: 5, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
      }]]),
    },
    {
      // Present ONLY in the home `userDriveTimes` fetch, absent from the away override map —
      // proves the override never falls back to it (test b).
      id: FAR_HOME_ONLY_ID,
      name: 'Far-home-only',
      lat: 55.7,
      lon: -1.8,
      regionName: 'North East',
      locationType: ['LANDSCAPE'],
      forecastsByDate: new Map([[TODAY, {
        sunset: { rating: 5, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
      }]]),
    },
  ];
}

function heatProp(driveOverrideById) {
  return {
    enabled: true,
    hasHome: false,
    spots: [],
    areaSpots: [],
    pointsByKey: new Map([[`${TODAY}:SUNSET`, []]]),
    windows: [{
      key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 5, conf: 1,
    }],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
    ...(driveOverrideById !== undefined ? { driveOverrideById } : {}),
  };
}

async function renderTab(props = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapView
        locations={makeLocations()}
        date={TODAY}
        autoEventType="SUNSET"
        forecastDates={[TODAY]}
        {...props}
      />,
    );
  });
  // Let the async `getDriveTimes()` fetch resolve and its state update flush.
  await act(async () => {
    await new Promise((resolve) => { setTimeout(resolve, 0); });
  });
  return result;
}

beforeEach(() => {
  localStorage.clear();
  fakeMarker = { openPopup: vi.fn() };
  driveTimesResponse = {};
});

afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
});

describe('MapView — driveMinutesFor under an away origin (heat.driveOverrideById)', () => {
  it('(a) returns the override entry\'s driveMinutes NUMBER, not the whole {driveMinutes, distanceMiles} object', async () => {
    // The home fetch answers with a DIFFERENT number for the same id, so a pass here can only be
    // explained by the override branch — never a fallback that happened to agree.
    driveTimesResponse = { [NEAR_ID]: 999 };
    const driveOverrideById = new Map([[NEAR_ID, { driveMinutes: 27, distanceMiles: 9 }]]);
    await renderTab({ heat: heatProp(driveOverrideById) });

    fireEvent.click(screen.getByTestId('probe-chip-Near'));
    expect(await screen.findByTestId('probe-callout')).toBeInTheDocument();
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Near');
    // Exact match, deliberately: `27`, never `{"driveMinutes":27,"distanceMiles":9}` — the bug's
    // own shape ALSO contains the substring "27", so a plain (substring) `toHaveTextContent('27')`
    // would pass on the bug too and prove nothing — this must be an anchored match.
    expect(screen.getByTestId('probe-callout-drive-minutes')).toHaveTextContent(/^27$/);
  });

  it('(b) reads null for a location the override map carries no entry for — NEVER the home figure', async () => {
    driveTimesResponse = { [FAR_HOME_ONLY_ID]: 55 };
    // The override map exists (an origin IS active) but has nothing for this location.
    const driveOverrideById = new Map([[NEAR_ID, { driveMinutes: 27, distanceMiles: 9 }]]);
    await renderTab({ heat: heatProp(driveOverrideById) });

    fireEvent.click(screen.getByTestId('probe-chip-Far-home-only'));
    expect(await screen.findByTestId('probe-callout')).toBeInTheDocument();
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Far-home-only');
    expect(screen.getByTestId('probe-callout-drive-minutes')).toHaveTextContent(/^null$/);
  });
});

describe('MapView — the drive-time filter actually filters under an away origin (the previously-broken path)', () => {
  it('hides a location beyond the selected threshold and keeps one within it', async () => {
    // Before the fix, `mins` was the whole `{driveMinutes, distanceMiles}` object for EVERY
    // location once an origin was active, so `mins <= driveTimeFilter` was `false` unconditionally
    // — nothing was ever filtered OUT (every location stayed visible regardless of the selected
    // threshold), the opposite of a hidden failure that could pass a naive "still filters SOME
    // locations" check.
    const driveOverrideById = new Map([
      [NEAR_ID, { driveMinutes: 20, distanceMiles: 5 }],
      [FAR_HOME_ONLY_ID, { driveMinutes: 100, distanceMiles: 40 }],
    ]);
    await renderTab({ overlayMode: true, heat: heatProp(driveOverrideById) });

    expect(screen.getAllByTestId('marker')).toHaveLength(2);

    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));
    fireEvent.change(screen.getByTestId('drive-time-filter-select'), { target: { value: '30' } });

    // Only `Near` (20 min) survives a ≤30 min filter; `Far-home-only` (100 min) is dropped.
    expect(screen.getAllByTestId('marker')).toHaveLength(1);

    fireEvent.change(screen.getByTestId('drive-time-filter-select'), { target: { value: '0' } });
    expect(screen.getAllByTestId('marker')).toHaveLength(2);
  });
});
