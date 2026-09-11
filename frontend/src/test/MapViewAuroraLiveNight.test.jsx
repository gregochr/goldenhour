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
 * Browse to a future night with no stored run and the rating, the medallions, the "🏆 best
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
  Marker: ({ children }) => <div data-testid="marker">{children}</div>,
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
// A spy, so the medallion's resolved rating can be read — `markerLabelAndColour(rating, …)`.
const markerLabelAndColour = vi.fn(() => ({ label: '★', colour: '#E5A00D' }));
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: (...args) => markerLabelAndColour(...args),
  STAND_DOWN_COLOUR: '#501313',
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

/**
 * ⚠️ Unique per test, and not for tidiness: `makeMarkerIcon`'s cache is MODULE-level, keyed on
 * the name among other things, so a reused name is a cache hit and `markerLabelAndColour` is never
 * called — an assertion on its arguments would read nothing while the component worked perfectly.
 */
let nonce = 0;
let LOC = '';

const liveScore = () => ({ location: { name: LOC, lat: 55.0, lon: -1.7 }, stars: 5, summary: 'Live: clear to the north' });

function locations() {
  return [{ name: LOC, lat: 55.0, lon: -1.7, forecastsByDate: new Map(), locationType: ['LANDSCAPE'] }];
}

async function renderOn(nightOnScreen, extra = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapView
        locations={locations()}
        date={nightOnScreen}
        autoEventType={null}
        handoffEventType="AURORA"
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
  localStorage.clear();
  markerLabelAndColour.mockClear();
  popupAuroraScores.length = 0;
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(SMALL_HOURS));
  // A live alert whose night in progress is THE_NIGHT — so `auroraScores` is populated.
  //
  // ⚠️ `active: true` is not decoration, and leaving it out made this whole file meaningless.
  // Aurora mode requires `auroraAvailable` — a LIVE alert (`active`) or some stored run — and
  // without either `MapView` enters aurora mode and immediately bounces back to SUNSET, nulling the
  // rating floor on the way out. The first cut omitted it: every aurora surface then rendered
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

describe('the aurora medallions', () => {
  it('carry the live stars on the night in progress, exactly as before', async () => {
    await renderOn(THE_NIGHT);
    await waitFor(() => expect(markerLabelAndColour.mock.calls.some((c) => c[0] === 5)).toBe(true));
  });

  it('do NOT carry tonight\'s live stars on another night with no stored run', async () => {
    await renderOn(ANOTHER_NIGHT, {});
    await waitFor(() => expect(getAuroraLocations).toHaveBeenCalled());
    // ⚠️ Two assertions because there are two paths. The rating ACCESSOR decides which locations
    // are drawn at all; each medallion's star comes from its own marker path. A leaking accessor
    // would draw a medallion wearing no star — which the star assertion alone cannot see, since
    // it only asks whether a 5 appeared. Nothing is rated for that night, so NOTHING is drawn.
    expect(screen.queryAllByTestId('marker')).toHaveLength(0);
    expect(markerLabelAndColour.mock.calls.some((c) => c[0] === 5)).toBe(false);
  });

  it('carry THAT night\'s stored stars on another night that has a stored run', async () => {
    // The live cache says 5 for tonight; the stored run for ANOTHER_NIGHT says 4. The medallion
    // must answer with the night it is on.
    getAuroraForecastResults.mockImplementation((night) => Promise.resolve(
      night === ANOTHER_NIGHT ? [{ locationName: LOC, stars: 4 }] : [],
    ));
    await renderOn(ANOTHER_NIGHT);
    await waitFor(() => expect(markerLabelAndColour.mock.calls.some((c) => c[0] === 4)).toBe(true));
    expect(markerLabelAndColour.mock.calls.some((c) => c[0] === 5)).toBe(false);
  });
});

