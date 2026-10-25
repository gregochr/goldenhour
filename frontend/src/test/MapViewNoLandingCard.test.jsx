/**
 * The Map tab has no landing card (`docs/engineering/map-landing-nocard-plan.md` P1, spec check 7).
 *
 * <p>The "Tonight, or tomorrow?" card, its once-per-run key, its document `Escape` listener and the
 * pill menu's `↺ Back to …` reopen row are all gone. This file pins that, and pins what had to
 * survive the removal because other code reads it:
 *
 * <ul>
 *   <li>the card never appears (load, `Escape` at the body, `Escape` inside the pane, a menu opened
 *   and closed), and the menu now opens on the windows and ends with the drilldown row;</li>
 *   <li>⚠️ the absence is not vacuous: the SAME fixture still draws the pill and its verdict cell;</li>
 *   <li>`Escape` still closes the window panel and still clears a selected location, fired at
 *   `document.activeElement` inside the pane and never at the panel node (a key aimed at the node
 *   itself reaches the handler by a route a real keyboard never takes);</li>
 *   <li>the phone sheet's Windows heading still reads the solar header, now derived at the `MapView`
 *   level from `landingHeader(landingRows(...))` instead of from the card's model.</li>
 * </ul>
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
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
    getContainer: () => ({ clientHeight: 500, clientWidth: 800 }),
  }),
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'ADMIN' }) }));

let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
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
vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));

/** The probes: the callout is a bare marker (is a location selected?), and the label layer is a
 * button that selects the fixture spot, as `MapViewSelectionOrdering.test.jsx` does. */
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: () => <div data-testid="probe-callout" />,
}));
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => (
    <button type="button" data-testid="probe-chip" onClick={() => props.onSelect('Bamburgh')}>
      chip
    </button>
  ),
}));

import MapView from '../components/MapView.jsx';
import { buildRegionVerdictIndex } from '../utils/mapVerdict.js';

const TODAY = '2026-01-15';

const SPOT = {
  id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'The Lakes', regionName: 'The Lakes',
};

function makeLocation() {
  return {
    id: SPOT.id,
    name: SPOT.name,
    lat: SPOT.lat,
    lon: SPOT.lng,
    regionName: SPOT.rid,
    bortleClass: 4,
    locationType: ['LANDSCAPE'],
    forecastsByDate: new Map([[TODAY, {
      sunrise: { rating: 4, solarEventTime: `${TODAY}T08:24:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
      sunset: { rating: 4, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    }]]),
  };
}

/** Two served solar windows on one day, each with a served regional verdict. */
function heatProp() {
  return {
    enabled: true,
    hasHome: false,
    spots: [SPOT],
    areaSpots: [SPOT],
    pointsByKey: new Map(['SUNRISE', 'SUNSET'].map((kind) => [`${TODAY}:${kind}`, [{
      id: SPOT.id, name: SPOT.name, lat: SPOT.lat, lng: SPOT.lng, rid: SPOT.rid, r: [4],
    }]])),
    windows: [
      { key: `${TODAY}:SUNRISE`, date: TODAY, targetType: 'SUNRISE', label: 'This morning', time: '08:24', bestRating: 4, conf: 1 },
      { key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 4, conf: 1 },
    ],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
  };
}

const DAYS = [{
  date: TODAY,
  eventSummaries: [
    {
      targetType: 'SUNRISE',
      regions: [{ regionName: 'The Lakes', meanRating: 4.4, displayVerdict: 'WORTH_IT', slots: [{ canopy: false }] }],
    },
    {
      targetType: 'SUNSET',
      regions: [{ regionName: 'The Lakes', meanRating: 2.9, displayVerdict: 'MAYBE', slots: [{ canopy: false }] }],
    },
  ],
}];

async function renderMap(props = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapView
        locations={[makeLocation()]}
        date={TODAY}
        autoEventType={null}
        heat={heatProp()}
        regionVerdictIndex={buildRegionVerdictIndex(DAYS)}
        // MapView ignores `runId` now, but the old card opened only when a run stamp was set, so a
        // restored card would open on this fixture. `mapLandingSeenRun` stays unset.
        runId="2026-01-15T04:00:00"
        {...props}
      />,
    );
  });
  return result;
}

beforeEach(() => {
  mockIsMobile = false;
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T05:00:00Z`));
});
afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
  vi.useRealTimers();
});

const noCard = () => expect(screen.queryByTestId('wf-land')).toBeNull();

describe('the Map tab has no landing card (desktop)', () => {
  it('draws no card at load, after Escape at the body or inside the pane, or after the menu opens and closes', async () => {
    await renderMap();
    // ⚠️ Non-vacuous: the same fixture still draws the pill and its verdict cell, so the absence below
    // is the card being gone and not the whole control failing to render.
    const pill = screen.getByTestId('wf-win-pill');
    expect(pill).toBeInTheDocument();
    expect(screen.getByTestId('wf-win-verdict')).toBeInTheDocument();
    noCard();

    fireEvent.keyDown(document.body, { key: 'Escape' });
    noCard();

    pill.focus();
    expect(screen.getByTestId('wf-map-pane').contains(document.activeElement)).toBe(true);
    fireEvent.keyDown(document.activeElement, { key: 'Escape' });
    noCard();

    fireEvent.click(pill);
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
    noCard();
    fireEvent.keyDown(document.activeElement, { key: 'Escape' });
    expect(screen.queryByTestId('wf-win-menu')).toBeNull();
    noCard();
    expect(screen.getByTestId('wf-win-pill')).toBeInTheDocument();
  });

  it('opens the pill menu on the windows, with no reopen row, and ends it with the drilldown row', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));

    const menu = screen.getByTestId('wf-win-menu');
    expect(screen.queryByTestId('wf-win-landing')).toBeNull();
    // No EARLIER group on this fixture, so the listbox of window rows is the menu's first child.
    expect(menu.firstElementChild).toBe(screen.getByTestId('wf-win-listbox'));
    const rows = menu.querySelectorAll('[data-ev-id]');
    expect(rows.length).toBeGreaterThanOrEqual(2);
    expect(rows[0].getAttribute('data-ev-id')).toContain('SUNRISE');
    expect(menu.lastElementChild).toBe(screen.getByTestId('wf-win-more'));
  });

  it('Escape, fired at the focused element in the pane, still closes the window panel', async () => {
    await renderMap();
    const pill = screen.getByTestId('wf-win-pill');
    pill.focus();
    fireEvent.click(pill);
    fireEvent.click(screen.getByTestId('wf-win-more'));
    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
    expect(screen.getByTestId('wf-map-pane').contains(document.activeElement)).toBe(true);

    fireEvent.keyDown(document.activeElement, { key: 'Escape' });

    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
  });

  it('Escape, fired at the focused element in the pane, still clears a selected location', async () => {
    await renderMap();
    const chip = screen.getByTestId('probe-chip');
    fireEvent.click(chip);
    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();
    chip.focus();
    expect(screen.getByTestId('wf-map-pane').contains(document.activeElement)).toBe(true);

    fireEvent.keyDown(document.activeElement, { key: 'Escape' });

    expect(screen.queryByTestId('probe-callout')).toBeNull();
  });
});

describe('the phone sheet still prints the solar header (now derived at the MapView level)', () => {
  it('reads the same-day form for two windows on one date', async () => {
    mockIsMobile = true;
    await renderMap();
    noCard();

    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });

    const windows = screen.getByTestId('wf-map-peek-windows');
    expect(windows.querySelector('h3')).toHaveTextContent('This morning — sunrise or sunset?');
  });
});
