/**
 * The 2026-10-08 defect, end to end: a window the honesty filter blanked still shows its coast.
 *
 * <h2>The shape this pins (docs/engineering/window-tide-facts-plan.md §1, P2)</h2>
 *
 * <p>Sunday's sunrise is a RENDERED window: the briefing serves it with a representative tide
 * (`row.tide`) and `window.tideFacts` for every coastal location. But `BriefingHonestyFilter` emptied
 * every region's `slots` (zero Claude coverage is the designed state of an unscored window), so the
 * pre-fix index, which walked slots, found no coastal location: the 3★ floor removed them all and
 * the strip had nothing to show. Reading `window.tideFacts` lifts all of that.
 *
 * <p>Not mocked: `MapView`, `MapTideStrip`, `WindowControl`, `buildMapEvents`, `stripModel`,
 * `buildTideAlignmentIndex`. Mocked: Leaflet, the heat/pin layers, and `MapLabels`, replaced by a
 * stub that records the chip spots it was handed (the chip's tier is a property of those spots).
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';

const labelProps = vi.hoisted(() => ({ spots: [] }));

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

const VIEWPORT = { pad: () => ({ contains: () => true }) };
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
  Marker: React.forwardRef(function MockMarker({ children }, ref) {
    React.useImperativeHandle(ref, () => ({ openPopup: () => {} }));
    return <div>{children}</div>;
  }),
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    getZoom: () => 9,
    getBounds: () => VIEWPORT,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
  }),
}));

vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: ({ spots }) => {
    labelProps.spots = spots;
    return <div data-testid="stub-labels" />;
  },
}));
vi.mock('../components/map/PinsLayer.jsx', () => ({ default: () => null }));

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

import MapView from '../components/MapView.jsx';
import { windowKey } from '../utils/windowKeys.js';
import { buildTideAlignmentIndex } from '../utils/locationSheet.js';
import { buildWindowTideIndex } from '../utils/mapTideFit.js';
import { setRewind } from '../utils/rewind.js';

const TODAY = '2026-10-08';
const SUN = '2026-10-11';
const EVENT_TIME = `${SUN}T05:50:00`;

const TIDE = {
  locationName: 'St. Mary\'s Lighthouse',
  state: 'LOW',
  direction: 'RISING',
  range: '4.3 m',
  rangeAnomaly: 'about average',
  curve: [0, 0.3, 0.7, 1, 0.6, 0.2, 0],
  windowPosition: 0.32,
  windowLevel: 0.2,
  sunrisePosition: 0.32,
  sunsetPosition: 0.7,
  extremes: [{ kind: 'LW', position: 0.3, time: '05:30' }],
  heightAtWindow: '0.9 m',
};

const loc = (id, name, tideType) => ({
  id, name, lat: 55.6 + id / 100, lon: -1.7, regionName: 'North East', bortleClass: 4,
  locationType: ['LANDSCAPE'], tideType, forecastsByDate: new Map(),
});

/** Bamburgh has a fact; Seahouses is coastal with NO fact; Alnwick is inland. */
const LOCATIONS = [loc(1, 'Bamburgh', ['HIGH']), loc(2, 'Seahouses', ['HIGH']), loc(3, 'Alnwick', [])];

/**
 * The production shape of a blanked window: a window with its representative tide AND the facts,
 * with every region's slots empty.
 */
const DAYS = [{
  date: SUN,
  eventSummaries: [{
    targetType: 'SUNRISE',
    regions: [{ regionName: 'North East', slots: [] }],
    unregioned: [],
    window: {
      eventTime: EVENT_TIME,
      tide: TIDE,
      tideFacts: [{
        locationId: 1,
        locationName: 'Bamburgh',
        tideState: 'LOW',
        tideAligned: false,
        tideFitPhrase: 'wants high water · low tide, rising at 05:30 · 0.9 m of 4.3 m',
        tideShortfall: 'HIGHER',
      }],
    },
  }],
}];

function heat() {
  const spots = LOCATIONS.map((l) => ({
    id: l.id, name: l.name, lat: l.lat, lng: l.lon, rid: l.regionName, bortleClass: 4,
  }));
  return {
    enabled: true,
    hasHome: false,
    spots,
    areaSpots: spots,
    pointsByKey: new Map(),
    windows: [{
      key: windowKey(SUN, 'SUNRISE'),
      date: SUN,
      targetType: 'SUNRISE',
      label: 'Sun sunrise',
      time: '05:50',
      bestRating: null,
      conf: null,
      tide: TIDE,
    }],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
    tideByWindow: buildWindowTideIndex(DAYS, () => '05:50'),
  };
}

async function renderMap(days = DAYS) {
  await act(async () => {
    render(
      <MapView
        locations={LOCATIONS}
        date={SUN}
        forecastDates={[TODAY, SUN]}
        autoEventType="SUNRISE"
        heat={heat()}
        tideAlignmentIndex={buildTideAlignmentIndex(days)}
      />,
    );
  });
}

beforeEach(() => {
  localStorage.clear();
  labelProps.spots = [];
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
});
afterEach(() => {
  setRewind(null);
  vi.useRealTimers();
  localStorage.clear();
});

describe('MapView — window.tideFacts on a window whose regions carry no slots', () => {
  it('keeps the coastal location with a fact through the 3★ floor, and says nothing is rated', async () => {
    await renderMap();
    const footer = screen.getByTestId('wf-map-counts-footer');
    expect(footer.textContent).toMatch(/1\s+of\s+3\s+shown\s*·\s*0 rated/);
    expect(labelProps.spots.map((s) => s.name)).toEqual(['Bamburgh']);
  });

  it('gives its chip a tide tier, and an unassessed one (no sky rating, no star)', async () => {
    await renderMap();
    const [spot] = labelProps.spots;
    expect(spot.tideTier).toBe('miss');
    expect(spot.tideFitPhrase).toMatch(/wants high water/);
    expect(spot.tideShortfall).toBe('HIGHER');
    expect(spot.rating).toBeNull();
    expect(spot.tideAssessed).toBe(false);
  });

  it('shows the strip with its dimmed count', async () => {
    await renderMap();
    expect(screen.getByTestId('wf-tide-strip')).toBeInTheDocument();
    expect(screen.getByTestId('wf-tide-strip-footer'))
      .toHaveTextContent('1 of 1 coastal spots are dimmed — they want high water');
  });

  it('gives a coastal location with NO fact no tide: it is filtered out, not shown as a miss', async () => {
    await renderMap();
    expect(labelProps.spots.some((s) => s.name === 'Seahouses')).toBe(false);
    expect(labelProps.spots.some((s) => s.name === 'Alnwick')).toBe(false);
  });

  it('with no tideFacts at all (a pre-deploy payload) draws as an unscored window: no strip, no coast', async () => {
    const stale = [{
      date: SUN,
      eventSummaries: [{
        targetType: 'SUNRISE',
        regions: [{ regionName: 'North East', slots: [] }],
        window: { eventTime: EVENT_TIME, tide: TIDE },
      }],
    }];
    await renderMap(stale);
    expect(screen.getByTestId('wf-map-counts-footer').textContent).toMatch(/0\s+of\s+3\s+shown/);
    expect(screen.queryByTestId('wf-tide-strip')).toBeNull();
  });
});
