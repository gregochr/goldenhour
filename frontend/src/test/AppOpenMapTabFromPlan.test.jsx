/**
 * `App.jsx`'s `openMapTabFromPlan` (doors D2, `plan-to-map-doors-plan.md` §3 D2 task 1) — the
 * sending side of the SAME `mapTabHandoff`/`tabRequest` channel `openFullMapTab` already uses,
 * distinguished downstream by `source: 'plan'`.
 *
 * No door UI ships in this phase (D3/D4 add the real buttons), so — the same problem
 * `WindowFirstShellPlanHandoff.test.jsx` solves for `openLocationInPlan`'s reverse-direction
 * cousin — the shell is stubbed to a probe exposing exactly the two props under test
 * (`onOpenMapTab`, `tabRequest`) plus a button standing in for "a door was pressed", and
 * `WindowFirstMapPane` is stubbed too so the payload App actually builds (`mapTabHandoff`, forwarded
 * as `handoff`) can be read straight off it rather than mounting the real Leaflet-backed pane.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import App from '../App.jsx';
import { ukDateStrOffset } from '../utils/mapDates.js';

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
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
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

/** Captures every props object App has ever handed `WindowFirstShell`, and renders a trigger for
 *  `onOpenMapTab` — the stand-in for a door tap, since no door UI ships in this phase. */
const ShellStub = { lastProps: null };
vi.mock('../components/WindowFirstShell.jsx', () => ({
  default: (props) => {
    ShellStub.lastProps = props;
    return (
      <div data-testid="stub-shell">
        <button
          type="button"
          data-testid="trigger-door"
          onClick={() => props.onOpenMapTab?.(DOOR)}
        >
          trigger door
        </button>
        {props.mapPane}
      </div>
    );
  },
}));

/** Captures every props object App/the pane has ever handed `WindowFirstMapPane` — the receiving
 *  end of `mapTabHandoff`, read here rather than re-derived. */
const MapPaneStub = { lastProps: null };
vi.mock('../components/WindowFirstMapPane.jsx', () => ({
  default: (props) => { MapPaneStub.lastProps = props; return <div data-testid="stub-map-pane" />; },
}));

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

const TOMORROW = ukDateStrOffset(1);

const LOCATION_META = [
  {
    id: 7, name: 'Bamburgh', lat: 55.608, lon: -1.719, enabled: true,
    locationType: ['SEASCAPE'], tideType: ['HIGH'], solarEventType: ['SUNRISE', 'SUNSET'],
    bortleClass: 4, region: { name: 'Northumberland' },
  },
];

const forecastRow = (meta, targetType) => ({
  locationName: meta.name,
  locationLat: String(meta.lat),
  locationLon: String(meta.lon),
  targetDate: TOMORROW,
  targetType,
  forecastRunAt: '2026-01-01T06:00:00',
  rating: 3,
});

const FORECASTS = LOCATION_META.flatMap((m) => [forecastRow(m, 'SUNRISE'), forecastRow(m, 'SUNSET')]);

// The door — `App.openMapTabFromPlan`'s own payload shape, already carrying the Plan lens values
// (read at the moment of the tap by `WindowFirstShell`'s own `openMapTab`, which this stub stands
// in for).
const DOOR = {
  date: TOMORROW, targetType: 'SUNSET', region: 'Lake District',
  minRating: 4, limitMinutes: 150, locationName: 'Bamburgh',
};

const renderApp = () => {
  localStorage.setItem('goldenhour_token', 'test-token');
  localStorage.setItem('goldenhour_role', 'PRO_USER');
  render(<App />);
};

beforeEach(() => {
  localStorage.clear();
  window.location.hash = '';
  ShellStub.lastProps = null;
  MapPaneStub.lastProps = null;
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
  window.requestIdleCallback = () => 1;
  window.cancelIdleCallback = () => {};
});

afterEach(() => {
  vi.restoreAllMocks();
  delete window.requestIdleCallback;
  delete window.cancelIdleCallback;
});

