/**
 * The Map tab's chrome and panels for a body the server shaped by a Lite reader's sub-region picks
 * (sub-regions-plan S5a-2): the region labels' picks line, the scope segment (camera only), the window
 * panel's order and Pro pills, the region panel's two states, the dropdown rows that never print a dash for
 * a place outside the picks, and the key on the phone. The same props from a Pro, Admin or flag-off reader
 * are asserted to draw none of it.
 *
 * The two layers (`MapHeatLayer`, `MapLabels`) are probes, as in `MapViewShaped.test.jsx`. The role is fixed
 * per test and never consulted by the code under test: "shaped" is `heat.shaped`, the served marker.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, render, screen, fireEvent, within,
} from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

let mapEventHandlers = [];
const mockMapInstance = { getZoom: () => 9 };
const fitBounds = vi.fn();
vi.mock('react-leaflet', () => ({
  MapContainer: (props) => <div data-testid="map-container">{props.children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: (handlers) => {
    mapEventHandlers.push(handlers);
    return mockMapInstance;
  },
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    fitBounds: (...args) => fitBounds(...args),
    getZoom: () => 9,
    flyTo: () => {},
    panInside: () => {},
  }),
}));

const heatLayerProps = { last: null };
vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: (props) => {
    heatLayerProps.last = props;
    return <div data-testid="map-heat-layer" />;
  },
}));
const labelProps = { last: null };
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => {
    labelProps.last = props;
    return <div data-testid="map-labels-stub" />;
  },
}));

let mockRole = 'LITE_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null, source: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn().mockResolvedValue({}) }));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import { buildRegionVerdictIndex, buildShapedLeadIndex } from '../utils/mapVerdict.js';
import { regionPickSummaries } from '../utils/pickGeography.js';

const TODAY = '2026-01-15';
const KEY = `${TODAY}:SUNSET`;

/** Forbidden for a place outside the picks, anywhere on these surfaces. */
const FORBIDDEN = /not rated|not scored|unrated|\b0 rated\b|\b0 of 0\b|—/i;

