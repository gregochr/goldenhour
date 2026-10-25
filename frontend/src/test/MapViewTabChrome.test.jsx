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
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
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
  // ⚠️ Rewritten for §3 P7, which removed the primary row and the old drawer/pill from the TAB, and
  // `map-container`'s height is no longer a pixel figure. (Absence assertions for the retired
  // `map-context-bar`, `filter-summary` and `advanced-filters-toggle` ids were dropped in C5: no
  // component can render them, so they could not fail.)
  it('has no primary row and no fixed map height — the window control and filters are chrome over the full-frame map', () => {
    renderMap({ forecastDates: [TODAY] });

    // The window control and the filters chip are both mounted, but as absolutely-positioned
    // chrome INSIDE the map container (map-tab-v2-plan.md §3 P7), not a page-flow row above it.
    expect(screen.getByTestId('wf-win-pill')).toBeInTheDocument();
    expect(screen.getByTestId('wf-filters-chip')).toBeInTheDocument();
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
