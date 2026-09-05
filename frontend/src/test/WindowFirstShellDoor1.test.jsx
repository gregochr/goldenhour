import React from 'react';
import { describe, it, expect, vi, afterEach, beforeAll } from 'vitest';
import { render, screen, fireEvent, act, cleanup, within } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import warmPlanChunks from './warmPlanChunks.js';

/**
 * The shell's wiring for Door 1 (`◍ Open in map →`, `plan-to-map-doors-plan.md` §3 D4) — the popup
 * field's button that hands the open window to {@code App}'s map handoff.
 *
 * <p>Scoped like {@code WindowFirstShellRegion.test.jsx} and for the same reason: both
 * {@code WindowRowFieldMap} and {@code WindowSheetDialog} take {@code onOpenInMap}/
 * {@code onOpenLocation}-shaped props and pass them straight through, so what only THIS file can be
 * wrong about is what the shell BUILDS for the callback — not the button, not the seed, and not the
 * close-then-move-and-merge ordering itself ({@code utils/mapDoors.js#openMapDoor} is a pure
 * function with its own dedicated suite, {@code test/mapDoors.test.js}, from D2). What is asserted
 * here is the ONE thing that lives only in this join: which region rides with the door — the
 * popup's own focus at home, forced null under an away origin (plan §1 #7, §4 #9's shell note) —
 * and that the withholding rule (no {@code onOpenMapTab} ⇒ no button at all) reaches this far.
 */

vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));

vi.mock('../utils/heatField.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    load: vi.fn(() => Promise.resolve({ type: 'FeatureCollection', features: [] })),
    land: vi.fn(() => ({ type: 'FeatureCollection', features: [] })),
    drawGeo: vi.fn(() => ([lng, lat]) => [lng * 10, lat * 10]),
  };
});

const TODAY = '2026-08-08';
const KEY_A = `${TODAY}:SUNSET`;
const KEY_B = '2026-08-09:SUNRISE';

function regions() {
  return [
    {
      regionName: 'Coast',
      displayVerdict: 'WORTH_IT',
      summary: 'A clean eastern horizon.',
      meanRating: 4.4,
      bestRating: 5,
      slots: [{ canopy: false }],
    },
    {
      regionName: 'Dales',
      displayVerdict: 'MAYBE',
      summary: 'A thin high veil.',
      meanRating: 3.1,
      bestRating: 4,
      slots: [{ canopy: false }],
    },
  ];
}

function cardSpot(name, regionName, rating) {
  return { key: name, name, locationName: name, regionName, rating, driveMinutes: 30 };
}

const SPOTS = [cardSpot('Bamburgh', 'Coast', 5), cardSpot('Malham', 'Dales', 4)];

const card = (key, date) => ({
  key,
  date,
  targetType: key.endsWith('SUNSET') ? 'SUNSET' : 'SUNRISE',
  lead: key === KEY_A,
  kicker: null,
  when: key === KEY_A ? 'Tonight Sunset' : 'Tomorrow sunrise',
  time: '20:41',
  verdict: 'WORTH_IT',
  verdictLabel: 'Worth it',
  bestRating: 5,
  confidence: 'high',
  badges: [],
  rows: [],
  pick: null,
  spots: SPOTS,
  allSpots: SPOTS,
  reachMeasured: SPOTS.some((s) => s?.driveMinutes != null),
  reachTotal: SPOTS.length,
});

const CARDS = [card(KEY_A, TODAY), card(KEY_B, '2026-08-09')];

const DAYS = [
  { date: TODAY, eventSummaries: [{ targetType: 'SUNSET', regions: regions() }] },
  { date: '2026-08-09', eventSummaries: [{ targetType: 'SUNRISE', regions: regions() }] },
];

const HEAT_SPOTS = [
  { id: 1, name: 'Bamburgh', lat: 6, lng: 4, regionName: 'Coast', rid: 'Coast', scores: [5, 4] },
  { id: 2, name: 'Malham', lat: 6, lng: 24, regionName: 'Dales', rid: 'Dales', scores: [4, 3] },
];

/**
 * Deliberately the SAME shape {@code WindowFirstShellRegion.test.jsx} builds — two regions, two
 * windows — so the region-focus mechanics it already exercises (the rail, the popup's per-window
 * focus) behave identically here. Neither {@code origin}, {@code setOrigin}, {@code regions} nor
 * {@code effectiveReachById} is supplied by default, matching that file exactly: the shell
 * tolerates all four absent, and this file only ever supplies {@code origin} where a test is
 * explicitly ABOUT the away case.
 */
const ctx = (overrides = {}) => ({
  briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [], days: DAYS },
  loading: false,
  heatStripCards: [
    { key: KEY_A, date: TODAY, targetType: 'SUNSET', dow: 'Sat', sunrise: false, label: 'Tonight Sunset', time: '20:41' },
    { key: KEY_B, date: '2026-08-09', targetType: 'SUNRISE', dow: 'Sun', sunrise: true, label: 'Tomorrow sunrise', time: '05:20' },
  ],
  heatSpots: HEAT_SPOTS,
  heatPointSets: new Map([
    [KEY_A, [{ lat: 6, lng: 4, rid: 'Coast', r: [5] }, { lat: 6, lng: 24, rid: 'Dales', r: [4] }]],
    [KEY_B, [{ lat: 6, lng: 4, rid: 'Coast', r: [4] }]],
  ]),
  regionSeries: new Map([
    ['Coast', new Map([[KEY_A, 4.4], [KEY_B, 3.0]])],
    ['Dales', new Map([[KEY_A, 3.1]])],
  ]),
  windowCards: CARDS,
  paneItems: CARDS.map((c) => ({ kind: 'card', key: c.key, card: c })),
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
    floor: { id: 'any', min: null, label: 'Any rating' }, floorId: 'any', minRating: null, selectFloor: vi.fn(),
  },
  ...overrides,
});

