/**
 * The Map tab's S4 status buttons and sub-regions doors for a body the server shaped by a Lite reader's picks
 * (sub-regions-plan §11 S5a-3, decisions 9 and 11; handoff tasks 4 and 7): the region panel's status line, change
 * button and door (no picks) or every-sub-region door (picks), the legend's zero-picks button on desktop and on the
 * phone, and the callout's word for a place outside the picks. Pro, Admin and flag-off bodies are asserted to draw
 * and request none of it.
 *
 * Harness as `MapViewShapedChrome.test.jsx`: the two layers are probes, the role is fixed per test and never
 * consulted by the code under test.
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
// The callout is a prop probe: its own drawing is `MapCalloutOutsidePicks.test.jsx`, this file pins the wiring.
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: (props) => <div data-testid="callout-probe" data-outside={String(props.outsidePicks)} />,
}));

import MapView from '../components/MapView.jsx';
import { buildRegionVerdictIndex, buildShapedLeadIndex } from '../utils/mapVerdict.js';
import { regionPickSummaries } from '../utils/pickGeography.js';
import { SubRegionStatusProvider } from '../context/SubRegionStatusContext.jsx';
import { FIRST_CHOICE, LOCKED_FULL, OPEN } from './subRegionFixtures.js';

vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));
import { getSubRegions } from '../api/subRegionApi.js';

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

const openSettings = vi.fn();
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
      <SubRegionStatusProvider enabled={Boolean(props.heat?.shaped)} onOpen={openSettings}>
        <MapView
          locations={locations}
          date={TODAY}
          autoEventType={null}
          regionVerdictIndex={buildRegionVerdictIndex(days)}
          shapedLeadIndex={buildShapedLeadIndex(days)}
          {...props}
        />
      </SubRegionStatusProvider>,
    );
  });
  return { ...result, locations };
}

const openPanel = () => {
  fireEvent.click(screen.getByTestId('wf-win-pill'));
  fireEvent.click(screen.getByTestId('wf-win-more'));
};
const rowOf = (name) => screen.getAllByTestId('wf-win-panel-row').find((r) => r.dataset.region === name);

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
  openSettings.mockClear();
  getSubRegions.mockReset();
  getSubRegions.mockResolvedValue(OPEN);
  nonce += 1;
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});


/** The S4 body with options naming the harness's own two regions, so the region door has examples to quote. */
const LAKES_STATE = {
  ...OPEN,
  options: [
    {
      regionId: 7,
      regionName: 'The Lakes',
      subRegions: [
        { areaId: 70, name: 'Wasdale', locationCount: 1, exampleLocations: ['Wastwater', 'Nether Wasdale'] },
        { areaId: 71, name: 'Langdales', locationCount: 9, exampleLocations: ['Blea Tarn'] },
      ],
    },
  ],
};

/** Every door the pane currently draws, by the shared door root's own test id. */
const doors = () => screen.queryAllByTestId(/-upsell-door$/);
const openLakesPanel = () => {
  openPanel();
  fireEvent.click(rowOf('The Lakes'));
};

