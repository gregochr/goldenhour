import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  render, screen, fireEvent, within, act,
} from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { useMapList } from '../context/MapListContext.jsx';
import { foreignModalOver, MAP_PANE_SELECTOR } from '../utils/mapForeignModal.js';

/**
 * The Map's List door, at the shell (glance-to-map-plan.md G1): the ranked spot list is the SHELL's own dialog, opened
 * from the Map pane through {@code MapListContext}. Scoped to the wiring only the shell can prove: the dialog is
 * foreign to the Map pane, it opens on the Map's floor and tier over the Map's in-scope set, a row selects in place
 * and closes, every other layer refuses it, and a phone gets a bottom sheet.
 */
const TODAY = '2026-08-08';

function spot(overrides = {}) {
  return {
    key: '1',
    locationId: 1,
    locationName: 'Bamburgh Beach',
    regionName: 'Northumberland & Tyneside',
    rating: 4,
    driveMinutes: 30,
    distanceMiles: 21,
    far: false,
    ...overrides,
  };
}

const NEAR = spot({ key: '1', locationName: 'Bamburgh Beach', rating: 4, driveMinutes: 30 });
const FAR = spot({
  key: '2', locationId: 2, locationName: 'Wastwater', rating: 5, driveMinutes: 180, far: true,
});
const KEY = `${TODAY}:SUNSET`;

const CARD = {
  key: KEY,
  date: TODAY,
  targetType: 'SUNSET',
  lead: true,
  kicker: 'Tonight',
  when: 'Sunset',
  time: '20:41',
  verdict: 'WORTH_IT',
  verdictLabel: 'Worth it',
  bestRating: 5,
  confidence: 'high',
  badges: [],
  rows: [],
  pick: null,
  spots: [NEAR],
  reachTotal: 2,
  allSpots: [NEAR, FAR],
};

const LOCATIONS = [
  { id: 1, name: 'Bamburgh Beach', locationType: ['SEASCAPE'] },
  { id: 2, name: 'Wastwater', locationType: ['LANDSCAPE'] },
];

const ctx = () => ({
  briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [] },
  loading: false,
  heatStripCards: [],
  heatSpots: [],
  heatPointSets: new Map(),
  windowCards: [CARD],
  paneItems: [{ kind: 'card', key: CARD.key, card: CARD }],
  upcomingEvents: [],
  travelDayDates: new Set(),
  evaluationScores: new Map(),
  scoreIndex: new Map(),
  reachById: new Map(),
  todayStr: TODAY,
  tomorrowStr: '2026-08-09',
  homePlace: 'Newcastle',
  isPro: true,
  isLiteUser: false,
  reachLens: {
    tier: { id: '45', label: '45 min', limitMinutes: 45 },
    tierId: '45',
    defaultTier: { id: '45', label: '45 min', limitMinutes: 45 },
    defaultTierId: '45',
    weekend: false,
    overridden: false,
    locked: false,
    selectTier: vi.fn(),
    resetToDefault: vi.fn(),
  },
  ratingLens: {
    floor: { id: 'any', min: null, label: 'Any rating' },
    floorId: 'any',
    minRating: null,
    selectFloor: vi.fn(),
  },
});

/** The Map pane stand-in: it holds the shell's List door and presses it with the request its test supplies. */
function MapProbe({ request, report }) {
  const door = useMapList();
  return (
    <div className="wf-map-tab" data-testid="map-probe">
      <button
        type="button"
        data-testid="probe-open"
        onClick={() => report(door?.openList(request))}
      >
        open
      </button>
      {door == null && <span data-testid="probe-no-door">no door</span>}
    </div>
  );
}

const baseRequest = (extra = {}) => ({
  key: KEY,
  spotKeys: new Set(['#1', '#2']),
  floorId: 'any',
  tierId: 'any',
  onPick: vi.fn(),
  ...extra,
});

const renderShell = (request, extraProps = {}) => {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  const report = vi.fn();
  const view = render(
    <WindowFirstShell
      onOpenSettings={vi.fn()}
      onSignOut={vi.fn()}
      onOpenMapTab={vi.fn()}
      locations={LOCATIONS}
      mapPane={<MapProbe request={request} report={report} />}
      loadMapRoute={{ preferred: true }}
      {...extraProps}
    />,
  );
  return { ...view, report };
};

const press = () => act(async () => { fireEvent.click(screen.getByTestId('probe-open')); });

let mobile = false;
beforeEach(() => {
  mobile = false;
  localStorage.clear();
  window.matchMedia = (query) => ({
    matches: mobile && query.includes('639'),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    onchange: null,
    dispatchEvent: () => false,
  });
});
afterEach(() => vi.restoreAllMocks());

