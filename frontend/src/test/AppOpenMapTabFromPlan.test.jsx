/**
 * `App.jsx`'s `openMapTabFromPlan` (doors D2, `plan-to-map-doors-plan.md` §3 D2 task 1) — the
 * sending side of the SAME `mapTabHandoff`/`tabRequest` channel `openFullMapTab` already uses,
 * distinguished downstream by `source: 'plan'`.
 *
 * No door UI ships in this phase (D3/D4 add the real buttons), so — the same problem
 * `WindowFirstShellLocationSheetHandoff.test.jsx` solves for `openLocationSheet`'s reverse-direction
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

import { fetchForecasts, fetchLocations } from '../api/forecastApi.js';
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

  it('an inPlace door carries NO source, NO lens and NO tabRequest — the reader is already there', async () => {
    // ⚠️ The four-day sheet can now be open OVER the Map tab (the callout's `Four days here ›`
    // peek), and its footer's `Show on map → <window>` presses this same entry from there. A door
    // payload would be destructive rather than redundant: `MapView`'s landing effect writes
    // `minRating` — and PERSISTS it to `mapFilterMinStars` — writes `limitMinutes`, and calls
    // `resetToMyArea()` for a `region: null` door, flipping scope and refitting the camera. So a
    // reader who had set a 4★ floor and jumped to a region ON THAT MAP would have all three
    // silently undone by a press that only asked to change the window.
    renderApp();
    await screen.findByTestId('stub-map-pane');

    await act(async () => {
      ShellStub.lastProps.onOpenMapTab({ ...DOOR, inPlace: true });
    });

    const handoff = MapPaneStub.lastProps.handoff;
    // ⚠️ `source: 'map'`, and NOT an absent source. A source-less payload is the overlay hatch's
    // shape, and the hatch's own event effect sets `userHasOverriddenEvent` FALSE — which re-runs
    // `MapView`'s auto-selection effect and replaces the window this press just named with
    // `autoEventType` a tick later. The first cut of this route did exactly that (Codex review,
    // P1): `Show on map → Monday sunrise`, pressed from a map the reader had manually stepped to,
    // landed on tonight's sunset. `'map'` rides the STRUCTURED channel, whose effect marks the
    // event an explicit selection.
    expect(handoff.source).toBe('map');
    expect(handoff.eventType).toBe('SUNSET');
    expect(handoff.date).toBe(TOMORROW);
    expect(handoff.locationName).toBe('Bamburgh');
    // The three fields the landing effect would have applied are absent, not null-and-applied.
    expect(handoff.minRating).toBeUndefined();
    expect(handoff.limitMinutes).toBeUndefined();
    expect(handoff.region).toBeUndefined();
    expect(MapPaneStub.lastProps.selectedDate).toBe(TOMORROW);
  });

  it('an inPlace door raises no tabRequest — a tab move would only steal focus to the tab in force', async () => {
    renderApp();
    await screen.findByTestId('stub-map-pane');
    const before = ShellStub.lastProps.tabRequest;

    await act(async () => {
      ShellStub.lastProps.onOpenMapTab({ ...DOOR, inPlace: true });
    });

    expect(ShellStub.lastProps.tabRequest).toBe(before);
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

describe('App — openMapTabFromPlan with a normalised trigger (overlay convergence C1)', () => {
  /** Opens the door with `arg` and returns the handoff the Map pane received. */
  async function press(arg) {
    renderApp();
    await screen.findByTestId('stub-map-pane');
    await act(async () => { ShellStub.lastProps.onOpenMapTab(arg); });
    return MapPaneStub.lastProps.handoff;
  }

  it('a plain door\'s payload carries NONE of the new fields - absent, not null', async () => {
    const handoff = await press(DOOR);
    for (const key of ['night', 'focusNames', 'focusPoints', 'filterAction', 'darkSky', 'title', 'caption']) {
      expect(handoff).not.toHaveProperty(key);
    }
  });

  it('a door that already names its fields passes them through to the handoff', async () => {
    const handoff = await press({
      ...DOOR,
      focusNames: ['Bamburgh'],
      focusPoints: [[55.608, -1.719]],
      filterAction: 'SEASCAPE',
      darkSky: true,
      title: 'Coastal',
      caption: 'Tomorrow sunset',
    });
    expect(handoff).toMatchObject({
      source: 'plan',
      focusNames: ['Bamburgh'],
      focusPoints: [[55.608, -1.719]],
      filterAction: 'SEASCAPE',
      darkSky: true,
      title: 'Coastal',
      caption: 'Tomorrow sunset',
    });
  });

  it('a kind:\'location\' trigger resolves to the same door the location sheet sends', async () => {
    const handoff = await press({
      kind: 'location', locationName: 'Bamburgh', date: TOMORROW, eventType: 'SUNSET',
    });
    expect(handoff).toMatchObject({
      source: 'plan', eventType: 'SUNSET', date: TOMORROW, locationName: 'Bamburgh', region: null,
    });
    expect(MapPaneStub.lastProps.selectedDate).toBe(TOMORROW);
    expect(handoff.title).toBe('Bamburgh');
  });

  it('a trigger carrying the Plan lens lands in the handoff with it, not as nulls', async () => {
    const handoff = await press({
      kind: 'location', locationName: 'Bamburgh', date: TOMORROW, eventType: 'SUNSET', minRating: 4, limitMinutes: 90,
    });
    expect(handoff.minRating).toBe(4);
    expect(handoff.limitMinutes).toBe(90);
  });

  it('a trigger carrying inPlace takes the in-place branch: source map, no lens', async () => {
    const handoff = await press({
      kind: 'location', locationName: 'Bamburgh', date: TOMORROW, eventType: 'SUNSET', inPlace: true,
    });
    expect(handoff.source).toBe('map');
  });

  it('a kind:\'coming-up\' trigger carries the exact spot set, the subject filter and its headline', async () => {
    const handoff = await press({
      kind: 'coming-up', filterAction: 'SEASCAPE', darkSky: false, label: 'Spring tide run', date: TOMORROW,
    });
    expect(handoff.source).toBe('plan');
    expect(handoff.focusNames).toEqual(['Bamburgh']);
    expect(handoff.focusPoints).toHaveLength(1);
    expect(handoff.filterAction).toBe('SEASCAPE');
    expect(handoff.title).toBe('Spring tide run');
    // A coming-up card names no solar event: the handoff carries none rather than inventing one.
    expect(handoff.eventType).toBeNull();
    expect(handoff).not.toHaveProperty('darkSky');
  });

  it('C4: a kind:\'region\' trigger (the pick dialog\'s Show region) opens the region\'s top spot on its window, headlined by the region', async () => {
    const handoff = await press({
      kind: 'region', region: 'Northumberland', date: TOMORROW, eventType: 'SUNSET',
      locationNames: null, label: null, filterAction: null,
      minRating: 3, limitMinutes: 45, inPlace: false,
    });
    // The overlay's own resolution is kept (C1 reads it, never re-decides it): a region with rated
    // spots lands on its TOP spot's callout, the crumb headline naming the region.
    expect(handoff).toMatchObject({
      source: 'plan', locationName: 'Bamburgh', eventType: 'SUNSET', date: TOMORROW,
      minRating: 3, limitMinutes: 45, title: 'Northumberland',
    });
    expect(handoff).not.toHaveProperty('night');
    expect(ShellStub.lastProps.tabRequest).toMatchObject({ id: 'map' });
  });

  it('a region trigger whose top spot is a marked neighbour outlier lands on the region with NO spot', async () => {
    const handoff = await press({
      kind: 'region', region: 'Northumberland', date: TOMORROW, eventType: 'SUNSET',
      locationNames: null, label: null, filterAction: null,
      minRating: 3, limitMinutes: 45, inPlace: false,
      outlierKeys: new Set([`Bamburgh|${TOMORROW}|SUNSET`]),
    });
    expect(handoff).toMatchObject({ source: 'plan', region: 'Northumberland', locationName: null });
    expect(handoff).not.toHaveProperty('outlierKeys');
  });

  it('C4: a kind:\'event\' trigger (the planner\'s drill-down pin) names its window and the Plan lens', async () => {
    const handoff = await press({
      kind: 'event', date: TOMORROW, eventType: 'SUNRISE', minRating: 4, limitMinutes: 90, inPlace: false,
    });
    expect(handoff).toMatchObject({
      source: 'plan', eventType: 'SUNRISE', date: TOMORROW, minRating: 4, limitMinutes: 90,
    });
    expect(handoff).not.toHaveProperty('night');
  });

  it('T3: a kind:\'event\' trigger with a topicType puts it in the handoff', async () => {
    const handoff = await press({ kind: 'event', date: TOMORROW, eventType: 'SUNSET', topicType: 'DUST' });
    expect(handoff).toMatchObject({ source: 'plan', eventType: 'SUNSET', date: TOMORROW, topicType: 'DUST' });
  });

  it('T3: the plain kind:\'event\' trigger has no topicType key', async () => {
    const handoff = await press({ kind: 'event', date: TOMORROW, eventType: 'SUNSET' });
    expect(handoff).not.toHaveProperty('topicType');
  });

  it('C4: a dark-sky coming-up trigger carries the dark-sky lens and the card\'s headline, no subject filter', async () => {
    const handoff = await press({
      kind: 'coming-up', darkSky: true, filterAction: null, label: 'Meteor shower', date: TOMORROW,
      minRating: null, limitMinutes: null, inPlace: false,
    });
    expect(handoff).toMatchObject({ source: 'plan', darkSky: true, title: 'Meteor shower' });
    expect(handoff).not.toHaveProperty('filterAction');
  });

  it('a kind:\'aurora\' trigger names its night, its AURORA event and its headline, and moves the date', async () => {
    const handoff = await press({ kind: 'aurora', date: TOMORROW });
    expect(handoff).toMatchObject({
      source: 'plan',
      eventType: 'AURORA',
      date: TOMORROW,
      night: { kind: 'aurora', date: TOMORROW },
      title: 'Aurora tonight',
    });
    expect(MapPaneStub.lastProps.selectedDate).toBe(TOMORROW);
  });

  it('⚠️ a night door is handed up AS A NIGHT: the running night, dated before today, survives the date resolver', async () => {
    // The reason the `isNight` flag exists: `resolveMapDate` refuses a date before today as a SOLAR
    // date, so without the flag `selectDate(door.date)` would silently land the map on a different
    // day. Only the night in progress (yesterday's date until dawn) is let through, and only when
    // it was chosen as a night.
    const YESTERDAY = ukDateStrOffset(-1);
    fetchForecasts.mockResolvedValue([
      ...FORECASTS,
      { ...forecastRow(LOCATION_META[0], 'SUNSET'), targetDate: YESTERDAY },
    ]);
    getAuroraStatus.mockResolvedValue({ currentNightDate: YESTERDAY, currentNightEndsAt: null });
    renderApp();
    await screen.findByTestId('stub-map-pane');
    await act(async () => {});

    await act(async () => { ShellStub.lastProps.onOpenMapTab({ kind: 'aurora', date: YESTERDAY }); });

    expect(MapPaneStub.lastProps.selectedDate).toBe(YESTERDAY);
    // And the same date as a SOLAR door is refused, which is what makes the assertion above mean something.
    await act(async () => {
      ShellStub.lastProps.onOpenMapTab({ date: YESTERDAY, targetType: 'SUNSET' });
    });
    expect(MapPaneStub.lastProps.selectedDate).not.toBe(YESTERDAY);
  });

  it('a trigger still asks for the Map tab, with its own nonce', async () => {
    await press({ kind: 'aurora', date: TOMORROW });
    expect(ShellStub.lastProps.tabRequest.id).toBe('map');
  });
});