const SUB = {
  bam: { subRegionId: 11, subRegionName: 'Bamburgh to Howick' },
  tyne: { subRegionId: 12, subRegionName: 'Druridge Bay to Tynemouth' },
  aln: { subRegionId: 13, subRegionName: 'Alnwick & the Coquet Coast' },
  wasd: { subRegionId: 20, subRegionName: 'Wasdale' },
};
const SPOTS = [
  { id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East', regionName: 'North East', ...SUB.bam },
  { id: 2, name: 'Tynemouth', lat: 55.02, lng: -1.42, rid: 'North East', regionName: 'North East', ...SUB.tyne },
  { id: 3, name: 'Wastwater', lat: 54.44, lng: -3.3, rid: 'The Lakes', regionName: 'The Lakes', ...SUB.wasd },
  { id: 5, name: 'Alnmouth', lat: 55.39, lng: -1.61, rid: 'North East', regionName: 'North East', ...SUB.aln },
  { id: 6, name: 'Coquet', lat: 55.33, lng: -1.53, rid: 'North East', regionName: 'North East', ...SUB.aln },
].map((s) => ({ ...s, skySubject: true, bortleClass: 4 }));
const ALL_IDS = SPOTS.map((s) => s.id);

const ALLOWED_BOUNDS = [[54.9, -1.9], [55.7, -1.3]];
const CATALOGUE_BOUNDS = [[54.3, -3.4], [56.4, -1.3]];

const pointOf = (spot, score) => ({
  id: spot.id, name: spot.name, lat: spot.lat, lng: spot.lng, rid: spot.rid, r: [score],
});

/** The shaped body's heat prop, built the way `WindowFirstMapPane` builds it: from the served ids. */
function shapedHeat({ allowed = [1, 2], rated = [1] } = {}) {
  const gated = ALL_IDS.filter((id) => !allowed.includes(id));
  const states = new Map(SPOTS.map((s) => [
    `#${s.id}`, gated.includes(s.id) ? 'gated' : (rated.includes(s.id) ? 'rated' : 'unrated'),
  ]));
  return {
    enabled: true,
    hasHome: false,
    shaped: true,
    allowedCount: allowed.length,
    regionPicks: regionPickSummaries(SPOTS, allowed),
    allowedBounds: allowed.length ? ALLOWED_BOUNDS : null,
    spots: SPOTS,
    areaSpots: SPOTS,
    pointsByKey: new Map([
      [KEY, SPOTS.filter((s) => rated.includes(s.id)).map((s) => pointOf(s, 4))],
      [`${TODAY}:SUNRISE`, []],
    ]),
    windows: [
      { key: `${TODAY}:SUNRISE`, date: TODAY, targetType: 'SUNRISE', label: 'This morning sunrise', time: '08:24', bestRating: null, conf: 1 },
      { key: KEY, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: null, conf: 1 },
    ],
    windowStates: new Map([[KEY, {
      unrated: SPOTS.filter((s) => states.get(`#${s.id}`) === 'unrated').map((s) => ({
        id: s.id, name: s.name, lat: s.lat, lng: s.lng, rid: s.rid,
      })),
      stood: [],
      states,
    }]]),
    areaBounds: CATALOGUE_BOUNDS,
    catalogueBounds: CATALOGUE_BOUNDS,
  };
}

/** The same roster as a Pro or Admin reader gets it: not shaped, nothing gated, no picks facts at all. */
function unshapedHeat() {
  const states = new Map(SPOTS.map((s) => [`#${s.id}`, s.id === 1 ? 'rated' : 'unrated']));
  return {
    ...shapedHeat({ allowed: ALL_IDS }),
    shaped: false,
    allowedCount: null,
    regionPicks: null,
    allowedBounds: null,
    windows: shapedHeat().windows.map((w) => (w.key === KEY ? { ...w, bestRating: 4 } : w)),
    windowStates: new Map([[KEY, { unrated: [], stood: [], states }]]),
  };
}

let nonce = 0;
const makeLocations = ({ gated = [3, 5, 6] } = {}) => SPOTS.map((s) => ({
  id: s.id,
  name: `${s.name}-${nonce}`,
  lat: s.lat,
  lon: s.lng,
  regionName: s.rid,
  bortleClass: 4,
  locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, {
    sunset: gated.includes(s.id)
      ? { rating: null, gated: true, solarEventTime: `${TODAY}T16:12:00` }
      : { rating: s.id === 1 ? 4 : null, solarEventTime: `${TODAY}T16:12:00` },
    sunrise: { rating: null, solarEventTime: `${TODAY}T08:24:00` },
  }]]),
}));

/**
 * Served region records with no meanRating (the shaper strips it). The lead is by tier alone. `leadOutside`
 * makes The Lakes (no picks) the lead, with North East one tier down.
 */
const daysFor = ({ leadOutside = false } = {}) => [{
  date: TODAY,
  eventSummaries: [{
    targetType: 'SUNSET',
    shapedLeadRegion: leadOutside ? 'The Lakes' : 'North East',
    regions: [
      {
        regionName: 'North East',
        displayVerdict: leadOutside ? 'MAYBE' : 'WORTH_IT',
        scoredLocationCount: 3,
        picksBestRating: 4,
        slots: [{ canopy: false, gated: true }],
      },
      {
        regionName: 'The Lakes',
        displayVerdict: leadOutside ? 'WORTH_IT' : 'MAYBE',
        scoredLocationCount: 3,
        slots: [{ canopy: false, gated: true }],
      },
    ],
  }],
}];

async function renderMap({ leadOutside = false, ...props } = {}) {
  let result;
  const days = daysFor({ leadOutside });
  const locations = props.locations ?? makeLocations();
  await act(async () => {
    result = render(
      <MapView
        locations={locations}
        date={TODAY}
        autoEventType={null}
        regionVerdictIndex={buildRegionVerdictIndex(days)}
        shapedLeadIndex={buildShapedLeadIndex(days)}
        {...props}
      />,
    );
  });
  return { ...result, locations };
}

