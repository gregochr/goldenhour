import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { ukDateStr, ukDateStrOffset } from '../utils/mapDates.js';
import { setRewind } from '../utils/rewind.js';

/**
 * The past-window provenance flag at the app root (outcome-rating-plan.md §2 #2, §3 O3a step 3):
 * `App.selectDate(date, { isPast })` stores `selectedDateIsPast` beside `selectedDateIsNight`, and
 * `resolveMapDate` honours a date before today only with it — exactly as it honours a night only with
 * `isNight`. The shape is `AppRewind.test.jsx`'s: the authenticated app with every API module mocked
 * and the heavy map pane cut down to a stub, here one that prints the date it was handed and exposes
 * the pane's one outbound call, `onSelectDate`, as buttons.
 *
 * What is proven: a flagged past pick holds the Map on that date; the SAME date unflagged (a reload,
 * a handoff, any other route to `selectDate`) falls through to the today-forward pick; the flag is
 * per pick — a later live pick clears it, and a repeat of the past date without it is refused again;
 * and a flagged date that is not in the forecast domain is refused.
 */

vi.mock('../api/forecastApi.js', () => ({
  fetchForecasts: vi.fn(),
  fetchLocations: vi.fn(),
  clearForecastDetailCache: vi.fn(),
}));
vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({
  getSettings: vi.fn(),
  getReach: vi.fn(),
  lookupPostcode: vi.fn(),
  saveHome: vi.fn(),
  refreshDriveTimes: vi.fn(),
  saveMapColourPreferences: vi.fn(),
  markComingUpSeen: vi.fn(),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraStatus: vi.fn(),
  getAuroraAlert: vi.fn().mockResolvedValue(null),
}));
vi.mock('../api/nlcApi.js', () => ({ getNlcSighting: vi.fn() }));
vi.mock('../api/astroApi.js', () => ({ getAstroConditions: vi.fn() }));
vi.mock('../api/almanacApi.js', () => ({ getAlmanac: vi.fn(), ALMANAC_DAYS: 90 }));
vi.mock('../api/lightApi.js', () => ({ getTodaysLight: vi.fn() }));
vi.mock('../api/authApi.js', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn(),
  refreshAccessToken: vi.fn(),
  register: vi.fn(),
  resendVerification: vi.fn(),
  verifyEmail: vi.fn(),
  setPasswordForNewUser: vi.fn(),
  submitWaitlist: vi.fn(),
}));
vi.mock('../components/MapView.jsx', () => ({
  default: () => <div data-testid="overlay-map-stub" />,
}));
vi.mock('../hooks/useHealthStatus.js', () => ({
  useHealthStatus: () => ({ status: 'UP', degraded: false, services: [], checkedAt: null }),
}));
vi.mock('../components/ManageView.jsx', () => ({
  default: () => <div data-testid="manage-stub" />,
}));

/** The dates the stub pane's buttons send, filled in below once the clock-relative dates exist (the `mock` prefix is what lets the hoisted factory close over it). */
const mockDates = { yesterday: '', twoAgo: '', tomorrow: '', t4: '' };
// The real shell, plus a probe printing the `forecastDates` it is handed (the Coming up and day-tile
// Map doors withhold on it). Wrapping leaves what every other test here sees untouched.
vi.mock('../components/WindowFirstShell.jsx', async (importActual) => {
  const actual = await importActual();
  const RealShell = actual.default;
  return {
    ...actual,
    default: (props) => (
      <>
        <span data-testid="shell-forecast-dates">
          {props.forecastDates === null ? 'null' : props.forecastDates.join(',')}
        </span>
        <RealShell {...props} />
      </>
    ),
  };
});
vi.mock('../components/WindowFirstMapPane.jsx', () => ({
  default: ({ selectedDate, onSelectDate, dates }) => (
    <div data-testid="map-pane-stub" data-selected-date={selectedDate ?? ''}>
      <span data-testid="map-pane-dates">{(dates ?? []).join(',')}</span>
      <button type="button" onClick={() => onSelectDate(mockDates.yesterday, { isNight: false, isPast: true })}>
        pick past yesterday
      </button>
      <button type="button" onClick={() => onSelectDate(mockDates.yesterday)}>
        pick yesterday unflagged
      </button>
      <button type="button" onClick={() => onSelectDate(mockDates.twoAgo, { isNight: false, isPast: true })}>
        pick past two days ago
      </button>
      <button type="button" onClick={() => onSelectDate(mockDates.tomorrow, { isNight: false, isPast: false })}>
        pick live tomorrow
      </button>
      <button type="button" onClick={() => onSelectDate(mockDates.t4)}>
        pick the T+4 sunset
      </button>
    </div>
  ),
}));