let originalGetContext;
/**
 * @param {object} overrides merged into the briefing context, as {@code WindowFirstShellRegion}'s
 *        own {@code renderShell} does.
 * @param {object} props merged into the shell's OWN props — the one addition this file needs over
 *        that template, so a test can withhold {@code onOpenMapTab} (undefined, never a no-op
 *        closure) and prove the withholding rule reaches the button.
 */
async function renderShell(overrides = {}, props = {}) {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(overrides));
  const onOpenMapTab = vi.fn();
  await act(async () => {
    render(
      <WindowFirstShell
        onOpenSettings={vi.fn()}
        onSignOut={vi.fn()}
        onShowOnMap={vi.fn()}
        onOpenMapTab={onOpenMapTab}
        locations={[]}
        {...props}
      />,
    );
  });
  return { onOpenMapTab };
}

afterEach(() => {
  if (originalGetContext) HTMLCanvasElement.prototype.getContext = originalGetContext;
  cleanup();
  vi.restoreAllMocks();
});

/**
 * Opens the nth matrix cell's popup, in DOCUMENT order — see
 * {@code WindowFirstShellRegion.test.jsx}'s own note on row-major DOM order: `nth: 1` is `KEY_A`
 * (today's sunset) on this file's fixture, the reverse of `heatStripCards`' own array order.
 */
async function openWindow(nth = 0) {
  await screen.findByTestId('wf-heat-strip');
  await act(async () => {
    fireEvent.click(screen.getAllByTestId('wf-heat-card')[nth]);
  });
  return screen.findByTestId('window-sheet');
}

/** The open popup's rail cells — scoped with `within`, never `querySelector`. */
function railCells() {
  return within(screen.getByTestId('window-sheet')).queryAllByTestId('wf-region-cell');
}

/** Door 1's own button, scoped to the open popup. */
function doorButton() {
  return within(screen.getByTestId('window-sheet')).getByTestId('wf-row-map-open');
}

// Pays the shell's four `lazy()` boundaries once per FILE, in a hook with its own budget, rather
// than inside whichever test happens to run first. See `warmPlanChunks.js` for the measurements
// and for the full-suite reproduction that made this necessary.
beforeAll(warmPlanChunks);

describe('WindowFirstShell — Door 1 (the popup field’s "Open in map")', () => {
  it('renders no button at all when the shell has no map door', async () => {
    await renderShell({}, { onOpenMapTab: undefined });
    await openWindow(1);
    expect(within(screen.getByTestId('window-sheet')).queryByTestId('wf-row-map-open')).toBeNull();
  });

  it('closes the popup and hands the open window to onOpenMapTab, with no region at home', async () => {
    const { onOpenMapTab } = await renderShell();
    await openWindow(1);
    await act(async () => { fireEvent.click(doorButton()); });

    // The ordering itself (close, THEN move) is `openMapDoor`'s own job and its own suite — what
    // this proves is that the shell's wiring actually reaches it: both effects fire from one click.
    expect(screen.queryByTestId('window-sheet')).toBeNull();
    expect(onOpenMapTab).toHaveBeenCalledTimes(1);
    // The lens values are read LIVE through `openMapDoor`, never off the door payload itself — see
    // `mapDoors.js`'s own doc comment. This fixture's lens is Any rating / 45 min, so that is what
    // rides regardless of what a caller might have written into the door object.
    // `inPlace: false` is the shell's own stamp, read off the tab in force at the moment of the
    // press. It is false here because this fixture has no map pane at all, so `effectiveTab` is
    // 'plan' — and a door pressed from the Plan tab MUST carry the full payload: it is the route
    // that imports the Plan's lens onto the map (`App.openMapTabFromPlan`'s two branches).
    expect(onOpenMapTab).toHaveBeenCalledWith({
      date: TODAY, targetType: 'SUNSET', region: null, minRating: null, limitMinutes: 45,
      inPlace: false,
    });
  });

  it('carries the popup’s own focused region, once one is picked', async () => {
    const { onOpenMapTab } = await renderShell();
    await openWindow(1);
    // 'all' is index 0; 'Coast' is index 1 — pinned by WindowFirstShellRegion.test.jsx's own
    // "ranks the popup's rail" test against this identical fixture shape.
    fireEvent.click(railCells()[1]);
    await act(async () => { fireEvent.click(doorButton()); });

    expect(onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({ region: 'Coast' }));
  });

  it('⚠️ carries no region under an away origin, even though the rail is withheld and cannot be pressed', async () => {
    // `openField`'s own note: the origin has already answered "which region" for the whole page,
    // so `singleRegionScope` withholds the rail entirely — there is no cell left to press, and the
    // only way this could still carry a region is a stale `focusedRegion` leaking through unguarded.
    const { onOpenMapTab } = await renderShell({
      origin: { id: 'lakes', name: 'Lake District', baseName: 'Keswick' },
    });
    await openWindow(1);
    expect(railCells()).toHaveLength(0);
    await act(async () => { fireEvent.click(doorButton()); });

    expect(onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({ region: null }));
  });
});
