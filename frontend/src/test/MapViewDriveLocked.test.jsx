/**
 * The Map's drive-time filter under `driveLocked` (docs/engineering/pro-features-gate-plan.md §9 D1):
 * a LITE reader sees the segment greyed with a real `disabled` and a Pro pill, and the filter is
 * forced to "Any" where it is APPLIED, so a Plan handoff's limit (or any stale value) can never hide
 * a spot for them. The badge and the breadcrumb read the effective value. Unlocked is unchanged.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, render, fireEvent, screen,
} from '@testing-library/react';

// A minimal working `L.Control`, `L.DomEvent` and a document-attached corner container — the same
// miniature `MapViewCentreOnHome.test.jsx` uses — so `CentreOnHomeControl`'s `⌂` portal actually
// lands somewhere `screen` can find it (D1's "the ⌂ control still present while away" case needs a
// real query, not a detached node).
let cornerEl;
vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });

  class Control {
    constructor(options = {}) { this.options = options; }

    addTo(map) {
      this._container = this.onAdd(map);
      this._container.classList.add('leaflet-control');
      map._corner.appendChild(this._container);
      return this;
    }

    remove() {
      this._container?.remove();
      return this;
    }
  }

  const DomEvent = {
    disableClickPropagation: () => {},
    disableScrollPropagation: () => {},
  };
  const L = { icon, divIcon, point, Control, DomEvent };
  return { default: L, ...L };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
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
    addControl: () => {},
    _corner: cornerEl,
  }),
}));


/** Captures every props object `MapView` has ever handed `MapHeatLayer` — D1's `homeGeo` gate is
 * asserted off `homeCoords` here, the same "read what MapView actually passed" idiom
 * `MapViewReachMeasured.test.jsx` uses for `MapLabels`. */
const mapHeatLayerCalls = [];
vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: (props) => { mapHeatLayerCalls.push(props); return <div data-testid="map-heat-layer" />; },
}));

/** The probe: a button that selects a named spot, exactly like `MapViewSelectionOrdering.test.jsx`'s
 * own mock — ALSO captures every props object handed to it, for the same D1 `homeGeo` assertion. */
const mapLabelsCalls = [];
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => {
    mapLabelsCalls.push(props);
    return (
      <div>
        {['Near', 'Far-home-only'].map((name) => (
          <button key={name} type="button" data-testid={`probe-chip-${name}`} onClick={() => props.onSelect(name)}>
            {name}
          </button>
        ))}
      </div>
    );
  },
}));

/** How many places the label layer was last handed: the places the map draws. */
const drawnSpotCount = () => mapLabelsCalls.at(-1)?.spots?.length;

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

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'LITE_USER' }) }));
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

function heatProp(driveOverrideById, spots = []) {
  return {
    enabled: true,
    hasHome: false,
    spots,
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


const SPOTS = makeLocations().map((l) => ({
  id: l.id, name: l.name, lat: l.lat, lng: l.lon, regionName: l.regionName, rid: l.regionName,
}));
const REACH = new Map([
  [NEAR_ID, { driveMinutes: 20, distanceMiles: 5 }],
  [FAR_HOME_ONLY_ID, { driveMinutes: 100, distanceMiles: 40 }],
]);
const DOOR = {
  source: 'plan', eventType: 'SUNSET', date: TODAY, region: null,
  minRating: 3, limitMinutes: 45, locationName: null, nonce: 1,
};
const heat = () => ({ ...heatProp(undefined, SPOTS), areaSpots: SPOTS });

beforeEach(() => {
  localStorage.clear();
  mapLabelsCalls.length = 0;
  cornerEl = document.createElement('div');
  cornerEl.className = 'leaflet-bottom leaflet-right';
  document.body.appendChild(cornerEl);
});

afterEach(() => {
  localStorage.clear();
  cornerEl.remove();
  vi.clearAllMocks();
});

describe('MapView — the drive-time filter for a locked reader', () => {
  it('draws the segment greyed, really disabled, with the Pro pill outside the grey', async () => {
    await renderTab({ driveLocked: true, reachById: REACH, heat: heat() });
    fireEvent.click(screen.getByTestId('wf-filters-chip'));

    const group = screen.getByTestId('drive-time-filter-group');
    expect(group).toHaveClass('wf-lens-locked');
    for (const value of [0, 45, 90, 150]) {
      expect(screen.getByTestId(`drive-time-filter-${value}`)).toBeDisabled();
    }
    const pill = screen.getByTestId('drive-time-filter-upsell');
    expect(pill).toHaveTextContent('Pro');
    expect(group.contains(pill)).toBe(false);
    // Effectively Any.
    expect(screen.getByTestId('drive-time-filter-0')).toHaveAttribute('aria-pressed', 'true');
  });

  it('a Plan handoff limit does not hide spots, and the badge and breadcrumb read Any', async () => {
    await renderTab({
      driveLocked: true, reachById: REACH, heat: heat(), planHandoff: DOOR,
    });
    // 100 minutes is beyond the carried 45-minute limit, yet nothing is hidden.
    expect(drawnSpotCount()).toBe(2);
    expect(screen.getByTestId('wf-filters-chip')).toHaveTextContent(/^Filters(?! \()/);
    expect(screen.getByTestId('wf-map-breadcrumb-carrying').textContent).not.toMatch(/within/);
  });

  it('an away origin with region-base minutes and a carried limit still hides nothing', async () => {
    const driveOverrideById = new Map([
      [NEAR_ID, { driveMinutes: 20, distanceMiles: null }],
      [FAR_HOME_ONLY_ID, { driveMinutes: 100, distanceMiles: null }],
    ]);
    const away = { id: 'lakes', name: 'Lake District', baseName: 'Keswick' };
    await renderTab({
      driveLocked: true, origin: away, planHandoff: DOOR, heat: { ...heatProp(driveOverrideById, SPOTS), areaSpots: SPOTS },
    });
    expect(drawnSpotCount()).toBe(2);
  });

  it('PRO (unlocked) is unchanged: the same handoff limit narrows the map and counts as a filter', async () => {
    await renderTab({
      driveLocked: false, reachById: REACH, heat: heat(), planHandoff: DOOR,
    });
    expect(drawnSpotCount()).toBe(1);
    expect(screen.getByTestId('wf-filters-chip')).toHaveTextContent('Filters (1)');
    expect(screen.getByTestId('wf-map-breadcrumb-carrying').textContent).toMatch(/within/);
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(screen.getByTestId('drive-time-filter-45')).not.toBeDisabled();
    expect(screen.queryByTestId('drive-time-filter-upsell')).toBeNull();
  });
});
