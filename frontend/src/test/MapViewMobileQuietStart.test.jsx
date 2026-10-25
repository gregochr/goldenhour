/**
 * The Map tab's phone "quiet start" (map-mobile-sheet-plan.md §3 M1,
 * `docs/design/map-mobile-sheet/README.md` "Interactions" 1 and the Frame section's toast line).
 *
 * <p>One claim, phone-only and pinned against the SAME desktop control so a regression cannot hide
 * behind "well it never ran on this fixture at all". (The first claim, that the landing card never
 * opens on the phone, went with the card: `map-landing-nocard-plan.md` P1.)
 *
 * <ul>
 *   <li>The "★ PhotoCast-scored locations shown" toast (the TAB's own copy, gated on
 *   `solarWindowOnScreen()` — the OVERLAY's copy is untouched and out of scope) gains
 *   `wf-map-scored-legend-gone` 3,000 ms after its `|date|eventType|` key last changed, on the
 *   phone only; a window change resets the clock, and the desktop control never gains the class at
 *   all, however long the clock runs.</li>
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

// Mutable per-test, `MapViewResponsivePhone.test.jsx`'s own pattern — everything else in this
// codebase's `MapView*.test.jsx` suite mocks this hook to a fixed value, but this file's whole
// point is proving the SAME fixture diverges between the two.
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
vi.mock('../components/map/MapCallout.jsx', () => ({ default: () => null }));
vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

const SPOT = { id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East' };

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

/** Both of TODAY's events, so a reader can step between them without changing `date` at all —
 * `MapViewHeat.test.jsx`'s own fixture shape (two events, one day). */
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

function mapElement(props = {}) {
  return (
    <MapView
      locations={[makeLocation()]}
      date={TODAY}
      autoEventType={null}
      heat={heatProp()}
      {...props}
    />
  );
}

async function renderMap(props = {}) {
  let result;
  await act(async () => {
    result = render(mapElement(props));
  });
  return result;
}

/** Re-renders the SAME mount with new props — the shape of a prop landing after the tab is open. */
async function rerenderMap(result, props = {}) {
  await act(async () => {
    result.rerender(mapElement(props));
  });
}

beforeEach(() => {
  mockIsMobile = false;
  localStorage.clear();
});
afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
  vi.useRealTimers();
});

describe('the scored-locations toast fades on the phone after 3,000 ms (map-mobile-sheet-plan.md §3 M1 task 2)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
  });

  const briefingScores = () => new Map([
    [`North East|${TODAY}|SUNRISE|Bamburgh`, { rating: 4 }],
    [`North East|${TODAY}|SUNSET|Bamburgh`, { rating: 4 }],
  ]);

  const elapse = async (ms) => { await act(async () => { vi.advanceTimersByTime(ms); }); };
  const toast = () => screen.getByTestId('photocast-scored-legend');

  it('gains `wf-map-scored-legend-gone` + `aria-hidden` at 3,000 ms, not at 2,999 ms — phone only', async () => {
    mockIsMobile = true;
    await renderMap({ briefingScores: briefingScores() });
    expect(toast()).toBeInTheDocument();
    expect(toast()).not.toHaveClass('wf-map-scored-legend-gone');
    expect(toast()).not.toHaveAttribute('aria-hidden', 'true');

    await elapse(2999);
    expect(toast()).not.toHaveClass('wf-map-scored-legend-gone');

    await elapse(1);
    expect(toast()).toHaveClass('wf-map-scored-legend-gone');
    expect(toast()).toHaveAttribute('aria-hidden', 'true');
  });

  it('a window change resets the 3 s clock — selecting the OTHER event of the same day re-shows it', async () => {
    mockIsMobile = true;
    await renderMap({ briefingScores: briefingScores() });
    await elapse(3000);
    expect(toast()).toHaveClass('wf-map-scored-legend-gone');

    // ⚠️ M2 (map-mobile-sheet-plan.md §3 M2 task 4): on the phone the pill body opens the peek
    // sheet's Windows section now, never the desktop dropdown — `wf-map-peek-win-row`, not
    // `wf-win-row`.
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const sunriseRow = screen.getAllByTestId('wf-map-peek-win-row')
      .find((r) => r.getAttribute('data-ev-id') === `solar:${TODAY}:SUNRISE`);
    expect(sunriseRow?.getAttribute('data-ev-id')).toBe(`solar:${TODAY}:SUNRISE`);
    await act(async () => { fireEvent.click(sunriseRow); });

    // The key changed (`|${TODAY}|SUNRISE|` now), so the effect's cleanup ran and reset the class
    // before starting a fresh timer — the toast is back, immediately, with no wait required.
    expect(toast()).not.toHaveClass('wf-map-scored-legend-gone');

    await elapse(2999);
    expect(toast()).not.toHaveClass('wf-map-scored-legend-gone');
    await elapse(1);
    expect(toast()).toHaveClass('wf-map-scored-legend-gone');
  });

  it('the clock starts when the toast is SHOWN, not when the tab mounted — scores landing 5 s after mount still get their 3 s', async () => {
    // A review of M1 found this: keyed on the window alone, a toast whose gate opened after the
    // timer had already fired was born with the class applied — invisible from its first render.
    mockIsMobile = true;
    const result = await renderMap({ briefingScores: new Map() });
    expect(screen.queryByTestId('photocast-scored-legend')).not.toBeInTheDocument();
    await elapse(5000);

    await rerenderMap(result, { briefingScores: briefingScores() });
    expect(toast()).toBeInTheDocument();
    expect(toast()).not.toHaveClass('wf-map-scored-legend-gone');
    expect(toast()).not.toHaveAttribute('aria-hidden');

    await elapse(2999);
    expect(toast()).not.toHaveClass('wf-map-scored-legend-gone');
    await elapse(1);
    expect(toast()).toHaveClass('wf-map-scored-legend-gone');
    expect(toast()).toHaveAttribute('aria-hidden', 'true');
  });

  it('unmounting before 3,000 ms clears the timer — no state update lands on an unmounted map', async () => {
    mockIsMobile = true;
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { unmount } = await renderMap();
    await elapse(1000);
    unmount();
    await elapse(5000);
    const leaked = errors.mock.calls.filter(([m]) => typeof m === 'string' && /unmounted|not wrapped in act/.test(m));
    errors.mockRestore();
    expect(leaked).toEqual([]);
  });

  it('control: desktop never gains the class, however long the clock runs, on the SAME fixture', async () => {
    await renderMap({ briefingScores: briefingScores() });
    expect(toast()).toBeInTheDocument();

    await elapse(10000);

    expect(toast()).not.toHaveClass('wf-map-scored-legend-gone');
    expect(toast()).not.toHaveAttribute('aria-hidden', 'true');
  });
});
