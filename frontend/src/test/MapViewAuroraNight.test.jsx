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
 *
 * ⚠️ Rewritten for map-tab-v2-plan.md §3 P6, which removed `ForecastTypeSelector` from the Map TAB
 * mount. The old mocked selector's "AURORA" button was a PURE kind switch — it never touched
 * `date` — and every test in this file depends on exactly that isolation (the auto-jump latch and
 * the viewline gate are both about what happens to a date the reader did NOT just pick). The real
 * replacement, `components/map/WindowControl.jsx`, has no such pure switch: every dropdown row
 * NAMES a date, so picking one is a combined kind+date choice by design (map-tab-v2-plan.md §3
 * P6's EV-ownership paragraph) — exercising that coupling is `WindowControl.test.jsx`'s job, not
 * this one's. `handoffEventType` — a real, pre-existing `MapView` prop that already sets
 * `eventType` with no date implication (Plan-tab event-type handoffs use it the same way) — is the
 * faithful stand-in: it isolates the kind axis exactly as the removed mock did.
 */
process.env.TZ = 'Europe/London';

import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act, waitFor, fireEvent } from '@testing-library/react';

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
  STAND_DOWN_COLOUR: '#501313',
}));

import MapView from '../components/MapView.jsx';
import { getAuroraForecastResults } from '../api/auroraApi.js';

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

/**
 * Renders `MapView` and returns a `rerender`-with-the-same-base-props helper alongside it, since
 * `enterAuroraMode` below needs to reapply every prop the test already set, not merely the one it
 * changes — `rerender` replaces the whole element rather than merging into it.
 */
async function renderMap(overrides = {}) {
  const props = {
    locations: makeLocations(),
    date: THE_CALENDAR_DAY,
    autoEventType: null,
    ...overrides,
  };
  let result;
  await act(async () => { result = render(<MapView {...props} />); });
  const withProps = async (nextOverrides) => {
    await act(async () => {
      result.rerender(<MapView {...props} {...nextOverrides} />);
    });
  };
  return { ...result, props, withProps };
}

/**
 * "Entering aurora mode" — a pure kind switch, exactly what the removed mocked selector's AURORA
 * button did. `handoffEventType` sets `eventType` with no date implication (see the file header),
 * which is what every test below needs isolated: the auto-jump latch and the viewline gate are
 * both about what happens to a date the reader did NOT just pick via the window control.
 */