import { fetchForecasts, fetchLocations } from '../api/forecastApi.js';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getSettings, getReach } from '../api/settingsApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAuroraStatus } from '../api/auroraApi.js';
import { getNlcSighting } from '../api/nlcApi.js';
import { getAstroConditions } from '../api/astroApi.js';
import { getAlmanac } from '../api/almanacApi.js';
import { getTodaysLight } from '../api/lightApi.js';

const YESTERDAY = ukDateStrOffset(-1);
const TWO_AGO = ukDateStrOffset(-2);
const TOMORROW = ukDateStrOffset(1);
mockDates.yesterday = YESTERDAY;
mockDates.twoAgo = TWO_AGO;
mockDates.tomorrow = TOMORROW;

const LOCATION_META = [{
  id: 1, name: 'Derwentwater', lat: 54.58, lon: -3.14, enabled: true,
  locationType: ['LANDSCAPE'], tideType: [], solarEventType: ['SUNRISE', 'SUNSET'],
  bortleClass: 3, region: { name: 'Lake District' },
}];
/** Forecast rows for yesterday and tomorrow only — so TWO_AGO is NOT in the forecast domain. */
const FORECASTS = [YESTERDAY, TOMORROW].flatMap((targetDate) => ['SUNRISE', 'SUNSET'].map((targetType) => ({
  locationName: 'Derwentwater', locationLat: '54.58', locationLon: '-3.14',
  targetDate, targetType, forecastRunAt: '2026-01-01T06:00:00', rating: 3,
})));
const SETTINGS = {
  role: 'ADMIN', homePostcode: null, homePlaceName: null, homeLatitude: null,
  homeLongitude: null, driveTimesCalculatedAt: null, mapColourScale: 'temp',
  comingUpLastSeenDate: null,
};
const LENS = {
  tier: { id: '45', limitMinutes: 45, label: '45 min' }, tierId: '45',
  defaultTier: { id: '45', limitMinutes: 45, label: '45 min' }, defaultTierId: '45',
  weekend: false, overridden: false, locked: false, selectTier: vi.fn(), resetToDefault: vi.fn(),
};
const ctx = () => ({
  briefing: { generatedAt: '2026-08-14T12:00:00', days: [] },
  loading: false, windowCards: [], paneItems: [], upcomingEvents: [], travelDayDates: new Set(),
  reachById: new Map(), effectiveReachById: new Map(), isPro: true, isLiteUser: false,
  evaluationScores: new Map(), scoresLoaded: true, scoreRows: [], scoreIndex: new Map(),
  heatStripCards: [], heatSpots: [], heatPointSets: new Map(), regionSeries: new Map(),
  todayStr: '2026-08-14', tomorrowStr: '2026-08-15', reachLens: LENS,
  ratingLens: { floor: { id: 'any', min: null, label: 'Any rating' }, floorId: 'any', minRating: null, selectFloor: vi.fn() },
  homePlace: null, origin: null, setOrigin: vi.fn(), regions: [],
  comingUpLastSeenDate: '2026-08-14', setComingUpLastSeenAt: vi.fn(),
});

function renderApp() {
  localStorage.setItem('goldenhour_token', 'test-token');
  localStorage.setItem('goldenhour_role', 'ADMIN');
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  return render(<App />);
}

async function openMapTab() {
  const tab = await screen.findByRole('tab', { name: 'Map' });
  await act(async () => { fireEvent.click(tab); });
  return screen.findByTestId('map-pane-stub');
}

const press = async (name) => {
  await act(async () => { fireEvent.click(screen.getByRole('button', { name })); });
};

