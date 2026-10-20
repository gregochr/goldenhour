/**
 * Tests for the Map tab's chrome: no primary row and no fixed map height (the window control and the
 * filters are chrome over the full-frame map), and a filters popover whose open state is not
 * persisted. (This file was `MapViewOverlayContext.test.jsx` while it also pinned the retired
 * Plan-tab overlay's context bar and drawer; overlay convergence C5 deleted those cases.)
 *
 * Leaflet needs heavy mocking in JSDOM; everything map-related is stubbed.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

// ── Leaflet / react-leaflet stubs ────────────────────────────────────────────

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
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 470 }),
  }),
}));


// ── App dependencies ─────────────────────────────────────────────────────────

let mockRole = 'PRO_USER';
vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: () => ({ role: mockRole }),
}));

vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));

vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));

vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '4★', colour: '#E5A00D' }),
  STAND_DOWN_COLOUR: '#501313',
}));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
  localStorage.clear();
  mockRole = 'PRO_USER';
});

afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

function makeLocations(ratings = [4, 4, 3]) {
  return ratings.map((r, i) => ({
    name: `Loc${i}`,
    lat: 55 + i * 0.1,
    lon: -1.7,
    locationType: ['LANDSCAPE'],
    forecastsByDate: new Map([[TODAY, {
      sunset: { rating: r, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
      sunrise: { rating: r, solarEventTime: `${TODAY}T08:24:00`, fierySkyPotential: 60, goldenHourPotential: 50 },
    }]]),
  }));
}

function renderMap(overrides = {}) {
  return render(
    <MapView
      locations={makeLocations()}
      date={TODAY}
      autoEventType={null}
      {...overrides}
    />,
  );
}

const mapHeight = () => screen.getByTestId('map-container').style.height;

describe('MapView Map tab chrome', () => {
  // ⚠️ Rewritten for §3 P7, which removed the primary row itself and the old drawer/pill it
  // held on the TAB — the two replaced here (`filter-summary`'s text and `advanced-filters-toggle`)
  // no longer exist for the tab at all, and `map-container`'s height is no longer a pixel figure.
  // Old pins: "keeps the Filters summary on the primary row..." (asserted `filter-summary` text and
  // a fixed `500px` map height) and "still remembers the disclosure across mounts" (asserted
  // `mapFiltersOpen` persistence through `advanced-filters-toggle`) — both below, replaced rather
  // than deleted, so the tab's actual P7 behaviour has a pin of its own.
  it('has no primary row and no fixed map height — the window control and filters are chrome over the full-frame map', () => {
    renderMap({ forecastDates: [TODAY] });

    expect(screen.queryByTestId('map-context-bar')).not.toBeInTheDocument();
    expect(screen.queryByTestId('forecast-type-selector')).not.toBeInTheDocument();
    // The window control and the filters chip are both mounted, but as absolutely-positioned
    // chrome INSIDE the map container (map-tab-v2-plan.md §3 P7), not a page-flow row above it.
    expect(screen.getByTestId('wf-win-pill')).toBeInTheDocument();
    expect(screen.getByTestId('wf-filters-chip')).toBeInTheDocument();
    expect(screen.queryByTestId('filter-summary')).not.toBeInTheDocument();
    expect(screen.queryByTestId('advanced-filters-toggle')).not.toBeInTheDocument();
    // No pixel height any more — the container is `flex:1; min-height:0`, sized by the shell's
    // own viewport-height chain rather than by a component-local constant.
    expect(mapHeight()).toBe('');
  });

  it('the filters popover opens closed on every mount — its own open/closed state is not persisted', () => {
    // The tab's popover is an ordinary click-to-open disclosure: nothing reads or writes
    // `mapFiltersOpen` any more (the retired overlay's drawer persisted it), so a reader who leaves it
    // open never finds it forced open on return.
    const { unmount } = renderMap();
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(screen.getByTestId('wf-filters-chip')).toHaveAttribute('aria-expanded', 'true');
    expect(localStorage.getItem('mapFiltersOpen')).toBeNull();
    unmount();

    renderMap();
    expect(screen.getByTestId('wf-filters-chip')).toHaveAttribute('aria-expanded', 'false');
  });
});
