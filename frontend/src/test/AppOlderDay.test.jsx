import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, render, screen, fireEvent,
} from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { ukDateStrOffset } from '../utils/mapDates.js';

/**
 * The older-day fetch at the app root (outcome-rating-plan.md §2 #2, §3 O3b): `App` owns
 * `useForecasts`, so it is where a fetched day becomes `allDates` and where the loader reaches the
 * Map pane. The shape is `AppPastSelection.test.jsx`'s: the authenticated app, every API module mocked
 * and the heavy map pane cut to a stub that prints what `App` hands it and exposes the loader and the
 * forecast-run refresh as buttons.
 *
 * What is proven: the pane receives a loader; pressing it requests exactly the day asked for; the day
 * lands in `dates` and in `loadedPastDays` (an empty one counts); a refresh that rebuilds the list
 * payload KEEPS the loaded day; and a flagged pick of the loaded date is then accepted where it was
 * refused before.
 */

vi.mock('../api/forecastApi.js', () => ({
  fetchForecasts: vi.fn(),
  fetchForecastsForDate: vi.fn(),
  fetchLocations: vi.fn(),
  clearForecastDetailCache: vi.fn(),
}));
vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({
  getSettings: vi.fn(),
  getReach: vi.fn(),
  getDriveTimes: vi.fn(),
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
vi.mock('../api/runProgressApi.js', () => ({ subscribeToRunNotifications: vi.fn() }));
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

/** The dates the stub pane's buttons send; filled in once the clock-relative dates exist. */
const mockDates = { older: '' };
vi.mock('../components/WindowFirstMapPane.jsx', () => ({
  default: ({
    selectedDate, onSelectDate, dates, onLoadOlderDay, olderDayLoading, loadedPastDays, emptyPastDays,
  }) => (
    <div data-testid="map-pane-stub" data-selected-date={selectedDate ?? ''}>
      <span data-testid="map-pane-dates">{(dates ?? []).join(',')}</span>
      <span data-testid="map-pane-loading">{olderDayLoading ?? ''}</span>
      <span data-testid="map-pane-loaded">{(loadedPastDays ?? []).join(',')}</span>
      <span data-testid="map-pane-empty">{(emptyPastDays ?? []).join(',')}</span>
      <span data-testid="map-pane-has-loader">{typeof onLoadOlderDay === 'function' ? 'yes' : 'no'}</span>
      <button type="button" onClick={() => onLoadOlderDay(mockDates.older)}>load older day</button>
      <button type="button" onClick={() => onSelectDate(mockDates.older, { isNight: false, isPast: true })}>
        pick the older day
      </button>
    </div>
  ),
}));

import {
  fetchForecasts, fetchForecastsForDate, fetchLocations,
} from '../api/forecastApi.js';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getSettings, getReach, getDriveTimes } from '../api/settingsApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAuroraStatus } from '../api/auroraApi.js';
import { getNlcSighting } from '../api/nlcApi.js';
import { getAstroConditions } from '../api/astroApi.js';
import { getAlmanac } from '../api/almanacApi.js';
import { subscribeToRunNotifications } from '../api/runProgressApi.js';
import { getTodaysLight } from '../api/lightApi.js';

const YESTERDAY = ukDateStrOffset(-1);
const TOMORROW = ukDateStrOffset(1);
const OLDER = ukDateStrOffset(-3);
mockDates.older = OLDER;

const LOCATION_META = [{
  id: 1, name: 'Derwentwater', lat: 54.58, lon: -3.14, enabled: true,
  locationType: ['LANDSCAPE'], tideType: [], solarEventType: ['SUNRISE', 'SUNSET'],
  bortleClass: 3, region: { name: 'Lake District' },
}];
const row = (targetDate, targetType) => ({
  locationName: 'Derwentwater', locationLat: '54.58', locationLon: '-3.14',
  targetDate, targetType, forecastRunAt: '2026-01-01T06:00:00', rating: 3,
});
/** The list: yesterday and tomorrow only, so OLDER is not in the forecast domain until it is fetched. */
const FORECASTS = [YESTERDAY, TOMORROW].flatMap((d) => [row(d, 'SUNRISE'), row(d, 'SUNSET')]);
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

async function openMapTab() {
  localStorage.setItem('goldenhour_token', 'test-token');
  localStorage.setItem('goldenhour_role', 'ADMIN');
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  render(<App />);
  const tab = await screen.findByRole('tab', { name: 'Map' });
  await act(async () => { fireEvent.click(tab); });
  return screen.findByTestId('map-pane-stub');
}

const press = async (name) => {
  await act(async () => { fireEvent.click(screen.getByRole('button', { name })); });
};

describe('App — the Map pane\'s older-day fetch', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchForecasts.mockResolvedValue(FORECASTS);
    fetchForecastsForDate.mockResolvedValue([row(OLDER, 'SUNRISE'), row(OLDER, 'SUNSET')]);
    fetchLocations.mockResolvedValue(LOCATION_META);
    getDailyBriefing.mockResolvedValue(null);
    getAllEvaluationScores.mockResolvedValue([]);
    getSettings.mockResolvedValue(SETTINGS);
    getReach.mockResolvedValue({ reach: [] });
    getDriveTimes.mockResolvedValue([]);
    fetchTravelDayRanges.mockResolvedValue([]);
    getAuroraStatus.mockResolvedValue(null);
    getNlcSighting.mockResolvedValue(null);
    getAstroConditions.mockResolvedValue(null);
    getAlmanac.mockResolvedValue({ entries: [], bands: null });
    subscribeToRunNotifications.mockReturnValue(() => {});
    getTodaysLight.mockResolvedValue(null);
  });

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('hands the pane a loader, and the older day is not in the forecast domain until it is fetched', async () => {
    await openMapTab();
    expect(screen.getByTestId('map-pane-has-loader')).toHaveTextContent('yes');
    expect(screen.getByTestId('map-pane-dates')).toHaveTextContent(`${YESTERDAY},${TOMORROW}`);
    expect(screen.getByTestId('map-pane-dates')).not.toHaveTextContent(OLDER);
    expect(fetchForecastsForDate).not.toHaveBeenCalled();
  });

  it('requests exactly the day asked for, and the day lands in dates and in loadedPastDays', async () => {
    await openMapTab();
    await press('load older day');

    expect(fetchForecastsForDate).toHaveBeenCalledTimes(1);
    expect(fetchForecastsForDate).toHaveBeenCalledWith(OLDER);
    expect(screen.getByTestId('map-pane-dates')).toHaveTextContent(`${OLDER},${YESTERDAY},${TOMORROW}`);
    expect(screen.getByTestId('map-pane-loaded')).toHaveTextContent(OLDER);
    expect(screen.getByTestId('map-pane-loading')).toBeEmptyDOMElement();
  });

  it('refuses a flagged pick of the older day before it is fetched, and takes it afterwards', async () => {
    const pane = await openMapTab();
    await press('pick the older day');
    expect(pane).not.toHaveAttribute('data-selected-date', OLDER);

    await press('load older day');
    await press('pick the older day');
    expect(pane).toHaveAttribute('data-selected-date', OLDER);
  });

  it('counts an EMPTY answer as loaded without adding a date', async () => {
    fetchForecastsForDate.mockResolvedValue([]);
    await openMapTab();
    await press('load older day');

    expect(screen.getByTestId('map-pane-loaded')).toHaveTextContent(OLDER);
    expect(screen.getByTestId('map-pane-empty')).toHaveTextContent(OLDER);
    expect(screen.getByTestId('map-pane-dates')).not.toHaveTextContent(OLDER);
  });

  it('names no empty day when the fetched day had rows', async () => {
    await openMapTab();
    await press('load older day');

    expect(screen.getByTestId('map-pane-loaded')).toHaveTextContent(OLDER);
    expect(screen.getByTestId('map-pane-empty')).toBeEmptyDOMElement();
  });
});
