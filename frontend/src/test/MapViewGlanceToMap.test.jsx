/**
 * `MapView` and the four pieces the Map gained for glance-to-map-plan.md G1: the List door, "Best bet · why", the
 * warning expand and the Regions menu in best-first order. Leaflet and the layers that need a real Leaflet pane are
 * mocked (`mapViewMocks.jsx`); `BottomSheet` is the kit's passthrough.
 *
 * <p>What breaks if these fail: the List counts a different set of places than the Map draws; a window with no card
 * offers a dead List; the phone gets the desktop chip (or the desktop gets the phone row); the pick's reason shows for
 * a window the server gave no pick, or a warning's chip stops being a Met Office link; the Regions menu ranks a region
 * the drilldown ranks differently, or says "in reach" of a count that is not about reach.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, render, screen, fireEvent, within,
} from '@testing-library/react';
import { mapViewState } from './mapViewMocks.jsx';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: ({ location }) => <b data-testid="probe-callout-name">{location?.name}</b>,
}));

import MapView from '../components/MapView.jsx';
import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import { buildRegionVerdictIndex } from '../utils/mapVerdict.js';
import { buildPastSolarWindows } from '../utils/mapEvents.js';
import { windowKey } from '../utils/windowKeys.js';
import { response, warning, METOFFICE_URL } from './warningsFixture.js';

const TODAY = '2026-01-15';
const TOMORROW = '2026-01-16';

const SPOTS = [
  {
    id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East', regionName: 'North East', bortleClass: 4,
  },
  {
    id: 2, name: 'Tynemouth', lat: 55.02, lng: -1.42, rid: 'North East', regionName: 'North East', bortleClass: 6,
  },
  {
    id: 3, name: 'Wastwater', lat: 54.44, lng: -3.3, rid: 'The Lakes', regionName: 'The Lakes', bortleClass: 2,
  },
  {
    id: 4, name: 'Kelso', lat: 56.2, lng: -2.43, rid: 'The Borders', regionName: 'The Borders', bortleClass: 5,
  },
];
// "The Borders" is outside the reader's area.
const AREA_SPOTS = SPOTS.slice(0, 3);
const pointOf = (spot, score) => ({
  id: spot.id, name: spot.name, lat: spot.lat, lng: spot.lng, rid: spot.rid, r: [score],
});
const SUNSET = windowKey(TODAY, 'SUNSET');

function heatProp() {
  return {
    enabled: true,
    hasHome: true,
    spots: SPOTS,
    areaSpots: AREA_SPOTS,
    pointsByKey: new Map([
      [SUNSET, [pointOf(SPOTS[0], 4), pointOf(SPOTS[1], 2), pointOf(SPOTS[2], 5), pointOf(SPOTS[3], 4)]],
      [windowKey(TODAY, 'SUNRISE'), [pointOf(SPOTS[0], 3)]],
    ]),
    windows: [
      {
        key: windowKey(TODAY, 'SUNRISE'), date: TODAY, targetType: 'SUNRISE', label: 'Sunrise', time: '08:24', bestRating: 3, conf: 1,
      },
      {
        key: SUNSET, date: TODAY, targetType: 'SUNSET', label: 'Sunset', time: '16:12', bestRating: 5, conf: 1,
      },
    ],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [56.4, -1.3]],
    tideByWindow: new Map(),
  };
}

const region = (regionName, meanRating, bestRating, displayVerdict, extra = {}) => ({
  regionName, meanRating, bestRating, displayVerdict, sampleSufficient: true, slots: [{ canopy: false }], ...extra,
});
const DAYS = [{
  date: TODAY,
  eventSummaries: [{
    targetType: 'SUNSET',
    regions: [
      region('North East', 2.0, 3, 'STAND_DOWN'),
      region('The Lakes', 4.4, 5, 'WORTH_IT'),
      region('The Borders', 4.9, 5, 'WORTH_IT'),
    ],
  }],
}];

const rowOf = (rating) => ({
  sunset: { rating, solarEventTime: `${TODAY}T16:12:00` },
  sunrise: { rating, solarEventTime: `${TODAY}T08:24:00` },
});
const locations = () => SPOTS.map((s) => ({
  id: s.id,
  name: s.name,
  lat: s.lat,
  lon: s.lng,
  regionName: s.rid,
  bortleClass: s.bortleClass,
  locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, rowOf(4)]]),
}));

const spotOf = (s, rating) => ({
  key: String(s.id), locationId: s.id, locationName: s.name, regionName: s.rid, rating, driveMinutes: 30 + s.id,
});
const PICK = {
  kind: 'best',
  regionName: 'The Lakes',
  headline: 'The Lakes has the best sky tonight.',
  detail: 'Broken high cloud lights from below as the sun drops.',
  locationName: 'Wastwater',
};
const CARD = {
  key: SUNSET,
  date: TODAY,
  targetType: 'SUNSET',
  pick: PICK,
  allSpots: SPOTS.map((s) => spotOf(s, 4)),
};
const cardsByKey = (card = CARD) => new Map([[card.key, card]]);

const AMBER = warning({
  warningId: 'a',
  level: 'AMBER',
  weatherTypes: ['SNOW'],
  locationIds: [1],
  validFrom: `${TODAY}T00:00:00Z`,
  validTo: `${TODAY}T23:00:00Z`,
});

let onOpenList;
async function renderMap(props = {}) {
  let result;
  getWarnings.mockResolvedValue(response([AMBER]));
  const locs = locations();
  const element = (extra = {}) => (
    <WarningsProvider>
      <MapView
        locations={locs}
        date={TODAY}
        forecastDates={[TODAY, TOMORROW]}
        autoEventType="SUNSET"
        heat={heatProp()}
        regionVerdictIndex={buildRegionVerdictIndex(DAYS)}
        windowCardsByKey={cardsByKey()}
        onOpenList={onOpenList}
        {...props}
        {...extra}
      />
    </WarningsProvider>
  );
  await act(async () => { result = render(element()); });
  return { ...result, again: (extra) => act(async () => { result.rerender(element(extra)); }) };
}

beforeEach(() => {
  localStorage.clear();
  mapViewState.role = 'PRO_USER';
  mapViewState.isMobile = false;
  onOpenList = vi.fn(() => true);
  getWarnings.mockReset();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
});
afterEach(() => {
  document.querySelectorAll('[data-test-foreign]').forEach((n) => n.remove());
  vi.useRealTimers();
  localStorage.clear();
});

describe('the Map List', () => {
  it('draws no List control with no shell to open it', async () => {
    await renderMap({ onOpenList: null });
    expect(screen.queryByTestId('map-list-button')).toBeNull();
  });

  it('sits beside Regions on desktop and opens the list for the window on screen', async () => {
    await renderMap();
    const cluster = screen.getByTestId('wf-map-chrome-tr');
    const button = within(cluster).getByTestId('map-list-button');
    expect(button).toHaveAttribute('aria-haspopup', 'dialog');
    expect(button).not.toHaveAttribute('aria-disabled');
    fireEvent.click(button);
    expect(onOpenList).toHaveBeenCalledTimes(1);
    const request = onOpenList.mock.calls[0][0];
    expect(request.key).toBe(SUNSET);
    // The Map's own floor (3★+ by default) and tier (Any with nothing measured), as the sheet's ids.
    expect(request.floorId).toBe('3');
    expect(request.tierId).toBe('any');
  });

  it('hands over exactly the places the Map holds in scope, so the two counts agree', async () => {
    await renderMap();
    const footer = screen.getByTestId('wf-map-counts-footer');
    const shown = Number(footer.querySelector('b').textContent);
    const total = Number(/of (\d+) shown/.exec(footer.textContent)[1]);
    fireEvent.click(screen.getByTestId('map-list-button'));
    const { spotKeys } = onOpenList.mock.calls[0][0];
    expect([...spotKeys].sort()).toEqual(['#1', '#2', '#3']);
    // The Map's "x of y shown" is over the same set (My area, three places).
    expect(spotKeys.size).toBe(total);
    expect(spotKeys.size).toBe(shown);
  });

  it('widens with the scope segment: Everywhere hands over the fourth place', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    fireEvent.click(screen.getByTestId('wf-filters-scope-all'));
    fireEvent.click(screen.getByTestId('map-list-button'));
    expect([...onOpenList.mock.calls[0][0].spotKeys].sort()).toEqual(['#1', '#2', '#3', '#4']);
  });

  it('closes the Map\'s open menu when it opens, and leaves it open when the shell refuses', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();
    onOpenList.mockReturnValueOnce(false);
    fireEvent.click(screen.getByTestId('map-list-button'));
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('map-list-button'));
    expect(screen.queryByTestId('wf-jump-menu')).toBeNull();
  });

  it('stands down while a dialog from outside the pane is over it', async () => {
    await renderMap();
    const foreign = document.createElement('div');
    foreign.setAttribute('role', 'dialog');
    foreign.setAttribute('aria-modal', 'true');
    foreign.setAttribute('data-test-foreign', '');
    document.body.appendChild(foreign);
    fireEvent.click(screen.getByTestId('map-list-button'));
    expect(onOpenList).not.toHaveBeenCalled();
  });

  it.each([
    ['a window with no card (past, travel day, filler)', { windowCardsByKey: new Map() }, /no spot list for this window/i],
    ['a window the shell holds no cards for at all', { windowCardsByKey: null }, /no spot list for this window/i],
  ])('is greyed with its reason for %s, and presses nothing', async (_name, props, reason) => {
    await renderMap(props);
    const button = screen.getByTestId('map-list-button');
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).toHaveAttribute('aria-describedby', 'wf-map-list-why');
    expect(screen.getByTestId('map-list-why')).toHaveTextContent(reason);
    fireEvent.click(button);
    expect(onOpenList).not.toHaveBeenCalled();
  });

  it('says why for a served window the shell holds no card for', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const row = screen.getAllByTestId('wf-win-row').find((r) => r.getAttribute('data-ev-id') === `solar:${TODAY}:SUNRISE`);
    fireEvent.click(row);
    expect(screen.getByTestId('map-list-button')).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByTestId('map-list-why')).toHaveTextContent('There is no spot list for this window');
  });

  it('says why on a past window', async () => {
    vi.setSystemTime(new Date(`${TOMORROW}T20:00:00Z`));
    const locs = locations();
    await renderMap({ pastSolarWindows: buildPastSolarWindows(locs, TOMORROW), locations: locs, date: TODAY, autoEventType: 'SUNRISE' });
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const row = screen.getAllByTestId('wf-win-row').find((r) => r.getAttribute('data-ev-id') === `solar:${TODAY}:SUNRISE`);
    if (row) fireEvent.click(row);
    expect(screen.getByTestId('map-list-why')).toHaveTextContent(/past window|no spot list/i);
  });

  it('says there is no window when the Map has none selected', async () => {
    await renderMap({ heat: { ...heatProp(), windows: [] }, forecastDates: [], date: TOMORROW });
    expect(screen.getByTestId('map-list-button')).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByTestId('map-list-why').textContent).toMatch(/no window|no spot list/i);
  });

  it('is not drawn on a phone: the peek sheet carries the door instead', async () => {
    mapViewState.isMobile = true;
    await renderMap();
    expect(screen.queryByTestId('map-list-button')).toBeNull();
    expect(screen.queryByTestId('wf-map-chrome-tr')).toBeNull();
    expect(screen.queryByTestId('wf-map-peek-list')).toBeNull();
  });

  it('adds "See all N spots" to the phone Windows section, opening the list without closing it', async () => {
    mapViewState.isMobile = true;
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-win'));
    const row = screen.getByTestId('wf-map-peek-list');
    expect(row).toHaveTextContent('See all 3 spots');
    fireEvent.click(row);
    expect(onOpenList).toHaveBeenCalledTimes(1);
    expect(onOpenList.mock.calls[0][0].key).toBe(SUNSET);
    expect(screen.getByTestId('wf-map-peek-windows')).toBeInTheDocument();
  });

  it('says "1 spot" for a single place and draws no row for a window with no card', async () => {
    mapViewState.isMobile = true;
    await renderMap({ windowCardsByKey: cardsByKey({ ...CARD, allSpots: [spotOf(SPOTS[0], 4)] }) });
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-win'));
    expect(screen.getByTestId('wf-map-peek-list')).toHaveTextContent('See all 1 spot');
    expect(screen.getByTestId('wf-map-peek-list')).not.toHaveTextContent('spots');
  });

  it('draws no phone row for a window with no card', async () => {
    mapViewState.isMobile = true;
    await renderMap({ windowCardsByKey: new Map() });
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-win'));
    expect(screen.queryByTestId('wf-map-peek-list')).toBeNull();
  });

  it('selects a place in place through the callback it hands over', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-list-button'));
    const { onPick } = onOpenList.mock.calls[0][0];
    await act(async () => { onPick({ locationId: 3, locationName: 'Wastwater' }); });
    expect(await screen.findByTestId('probe-callout-name')).toHaveTextContent('Wastwater');
  });
});

describe('the Map\'s Best bet · why', () => {
  it('draws nothing without a pick, for a Lite reader or an away origin the server scoped out', async () => {
    await renderMap({ windowCardsByKey: cardsByKey({ ...CARD, pick: null }) });
    expect(screen.queryByTestId('map-best-bet-why')).toBeNull();
  });

  it('is a pill outside the window control that expands the served headline and detail', async () => {
    await renderMap();
    const pill = screen.getByTestId('map-best-bet-why');
    expect(screen.getByTestId('wf-win-pill').closest('[data-testid="wf-map-chrome-tl"]')).toContainElement(pill);
    expect(screen.getByTestId('wf-win-pill').parentElement).not.toContainElement(pill);
    expect(pill).toHaveTextContent('Best bet · why');
    expect(pill).toHaveAttribute('aria-expanded', 'false');
    expect(pill).not.toHaveAttribute('aria-controls');
    fireEvent.click(pill);
    expect(pill).toHaveAttribute('aria-expanded', 'true');
    expect(pill).toHaveAttribute('aria-controls', screen.getByTestId('map-best-bet-body').id);
    expect(screen.getByTestId('map-best-bet-headline')).toHaveTextContent(PICK.headline);
    expect(screen.getByTestId('map-best-bet-detail')).toHaveTextContent(PICK.detail);
    expect(screen.getByTestId('map-best-bet-body').closest('[role="status"]')).toBeNull();
    fireEvent.click(pill);
    expect(screen.queryByTestId('map-best-bet-body')).toBeNull();
  });

  it('draws nothing for a window with no card (a night, a past window, a filler)', async () => {
    await renderMap({ windowCardsByKey: new Map() });
    expect(screen.queryByTestId('map-best-bet-why')).toBeNull();
  });

  it('closes when the window moves, because the pick belongs to the window', async () => {
    const view = await renderMap();
    fireEvent.click(screen.getByTestId('map-best-bet-why'));
    expect(screen.getByTestId('map-best-bet-body')).toBeInTheDocument();
    const sunrise = { ...CARD, key: windowKey(TODAY, 'SUNRISE'), targetType: 'SUNRISE' };
    await view.again({
      autoEventType: 'SUNRISE',
      windowCardsByKey: new Map([[CARD.key, CARD], [sunrise.key, sunrise]]),
    });
    expect(screen.queryByTestId('map-best-bet-body')).toBeNull();
  });
});

describe('the Regions menu, best first', () => {
  const names = () => screen.getAllByTestId('wf-jump-row').map((row) => row.querySelector('.wf-jump-name').textContent);

  it('orders a solar window by the drilldown\'s own comparator, with the verdict word and "k of m at 4★+"', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    // The Borders is outside the area, so it has no verdict row and keeps its place after the ranked ones.
    expect(names()).toEqual(['The Lakes', 'North East', 'The Borders']);
    const lakes = screen.getAllByTestId('wf-jump-row')[0];
    expect(within(lakes).getByTestId('wf-jump-word')).toHaveTextContent('Worth it');
    expect(within(lakes).getByTestId('wf-jump-word')).toHaveAttribute('data-tier', 'WORTH_IT');
    expect(within(lakes).getByTestId('wf-jump-count')).toHaveTextContent('1 of 1 at 4★+');
    const northEast = screen.getAllByTestId('wf-jump-row')[1];
    expect(within(northEast).getByTestId('wf-jump-word')).toHaveTextContent('Poor');
    expect(within(northEast).getByTestId('wf-jump-count')).toHaveTextContent('1 of 2 at 4★+');
    expect(screen.getByTestId('wf-jump-menu').textContent).not.toMatch(/in reach/i);
    expect(within(screen.getAllByTestId('wf-jump-row')[2]).queryByTestId('wf-jump-sub')).toBeNull();
  });

  it('keeps the drive order for a window with no regional verdict (here, one the index has no entry for)', async () => {
    await renderMap({ regionVerdictIndex: buildRegionVerdictIndex([]) });
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(names()).toEqual(['North East', 'The Borders', 'The Lakes']);
    expect(screen.queryByTestId('wf-jump-sub')).toBeNull();
  });
});

describe('the Map warning expand', () => {
  it('leaves the chip a Met Office link and adds a separate toggle beside it', async () => {
    await renderMap();
    const chip = screen.getByTestId('warning-chip');
    expect(chip).toHaveAttribute('href', METOFFICE_URL);
    const toggle = screen.getByTestId('map-warning-toggle');
    expect(toggle.contains(chip)).toBe(false);
    expect(chip.contains(toggle)).toBe(false);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByTestId('warning-attribution')).toBeInTheDocument();
  });

  it('opens the warning in full in a panel under the chrome group, never inside it', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    const panel = screen.getByTestId('map-warning-panel');
    const group = screen.getByTestId('wf-map-chrome-tl');
    expect(group.contains(panel)).toBe(false);
    expect(panel).toHaveTextContent('Amber warning of snow');
    expect(screen.getByTestId('window-warning')).toBeInTheDocument();
    expect(screen.getByTestId('map-warning-toggle')).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('map-warning-toggle')).toHaveAttribute('aria-controls', panel.id);
    // The attribution is on screen open or closed.
    expect(screen.getAllByTestId('warning-attribution').length).toBeGreaterThan(0);
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
  });

  it('closes on Escape before anything else, and on its own close button', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    fireEvent.keyDown(screen.getByTestId('wf-map-pane'), { key: 'Escape' });
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    fireEvent.click(screen.getByTestId('map-warning-panel-close'));
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
  });

  it('is exclusive with the Map\'s menus in both directions', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    expect(screen.queryByTestId('wf-jump-menu')).toBeNull();
    expect(screen.getByTestId('map-warning-panel')).toBeInTheDocument();
  });

  it('is a bottom sheet on a phone', async () => {
    mapViewState.isMobile = true;
    await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    const panel = screen.getByTestId('map-warning-panel');
    expect(panel).toHaveTextContent('Amber warning of snow');
    expect(screen.queryByTestId('map-warning-panel-close')).toBeNull();
    expect(screen.getByTestId('wf-map-chrome-tl').contains(panel)).toBe(false);
  });
});

describe('a Lite reader', () => {
  beforeEach(() => { mapViewState.role = 'LITE_USER'; });

  it('gets the List, the warning expand and the ranked Regions menu as a Pro reader does', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-list-button'));
    expect(onOpenList).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    expect(screen.getByTestId('map-warning-panel')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(screen.getAllByTestId('wf-jump-row')[0]).toHaveTextContent('The Lakes');
  });

  it('shows no Best bet reason where the server served no pick', async () => {
    await renderMap({ windowCardsByKey: cardsByKey({ ...CARD, pick: null }) });
    expect(screen.queryByTestId('map-best-bet-why')).toBeNull();
    expect(screen.getByTestId('map-list-button')).toBeInTheDocument();
  });
});
