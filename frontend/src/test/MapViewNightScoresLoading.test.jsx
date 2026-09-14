/**
 * `MapView` → `MapCallout` — while a night's own scores are loading, the tab says so, and it keeps
 * drawing what it already has for that night.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>The astro and stored-aurora single-night fetches drew nothing for the new night on every night
 * step until its request answered — the fix that stopped one night's stars standing in for
 * another's (#814, and its astro twin in `MapViewAstroNightFetch.test.jsx`). For that round trip the
 * callout said so in the definitive voice: its null-rating line reads "Loading…" only while the flag
 * it is handed is false, and it was handed `scoresKnown` — the SOLAR scores fetch's flag, true on
 * every night step once the solar scores had landed. So a night step read "Not scored yet" for a
 * request still in flight, sometimes above a strip cell already showing that night's star, because
 * the strip reads the window control's preview.
 *
 * <p>What this pins, for BOTH night kinds — every shared rule runs once for astro and once for aurora
 * off one table ({@link KINDS}), because mutation testing found every aurora twin of an astro-only
 * test deletable:
 * <ul>
 *   <li><b>A night-aware flag.</b> `ratingKnown` asks the source that rates the window on screen —
 *       for astro and aurora, the night's own request — and only a successful response sets it. So
 *       the headline reads "Loading…" until that answer lands, never "Not scored yet" after a
 *       failure, and "Not scored yet" only once the answer has said so.</li>
 *   <li><b>The preview's rows in the meantime.</b> A night draws its own answer once it has landed
 *       and, until then, the window control's preview rows for it — derived at render, so a preview
 *       that lands after the step, or after the night's own request has failed, fills it in. A
 *       preview row confirms nothing, and a failed request takes nothing away.</li>
 *   <li><b>The strip.</b> Its night cells ask whether their OWN night's preview has answered, never
 *       the solar flag, and its cell for the window on screen restates the headline.</li>
 *   <li><b>The retry.</b> A failed night request is asked again — 2s, 10s, a minute, then every ten
 *       minutes, and at once when the reader comes back to the page — until it answers or the night
 *       changes; the frozen Plan-tab overlay still asks once. Walked on fake timers, to the
 *       millisecond.</li>
 * </ul>
 *
 * <h2>How the requests are driven</h2>
 *
 * <p>Every request is left for the test to settle. The single-night effect and the preview send
 * the SAME endpoint the SAME night, so the order they were sent in is the only thing that tells
 * them apart — which is why this file keeps them in arrival order and names one by `nth`, rather
 * than answering by argument. Each test that relies on that order asserts it first. Late settles
 * run inside an AWAITED `act` — `frontend-test-standards.md`'s "A late response is only 'dropped'
 * once it has landed": un-awaited, a negative passes with its guard deleted, and it is the `await`
 * that carries it, not the async callback.
 *
 * <p>The callout is the REAL component, not a probe. The `react-leaflet` mock hands it a container
 * with a `parentElement` to portal into and no `getSize`, so it never places the card: it renders
 * it off-screen, which leaves every word of it in the DOM for these assertions. With no `heat` prop
 * the canvas and label layers never mount, so nothing else needs stubbing out of the way.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';

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
  // Every map method `MapView`'s own controllers call, as no-ops. The location handoff below flies
  // to its target, so `flyTo` is reached; `once`/`off` belong to the overlay's popup controller,
  // which these tests never mount, and are here only so nothing throws if one ever does.
  // `parentElement` is what lets the callout mount.
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500, clientWidth: 800, parentElement: document.body }),
    getZoom: () => 9,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
    invalidateSize: () => {},
  }),
}));

const { auroraStatusRef } = vi.hoisted(() => ({ auroraStatusRef: { current: null } }));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'ADMIN' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: () => ({ status: auroraStatusRef.current }),
}));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn(),
  getAuroraForecastResults: vi.fn(),
  getAuroraForecastAvailableDates: vi.fn(),
}));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn(),
  getAstroAvailableDates: vi.fn(),
}));
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn().mockResolvedValue({}) }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div data-testid="popup-content" /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '★', colour: '#E5A00D' }),
  STAND_DOWN_COLOUR: '#501313',
}));

import MapView from '../components/MapView.jsx';
import {
  getAuroraLocations, getAuroraForecastResults, getAuroraForecastAvailableDates,
} from '../api/auroraApi.js';
import { getAstroConditions, getAstroAvailableDates } from '../api/astroApi.js';

/** A frozen clock, so every night below sits where this file says rather than where CI runs. */
const NOW = '2026-08-14T12:00:00Z';
const NIGHT_A = '2026-08-16';
const NIGHT_B = '2026-08-17';
const NIGHT_C = '2026-08-18';
/**
 * A night already had — stored, so it is a row in the window list, but never one the preview asks
 * about, because the preview's horizon runs from today forward. Picking it keeps it LOCAL: the
 * window control cannot forward a past date, so `nightDate` moves and `date` does not.
 */
const PAST_NIGHT = '2026-08-10';
/**
 * The night the live aurora state describes — neither A nor B, so the live cache answers for
 * neither and every aurora rating below is the stored request's alone (see `liveAuroraOnScreen`).
 */
const LIVE_NIGHT = '2026-08-13';

/**
 * The forecast dates that put A and B inside the preview's horizon. ⚠️ One module-level array, and
 * that is load-bearing: a fresh literal on a rerender would be a new `forecastDates` identity,
 * which re-runs the preview and sends it all over again mid-test — which is exactly how the one
 * test that WANTS a second run gets it.
 */
