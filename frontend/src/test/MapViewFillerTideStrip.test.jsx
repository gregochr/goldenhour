/**
 * `MapView` + the REAL `MapTideStrip` — a D-13 filler window borrows the briefing's served tide.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>The briefing rolls a tide up for every event of its four owned days, but the pane lists only
 * the six it renders (`heat.windows`). The Sunday sunrise, seen from Thursday morning, is the
 * seventh: it became a tide-less D-13 filler row, so `stripModel` hid the strip. The fix is a chain
 * — `WindowFirstMapPane` builds `heat.tideByWindow`, `MapView` hands it to `buildMapEvents`,
 * `solarRow`'s filler branch lends it — and every link is invisible to a test that stubs the next
 * one. This file mounts the real `MapView` and the real strip so deleting any link fails it.
 *
 * <p>Not mocked: `MapView`, `MapTideStrip`, `WindowControl`, `buildMapEvents`, `stripModel`,
 * `buildTideAlignmentIndex`. Mocked: Leaflet, and the two layers that need a real Leaflet pane.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  act, fireEvent, render, screen, within,
} from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

// A viewport that contains everything: the strip's "coastal spot in view" test is not this file's
// subject, so the padded box answers true for any point.
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
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => <div /> }));
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
import { solarWindowKey } from '../utils/mapEvents.js';
import { buildTideAlignmentIndex } from '../utils/locationSheet.js';
import { setRewind } from '../utils/rewind.js';

const TODAY = '2026-01-15';
const X = '2026-01-16'; // the unrendered window: tomorrow's sunrise
const X_EVENT_TIME = `${X}T07:44:00`; // UTC-naive; GMT in January, so it reads 07:44 UK
const LENT_TIME = '07:44';

const SPOT = {
  id: 1, name: 'Bamburgh-0', lat: 55.61, lng: -1.71, rid: 'North East', bortleClass: 4,
};

const TIDE = {
  locationName: 'Bamburgh Castle',
  state: 'LOW',
  direction: 'RISING',
  range: '4.3 m',
  rangeAnomaly: 'about average',
  curve: [0, 0.3, 0.7, 1, 0.6, 0.2, 0],
  windowPosition: 0.32,
  windowLevel: 0.2,
  sunrisePosition: 0.32,
  sunsetPosition: 0.7,
  extremes: [{ kind: 'LW', position: 0.3, time: '07:30' }],
  heightAtWindow: '0.9 m',
};

/** Heat whose windows list has TODAY's sunset only — X is NOT in it, which is the whole setup. */
function heatProp(overrides = {}) {
  return {
    enabled: true,
    hasHome: false,
    spots: [SPOT],
    areaSpots: [SPOT],
    pointsByKey: new Map([[solarWindowKey(TODAY, 'SUNSET'), [{
      id: SPOT.id, name: SPOT.name, lat: SPOT.lat, lng: SPOT.lng, rid: SPOT.rid, r: [5],
    }]]]),
    windows: [{
      key: solarWindowKey(TODAY, 'SUNSET'),
      date: TODAY,
      targetType: 'SUNSET',
      label: 'Tonight sunset',
      time: '16:12',
      bestRating: 5,
      conf: 1,
    }],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
    tideByWindow: new Map([[solarWindowKey(X, 'SUNRISE'), {
      tide: TIDE, eventTime: X_EVENT_TIME, time: LENT_TIME,
    }]]),
    ...overrides,
  };
}

function makeLocation() {
  return {
    id: SPOT.id,
    name: SPOT.name,
    lat: SPOT.lat,
    lon: SPOT.lng,
    regionName: SPOT.rid,
    bortleClass: SPOT.bortleClass,
    locationType: ['LANDSCAPE'],
    // Wants high water: the slot below says the water is LOW at the light, so it is a served miss.
    tideType: ['HIGH'],
    forecastsByDate: new Map([[TODAY, {
      sunset: { rating: 5, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    }]]),
  };
}

/** The per-spot served fact for X — the briefing's slots cover its unrendered windows too. */
function alignmentIndex() {
  return buildTideAlignmentIndex([{
    date: X,
    eventSummaries: [{
      targetType: 'SUNRISE',
      regions: [{
        slots: [{
          locationId: SPOT.id, locationName: SPOT.name, tideState: 'LOW', tideAligned: false,
        }],
      }],
    }],
  }]);
}

/** A briefing slot for the spot at one window, as `buildTideAlignmentIndex` reads it. */
function slotDay(date, targetType, tideState, tideAligned) {
  return {
    date,
    eventSummaries: [{
      targetType,
      regions: [{
        slots: [{
          locationId: SPOT.id, locationName: SPOT.name, tideState, tideAligned,
        }],
      }],
    }],
  };
}

async function renderMap(props = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapView
        locations={[makeLocation()]}
        date={X}
        forecastDates={[TODAY, X]}
        autoEventType="SUNRISE"
        heat={heatProp()}
        tideAlignmentIndex={alignmentIndex()}
        {...props}
      />,
    );
  });
  return result;
}

beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
});
afterEach(() => {
  setRewind(null);
  vi.useRealTimers();
  localStorage.clear();
});

describe('MapView — the next-fit line names the EARLIEST fit, jumping only if the strip can show it', () => {
  const Y = '2026-01-17'; // the day after X; its windows are NOT in heat.windows
  const lentFor = (date, type) => [solarWindowKey(date, type), {
    tide: TIDE, eventTime: `${date}T08:00:00`, time: '08:00',
  }];

  it('a later window that fits but has NO window tide is named as plain text — no button, no ›, no "beyond"', async () => {
    // X is dimmed (LOW water, wants HIGH). Y sunrise is aligned to HIGH by a real slot fact, but the
    // briefing served Y no window tide (absent from tideByWindow), so the strip cannot show it.
    const index = buildTideAlignmentIndex([
      slotDay(X, 'SUNRISE', 'LOW', false),
      slotDay(Y, 'SUNRISE', 'HIGH', true),
    ]);
    await renderMap({ forecastDates: [TODAY, X, Y], tideAlignmentIndex: index });

    const footer = screen.getByTestId('wf-tide-strip-footer');
    const plain = within(footer).getByTestId('wf-tide-strip-nostrip');
    expect(plain.textContent).toMatch(/^Next high water on the light · .+ sunrise$/);
    expect(plain.textContent).not.toContain('›');
    expect(within(footer).queryByRole('button')).toBeNull();
    expect(within(footer).queryByTestId('wf-tide-strip-next')).toBeNull();
    expect(footer).not.toHaveTextContent('beyond these four days');
  });

  it('tideless EARLIER fit, tide-bearing LATER fit: names the earlier one as plain text, no jump', async () => {
    // Y sunrise fits but is tideless; Y sunset fits AND carries a lent tide. The earliest is Y
    // sunrise, as the callout would say, so the strip must not skip to the sunset.
    const index = buildTideAlignmentIndex([
      slotDay(X, 'SUNRISE', 'LOW', false),
      slotDay(Y, 'SUNRISE', 'HIGH', true),
      slotDay(Y, 'SUNSET', 'HIGH', true),
    ]);
    const heat = heatProp();
    heat.tideByWindow.set(...lentFor(Y, 'SUNSET'));
    await renderMap({ forecastDates: [TODAY, X, Y], tideAlignmentIndex: index, heat });

    const footer = screen.getByTestId('wf-tide-strip-footer');
    expect(within(footer).getByTestId('wf-tide-strip-nostrip').textContent)
      .toMatch(/^Next high water on the light · .+ sunrise$/);
    expect(within(footer).queryByRole('button')).toBeNull();
    expect(footer).not.toHaveTextContent('sunset');
  });

  it('when the earliest fit carries a tide it is a real jump button', async () => {
    const index = buildTideAlignmentIndex([
      slotDay(X, 'SUNRISE', 'LOW', false),
      slotDay(Y, 'SUNSET', 'HIGH', true),
    ]);
    const heat = heatProp();
    heat.tideByWindow.set(...lentFor(Y, 'SUNSET'));
    await renderMap({ forecastDates: [TODAY, X, Y], tideAlignmentIndex: index, heat });

    const footer = screen.getByTestId('wf-tide-strip-footer');
    expect(within(footer).getByTestId('wf-tide-strip-next').textContent).toMatch(/sunset ›$/);
    expect(within(footer).queryByTestId('wf-tide-strip-nostrip')).toBeNull();
  });
});