describe('MapView, S5a-3: the region panel with no pick in the region', () => {
  it('prints S4\'s status line, the S4 button in the actions, and one region door', async () => {
    getSubRegions.mockResolvedValue(LAKES_STATE);
    await renderMap({ heat: shapedHeat() });
    openLakesPanel();

    const block = screen.getByTestId('wf-reg-panel-outside-block');
    expect(await within(block).findByTestId('wf-reg-panel-status-line'))
      .toHaveTextContent('You can change your sub-regions now. Removing one starts a 30-day lock.');
    expect(within(block).getByTestId('wf-subregions-region-upsell-trigger')).toHaveTextContent('Ratings across the Lakes');
    const button = screen.getByTestId('wf-reg-panel-subregion-action');
    expect(button).toHaveTextContent('Change your sub-regions');
    expect(button.closest('.wf-reg-actions')).not.toBeNull();
    expect(doors()).toHaveLength(1);
    expect(screen.getByTestId('wf-reg-panel').textContent).not.toMatch(FORBIDDEN);
  });

  it('the door counts come from the served roster and the example places from the S4 options', async () => {
    getSubRegions.mockResolvedValue(LAKES_STATE);
    await renderMap({ heat: shapedHeat() });
    openLakesPanel();
    await screen.findByTestId('wf-reg-panel-status-line');

    fireEvent.click(screen.getByTestId('wf-subregions-region-upsell-trigger'));

    expect(screen.getByTestId('wf-subregions-region-upsell-body')).toHaveTextContent(
      'Pro rates all 1 place across the one sub-region of the Lakes, Wastwater and Blea Tarn among them, for every sunrise and sunset.',
    );
  });

  it('presses through to Settings', async () => {
    getSubRegions.mockResolvedValue(LAKES_STATE);
    await renderMap({ heat: shapedHeat() });
    openLakesPanel();

    fireEvent.click(await screen.findByTestId('wf-reg-panel-subregion-action'));

    expect(openSettings).toHaveBeenCalledTimes(1);
  });

  it('a reader locked at the limit gets the date and no button', async () => {
    getSubRegions.mockResolvedValue(LOCKED_FULL);
    await renderMap({ heat: shapedHeat() });
    openLakesPanel();

    expect(await screen.findByTestId('wf-reg-panel-status-line'))
      .toHaveTextContent('You can next change your sub-regions on 25 November.');
    expect(screen.queryByTestId('wf-reg-panel-subregion-action')).toBeNull();
  });

  it('draws the door while the status is still loading, and no button yet', async () => {
    getSubRegions.mockReturnValue(new Promise(() => {}));
    await renderMap({ heat: shapedHeat() });
    openLakesPanel();

    expect(screen.getByTestId('wf-subregions-region-upsell-trigger')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-reg-panel-subregion-action')).toBeNull();
    expect(screen.queryByTestId('wf-reg-panel-status-line')).toBeNull();
  });

  it('one Escape closes the door and leaves the panel; the next goes back a level', async () => {
    getSubRegions.mockResolvedValue(LAKES_STATE);
    await renderMap({ heat: shapedHeat() });
    openLakesPanel();
    fireEvent.click(screen.getByTestId('wf-subregions-region-upsell-trigger'));
    expect(screen.getByTestId('wf-subregions-region-upsell-popover')).toBeInTheDocument();

    fireEvent.keyDown(screen.getByTestId('wf-subregions-region-upsell-heading'), { key: 'Escape' });

    expect(screen.queryByTestId('wf-subregions-region-upsell-popover')).toBeNull();
    expect(screen.getByTestId('wf-reg-panel')).toBeInTheDocument();
    fireEvent.keyDown(screen.getByTestId('wf-reg-panel'), { key: 'Escape' });
    expect(screen.queryByTestId('wf-reg-panel')).toBeNull();
    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
  });

  it('has no link and no "upgrade" word anywhere in the open panel', async () => {
    getSubRegions.mockResolvedValue(LAKES_STATE);
    await renderMap({ heat: shapedHeat() });
    openLakesPanel();
    fireEvent.click(screen.getByTestId('wf-subregions-region-upsell-trigger'));

    const panel = screen.getByTestId('wf-reg-panel');
    expect(panel.querySelector('a')).toBeNull();
    expect(panel.textContent).not.toMatch(/upgrade/i);
    expect(within(screen.getByTestId('wf-subregions-region-upsell-trigger')).getByText('Pro')).toBeInTheDocument();
  });
});

describe('MapView, S5a-3: the region panel where the reader holds picks', () => {
  it('has the every-sub-region door under the actions, with served counts and the applied pick count', async () => {
    await renderMap({ heat: shapedHeat() });
    openPanel();
    fireEvent.click(rowOf('North East'));

    const foot = screen.getByTestId('wf-reg-panel-foot');
    expect(foot.previousElementSibling).toHaveClass('wf-reg-actions');
    expect(within(foot).getByTestId('wf-subregions-all-upsell-trigger')).toHaveTextContent('Every sub-region');
    fireEvent.click(within(foot).getByTestId('wf-subregions-all-upsell-trigger'));
    expect(screen.getByTestId('wf-subregions-all-upsell-body')).toHaveTextContent(
      `Pro rates all ${SPOTS.length} places across the 4 sub-regions. You see ratings for 2 places in your two sub-regions.`,
    );
    expect(doors()).toHaveLength(1);
    expect(screen.queryByTestId('wf-reg-panel-subregion-action')).toBeNull();
  });

  it('says "your one sub-region" for a reader with one pick', async () => {
    await renderMap({ heat: shapedHeat({ allowed: [1], rated: [1] }), locations: makeLocations({ gated: [2, 3, 5, 6] }) });
    openPanel();
    fireEvent.click(rowOf('North East'));
    fireEvent.click(screen.getByTestId('wf-subregions-all-upsell-trigger'));

    expect(screen.getByTestId('wf-subregions-all-upsell-body'))
      .toHaveTextContent('You see ratings for 1 place in your one sub-region.');
  });
});

describe('MapView, S5a-3: the zero-picks legend line', () => {
  const zero = () => ({
    heat: shapedHeat({ allowed: [], rated: [] }), locations: makeLocations({ gated: ALL_IDS }),
  });

  it('puts the Choose button under the note in the desktop legend, and no door', async () => {
    getSubRegions.mockResolvedValue(FIRST_CHOICE);
    await renderMap(zero());
    fireEvent.click(screen.getByTestId('wf-legend-chip'));

    expect(screen.getByTestId('wf-legend-picks-note')).toHaveTextContent('Choose your sub-regions in Settings to see ratings.');
    const button = await screen.findByTestId('wf-legend-picks-action');
    expect(button).toHaveTextContent('Choose your sub-regions');
    expect(doors()).toHaveLength(0);
    fireEvent.click(button);
    expect(openSettings).toHaveBeenCalledTimes(1);
  });

  it('puts the same button in the phone peek sheet\'s key', async () => {
    mockIsMobile = true;
    getSubRegions.mockResolvedValue(FIRST_CHOICE);
    await renderMap(zero());
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));

    const button = await screen.findByTestId('wf-map-peek-picks-action');
    expect(button).toHaveTextContent('Choose your sub-regions');
    expect(doors()).toHaveLength(0);
    fireEvent.click(button);
    expect(openSettings).toHaveBeenCalledTimes(1);
  });

  it('draws no button for a reader who holds picks', async () => {
    await renderMap({ heat: shapedHeat() });
    fireEvent.click(screen.getByTestId('wf-legend-chip'));

    expect(screen.queryByTestId('wf-legend-picks-action')).toBeNull();
  });
});