const PREVIEWED = [NIGHT_A, NIGHT_B];
/** The strip blocks' horizon: C is a third previewed night, off screen, to prove a run landed. */
const STRIP_PREVIEWED = [NIGHT_A, NIGHT_B, NIGHT_C];

/**
 * Two dark-sky places. ⚠️ Both Bortle-classed, and that is a precondition for every astro marker
 * count here: astro mode draws only a location with a `bortleClass`, so without one nothing is
 * drawn on any night and a count would pass for nothing. Aurora draws them either way.
 */
const LOCATIONS = [
  {
    id: 1, name: 'Kielder', lat: 55.23, lon: -2.58, regionName: 'Northumberland',
    forecastsByDate: new Map(), locationType: ['LANDSCAPE'], bortleClass: 2,
  },
  {
    id: 2, name: 'Cheviot', lat: 55.48, lon: -2.15, regionName: 'Northumberland',
    forecastsByDate: new Map(), locationType: ['LANDSCAPE'], bortleClass: 3,
  },
];
/** The place the callout is open on, in every test. */
const SELECTED = 'Kielder';

/** Night A's answer: both places, both over the default 3★ floor — two markers. */
const A_ROWS = [{ locationName: 'Kielder', stars: 4 }, { locationName: 'Cheviot', stars: 4 }];
/**
 * Night B's PREVIEW rows: the selected place at 5★ and the other under the floor — one marker. So
 * the preview's rows are told apart from A's (two) and from nothing (none) by a count no icon cache
 * can hide, and from B's own later answer by the headline's star.
 */
const B_PREVIEW = [{ locationName: 'Kielder', stars: 5 }, { locationName: 'Cheviot', stars: 2 }];

/** A request this test settles by hand, so it — not the scheduler — decides what lands when. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/** Every request each night endpoint has been sent, in the order sent, each still unsettled. */
const sent = { astro: [], aurora: [] };

function recordInto(list) {
  return (night) => {
    const d = deferred();
    list.push({ night, ...d });
    return d.promise;
  };
}

/**
 * The two night kinds, each with what differs between them: the mode, the endpoint's request
 * list, the window list's row-id prefix, and what makes the mode available at all.
 */
const KINDS = [
  {
    name: 'astro',
    eventType: 'ASTRO',
    rowPrefix: 'astro',
    requests: sent.astro,
    arrange: (nights) => { getAstroAvailableDates.mockResolvedValue(nights); },
  },
  {
    name: 'aurora',
    eventType: 'AURORA',
    rowPrefix: 'aur',
    requests: sent.aurora,
    arrange: (nights) => {
      getAuroraForecastAvailableDates.mockResolvedValue(nights);
      // ⚠️ A live alert, so aurora mode is available from the first render: with neither a live
      // alert nor a stored run on hand, the map enters aurora mode and bounces straight back to
      // SUNSET (`MapViewAuroraLiveNight.test.jsx` records the same trap). Its night is LIVE_NIGHT,
      // so the live cache answers for neither A nor B.
      auroraStatusRef.current = {
        level: 'MODERATE', kpIndex: 6, currentNightDate: LIVE_NIGHT, active: true,
      };
    },
  },
];

/** The `n`th request sent for `night` (0-based). Throws rather than settle a request never sent. */
function nth(list, night, n) {
  const request = list.filter((r) => r.night === night)[n];
  if (!request) throw new Error(`request #${n} for ${night} was never sent`);
  return request;
}

const sentFor = (list, night) => list.filter((r) => r.night === night).length;

/** Settles hand-held requests inside an AWAITED `act` — see the file comment for why. */
async function land(settle) {
  await act(async () => { settle(); });
}

/**
 * `scoresKnown` is TRUE throughout unless a test says otherwise: the solar scores have landed, which
 * is exactly the state in which the old callout claimed "Not scored yet" on every night step.
 */
function mapOn(night, eventType, extra) {
  return (
    <MapView
      locations={LOCATIONS}
      date={night}
      autoEventType={null}
      handoffEventType={eventType}
      handoffLocationName={SELECTED}
      handoffNonce={1}
      scoresKnown
      {...extra}
    />
  );
}

async function renderOn(night, eventType, extra = {}) {
  let result;
  await act(async () => { result = render(mapOn(night, eventType, extra)); });
  return result;
}

/** Moves the map to another night the way a parent does — a new `date`, the rest unchanged. */
async function stepTo(result, night, eventType, extra = {}) {
  await act(async () => { result.rerender(mapOn(night, eventType, extra)); });
}

const headline = () => screen.getByTestId('map-callout-score');

beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(NOW));
  sent.astro.length = 0;
  sent.aurora.length = 0;
  auroraStatusRef.current = null;
  // Re-asserted per test: `mockImplementation` outlives the test that installed it.
  getAstroConditions.mockReset();
  getAstroConditions.mockImplementation(recordInto(sent.astro));
  getAuroraForecastResults.mockReset();
  getAuroraForecastResults.mockImplementation(recordInto(sent.aurora));
  getAstroAvailableDates.mockReset();
  getAstroAvailableDates.mockResolvedValue([]);
  getAuroraForecastAvailableDates.mockReset();
  getAuroraForecastAvailableDates.mockResolvedValue([]);
  getAuroraLocations.mockReset();
  getAuroraLocations.mockResolvedValue([]);
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe.each(KINDS)('the callout on an $name night the preview does not ask about', (kind) => {
  // No `forecastDates`, so the preview's horizon is empty and it sends nothing: every request in
  // this block is the single-night effect's own.
  beforeEach(() => { kind.arrange([NIGHT_A, NIGHT_B]); });

  /** Opens the callout on night A, lets A answer, and steps to B with B's own request in flight. */
  async function onNightBWhileItLoads() {
    const result = await renderOn(NIGHT_A, kind.eventType);
    await land(() => nth(kind.requests, NIGHT_A, 0).resolve([{ locationName: SELECTED, stars: 4 }]));
    // The control every assertion below rests on: the callout is mounted, on a night of this kind,
    // and reads that night's own answer.
    expect(headline()).toHaveTextContent('4★');
    await stepTo(result, NIGHT_B, kind.eventType);
    expect(sentFor(kind.requests, NIGHT_B)).toBe(1);
    return result;
  }

  it('reads "Loading…", never "Not scored yet", while the new night\'s own request is in flight', async () => {
    await onNightBWhileItLoads();
    // Broken, the callout read the SOLAR flag — true here — and said "Not scored yet".
    expect(headline()).toHaveTextContent('Loading…');
    expect(headline()).not.toHaveTextContent('Not scored yet');
  });

  it('reads "Not scored yet" once that request has answered without this place', async () => {
    await onNightBWhileItLoads();
    await land(() => nth(kind.requests, NIGHT_B, 0).resolve([{ locationName: 'Cheviot', stars: 4 }]));
    expect(headline()).toHaveTextContent('Not scored yet');
  });

  it('shows the star once that request has answered with this place', async () => {
    await onNightBWhileItLoads();
    await land(() => nth(kind.requests, NIGHT_B, 0).resolve([{ locationName: SELECTED, stars: 5 }]));
    expect(headline()).toHaveTextContent('5★');
  });

  it('never reads "Not scored yet" after that request FAILS — it goes on reading "Loading…"', async () => {
    // A failure is not evidence that nothing was rated, so the definitive claim must not follow one.
    // This test runs on real timers and does not see the request asked again; the retry block
    // below, on fake ones, walks when it is.
    await onNightBWhileItLoads();
    await land(() => nth(kind.requests, NIGHT_B, 0).reject(new Error('night B timed out')));
    expect(headline()).not.toHaveTextContent('Not scored yet');
    expect(headline()).toHaveTextContent('Loading…');
  });

  it('shows a night\'s own answer at once on a return, while it asks again', async () => {
    // A's answer is the last one to have landed — B's is still in flight — so it still names A:
    // stepping back shows it at once, rather than "Loading…" for an answer already in hand.
    const result = await onNightBWhileItLoads();
    await stepTo(result, NIGHT_A, kind.eventType);
    expect(sentFor(kind.requests, NIGHT_A)).toBe(2);
    expect(headline()).toHaveTextContent('4★');

    // ...and the fresh one replaces it when it lands.
    await land(() => nth(kind.requests, NIGHT_A, 1).resolve([{ locationName: SELECTED, stars: 3 }]));
    expect(headline()).toHaveTextContent('3★');
  });

  it('reads "Loading…" on a return once another night\'s answer has replaced its own', async () => {
    // ⚠️ The answer names its night, and only one is held. Once B's has landed, A's 4★ is gone —
    // so back on A the callout must neither borrow B's "Not scored yet" nor keep a 4★ it no longer
    // holds: "Loading…" until A answers again.
    const result = await onNightBWhileItLoads();
    await land(() => nth(kind.requests, NIGHT_B, 0).resolve([{ locationName: 'Cheviot', stars: 4 }]));
    expect(headline()).toHaveTextContent('Not scored yet');

    await stepTo(result, NIGHT_A, kind.eventType);
    expect(headline()).toHaveTextContent('Loading…');

    await land(() => nth(kind.requests, NIGHT_A, 1).resolve([{ locationName: SELECTED, stars: 4 }]));
    expect(headline()).toHaveTextContent('4★');
  });
});

