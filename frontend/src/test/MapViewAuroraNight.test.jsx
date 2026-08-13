/**
 * Tests for MapView's aurora NIGHT selection — the client half of the fix that made the backend
 * score the night in progress rather than a calendar date.
 *
 * A night runs dusk on D to dawn on D+1, so in the small hours you are inside the window named
 * *yesterday*, and that is the date its results are stored under. This file pins the two places
 * that used to ask a calendar instead:
 *
 * - the aurora viewline, which rendered only when the selected date was today's date; and
 * - the selected date itself, which stayed on a day the run had never scored.
 *
 * ⚠️ TIMEZONE AND CLOCK ARE BOTH PINNED. Nothing in this repo pins TZ (this Mac is Europe/London,
 * CI runners are UTC) and every assertion below depends on being in the small hours, so an
 * unfrozen clock would make these tests pass or fail by time of day. Verified to survive `TZ=UTC`
 * in the environment.
 */
process.env.TZ = 'Europe/London';

import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';

// ── Leaflet / react-leaflet stubs ────────────────────────────────────────────

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = () => ({});
  return { default: { icon, divIcon }, icon, divIcon };
});

vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('leaflet.markercluster/dist/MarkerCluster.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div data-testid="marker">{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
  }),
}));

vi.mock('react-leaflet-cluster', () => ({
  default: ({ children }) => <div>{children}</div>,
}));

// ── App dependencies ─────────────────────────────────────────────────────────

const mockUseAuth = vi.fn().mockReturnValue({ role: 'ADMIN' });
vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: (...args) => mockUseAuth(...args),
}));

vi.mock('../hooks/useIsMobile.js', () => ({
  useIsMobile: () => false,
}));

// The status object is swapped per test but must keep a STABLE identity within one, or MapView's
// effects re-run forever on a fresh object each render.
const { auroraStatusRef, stableViewline } = vi.hoisted(() => ({
  auroraStatusRef: { current: null },
  stableViewline: {
    points: [
      { longitude: -5, latitude: 54 },
      { longitude: 0, latitude: 55 },
    ],
    summary: 'Visible as far south as northern England',
    southernmostLatitude: 54,
    forecastTime: '2026-08-13T22:00:00Z',
    active: true,
  },
}));

vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: () => ({ status: auroraStatusRef.current }),
}));

vi.mock('../hooks/useAuroraViewline.js', () => ({
  useAuroraViewline: () => ({ viewline: stableViewline }),
}));

const { availableDatesRef } = vi.hoisted(() => ({ availableDatesRef: { current: [] } }));

vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn(() => Promise.resolve(availableDatesRef.current)),
}));

vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));

vi.mock('../api/settingsApi.js', () => ({
  getDriveTimes: vi.fn().mockResolvedValue({}),
}));

vi.mock('../components/BottomSheet.jsx', () => ({
  default: ({ children }) => <div>{children}</div>,
}));
vi.mock('../components/MarkerPopupContent.jsx', () => ({
  default: () => <div data-testid="popup-content" />,
}));
vi.mock('../components/ForecastTypeSelector.jsx', () => ({
  default: ({ eventType, onChange }) => (
    <div>
      <button data-testid="type-sunset" onClick={() => onChange('SUNSET')}>Sunset</button>
      <button data-testid="type-aurora" onClick={() => onChange('AURORA')}>Aurora</button>
      <span data-testid="current-event-type">{eventType}</span>
    </div>
  ),
}));
vi.mock('../components/InfoTip.jsx', () => ({
  default: ({ text }) => <span data-testid="infotip-text">{text}</span>,
}));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({
  default: ({ viewline }) => (viewline ? <div data-testid="aurora-viewline-overlay" /> : null),
}));
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '4★', colour: '#E5A00D' }),
  createClusterIcon: () => ({ options: { html: '', iconSize: { x: 40, y: 40 }, className: '' } }),
  RATING_COLOURS: { 1: '#A32D2D', 2: '#D85A30', 3: '#FAC775', 4: '#97C459', 5: '#3B6D11' },
  STAND_DOWN_COLOUR: '#501313',
}));

import MapView from '../components/MapView.jsx';

// ── Fixtures ─────────────────────────────────────────────────────────────────

/**
 * 02:00 UK on 14 August. The dark window running right now opened at dusk on the 13th, so the
 * backend calls it "Tonight" and files its results under THE_NIGHT while the calendar says
 * THE_CALENDAR_DAY. Every test here sits at this instant.
 */
