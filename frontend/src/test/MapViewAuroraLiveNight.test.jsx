/**
 * `MapView` — the LIVE aurora state answers for the night in progress, and no other night.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>Two aurora sources feed the map and they are not the same kind of thing.
 * `storedAuroraResults` is fetched per `nightDate`, so it always describes the night on screen.
 * `auroraScores` comes from `getAuroraLocations()`, which takes <b>no date at all</b> — it is the
 * NOAA-triggered live state for the night in progress, fetched only while an alert is running.
 *
 * <p>Read unconditionally, the live cache answered for whichever night the reader was looking at.
 * Browse to a future night with no stored run and the rating, the chips, the "🏆 best
 * location" card and the overlay's aurora popup all carried TONIGHT's live stars and narrative as
 * though they were that night's. The same class of defect as #803's stale-window ratings — a
 * rating answering for a window it does not belong to — through a source with no date to check.
 *
 * <h2>What must NOT change</h2>
 *
 * <p>Tonight. Where the night on screen IS the night in progress, every reader keeps exactly the
 * precedence it had. The controls below are there to prove the gate did not simply switch the live
 * cache off.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';

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
  useMap: () => ({ eachLayer: () => {}, getContainer: () => ({ clientHeight: 500, clientWidth: 800 }) }),
}));

const { auroraStatusRef } = vi.hoisted(() => ({ auroraStatusRef: { current: null } }));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'ADMIN' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: auroraStatusRef.current }) }));
// A stable viewline, so the overlay below has something to draw — the viewline is the OTHER
// consumer of `liveAuroraOnScreen`, and the kept-local test pins it too.
const { stableViewline } = vi.hoisted(() => ({ stableViewline: { points: [], active: true } }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: stableViewline }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn().mockResolvedValue({}) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
// The overlay's aurora popup, recording what it was handed.
const popupAuroraScores = [];
vi.mock('../components/MarkerPopupContent.jsx', () => ({
  default: ({ auroraScore }) => {
    popupAuroraScores.push(auroraScore);
    return <div data-testid="popup-content" />;
  },
}));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({
  default: ({ viewline }) => (viewline ? <div data-testid="aurora-viewline-overlay" /> : null),
}));
// `MapLabels` needs a real Leaflet pane to place a chip (its own test's job). What THIS file reads is
// the `spots` array `MapView` hands it: its length is the set of places drawn and each `rating` the
// star the chip prints, so it is the observable for which night's stars a place carries.
const labelSpotsSeen = { last: null };
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: ({ spots }) => {
    labelSpotsSeen.last = spots;
    return <div data-testid="map-labels-stub" />;
  },
}));

import MapView from '../components/MapView.jsx';
import {
  getAuroraLocations, getAuroraForecastResults, getAuroraForecastAvailableDates,
} from '../api/auroraApi.js';

/** The night in progress: 01:00 UK on the 14th, so the night that began on the 13th. */
const SMALL_HOURS = '2026-08-14T00:00:00Z';
const THE_NIGHT = '2026-08-13';
/** A future night the reader browses to — never the one the live state describes. */
const ANOTHER_NIGHT = '2026-08-16';

let nonce = 0;
let LOC = '';

const liveScore = () => ({ location: { name: LOC, lat: 55.0, lon: -1.7 }, stars: 5, summary: 'Live: clear to the north' });

/** A roster join, so the heat field is offered and `MapLabels` (the drawn places) is mounted. */
let HEAT = null;
const makeHeat = (name) => {
  const spots = [{ id: 1, name, lat: 55.0, lng: -1.7, rid: 'North East' }];
  return {
    enabled: true,
    hasHome: false,
    spots,
    areaSpots: spots,
    pointsByKey: new Map(),
    windows: [],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [56.4, -1.3]],
  };
};

/** The stars on the places currently drawn. */
const drawnRatings = () => (labelSpotsSeen.last ?? []).map((spot) => spot.rating);

function locations() {
  return [{ id: 1, name: LOC, lat: 55.0, lon: -1.7, regionName: 'North East', forecastsByDate: new Map(), locationType: ['LANDSCAPE'] }];
}

async function renderOn(nightOnScreen, extra = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapView
        locations={locations()} heat={HEAT}
        date={nightOnScreen}
        autoEventType="AURORA"
        {...extra}
      />,
    );
  });
  // Let the live-state and stored-results fetches resolve.
  await act(async () => { await Promise.resolve(); });
  return result;
}

