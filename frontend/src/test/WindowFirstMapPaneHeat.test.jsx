import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render } from '@testing-library/react';

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
let briefingValue = null;
vi.mock('../context/WindowFirstBriefingContext.jsx', () => ({
  useWindowFirstBriefing: () => briefingValue,
}));

import WindowFirstMapPane from '../components/WindowFirstMapPane.jsx';

const TODAY = '2026-08-11';

const SPOTS = [
  { id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, regionName: 'North East', rid: 'North East', bortleClass: 4, scores: [5, 4] },
  { id: 2, name: 'Wastwater', lat: 54.44, lng: -3.30, regionName: 'The Lakes', rid: 'The Lakes', bortleClass: 2, scores: [3, null] },
  { id: 3, name: 'Kelso', lat: 56.20, lng: -2.43, regionName: 'The Borders', rid: 'The Borders', bortleClass: 5, scores: [2, 2] },
];

/** North East 40 min, The Lakes 120 min, The Borders 400 min — the last is beyond GLANCE (180). */
const REACH = new Map([[1, { driveMinutes: 40 }], [2, { driveMinutes: 120 }], [3, { driveMinutes: 400 }]]);

const STRIP_CARDS = [
  { key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '20:41', bestRating: 4, confidence: 'high', away: false },
  { key: '2026-08-14:SUNRISE', date: '2026-08-14', targetType: 'SUNRISE', label: 'Fri sunrise', time: '05:31', bestRating: null, confidence: null, away: true },
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
