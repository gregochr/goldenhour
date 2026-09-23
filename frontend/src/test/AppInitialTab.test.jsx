/**
 * `App`'s wiring of `utils/initialTab.js` into the Plan shell (default-tab-by-device-plan.md §4.2).
 *
 * <p>Every other App suite mocks the util to `'plan'` (see `App.test.jsx`'s own note) because
 * jsdom's `matchMedia` stub answers "no match" for everything, which is the REAL desktop/iPad
 * answer — so leaving it unmocked there would move every Plan-first assumption those files make.
 * This file is the one place that keeps the real resolution live, proving the two ends actually
 * join: `App` calls `resolveInitialTab()` once at mount and hands the result to
 * `WindowFirstShell` as `initialTab`, and the shell's own preference/commit mechanics
 * (`WindowFirstShellInitialTab.test.jsx`) do the rest.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import App from '../App.jsx';

vi.mock('../api/forecastApi.js', () => ({
  fetchForecasts: vi.fn(),
  fetchLocations: vi.fn(),
  fetchAllOutcomes: vi.fn(),
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
vi.mock('../api/auroraApi.js', () => ({ getAuroraStatus: vi.fn() }));
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
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));
vi.mock('../components/MapView.jsx', () => ({
  default: () => <div data-testid="overlay-map-stub" />,
}));
// The Map pane, stubbed so mounting it costs nothing — this file is about which TAB is selected,
// never about the pane's own content.
vi.mock('../components/WindowFirstMapPane.jsx', () => ({
  default: () => <div data-testid="map-pane-stub" />,
}));
// The one mock this file exists to NOT set to 'plan' — see the header comment.
vi.mock('../utils/initialTab.js', () => ({ resolveInitialTab: vi.fn(() => 'map') }));

import { fetchForecasts, fetchLocations, fetchAllOutcomes } from '../api/forecastApi.js';
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
import { resolveInitialTab } from '../utils/initialTab.js';

const LOCATION_META = [{
  id: 7, name: 'Bamburgh', lat: 55.608, lon: -1.719, enabled: true,
  locationType: ['SEASCAPE'], tideType: ['HIGH'], solarEventType: ['SUNRISE', 'SUNSET'],
  bortleClass: 4, region: { name: 'Northumberland' },
}];
const FORECASTS = [{
  locationName: 'Bamburgh', locationLat: '55.608', locationLon: '-1.719',
  targetDate: '2099-01-01', targetType: 'SUNSET', forecastRunAt: '2026-01-01T06:00:00', rating: 3,
}];

const renderApp = () => {
  localStorage.setItem('goldenhour_token', 'test-token');
  localStorage.setItem('goldenhour_role', 'PRO_USER');
  render(<App />);
};

beforeEach(() => {
  localStorage.clear();
  fetchForecasts.mockReset().mockResolvedValue(FORECASTS);
  fetchLocations.mockReset().mockResolvedValue(LOCATION_META);
  fetchAllOutcomes.mockReset().mockResolvedValue([]);
  getDailyBriefing.mockReset().mockResolvedValue(null);
  getAllEvaluationScores.mockReset().mockResolvedValue([]);
  getSettings.mockReset().mockResolvedValue({});
  getReach.mockReset().mockResolvedValue([]);
  getDriveTimes.mockReset().mockResolvedValue({});
  fetchTravelDayRanges.mockReset().mockResolvedValue([]);
  getAuroraStatus.mockReset().mockResolvedValue(null);
  getNlcSighting.mockReset().mockResolvedValue(null);
  getAstroConditions.mockReset().mockResolvedValue(null);
  getAlmanac.mockReset().mockResolvedValue({ entries: [] });
  subscribeToRunNotifications.mockReset().mockReturnValue(() => {});
  getTodaysLight.mockReset().mockResolvedValue(null);
  resolveInitialTab.mockClear();
  window.requestIdleCallback = () => 1;
  window.cancelIdleCallback = () => {};
});

afterEach(() => {
  vi.restoreAllMocks();
  delete window.requestIdleCallback;
  delete window.cancelIdleCallback;
});

describe('App — wires utils/initialTab.js into the shell', () => {
  it('resolves the device tab once and the Map tab ends up selected once forecasts load', async () => {
    renderApp();

    // The Map pane does not exist until forecasts land (`allDates.length > 0`), so the shell's
    // OWN fallback puts the very first render on Plan regardless of the preference — this is not
    // an assertion of App's own default. What this file proves is the join: once the pane
    // arrives, the preference `App` resolved actually reaches the shell and moves it.
    const mapTab = await screen.findByRole('tab', { name: 'Map' });
    expect(mapTab).toHaveAttribute('aria-selected', 'true');
    // `findBy*`, not `getBy*`: `App.jsx` imports `WindowFirstMapPane` via `lazy()`, so even a
    // mocked module resolves through one more microtask than the tab button itself does.
    expect(await screen.findByTestId('map-pane-stub')).toBeInTheDocument();

    // Read once, at mount — not re-read on every render (plan §3.3's one-shot rule).
    expect(resolveInitialTab).toHaveBeenCalledTimes(1);
  });
});