beforeEach(() => {
  nonce += 1;
  LOC = `Bamburgh-${nonce}`;
  HEAT = makeHeat(LOC);
  localStorage.clear();
  labelSpotsSeen.last = null;
  // The API mocks' call history too. Several tests here wait on `toHaveBeenCalled()` as proof that
  // their OWN fetch ran — which, uncleared, the first test's call satisfies for every test after it.
  getAuroraLocations.mockClear();
  getAuroraForecastResults.mockClear();
  getAuroraForecastAvailableDates.mockClear();
  popupAuroraScores.length = 0;
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(SMALL_HOURS));
  // A live alert whose night in progress is THE_NIGHT — so `auroraScores` is populated.
  //
  // ⚠️ `active: true` is not decoration, and leaving it out made this whole file meaningless.
  // Aurora mode requires `auroraAvailable` — a LIVE alert (`active`) or some stored run — and
  // without either `MapView` enters aurora mode and immediately bounces back to SUNSET. The first
  // cut omitted it: every aurora surface then rendered
  // nothing on ANY night, so the three "withheld on another night" tests passed trivially while
  // all four controls failed. The controls are what exposed it — which is the whole reason each
  // negative here has one.
  auroraStatusRef.current = {
    level: 'MODERATE', kpIndex: 6, currentNightDate: THE_NIGHT, active: true,
  };
  getAuroraLocations.mockResolvedValue([liveScore()]);
  getAuroraForecastResults.mockResolvedValue([]);
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
  getAuroraLocations.mockResolvedValue([]);
  getAuroraForecastResults.mockResolvedValue([]);
  getAuroraForecastAvailableDates.mockResolvedValue([]);
});

describe('the 🏆 best-location card', () => {
  it('names the live best on the night in progress', async () => {
    await renderOn(THE_NIGHT);
    const card = await screen.findByTestId('aurora-best-location-card');
    expect(card).toHaveTextContent(LOC);
  });

  it('is withheld on any OTHER night — it was naming tonight\'s best as that night\'s', async () => {
    await renderOn(ANOTHER_NIGHT);
    // The live fetch did resolve — so an absent card is the gate, not an empty cache.
    await waitFor(() => expect(getAuroraLocations).toHaveBeenCalled());
    expect(screen.queryByTestId('aurora-best-location-card')).not.toBeInTheDocument();
  });
});

