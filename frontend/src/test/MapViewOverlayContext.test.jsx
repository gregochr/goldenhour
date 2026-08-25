/**
 * Tests for MapView's overlay mode — the Plan tab's drill-down.
 *
 * Opening the overlay from a plan card IS a filter action: the user chose the location and the
 * solar event on the way in. Restating that as five rows of controls cost ~300px of map and asked
 * an answered question, leaving a strip too short to show the focused pin, its neighbours and the
 * popup without panning. What is pinned here is the replacement: one read-only context row, the
 * controls folded behind a disclosure that never remembers being open, and the height back on the
 * map — and, on the Map tab, that none of it changed.
 *
 * Leaflet needs heavy mocking in JSDOM; everything map-related is stubbed.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';

// ── Leaflet / react-leaflet stubs ────────────────────────────────────────────

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = () => ({});
  return { default: { icon, divIcon }, icon, divIcon };
});

vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('leaflet.markercluster/dist/MarkerCluster.css', () => ({}));

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

vi.mock('react-leaflet-cluster', () => ({
  default: ({ children }) => <div>{children}</div>,
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
  createClusterIcon: () => ({ options: { html: '', iconSize: { x: 40, y: 40 }, className: '' } }),
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

const drawerHeight = () => screen.getByTestId('advanced-filters-panel').style.maxHeight;
const mapHeight = () => screen.getByTestId('map-container').style.height;

describe('MapView overlay mode — the Plan tab drill-down', () => {
  it('opens on a context row, not a filter rail', () => {
    renderMap({ overlayMode: true });

    // One row, and the controls it stands for are folded away.
    expect(screen.getByTestId('map-context-bar')).toBeInTheDocument();
    expect(screen.getByTestId('advanced-filters-toggle')).toHaveAttribute('aria-expanded', 'false');
    expect(drawerHeight()).toBe('0px');
    // The event selector is real and mounted — it lives inside the drawer, not on the bar.
    expect(within(screen.getByTestId('map-context-bar'))
      .queryByTestId('forecast-type-selector')).toBeNull();
  });

  it('states the inherited event with its own clock time', () => {
    renderMap({ overlayMode: true, emphasiseLocationName: 'Loc1' });

    // 16:12 UTC in January is 16:12 in London — the chip carries the time the plan card showed.
    expect(screen.getByTestId('map-context-event')).toHaveTextContent('🌇 Sunset · 16:12');
  });

  it('states the quality floor always, and the other filters only when they cut', () => {
    // The floor is never a no-op: at 3★+ pins are being hidden right now, and the count beside it
    // would otherwise be unexplained. A drive-time filter left at "any" is cutting nothing, so
    // naming it would pad the receipt with switched-off things.
    renderMap({ overlayMode: true });

    const chips = screen.getAllByTestId('map-context-chip').map((c) => c.textContent);
    expect(chips).toEqual(['3★+']);

    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));
    fireEvent.change(screen.getByTestId('drive-time-filter-select'), { target: { value: '45' } });

    expect(screen.getAllByTestId('map-context-chip').map((c) => c.textContent))
      .toEqual(['3★+', '≤45m']);
  });

  it('counts the pins the map is showing, and recounts as a filter cuts them', () => {
    renderMap({ overlayMode: true });

    // Three locations, two at 4★ and one at 3★ — all above the 3★+ default.
    expect(screen.getByTestId('map-context-count')).toHaveTextContent('3 pins in view');

    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));
    fireEvent.click(screen.getByTestId('star-filter-4'));

    expect(screen.getByTestId('map-context-count')).toHaveTextContent('2 pins in view');
  });

  it('gives the reclaimed height to the map, and hands it back when the drawer opens', () => {
    renderMap({ overlayMode: true });
    expect(mapHeight()).toBe('470px');

    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));

    expect(drawerHeight()).toBe('340px');
    expect(mapHeight()).toBe('300px');

    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));
    expect(mapHeight()).toBe('470px');
  });

  it('labels the event row as inherited rather than asking again', () => {
    renderMap({ overlayMode: true });
    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));

    const drawer = screen.getByTestId('advanced-filters-content');
    expect(drawer).toHaveTextContent('inherited from the plan card');
    // The quality row keeps saying it is remembered — that one IS a standing preference.
    expect(within(drawer).getByTestId('quality-hint')).toHaveTextContent('saved');
  });

  it('opens collapsed EVERY time, even after the Map tab was left with filters open', () => {
    // The disclosure's own state is deliberately not persisted here. A remembered "open" would
    // restate the answered question on every drill-down from then on.
    localStorage.setItem('mapFiltersOpen', '1');

    renderMap({ overlayMode: true });
    expect(drawerHeight()).toBe('0px');

    // ...and opening it here does not teach the Map tab anything either.
    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));
    expect(localStorage.getItem('mapFiltersOpen')).toBe('1');
  });

  it('still persists the FILTER VALUES changed while the drawer is open', () => {
    // Only the disclosure is forgotten. A threshold the user chose here is the same standing
    // preference it is on the Map tab, and losing it would be a different kind of surprise.
    renderMap({ overlayMode: true });
    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));
    fireEvent.click(screen.getByTestId('star-filter-4'));

    expect(localStorage.getItem('mapFilterMinStars')).toBe('4');
  });
});

describe('MapView Map tab — unchanged by the overlay work', () => {
  it('keeps the full filter rail: event toggles in the primary row, summary on the pill', () => {
    renderMap();

    expect(screen.queryByTestId('map-context-bar')).not.toBeInTheDocument();
    expect(screen.getByTestId('forecast-type-selector')).toBeInTheDocument();
    expect(screen.getByTestId('filter-summary')).toHaveTextContent('3★+');
    expect(mapHeight()).toBe('500px');
  });

  it('still remembers the disclosure across mounts', () => {
    const { unmount } = renderMap();
    fireEvent.click(screen.getByTestId('advanced-filters-toggle'));
    expect(localStorage.getItem('mapFiltersOpen')).toBe('1');
    unmount();

    renderMap();
    expect(screen.getByTestId('advanced-filters-toggle')).toHaveAttribute('aria-expanded', 'true');
  });
});
