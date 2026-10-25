import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render, cleanup } from '@testing-library/react';

/**
 * The `heat` prop the Map pane builds, and the reasons each field is shaped the way it is.
 *
 * <p>`MapView` is stubbed and every assertion is about what it is HANDED — the same level
 * `WindowFirstMapPane.test.jsx` works at, and the right one: what this component decides is
 * entirely a matter of props. The field's pixels belong to `heatField.test.js` and its Leaflet
 * hosting to `MapHeatLayer.test.jsx`.
 *
 * <p>The rules worth breaking a build over: the prop is withheld when there is no catalogue, the
 * area segment is declared absent when the planning area is not a real narrowing, the window list
 * carries the arm's own six (away days included) with each window's confidence already resolved,
 * and the whole object keeps ONE identity across a re-render — `MapView` is `React.memo`, and a
 * fresh literal per render would rebuild every marker on every provider tick.
 */

const MapStub = { lastProps: null, renders: 0 };
vi.mock('../components/MapView.jsx', () => ({
  default: (props) => {
    MapStub.lastProps = props;
    MapStub.renders += 1;
    return <div data-testid="stub-map" />;
  },
}));
// The pane mounts the reader's outcome-rating hook (outcome-rating-plan.md §2 #5): it reads the role
// and asks for the range of ratings — never answered here (a read that never lands changes no state, so
// no act() noise), since this file is about what the pane hands the map, not about ratings
// (`useOutcomeRatings.test.js` and `WindowFirstMapPaneRatings.test.jsx` are).
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
vi.mock('../api/outcomeRatingApi.js', () => ({
  fetchMyRatings: vi.fn(() => new Promise(() => {})),
  putRating: vi.fn(),
}));
let briefingValue = null;
vi.mock('../context/WindowFirstBriefingContext.jsx', () => ({
  useWindowFirstBriefing: () => briefingValue,
}));

import WindowFirstMapPane from '../components/WindowFirstMapPane.jsx';
import { windowKey } from '../utils/windowKeys.js';
import { formatTime } from '../utils/briefingDisplay.js';

const TODAY = '2026-08-11';

const SPOTS = [
  { id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, regionName: 'North East', rid: 'North East', bortleClass: 4, scores: [5, 4] },
  { id: 2, name: 'Wastwater', lat: 54.44, lng: -3.30, regionName: 'The Lakes', rid: 'The Lakes', bortleClass: 2, scores: [3, null] },
  { id: 3, name: 'Kelso', lat: 56.20, lng: -2.43, regionName: 'The Borders', rid: 'The Borders', bortleClass: 5, scores: [2, 2] },
];

/** North East 40 min, The Lakes 120 min, The Borders 400 min — the last is beyond GLANCE (180). */
const REACH = new Map([[1, { driveMinutes: 40 }], [2, { driveMinutes: 120 }], [3, { driveMinutes: 400 }]]);

// `pickKind` is what `buildHeatStripCards` publishes — it narrows the served `Pick` record to its
// kind alone — so the fixture carries that and never a `pick` object. The map's forwarding is
// asserted against this shape below.
// `tide` is the raw served `BriefingWindowTide` `buildHeatStripCards` folds through untouched
// (tide-window-plan.md T6) — present on the SUNSET card, absent (null) on the away SUNRISE one,
// mirroring how a real payload has no coastal representative for every window.
const TIDE = { locationName: 'Bamburgh Beach', state: 'MID', curve: [0, 1] };
const STRIP_CARDS = [
  {
    key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '20:41', bestRating: 4, confidence: 'high', away: false, pickKind: 'best', tide: TIDE,
  },
  {
    key: '2026-08-14:SUNRISE', date: '2026-08-14', targetType: 'SUNRISE', label: 'Fri sunrise', time: '05:31', bestRating: null, confidence: null, away: true, pickKind: null, tide: null,
  },
];