describe('the 🏆 best-location card — dismissal (aurora heat A4)', () => {
  /** Re-render with a NEW status object (the live fetch is keyed on its identity) and the given scores. */
  async function reRead(view, night, scores) {
    auroraStatusRef.current = { level: 'MODERATE', kpIndex: 6, currentNightDate: night, active: true };
    getAuroraLocations.mockResolvedValue(scores);
    await act(async () => {
      view.rerender(
        <MapView locations={locations()} heat={HEAT} date={night} autoEventType="AURORA" />,
      );
    });
    await act(async () => { await Promise.resolve(); });
  }

  async function dismiss() {
    await screen.findByTestId('aurora-best-location-card');
    fireEvent.click(screen.getByTestId('aurora-best-location-dismiss'));
  }

  it('offers a named dismiss button', async () => {
    await renderOn(THE_NIGHT);
    const button = await screen.findByTestId('aurora-best-location-dismiss');
    expect(button).toHaveAccessibleName('Dismiss best aurora location');
  });

  it('hides the card when dismissed', async () => {
    await renderOn(THE_NIGHT);
    await dismiss();
    expect(screen.queryByTestId('aurora-best-location-card')).not.toBeInTheDocument();
  });

  it('keeps it hidden across a re-render with the same best location', async () => {
    const view = await renderOn(THE_NIGHT);
    await dismiss();
    await reRead(view, THE_NIGHT, [liveScore()]);
    // The live read did run again — so an absent card is the key, not an empty cache.
    expect(getAuroraLocations.mock.calls.length).toBeGreaterThan(1);
    expect(screen.queryByTestId('aurora-best-location-card')).not.toBeInTheDocument();
  });

  it('shows it again when a different location takes the top spot', async () => {
    const view = await renderOn(THE_NIGHT);
    await dismiss();
    const other = `${LOC}-other`;
    await reRead(view, THE_NIGHT, [
      { location: { name: other, lat: 55.1, lon: -1.8 }, stars: 5, summary: 'A new top spot' },
    ]);
    expect(await screen.findByTestId('aurora-best-location-card')).toHaveTextContent(other);
  });

  it('shows it again when the NIGHT moves, even for the same location', async () => {
    const view = await renderOn(THE_NIGHT);
    await dismiss();
    await reRead(view, ANOTHER_NIGHT, [liveScore()]);
    expect(await screen.findByTestId('aurora-best-location-card')).toHaveTextContent(LOC);
  });

  it('keys on the NIGHT the card speaks for, not the viewed date (plan A4 D-rule)', async () => {
    // The live night THE_NIGHT is picked through the real window control while the parent's `date`
    // is an earlier day, and THE_NIGHT is not in `forecastDates` — so the pick is KEPT LOCAL
    // (`localNightDate`): `nightDate` is THE_NIGHT while `date` stays DAY_BEFORE.
    const DAY_BEFORE = '2026-08-12';
    getAuroraForecastAvailableDates.mockResolvedValue([THE_NIGHT]);
    const view = await renderOn(DAY_BEFORE, { forecastDates: [DAY_BEFORE] });
    await act(async () => { fireEvent.click(await screen.findByTestId('wf-win-pill')); });
    const row = screen.getAllByTestId('wf-win-row')
      .find((r) => r.getAttribute('data-ev-id') === `aur:${THE_NIGHT}:AURORA`);
    expect(row).toBeTruthy();
    await act(async () => { fireEvent.click(row); });
    await dismiss();
    expect(screen.queryByTestId('aurora-best-location-card')).not.toBeInTheDocument();
    // The parent now moves `date` onto the very night that was dismissed: same night, new `date`.
    // A date-keyed dismissal would read this as a new card.
    await act(async () => {
      view.rerender(
        <MapView
          locations={locations()} heat={HEAT}
          date={THE_NIGHT}
          forecastDates={[THE_NIGHT]}
          autoEventType="AURORA"
        />,
      );
    });
    await act(async () => { await Promise.resolve(); });
    expect(screen.queryByTestId('aurora-best-location-card')).not.toBeInTheDocument();
    // ...and a card for that night is still reachable, proving the absence above is the key.
    await reRead(view, THE_NIGHT, [
      { location: { name: `${LOC}-other`, lat: 55.1, lon: -1.8 }, stars: 5, summary: 'x' },
    ]);
    expect(await screen.findByTestId('aurora-best-location-card')).toBeInTheDocument();
  });

  it('hands focus to the window pill when one exists', async () => {
    getAuroraForecastAvailableDates.mockResolvedValue([THE_NIGHT]);
    await renderOn(THE_NIGHT, { forecastDates: [THE_NIGHT] });
    const pill = await screen.findByTestId('wf-win-pill');
    const button = await screen.findByTestId('aurora-best-location-dismiss');
    button.focus();
    fireEvent.click(button);
    expect(screen.queryByTestId('aurora-best-location-card')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(pill);
  });

  it('with no window pill, skips disabled, aria-hidden and tabindex=-1 controls and lands on the next real one', async () => {
    // Since overlay convergence C3 a REAL live alert always gets its own window row, so a map with live
    // scores and NO pill needs an alert that offers no row: a SIMULATED one, which is still `active`
    // (aurora mode stands) and still has its live scores fetched by level, but is isolated from the row.
    auroraStatusRef.current = { ...auroraStatusRef.current, simulated: true };
    const { container } = await renderOn(THE_NIGHT);
    await screen.findByTestId('aurora-best-location-card');
    expect(screen.queryByTestId('wf-win-pill')).not.toBeInTheDocument();
    const pane = container.querySelector('.wf-map-tab');
    const make = (testid, attrs) => {
      const el = document.createElement('button');
      el.setAttribute('data-testid', testid);
      Object.entries(attrs).forEach(([k, v]) => el.setAttribute(k, v));
      return el;
    };
    const good = make('good-target', {});
    // Prepended in reverse so the DOM order is: disabled, aria-hidden, tabindex=-1, good.
    [good, make('skip-tab', { tabindex: '-1' }), make('skip-hidden', { 'aria-hidden': 'true' }),
      make('skip-disabled', { disabled: '' })].forEach((el) => pane.prepend(el));
    const button = screen.getByTestId('aurora-best-location-dismiss');
    button.focus();
    fireEvent.click(button);
    expect(screen.queryByTestId('aurora-best-location-card')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(good);
  });
});

describe('the aurora label spots', () => {
  it('carry the live stars on the night in progress, exactly as before', async () => {
    await renderOn(THE_NIGHT);
    await waitFor(() => expect(drawnRatings()).toContain(5));
  });

  it('do NOT carry tonight\'s live stars on another night with no stored run', async () => {
    await renderOn(ANOTHER_NIGHT, {});
    await waitFor(() => expect(getAuroraLocations).toHaveBeenCalled());
    // The rating ACCESSOR decides which locations are drawn at all. Nothing is rated for that night,
    // so NOTHING is drawn — and no 5 appears. (`toEqual([])` also proves `MapLabels` is mounted.)
    expect(labelSpotsSeen.last).toEqual([]);
    expect(drawnRatings()).not.toContain(5);
  });

  it('carry THAT night\'s stored stars on another night that has a stored run', async () => {
    // The live cache says 5 for tonight; the stored run for ANOTHER_NIGHT says 4. The spot
    // must answer with the night it is on.
    getAuroraForecastResults.mockImplementation((night) => Promise.resolve(
      night === ANOTHER_NIGHT ? [{ locationName: LOC, stars: 4 }] : [],
    ));
    await renderOn(ANOTHER_NIGHT);
    await waitFor(() => expect(drawnRatings()).toContain(4));
    expect(drawnRatings()).not.toContain(5);
  });
});

describe('the night on screen is `nightDate`, not `date`', () => {
  it('keeps the live state on the night in progress when the window control kept it LOCAL', async () => {
    // ⚠️ The case that makes `nightDate` load-bearing, and no other test reaches it: in every
    // other case here `nightDate === date`, so a predicate keyed on `date` passed the whole file.
    //
    // The window control keeps a night row LOCAL when its date is outside the forecast domain —
    // `date` stays where it was and only `nightDate` moves. So: sit on an ordinary day, then pick
    // TONIGHT's aurora row. The night on screen is now the night in progress, and the live state
    // must answer for it; keyed on `date`, it would still be looking at the ordinary day and
    // withhold it — the same failure the viewline gate's own comment records.
    const ORDINARY_DAY = '2026-08-16';
    getAuroraForecastAvailableDates.mockResolvedValue([THE_NIGHT]);
    await act(async () => {
      render(
        <MapView
          locations={locations()} heat={HEAT}
          date={ORDINARY_DAY}
          // THE_NIGHT is deliberately NOT in the domain — that is what keeps the row local.
          forecastDates={[ORDINARY_DAY]}
          autoEventType={null}
        />,
      );
    });
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(await screen.findByTestId('wf-win-pill'));
    const row = (await screen.findAllByTestId('wf-win-row'))
      .find((r) => r.getAttribute('data-ev-id') === `aur:${THE_NIGHT}:AURORA`);
    expect(row?.getAttribute('data-ev-id')).toBe(`aur:${THE_NIGHT}:AURORA`);
    await act(async () => { fireEvent.click(row); });

    // `date` never moved off the ordinary day; only `nightDate` did — and the live best is shown.
    const card = await screen.findByTestId('aurora-best-location-card');
    expect(card).toHaveTextContent(LOC);
    // ...and the VIEWLINE, the other live-state consumer, which now reads the same predicate. Its
    // own comment records `date === auroraNight` here as a real, shipped bug — "a raw `date`
    // compare would have hidden the viewline on the night the reader had just picked" — yet no
    // test anywhere distinguished the two until this one.
    expect(screen.getByTestId('aurora-viewline-overlay')).toBeInTheDocument();
  });

  it('control: the viewline is withheld on a night that is NOT the night in progress', async () => {
    await renderOn(ANOTHER_NIGHT);
    await waitFor(() => expect(getAuroraLocations).toHaveBeenCalled());
    expect(screen.queryByTestId('aurora-viewline-overlay')).not.toBeInTheDocument();
  });
});

describe('stored results answer for the night they were fetched for (Codex, #814)', () => {
  const NIGHT_A = '2026-08-16';
  const NIGHT_B = '2026-08-17';

  // No live scores in this block, so every spot it counts can only have come from a STORED result.
  // The file-level fixture rates LOC 5★ live; that is withheld off the night in progress, but a count
  // of one could not tell B's stored 3 from a leaked live 5, and nothing here would notice if one of
  // these nights ever came to be read as live.
  beforeEach(() => {
    getAuroraLocations.mockResolvedValue([]);
  });

  /** A promise this test settles by hand, so it — not the scheduler — decides which lands first. */
  function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
    return { promise, resolve, reject };
  }

  async function renderNight(night) {
    let result;
    await act(async () => {
      result = render(
        <MapView locations={locations()} heat={HEAT} date={night} autoEventType="AURORA" />,
      );
    });
    return result;
  }

  const rerenderNight = async (result, night) => {
    await act(async () => {
      result.rerender(
        <MapView locations={locations()} heat={HEAT} date={night} autoEventType="AURORA" />,
      );
    });
  };

  it('drops a LATE response for a night the reader has already left', async () => {
    // ⚠️ The out-of-order case. Night A's request is still in flight when the reader moves to B,
    // and A's finishes LAST. Without cancellation it wrote A's stars in as B's, and they stayed.
    const a = deferred();
    getAuroraForecastResults.mockImplementation((night) => (
      night === NIGHT_A ? a.promise : Promise.resolve([{ locationName: LOC, stars: 3 }])
    ));
    const result = await renderNight(NIGHT_A);
    await rerenderNight(result, NIGHT_B);
    // B's own 3 is on screen.
    await waitFor(() => expect(drawnRatings()).toContain(3));

    // Now A's stale response lands — after B's.
    await act(async () => { a.resolve([{ locationName: LOC, stars: 4 }]); await Promise.resolve(); });

    expect(drawnRatings()).toEqual([3]);
  });

  it('does not let the previous night\'s results stand in while the new night loads', async () => {
    // ⚠️ The stale-window case. A has resolved; the reader moves to B, whose request has NOT yet
    // resolved. Unless the results are keyed to their night — a clear on every change when this was
    // written, the answer's own night since the night-aware loading fix — A's were still on hand
    // and answered for B.
    const b = deferred();
    getAuroraForecastResults.mockImplementation((night) => (
      night === NIGHT_A ? Promise.resolve([{ locationName: LOC, stars: 4 }]) : b.promise
    ));
    const result = await renderNight(NIGHT_A);
    await waitFor(() => expect(drawnRatings()).toContain(4));

    // Night A's own 4 above is legitimate; only what is drawn AFTER the move to B counts against it.
    await rerenderNight(result, NIGHT_B);
    await act(async () => { await Promise.resolve(); });
    // Nothing from ANOTHER night may be drawn for B, and this file previews no night, so B has
    // nothing of its own to draw yet either. (With a preview, B draws its own preview rows meanwhile
    // — `MapViewNightScoresLoading.test.jsx`.)
    //
    // Fixed, B has nothing rated (nothing drawn); broken, A's 4 stands in and clears the floor.
    expect(labelSpotsSeen.last).toEqual([]);

    // ...and once B's own answer lands, that is what shows.
    await act(async () => { b.resolve([{ locationName: LOC, stars: 2 }]); await Promise.resolve(); });
    // 2 is below the default 3★ floor, so B's location is correctly not drawn — and still never 4.
    expect(drawnRatings()).not.toContain(4);
  });

  it('drops a LATE failure too — a night the reader has left cannot blank the one on screen', async () => {
    // ⚠️ The failure path, which the late-response test above cannot reach: A's request FAILS after
    // B's has answered. When this was written it was the `.catch` half of the cancellation —
    // unguarded, the failure handler cleared the results, B's, and the map showed nothing rated for
    // a night that had a stored run, until the next selection; that guard had shipped with no test
    // at all. Since the night-aware loading fix the `.catch` writes nothing and arms a retry instead,
    // so this now pins that a late failure never writes over the night on screen. The retry's own
    // guard is pinned on fake timers in `MapViewNightScoresLoading.test.jsx` ("arms no retry for a
    // failure that lands after the reader has left"), not here.
    const a = deferred();
    getAuroraForecastResults.mockImplementation((night) => (
      night === NIGHT_A ? a.promise : Promise.resolve([{ locationName: LOC, stars: 3 }])
    ));
    const result = await renderNight(NIGHT_A);
    // A's request really is in flight, and is this test's only one so far — without it the rejection
    // below would land on nothing, and this test would pass having never reached a `.catch`.
    expect(getAuroraForecastResults.mock.calls).toEqual([[NIGHT_A]]);

    await rerenderNight(result, NIGHT_B);
    // B's own stored 3 is on screen: one place rated (this block has no live scores — see above).
    // Also the control that aurora mode is live.
    await waitFor(() => expect(drawnRatings()).toEqual([3]));

    // ⚠️ An AWAITED `act`, and the `await` is load-bearing — measured: with `act(() => a.reject(...))`
    // left un-awaited this test passes with the guard deleted, because the unguarded clear lands after
    // `act` has returned and is never committed before the count below is read.
    await act(async () => { a.reject(new Error('night A timed out')); });

    // Still B's one. Broken, A's failure cleared B's results and nothing was drawn.
    expect(drawnRatings()).toEqual([3]);
  });
});