describe('WindowFirstShell: the Map List door', () => {
  it('opens the shell-owned list, a foreign modal to the Map pane while it is open', async () => {
    const { report } = renderShell(baseRequest());
    const pane = screen.getByTestId('map-probe');
    expect(pane.matches(MAP_PANE_SELECTOR)).toBe(true);
    expect(foreignModalOver(pane)).toBe(false);
    await press();
    expect(report).toHaveBeenCalledWith(true);
    const sheet = screen.getByTestId('window-spot-sheet');
    expect(sheet).toHaveAttribute('aria-modal', 'true');
    expect(pane.contains(sheet)).toBe(false);
    expect(foreignModalOver(pane)).toBe(true);
    fireEvent.click(screen.getByTestId('window-spot-sheet-close'));
    expect(foreignModalOver(pane)).toBe(false);
  });

  it('shows only the places the Map holds in scope, so its count is the Map\'s', async () => {
    renderShell(baseRequest({ spotKeys: new Set(['#1']) }));
    await press();
    const list = screen.getByTestId('window-spot-sheet-list');
    expect(within(list).getAllByTestId('window-spot')).toHaveLength(1);
    expect(within(list).getByText('Bamburgh Beach')).toBeInTheDocument();
    expect(within(list).queryByText('Wastwater')).toBeNull();
    expect(screen.getByTestId('window-spot-sheet-count')).toHaveTextContent(/^1 spot/);
  });

  it('opens on the Map\'s rating floor and drive tier, not the Plan bar\'s', async () => {
    renderShell(baseRequest({ floorId: '4', tierId: '150' }));
    await press();
    const pressed = (id) => within(screen.getByTestId(id)).getAllByRole('button').filter(
      (b) => b.getAttribute('aria-pressed') === 'true',
    ).map((b) => b.dataset.option);
    expect(pressed('window-spot-sheet-rating')).toEqual(['4']);
    expect(pressed('window-spot-sheet-reach')).toEqual(['150']);
    // The Map's own tier is the sheet's, so it is not "widened for browsing" against it.
    expect(screen.queryByTestId('window-spot-sheet-widened')).toBeNull();
  });

  it('selects the place in place on a row press and closes, without re-doorring the Map', async () => {
    const request = baseRequest();
    const onOpenMapTab = vi.fn();
    renderShell(request, { onOpenMapTab });
    await press();
    fireEvent.click(within(screen.getByTestId('window-spot-sheet-list')).getAllByTestId('window-spot')[0]);
    expect(request.onPick).toHaveBeenCalledTimes(1);
    expect(request.onPick.mock.calls[0][0]).toMatchObject({ locationName: expect.any(String) });
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
    expect(onOpenMapTab).not.toHaveBeenCalled();
  });

  it('tolerates a request with no pick callback', async () => {
    renderShell(baseRequest({ onPick: undefined }));
    await press();
    fireEvent.click(within(screen.getByTestId('window-spot-sheet-list')).getAllByTestId('window-spot')[0]);
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
  });

  it.each([
    ['a window with no card', { key: '2026-01-01:SUNRISE' }],
    ['a request with no key', { key: '' }],
    ['a scope that is not a set', { spotKeys: ['#1'] }],
  ])('opens nothing for %s', async (_name, extra) => {
    const { report } = renderShell(baseRequest(extra));
    await press();
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
    expect(report).toHaveBeenCalledWith(false);
  });

  it('refuses while settings stands (the depth cap), taking nothing down', async () => {
    const { report } = renderShell(baseRequest(), { settingsOpen: true });
    await press();
    expect(report).toHaveBeenCalledWith(false);
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
  });

  it('refuses over a dialog this shell does not own', async () => {
    const { report } = renderShell(baseRequest());
    const foreign = document.createElement('div');
    foreign.setAttribute('role', 'dialog');
    foreign.setAttribute('aria-modal', 'true');
    document.body.appendChild(foreign);
    await press();
    foreign.remove();
    expect(report).toHaveBeenCalledWith(false);
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
  });

  it('refuses a second list while one is open', async () => {
    const { report } = renderShell(baseRequest());
    await press();
    expect(screen.getByTestId('window-spot-sheet')).toBeInTheDocument();
    // The probe sits behind the first dialog; a keyboard reader can still reach it.
    await press();
    expect(report).toHaveBeenLastCalledWith(false);
  });

  it('is a bottom sheet on a phone, the same card in a different frame', async () => {
    mobile = true;
    renderShell(baseRequest());
    await press();
    const sheet = screen.getByTestId('window-spot-sheet');
    expect(sheet).toHaveClass('wf-sheet-card--in-sheet');
    expect(screen.getByTestId('bottom-sheet-root')).toContainElement(sheet);
    expect(foreignModalOver(screen.getByTestId('map-probe'))).toBe(true);
  });

  it('is the centred modal off a phone', async () => {
    renderShell(baseRequest());
    await press();
    expect(screen.getByTestId('window-spot-sheet')).not.toHaveClass('wf-sheet-card--in-sheet');
    expect(screen.queryByTestId('bottom-sheet-root')).toBeNull();
  });

  it('closes on Escape and can be opened again', async () => {
    renderShell(baseRequest());
    await press();
    await act(async () => { fireEvent.keyDown(document, { key: 'Escape' }); });
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
    await press();
    expect(screen.getByTestId('window-spot-sheet')).toBeInTheDocument();
  });

  it('goes with the tab when the reader moves to another (selectTab clears its snapshot)', async () => {
    renderShell(baseRequest());
    await press();
    fireEvent.click(screen.getByRole('tab', { name: /^At a Glance/ }));
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
  });
});