function context(overrides = {}) {
  return {
    heatSpots: SPOTS,
    heatPointSets: new Map([[`${TODAY}:SUNSET`, []]]),
    heatStripCards: STRIP_CARDS,
    reachById: REACH,
    // A STRING, which is what the provider actually publishes (`homePlaceName || homePostcode ||
    // null`). The first cut used an object: truthy either way, so the assertion held while the
    // fixture misrepresented the contract.
    homePlace: 'Newcastle upon Tyne',
    todayStr: TODAY,
    ...overrides,
  };
}

function renderPane(ctx = context(), props = {}) {
  briefingValue = ctx;
  return render(
    <WindowFirstMapPane
      locations={[]}
      dates={[TODAY]}
      selectedDate={TODAY}
      onSelectDate={vi.fn()}
      {...props}
    />,
  );
}

beforeEach(() => {
  MapStub.lastProps = null;
  MapStub.renders = 0;
});
afterEach(() => { briefingValue = null; vi.restoreAllMocks(); });

describe('WindowFirstMapPane — the heat prop', () => {
  it('hands the map the catalogue and the point sets from the ONE join the provider built', () => {
    // Three surfaces read this join — the strip, the row maps and this tab — and building it here
    // would be a third chance to disagree about which locations exist.
    renderPane();
    expect(MapStub.lastProps.heat.enabled).toBe(true);
    expect(MapStub.lastProps.heat.spots).toBe(SPOTS);
    expect(MapStub.lastProps.heat.pointsByKey).toBe(briefingValue.heatPointSets);
  });

  it('carries each window\'s served rating onto the selector\'s window list', () => {
    // `MapView`'s unscored note reads it, and it must be the STRIP CARD's own field rather than
    // anything re-derived here: the whole point is that the Map tab and the Plan tab answer "is
    // this window rated" from one value.
    renderPane();
    expect(MapStub.lastProps.heat.windows.map((w) => w.bestRating)).toEqual([4, null]);
  });

  it('reports no field at all when the join produced no spots', () => {
    // The degrade path: a failed `evaluate/scores` fetch, an empty roster, a briefing that has not
    // arrived. `MapView` answers `enabled: false` by rendering neither the toolbar nor the layer.
    renderPane(context({ heatSpots: [] }));
    expect(MapStub.lastProps.heat.enabled).toBe(false);
  });

  it('narrows the area to the regions inside the glance, leaving the beyond one out', () => {
    // `planningArea`'s rule, applied here for the framing only: The Borders is 400 minutes away and
    // must not decide where the map opens.
    renderPane();
    expect(MapStub.lastProps.heat.areaSpots.map((s) => s.name)).toEqual(['Bamburgh', 'Wastwater']);
  });

  it('declares the area segment absent when the whole roster is already inside it (D6)', () => {
    // Two identical states is a dead control. The narrowing has to be REAL, not merely possible.
    renderPane(context({ reachById: new Map([[1, { driveMinutes: 10 }], [2, { driveMinutes: 20 }], [3, { driveMinutes: 30 }]]) }));
    expect(MapStub.lastProps.heat.hasHome).toBe(false);
  });

  it('declares it absent while the settings are still loading', () => {
    // `homePlace` is `undefined` between mount and the settings response, and the segment must not
    // flicker into existence on a framing that has not been measured yet.
    renderPane(context({ homePlace: undefined, reachById: new Map() }));
    expect(MapStub.lastProps.heat.hasHome).toBe(false);
  });

  it('declares it absent when no postcode is saved, however the reach map happens to look', () => {
    // With no home there are no drive times, every region is unmeasured-and-therefore-in, and the
    // area IS the catalogue. Keying on `homePlace` as well says so in one place rather than relying
    // on the arithmetic to come out the same way.
    renderPane(context({ homePlace: null, reachById: new Map() }));
    expect(MapStub.lastProps.heat.hasHome).toBe(false);
    expect(MapStub.lastProps.heat.areaSpots).toHaveLength(SPOTS.length);
  });

  it('frames the area and the catalogue as two DIFFERENT boxes, both padded by the bundle’s 0.12°', () => {
    renderPane();
    const { areaBounds, catalogueBounds } = MapStub.lastProps.heat;
    // ⚠️ The out-of-reach spot has to sit OUTSIDE the area's own box or the two are identical
    // numbers and this assertion is vacuous — Kelso is at 56.20°N, north of everything in reach.
    // A fixture that merely dropped a spot from the list would prove nothing here.
    expect(areaBounds).not.toEqual(catalogueBounds);
    // South-west then north-east, Leaflet's own order; the pad is 0.12° of latitude.
    expect(areaBounds[0][0]).toBeCloseTo(54.44 - 0.12, 5);
    expect(areaBounds[1][0]).toBeCloseTo(55.61 + 0.12, 5);
    expect(catalogueBounds[1][0]).toBeCloseTo(56.20 + 0.12, 5);
  });

  it('supplies no boxes at all when there is nothing to frame', () => {
    // `bbox` over an empty list has no answer, and `MapView` falls back to the default framing on null.
    renderPane(context({ heatSpots: [] }));
    expect(MapStub.lastProps.heat.areaBounds).toBeNull();
    expect(MapStub.lastProps.heat.catalogueBounds).toBeNull();
  });

  it('offers every rendered window, away days included, so the six are the strip’s six', () => {
    // Removing an away window would renumber the shape of the week between two surfaces that are
    // meant to be one. It simply has no points and paints nothing, which is the same answer the
    // Plan tab's own thumbnail gives it.
    renderPane();
    expect(MapStub.lastProps.heat.windows.map((w) => w.key))
      .toEqual([`${TODAY}:SUNSET`, '2026-08-14:SUNRISE']);
    expect(MapStub.lastProps.heat.windows[0].label).toBe('Tonight sunset');
    expect(MapStub.lastProps.heat.windows[0].time).toBe('20:41');
  });

  it('resolves each window’s confidence to the SAME scalar the badge decays by (D3)', () => {
    // One language for the haze and the badge. `high` → 1.0; the away window carries no tier, so it
    // falls through the fail-soft path to the horizon's — T+3, inferred, capped at medium → 0.82.
    renderPane();
    expect(MapStub.lastProps.heat.windows[0].conf).toBe(1);
    expect(MapStub.lastProps.heat.windows[1].conf).toBe(0.82);
  });

  it('forwards each window\'s served pick kind, and only that', () => {
    // ⚠️ This is the join nothing crossed in the first cut of the phase: the pure builder was
    // tested, the EV row was tested, and the FOLD between them read `card.pick` — a field
    // `buildHeatStripCards` does not publish — so every window's pick was `undefined` forever with
    // a fully green suite. Asserting against the real card shape is what catches a re-key.
    renderPane();

    expect(MapStub.lastProps.heat.windows[0].pickKind).toBe('best');
    expect(MapStub.lastProps.heat.windows[1].pickKind).toBeNull();
  });

  it('forwards each window\'s served tide object, and null when there is none', () => {
    // ⚠️ The identical join, one field over — and the one that actually shipped broken: the pure
    // builder (`windowFirstCards.test.js`) was tested, the EV row (`mapEvents.test.js`) was
    // tested, and this fold read no `tide` at all, so `card.tide` never reached `heat.windows`
    // and the Map tab's tide strip stayed invisible in production with a fully green suite. Found
    // in browser verification against a live payload, not by any test — this is the test that
    // would have caught it.
    renderPane();

    expect(MapStub.lastProps.heat.windows[0].tide).toBe(TIDE);
    expect(MapStub.lastProps.heat.windows[1].tide).toBeNull();
  });

  it('hands the map the briefing\'s tide for EVERY window it carries, including ones the strip does not render', () => {
    // ⚠️ The same join once more, and the one the Sunday-sunrise defect lived in: the tide strip
    // read only `heat.windows` (the rendered six), so a window the briefing HAD a tide for but the
    // Plan tab did not draw became a tide-less D-13 filler. The index is built from
    // `briefing.days`, not from the cards, so it must reach the map even for an unrendered event.
    const unrenderedTide = { locationName: 'Bamburgh', state: 'LOW' };
    renderPane(context({
      briefing: {
        days: [{
          date: '2026-08-14',
          eventSummaries: [{ targetType: 'SUNRISE', window: { tide: unrenderedTide } }],
        }],
      },
    }));

    const entry = MapStub.lastProps.heat.tideByWindow.get(windowKey('2026-08-14', 'SUNRISE'));
    expect(entry.tide).toBe(unrenderedTide);
    // No served eventTime: no time is invented for it.
    expect(entry.eventTime).toBeNull();
    expect(entry.time).toBeNull();
  });

  it('formats the lent clock time with the formatter served rows use, from the window\'s eventTime', () => {
    renderPane(context({
      briefing: {
        days: [{
          date: '2026-08-14',
          eventSummaries: [{
            targetType: 'SUNRISE',
            window: { tide: { locationName: 'B' }, eventTime: '2026-08-14T04:31:00' },
          }],
        }],
      },
    }));
    const entry = MapStub.lastProps.heat.tideByWindow.get(windowKey('2026-08-14', 'SUNRISE'));
    expect(entry.eventTime).toBe('2026-08-14T04:31:00');
    expect(entry.time).toBe(formatTime('2026-08-14T04:31:00'));
    // 04:31 UTC is 05:31 in BST: the formatter converts, the pane does no time maths of its own.
    expect(entry.time).toBe('05:31');
    expect(entry.time).toBeTruthy();
  });

  it('rebuilds the tide index when the briefing\'s days change', () => {
    const day = (name) => [{
      date: '2026-08-14',
      eventSummaries: [{ targetType: 'SUNRISE', window: { tide: { locationName: name } } }],
    }];
    const { rerender } = renderPane(context({ briefing: { days: day('First') } }));
    const key = windowKey('2026-08-14', 'SUNRISE');
    expect(MapStub.lastProps.heat.tideByWindow.get(key).tide.locationName).toBe('First');

    briefingValue = context({ briefing: { days: day('Second') } });
    rerender(
      <WindowFirstMapPane
        locations={[]}
        dates={[TODAY]}
        selectedDate={TODAY}
        onSelectDate={vi.fn()}
      />,
    );
    expect(MapStub.lastProps.heat.tideByWindow.get(key).tide.locationName).toBe('Second');
  });

  it('forwards each window\'s TRAVEL flag — the next-two heading needs it and the fold dropped it', () => {
    // ⚠️ The same join, one field over. `buildHeatStripCards`' contract is "every window the strip
    // must show, travel days included", so an away day reaches the EV list as an ordinary served
    // row — and without `away` the next-two heading read "Tomorrow — sunrise or sunset?" over two
    // days the reader is not there for, while the Plan matrix drew the same two cards as Away. Asserted here because no test below this fold can see the field go missing.
    renderPane();

    expect(MapStub.lastProps.heat.windows[0].away).toBe(false);
    expect(MapStub.lastProps.heat.windows[1].away).toBe(true);
  });

  it('forwards NO verdict — the map derives its own from the reader\'s scope', () => {
    // The served word is the Plan tab's answer over the whole roster; the map's has to move with
    // the scope segment. Two channels for one window is the defect this absence prevents.
    renderPane();

    for (const window of MapStub.lastProps.heat.windows) {
      expect(window.verdict).toBeUndefined();
      expect(window.verdictLabel).toBeUndefined();
      expect(window.regionName).toBeUndefined();
    }
  });

  it('hands MapView a region-verdict index built from the same briefing days', () => {
    renderPane();

    expect(MapStub.lastProps.regionVerdictIndex).toBeInstanceOf(Map);
  });

  describe('window states (thin-sample F4) wait for the scores', () => {
    const STATE_SPOTS = SPOTS.map((s, i) => ({
      ...s, skySubject: true, states: [i === 0 ? 'rated' : 'unrated', 'rated'],
    }));
    const EVENTS = [
      { date: TODAY, targetType: 'SUNSET' },
      { date: '2026-08-14', targetType: 'SUNRISE' },
    ];
    const withScores = (scoresLoaded) => context({
      heatSpots: STATE_SPOTS, upcomingEvents: EVENTS, scoresLoaded,
    });

    it('offers a served window no states while the scores are pending, so nothing reads "unrated"', () => {
      renderPane(withScores(false));
      expect(MapStub.lastProps.heat.windowStates.has(`${TODAY}:SUNSET`)).toBe(false);
    });

    it('offers none after a failed fetch either — scoresLoaded is success-only', () => {
      // A rejected fetch leaves `scoresLoaded` false, which is the same context value as pending.
      renderPane(withScores(false));
      expect(MapStub.lastProps.heat.windowStates.size).toBe(0);
    });

    it('offers them once the scores have loaded', () => {
      renderPane(withScores(true));
      const entry = MapStub.lastProps.heat.windowStates.get(`${TODAY}:SUNSET`);
      // One rated spot is evidence for the window, so its unrated neighbours are hatched.
      expect(entry.unrated.map((p) => p.name)).toEqual(['Wastwater', 'Kelso']);
    });

    it('offers the stood list, reasons and region names included, only once the scores have loaded (big-seas F3)', () => {
      const stoodSpots = SPOTS.map((s, i) => ({
        ...s,
        skySubject: true,
        states: [i === 1 ? 'stood' : 'rated', 'rated'],
        reasons: [i === 1 ? 'PRECIPITATION' : null, null],
      }));
      const ctx = (scoresLoaded) => context({
        heatSpots: stoodSpots, upcomingEvents: EVENTS, scoresLoaded,
      });
      renderPane(ctx(false));
      expect(MapStub.lastProps.heat.windowStates.size).toBe(0);
      cleanup();
      renderPane(ctx(true));
      const entry = MapStub.lastProps.heat.windowStates.get(`${TODAY}:SUNSET`);
      expect(entry.stood).toEqual([{
        lat: 54.44, lng: -3.3, reason: 'PRECIPITATION', rid: 'The Lakes', key: '#2',
      }]);
    });

    it('offers no unrated points for a window with no rated or stood spot (empty scores response)', () => {
      const none = STATE_SPOTS.map((s) => ({ ...s, states: ['unrated', 'unrated'] }));
      renderPane(context({ heatSpots: none, upcomingEvents: EVENTS, scoresLoaded: true }));
      const entry = MapStub.lastProps.heat.windowStates.get(`${TODAY}:SUNSET`);
      expect(entry.unrated).toEqual([]);
    });
  });

  it('keeps ONE prop identity across a re-render, because MapView is memoised', () => {
    // A fresh object literal per render defeats `React.memo` and rebuilds every marker on every
    // provider tick — and the provider re-renders on every health SSE beat.
    const { rerender } = renderPane();
    const first = MapStub.lastProps.heat;
    rerender(
      <WindowFirstMapPane
        locations={[]}
        dates={[TODAY]}
        selectedDate={TODAY}
        onSelectDate={vi.fn()}
      />,
    );
    expect(MapStub.renders).toBeGreaterThan(1);
    expect(MapStub.lastProps.heat).toBe(first);
  });
});