const openPanel = () => {
  fireEvent.click(screen.getByTestId('wf-win-pill'));
  fireEvent.click(screen.getByTestId('wf-win-more'));
};
const rowOf = (name) => screen.getAllByTestId('wf-win-panel-row').find((r) => r.dataset.region === name);
const openFilters = () => fireEvent.click(screen.getByTestId('wf-filters-chip'));

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
  localStorage.clear();
  heatLayerProps.last = null;
  labelProps.last = null;
  mapEventHandlers = [];
  mockIsMobile = false;
  mockRole = 'LITE_USER';
  fitBounds.mockClear();
  nonce += 1;
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe('MapView, shaped chrome — the region labels', () => {
  it('hands the labels each region\'s count of picked sub-regions, from the served facts', async () => {
    await renderMap({ heat: shapedHeat() });
    await screen.findByTestId('map-labels-stub');

    expect(labelProps.last.regionPicks).toBeInstanceOf(Map);
    expect(labelProps.last.regionPicks.get('North East')).toBe(2);
    expect(labelProps.last.regionPicks.get('The Lakes')).toBe(0);
  });

  it('counts a reader with no pick as none in every region', async () => {
    await renderMap({ heat: shapedHeat({ allowed: [], rated: [] }), locations: makeLocations({ gated: ALL_IDS }) });
    await screen.findByTestId('map-labels-stub');

    expect([...labelProps.last.regionPicks.values()].every((n) => n === 0)).toBe(true);
  });

  it('hands nothing for a body that is not shaped, whatever the role says', async () => {
    for (const role of ['LITE_USER', 'PRO_USER', 'ADMIN', undefined]) {
      mockRole = role;
      labelProps.last = null;
      const view = await renderMap({ heat: unshapedHeat(), locations: makeLocations({ gated: [] }) });
      await screen.findByTestId('map-labels-stub');
      expect(labelProps.last.regionPicks).toBeNull();
      view.unmount();
    }
  });
});

describe('MapView, shaped chrome — the scope segment moves the camera and nothing else', () => {
  it('shows Your sub-regions | Everywhere for a shaped body with a pick, whatever the role', async () => {
    for (const role of ['LITE_USER', 'PRO_USER']) {
      mockRole = role;
      const view = await renderMap({ heat: shapedHeat() });
      openFilters();
      expect(screen.getByTestId('wf-filters-scope-picks')).toBeInTheDocument();
      expect(screen.getByTestId('wf-filters-scope-everywhere')).toBeInTheDocument();
      view.unmount();
    }
  });

  it('is hidden with no pick: nothing to fit to', async () => {
    await renderMap({ heat: shapedHeat({ allowed: [], rated: [] }), locations: makeLocations({ gated: ALL_IDS }) });
    openFilters();

    expect(screen.queryByTestId('wf-filters-picks-scope')).toBeNull();
  });

  it('is absent for a body that is not shaped, which keeps its own scope control', async () => {
    mockRole = 'PRO_USER';
    await renderMap({ heat: { ...unshapedHeat(), hasHome: true }, locations: makeLocations({ gated: [] }) });
    openFilters();

    expect(screen.queryByTestId('wf-filters-picks-scope')).toBeNull();
    expect(screen.getByTestId('wf-filters-scope-home')).toBeInTheDocument();
  });

  it('opens with neither pressed, so the camera is where it always was, and fits the allowed places, then the roster, on press', async () => {
    await renderMap({ heat: shapedHeat() });
    openFilters();
    expect(screen.getByTestId('wf-filters-scope-everywhere')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('wf-filters-scope-picks')).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(screen.getByTestId('wf-filters-scope-picks'));
    expect(fitBounds).toHaveBeenLastCalledWith(ALLOWED_BOUNDS, expect.any(Object));
    expect(screen.getByTestId('wf-filters-scope-picks')).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(screen.getByTestId('wf-filters-scope-everywhere'));
    expect(fitBounds).toHaveBeenLastCalledWith(CATALOGUE_BOUNDS, expect.any(Object));
  });

  it('changes no count and no pool: the field, the footer and the labels read the same either way', async () => {
    await renderMap({ heat: shapedHeat() });
    const footerBefore = screen.getByTestId('wf-map-counts-footer').textContent;
    const pointsBefore = heatLayerProps.last.points.map((p) => p.id);
    const outsideBefore = heatLayerProps.last.outside.map((p) => p.id).sort();
    const spotsBefore = labelProps.last.spots.map((s) => s.name).sort();

    openFilters();
    fireEvent.click(screen.getByTestId('wf-filters-scope-picks'));

    expect(screen.getByTestId('wf-map-counts-footer').textContent).toBe(footerBefore);
    expect(heatLayerProps.last.points.map((p) => p.id)).toEqual(pointsBefore);
    expect(heatLayerProps.last.outside.map((p) => p.id).sort()).toEqual(outsideBefore);
    expect(labelProps.last.spots.map((s) => s.name).sort()).toEqual(spotsBefore);
  });
});