describe.each(KINDS)('the callout and the map on an $name night the preview DOES cover', (kind) => {
  beforeEach(() => { kind.arrange([NIGHT_A, NIGHT_B]); });

  /** Renders night A with A and B previewed, and checks the three requests that sends. */
  async function renderPreviewedNightA() {
    const result = await renderOn(NIGHT_A, kind.eventType, { forecastDates: PREVIEWED });
    // A's own request at mount, then — once the available dates land — the preview's A and B.
    // Asserted, because every `nth` below depends on it.
    expect(kind.requests.map((r) => r.night)).toEqual([NIGHT_A, NIGHT_A, NIGHT_B]);
    return result;
  }

  /** ...then settles all three, and steps to B with B's own request left in flight. */
  async function onPreviewedNightB(previewOfB) {
    const result = await renderPreviewedNightA();
    await land(() => {
      nth(kind.requests, NIGHT_A, 0).resolve(A_ROWS);
      nth(kind.requests, NIGHT_A, 1).resolve(A_ROWS);
      nth(kind.requests, NIGHT_B, 0).resolve(previewOfB);
    });
    expect(headline()).toHaveTextContent('4★');
    expect(screen.queryAllByTestId('marker')).toHaveLength(2);

    await stepTo(result, NIGHT_B, kind.eventType, { forecastDates: PREVIEWED });
    // The preview's B request, and now B's own.
    expect(sentFor(kind.requests, NIGHT_B)).toBe(2);
    return result;
  }

  /** ...or lets only A's own request answer, and steps to B with the whole preview still out. */
  async function onNightBWithThePreviewInFlight() {
    const result = await renderPreviewedNightA();
    await land(() => nth(kind.requests, NIGHT_A, 0).resolve(A_ROWS));
    await stepTo(result, NIGHT_B, kind.eventType, { forecastDates: PREVIEWED });
    expect(sentFor(kind.requests, NIGHT_B)).toBe(2);
    return result;
  }

  it('draws that night\'s preview rows at once — the callout shows the star, the map the pins', async () => {
    await onPreviewedNightB(B_PREVIEW);
    // Broken with no preview rows: "Loading…" and nothing drawn. Broken the old way: A's two pins.
    expect(headline()).toHaveTextContent('5★');
    expect(screen.queryAllByTestId('marker')).toHaveLength(1);
  });

  it('replaces the preview rows with the night\'s own answer when it lands', async () => {
    await onPreviewedNightB(B_PREVIEW);
    await land(() => nth(kind.requests, NIGHT_B, 1).resolve([
      { locationName: 'Kielder', stars: 3 }, { locationName: 'Cheviot', stars: 4 },
    ]));
    expect(headline()).toHaveTextContent('3★');
    expect(screen.queryAllByTestId('marker')).toHaveLength(2);
  });

  it('keeps drawing the preview rows when the night\'s own request FAILS — a dropped request does not blank the night', async () => {
    await onPreviewedNightB(B_PREVIEW);
    await land(() => nth(kind.requests, NIGHT_B, 1).reject(new Error('night B timed out')));
    // Broken — the failure clearing the rows, as it used to — "Loading…" and nothing drawn.
    expect(headline()).toHaveTextContent('5★');
    expect(screen.queryAllByTestId('marker')).toHaveLength(1);
  });

  it('does not take a preview row as an answer — a place it does not rate reads "Loading…" until the night answers', async () => {
    // The preview says nothing of Kielder. That is the preview's word, not night B's own answer, so
    // it must not be printed as the definitive "Not scored yet".
    await onPreviewedNightB([{ locationName: 'Cheviot', stars: 4 }]);
    expect(headline()).toHaveTextContent('Loading…');

    await land(() => nth(kind.requests, NIGHT_B, 1).resolve([{ locationName: 'Cheviot', stars: 4 }]));
    expect(headline()).toHaveTextContent('Not scored yet');
  });

  it('skips a row with no name rather than failing the night — in the preview and in the answer', async () => {
    await onPreviewedNightB([null, { locationName: 'Kielder', stars: 5 }]);
    expect(headline()).toHaveTextContent('5★');

    // Broken, the nameless row threw: inside the render for the preview, and inside the request's
    // `.then` for the answer — where the throw became a "failure" and the preview's 5★ stood on.
    await land(() => nth(kind.requests, NIGHT_B, 1).resolve([null, { locationName: 'Kielder', stars: 3 }]));
    expect(headline()).toHaveTextContent('3★');
  });

  it('fills the night in when its preview lands after the step', async () => {
    await onNightBWithThePreviewInFlight();
    expect(headline()).toHaveTextContent('Loading…');
    expect(screen.queryAllByTestId('marker')).toHaveLength(0);

    await land(() => {
      nth(kind.requests, NIGHT_A, 1).resolve(A_ROWS);
      nth(kind.requests, NIGHT_B, 0).resolve(B_PREVIEW);
    });
    expect(headline()).toHaveTextContent('5★');
    expect(screen.queryAllByTestId('marker')).toHaveLength(1);
  });

  it('fills the night in from a preview that lands after the night\'s own request FAILED', async () => {
    await onNightBWithThePreviewInFlight();
    await land(() => nth(kind.requests, NIGHT_B, 1).reject(new Error('night B timed out')));
    expect(headline()).toHaveTextContent('Loading…');

    await land(() => {
      nth(kind.requests, NIGHT_A, 1).resolve(A_ROWS);
      nth(kind.requests, NIGHT_B, 0).resolve(B_PREVIEW);
    });
    expect(headline()).toHaveTextContent('5★');
    expect(screen.queryAllByTestId('marker')).toHaveLength(1);
  });

  it('never re-requests the night on screen when the preview lands — the preview is not the fetch\'s dependency', async () => {
    await onNightBWithThePreviewInFlight();
    await land(() => {
      nth(kind.requests, NIGHT_A, 1).resolve(A_ROWS);
      nth(kind.requests, NIGHT_B, 0).resolve(B_PREVIEW);
    });
    // Broken — the preview read as the fetch's dependency — its arrival re-ran B's effect: a third
    // request for B, while its own answer was already on the way.
    expect(sentFor(kind.requests, NIGHT_B)).toBe(2);
  });
});

