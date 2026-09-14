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
import { render, screen, act, waitFor, fireEvent, within } from '@testing-library/react';

// ── Leaflet / react-leaflet stubs ────────────────────────────────────────────

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = () => ({});
  return { default: { icon, divIcon }, icon, divIcon };
});

vi.mock('leaflet/dist/leaflet.css', () => ({}));

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
  STAND_DOWN_COLOUR: '#501313',
}));

import MapView from '../components/MapView.jsx';
import { buildRegionVerdictIndex } from '../utils/mapVerdict.js';
import { getAuroraForecastResults } from '../api/auroraApi.js';
import { resolveMapDate, ukDateStrOffset } from '../utils/mapDates.js';

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

  it('asks for the night in progress, AS A NIGHT, when the selected date has no aurora results', async () => {
    // The headline defect: a forecast run at 02:00 stores under the 13th, the map opens on the
    // 14th, and the run the user paid for appears to have produced nothing.
    //
    // ⚠️ The provenance is half the assertion, not decoration (Codex, #803). `THE_NIGHT` is
    // yesterday at this frozen clock, and `App`'s never-past clamp refuses a past date unless the
    // selection NAMED a night. Asking with a bare date left the jump landing nowhere — and the
    // latch is set BEFORE the call, so it never retries: the exact "paid run looks empty" symptom
    // this test was written for, re-created by the clamp that shipped alongside it.
    const onSelectDate = vi.fn();
    const rendered = await renderMap({ date: THE_CALENDAR_DAY, onSelectDate });
    await enterAuroraMode(rendered);

    await waitFor(() => expect(onSelectDate).toHaveBeenCalledWith(THE_NIGHT, { isNight: true }));
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
    // The dark-sky toggle lives in `FiltersPopover` now (map-tab-v2-plan.md §3 P7), which mounts
    // its rows only while open — `enterAuroraMode` below rerenders via `handoffEventType` rather
    // than through the window control, so it never touches (and so never closes) this popover.
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(screen.getByTestId('dark-sky-filter-toggle')).toBeInTheDocument();
    await enterAuroraMode(rendered);

    expect(screen.queryByTestId('dark-sky-filter-toggle')).not.toBeInTheDocument();
  });
});

/**
 * PR #731 review: `MapView.jsx`'s astro/aurora multi-date fetch used to hand the raw available-
 * dates list straight to `Promise.all` — and those endpoints answer with every distinct date ever
 * persisted (writers replace a rerun date's row rather than pruning it), so a long-lived database
 * fanned a single Map-tab mount out to hundreds of concurrent requests. `solarHorizonDates`
 * (`utils/mapEvents.js`) is the bound: the SAME domain `buildMapEvents` derives its D-13 filler
 * rows from — briefing dates plus `forecastDates`, UK-civil today-forward.
 *
 * <p>Deliberately NOT built on `THE_NIGHT`/`THE_CALENDAR_DAY`/`SMALL_HOURS`: at that frozen
 * instant `THE_NIGHT` (yesterday's date) is itself before the UK-civil today the horizon clips to
 * — correct for D-13's solar filler rows (yesterday's sunrise/sunset really has elapsed), but a
 * different question from this test's own claim, which only needs a plain forward-looking horizon
 * with no night-vs-calendar wrinkle in it.
 */
