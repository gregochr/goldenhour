/**
 * The Map's drive tier is the Plan bar's, and the pill follows it (docs/engineering/reach-scoped-card-plan.md R3, D9).
 *
 * <p><b>What breaks if these fail:</b> the Map keeps its own tier and shows a word for everywhere beside a filter for
 * 45 minutes (the defect this series exists to end); or the scope segment stops narrowing the tier's regions (the pill
 * names a region the reader has scoped away, or a region with nothing in reach); or an unmeasured reader, who opens on
 * the weekday's 45-minute default, has the whole map emptied by a filter with no drive time to gate on.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, render, fireEvent, screen,
} from '@testing-library/react';

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

vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: () => <div data-testid="map-heat-layer" />,
}));

const mapLabelsCalls = [];
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => { mapLabelsCalls.push(props); return <div data-testid="probe-labels" />; },
}));
const drawnNames = () => mapLabelsCalls.at(-1)?.spots?.map((s) => s.name).sort();

vi.mock('../components/map/MapCallout.jsx', () => ({ default: () => <div data-testid="probe-callout" /> }));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
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
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import { buildReachVerdictIndex, buildRegionVerdictIndex } from '../utils/mapVerdict.js';
import { reachLensStub } from './reachLensStub.js';

const TODAY = '2026-01-15';
const NE = 'North East';
const LAKES = 'The Lakes';

const FORECASTS = () => new Map([[TODAY, {
  sunset: { rating: 5, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
}]]);
const LOCATIONS = [
  { id: 1, name: 'Near', lat: 55.61, lon: -1.71, regionName: NE, locationType: ['LANDSCAPE'], forecastsByDate: FORECASTS() },
  { id: 2, name: 'Far', lat: 54.44, lon: -3.3, regionName: LAKES, locationType: ['LANDSCAPE'], forecastsByDate: FORECASTS() },
];
const SPOT = (loc) => ({
  id: loc.id, name: loc.name, lat: loc.lat, lng: loc.lon, regionName: loc.regionName, rid: loc.regionName,
});
const SPOTS = LOCATIONS.map(SPOT);
const REACH = new Map([
  [1, { driveMinutes: 20, distanceMiles: 5, driveStatus: 'MEASURED' }],
  [2, { driveMinutes: 100, distanceMiles: 40, driveStatus: 'MEASURED' }],
]);

const region = (regionName, meanRating, displayVerdict) => ({
  regionName, meanRating, displayVerdict, sampleSufficient: true, slots: [{ canopy: false }],
});
/** The served (whole-region) verdicts: the North East is Worth it, the Lakes Maybe. */
const SERVED = buildRegionVerdictIndex([{
  date: TODAY,
  eventSummaries: [{
    targetType: 'SUNSET',
    regions: [region(NE, 4.4, 'WORTH_IT'), region(LAKES, 3.0, 'MAYBE')],
  }],
}]);

const rv = (regionName, displayVerdict, meanRating) => ({ regionName, displayVerdict, meanRating });
/** A tier's answer for the one window, regions best first. */
const scopeOf = (tierMinutes, regions) => ({
  tierMinutes,
  ...buildReachVerdictIndex([{ date: TODAY, targetType: 'SUNSET', displayVerdict: regions[0]?.displayVerdict, regions }]),
});

function heat(over = {}) {
  return {
    enabled: true,
    hasHome: false,
    spots: SPOTS,
    areaSpots: SPOTS,
    pointsByKey: new Map([[`${TODAY}:SUNSET`, []]]),
    windows: [{
      key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 4, conf: 1,
    }],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
    ...over,
  };
}

async function renderTab(props = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapView
        locations={LOCATIONS}
        date={TODAY}
        autoEventType="SUNSET"
        forecastDates={[TODAY]}
        heat={heat()}
        regionVerdictIndex={SERVED}
        reachById={REACH}
        {...props}
      />,
    );
  });
  await act(async () => { await new Promise((resolve) => { setTimeout(resolve, 0); }); });
  return result;
}

const word = () => screen.getByTestId('wf-win-verdict-word').textContent;
const regionLine = () => screen.getByTestId('wf-win-verdict-region').textContent;

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