describe.each(KINDS)('the callout strip\'s $name cells, through the map', (kind) => {
  beforeEach(() => { kind.arrange([PAST_NIGHT, NIGHT_A, NIGHT_B, NIGHT_C]); });

  /** Opens the callout on night A with A, B and C previewed, A answered, and the strip expanded. */
  async function stripOnNightA() {
    const result = await renderOn(NIGHT_A, kind.eventType, { forecastDates: STRIP_PREVIEWED });
    // PAST_NIGHT is a row in the window list but outside the preview's horizon, so nothing sends a
    // request for it: A's own, then the preview's A, B and C.
    expect(kind.requests.map((r) => r.night)).toEqual([NIGHT_A, NIGHT_A, NIGHT_B, NIGHT_C]);
    await land(() => nth(kind.requests, NIGHT_A, 0).resolve(A_ROWS));
    // By test id, not role: this harness gives the card nothing to measure, so it renders unplaced
    // with `visibility: hidden`, and a role query rightly treats that as out of the accessibility
    // tree. The toggle's role contract is pinned where the card IS placed, in `MapCallout.test.jsx`
    // ("names its toggle and its cells as buttons…").
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    return result;
  }

  const cellFor = (night) => {
    const id = `${kind.rowPrefix}:${night}:${kind.eventType}`;
    const cell = screen.getAllByTestId('map-callout-strip-cell')
      .find((c) => c.getAttribute('data-ev-id') === id);
    if (!cell) throw new Error(`no strip cell for ${id}`);
    return cell;
  };

  it('reads "…" for a night whose preview is still in flight — never "—" with the solar scores in', async () => {
    await stripOnNightA();
    // Broken — the old rule, the SOLAR flag, true here — the cell claimed "unscored".
    expect(cellFor(NIGHT_B)).toHaveTextContent('…');
    expect(cellFor(NIGHT_B)).not.toHaveTextContent('—');
  });

  it('reads "—" for a night whose preview has answered without this place', async () => {
    await stripOnNightA();
    await land(() => {
      nth(kind.requests, NIGHT_A, 1).resolve(A_ROWS);
      nth(kind.requests, NIGHT_B, 0).resolve([{ locationName: 'Cheviot', stars: 4 }]);
      nth(kind.requests, NIGHT_C, 0).resolve([]);
    });
    expect(cellFor(NIGHT_B)).toHaveTextContent('—');
    expect(cellFor(NIGHT_B)).not.toHaveTextContent('…');
  });

  it('keeps "…" for a night whose preview request FAILED — while the rest of that run lands', async () => {
    await stripOnNightA();
    await land(() => {
      nth(kind.requests, NIGHT_A, 1).resolve(A_ROWS);
      nth(kind.requests, NIGHT_B, 0).reject(new Error('night B timed out'));
      nth(kind.requests, NIGHT_C, 0).resolve([{ locationName: SELECTED, stars: 3 }]);
    });
    // The control that the run LANDED: C, answered in the same run, shows its star. Without it B's
    // "…" below would read the same if the whole run had been thrown away on B's failure.
    expect(cellFor(NIGHT_C)).toHaveTextContent('3★');
    // Broken — a failed night stored as `[]`, the endpoint's own "nothing is rated" — it read "—".
    expect(cellFor(NIGHT_B)).toHaveTextContent('…');
    expect(cellFor(NIGHT_B)).not.toHaveTextContent('—');
  });

  it('keeps a night\'s earlier rows when a later preview run fails for it', async () => {
    const result = await stripOnNightA();
    await land(() => {
      nth(kind.requests, NIGHT_A, 1).resolve(A_ROWS);
      nth(kind.requests, NIGHT_B, 0).resolve([{ locationName: SELECTED, stars: 4 }]);
      nth(kind.requests, NIGHT_C, 0).resolve([]);
    });
    expect(cellFor(NIGHT_B)).toHaveTextContent('4★');

    // A new forecast-dates list re-runs the preview, as a briefing beat or a window focus does.
    await stepTo(result, NIGHT_A, kind.eventType, { forecastDates: [...STRIP_PREVIEWED] });
    expect(sentFor(kind.requests, NIGHT_B)).toBe(2);
    await land(() => {
      nth(kind.requests, NIGHT_A, 2).resolve(A_ROWS);
      nth(kind.requests, NIGHT_B, 1).reject(new Error('night B 502'));
      nth(kind.requests, NIGHT_C, 1).resolve([]);
    });
    // Broken — each run rebuilding the map from scratch — B's good rows went with the failure.
    expect(cellFor(NIGHT_B)).toHaveTextContent('4★');
  });

  it('keeps "—" for a night the preview never asks about — a past one — rather than "…" for good', async () => {
    // Pins the stated limit, not an oversight: nothing is loading for this night, so "…" would be
    // the false claim. Broken — pending read as "not in the map" alone — this cell said "…" forever.
    await stripOnNightA();
    await land(() => {
      nth(kind.requests, NIGHT_A, 1).resolve(A_ROWS);
      nth(kind.requests, NIGHT_B, 0).resolve(B_PREVIEW);
      nth(kind.requests, NIGHT_C, 0).resolve([]);
    });
    expect(cellFor(PAST_NIGHT)).toHaveTextContent('—');
    expect(cellFor(PAST_NIGHT)).not.toHaveTextContent('…');
  });

  it('asks for a past night\'s own answer when it is picked, and the callout follows THAT night', async () => {
    // ⚠️ The one route on which `nightDate` and `date` part company. A past night cannot be
    // forwarded, so picking its cell keeps it local: the night moves and the map's `date` stays on
    // A. Every other test here moves both together, so a flag or a draw keyed on `date` passed them
    // all; here it would say "Loading…" for good, or draw A's rows as the past night's.
    await stripOnNightA();
    await act(async () => { fireEvent.click(cellFor(PAST_NIGHT)); });
    expect(sentFor(kind.requests, PAST_NIGHT)).toBe(1);
    expect(headline()).toHaveTextContent('Loading…');

    await land(() => nth(kind.requests, PAST_NIGHT, 0).resolve([{ locationName: 'Cheviot', stars: 4 }]));
    expect(headline()).toHaveTextContent('Not scored yet');
  });

  it('restates a picked past night\'s star in its own cell — never "—" beside the headline\'s star', async () => {
    // The preview never asks about a past night, so its cell's own source says nothing; the cell for
    // the window on screen restates the headline instead. Broken, it read "—" beside "4★".
    await stripOnNightA();
    await act(async () => { fireEvent.click(cellFor(PAST_NIGHT)); });
    await land(() => nth(kind.requests, PAST_NIGHT, 0).resolve([{ locationName: SELECTED, stars: 4 }]));
    expect(headline()).toHaveTextContent('4★');
    expect(cellFor(PAST_NIGHT)).toHaveTextContent('4★');
  });
});