describe('MapView aurora night — bounding the multi-date preview fetch (PR #731 review)', () => {
  const HORIZON_DAY = '2026-09-10';
  const HORIZON_TOMORROW = '2026-09-11';

  beforeEach(() => {
    localStorage.clear();
    mockUseAuth.mockReturnValue({ role: 'ADMIN' });
    auroraStatusRef.current = { level: 'MODERATE', kpIndex: 5.0, currentNightDate: HORIZON_DAY };
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(`${HORIZON_DAY}T12:00:00Z`));
  });
  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  it('fetches only the horizon dates, not the full available-dates list', async () => {
    const historical = Array.from({ length: 200 }, (_, i) => (
      ukDateStrOffset(-(i + 10), new Date(`${HORIZON_DAY}T12:00:00Z`))
    ));
    availableDatesRef.current = [...historical, HORIZON_DAY, HORIZON_TOMORROW];
    getAuroraForecastResults.mockClear();

    await renderMap({
      date: HORIZON_DAY,
      forecastDates: [HORIZON_DAY, HORIZON_TOMORROW],
      locations: makeLocations([HORIZON_DAY, HORIZON_TOMORROW]),
    });
    await waitFor(() => expect(getAuroraForecastResults).toHaveBeenCalled());

    const fetchedDates = getAuroraForecastResults.mock.calls.map((args) => args[0]).sort();
    expect(fetchedDates).toEqual([HORIZON_DAY, HORIZON_TOMORROW].sort());
    expect(getAuroraForecastResults).toHaveBeenCalledTimes(2);
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
  /**
   * Has stored aurora results, but is deliberately NOT one of `forecastDates` below.
   *
   * <p>⚠️ A night AHEAD of the frozen clock, and it has to be. This was `2026-08-10`, four nights
   * past, until D-14 (map-tab-v2-plan.md §5) stopped offering any night that is over — the row this
   * block clicks would no longer exist. A night beyond the forecast's own dates is also the honest
   * shape of this branch: the astro/aurora rows can outrun `GET /api/forecast`'s range.
   */
  const KEPT_LOCAL_NIGHT = '2026-08-17';
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

  it('does not let the auto-jump stomp a kept-local selection, even when the auto-jump\'s own conditions all hold (PR #731 review)', async () => {
    // Sequence the bug used to allow: pick an out-of-domain aurora row (kept local) → the SAME
    // render also flips `isAuroraMode` to true → the auto-jump effect, reading the RAW `date`
    // prop (untouched by a kept-local pick), sees its own three conditions all satisfied and
    // calls `onSelectDate(auroraNight)` regardless of what the reader just chose.
    // The real night in progress at this frozen clock, not an arbitrary date: since D-14 the
    // backend's `currentNightDate` also decides which past night the list may still offer, so a
    // value it could never send would make this fixture describe a list production cannot produce.
    const AUTOJUMP_NIGHT = THE_NIGHT;
    auroraStatusRef.current = { level: 'MODERATE', kpIndex: 5.0, currentNightDate: AUTOJUMP_NIGHT };
    // Both nights have results: AUTOJUMP_NIGHT so the latch's "nothing to land on yet" guard does
    // NOT block it (the whole point is proving suppression happens for a DIFFERENT reason), and
    // KEPT_LOCAL_NIGHT so there is a real dropdown row to click.
    availableDatesRef.current = [KEPT_LOCAL_NIGHT, AUTOJUMP_NIGHT];
    const onSelectDate = vi.fn();
    getAuroraForecastResults.mockClear();

    await renderMap({ date: START_DATE, forecastDates: [START_DATE], onSelectDate });

    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
    const row = screen.getAllByTestId('wf-win-row')
      .find((r) => r.getAttribute('data-ev-id') === `aur:${KEPT_LOCAL_NIGHT}:AURORA`);
    expect(row).toBeTruthy();
    await act(async () => { fireEvent.click(row); });

    // The auto-jump's three conditions all hold at this point — `auroraNight` (AUTOJUMP_NIGHT)
    // has results, and `date` (START_DATE) is neither `auroraNight` nor itself a member of
    // `auroraAvailableDates` — so without the `localNightDate` guard this calls
    // `onSelectDate(AUTOJUMP_NIGHT)`, which is exactly the stomp PR #731 review found.
    expect(onSelectDate).not.toHaveBeenCalled();
    // And the reader's own pick is still what is showing, never silently replaced by the night
    // the auto-jump would have preferred. ⚠️ Read off the PILL, not off which dates were fetched: it
    // used to assert AUTOJUMP_NIGHT was never fetched, but since D-14 the preview fetch covers the
    // night in progress on purpose (its row must carry a best), so that fetch proves nothing now.
    // KEPT_LOCAL_NIGHT, 2026-08-17, is a Monday.
    await waitFor(() => expect(getAuroraForecastResults).toHaveBeenCalledWith(KEPT_LOCAL_NIGHT));
    expect(screen.getByRole('button', { name: /Monday night/ })).toHaveAttribute('aria-haspopup', 'listbox');
  });
});

/**
 * The kept-local branch's SECOND trigger — a night row whose date is IN `forecastDates` but whose
 * night `App` would refuse.
 *
 * <p>⚠️ This is a regression PR #803 introduced and Codex caught. `buildMapEvents` then clipped no
 * night row at all and was handed the RAW available-date lists, while `GET /api/forecast` serves
 * `today-2` onward — so last night's aurora row was both offered in the dropdown AND
 * `inForecastDomain`. The forward branch therefore fired: it cleared `localNightDate` and asked the
 * parent to adopt a past date, which `resolveMapDate`'s never-past clamp then refused. The row
 * could be selected and went nowhere.
 *
 * <p>The pane's forwardability test mirrors the parent's ACCEPTANCE rule — that is what
 * `localNightDate` is for — and since D-14 (map-tab-v2-plan.md §5) it is that rule exactly:
 * `mapEvents.isForwardableRow` reads the same `mapDates.isNightOver` `resolveMapDate` does. The first
 * two cases are its two sides. Each computes `App`'s answer IN the test, from `resolveMapDate` on the
 * same inputs, so the premise cannot go stale unnoticed — it did once: when D-14 first moved the
 * opening case into the small hours, its comment went on saying `App` would refuse a night `App` in
 * fact accepts.
 */
describe('MapView aurora night — a PAST night row inside the forecast domain (Codex, #803)', () => {
  /** Yesterday relative to the frozen clock, and deliberately a MEMBER of `forecastDates`. */
  const PAST_NIGHT = '2026-08-13';
  const TODAY_DATE = '2026-08-14';

  beforeEach(() => {
    localStorage.clear();
    mockUseAuth.mockReturnValue({ role: 'ADMIN' });
    // The latch stays inert: `resolveAuroraNight` falls back to the UK calendar date, which is not
    // in `availableDatesRef`, so the auto-jump returns early on every render.
    auroraStatusRef.current = null;
    availableDatesRef.current = [PAST_NIGHT];
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(`${TODAY_DATE}T12:00:00Z`));
  });
  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  it('FORWARDS the night in progress, as a night — App takes a past date that names one', async () => {
    // 02:00 UK on TODAY_DATE, and the backend names PAST_NIGHT as the night in progress: the only
    // state in which D-14 lets a past-dated night be offered for its own sake. ACTIVE_STATUS's own
    // `currentNightDate` is THE_NIGHT, which is PAST_NIGHT.
    auroraStatusRef.current = ACTIVE_STATUS;
    vi.setSystemTime(new Date(SMALL_HOURS));
    const onSelectDate = vi.fn();
    const forecastDates = [PAST_NIGHT, TODAY_DATE];
    await renderMap({
      date: TODAY_DATE,
      // ⚠️ PAST_NIGHT IS in the domain — that is the whole point. `today-2` onward is what the
      // forecast endpoint serves, so this is the ordinary production shape, not a contrived one.
      forecastDates,
      locations: makeLocations(forecastDates),
      onSelectDate,
    });

    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
    const row = screen.getAllByTestId('wf-win-row')
      .find((r) => r.getAttribute('data-ev-id') === `aur:${PAST_NIGHT}:AURORA`);
    // The row is offered — the night in progress is not over, even though its date is.
    expect(row?.getAttribute('data-ev-id')).toBe(`aur:${PAST_NIGHT}:AURORA`);
    await act(async () => { fireEvent.click(row); });

    // The premise, computed rather than asserted in prose: App's own clamp takes this pair.
    expect(resolveMapDate({
      selectedDate: PAST_NIGHT, selectedIsNight: true, autoDate: null, allDates: forecastDates,
      todayStr: TODAY_DATE, nightDate: PAST_NIGHT,
    })).toBe(PAST_NIGHT);
    // So the pick is handed over, flagged as a night. ⚠️ The FIRST call is the pick's own. This
    // harness's parent never feeds the date back, so the aurora auto-jump then sees a map still on
    // TODAY_DATE and asks for the same night a second time; in the app, the pick has already moved
    // the date and the jump stays quiet.
    expect(onSelectDate).toHaveBeenNthCalledWith(1, PAST_NIGHT, { isNight: true });
  });

  it('keeps an ENDED night local when it is picked again — App would refuse it (#803\'s shape)', async () => {
    // The night the parent holds (after a forward, or the aurora banner's route) is PAST_NIGHT, and
    // then dawn passes before the parent re-clamps. D-14 keeps that ended night's row, because the
    // map is still painting it — and a row App would refuse is exactly what #803 stranded.
    auroraStatusRef.current = ACTIVE_STATUS;
    vi.setSystemTime(new Date(SMALL_HOURS));
    availableDatesRef.current = [PAST_NIGHT, TODAY_DATE];
    const onSelectDate = vi.fn();
    const forecastDates = [PAST_NIGHT, TODAY_DATE];
    const rendered = await renderMap({
      date: PAST_NIGHT, forecastDates, locations: makeLocations(forecastDates), onSelectDate,
    });
    await enterAuroraMode(rendered);

    auroraStatusRef.current = { ...ACTIVE_STATUS, currentNightDate: TODAY_DATE };
    vi.setSystemTime(new Date(`${TODAY_DATE}T06:00:00Z`)); // 07:00 BST — past dawn
    await rendered.withProps({ handoffEventType: 'AURORA', resizeNonce: 1 });

    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
    const row = screen.getAllByTestId('wf-win-row')
      .find((r) => r.getAttribute('data-ev-id') === `aur:${PAST_NIGHT}:AURORA`);
    expect(row?.getAttribute('data-ev-id')).toBe(`aur:${PAST_NIGHT}:AURORA`);
    await act(async () => { fireEvent.click(row); });

    // The premise, computed: after dawn App's clamp refuses this night even flagged as one.
    expect(resolveMapDate({
      selectedDate: PAST_NIGHT, selectedIsNight: true, autoDate: null, allDates: forecastDates,
      todayStr: TODAY_DATE, nightDate: TODAY_DATE,
    })).not.toBe(PAST_NIGHT);
    // So the pick is never handed over — asking is what stranded it on #803 — and the map stays on
    // the night: the pill still names it.
    expect(onSelectDate).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /Thursday night/ })).toHaveAttribute('aria-haspopup', 'listbox');
  });

  it('reports night provenance even when the date is UNCHANGED (Codex, #803)', async () => {
    // ⚠️ The common case, and the one the old `row.date !== date` skip silently dropped: the map
    // already sits on today, and the reader picks TONIGHT. `App` therefore never learned the
    // selection named a night — so at UK midnight `resolveMapDate` saw an unflagged, now-past date
    // and advanced the map to tomorrow, while the night runs on until dawn.
    const TONIGHT = TODAY_DATE;
    availableDatesRef.current = [TONIGHT];
    const onSelectDate = vi.fn();
    await renderMap({
      date: TONIGHT, // already the row's own date — nothing for the forward to change
      forecastDates: [TONIGHT],
      locations: makeLocations([TONIGHT]),
      onSelectDate,
    });

    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
    const row = screen.getAllByTestId('wf-win-row')
      .find((r) => r.getAttribute('data-ev-id') === `aur:${TONIGHT}:AURORA`);
    expect(row?.getAttribute('data-ev-id')).toBe(`aur:${TONIGHT}:AURORA`);
    await act(async () => { fireEvent.click(row); });

    expect(onSelectDate).toHaveBeenCalledWith(TONIGHT, { isNight: true });
  });

  it('reports SOLAR provenance on the same date, so the night licence cannot stick', async () => {
    // The other direction: picking a solar row for a date a night row already occupied must clear
    // the flag rather than leave the night's licence attached to a calendar day.
    const TONIGHT = TODAY_DATE;
    availableDatesRef.current = [TONIGHT];
    const onSelectDate = vi.fn();
    await renderMap({
      date: TONIGHT,
      forecastDates: [TONIGHT],
      locations: makeLocations([TONIGHT]),
      onSelectDate,
    });

    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
    const solar = screen.getAllByTestId('wf-win-row')
      .find((r) => r.getAttribute('data-ev-id') === `solar:${TONIGHT}:SUNSET`);
    expect(solar?.getAttribute('data-ev-id')).toBe(`solar:${TONIGHT}:SUNSET`);
    await act(async () => { fireEvent.click(solar); });

    expect(onSelectDate).toHaveBeenCalledWith(TONIGHT, { isNight: false });
  });

  it('still forwards a night row that is in the domain AND today-forward', async () => {
    // The control: the clause added for the case above must not swallow the ordinary forward.
    const FUTURE_NIGHT = '2026-08-16';
    availableDatesRef.current = [FUTURE_NIGHT];
    const onSelectDate = vi.fn();
    await renderMap({
      date: TODAY_DATE,
      forecastDates: [TODAY_DATE, FUTURE_NIGHT],
      locations: makeLocations([TODAY_DATE, FUTURE_NIGHT]),
      onSelectDate,
    });

    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
    const row = screen.getAllByTestId('wf-win-row')
      .find((r) => r.getAttribute('data-ev-id') === `aur:${FUTURE_NIGHT}:AURORA`);
    expect(row?.getAttribute('data-ev-id')).toBe(`aur:${FUTURE_NIGHT}:AURORA`);
    await act(async () => { fireEvent.click(row); });

    expect(onSelectDate).toHaveBeenCalledWith(FUTURE_NIGHT, { isNight: true });
  });
});