/**
 * The origin on the Map tab (plan §4.8, P7).
 *
 * <p><b>What breaks if these fail.</b> "All six thumbnails re-frame" is pinned on the Plan strip;
 * this is the same claim's other surface, and a half-applied origin — a Map tab opening on the home
 * planning area while the strip has re-framed to the region — is worse than none. The drive figures
 * matter as much: `MapView` fetches the per-user times itself, so without an overwrite the pin
 * popups and the drive-time filter measure from the reader's house on a tab framed around Keswick.
 */
describe('WindowFirstMapPane — the origin', () => {
  const LAKES = { id: 3, name: 'The Lakes', baseName: 'Keswick' };
  const BASE_REACH = new Map([[2, { driveMinutes: 8, distanceMiles: null }]]);

  it('frames on the planning area at home', () => {
    renderPane();
    expect(MapStub.lastProps.heat.areaSpots.map((s) => s.name)).toEqual(['Bamburgh', 'Wastwater']);
  });

  it('⚠️ frames on the ORIGIN\'s own region when away — including one the home area excludes', () => {
    renderPane(context({ origin: { id: 4, name: 'The Borders', baseName: 'Kelso' } }));
    // The Borders is 400 minutes from home, i.e. beyond GLANCE and outside the planning area. The
    // origin move is precisely how a reader reaches it, so the frame must not still be the area's.
    expect(MapStub.lastProps.heat.areaSpots.map((s) => s.name)).toEqual(['Kelso']);
  });

  it('draws the area segment away even with no home postcode saved', () => {
    // Without the widening an away reader who has never set a postcode loses the control entirely,
    // on a tab whose frame the origin has just narrowed — two states, one of them unreachable.
    renderPane(context({ homePlace: null, origin: LAKES }));
    expect(MapStub.lastProps.heat.hasHome).toBe(true);
  });

  it('names the framed area after the base, because "My area" is false away from home', () => {
    renderPane(context({ origin: LAKES }));
    expect(MapStub.lastProps.heat.areaLabel).toBe('Around Keswick');
  });

  it('keeps the label undefined at home, so the map uses its own "My area"', () => {
    renderPane();
    expect(MapStub.lastProps.heat.areaLabel).toBeUndefined();
  });

  it('⚠️ OVERWRITES the map\'s drive figures with the base-measured matrix when away', () => {
    renderPane(context({ origin: LAKES, effectiveReachById: BASE_REACH }));
    expect(MapStub.lastProps.heat.driveOverrideById).toBe(BASE_REACH);
  });

  it('hands the map NO override at home, so the standalone Map tab and the home path keep their own fetch', () => {
    renderPane(context({ effectiveReachById: REACH }));
    expect(MapStub.lastProps.heat.driveOverrideById).toBeUndefined();
  });

  it('re-frames on an origin CHANGE, not merely on a first render with one set', () => {
    // The mutation this catches is dropping `origin` from the heat memo's dependency list: the
    // first render is correct either way, and the map keeps the previous frame forever after.
    const { rerender } = renderPane();
    expect(MapStub.lastProps.heat.areaSpots.map((s) => s.name)).toEqual(['Bamburgh', 'Wastwater']);

    briefingValue = context({ origin: LAKES });
    rerender(
      <WindowFirstMapPane
        locations={[]}
        dates={[TODAY]}
        selectedDate={TODAY}
        onSelectDate={vi.fn()}
      />,
    );

    expect(MapStub.lastProps.heat.areaSpots.map((s) => s.name)).toEqual(['Wastwater']);
  });
});