describe('App — a door never gives the Plan tab a date (plan 4 #7)', () => {
  // `App`'s `selectedDate` is the MAP's window (only `MapView` and the pane read it; the shell and the
  // Plan matrix have no selected date at all), so a door writing it moves the Map's window - which is
  // the point of a door - and nothing the Plan shows. These pin the two halves of that: the Plan
  // side is handed no date, and `← Plan` carries none back.
  const KINDS = {
    'a plain door': DOOR,
    'a location trigger': { kind: 'location', locationName: 'Bamburgh', date: TOMORROW, eventType: 'SUNSET' },
    'a coming-up trigger': {
      kind: 'coming-up', filterAction: 'SEASCAPE', darkSky: false, label: 'Coastal', date: TOMORROW,
    },
    'an aurora trigger': { kind: 'aurora', date: TOMORROW },
    'a region trigger': { kind: 'region', region: 'Northumberland', date: TOMORROW, eventType: 'SUNSET' },
    'an event trigger': { kind: 'event', date: TOMORROW, eventType: 'SUNSET' },
  };

  for (const [name, arg] of Object.entries(KINDS)) {
    it(`${name}: the shell is given no selected date before or after, and ← Plan asks for the tab alone`, async () => {
      renderApp();
      await screen.findByTestId('stub-map-pane');
      const planKeys = () => Object.keys(ShellStub.lastProps).filter((k) => /date/i.test(k));
      const before = planKeys();
      expect(before).not.toContain('selectedDate');

      await act(async () => { ShellStub.lastProps.onOpenMapTab(arg); });
      // The Map shows the door's window...
      expect(MapPaneStub.lastProps.selectedDate).toBe(TOMORROW);
      // ...and the Plan side learned of no date.
      expect(planKeys()).toEqual(before);

      await act(async () => { MapPaneStub.lastProps.onReturnToPlan(); });
      expect(Object.keys(ShellStub.lastProps.tabRequest).sort()).toEqual(['id', 'nonce']);
      expect(ShellStub.lastProps.tabRequest.id).toBe('plan');
    });
  }
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

describe('App — the forecast dates handed to the shell (plan §11.24)', () => {
  it('passes the dates the reader holds a colour forecast for, so Coming up can withhold a door '
      + 'onto any other date', async () => {
    renderApp();
    await screen.findByTestId('stub-map-pane');
    expect(ShellStub.lastProps.forecastDates).toEqual([TOMORROW]);
  });

  it('passes null until the forecast lands and the dates after — unknown is not an empty list', async () => {
    let resolveForecasts;
    fetchForecasts.mockReset().mockReturnValue(new Promise((resolve) => { resolveForecasts = resolve; }));
    renderApp();
    await screen.findByTestId('stub-shell');
    // The fetch is outstanding, so `allDates` is empty and the doors must not read that as "none".
    expect(ShellStub.lastProps.forecastDates).toBeNull();

    await act(async () => { resolveForecasts(FORECASTS); });
    // The Map pane only mounts once forecast dates exist: it is the post-forecast signal.
    await screen.findByTestId('stub-map-pane');
    expect(ShellStub.lastProps.forecastDates).toEqual([TOMORROW]);
  });

  it('passes null after a failed forecast fetch with nothing on screen — a failure is not an absence', async () => {
    fetchForecasts.mockReset().mockRejectedValue(new Error('502 from /api/forecast'));
    renderApp();
    await screen.findByTestId('stub-shell');
    await act(async () => {});
    expect(ShellStub.lastProps.forecastDates).toBeNull();
  });

  it('passes an empty array, not null, once a forecast has landed with no dates in it', async () => {
    fetchForecasts.mockResolvedValue([]);
    renderApp();
    await screen.findByTestId('stub-shell');
    await act(async () => {});
    expect(ShellStub.lastProps.forecastDates).toEqual([]);
  });
});