const SMALL_HOURS = '2026-08-14T01:00:00Z';
const THE_NIGHT = '2026-08-13';
const THE_CALENDAR_DAY = '2026-08-14';

const ACTIVE_STATUS = { level: 'MODERATE', kpIndex: 5.0, currentNightDate: THE_NIGHT };
/** What a backend deployed before this field returns — and what a LITE user's null degrades to. */
const STATUS_WITHOUT_NIGHT = { level: 'MODERATE', kpIndex: 5.0 };

function makeLocations(dates = [THE_NIGHT, THE_CALENDAR_DAY]) {
  const forecasts = new Map(
    dates.map((d) => [d, {
      sunset: { rating: 4, solarEventTime: `${d}T18:00:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
      sunrise: { rating: 3, solarEventTime: `${d}T06:00:00`, fierySkyPotential: 60, goldenHourPotential: 50 },
    }]),
  );
  return [
    { name: 'TestLoc', lat: 55.0, lon: -1.7, forecastsByDate: forecasts, locationType: ['LANDSCAPE'] },
  ];
}

async function renderMap(overrides = {}) {
  const props = {
    locations: makeLocations(),
    date: THE_CALENDAR_DAY,
    autoEventType: null,
    ...overrides,
  };
  let result;
  await act(async () => { result = render(<MapView {...props} />); });
  return result;
}

const enterAuroraMode = async () => {
  await act(async () => { fireEvent.click(screen.getByTestId('type-aurora')); });
};

// ── Tests ────────────────────────────────────────────────────────────────────

describe('MapView aurora viewline night gating', () => {
  beforeEach(() => {
    localStorage.clear();
    mockUseAuth.mockReturnValue({ role: 'ADMIN' });
    auroraStatusRef.current = ACTIVE_STATUS;
    availableDatesRef.current = [THE_NIGHT];
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(SMALL_HOURS));
  });
  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  it('renders the viewline on the night in progress, which is yesterday’s date', async () => {
    // The regression: this date is not "today", so the old `date === today` gate hid the viewline
    // on precisely the night the reader had come to look at.
    await renderMap({ date: THE_NIGHT });
    await enterAuroraMode();

    expect(screen.getByTestId('aurora-viewline-overlay')).toBeInTheDocument();
  });

  it('hides the viewline on the calendar day, whose night has not begun', async () => {
    // The mirror image, and the reason this is a gate rather than a removal: at 02:00 the window
    // named by today's date starts at dusk, ~19 hours away. A nowcast does not belong on it.
    await renderMap({ date: THE_CALENDAR_DAY, onSelectDate: null });
    await enterAuroraMode();

    expect(screen.queryByTestId('aurora-viewline-overlay')).not.toBeInTheDocument();
  });

  it('falls back to the local date when the backend sends no night', async () => {
    // A backend older than the field, or a browser on a cached bundle. The fallback is the old
    // behaviour exactly: the viewline shows on the calendar date and nowhere else.
    auroraStatusRef.current = STATUS_WITHOUT_NIGHT;
    availableDatesRef.current = [THE_CALENDAR_DAY];
    await renderMap({ date: THE_CALENDAR_DAY });
    await enterAuroraMode();

    expect(screen.getByTestId('aurora-viewline-overlay')).toBeInTheDocument();
  });

  it('hides the viewline outside aurora mode even on the night in progress', async () => {
    await renderMap({ date: THE_NIGHT });

    expect(screen.queryByTestId('aurora-viewline-overlay')).not.toBeInTheDocument();
  });
});

describe('MapView aurora night date selection', () => {
  beforeEach(() => {
    localStorage.clear();
    mockUseAuth.mockReturnValue({ role: 'ADMIN' });
    auroraStatusRef.current = ACTIVE_STATUS;
    availableDatesRef.current = [THE_NIGHT];
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(SMALL_HOURS));
  });
  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  it('asks for the night in progress when the selected date has no aurora results', async () => {
    // The headline defect: a forecast run at 02:00 stores under the 13th, the map opens on the
    // 14th, and the run the user paid for appears to have produced nothing.
    const onSelectDate = vi.fn();
    await renderMap({ date: THE_CALENDAR_DAY, onSelectDate });
    await enterAuroraMode();

    await waitFor(() => expect(onSelectDate).toHaveBeenCalledWith(THE_NIGHT));
  });

  it('leaves the date alone when it already has aurora results', async () => {
    // Someone who arrived on a scored night is looking at what they came for. Moving them would be
    // the component overriding a choice already made.
    const onSelectDate = vi.fn();
    availableDatesRef.current = [THE_NIGHT, THE_CALENDAR_DAY];
    await renderMap({ date: THE_CALENDAR_DAY, onSelectDate });
    await enterAuroraMode();

    expect(onSelectDate).not.toHaveBeenCalled();
  });

  it('leaves the date alone when the night in progress has no results either', async () => {
    // Nothing was run for this night, so jumping would swap one empty day for another.
    const onSelectDate = vi.fn();
    availableDatesRef.current = ['2026-08-01'];
    await renderMap({ date: THE_CALENDAR_DAY, onSelectDate });
    await enterAuroraMode();

    expect(onSelectDate).not.toHaveBeenCalled();
  });

  it('does not eat the next date click after entering aurora mode already on the night', async () => {
    // ⚠️ REGRESSION TEST. The latch was originally set only on the branch that fires, so entering
    // aurora mode while already ON the aurora night returned through a guard without arming it —
    // and that is the ORDINARY case, because the map's default date and the current night are both
    // today for most of the day. The effect stayed live, and the reader's very next date-strip
    // click satisfied it: the click was swallowed and the map snapped back. Reproduced in a
    // browser before being fixed, not theorised.
    const onSelectDate = vi.fn();
    const { rerender } = await renderMap({ date: THE_NIGHT, onSelectDate });
    await enterAuroraMode();

    // Nothing to do on entry — already on the night.
    expect(onSelectDate).not.toHaveBeenCalled();

    // The reader now picks a night with no results. This must be honoured, not undone.
    await act(async () => {
      rerender(
        <MapView
          locations={makeLocations()}
          date={THE_CALENDAR_DAY}
          autoEventType={null}
          onSelectDate={onSelectDate}
        />,
      );
    });

    expect(onSelectDate).not.toHaveBeenCalled();
  });

  it('does not ask again after the reader moves the date themselves', async () => {
    // The latch. It fires once per entry into aurora mode, so the strip stays the reader's —
    // including a deliberate move to a night with nothing on it. Without this the component would
    // pull them straight back and the strip would be unusable in aurora mode.
    const onSelectDate = vi.fn();
    const { rerender } = await renderMap({ date: THE_CALENDAR_DAY, onSelectDate });
    await enterAuroraMode();
    await waitFor(() => expect(onSelectDate).toHaveBeenCalledTimes(1));

    // The reader picks an unscored day; the parent feeds it back down as the new `date`.
    await act(async () => {
      rerender(
        <MapView
          locations={makeLocations([THE_NIGHT, THE_CALENDAR_DAY, '2026-08-12'])}
          date="2026-08-12"
          autoEventType={null}
          onSelectDate={onSelectDate}
        />,
      );
    });

    expect(onSelectDate).toHaveBeenCalledTimes(1);
  });

  it('asks again on a fresh entry into aurora mode', async () => {
    // The latch resets on leaving, so the jump is available every time aurora mode is entered —
    // it is a default for entering the mode, not a one-shot for the session.
    const onSelectDate = vi.fn();
    await renderMap({ date: THE_CALENDAR_DAY, onSelectDate });
    await enterAuroraMode();
    await waitFor(() => expect(onSelectDate).toHaveBeenCalledTimes(1));

    await act(async () => { fireEvent.click(screen.getByTestId('type-sunset')); });
    await enterAuroraMode();

    await waitFor(() => expect(onSelectDate).toHaveBeenCalledTimes(2));
  });

  it('never asks outside aurora mode', async () => {
    // The product decision this change was scoped to: the colour map keeps its calendar default,
    // because at 02:00 a landscape photographer wants today's sunrise, not last night's sunset.
    const onSelectDate = vi.fn();
    await renderMap({ date: THE_CALENDAR_DAY, onSelectDate });

    expect(onSelectDate).not.toHaveBeenCalled();
  });

  it('renders without a date handler, asking for nothing', async () => {
    // The map overlay deliberately passes none: it reads its own date, so a request could not
    // reach it and would only move the Plan tab behind it.
    await renderMap({ date: THE_CALENDAR_DAY, onSelectDate: null });
    await enterAuroraMode();

    expect(screen.getByTestId('current-event-type')).toHaveTextContent('AURORA');
  });
});
