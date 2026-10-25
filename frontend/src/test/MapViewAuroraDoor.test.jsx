/**
 * The Lite aurora Pro door at the `MapView` integration level (docs/engineering/aurora-pro-door-plan.md §2.2).
 *
 * <p><b>What breaks if these fail:</b> a Lite reader gets no way to learn aurora is a Pro feature, or a door for
 * a night that is over; a Pro or Admin reader sees a door (or makes the request); the phone and the desktop
 * draw the door together, or the other width's DOM changes.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, render, screen, fireEvent,
} from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

const mockMapInstance = {
  getZoom: () => 9,
  getBounds: () => ({ pad: () => ({ contains: () => false }) }),
};
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => mockMapInstance,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500, offsetWidth: 800, parentElement: document.body }),
    getZoom: () => 9,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
    panInside: () => {},
    getSize: () => ({ x: 390, y: 844 }),
    latLngToContainerPoint: () => ({ x: 195, y: 400 }),
  }),
}));

let mockRole = 'LITE_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn(),
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
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
vi.mock('../components/map/MapCallout.jsx', () => ({ default: () => null }));
vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => null }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import { getAuroraNights } from '../api/auroraApi.js';
import { setRewind } from '../utils/rewind.js';

const TODAY = '2026-01-15';
const YESTERDAY = '2026-01-14'; // a Wednesday
const TOMORROW = '2026-01-16';
const NOON = `${TODAY}T12:00:00Z`;
const SPOT = {
  id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East',
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

function heatProp() {
  return {
    enabled: true,
    hasHome: false,
    spots: [SPOT],
    areaSpots: [SPOT],
    pointsByKey: new Map(),
    windows: [
      { key: `${TODAY}:SUNRISE`, date: TODAY, targetType: 'SUNRISE', label: 'This morning', time: '08:24', bestRating: 4, conf: 1 },
      { key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 4, conf: 1 },
    ],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
  };
}

function mapElement() {
  return (
    <MapView locations={[makeLocation()]} date={TODAY} autoEventType={null} heat={heatProp()} />
  );
}

async function renderMap() {
  let result;
  await act(async () => {
    result = render(mapElement());
  });
  return result;
}

const served = (nights, currentNightEndsAt = `${TOMORROW}T07:30:00Z`) => (
  getAuroraNights.mockResolvedValue({ nights, currentNightEndsAt })
);
const openDesktopMenu = () => fireEvent.click(screen.getByTestId('wf-win-pill'));

beforeEach(() => {
  mockRole = 'LITE_USER';
  mockIsMobile = false;
  localStorage.clear();
  getAuroraNights.mockReset();
  served([]);
  setRewind(NOON);
});
afterEach(() => {
  setRewind(null);
  localStorage.clear();
  vi.useRealTimers();
});

describe('MapView — the Lite aurora door (desktop)', () => {
  it('serves tonight as "Aurora tonight", in the open window menu', async () => {
    served([TODAY]);
    await renderMap();
    openDesktopMenu();

    expect(screen.getByRole('button', { name: 'Aurora tonight Pro' })).toBeInTheDocument();
    expect(screen.getByTestId('wf-win-menu')).toContainElement(screen.getByTestId('wf-win-aurora-door'));
    // One door, the soonest night, never a row.
    expect(screen.getAllByTestId('wf-aurora-upsell-door')).toHaveLength(1);
    expect(screen.queryByTestId('wf-map-peek-aurora-door')).toBeNull();
  });

  it('labels tomorrow and a later night the way the Pro row would', async () => {
    served([TOMORROW, '2026-01-17']);
    await renderMap();
    openDesktopMenu();
    expect(screen.getByRole('button', { name: 'Aurora tomorrow night Pro' })).toBeInTheDocument();
  });

  it('no served night, no door', async () => {
    served([]);
    await renderMap();
    openDesktopMenu();
    expect(screen.queryByTestId('wf-win-aurora-door')).toBeNull();
  });

  it('a failed read (the client folds it to an empty answer) draws no door', async () => {
    getAuroraNights.mockResolvedValue({ nights: [], currentNightEndsAt: null });
    await renderMap();
    openDesktopMenu();
    expect(screen.queryByTestId('wf-win-aurora-door')).toBeNull();
  });

  it('a rejected read also draws no door and does not throw', async () => {
    getAuroraNights.mockRejectedValue(new Error('offline'));
    await renderMap();
    openDesktopMenu();
    expect(screen.queryByTestId('wf-win-aurora-door')).toBeNull();
  });

  it('a refetch that adds a night shows the door', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    served([]);
    await renderMap();
    openDesktopMenu();
    expect(screen.queryByTestId('wf-win-aurora-door')).toBeNull();

    served([TODAY]);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5 * 60 * 1000);
    });

    expect(getAuroraNights).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('button', { name: 'Aurora tonight Pro' })).toBeInTheDocument();
  });

  it('before the served current night is over, its label stays (a pre-dawn reader sees last night)', async () => {
    served([YESTERDAY, TODAY], `${TODAY}T22:00:00Z`);
    await renderMap();
    openDesktopMenu();
    expect(screen.getByRole('button', { name: 'Aurora Wednesday night Pro' })).toBeInTheDocument();
  });

  it('once currentNightEndsAt has passed, the current night is dropped and the next one leads', async () => {
    // Now is 12:00Z on the 15th; the night that ended at 07:30Z that morning was the 14th's.
    served([YESTERDAY, TODAY], `${TODAY}T07:30:00Z`);
    await renderMap();
    openDesktopMenu();
    expect(screen.getByRole('button', { name: 'Aurora tonight Pro' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Wednesday/ })).toBeNull();
  });

  it('a null currentNightEndsAt never ends the night: every served night stays, as in D-14', async () => {
    served([YESTERDAY, TODAY], null);
    await renderMap();
    openDesktopMenu();
    expect(screen.getByRole('button', { name: 'Aurora Wednesday night Pro' })).toBeInTheDocument();
  });

  it('if only the ended night was served, no door', async () => {
    served([YESTERDAY], `${TODAY}T07:30:00Z`);
    await renderMap();
    openDesktopMenu();
    expect(screen.queryByTestId('wf-win-aurora-door')).toBeNull();
  });
});

describe('MapView — the Lite aurora door (phone)', () => {
  it('draws the door in the peek sheet\'s Windows section, and not in the (never-opened) desktop menu', async () => {
    mockIsMobile = true;
    served([TODAY]);
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));

    const wrapper = screen.getByTestId('wf-map-peek-aurora-door');
    expect(screen.getByTestId('wf-map-peek-windows')).toContainElement(wrapper);
    expect(screen.getByRole('button', { name: 'Aurora tonight Pro' })).toBeInTheDocument();
    expect(screen.getAllByTestId('wf-aurora-upsell-door')).toHaveLength(1);
    expect(screen.queryByTestId('wf-win-aurora-door')).toBeNull();
  });

  it('Escape on the door trigger closes the section and focus lands on the sheet\'s fallback, not the tab behind', async () => {
    mockIsMobile = true;
    served([TODAY]);
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const trigger = screen.getByTestId('wf-aurora-upsell-trigger');
    act(() => { trigger.focus(); });

    await act(async () => { fireEvent.keyDown(trigger, { key: 'Escape' }); });

    expect(screen.queryByTestId('wf-map-peek-body')).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-btn-lay'));
  });

  it('draws no door on the phone when no night is served', async () => {
    mockIsMobile = true;
    served([]);
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    expect(screen.queryByTestId('wf-map-peek-aurora-door')).toBeNull();
  });
});

describe.each(['PRO_USER', 'ADMIN'])('MapView — %s never sees the door nor asks for the nights', (role) => {
  it('desktop: no request, no door', async () => {
    mockRole = role;
    served([TODAY]);
    await renderMap();
    openDesktopMenu();

    expect(getAuroraNights).not.toHaveBeenCalled();
    expect(screen.queryByTestId('wf-win-aurora-door')).toBeNull();
    expect(screen.queryByTestId('wf-aurora-upsell-door')).toBeNull();
    expect(screen.getByTestId('wf-win-menu').innerHTML).not.toMatch(/aurora-door|wf-pro-upsell/);
  });

  it('phone: no request, no door', async () => {
    mockRole = role;
    mockIsMobile = true;
    served([TODAY]);
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));

    expect(getAuroraNights).not.toHaveBeenCalled();
    expect(screen.queryByTestId('wf-map-peek-aurora-door')).toBeNull();
    expect(screen.getByTestId('wf-map-peek-windows').innerHTML).not.toMatch(/aurora-door|wf-pro-upsell/);
  });
});

describe('MapView — the nights request is Lite-only and follows the role', () => {
  it('a role change to Lite starts asking; leaving Lite stops the poll', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    mockRole = 'PRO_USER';
    const result = await renderMap();
    expect(getAuroraNights).not.toHaveBeenCalled();

    mockRole = 'LITE_USER';
    await act(async () => { result.rerender(mapElement()); });
    expect(getAuroraNights).toHaveBeenCalledTimes(1);

    mockRole = 'ADMIN';
    await act(async () => { result.rerender(mapElement()); });
    await act(async () => { await vi.advanceTimersByTimeAsync(10 * 60 * 1000); });
    expect(getAuroraNights).toHaveBeenCalledTimes(1);
  });
});