describe.each(KINDS)('the callout strip\'s $name cells on a SUNSET callout', (kind) => {
  // The strip's night cells are not only read on a night: the commonest card is a sunset one, with
  // every night listed under it. Their rule must not depend on the kind of window on screen.
  beforeEach(() => { kind.arrange([NIGHT_A, NIGHT_B]); });

  async function sunsetStrip() {
    await renderOn(NIGHT_A, 'SUNSET', { forecastDates: PREVIEWED });
    // No night on screen, so no single-night request: the preview's A and B alone.
    expect(kind.requests.map((r) => r.night)).toEqual([NIGHT_A, NIGHT_B]);
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
  }

  const cellFor = (night) => screen.getAllByTestId('map-callout-strip-cell')
    .find((c) => c.getAttribute('data-ev-id') === `${kind.rowPrefix}:${night}:${kind.eventType}`);

  it('reads "…" for a night whose preview is still in flight', async () => {
    await sunsetStrip();
    expect(cellFor(NIGHT_B)).toHaveTextContent('…');
    expect(cellFor(NIGHT_B)).not.toHaveTextContent('—');
  });

  it('reads "—" once that preview has answered without this place', async () => {
    await sunsetStrip();
    await land(() => {
      nth(kind.requests, NIGHT_A, 0).resolve(A_ROWS);
      nth(kind.requests, NIGHT_B, 0).resolve([{ locationName: 'Cheviot', stars: 4 }]);
    });
    expect(cellFor(NIGHT_B)).toHaveTextContent('—');
  });
});

