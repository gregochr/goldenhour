/**
 * `MapView` — no rating may answer for a window the tab has no EV row for.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>Reported from production on 2026-09-07, with no forecast run for that day. `App`'s
 * `defaultDate` fell back to {@code allDates[allDates.length - 1]} — the most recent date that HAD
 * been scored, two days earlier — and handed it to the Map tab. Every surface on that tab which
 * knows what a window is then went correctly quiet: `WindowControl` said <em>"No forecast"</em>
 * with both steppers disabled, `heatWindow` resolved to null so the field painted nothing, the
 * colour key was withheld, and `MapCallout` refused to mount (it is gated on `activeMapEvent`).
 *
 * <p>The label chips did not. `getRatingForLocation` reads two indexes keyed by an arbitrary
 * `date` — the briefing score index and each location's `forecastsByDate` — and both legitimately
 * carry rows for dates the EV list excludes (`GET /api/forecast` serves `today-2` onward). So the
 * stars, the pins, the 3★ rating floor and the counts footer all went on reporting the stale run:
 * a blank map captioned <em>133 of 253 shown · 130 rated</em>, every chip a 4★.
 *
 * <h2>What is asserted, and what is deliberately NOT</h2>
 *
 * <p>The gate is on the ACCESSOR, which is what `labelSpots` hands `MapLabels` as `rating` and
 * what `PinsLayer`, the star filter and the footer all read. This file proves the accessor; that
 * `MapLabels` prints a rating it is given is `MapLabels.test.jsx`'s job and is not re-tested here.
 *
 * <p>Two things must NOT change and are pinned alongside: an ELAPSED window that still has a row
 * (this morning's sunrise, which the briefing retires but D-13 keeps in the map's own domain) keeps
 * its rating — the question is whether the row exists, never whether it has passed; and the frozen
 * Plan-tab overlay, which builds no EV list at all, is exempt.
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
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500, clientWidth: 800 }),
  }),
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'ADMIN' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
// A spy, so the medallion test below can read the rating the marker layer actually resolved —
// the one surface whose value never reaches the DOM in a way a query can see.
const markerLabelAndColour = vi.fn(() => ({ label: '4★', colour: '#E5A00D' }));
const buildStandDownSvg = vi.fn(() => '<svg data-sd></svg>');
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  buildStandDownSvg: (...args) => buildStandDownSvg(...args),
  markerLabelAndColour: (...args) => markerLabelAndColour(...args),
  STAND_DOWN_COLOUR: '#501313',
}));

// `MapLabels` needs a real Leaflet pane and measured DOM boxes to place a chip, which is
// `MapLabels.test.jsx`'s harness and its job. What THIS file needs is the other end of the wire:
// the `spots` array `MapView` builds and hands over, whose `rating` becomes the chip's `N★` and
// its "<name>, N star" accessible name, and whose `onTheLight` becomes the tide glyph, the
// tooltip's third line and ", tide on the light" in that same name.
const labelSpotsSeen = { last: null };
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: ({ spots }) => {
    labelSpotsSeen.last = spots;
    return <div data-testid="map-labels-stub" />;
  },
}));

import MapView from '../components/MapView.jsx';

/** The last run that produced ratings scored YESTERDAY; nothing has been generated since. */
const YESTERDAY = '2026-01-14';
const TODAY = '2026-01-15';
const TOMORROW = '2026-01-16';

beforeEach(() => {
  markerNonce += 1;
  markerLabelAndColour.mockClear();
  buildStandDownSvg.mockClear();
  labelSpotsSeen.last = null;
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  // Mid-morning on TODAY. ⚠️ Only the UK DATE matters to anything asserted here — no test in this
  // file turns on the hour, and the fixture's own solar times are not arranged around it. The one
  // test that cares about the clock at all (the midnight roll, below) sets its own.
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
});
afterEach(() => { vi.useRealTimers(); localStorage.clear(); });