describe('MapView, S5a-3: a place outside the picks in the callout', () => {
  it('hands the callout outsidePicks for a place the reader does not hold, and not for one they do', async () => {
    const { locations } = await renderMap({ heat: shapedHeat(), scoresKnown: true, ratingKnown: true });

    await act(async () => { labelProps.last.onSelect(locations.find((l) => l.id === 3).name); });
    expect(screen.getByTestId('callout-probe')).toHaveAttribute('data-outside', 'true');

    await act(async () => { labelProps.last.onSelect(locations.find((l) => l.id === 2).name); });
    expect(screen.getByTestId('callout-probe')).toHaveAttribute('data-outside', 'false');
  });

  it('never hands it to a Pro or Admin reader, whatever the place', async () => {
    mockRole = 'PRO_USER';
    const { locations } = await renderMap({ heat: unshapedHeat(), locations: makeLocations({ gated: [] }) });

    await act(async () => { labelProps.last.onSelect(locations.find((l) => l.id === 3).name); });

    expect(screen.getByTestId('callout-probe')).toHaveAttribute('data-outside', 'false');
  });
});

describe('MapView, S5a-3: Pro, Admin and flag-off bodies are byte-identical', () => {
  it.each(['PRO_USER', 'ADMIN'])('%s: no status read, no door, no button, anywhere', async (role) => {
    mockRole = role;
    await renderMap({ heat: unshapedHeat(), locations: makeLocations({ gated: [] }) });
    openPanel();
    fireEvent.click(rowOf('North East'));
    expect(screen.getByTestId('wf-reg-panel').textContent).not.toMatch(/sub-region/i);
    fireEvent.click(screen.getByTestId('wf-legend-chip'));

    expect(getSubRegions).not.toHaveBeenCalled();
    expect(doors()).toHaveLength(0);
    expect(screen.queryByTestId('wf-reg-panel-foot')).toBeNull();
    expect(screen.queryByTestId('wf-reg-panel-subregion-action')).toBeNull();
    expect(screen.queryByTestId('wf-legend-picks-action')).toBeNull();
  });
});