describe('App — openMapTabFromPlan (doors D2)', () => {
  it('is withheld (onOpenMapTab undefined) while there is nothing to map, the same rule that '
      + 'withholds mapPane/onOpenFullMap', async () => {
    fetchForecasts.mockResolvedValue([]); // allDates stays empty
    renderApp();
    await screen.findByTestId('stub-shell');
    // The forecast chain resolving [] changes no DOM the shell stub renders, so there is nothing
    // to findBy for it — one flush drains the remaining microtasks (App.test.jsx's own idiom for
    // this exact case).
    await act(async () => {});
    expect(ShellStub.lastProps.onOpenMapTab).toBeUndefined();
  });

  it('sets the date, and hands the Map pane a source:\'plan\' handoff carrying the door\'s own '
      + 'fields plus a nonce, on the SAME channel openFullMapTab uses', async () => {
    renderApp();
    await screen.findByTestId('stub-map-pane');

    await act(async () => {
      fireEvent.click(screen.getByTestId('trigger-door'));
    });

    expect(MapPaneStub.lastProps.selectedDate).toBe(TOMORROW);
    const handoff = MapPaneStub.lastProps.handoff;
    expect(handoff.source).toBe('plan');
    expect(handoff.eventType).toBe('SUNSET');
    expect(handoff.date).toBe(TOMORROW);
    expect(handoff.region).toBe('Lake District');
    expect(handoff.minRating).toBe(4);
    expect(handoff.limitMinutes).toBe(150);
    expect(handoff.locationName).toBe('Bamburgh');
    expect(typeof handoff.nonce).toBe('number');
  });

  it('requests the Map tab with its OWN nonce, distinct from the handoff\'s — proven by a genuine '
      + 'inequality, not just "both are numbers" (a regression collapsing tabRequestNonce and '
      + 'handoffNonce onto one shared ref would still pass a same-type check)', async () => {
    renderApp();
    await screen.findByTestId('stub-map-pane');

    await act(async () => {
      fireEvent.click(screen.getByTestId('trigger-door'));
    });

    expect(ShellStub.lastProps.tabRequest.id).toBe('map');
    expect(typeof ShellStub.lastProps.tabRequest.nonce).toBe('number');
    expect(ShellStub.lastProps.tabRequest.nonce).not.toBe(MapPaneStub.lastProps.handoff.nonce);
  });

  it('a null field on the door (no region/rating/reach/location) lands as null, never undefined, '
      + 'on the handoff', async () => {
    renderApp();
    await screen.findByTestId('stub-map-pane');
    // Re-point the trigger at a minimal door for this one assertion.
    await act(async () => {
      ShellStub.lastProps.onOpenMapTab({ date: TOMORROW, targetType: 'SUNRISE' });
    });

    const handoff = MapPaneStub.lastProps.handoff;
    expect(handoff.region).toBeNull();
    expect(handoff.minRating).toBeNull();
    expect(handoff.limitMinutes).toBeNull();
    expect(handoff.locationName).toBeNull();
  });
});

describe('App — returnToPlan (the breadcrumb\'s ← Plan, doors D2)', () => {
  it('requests the plan tab, with no window key carried (plan §6 Q2, decided)', async () => {
    renderApp();
    await screen.findByTestId('stub-map-pane');

    await act(async () => {
      MapPaneStub.lastProps.onReturnToPlan();
    });

    expect(ShellStub.lastProps.tabRequest.id).toBe('plan');
    expect(typeof ShellStub.lastProps.tabRequest.nonce).toBe('number');
  });

  it('is a nonce that moves on every call, so a repeat press still lands', async () => {
    renderApp();
    await screen.findByTestId('stub-map-pane');

    await act(async () => { MapPaneStub.lastProps.onReturnToPlan(); });
    const first = ShellStub.lastProps.tabRequest.nonce;
    await act(async () => { MapPaneStub.lastProps.onReturnToPlan(); });
    const second = ShellStub.lastProps.tabRequest.nonce;

    expect(second).not.toBe(first);
  });
});