const SPOTS = [
  { id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East' },
  { id: 2, name: 'Tynemouth', lat: 55.02, lng: -1.42, rid: 'North East' },
];

/**
 * ⚠️ Marker names are made unique per test, and that is not tidiness — `makeMarkerIcon`'s cache is
 * MODULE-level and survives every render in this file, keyed on (name, scores, flags, emphasis,
 * ramp). Reuse one fixture across two tests and the second render is a cache hit, so
 * `markerLabelAndColour` is never called and an assertion on its arguments reads zero calls while
 * the component is working perfectly. (`MapViewHeat.test.jsx` records the same trap.)
 */
let markerNonce = 0;

/** Locations carrying a 4★ run on `scoredDate` and nothing else — the stale-cache shape. */
const makeLocations = (scoredDate) => SPOTS.map((s) => ({
  id: s.id,
  name: `${s.name}-${markerNonce}`,
  lat: s.lat,
  lon: s.lng,
  regionName: s.rid,
  bortleClass: 4,
  locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[scoredDate, {
    sunset: { rating: 4, solarEventTime: `${scoredDate}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    sunrise: { rating: 4, solarEventTime: `${scoredDate}T08:24:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
  }]]),
}));

/** Locations triaged (stood down) on `scoredDate` — no rating, but a reason recorded. */
const makeStandDownLocations = (scoredDate) => SPOTS.map((s) => ({
  id: s.id,
  name: s.name,
  lat: s.lat,
  lon: s.lng,
  regionName: s.rid,
  bortleClass: 4,
  locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[scoredDate, {
    sunset: { rating: null, triageReason: 'Overcast', solarEventTime: `${scoredDate}T16:12:00` },
    sunrise: { rating: null, triageReason: 'Overcast', solarEventTime: `${scoredDate}T08:24:00` },
  }]]),
}));

/**
 * The briefing keeps rebuilding its windows from Open-Meteo whether or not anything was
 * evaluated, so the EV list is NOT empty — which is exactly why `WindowControl` renders a pill
 * saying "No forecast" rather than returning null, and why this is the "list with rows, none of
 * them this window" case rather than the empty-domain one.
 */
/**
 * A tide-alignment index in the `{byId, byName}` shape `lookupForWindow` reads (id first, name
 * second). ⚠️ A plain `Map` here silently misses on every lookup — the control below caught it.
 */
const tideIndexFor = (date) => ({
  byId: new Map([
    [`1|${date}|SUNSET`, { onTheLight: true, phrase: 'HW 16:41 · 29m after sunset' }],
    [`2|${date}|SUNSET`, { onTheLight: true, phrase: 'HW 16:52 · 40m after sunset' }],
  ]),
  byName: new Map(),
});

const heat = {
  enabled: true,
  hasHome: false,
  spots: SPOTS,
  areaSpots: SPOTS,
  pointsByKey: new Map(),
  windows: [
    { key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: null, conf: 1 },
    { key: `${TOMORROW}:SUNRISE`, date: TOMORROW, targetType: 'SUNRISE', label: 'Tomorrow sunrise', time: '08:24', bestRating: null, conf: 0.82 },
  ],
  areaBounds: [[54.3, -3.4], [55.7, -1.3]],
  catalogueBounds: [[54.3, -3.4], [56.4, -1.3]],
};

async function renderMap(props) {
  let out;
  await act(async () => { out = render(<MapView autoEventType={null} {...props} />); });
  return out;
}

const footer = () => screen.getByTestId('wf-map-counts-footer');
const markerCount = () => screen.queryAllByTestId('marker').length;