describe('MapView — a filler window borrows the briefing\'s served tide', () => {
  it('shows the strip for a window the pane did not list, with its per-spot fit and next-fit denial', async () => {
    await renderMap();

    const strip = screen.getByTestId('wf-tide-strip');
    expect(strip).toBeInTheDocument();
    expect(strip).toHaveTextContent('Bamburgh Castle');
    // Fit is KNOWN: the spot has a served tier, so it is counted dimmed, not "No per-spot tide fit".
    expect(screen.getByTestId('wf-tide-strip-footer'))
      .toHaveTextContent('1 of 1 coastal spots are dimmed — they want high water');
    expect(screen.queryByText(/No per-spot tide fit/)).toBeNull();
    // The spot is UNRATED for X, so the default 3★ floor would hide it; it is on the map only
    // because a served tide fact for X exempts it (`getTideOnLightForLocation` keys on the
    // briefing's slots, not on the pane's rendered list, so the exemption holds on a filler).
    expect(screen.getByTestId('wf-map-counts-footer')).toHaveTextContent('1 of 1 shown');
    // The next-fit scan found no later row that fits, so the honest denial stands.
    expect(screen.getByTestId('wf-tide-strip-beyond')).toBeInTheDocument();
  });

  it('shows the lent clock time on the window control and the chart', async () => {
    await renderMap();

    // The window menu's option for X itself carries the lent time (not just some element on the
    // page that happens to read 07:44).
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const option = screen.getAllByTestId('wf-win-row')
      .find((el) => el.getAttribute('data-ev-id') === `solar:${X}:SUNRISE`);
    expect(option).toBeTruthy();
    expect(within(option).getByText(LENT_TIME)).toBeInTheDocument();
    // The chart labels the sunrise with the filler's lent time (the sibling-row lookup).
    expect(screen.getByTestId('wf-tide-strip')).toHaveTextContent(LENT_TIME);
  });

  it('states the water and says the fit is unforecast when no in-view spot carries a served tier', async () => {
    // Rated 4★ for X so the 3★ floor keeps the spot on the map, but with no slot fact there is no
    // tier: the strip may state the water and must say nothing about fit.
    const rated = makeLocation();
    rated.forecastsByDate.set(X, {
      sunrise: { rating: 4, solarEventTime: X_EVENT_TIME, fierySkyPotential: 70, goldenHourPotential: 60 },
    });
    await renderMap({ locations: [rated], tideAlignmentIndex: buildTideAlignmentIndex([]) });

    const footer = screen.getByTestId('wf-tide-strip-footer');
    expect(footer).toHaveTextContent('No per-spot tide fit for this window');
    expect(footer).not.toHaveTextContent('No coastal spot here has its water');
    expect(screen.queryByTestId('wf-tide-strip-beyond')).toBeNull();
  });

  it('...and with NO served tide fact for X, an unrated coastal spot is filtered out and the strip stays hidden', async () => {
    // Documents gate (c), which this change deliberately leaves alone: the strip needs a coastal
    // spot IN VIEW after the reader's filters. Without a fact (or a rating) the 3★ floor removes
    // the spot, so the lent tide alone does not bring the strip back.
    await renderMap({ tideAlignmentIndex: buildTideAlignmentIndex([]) });
    expect(screen.getByTestId('wf-map-counts-footer')).toHaveTextContent('0 of 1 shown');
    expect(screen.queryByTestId('wf-tide-strip')).toBeNull();
  });

  it('shows no strip when the briefing served no tide for the window — nothing is synthesised', async () => {
    await renderMap({ heat: heatProp({ tideByWindow: new Map() }) });
    // The spot is still on the map (its served tier keeps it), so the absence is the tide's.
    expect(screen.getByTestId('wf-map-counts-footer')).toHaveTextContent('1 of 1 shown');
    expect(screen.queryByTestId('wf-tide-strip')).toBeNull();
  });

  it('shows no strip for a filler whose window has elapsed', async () => {
    // Live clock past the lent window's event time plus the 30-minute afterglow.
    vi.setSystemTime(new Date(`${X}T08:30:00Z`));
    await renderMap({ date: X, forecastDates: [X] });
    expect(screen.getByTestId('wf-map-counts-footer')).toHaveTextContent('1 of 1 shown');
    expect(screen.queryByTestId('wf-tide-strip')).toBeNull();
  });

  it('...but shows it again under a rewind to before the window, whatever the wall clock says', async () => {
    vi.setSystemTime(new Date(`${X}T08:30:00Z`));
    setRewind(`${TODAY}T20:00:00Z`);
    await renderMap();
    expect(screen.getByTestId('wf-tide-strip')).toBeInTheDocument();
  });

  it('the afterglow boundary is exactly 30 minutes: present AT event+30min, absent one second later', async () => {
    // PlanWindowProjector.AFTERGLOW_MINUTES = 30; `plusMinutes(30).isBefore(now)` is strict.
    vi.setSystemTime(new Date(`${X}T08:14:00Z`));
    const { unmount } = await renderMap({ date: X, forecastDates: [X] });
    expect(screen.getByTestId('wf-tide-strip')).toBeInTheDocument();
    unmount();

    vi.setSystemTime(new Date(`${X}T08:14:01Z`));
    await renderMap({ date: X, forecastDates: [X] });
    expect(screen.getByTestId('wf-map-counts-footer')).toHaveTextContent('1 of 1 shown');
    expect(screen.queryByTestId('wf-tide-strip')).toBeNull();
  });

  it('keeps the afterglow: a window 20 minutes gone is still current', async () => {
    vi.setSystemTime(new Date(`${X}T08:04:00Z`));
    await renderMap({ date: X, forecastDates: [X] });
    expect(screen.getByTestId('wf-tide-strip')).toBeInTheDocument();
  });
});