describe('App — the past-window selection licence (selectedDateIsPast)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchForecasts.mockResolvedValue(FORECASTS);
    fetchLocations.mockResolvedValue(LOCATION_META);
    getDailyBriefing.mockResolvedValue(null);
    getAllEvaluationScores.mockResolvedValue([]);
    getSettings.mockResolvedValue(SETTINGS);
    getReach.mockResolvedValue({ reach: [] });
    fetchTravelDayRanges.mockResolvedValue([]);
    getAuroraStatus.mockResolvedValue(null);
    getNlcSighting.mockResolvedValue(null);
    getAstroConditions.mockResolvedValue(null);
    getAlmanac.mockResolvedValue({ entries: [], bands: null });
    getTodaysLight.mockResolvedValue(null);
  });

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('the fixture\'s forecast domain holds yesterday and tomorrow, not two days ago', async () => {
    renderApp();
    await openMapTab();
    expect(screen.getByTestId('map-pane-dates')).toHaveTextContent(`${YESTERDAY},${TOMORROW}`);
  });

  it('a past pick WITH the flag holds the Map on that past date', async () => {
    renderApp();
    const pane = await openMapTab();
    expect(pane).not.toHaveAttribute('data-selected-date', YESTERDAY);

    await press('pick past yesterday');

    expect(pane).toHaveAttribute('data-selected-date', YESTERDAY);
  });

  it('the SAME past date WITHOUT the flag falls through to the today-forward pick', async () => {
    renderApp();
    const pane = await openMapTab();

    await press('pick yesterday unflagged');

    expect(pane).not.toHaveAttribute('data-selected-date', YESTERDAY);
    expect(pane).toHaveAttribute('data-selected-date', TOMORROW);
  });

  it('the flag is per pick: a live pick afterwards clears it, and the past date is refused again unflagged', async () => {
    renderApp();
    const pane = await openMapTab();

    await press('pick past yesterday');
    expect(pane).toHaveAttribute('data-selected-date', YESTERDAY);

    await press('pick live tomorrow');
    expect(pane).toHaveAttribute('data-selected-date', TOMORROW);

    // A stale pick of the same past date, this time without the flag, does not hold the map.
    await press('pick yesterday unflagged');
    expect(pane).toHaveAttribute('data-selected-date', TOMORROW);
  });

  it('refuses a flagged past date that is not in the forecast domain', async () => {
    renderApp();
    const pane = await openMapTab();

    await press('pick past two days ago');

    expect(pane).not.toHaveAttribute('data-selected-date', TWO_AGO);
    expect(pane).toHaveAttribute('data-selected-date', TOMORROW);
  });

  /**
   * The Map ends at T+3 (whole-day-horizon-plan.md §5), the last day At a Glance shows. `App.allDates`
   * is `colourForecastDates` bounded above at `lastPlanDateStr(today)` (past dates kept), except that
   * it never cuts a date the briefing SERVES a window for. The pane stub prints the `dates` it is
   * handed and the wrapped shell prints its own `forecastDates` (what the Coming up and day-tile Map
   * doors withhold on), so the one harness pins both lists. Nested here for this file's mocks and
   * `beforeEach`; each test supplies its own forecast span.
   */
  describe('the Map ends at T+3', () => {
    afterEach(() => { act(() => setRewind(null)); });

    /** Sunrise and sunset rows for each date, as `GET /api/forecast` serves them. */
    const forecastsFor = (dates) => dates.flatMap((targetDate) => ['SUNRISE', 'SUNSET']
      .map((targetType) => ({
        locationName: 'Derwentwater', locationLat: '54.58', locationLon: '-3.14',
        targetDate, targetType, forecastRunAt: '2026-01-01T06:00:00', rating: 3,
      })));

    /** T-2 .. T+5 relative to the app clock: the served span, past days included. */
    const servedSpan = () => [-2, -1, 0, 1, 2, 3, 4, 5].map((n) => ukDateStrOffset(n));

    /** Renders with the briefing context overridden (e.g. the windows the server serves). */
    const renderWith = (overrides = {}) => {
      localStorage.setItem('goldenhour_token', 'test-token');
      localStorage.setItem('goldenhour_role', 'ADMIN');
      vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue({ ...ctx(), ...overrides });
      return render(<App />);
    };

    const mapDates = async () => {
      await openMapTab();
      return screen.getByTestId('map-pane-dates').textContent.split(',').filter(Boolean);
    };

    const shellDates = async () => {
      const el = await screen.findByTestId('shell-forecast-dates');
      await act(async () => {});
      return el.textContent.split(',').filter(Boolean);
    };

    it('drops T+4 and T+5, keeps T+3 and every earlier date, past included', async () => {
      const span = servedSpan();
      fetchForecasts.mockResolvedValue(forecastsFor(span));

      renderWith();

      expect(await mapDates()).toEqual(span.slice(0, 6));
    });

    it('never cuts a date the briefing SERVES a window for: a device behind the server still selects it', async () => {
      // The device's UK date is a day behind the server's, so the server's T+3 is this client's T+4. The
      // briefing serves windows through that date; the client cut must not drop its row, and picking its
      // sunset must land the Map on it (it used to be listed but refused by `resolveMapDate`).
      const span = servedSpan();
      const t4 = ukDateStrOffset(4);
      mockDates.t4 = t4;
      fetchForecasts.mockResolvedValue(forecastsFor(span));

      renderWith({
        upcomingEvents: [
          { date: ukDateStrOffset(3), targetType: 'SUNSET' },
          { date: t4, targetType: 'SUNRISE' },
          { date: t4, targetType: 'SUNSET' },
        ],
      });

      expect(await mapDates()).toEqual(span.slice(0, 7));
      await press('pick the T+4 sunset');
      expect(screen.getByTestId('map-pane-stub')).toHaveAttribute('data-selected-date', t4);
    });

    it('still cuts T+5 when only T+4 is served (the exemption is per served date)', async () => {
      const span = servedSpan();
      fetchForecasts.mockResolvedValue(forecastsFor(span));

      renderWith({ upcomingEvents: [{ date: ukDateStrOffset(4), targetType: 'SUNSET' }] });

      expect(await mapDates()).toEqual(span.slice(0, 7));
    });

    it('hands the shell the BOUNDED list: no T+4 or T+5 reaches the Coming up and day-tile Map doors', async () => {
      const span = servedSpan();
      fetchForecasts.mockResolvedValue(forecastsFor(span));

      renderWith();

      // The unbounded `colourForecastDates` would end at T+5; the doors withhold on membership.
      expect(await shellDates()).toEqual(span.slice(0, 6));
    });

    it('hands the shell null (not known yet), not an empty list, while the forecast is outstanding', async () => {
      fetchForecasts.mockReturnValue(new Promise(() => {}));

      renderWith();

      expect(await screen.findByTestId('shell-forecast-dates')).toHaveTextContent('null');
    });

    it('keeps a T+3 that is the last served date (nothing to drop)', async () => {
      const span = servedSpan().slice(0, 6);
      fetchForecasts.mockResolvedValue(forecastsFor(span));

      renderWith();

      expect(await mapDates()).toEqual(span);
    });

    it('keeps only the past when every served date is after T+3 (the domain is empty above, not past)', async () => {
      const span = [ukDateStrOffset(-1), ukDateStrOffset(4), ukDateStrOffset(5)];
      fetchForecasts.mockResolvedValue(forecastsFor(span));

      renderWith();

      expect(await mapDates()).toEqual([span[0]]);
    });

    it('reads today from the app clock: a rewound clock moves the cut with it', async () => {
      // A fixed instant far from the real today, so the test cannot pass by reading the wall clock.
      const rewoundTo = '2026-03-10T12:00:00Z';
      act(() => setRewind(rewoundTo));
      const today = ukDateStr();
      expect(today).toBe('2026-03-10');
      const span = [-2, -1, 0, 1, 2, 3, 4, 5].map((n) => ukDateStrOffset(n));
      fetchForecasts.mockResolvedValue(forecastsFor(span));

      renderWith();

      // T-2..T+3 of the rewound day; T+4 and T+5 (2026-03-14, 2026-03-15) are gone.
      expect(await mapDates()).toEqual([
        '2026-03-08', '2026-03-09', '2026-03-10', '2026-03-11', '2026-03-12', '2026-03-13',
      ]);
    });
  });
});