describe('the overlay\'s aurora popup', () => {
  it('is handed the live score on the night in progress', async () => {
    await renderOn(THE_NIGHT, { overlayMode: true, handoffLocationName: null });
    await waitFor(() => expect(popupAuroraScores.some((s) => s?.stars === 5)).toBe(true));
  });

  it('is handed THAT night\'s stored result on another night — not tonight\'s, and not nothing', async () => {
    // ⚠️ This test asserted `null` here in its first form, and that encoded the defect Codex then
    // found (#814). `MarkerPopupContent` reads a null aurora score as "Not suitable for aurora
    // photography", so withholding tonight's live score swapped wrong data for a false NEGATIVE —
    // a medallion wearing that night's stored 4★ opened a popup denying the night outright. The
    // claim that matters is that the popup and the medallion it opens from agree.
    //
    // That night HAS a stored run, which is also the precondition: a popup renders only inside a
    // visible marker, and with nothing rated there is no marker to host one.
    getAuroraForecastResults.mockImplementation((night) => Promise.resolve(
      night === ANOTHER_NIGHT ? [{ locationName: LOC, stars: 4, alertLevel: 'MINOR' }] : [],
    ));
    await renderOn(ANOTHER_NIGHT, { overlayMode: true });
    // The precondition, asserted: a popup really was rendered and handed something.
    await waitFor(() => expect(popupAuroraScores.length).toBeGreaterThan(0));
    // It carries THAT night's stored 4 — the same figure the medallion shows...
    expect(popupAuroraScores.some((sc) => sc?.stars === 4)).toBe(true);
    // ...never tonight's live 5...
    expect(popupAuroraScores.some((sc) => sc?.stars === 5)).toBe(false);
    // ...and never nothing, which the popup would render as "Not suitable".
    expect(popupAuroraScores.every((sc) => sc != null)).toBe(true);
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
          locations={locations()}
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

  /** A promise this test resolves by hand, so it — not the scheduler — decides which lands first. */
  function deferred() {
    let resolve;
    const promise = new Promise((r) => { resolve = r; });
    return { promise, resolve };
  }

  async function renderNight(night) {
    let result;
    await act(async () => {
      result = render(
        <MapView locations={locations()} date={night} autoEventType={null} handoffEventType="AURORA" />,
      );
    });
    return result;
  }

  const rerenderNight = async (result, night) => {
    await act(async () => {
      result.rerender(
        <MapView locations={locations()} date={night} autoEventType={null} handoffEventType="AURORA" />,
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
    await waitFor(() => expect(markerLabelAndColour.mock.calls.some((c) => c[0] === 3)).toBe(true));

    markerLabelAndColour.mockClear();
    // Now A's stale response lands — after B's.
    await act(async () => { a.resolve([{ locationName: LOC, stars: 4 }]); await Promise.resolve(); });

    expect(markerLabelAndColour.mock.calls.some((c) => c[0] === 4)).toBe(false);
    expect(screen.queryAllByTestId('marker')).toHaveLength(1);
  });

  it('does not let the previous night\'s results stand in while the new night loads', async () => {
    // ⚠️ The stale-window case. A has resolved; the reader moves to B, whose request has NOT yet
    // resolved. Without the clear-on-change, A's results were still on hand and answered for B.
    const b = deferred();
    getAuroraForecastResults.mockImplementation((night) => (
      night === NIGHT_A ? Promise.resolve([{ locationName: LOC, stars: 4 }]) : b.promise
    ));
    const result = await renderNight(NIGHT_A);
    await waitFor(() => expect(markerLabelAndColour.mock.calls.some((c) => c[0] === 4)).toBe(true));

    // Night A's own 4 above is legitimate; only calls made AFTER the move to B count against it.
    markerLabelAndColour.mockClear();
    await rerenderNight(result, NIGHT_B);
    await act(async () => { await Promise.resolve(); });
    // B is loading, so NOTHING is rated for it yet — and nothing may be drawn.
    //
    // ⚠️ Asserted on the marker COUNT, not on `markerLabelAndColour`, and that is not a stylistic
    // choice. The first form of this test asked "was the spy called with a 4?" and passed with the
    // clear-on-change deleted: `makeMarkerIcon`'s cache is module-level and keyed on the rating
    // among other things, so night B showing A's stale 4 hit the SAME cache entry A had built — the
    // icon was reused, the spy never called, and the stale data sat on screen invisible to the
    // assertion. The count cannot be fooled that way: fixed, B has nothing rated (0 drawn); broken,
    // A's 4 stands in and clears the floor (1 drawn).
    expect(screen.queryAllByTestId('marker')).toHaveLength(0);

    // ...and once B's own answer lands, that is what shows.
    await act(async () => { b.resolve([{ locationName: LOC, stars: 2 }]); await Promise.resolve(); });
    // 2 is below the default 3★ floor, so B's location is correctly not drawn — and still never 4.
    expect(markerLabelAndColour.mock.calls.some((c) => c[0] === 4)).toBe(false);
  });
});
