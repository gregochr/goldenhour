/**
 * `MapSizeSync` — the only thing that tells Leaflet its container changed size.
 *
 * <p><b>Why this file exists.</b> Nothing in the suite has ever reached it. Every other `MapView`
 * suite stubs `useMap` without an `invalidateSize`, so the guard at the top of the effect
 * (`typeof map?.invalidateSize !== 'function'`) early-returns and the whole component is inert under
 * test. Review found that deleting `|| resizeNonce != null` from its `enabled` prop — one clause —
 * brought back the grey-map-on-return bug with all 3067 tests still green, and that the overlay's
 * own `advancedOpen` path had never been covered either.
 *
 * <p>The stub set is the one the other `MapView` suites use, plus the one method that matters.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render } from '@testing-library/react';

const invalidateSize = vi.fn();

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = () => ({});
  return { default: { icon, divIcon }, icon, divIcon };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="leaflet-map">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div>{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  // The one difference from every other suite, and the reason this file can see anything.
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 470 }),
    invalidateSize,
  }),
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
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn().mockResolvedValue({}) }));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '4★', colour: '#E5A00D' }),
  STAND_DOWN_COLOUR: '#501313',
}));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

const LOCATIONS = [{
  name: 'Loc0',
  lat: 55,
  lon: -1.7,
  locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, {
    sunset: { rating: 4, solarEventTime: `${TODAY}T16:12:00` },
    sunrise: { rating: 4, solarEventTime: `${TODAY}T08:24:00` },
  }]]),
}];

const renderMap = (props = {}) => render(
  <MapView locations={LOCATIONS} date={TODAY} autoEventType={null} {...props} />,
);

beforeEach(() => {
  invalidateSize.mockClear();
  vi.useFakeTimers();
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
  localStorage.clear();
});
afterEach(() => { vi.useRealTimers(); localStorage.clear(); });

/** The sync polls on a 60ms interval for 340ms; one tick is enough to prove it is armed. */
const tick = (ms = 100) => vi.advanceTimersByTime(ms);

describe('MapSizeSync', () => {
  it('runs for a pane that supplies a resizeNonce', () => {
    renderMap({ resizeNonce: 0 });
    tick();
    expect(invalidateSize).toHaveBeenCalledWith({ animate: false });
  });

  it('runs again when the nonce moves, which is what a second rotation depends on', () => {
    // Monotonicity is the contract the pane upholds and this is the consuming half of it: the
    // effect keys on the value, so a nonce that stopped moving would leave every rotation after the
    // first uncorrected.
    const { rerender } = renderMap({ resizeNonce: 1 });
    tick(400);
    const afterFirst = invalidateSize.mock.calls.length;
    expect(afterFirst).toBeGreaterThan(0);
    rerender(<MapView locations={LOCATIONS} date={TODAY} autoEventType={null} resizeNonce={2} />);
    tick(400);
    expect(invalidateSize.mock.calls.length).toBeGreaterThan(afterFirst);
  });

  it('stops polling once the transition window has passed', () => {
    // It is an interval with a 340ms stop, not a permanent poll. Without the stop this would be a
    // timer running for the life of a pane that is never unmounted.
    renderMap({ resizeNonce: 0 });
    tick(400);
    const settled = invalidateSize.mock.calls.length;
    tick(2000);
    expect(invalidateSize.mock.calls.length).toBe(settled);
  });

  it('still runs in overlay mode, the path it was originally written for', () => {
    // Never covered before this file, and it is the reason the component exists at all — the
    // overlay's map grows and shrinks as the filter drawer animates.
    renderMap({ overlayMode: true });
    tick();
    expect(invalidateSize).toHaveBeenCalled();
  });
});