const enterAuroraMode = async (rendered) => {
  await rendered.withProps({ handoffEventType: 'AURORA' });
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
    const rendered = await renderMap({ date: THE_NIGHT });
    await enterAuroraMode(rendered);

    expect(screen.getByTestId('aurora-viewline-overlay')).toBeInTheDocument();
  });

  it('hides the viewline on the calendar day, whose night has not begun', async () => {
    // The mirror image, and the reason this is a gate rather than a removal: at 02:00 the window
    // named by today's date starts at dusk, ~19 hours away. A nowcast does not belong on it.
    const rendered = await renderMap({ date: THE_CALENDAR_DAY, onSelectDate: null });
    await enterAuroraMode(rendered);

    expect(screen.queryByTestId('aurora-viewline-overlay')).not.toBeInTheDocument();
  });

  it('falls back to the local date when the backend sends no night', async () => {
    // A backend older than the field, or a browser on a cached bundle. The fallback is the old
    // behaviour exactly: the viewline shows on the calendar date and nowhere else.
    auroraStatusRef.current = STATUS_WITHOUT_NIGHT;
    availableDatesRef.current = [THE_CALENDAR_DAY];
    const rendered = await renderMap({ date: THE_CALENDAR_DAY });
    await enterAuroraMode(rendered);

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
    const rendered = await renderMap({ date: THE_CALENDAR_DAY, onSelectDate });
    await enterAuroraMode(rendered);

    await waitFor(() => expect(onSelectDate).toHaveBeenCalledWith(THE_NIGHT));
  });

  it('leaves the date alone when it already has aurora results', async () => {
    // Someone who arrived on a scored night is looking at what they came for. Moving them would be
    // the component overriding a choice already made.
    const onSelectDate = vi.fn();
    availableDatesRef.current = [THE_NIGHT, THE_CALENDAR_DAY];
    const rendered = await renderMap({ date: THE_CALENDAR_DAY, onSelectDate });
    await enterAuroraMode(rendered);

    expect(onSelectDate).not.toHaveBeenCalled();
  });

  it('leaves the date alone when the night in progress has no results either', async () => {
    // Nothing was run for this night, so jumping would swap one empty day for another.
    const onSelectDate = vi.fn();
    availableDatesRef.current = ['2026-08-01'];
    const rendered = await renderMap({ date: THE_CALENDAR_DAY, onSelectDate });
    await enterAuroraMode(rendered);

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
    const rendered = await renderMap({ date: THE_NIGHT, onSelectDate });
    await enterAuroraMode(rendered);

    // Nothing to do on entry — already on the night.
    expect(onSelectDate).not.toHaveBeenCalled();

    // The reader now picks a night with no results. This must be honoured, not undone. Simulates
    // the parent (App) moving `date` after a real selection elsewhere — the window control's own
    // date-forwarding contract is `WindowControl.test.jsx`'s concern, not this latch's.
    await rendered.withProps({ date: THE_CALENDAR_DAY, handoffEventType: 'AURORA', locations: makeLocations() });

    expect(onSelectDate).not.toHaveBeenCalled();
  });

  it('does not ask again after the reader moves the date themselves', async () => {
    // The latch. It fires once per entry into aurora mode, so the strip stays the reader's —
    // including a deliberate move to a night with nothing on it. Without this the component would
    // pull them straight back and the strip would be unusable in aurora mode.
    const onSelectDate = vi.fn();
    const rendered = await renderMap({ date: THE_CALENDAR_DAY, onSelectDate });
    await enterAuroraMode(rendered);
    await waitFor(() => expect(onSelectDate).toHaveBeenCalledTimes(1));

    // The reader picks an unscored day; the parent feeds it back down as the new `date`.
    await rendered.withProps({
      date: '2026-08-12', handoffEventType: 'AURORA',
      locations: makeLocations([THE_NIGHT, THE_CALENDAR_DAY, '2026-08-12']),
    });

    expect(onSelectDate).toHaveBeenCalledTimes(1);
  });

  it('asks again on a fresh entry into aurora mode', async () => {
    // The latch resets on leaving, so the jump is available every time aurora mode is entered —
    // it is a default for entering the mode, not a one-shot for the session.
    const onSelectDate = vi.fn();
    const rendered = await renderMap({ date: THE_CALENDAR_DAY, onSelectDate });
    await enterAuroraMode(rendered);
    await waitFor(() => expect(onSelectDate).toHaveBeenCalledTimes(1));

    await rendered.withProps({ handoffEventType: 'SUNSET' });
    await enterAuroraMode(rendered);

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
    //
    // ⚠️ Adversarial review: an earlier revision asserted only the viewline's absence here, which
    // is already the "hides the viewline on the calendar day" test's own claim above (THE_CALENDAR_DAY
    // is not the night in progress either way) — a byte-for-byte duplicate that dropped this test's
    // actual point. What is specific to THIS test is that aurora mode still takes with no handler
    // at all, rather than throwing or silently failing to switch. The viewline can't prove that on
    // its own (it would read absent whether the mode took and found nothing, or never took at
    // all), so — mirroring `MapViewAstro.test.jsx`'s own proxy — this reads `isAuroraMode` off the
    // dark-sky toggle's visibility gate (`!isAuroraMode && !isAstroMode`), which is a mode question
    // with no dependence on THE_CALENDAR_DAY having a night in progress.
    const rendered = await renderMap({ date: THE_CALENDAR_DAY, onSelectDate: null });
    expect(screen.getByTestId('dark-sky-filter-toggle')).toBeInTheDocument();
    await enterAuroraMode(rendered);

    expect(screen.queryByTestId('dark-sky-filter-toggle')).not.toBeInTheDocument();
  });
});

