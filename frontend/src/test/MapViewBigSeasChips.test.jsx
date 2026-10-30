/**
 * `MapView` + the real chip list, legend and phone Layers row for the big-seas chips (big-seas plan
 * F2): the one chip list the label layer draws, the key that shares its condition, and the rule that
 * withholds an Ask-picked place's chip (the selected place keeps its own).
 *
 * <p>`MapLabels` is a probe that records the props `MapView` hands it (its own
 * pixels are `MapLabelsBigSeas.test.jsx`'s). Not mocked: `MapView`,
 * `MapLegendPanel`, `buildMapEvents`, `bigSeasFor`. Saturday 24 Oct 2026 is still BST; the wall clock
 * is frozen on Thursday 22 Oct, so both Saturday events are ahead.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen,
} from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

const VIEWPORT = { pad: () => ({ contains: () => true }) };
let mockZoom = 9;
const zoomHandlers = [];
const mockMapInstance = { getZoom: () => mockZoom };
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: (handlers) => {
    zoomHandlers.push(handlers);
    return mockMapInstance;
  },
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    getZoom: () => mockZoom,
    getBounds: () => VIEWPORT,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
  }),
}));

vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));
const labelProps = { last: null, extra: null };
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => {
    labelProps.last = props;
    return <div data-testid="map-labels-stub">{labelProps.extra}</div>;
  },
}));

let mockRole = 'PRO_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));
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
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: (props) => (
    <button type="button" data-testid="map-callout-close" onClick={props.onClose}>close</button>
  ),
}));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import { getAstroAvailableDates } from '../api/astroApi.js';
import { windowKey } from '../utils/windowKeys.js';
import { SATURDAY } from './bigSeasFixtures.js';

const TODAY = '2026-10-22';
const SAT = '2026-10-24';
const KEY_TEXT = 'waves onto a pier or lighthouse at high water · ringed when high water is on the light';

const SPOT = {
  id: 1, name: 'Bamburgh-0', lat: 55.61, lng: -1.71, rid: 'North East', bortleClass: 4,
};

function heatProp() {
  return {
    enabled: true,
    hasHome: false,
    spots: [SPOT],
    areaSpots: [SPOT],
    pointsByKey: new Map([[windowKey(TODAY, 'SUNSET'), [{
      id: SPOT.id, name: SPOT.name, lat: SPOT.lat, lng: SPOT.lng, rid: SPOT.rid, r: [5],
    }]]]),
    windows: [{
      key: windowKey(TODAY, 'SUNSET'),
      date: TODAY,
      targetType: 'SUNSET',
      label: 'Tonight sunset',
      time: '18:12',
      bestRating: 5,
      conf: 1,
    }],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
    tideByWindow: new Map(),
  };
}

/** Bamburgh is rated tonight; the three structures carry NO forecast at all (as a stood-down place does). */
function makeLocations() {
  const base = {
    regionName: 'North East', bortleClass: 4, locationType: ['LANDSCAPE'], tideType: ['HIGH'],
  };
  return [
    {
      ...base,
      id: SPOT.id,
      name: SPOT.name,
      lat: SPOT.lat,
      lon: SPOT.lng,
      forecastsByDate: new Map([
        [TODAY, {
          sunset: { rating: 5, solarEventTime: `${TODAY}T17:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
        }],
        [SAT, {
          sunrise: { rating: 4, solarEventTime: `${SAT}T06:20:00`, fierySkyPotential: 60, goldenHourPotential: 60 },
          sunset: { rating: 4, solarEventTime: `${SAT}T16:47:00`, fierySkyPotential: 60, goldenHourPotential: 60 },
        }],
      ]),
    },
    {
      ...base, id: 243, name: 'Roker Pier & Lighthouse', lat: 54.92, lon: -1.36, forecastsByDate: new Map(),
    },
    {
      ...base, id: 28, name: "St. Mary's Lighthouse", lat: 55.07, lon: -1.44, forecastsByDate: new Map(),
    },
    {
      ...base, id: 21, name: 'Amble', lat: 55.33, lon: -1.58, forecastsByDate: new Map(),
    },
  ];
}

const LOCATIONS = makeLocations();

/** The pane owns the date; this stands in for it, so the line's "See Saturday" moves the map. */
function Pane(props) {
  const [date, setDate] = React.useState(TODAY);
  return (
    <MapView
      locations={LOCATIONS}
      date={date}
      forecastDates={[TODAY, SAT]}
      autoEventType="SUNSET"
      heat={heatProp()}
      bigSeas={[SATURDAY]}
      onSelectDate={(d) => setDate(d)}
      {...props}
    />
  );
}

/** Renders Thursday and follows the line's "See Saturday" to the Saturday sunset (full, aligned). */
async function renderMap(props = {}, { stay = false } = {}) {
  let result;
  await act(async () => { result = render(<Pane {...props} />); });
  if (!stay && screen.queryByTestId('wf-big-seas-go')) {
    await act(async () => { fireEvent.click(screen.getByTestId('wf-big-seas-go')); });
  }
  return result;
}

/** Steps from the Saturday sunset to the Saturday sunrise through the week menu. */
async function chooseSaturdaySunrise() {
  await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
  const row = screen.getAllByTestId('wf-win-row').find((r) => r.getAttribute('data-ev-id') === `solar:${SAT}:SUNRISE`);
  await act(async () => { fireEvent.click(row); });
}

const openLegendIfAny = () => {
  const chip = screen.queryByTestId('wf-legend-chip');
  if (chip) fireEvent.click(chip);
};
const openLegend = () => fireEvent.click(screen.getByTestId('wf-legend-chip'));
const chipNames = (probe) => (probe.last?.bigSeasChips ?? []).map((c) => c.name);

beforeEach(() => {
  localStorage.clear();
  mockRole = 'PRO_USER';
  mockIsMobile = false;
  mockZoom = 9;
  zoomHandlers.length = 0;
  getAstroAvailableDates.mockResolvedValue([]);
  labelProps.last = null;
  labelProps.extra = null;
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe('MapView — the big-seas chips', () => {
  it('hands the label layer a chip per served structure, joined by id, ringed on the aligned event', async () => {
    await renderMap();
    // Roker, St Mary's and Amble have no forecast and no rating at all: the chips come from the roster.
    expect(chipNames(labelProps)).toEqual(['Roker Pier & Lighthouse', "St. Mary's Lighthouse", 'Amble']);
    expect(labelProps.last.bigSeasChips.every((c) => c.ringed)).toBe(true);
  });

  it('does not ring the chips on the day\'s other solar event', async () => {
    await renderMap();
    await chooseSaturdaySunrise();
    expect(labelProps.last.bigSeasChips).toHaveLength(3);
    expect(labelProps.last.bigSeasChips.every((c) => c.ringed === false)).toBe(true);
  });

  it('shows to a LITE reader too (owner Q8: all roles)', async () => {
    mockRole = 'LITE_USER';
    await renderMap();
    expect(labelProps.last.bigSeasChips).toHaveLength(3);
  });

  it('hands none when no big-seas day is served, and on a window the day is not on', async () => {
    await renderMap({ bigSeas: undefined });
    expect(labelProps.last.bigSeasChips).toHaveLength(0);
  });

  it('hands none from zoom 13 up', async () => {
    mockZoom = 13;
    await renderMap();
    expect(labelProps.last.bigSeasChips).toHaveLength(0);
  });

  it('closeCallout returns focus to the VISIBLE selected label, never to a hidden one that comes first', async () => {
    // A selected big-seas chip the placer has hidden (display: none) precedes the visible replacement
    // in the DOM; the lookup must skip it or focus is lost to <body>.
    labelProps.extra = (
      <>
        <button type="button" data-testid="map-label-chip" data-selected="true" style={{ display: 'none' }}>hidden</button>
        <button type="button" data-testid="map-label-chip" data-selected="true">visible</button>
      </>
    );
    await renderMap();
    await act(async () => { labelProps.last.onSelect(SPOT.name); });
    const close = await screen.findByTestId('map-callout-close');
    await act(async () => { fireEvent.click(close); });
    expect(document.activeElement).toBe(screen.getByText('visible'));
  });

  it('the SELECTED place KEEPS its chip (the pressed button stays mounted; only an Ask pick displaces one)', async () => {
    await renderMap();
    await act(async () => { labelProps.last.onSelect('Roker Pier & Lighthouse'); });
    expect(chipNames(labelProps)).toEqual(['Roker Pier & Lighthouse', "St. Mary's Lighthouse", 'Amble']);
    expect(labelProps.last.selectedName).toBe('Roker Pier & Lighthouse');
  });

  it('hands none on a night row of the same Saturday', async () => {
    getAstroAvailableDates.mockResolvedValue([SAT]);
    await renderMap();
    expect(labelProps.last.bigSeasChips).toHaveLength(3);
    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
    const row = screen.getAllByTestId('wf-win-row').find((r) => r.getAttribute('data-ev-id') === `astro:${SAT}:ASTRO`);
    await act(async () => { fireEvent.click(row); });
    expect(labelProps.last.bigSeasChips).toHaveLength(0);
    openLegendIfAny();
    expect(screen.queryByTestId('wf-legend-bigseas')).toBeNull();
  });

  it('withholds an ASK PICK\'s chip', async () => {
    await renderMap({
      askPicks: [{
        rank: 1, locationId: 21, name: 'Amble', shortWindow: 'Sat 17:47', rating: 4, verdict: 'MAYBE', label: 'Amble',
      }],
    });
    expect(chipNames(labelProps)).toEqual(['Roker Pier & Lighthouse', "St. Mary's Lighthouse"]);
  });

});

describe('MapView — the big-seas legend key', () => {
  it('Heat: the key is in the Legend panel while chips are drawn', async () => {
    await renderMap();
    openLegend();
    expect(screen.getByTestId('wf-legend-bigseas')).toHaveTextContent(KEY_TEXT);
  });

  it('Heat: no key when no chips are drawn (nothing served, or street level)', async () => {
    const { unmount } = await renderMap({ bigSeas: undefined });
    openLegend();
    expect(screen.queryByTestId('wf-legend-bigseas')).toBeNull();
    unmount();
    mockZoom = 13;
    await renderMap();
    openLegend();
    expect(screen.queryByTestId('wf-legend-bigseas')).toBeNull();
  });

  it('Heat: no key once the only chip is an Ask pick\'s (the chip list is the key\'s condition)', async () => {
    await renderMap({
      bigSeas: [{ ...SATURDAY, structures: [SATURDAY.structures[0]] }],
      askPicks: [{
        rank: 1, locationId: 243, name: 'Roker Pier & Lighthouse', shortWindow: 'x', rating: 4, verdict: 'MAYBE', label: 'p',
      }],
    });
    expect(labelProps.last.bigSeasChips).toHaveLength(0);
    openLegend();
    expect(screen.queryByTestId('wf-legend-bigseas')).toBeNull();
  });

  it('no key without the heat layer offered, on a full day (desktop and phone)', async () => {
    const { unmount } = await renderMap({ heat: { ...heatProp(), enabled: false } });
    expect(labelProps.last).toBeNull();
    expect(screen.queryByTestId('wf-legend-chip')).toBeNull();
    unmount();
    mockIsMobile = true;
    await renderMap({ heat: { ...heatProp(), enabled: false } });
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));
    expect(screen.queryByTestId('wf-map-peek-bigseas')).toBeNull();
  });

  it('phone: no Layers row at street level, where the day is served but no chip is drawn', async () => {
    mockIsMobile = true;
    mockZoom = 13;
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));
    expect(screen.queryByTestId('wf-map-peek-bigseas')).toBeNull();
  });

  it('phone: the Layers section carries the key, and not without chips', async () => {
    mockIsMobile = true;
    const { unmount } = await renderMap();
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));
    expect(screen.getByTestId('wf-map-peek-bigseas')).toHaveTextContent(KEY_TEXT);
    unmount();

    await renderMap({ bigSeas: undefined });
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));
    expect(screen.queryByTestId('wf-map-peek-bigseas')).toBeNull();
  });

  it('phone: mounts no desktop Legend panel for the key', async () => {
    mockIsMobile = true;
    await renderMap();
    expect(screen.queryByTestId('wf-legend-chip')).toBeNull();
  });
});