describe('MapView, shaped chrome — a reader with a home keeps their own scope row', () => {
  it('renders both segments; My area / Everywhere still toggles heatArea and the picks one never does', async () => {
    await renderMap({ heat: { ...shapedHeat(), hasHome: true, areaBounds: ALLOWED_BOUNDS } });
    openFilters();
    expect(screen.getByTestId('wf-filters-scope-home')).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(screen.getByTestId('wf-filters-scope-picks'));
    expect(screen.getByTestId('wf-filters-scope-home')).toHaveAttribute('aria-pressed', 'true');
    expect(fitBounds).toHaveBeenLastCalledWith(ALLOWED_BOUNDS, expect.any(Object));

    fireEvent.click(screen.getByTestId('wf-filters-scope-all'));
    expect(screen.getByTestId('wf-filters-scope-all')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('wf-filters-scope-picks')).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(screen.getByTestId('wf-filters-scope-picks'));
    expect(screen.getByTestId('wf-filters-scope-all')).toHaveAttribute('aria-pressed', 'true');
  });

  it('shows the picks segment only when the served allowed places can be framed', async () => {
    await renderMap({ heat: { ...shapedHeat(), allowedBounds: null } });
    openFilters();

    expect(screen.queryByTestId('wf-filters-picks-scope')).toBeNull();
  });
});

describe('MapView, shaped chrome — the window panel', () => {
  it('ranks regions with picks first, then verdict tier, then name, and gives the others a Pro pill', async () => {
    await renderMap({ heat: shapedHeat() });
    openPanel();

    const rows = screen.getAllByTestId('wf-win-panel-row');
    expect(rows.map((r) => r.dataset.region)).toEqual(['North East', 'The Lakes']);

    const ne = within(rowOf('North East'));
    expect(ne.getByTestId('wf-win-panel-stat').textContent).toMatch(/1 of 2 at 4/);
    expect(ne.getByTestId('wf-win-panel-more-outside')).toHaveTextContent('· 2 more outside');
    expect(ne.getByTestId('wf-win-panel-best').textContent).toContain('4');

    const lakes = within(rowOf('The Lakes'));
    expect(lakes.getByTestId('wf-win-panel-stat')).toHaveTextContent('Outside your sub-regions · 1 place');
    expect(lakes.getByTestId('wf-win-panel-best').textContent).toBe('Pro');
    expect(lakes.getByTestId('wf-win-panel-best').querySelector('.wf-lens-pro')).not.toBeNull();
    expect(rowOf('The Lakes').textContent).not.toMatch(FORBIDDEN);
  });

  it('says where the best region lies, and what is best in the reader\'s sub-regions, when the lead is outside them', async () => {
    await renderMap({ heat: shapedHeat(), leadOutside: true });
    openPanel();

    expect(screen.getByTestId('wf-win-panel-note')).toHaveTextContent(
      'Best in the Lakes, outside your sub-regions. In your sub-regions, North East is Maybe.',
    );
    // The lead outside the picks still ranks below the region the reader holds.
    expect(screen.getAllByTestId('wf-win-panel-row').map((r) => r.dataset.region)).toEqual(['North East', 'The Lakes']);
  });

  it('draws the one-line order note when the lead region holds a pick', async () => {
    await renderMap({ heat: shapedHeat() });
    openPanel();

    expect(screen.getByTestId('wf-win-panel-note')).toHaveTextContent('Regions with places in your sub-regions come first');
    expect(screen.getByTestId('wf-win-panel-note').textContent).not.toMatch(/average/);
  });

  it('draws no note, and every row Outside your sub-regions, for a reader with no pick', async () => {
    await renderMap({
      heat: shapedHeat({ allowed: [], rated: [] }),
      locations: makeLocations({ gated: ALL_IDS }),
      leadOutside: true,
    });
    openPanel();

    expect(screen.queryByTestId('wf-win-panel-note')).toBeNull();
    screen.getAllByTestId('wf-win-panel-stat').forEach((stat) => {
      expect(stat.textContent).toMatch(/^Outside your sub-regions · \d+ places?$/);
    });
    screen.getAllByTestId('wf-win-panel-best').forEach((best) => expect(best.textContent).toBe('Pro'));
  });

  it('opens the region panel from a region with no pick, still a button', async () => {
    await renderMap({ heat: shapedHeat() });
    openPanel();
    const row = rowOf('The Lakes');

    expect(row.tagName).toBe('BUTTON');
    fireEvent.click(row);
    expect(screen.getByTestId('wf-reg-panel')).toBeInTheDocument();
  });

  it('is the old panel, with the old note, for a Pro or Admin reader', async () => {
    for (const role of ['PRO_USER', 'ADMIN']) {
      mockRole = role;
      const view = await renderMap({ heat: unshapedHeat(), locations: makeLocations({ gated: [] }) });
      openPanel();
      const panel = screen.getByTestId('wf-win-panel');
      expect(panel.textContent).not.toMatch(/outside|more outside|Pro\b/i);
      expect(screen.getByTestId('wf-win-panel-note').textContent).toMatch(/average|choice between them/);
      view.unmount();
    }
  });
});