describe('MapView aurora night — a night row must not blank the solar windows\' verdicts', () => {
  /**
   * ⚠️ **The regression this pins is one an adversarial review found in L1 and no test caught.**
   * The verdict's region scope was routed through `heatOffered`, which folds in `!isAuroraMode`
   * because it gates whether the heat FIELD is drawable. Selecting any aurora row therefore emptied
   * the scope and silently deleted the verdict from every SOLAR window in the list — and standing
   * on a night row is exactly when both neighbours are solar and both stepper ticks should be lit.
   * Which regions are in the reader's scope is a fact about geography; it has nothing to do with
   * which layer is currently painted.
   *
   * <p>⚠️ Re-anchored for D-14 (map-tab-v2-plan.md §5). This used a night four days past, which
   * sorted BEFORE the calendar day's solar rows and so could only ever light the `›` tick. A night
   * that is over is no longer a row, so the fixture now uses TONIGHT — the calendar day's own night,
   * sorting after its sunset — with tomorrow's sunrise served behind it. That brackets the aurora row
   * with a solar window on both sides: the two-tick case the old fixture could not build.
   */
  const NIGHT = THE_CALENDAR_DAY;
  const NEXT_DAY = '2026-08-15';

  beforeEach(() => {
    localStorage.clear();
    mockUseAuth.mockReturnValue({ role: 'ADMIN' });
    auroraStatusRef.current = null;
    availableDatesRef.current = [NIGHT];
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(SMALL_HOURS));
  });
  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  it('keeps the neighbouring solar windows\' stepper ticks while an aurora row is showing', async () => {
    const region = {
      regionName: 'North East', meanRating: 4.2, displayVerdict: 'WORTH_IT', slots: [{ canopy: false }],
    };
    const days = [THE_CALENDAR_DAY, NEXT_DAY].map((date) => ({
      date,
      eventSummaries: ['SUNRISE', 'SUNSET'].map((targetType) => ({ targetType, regions: [region] })),
    }));

    // A minimal heat prop: the verdict's scope is read off the spot pool, so without one there are
    // no regions in scope and the assertion below would pass for the wrong reason.
    const spots = [{
      id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East', regionName: 'North East',
    }];

    // ⚠️ **`windows` must carry these two, and the first cut of this fixture left it empty.** With
    // no served window the EV list builds both solar rows as D-13 FILLERS, and this test then
    // asserted verdicts on windows the briefing never served — the exact defect the cross-vendor
    // review found on #792 (§4 #38). The test's own claim is sound and unchanged; its fixture was
    // demonstrating the bug it now guards against. Serving them makes the ticks legitimate.
    const windows = [
      [THE_CALENDAR_DAY, 'SUNRISE'], [THE_CALENDAR_DAY, 'SUNSET'], [NEXT_DAY, 'SUNRISE'],
    ].map(([date, targetType]) => ({
      key: `${date}:${targetType}`,
      date,
      targetType,
      label: `${targetType === 'SUNRISE' ? 'Sunrise' : 'Sunset'} ${date}`,
      time: targetType === 'SUNRISE' ? '05:34' : '20:31',
      bestRating: 4,
      conf: 1,
    }));

    await renderMap({
      date: THE_CALENDAR_DAY,
      forecastDates: [THE_CALENDAR_DAY, NEXT_DAY],
      heat: {
        enabled: true, hasHome: true, spots, areaSpots: spots, pointsByKey: new Map(), windows,
      },
      regionVerdictIndex: buildRegionVerdictIndex(days),
    });

    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
    const auroraRow = screen.getAllByTestId('wf-win-row')
      .find((r) => r.getAttribute('data-ev-id') === `aur:${NIGHT}:AURORA`);
    expect(auroraRow?.getAttribute('data-ev-id')).toBe(`aur:${NIGHT}:AURORA`);
    await act(async () => { fireEvent.click(auroraRow); });

    // The night row itself states no verdict — §6 Q1, and it has no per-region rollup to state one
    // from. That is the correct absence.
    expect(screen.queryByTestId('wf-win-verdict')).toBeNull();

    // But the SOLAR neighbours still have theirs, which is what the ticks are for. Route the scope
    // back through `heatOffered` and this array is empty.
    //
    // ⚠️ **Exactly two, one on EACH stepper** — asserted precisely rather than as "more than zero",
    // which is the banned assert-existence form. Tonight sorts after the calendar day's sunset and
    // before tomorrow's sunrise, so both neighbours are served solar windows and both ticks are lit.
    const prevTick = within(screen.getByTestId('wf-win-prev')).getByTestId('wf-win-tick');
    const nextTick = within(screen.getByTestId('wf-win-next')).getByTestId('wf-win-tick');
    expect(screen.getAllByTestId('wf-win-tick')).toHaveLength(2);
    expect(prevTick).toHaveAttribute('data-tier', 'WORTH_IT');
    expect(nextTick).toHaveAttribute('data-tier', 'WORTH_IT');
  });
});