/**
 * The EV-ownership rule's KEPT-LOCAL branch, driven through the REAL `components/map/
 * WindowControl.jsx` rather than the `handoffEventType` proxy every other test in this file uses
 * (adversarial review, BLOCKING #2 — this branch had ZERO coverage). `enterAuroraMode` above is
 * deliberately a pure kind switch precisely so the tests around it can isolate the auto-jump
 * latch's own behaviour from date movement; this describe block is the one place that instead
 * clicks an actual dropdown row, which — unlike a handoff — is a combined kind+date choice.
 *
 * <p>The auto-jump latch is kept genuinely inert throughout (not merely coincidentally quiet):
 * `auroraStatusRef.current = null` makes `resolveAuroraNight` fall back to the UK calendar date at
 * the frozen clock (`2026-08-14`), which is never a member of `availableDatesRef.current` here —
 * so the latch's own `!auroraAvailableDates.includes(auroraNight)` guard returns early on every
 * render, regardless of which row this test clicks. Without that, the latch's independent
 * "jump to the night in progress" behaviour (already pinned above) would confound this test's own
 * claim about row-selection.
 */
describe('MapView aurora night — the KEPT-LOCAL branch via the real window control (adversarial review, BLOCKING #2)', () => {
  /** Has stored aurora results, but is deliberately NOT one of `forecastDates` below. */
  const KEPT_LOCAL_NIGHT = '2026-08-10';
  const START_DATE = THE_CALENDAR_DAY;

  beforeEach(() => {
    localStorage.clear();
    mockUseAuth.mockReturnValue({ role: 'ADMIN' });
    auroraStatusRef.current = null;
    availableDatesRef.current = [KEPT_LOCAL_NIGHT];
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(SMALL_HOURS));
  });
  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  it('selects locally (a) never forwards, (b) still fetches that night\'s own content, and (c) a later external date change replaces it cleanly', async () => {
    const onSelectDate = vi.fn();
    getAuroraForecastResults.mockClear();
    const rendered = await renderMap({
      date: START_DATE,
      forecastDates: [START_DATE], // deliberately omits KEPT_LOCAL_NIGHT
      onSelectDate,
    });

    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
    const row = screen.getAllByTestId('wf-win-row')
      .find((r) => r.getAttribute('data-ev-id') === `aur:${KEPT_LOCAL_NIGHT}:AURORA`);
    expect(row).toBeTruthy();
    await act(async () => { fireEvent.click(row); });

    // (a) `App`'s `effectiveDate` guard would reject KEPT_LOCAL_NIGHT outright (it is not in
    // `allDates`), so the pane must never even ask.
    expect(onSelectDate).not.toHaveBeenCalled();
    // (b) the night's own content is fetched — proof `nightDate` resolved to the kept-local
    // override rather than staying on the stale `date` prop.
    await waitFor(() => expect(getAuroraForecastResults).toHaveBeenCalledWith(KEPT_LOCAL_NIGHT));

    // (c) an external date change — the parent moving `date` for a reason that has nothing to do
    // with this pane's own row selection (a Coming-up card, another handoff, the auto-jump latch
    // elsewhere) — must invalidate the kept-local override rather than leaving it stuck forever
    // (adversarial review, BLOCKING #1: `localNightDate` used to be touched in exactly one place).
    getAuroraForecastResults.mockClear();
    const EXTERNAL_DATE = '2026-08-20';
    await rendered.withProps({
      date: EXTERNAL_DATE,
      forecastDates: [EXTERNAL_DATE],
      onSelectDate,
      locations: makeLocations([EXTERNAL_DATE]),
    });
    await waitFor(() => expect(getAuroraForecastResults).toHaveBeenCalledWith(EXTERNAL_DATE));
  });
});