describe.each(KINDS)('a failed $name night request is asked again', (kind) => {
  // No preview here, so every request is the single-night effect's own. The timers are faked so the
  // retry schedule can be walked to the millisecond — intervals too, because `MapSizeSync` pairs a
  // real interval with the timeout that stops it. The file-wide `Date` pin is re-applied, and the
  // awaited `act` still settles on real macrotasks: it flushes on `setImmediate`/`MessageChannel`,
  // which stay real.
  beforeEach(() => {
    kind.arrange([NIGHT_A, NIGHT_B]);
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date(NOW));
  });

  const HOUR_MS = 60 * 60 * 1000;
  const elapse = async (ms) => { await act(async () => { vi.advanceTimersByTime(ms); }); };

  /** Opens the callout on A, lets A answer, steps to B, and fails B's own request. */
  async function onNightBAfterAFailure() {
    const result = await renderOn(NIGHT_A, kind.eventType);
    await land(() => nth(kind.requests, NIGHT_A, 0).resolve([{ locationName: SELECTED, stars: 4 }]));
    await stepTo(result, NIGHT_B, kind.eventType);
    await land(() => nth(kind.requests, NIGHT_B, 0).reject(new Error('night B timed out')));
    expect(sentFor(kind.requests, NIGHT_B)).toBe(1);
    return result;
  }

  /**
   * The trailing control for a test whose claim is "nothing was asked": back on B, fail B's newest
   * request and see a retry follow at 2s — proof, inside the same test, that a failure here IS
   * observed. Without it, "no retry" reads the same as "the failure never landed".
   */
  async function aRetryStillFollowsAFailureOnB(result) {
    await stepTo(result, NIGHT_B, kind.eventType);
    const newest = sentFor(kind.requests, NIGHT_B) - 1;
    await land(() => nth(kind.requests, NIGHT_B, newest).reject(new Error('down again')));
    await elapse(2000);
    expect(sentFor(kind.requests, NIGHT_B)).toBe(newest + 2);
  }

  /** Fails B's retries through the quick schedule, leaving the next ask ten minutes away. */
  async function intoTheTenMinuteBeat() {
    for (const [sentSoFar, wait] of [[1, 2000], [2, 10000], [3, 60000]]) {
      await elapse(wait);
      expect(sentFor(kind.requests, NIGHT_B)).toBe(sentSoFar + 1);
      await land(() => nth(kind.requests, NIGHT_B, sentSoFar).reject(new Error('still down')));
    }
  }

  const focusTheWindow = async () => { await act(async () => { window.dispatchEvent(new Event('focus')); }); };

  it('asks again two seconds after a failure — and not a millisecond sooner', async () => {
    await onNightBAfterAFailure();
    expect(headline()).toHaveTextContent('Loading…');
    await elapse(1999);
    expect(sentFor(kind.requests, NIGHT_B)).toBe(1);
    await elapse(1);
    expect(sentFor(kind.requests, NIGHT_B)).toBe(2);
  });

  it('takes a retried request\'s answer as the night\'s own — "Not scored yet" once it says so', async () => {
    await onNightBAfterAFailure();
    await elapse(2000);
    await land(() => nth(kind.requests, NIGHT_B, 1).resolve([{ locationName: 'Cheviot', stars: 4 }]));
    expect(headline()).toHaveTextContent('Not scored yet');
  });

  it('backs off — 2s, 10s, a minute — then asks every ten minutes, and goes on asking', async () => {
    await onNightBAfterAFailure();
    // Each pair is [requests sent so far, the wait before the next]: one millisecond short of the
    // wait sends nothing, the wait itself sends one — which pins each delay, not merely its order.
    for (const [sentSoFar, wait] of [[1, 2000], [2, 10000], [3, 60000], [4, 600000], [5, 600000]]) {
      await elapse(wait - 1);
      expect(sentFor(kind.requests, NIGHT_B)).toBe(sentSoFar);
      await elapse(1);
      expect(sentFor(kind.requests, NIGHT_B)).toBe(sentSoFar + 1);
      await land(() => nth(kind.requests, NIGHT_B, sentSoFar).reject(new Error('still down')));
    }
    // ...and the loop's last failure arms the next beat too: a cap on the retries is caught here.
    await elapse(599999);
    expect(sentFor(kind.requests, NIGHT_B)).toBe(6);
    await elapse(1);
    expect(sentFor(kind.requests, NIGHT_B)).toBe(7);
  });

  it('starts each night\'s backoff afresh — a new night\'s first retry is at 2s, wherever the last one had got to', async () => {
    const result = await onNightBAfterAFailure();
    await intoTheTenMinuteBeat();
    await stepTo(result, NIGHT_A, kind.eventType);
    await land(() => nth(kind.requests, NIGHT_A, 1).reject(new Error('night A down too')));
    // Broken — the failure count outliving the night it counted — A's first retry waited ten minutes.
    await elapse(1999);
    expect(sentFor(kind.requests, NIGHT_A)).toBe(2);
    await elapse(1);
    expect(sentFor(kind.requests, NIGHT_A)).toBe(3);
  });

  it('asks again after a malformed answer — a body that is not a list is the failure it looks like', async () => {
    const result = await renderOn(NIGHT_A, kind.eventType);
    await land(() => nth(kind.requests, NIGHT_A, 0).resolve([{ locationName: SELECTED, stars: 4 }]));
    await stepTo(result, NIGHT_B, kind.eventType);
    await land(() => nth(kind.requests, NIGHT_B, 0).resolve(null));
    // Not an answer — so never "Not scored yet" on its strength — and asked again like any failure.
    expect(headline()).toHaveTextContent('Loading…');
    await elapse(2000);
    expect(sentFor(kind.requests, NIGHT_B)).toBe(2);

    await land(() => nth(kind.requests, NIGHT_B, 1).resolve([{ locationName: SELECTED, stars: 5 }]));
    expect(headline()).toHaveTextContent('5★');
  });

  it('stops asking once the night has answered', async () => {
    await onNightBAfterAFailure();
    await elapse(2000);
    await land(() => nth(kind.requests, NIGHT_B, 1).resolve([{ locationName: SELECTED, stars: 5 }]));
    expect(headline()).toHaveTextContent('5★');

    await elapse(HOUR_MS);
    expect(sentFor(kind.requests, NIGHT_B)).toBe(2);
  });

  it('never asks again for a night the reader has left while its retry was waiting', async () => {
    const result = await onNightBAfterAFailure();
    await stepTo(result, NIGHT_A, kind.eventType);
    // Broken — the leaving night's cleanup not clearing its timer — B was asked again at 2s, for a
    // night nobody was looking at (once: that request's own failure finds the loop stopped).
    await elapse(HOUR_MS);
    expect(sentFor(kind.requests, NIGHT_B)).toBe(1);
    await aRetryStillFollowsAFailureOnB(result);
  });

  it('arms no retry for a failure that lands after the reader has left', async () => {
    const result = await renderOn(NIGHT_A, kind.eventType);
    await land(() => nth(kind.requests, NIGHT_A, 0).resolve([{ locationName: SELECTED, stars: 4 }]));
    await stepTo(result, NIGHT_B, kind.eventType);
    // Leave B while its request is still in flight; THEN it fails.
    await stepTo(result, NIGHT_A, kind.eventType);
    await land(() => nth(kind.requests, NIGHT_B, 0).reject(new Error('night B timed out')));
    // Broken — the failure arming a retry without asking whether its night's effect had been cleaned
    // up — the cleanup had already run, so nothing was left to clear the timer it armed.
    await elapse(HOUR_MS);
    expect(sentFor(kind.requests, NIGHT_B)).toBe(1);
    await aRetryStillFollowsAFailureOnB(result);
  });

  it('asks at once when the reader comes back to the window, rather than waiting out the ten-minute beat', async () => {
    await onNightBAfterAFailure();
    await intoTheTenMinuteBeat();
    await elapse(60000);
    await focusTheWindow();
    expect(sentFor(kind.requests, NIGHT_B)).toBe(5);

    // ...and the wait it cut short does not ask again at its old deadline.
    await elapse(540000);
    expect(sentFor(kind.requests, NIGHT_B)).toBe(5);
    await land(() => nth(kind.requests, NIGHT_B, 4).resolve([{ locationName: SELECTED, stars: 5 }]));
    expect(headline()).toHaveTextContent('5★');
  });

  it('asks at once when the tab becomes visible again — and not while it is still hidden', async () => {
    await onNightBAfterAFailure();
    // An own property shadows jsdom's getter on the prototype; deleting it restores that getter.
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    try {
      await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
      expect(sentFor(kind.requests, NIGHT_B)).toBe(1);
    } finally {
      delete document.visibilityState;
    }
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
    expect(sentFor(kind.requests, NIGHT_B)).toBe(2);
  });

  it('sends no second request on a focus while the first is still in flight', async () => {
    const result = await renderOn(NIGHT_A, kind.eventType);
    await land(() => nth(kind.requests, NIGHT_A, 0).resolve([{ locationName: SELECTED, stars: 4 }]));
    await stepTo(result, NIGHT_B, kind.eventType);
    await focusTheWindow();
    expect(sentFor(kind.requests, NIGHT_B)).toBe(1);

    // The control: once B has failed and a retry IS waiting, the same focus asks at once.
    await land(() => nth(kind.requests, NIGHT_B, 0).reject(new Error('night B timed out')));
    await focusTheWindow();
    expect(sentFor(kind.requests, NIGHT_B)).toBe(2);
  });

  it('asks only once on the frozen Plan-tab overlay, as it always has — and listens for nothing', async () => {
    const added = vi.spyOn(window, 'addEventListener');
    try {
      await renderOn(NIGHT_A, kind.eventType, { overlayMode: true });
      await land(() => nth(kind.requests, NIGHT_A, 0).reject(new Error('night A timed out')));
      await focusTheWindow();
      await elapse(HOUR_MS);
      expect(sentFor(kind.requests, NIGHT_A)).toBe(1);
      expect(added.mock.calls.filter(([type]) => type === 'focus')).toHaveLength(0);
    } finally {
      added.mockRestore();
    }
  });

  it('removes every focus and visibility listener it adds — across night steps and when the map goes', async () => {
    const spies = [
      vi.spyOn(window, 'addEventListener'), vi.spyOn(window, 'removeEventListener'),
      vi.spyOn(document, 'addEventListener'), vi.spyOn(document, 'removeEventListener'),
    ];
    const [winAdd, winRemove, docAdd, docRemove] = spies;
    const handlers = (spy, type) => spy.mock.calls.filter(([t]) => t === type).map(([, fn]) => fn);
    try {
      const result = await renderOn(NIGHT_A, kind.eventType);
      await stepTo(result, NIGHT_B, kind.eventType);
      await stepTo(result, NIGHT_A, kind.eventType);
      result.unmount();
      // One pair per night asked about — A, then B, then A again — each added AND removed.
      expect(handlers(winAdd, 'focus')).toHaveLength(3);
      expect(new Set(handlers(winRemove, 'focus'))).toEqual(new Set(handlers(winAdd, 'focus')));
      expect(handlers(docAdd, 'visibilitychange')).toHaveLength(3);
      expect(new Set(handlers(docRemove, 'visibilitychange')))
        .toEqual(new Set(handlers(docAdd, 'visibilitychange')));
    } finally {
      spies.forEach((spy) => spy.mockRestore());
    }
  });
});