describe('the pill follows the shared drive tier', () => {
  it('with no reach answer it is exactly the served verdict, and prints no scope words', async () => {
    await renderTab({ reachLens: reachLensStub(45), reachMeasured: true, reachScope: null });

    expect(word()).toBe('Worth it');
    expect(regionLine()).toBe('North East');
    expect(screen.queryByTestId('wf-win-verdict-scope')).toBeNull();
  });

  it('wears the tier\'s word, region and scope when the answer is held (Everywhere)', async () => {
    await renderTab({
      reachLens: reachLensStub(45),
      reachMeasured: true,
      reachScope: scopeOf(45, [rv(NE, 'STAND_DOWN', 2.0)]),
    });

    expect(word()).toBe('Poor');
    expect(regionLine()).toBe('North East');
    expect(screen.getByTestId('wf-win-verdict-scope')).toHaveTextContent('within 45m');
    expect(screen.getByTestId('wf-win-pill').textContent).toContain('within 45 min');
  });

  it('⚠️ intersects the tier\'s regions with the scope: a region with nothing in reach is neither named nor counted', async () => {
    // The answer lists the Lakes first, but no Lakes place is within 90 minutes of this reader (Far is 100): the
    // scope pool narrowed by the tier holds the North East alone, so the pill is the North East's word, not the Lakes'.
    await renderTab({
      reachLens: reachLensStub(90),
      reachMeasured: true,
      reachScope: scopeOf(90, [rv(LAKES, 'WORTH_IT', 4.6), rv(NE, 'MAYBE', 3.0)]),
    });

    expect(word()).toBe('Maybe');
    expect(regionLine()).toBe('North East');
  });

  it('⚠️ and with My area, the scope\'s regions only: a lead outside the area is not named', async () => {
    // The area holds the North East only; the tier's lead (the Lakes, in reach at 150) is outside it.
    await renderTab({
      reachLens: reachLensStub(150),
      reachMeasured: true,
      heat: heat({ hasHome: true, areaSpots: [SPOTS[0]] }),
      reachScope: scopeOf(150, [rv(LAKES, 'WORTH_IT', 4.6), rv(NE, 'MAYBE', 3.0)]),
    });

    expect(word()).toBe('Maybe');
    expect(regionLine()).toBe('North East');
  });

  it('counts the others sharing the tier over the regions in scope AND in reach', async () => {
    await renderTab({
      reachLens: reachLensStub(150),
      reachMeasured: true,
      reachScope: scopeOf(150, [rv(NE, 'WORTH_IT', 4.4), rv(LAKES, 'WORTH_IT', 4.0)]),
    });

    // Both regions share the tier and both are in scope and in reach: the region line is the scope's own "everywhere".
    expect(regionLine()).toBe('everywhere');
    expect(screen.getByTestId('wf-win-verdict-scope')).toHaveTextContent('within 2h30');
  });
});

describe('one drive state', () => {
  it('a popover tier tap writes the SHARED lens (the Plan bar follows), not a local copy', async () => {
    const lens = reachLensStub(null);
    await renderTab({ reachLens: lens, reachMeasured: true });

    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    fireEvent.click(screen.getByTestId('drive-time-filter-90'));

    expect(lens.selectTier).toHaveBeenCalledTimes(1);
    expect(lens.selectTier).toHaveBeenCalledWith('90');
  });

  it('"Any" in the popover writes Any to the lens', async () => {
    const lens = reachLensStub(45);
    await renderTab({ reachLens: lens, reachMeasured: true });

    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    fireEvent.click(screen.getByTestId('drive-time-filter-0'));

    expect(lens.selectTier).toHaveBeenCalledWith('any');
  });

  it('opens on the Plan\'s tier: the popover shows the shared tier pressed and the map is filtered by it', async () => {
    await renderTab({ reachLens: reachLensStub(45), reachMeasured: true });

    expect(drawnNames()).toEqual(['Near']);
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(screen.getByTestId('drive-time-filter-45')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('wf-filters-chip')).toHaveTextContent('Filters (1)');
  });

  it('Any draws every place', async () => {
    await renderTab({ reachLens: reachLensStub(null), reachMeasured: true });

    expect(drawnNames()).toEqual(['Far', 'Near']);
  });

  it('⚠️ a reader whose drives are unmeasured is not emptied by the tier they open on', async () => {
    await renderTab({ reachLens: reachLensStub(45), reachMeasured: false, reachById: new Map() });

    expect(drawnNames()).toEqual(['Far', 'Near']);
    expect(screen.getByTestId('wf-filters-chip')).not.toHaveTextContent('Filters (');
  });

  it('a drive-locked reader is forced to Any whatever the shared tier says', async () => {
    await renderTab({ reachLens: reachLensStub(45), reachMeasured: true, driveLocked: true });

    expect(drawnNames()).toEqual(['Far', 'Near']);
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(screen.getByTestId('drive-time-filter-0')).toHaveAttribute('aria-pressed', 'true');
  });

  it('a standalone mount (no shared lens) keeps a local tier pinned to Any until one is chosen', async () => {
    await renderTab({});

    expect(drawnNames()).toEqual(['Far', 'Near']);
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    fireEvent.click(screen.getByTestId('drive-time-filter-45'));
    expect(screen.getByTestId('drive-time-filter-45')).toHaveAttribute('aria-pressed', 'true');
    expect(drawnNames()).toEqual(['Near']);
  });
});