describe('MapView, shaped chrome — the region panel', () => {
  it('for a region with no pick: none picked, the sub-regions with counts, the grey-wash line, zoom only', async () => {
    await renderMap({ heat: shapedHeat() });
    openPanel();
    fireEvent.click(rowOf('The Lakes'));

    expect(screen.getByTestId('wf-reg-panel-stats')).toHaveTextContent('1 sub-region · 1 place · none picked');
    expect(screen.getByTestId('wf-reg-panel-outside-block')).toHaveTextContent('Ratings here are outside your sub-regions.');
    expect(screen.getAllByTestId('wf-reg-panel-sub').map((s) => s.textContent)).toEqual(['Wasdale1 place']);
    expect(screen.getByTestId('wf-reg-panel-zoom')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-reg-panel-four-days')).toBeNull();
    expect(screen.getByTestId('wf-reg-panel').textContent).not.toMatch(FORBIDDEN);
  });

  it('for a region with picks: the picked sub-regions, then the outside places by name, each a button', async () => {
    await renderMap({ heat: shapedHeat() });
    openPanel();
    fireEvent.click(rowOf('North East'));

    expect(screen.getByTestId('wf-reg-panel-stats')).toHaveTextContent(
      'Bamburgh to Howick, Druridge Bay to Tynemouth · 2 places · 1 at 4',
    );
    expect(screen.getByTestId('wf-reg-panel-outside-head')).toHaveTextContent('OUTSIDE YOUR SUB-REGIONS');
    expect(screen.getByTestId('wf-reg-panel-outside-count')).toHaveTextContent('2 places in 1 sub-region');
    const outside = screen.getAllByTestId('wf-reg-panel-outside-row');
    expect(outside.map((r) => r.dataset.location)).toEqual(['Alnmouth', 'Coquet']);
    outside.forEach((r) => {
      expect(r.tagName).toBe('BUTTON');
      expect(r).toHaveTextContent('Alnwick & the Coquet Coast');
      expect(r.textContent).not.toMatch(/★|not scored|not rated/i);
    });
  });

  it('for a Pro or Admin reader is the old panel, with no outside block', async () => {
    mockRole = 'PRO_USER';
    await renderMap({ heat: unshapedHeat(), locations: makeLocations({ gated: [] }) });
    openPanel();
    fireEvent.click(rowOf('North East'));

    expect(screen.queryByTestId('wf-reg-panel-outside-head')).toBeNull();
    expect(screen.queryByTestId('wf-reg-panel-outside-block')).toBeNull();
    expect(screen.getByTestId('wf-reg-panel-stats').textContent).toMatch(/of \d+ at 4/);
  });
});

describe('MapView, shaped chrome — the window dropdown and the pill', () => {
  it('prints no dash and no "not scored" on a window row of a shaped body', async () => {
    await renderMap({ heat: shapedHeat() });
    fireEvent.click(screen.getByTestId('wf-win-pill'));

    // The sunset has a verdict (and no served best star): no dash. The sunrise has no verdict at all: it keeps one.
    const rows = screen.getAllByTestId('wf-win-row');
    const sunset = rows.find((r) => /sunset/i.test(r.textContent));
    expect(sunset.textContent).not.toMatch(FORBIDDEN);
    expect(sunset.querySelector('.wf-win-row-unscored')).toBeNull();
  });

  it('keeps the dash for a window with no served best star on a body that is not shaped', async () => {
    mockRole = 'PRO_USER';
    const heat = unshapedHeat();
    heat.windows = heat.windows.map((w) => ({ ...w, bestRating: null }));
    await renderMap({ heat, locations: makeLocations({ gated: [] }) });
    fireEvent.click(screen.getByTestId('wf-win-pill'));

    expect(screen.getByTestId('wf-win-menu').querySelector('.wf-win-row-unscored')).not.toBeNull();
  });

  it('puts no star on the pill and prints no thin count that includes an outside place', async () => {
    await renderMap({ heat: shapedHeat() });

    expect(screen.getByTestId('wf-win-pill').textContent).not.toContain('★');
    expect(screen.getByTestId('wf-win-pill').textContent).not.toMatch(FORBIDDEN);
  });
});

describe('MapView, shaped chrome — the key on the phone, where the desktop legend is not drawn', () => {
  const openLayers = () => fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));

  it('surfaces the grey swatch with its Pro pill, the dashed row and the note in the Layers section', async () => {
    mockIsMobile = true;
    await renderMap({ heat: shapedHeat() });
    openLayers();

    expect(screen.queryByTestId('wf-legend-chip')).toBeNull();
    const row = screen.getByTestId('wf-map-peek-picks');
    expect(within(row).getByTestId('wf-map-peek-picks-outside')).toHaveTextContent('outside your sub-regions');
    expect(within(row).getByTestId('wf-map-peek-picks-outside').querySelector('.wf-lens-pro')).toHaveTextContent('Pro');
    expect(within(row).getByTestId('wf-map-peek-picks-yours')).toHaveTextContent('your sub-regions');
    expect(screen.getByTestId('wf-map-peek-picks-note'))
      .toHaveTextContent(`Colour shows the 2 places in your sub-regions. Pro rates all ${SPOTS.length}.`);
  });

  it('draws the note and the grey row, but no dashed row, for a reader with no pick', async () => {
    mockIsMobile = true;
    await renderMap({ heat: shapedHeat({ allowed: [], rated: [] }), locations: makeLocations({ gated: ALL_IDS }) });
    openLayers();

    expect(screen.getByTestId('wf-map-peek-picks-outside')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-map-peek-picks-yours')).toBeNull();
    expect(screen.getByTestId('wf-map-peek-picks-note')).toHaveTextContent('Choose your sub-regions in Settings to see ratings.');
  });

  it('draws nothing new on the phone for a body that is not shaped', async () => {
    mockIsMobile = true;
    mockRole = 'PRO_USER';
    await renderMap({ heat: unshapedHeat(), locations: makeLocations({ gated: [] }) });
    openLayers();

    expect(screen.getByTestId('wf-map-peek-layers')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-map-peek-picks')).toBeNull();
    expect(screen.queryByTestId('wf-map-peek-picks-note')).toBeNull();
  });

  it('keeps the desktop legend rows off the desktop key for a body that is not shaped, and on for a shaped one', async () => {
    await renderMap({ heat: shapedHeat() });
    fireEvent.click(screen.getByTestId('wf-legend-chip'));

    expect(screen.getByTestId('wf-legend-picks')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-map-peek-picks')).toBeNull();
  });
});