describe('a window the EV list has no row for', () => {
  it('reports nothing rated — the pill and the catalogue agree', async () => {
    await renderMap({
      locations: makeLocations(YESTERDAY),
      // What `App`'s `defaultDate` used to fall back to: the most recent PAST date.
      date: YESTERDAY,
      forecastDates: [YESTERDAY],
      heat,
    });

    // The list has rows (tonight, tomorrow) — just none for the window on screen.
    expect(screen.getByTestId('wf-win-no-match')).toHaveTextContent('No forecast');
    expect(screen.getAllByTestId('wf-win-pill')).toHaveLength(1);

    // ...and nothing clears the 3★ floor, because nothing is rated for a window that is not here.
    expect(footer()).toHaveTextContent('0 of 2 shown');
    // The other half of the production caption — "· 130 rated" — is gone too. It renders only when
    // the rated count DIFFERS from the shown count, so its absence here is a real assertion about
    // the numerator, not a restatement of the line above.
    expect(screen.queryByTestId('wf-map-counts-rated')).not.toBeInTheDocument();
    expect(markerCount()).toBe(0);
  });

  it('control: the SAME fixture on a window that HAS a row rates both locations', async () => {
    // Identical props but for the date, so the assertion above cannot pass by the ratings being
    // absent, the fixture being misbuilt or the filter being on.
    await renderMap({
      locations: makeLocations(TODAY),
      date: TODAY,
      forecastDates: [TODAY],
      heat,
    });

    expect(screen.queryByTestId('wf-win-no-match')).not.toBeInTheDocument();
    expect(footer()).toHaveTextContent('2 of 2 shown');
    expect(markerCount()).toBe(2);
  });

  it('is per-WINDOW, not per-date — one event of a date can have a row while the other has none', async () => {
    // ⚠️ The case that separates this gate from a naive "is the date today-forward" test. TOMORROW
    // is outside `forecastDates` (so no D-13 filler for either event) and `heat.windows` serves its
    // SUNRISE only — so TOMORROW:SUNRISE has a row and TOMORROW:SUNSET does not, on one date.
    // Without it, `solarRowExists(date, 'SUNSET')` hardcoded — and `date >= todayStr` substituted
    // for the whole predicate — both passed the entire suite.
    const locations = SPOTS.map((sp) => ({
      id: sp.id,
      name: `${sp.name}-${markerNonce}`,
      lat: sp.lat,
      lon: sp.lng,
      regionName: sp.rid,
      bortleClass: 4,
      locationType: ['LANDSCAPE'],
      forecastsByDate: new Map([[TOMORROW, {
        sunrise: { rating: 4, solarEventTime: `${TOMORROW}T08:24:00` },
        sunset: { rating: 4, solarEventTime: `${TOMORROW}T16:12:00` },
      }]]),
    }));
    await renderMap({ locations, date: TOMORROW, forecastDates: [TODAY], heat });

    // The map lands on SUNSET (the default for a non-today date) — which has no row.
    expect(screen.getByTestId('wf-win-no-match')).toHaveTextContent('No forecast');
    expect(footer()).toHaveTextContent('0 of 2 shown');

    // Step to the sibling event on the SAME date, which does have a row.
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const row = screen.getAllByTestId('wf-win-row')
      .find((r) => r.getAttribute('data-ev-id') === `solar:${TOMORROW}:SUNRISE`);
    expect(row?.getAttribute('data-ev-id')).toBe(`solar:${TOMORROW}:SUNRISE`);
    await act(async () => { fireEvent.click(row); });

    expect(screen.queryByTestId('wf-win-no-match')).not.toBeInTheDocument();
    expect(footer()).toHaveTextContent('2 of 2 shown');
  });

  it('keeps the rating on a window the BRIEFING has retired but D-13 still carries', async () => {
    // The structural case the gate must not swallow. `heat.windows` carries only tonight and
    // tomorrow — today's sunrise has been retired from the briefing — but today IS in the forecast
    // domain, so `buildMapEvents` emits a D-13 filler row for it and the `‹` stepper walks
    // straight into it. A row exists, so the rating is the real answer for that window. The gate
    // asks whether the row exists, NEVER whether the window has passed.
    await renderMap({
      locations: makeLocations(TODAY),
      date: TODAY,
      forecastDates: [TODAY],
      heat,
    });

    // Selected through the real control, so the row's existence is proven rather than assumed —
    // setting `eventType` by prop would pass just as well if the row were missing and the map had
    // quietly stayed on the served sunset window.
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const sunriseRow = screen.getAllByTestId('wf-win-row')
      .find((r) => r.getAttribute('data-ev-id') === `solar:${TODAY}:SUNRISE`);
    // Asserted on the attribute, not `toBeTruthy()`: a truthiness check on a `find` passes for any
    // non-null element, including the wrong one (frontend-test-standards.md).
    expect(sunriseRow?.getAttribute('data-ev-id')).toBe(`solar:${TODAY}:SUNRISE`);
    await act(async () => { fireEvent.click(sunriseRow); });

    expect(screen.queryByTestId('wf-win-no-match')).not.toBeInTheDocument();
    expect(footer()).toHaveTextContent('2 of 2 shown');
  });

  it('re-asks the question when the UK date rolls under a mount that nothing else changed', async () => {
    // ⚠️ The gate's predicate is memoised while `buildMapEvents` reads the clock fresh every
    // render, so the two must be given the SAME today or they part company across UK midnight —
    // the EV list drops the row while the predicate goes on permitting its ratings, which is this
    // whole defect reopened until the next briefing poll lands. Found by adversarial review.
    //
    // Served windows are deliberately kept off `date` here: with one, the row survives midnight on
    // its own (a served window is a row unconditionally) and there would be nothing to observe.
    // ⚠️ Every prop below is built ONCE and passed by the SAME reference to both renders. The
    // first cut rebuilt `heat`, `locations` and `forecastDates` as fresh literals in the rerender,
    // which busts a `[heat, forecastDates]` memo all by itself — so the test passed with
    // `mapTodayStr` deleted from the deps and proved nothing. Caught by mutation testing.
    const stableLocations = makeLocations(TODAY);
    const stableDates = [TODAY];
    const stableHeat = { ...heat, windows: [heat.windows[1]] };
    const props = {
      autoEventType: null,
      locations: stableLocations,
      date: TODAY,
      forecastDates: stableDates,
      heat: stableHeat,
    };

    vi.setSystemTime(new Date(`${TODAY}T23:50:00Z`));
    let rerender;
    await act(async () => {
      ({ rerender } = render(<MapView {...props} resizeNonce={1} />));
    });
    // Before midnight: today is today, the filler row exists, both locations are rated.
    expect(screen.queryByTestId('wf-win-no-match')).not.toBeInTheDocument();
    expect(footer()).toHaveTextContent('2 of 2 shown');

    // The date rolls. Nothing else changes — the reader has not touched anything, and every prop
    // is the identical reference; only `resizeNonce` moves, to get past `React.memo`.
    vi.setSystemTime(new Date(`${TOMORROW}T00:10:00Z`));
    await act(async () => { rerender(<MapView {...props} resizeNonce={2} />); });

    // The EV list has moved on, so the gate must have too.
    expect(screen.getByTestId('wf-win-no-match')).toHaveTextContent('No forecast');
    expect(footer()).toHaveTextContent('0 of 2 shown');
  });

  it('withdraws the "PhotoCast-scored locations shown" chip', async () => {
    // ⚠️ In the production capture that reported this, that chip was on screen — bottom right,
    // over a blank map, beside a pill saying "No forecast". It reads `briefingScores` by
    // `|date|eventType|`: the same index, keyed the same way, as the rating accessor, so it
    // carried rows for windows the EV list excludes and asserted the map was showing them.
    const briefingScores = new Map([
      [`North East|${YESTERDAY}|SUNSET|Bamburgh`, { rating: 4 }],
      [`North East|${TODAY}|SUNSET|Bamburgh`, { rating: 4 }],
    ]);
    await renderMap({
      locations: makeLocations(YESTERDAY),
      date: YESTERDAY,
      forecastDates: [YESTERDAY],
      heat,
      briefingScores,
    });

    expect(screen.getByTestId('wf-win-no-match')).toHaveTextContent('No forecast');
    expect(screen.queryByTestId('photocast-scored-legend')).not.toBeInTheDocument();
  });

  it('control: the SAME score map on a window that HAS a row keeps the chip', async () => {
    const briefingScores = new Map([
      [`North East|${YESTERDAY}|SUNSET|Bamburgh`, { rating: 4 }],
      [`North East|${TODAY}|SUNSET|Bamburgh`, { rating: 4 }],
    ]);
    await renderMap({
      locations: makeLocations(TODAY),
      date: TODAY,
      forecastDates: [TODAY],
      heat,
      briefingScores,
    });

    expect(screen.getByTestId('photocast-scored-legend')).toBeInTheDocument();
  });

  it('withholds the rating from the MEDALLION layer too, on the one view where it is visible', async () => {
    // ⚠️ `heat` is omitted, and that is the whole point. The medallions are normally invisible on
    // this tab — `MapHeatLayer` pins their panes to opacity 0 — but that layer mounts only when a
    // field exists, so with no roster join at all they become the tab's ONLY location vocabulary
    // (no chips, no pins). The marker block used to carry its own copy of the briefing-then-
    // forecast precedence, so the gate reached every other surface and not this one.
    //
    // An admin turning the `?` unknown lens on is what puts a gate-blanked location back into
    // `visibleLocations`, which is what makes the medallion render at all.
    await renderMap({
      locations: makeLocations(YESTERDAY),
      date: YESTERDAY,
      forecastDates: [YESTERDAY],
    });
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    fireEvent.click(screen.getByTestId('star-filter-unrated'));

    expect(markerCount()).toBe(2);
    expect(markerLabelAndColour).toHaveBeenCalled();
    // Every medallion resolved a null rating — not yesterday's 4 — and the arcs beside it went
    // with it: `markerLabelAndColour(rating, fierySky, goldenHour, isPureWildlife)`.
    for (const call of markerLabelAndColour.mock.calls) {
      expect(call.slice(0, 3)).toEqual([null, null, null]);
    }
  });

  it('control: the SAME medallions on a window that HAS a row carry their rating', async () => {
    await renderMap({
      locations: makeLocations(TODAY),
      date: TODAY,
      forecastDates: [TODAY],
    });

    expect(markerCount()).toBe(2);
    expect(markerLabelAndColour).toHaveBeenCalled();
    expect(markerLabelAndColour.mock.calls.some((c) => c[0] === 4)).toBe(true);
    // ...and the arcs are present, so the null-triple above is the gate and not the fixture.
    expect(markerLabelAndColour.mock.calls.some((c) => c[1] === 70 && c[2] === 60)).toBe(true);
  });

  it('withholds the medallion STAND-DOWN glyph too', async () => {
    // Kills the sibling of the mutation above: the marker block also carried its own
    // `resolveStandDown` call, so reverting only the rating would leave the grey triaged glyph
    // asserting a verdict for a window the tab has no row for.
    await renderMap({
      locations: makeStandDownLocations(YESTERDAY),
      date: YESTERDAY,
      forecastDates: [YESTERDAY],
    });
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    fireEvent.click(screen.getByTestId('star-filter-unrated'));

    expect(markerCount()).toBe(2);
    expect(buildStandDownSvg).not.toHaveBeenCalled();
  });

  it('control: the SAME triage on a window that HAS a row draws the stand-down glyph', async () => {
    await renderMap({
      locations: makeStandDownLocations(TODAY),
      date: TODAY,
      forecastDates: [TODAY],
    });
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    fireEvent.click(screen.getByTestId('star-filter-standdown'));

    expect(buildStandDownSvg).toHaveBeenCalled();
  });

  it('hands MapLabels no star and no tide claim — the chips the report was about', async () => {
    // The chip is where the reported defect was actually SEEN: `spot.rating` is printed as `N★`
    // and spoken as "<name>, N star". `onTheLight` rides the same array and is the same kind of
    // claim — "the water lands on this window's light" — so it is gated with it.
    await renderMap({
      locations: makeLocations(YESTERDAY),
      date: YESTERDAY,
      forecastDates: [YESTERDAY],
      heat,
      tideAlignmentIndex: tideIndexFor(YESTERDAY),
    });

    expect(screen.getByTestId('wf-win-no-match')).toHaveTextContent('No forecast');
    // Nothing survives the rating floor, so there is no chip to carry either claim...
    expect(labelSpotsSeen.last).toEqual([]);
  });

  it('control: the SAME fixture on a real window carries both the star and the tide', async () => {
    await renderMap({
      locations: makeLocations(TODAY),
      date: TODAY,
      forecastDates: [TODAY],
      heat,
      tideAlignmentIndex: tideIndexFor(TODAY),
    });

    expect(labelSpotsSeen.last).toHaveLength(2);
    for (const spot of labelSpotsSeen.last) {
      expect(spot.rating).toBe(4);
      expect(spot.onTheLight).toBe(true);
    }
  });

  it('withholds the tide claim from a chip that IS drawn on a rowless window', async () => {
    // ⚠️ The rating floor hides most locations, but not all: a pure-wildlife site has no sky
    // rating by design and is drawn regardless (`isPureWildlife || showUnrated`). A COASTAL
    // wildlife hide therefore still gets a chip on a window with no row — and without the tide
    // gate that chip kept the wave glyph and the ", tide on the light" in its accessible name.
    const wildlife = makeLocations(YESTERDAY).map((l) => ({ ...l, locationType: ['WILDLIFE'] }));
    await renderMap({
      locations: wildlife,
      date: YESTERDAY,
      forecastDates: [YESTERDAY],
      heat,
      tideAlignmentIndex: tideIndexFor(YESTERDAY),
    });

    expect(labelSpotsSeen.last).toHaveLength(2);
    for (const spot of labelSpotsSeen.last) {
      expect(spot.rating).toBeNull();
      expect(spot.onTheLight).toBe(false);
      expect(spot.nearestSolarOffsetPhrase).toBeNull();
    }
  });

  it('says "No forecast to show." when the EV list has rows but none for this window', async () => {
    // The state the production report was in: a pill reading "No forecast" over a map with
    // nothing on it. The pill is a corner chip; the body had no account of itself at all.
    await renderMap({
      locations: makeLocations(YESTERDAY),
      date: YESTERDAY,
      forecastDates: [YESTERDAY],
      heat,
    });

    expect(screen.getByTestId('wf-win-no-match')).toHaveTextContent('No forecast');
    expect(screen.getByTestId('wf-map-no-forecast')).toHaveTextContent('No forecast to show.');
  });

  it('says it in PINS view too — the map is exactly as empty there', async () => {
    // ⚠️ `windowUnscored` is `heatOn`-gated because a colour key explains a gradient. A sentence
    // explaining an absence is not a key, so that reasoning does not transfer.
    await renderMap({
      locations: makeLocations(YESTERDAY),
      date: YESTERDAY,
      forecastDates: [YESTERDAY],
      heat,
    });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /Pins/i })); });

    expect(screen.getByTestId('wf-map-no-forecast')).toBeInTheDocument();
  });

  it('says it when there is no window control AT ALL — the day nothing was forecast', async () => {
    // `WindowControl` returns null on an empty EV list rather than render a control with nothing
    // behind it, so this state had no pill AND no message: the tab said nothing whatsoever.
    await renderMap({
      locations: makeLocations(YESTERDAY),
      date: YESTERDAY,
      forecastDates: [YESTERDAY],
      heat: { ...heat, windows: [] },
    });

    expect(screen.queryByTestId('wf-win-pill')).not.toBeInTheDocument();
    expect(screen.getByTestId('wf-map-no-forecast')).toHaveTextContent('No forecast to show.');
  });

  it('stays silent when a window IS on screen — one voice, not two', async () => {
    // A served window with nothing rated is a different and more precise claim, and the existing
    // line already makes it. Printing both would be the "second voice" the older derivation
    // guards against; that argument still holds wherever a window actually exists.
    await renderMap({
      locations: makeLocations(TODAY),
      date: TODAY,
      forecastDates: [TODAY],
      heat,
    });

    expect(screen.queryByTestId('wf-map-no-forecast')).not.toBeInTheDocument();
    expect(screen.getByTestId('wf-map-heat-unscored')).toBeInTheDocument();
  });

  it('defers to the unscored line in ASTRO mode, which has no row but a real claim', async () => {
    // ⚠️ The one overlap, and it is why the two lines are mutually exclusive rather than each
    // gated on its own condition. A catalogue with no astro conditions carries no astro EV row, so
    // `activeMapEvent` is null here — but astro's unscored state is derived from its own point set,
    // which IS a statement about the forecast rather than about the camera, and that line is
    // deliberately exempt from the row gate. Without the exclusion both sentences would print.
    await renderMap({
      locations: makeLocations(TODAY),
      date: TODAY,
      forecastDates: [TODAY],
      heat,
      autoEventType: 'ASTRO',
    });

    expect(screen.getByTestId('wf-map-heat-unscored')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-map-no-forecast')).not.toBeInTheDocument();
  });

  it('leaves the frozen Plan-tab overlay alone — it builds no EV list to be judged against', async () => {
    // The overlay is `overlayMode ? [] : buildMapEvents(...)`: it has no EV list, no window
    // control and no pill, and inherits its window from the Plan card that opened it. There is
    // nothing there for a rating to contradict, so gating it would blank the whole frozen surface.
    //
    // ⚠️ `forecastDates` is passed HERE and `App` does not pass it to the overlay today — that is
    // deliberate. Without it the predicate sees an empty domain and fails open, so this test would
    // pass with the `overlayMode` exemption deleted and prove nothing (mutation-checked: it did).
    // The overlay's protection must rest on WHAT IT IS, not on which props it currently happens to
    // be handed — a later change adding `forecastDates` here for some unrelated reason must not
    // silently blank it.
    await renderMap({
      locations: makeLocations(YESTERDAY),
      date: YESTERDAY,
      forecastDates: [TODAY],
      overlayMode: true,
    });

    expect(screen.queryByTestId('wf-map-counts-footer')).not.toBeInTheDocument();
    expect(markerCount()).toBe(2);
    // ...and no empty-state line either. ⚠️ This assertion is STRUCTURAL rather than load-bearing,
    // and mutation testing is what established that: rendering the line in the overlay arm too
    // changes nothing observable, because that arm's whole chrome block is `heatOffered`-gated and
    // the overlay is deliberately handed no `heat` at all. Kept as documentation of the intent —
    // the overlay inherits its window from the card that opened it and has no "which window" state
    // to be empty about — and as a tripwire if the overlay is ever given a field.
    expect(screen.queryByTestId('wf-map-no-forecast')).not.toBeInTheDocument();
  });

  it('reports no STAND-DOWN either — a triage verdict is a claim about a window too', async () => {
    // The same defect one field over: `resolveStandDown` reads the same two per-window indexes,
    // so without its own gate a grey "stood down" pin outlived the stars beside it, still
    // asserting that this window had been triaged.
    await renderMap({
      locations: makeStandDownLocations(YESTERDAY),
      date: YESTERDAY,
      forecastDates: [YESTERDAY],
      heat,
    });

    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    // Disabled means `hasStandDown` is false: nothing on screen claims a triage for this window.
    expect(screen.getByTestId('star-filter-standdown')).toBeDisabled();
  });

  it('control: the SAME stand-downs on a window that HAS a row are offered', async () => {
    await renderMap({
      locations: makeStandDownLocations(TODAY),
      date: TODAY,
      forecastDates: [TODAY],
      heat,
    });

    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    expect(screen.getByTestId('star-filter-standdown')).toBeEnabled();
  });
});