/**
 * The heat field for PAST windows (outcome-rating-plan.md §2 #3, O3c).
 *
 * <p><b>What breaks if these fail.</b> A past window is listed in `heat.windows` and given a point
 * set so the Map paints it; both must be built in the PANE (the briefing context's join is shared
 * with the Plan matrix, which must never learn a past window), from the forecast rows alone (the
 * scores endpoint serves the latest result, which may postdate the event), and flagged `past: true`
 * (or `buildMapEvents` reads every one as an unlabelled SERVED row).
 */
describe('WindowFirstMapPane — the heat field on a past window', () => {
  const T1 = '2026-08-10';
  const T2 = '2026-08-09';

  const loc = (id, name, days, extra = {}) => ({
    id,
    name,
    lat: 55 + id / 10,
    lon: -1.5,
    regionName: 'North East',
    locationType: ['LANDSCAPE'],
    forecastsByDate: new Map(Object.entries(days)),
    ...extra,
  });
  const sunsetRow = (rating, date) => ({ rating, solarEventTime: `${date}T19:50:00` });

  const past = () => MapStub.lastProps.heat.windows.filter((w) => w.past);

  it('lists a past window in heat.windows, flagged past, with a client max over its painted points', () => {
    renderPane(context(), {
      locations: [
        loc(1, 'Bamburgh', { [T1]: { sunset: sunsetRow(2, T1) } }),
        loc(2, 'Alnmouth', { [T1]: { sunset: sunsetRow(4, T1) } }),
      ],
    });

    expect(past()).toHaveLength(1);
    expect(past()[0]).toMatchObject({
      key: `${T1}:SUNSET`, date: T1, targetType: 'SUNSET', past: true, bestRating: 4, confidence: null,
    });
    // The served six keep their place and order ahead of it.
    expect(MapStub.lastProps.heat.windows.slice(0, 2).map((w) => w.key))
      .toEqual([`${TODAY}:SUNSET`, '2026-08-14:SUNRISE']);
    expect(MapStub.lastProps.heat.windows.slice(0, 2).some((w) => w.past)).toBe(false);
  });

  it('gives the past window its own point set, beside the provider\'s and without replacing them', () => {
    renderPane(context(), {
      locations: [
        loc(1, 'Bamburgh', { [T1]: { sunset: sunsetRow(2, T1) } }),
        loc(2, 'Alnmouth', { [T1]: { sunset: sunsetRow(4, T1) } }),
      ],
    });

    const sets = MapStub.lastProps.heat.pointsByKey;
    expect(sets.get(`${T1}:SUNSET`).map((p) => [p.name, p.r[0]]))
      .toEqual([['Bamburgh', 2], ['Alnmouth', 4]]);
    expect(sets.get(`${TODAY}:SUNSET`)).toBe(briefingValue.heatPointSets.get(`${TODAY}:SUNSET`));
    // The provider's own Map is untouched: the pane built a new one, it did not write into it.
    expect(briefingValue.heatPointSets.has(`${T1}:SUNSET`)).toBe(false);
  });

  it('reads the FORECAST row, never the scores endpoint\'s latest result for the slot', () => {
    // A rating that landed after the event: the latest result says 5, the forecast row (served as
    // of the event) says 2. The chips and the callout read the row, so the field must too.
    renderPane(context({
      scoreRows: [{
        locationId: 1, locationName: 'Bamburgh', date: T1, targetType: 'SUNSET', rating: 5,
      }],
    }), {
      locations: [loc(1, 'Bamburgh', { [T1]: { sunset: sunsetRow(2, T1) } })],
    });

    expect(past()[0].bestRating).toBe(2);
    expect(MapStub.lastProps.heat.pointsByKey.get(`${T1}:SUNSET`)[0].r[0]).toBe(2);
  });

  it('⚠️ takes the best over the PAINTED points: a wood\'s 5 does not make the key appear', () => {
    // Codex, #1185 round 8. The raw row maximum is 5; the field paints nothing for a canopy site,
    // so the window must read unscored and the key be withheld.
    renderPane(context(), {
      locations: [loc(1, 'Wood', { [T1]: { sunset: sunsetRow(5, T1) } }, { locationType: ['WOODLAND'] })],
    });

    expect(past()[0].bestRating).toBeNull();
    expect(MapStub.lastProps.heat.pointsByKey.get(`${T1}:SUNSET`)).toEqual([]);
  });

  it('reaches the oldest reachable day (T-4), built from forecast rows with no briefing score behind it', () => {
    const T4 = '2026-08-07';
    renderPane(context(), {
      locations: [loc(1, 'Bamburgh', { [T4]: { sunset: sunsetRow(3, T4) } })],
    });

    expect(past().map((w) => [w.key, w.bestRating])).toEqual([[`${T4}:SUNSET`, 3]]);
    expect(MapStub.lastProps.heat.pointsByKey.get(`${T4}:SUNSET`)).toHaveLength(1);
  });

  it('builds an entry for today\'s UNSERVED window too — the pane cannot follow the clock, MapView gates it', () => {
    // TODAY's sunrise has rows but no strip card. Whether it has elapsed is the app clock's call,
    // made per render in MapView (`heatWindow`'s guard), so the entry exists regardless.
    renderPane(context(), {
      locations: [loc(1, 'Bamburgh', { [TODAY]: { sunrise: { rating: 4, solarEventTime: `${TODAY}T05:40:00` } } })],
    });

    expect(past().map((w) => w.key)).toEqual([`${TODAY}:SUNRISE`]);
  });

  it('reads a row with no rating field at all as unrated, not as a star', () => {
    renderPane(context(), {
      locations: [loc(1, 'Bamburgh', { [T1]: { sunset: { solarEventTime: `${T1}T19:50:00` } } })],
    });

    expect(past()[0].bestRating).toBeNull();
  });

  it('reads null for a window whose places have a row but no rating', () => {
    renderPane(context(), {
      locations: [loc(1, 'Bamburgh', { [T1]: { sunset: { rating: null, solarEventTime: `${T1}T19:50:00` } } })],
    });

    expect(past()[0].bestRating).toBeNull();
  });

  it('resolves the inferred confidence cap into the kernel\'s haze, since a past window serves none', () => {
    renderPane(context(), {
      locations: [loc(1, 'Bamburgh', { [T1]: { sunset: sunsetRow(3, T1) } })],
    });

    // `MAX_INFERRED_TIER` is medium: 0.82, the figure the strip's own medium cards carry above.
    expect(past()[0].conf).toBe(0.82);
  });

  it('lists every past day and event the roster holds rows for, sunrise before sunset', () => {
    renderPane(context(), {
      locations: [loc(1, 'Bamburgh', {
        [T2]: { sunset: sunsetRow(3, T2) },
        [T1]: { sunrise: { rating: 4, solarEventTime: `${T1}T05:40:00` }, sunset: sunsetRow(1, T1) },
      })],
    });

    expect(past().map((w) => w.key)).toEqual([
      `${T2}:SUNSET`, `${T1}:SUNRISE`, `${T1}:SUNSET`,
    ]);
    expect(past().map((w) => w.bestRating)).toEqual([3, 4, 1]);
  });

  it('leaves a window the briefing still SERVES to the briefing — no second entry for the key', () => {
    // Tonight's sunset has rows, and a strip card: the card's entry and the provider's point set
    // are the ones to read, or two entries for one key could paint the wrong ratings.
    renderPane(context(), {
      locations: [loc(1, 'Bamburgh', { [TODAY]: { sunset: sunsetRow(1, TODAY) } })],
    });

    expect(past()).toEqual([]);
    expect(MapStub.lastProps.heat.windows.filter((w) => w.key === `${TODAY}:SUNSET`)).toHaveLength(1);
    expect(MapStub.lastProps.heat.pointsByKey).toBe(briefingValue.heatPointSets);
  });

  it('hands the provider\'s Map through untouched, identity and all, when there is no past window', () => {
    renderPane(context(), {
      locations: [loc(1, 'Bamburgh', { '2026-08-12': { sunset: sunsetRow(4, '2026-08-12') } })],
    });

    expect(past()).toEqual([]);
    expect(MapStub.lastProps.heat.pointsByKey).toBe(briefingValue.heatPointSets);
  });

  it('keeps heat.spots the briefing\'s own catalogue — the past join is a separate one', () => {
    // The past windows' scores live in a past-only spot set the pane builds and throws away after
    // reading its points; `heat.spots` (region labels, the dark-sky pool) stays the provider's.
    renderPane(context(), {
      locations: [loc(1, 'Bamburgh', { [T1]: { sunset: sunsetRow(4, T1) } })],
    });

    expect(MapStub.lastProps.heat.spots).toBe(SPOTS);
  });

  it('keeps ONE heat identity across a re-render that changes nothing', () => {
    const locations = [loc(1, 'Bamburgh', { [T1]: { sunset: sunsetRow(4, T1) } })];
    const { rerender } = renderPane(context(), { locations });
    const first = MapStub.lastProps.heat;
    rerender(
      <WindowFirstMapPane
        locations={locations}
        dates={[TODAY]}
        selectedDate={TODAY}
        onSelectDate={vi.fn()}
      />,
    );
    expect(MapStub.renders).toBeGreaterThan(1);
    expect(MapStub.lastProps.heat).toBe(first);
  });
});

describe('WindowFirstMapPane — the bigSeas prop (big-seas plan F1)', () => {
  const DAYS = [{ date: '2026-08-12', windowStart: '2026-08-12T10:00:00', windowEnd: '2026-08-12T12:00:00' }];

  it('hands the map the briefing\'s served bigSeas, by identity', () => {
    renderPane(context({ briefing: { days: [], bigSeas: DAYS } }));
    expect(MapStub.lastProps.bigSeas).toBe(DAYS);
  });

  it('hands undefined when the briefing serves none, so MapView\'s stable empty default applies', () => {
    renderPane(context({ briefing: { days: [] } }));
    expect(MapStub.lastProps.bigSeas).toBeUndefined();
  });
});