describe.each(KINDS)('the frozen Plan-tab overlay and the $name preview', (kind) => {
  beforeEach(() => { kind.arrange([NIGHT_A, NIGHT_B]); });

  it('never asks the preview — only the night on screen, even when handed a forecast horizon', async () => {
    // The overlay mounts no window control, so a preview there is pure waste (`MapView`'s
    // `astroPreviewDates` note). `App` hands that mount no `forecastDates` today, which alone would
    // keep the preview's horizon empty; this hands it some, so the overlay gate itself is what is
    // under test — and the one list the preview and the pending set both read.
    await renderOn(NIGHT_A, kind.eventType, { forecastDates: PREVIEWED, overlayMode: true });
    expect(kind.requests.map((r) => r.night)).toEqual([NIGHT_A]);
  });
});

describe('the callout on a solar window keeps the solar flag', () => {
  // A sunset with no rating anywhere — no briefing score and no forecast row — so what the callout
  // prints is decided by the flag alone.

  it('reads "Loading…" for an unrated place while the solar scores have not landed', async () => {
    await renderOn(NIGHT_A, 'SUNSET', { forecastDates: PREVIEWED, scoresKnown: false });
    expect(headline()).toHaveTextContent('Loading…');
  });

  it('reads "Not scored yet" for an unrated place once the solar scores have landed', async () => {
    await renderOn(NIGHT_A, 'SUNSET', { forecastDates: PREVIEWED });
    expect(headline()).toHaveTextContent('Not scored yet');
  });
});

describe('the callout on the aurora night in progress', () => {
  beforeEach(() => {
    getAuroraForecastAvailableDates.mockResolvedValue([NIGHT_A, NIGHT_B]);
    auroraStatusRef.current = {
      level: 'MODERATE', kpIndex: 6, currentNightDate: NIGHT_B, active: true,
    };
  });

  async function onLiveNightBWhileItLoads() {
    const result = await renderOn(NIGHT_A, 'AURORA');
    await land(() => nth(sent.aurora, NIGHT_A, 0).resolve([{ locationName: SELECTED, stars: 4 }]));
    expect(headline()).toHaveTextContent('4★');
    await stepTo(result, NIGHT_B, 'AURORA');
    expect(sentFor(sent.aurora, NIGHT_B)).toBe(1);
    return result;
  }

  it('control: the live state still rates a place while that night\'s stored results load', async () => {
    // The flag decides only what a NULL rating says. On the night in progress the rating accessor
    // falls back to the live cache, so a place it rates shows that star through the stored round
    // trip rather than "Loading…".
    getAuroraLocations.mockResolvedValue([
      { location: { name: SELECTED, lat: 55.23, lon: -2.58 }, stars: 5, summary: 'Live: clear to the north' },
    ]);
    await onLiveNightBWhileItLoads();
    expect(headline()).toHaveTextContent('5★');
  });

  it('reads "Loading…" for a place the live state does not rate, until the STORED results answer', async () => {
    // ⚠️ Aurora asks the stored request alone. The live state answering for tonight is not the
    // stored results answering — they are what the accessor reads FIRST — so a place the live state
    // leaves out must not be called "Not scored yet" while they are still on their way.
    getAuroraLocations.mockResolvedValue([
      { location: { name: 'Cheviot', lat: 55.48, lon: -2.15 }, stars: 5, summary: 'Live' },
    ]);
    await onLiveNightBWhileItLoads();
    expect(headline()).toHaveTextContent('Loading…');

    await land(() => nth(sent.aurora, NIGHT_B, 0).resolve([]));
    expect(headline()).toHaveTextContent('Not scored yet');
  });
});
