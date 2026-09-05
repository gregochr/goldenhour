import React from 'react';
import { describe, it, expect, vi, afterEach, beforeAll } from 'vitest';
import { render, screen, fireEvent, act, cleanup, within } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import warmPlanChunks from './warmPlanChunks.js';

/**
 * The shell's half of the window popup's region layer.
 *
 * <p>Scoped the way {@code WindowFirstShellTabs} and {@code WindowFirstShellSheet} are, and for the
 * same reason: every rule here is about how the shell JOINS things, and none of them is reachable
 * from a component's own test. The components all take their inputs as props, so their files pass
 * {@code field} by hand — which leaves the object that BUILDS it, the region-focus state and the
 * withholding rule pinned by nothing at all.
 *
 * <p>⚠️ The surface moved at M2: the layer was an accordion row's body and is now the popup's, so
 * every assertion here opens the window first. What is asserted is the same set of joins — the
 * per-window event summary, the per-window {@code bestRating}, the live lens words, and a focus
 * that dies with the dialog rather than leaking into the next window.
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
  // The ordinary reader: a saved postcode, so the reach tier really does gate and the surfaces may
  // say "within reach". `buildWindowCards` derives it from `allSpots`; a fixture that omitted it
  // would put every one of these assertions on the no-postcode wording.
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
async function renderShell(overrides = {}) {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(overrides));
  await act(async () => {
    render(
      <WindowFirstShell
        onOpenSettings={vi.fn()}
        onSignOut={vi.fn()}
        onShowOnMap={vi.fn()}
        locations={[]}
      />,
    );
  });
}

afterEach(() => {
  if (originalGetContext) HTMLCanvasElement.prototype.getContext = originalGetContext;
  cleanup();
  vi.restoreAllMocks();
});

/**
 * Opens the nth matrix cell's popup, in DOCUMENT order, and waits for the lazy dialog.
 *
 * <p>The matrix is behind its own `lazy()` boundary, so the cells do not exist on the first commit
 * — `findByTestId` for the strip is what makes the first test in the file behave like the rest
 * rather than like a race the module cache happens to win.
 *
 * <p>⚠️ `nth` is DOM position, not array position (matrix-axis plan §6.4) — since the desktop
 * default is row-major, `openWindow(0)` opens {@code KEY_B} (tomorrow's sunrise, this file's
 * sunrise-row window) and `openWindow(1)` opens {@code KEY_A} (today's sunset), the reverse of
 * their order in `heatStripCards`/`CARDS`.
 */
async function openWindow(nth = 0) {
  await screen.findByTestId('wf-heat-strip');
  await act(async () => {
    fireEvent.click(screen.getAllByTestId('wf-heat-card')[nth]);
  });
  return screen.findByTestId('window-sheet');
}

/** Closes whatever popup is open, so a test can open a different window. */
async function closeWindow() {
  await act(async () => { fireEvent.click(screen.getByTestId('window-sheet-close')); });
}

/** The open popup's rail cells — scoped with `within`, never `querySelector`. */
function railCells() {
  return within(screen.getByTestId('window-sheet')).queryAllByTestId('wf-region-cell');
}

// Pays the shell's `lazy()` boundaries once per FILE, in a hook with its own budget, rather than
// inside whichever test happens to run first. See `warmPlanChunks.js` for the measurements, the
// membership rule and the full-suite reproduction that made this necessary.
beforeAll(warmPlanChunks);

describe('WindowFirstShell — building the region layer', () => {
  // ⚠️ `openWindow(1)`, not `(0)`, opens KEY_A (today's sunset — "the first window" every comment
  // below means) throughout this file (matrix-axis plan §6.4). Row-major DOM puts the whole sunrise
  // row before the whole sunset row, so `openWindow(0)` now opens KEY_B (tomorrow's sunrise) — the
  // REVERSE of `heatStripCards`' own [KEY_A, KEY_B] order. Only 'marks only the card whose window
  // carries no served rating' (below) actually differentiates the two windows' data; every other
  // test here reads from `regions()`, which is identical content for both dates (`DAYS`, above), so
  // for THEM the index is unobservable either way — swept anyway, so `openWindow(1)` keeps meaning
  // "today's window" consistently across the file rather than by accident in one test alone.
  it('ranks the popup’s rail from ITS OWN window’s regions', async () => {
    // The event summary is looked up by the open card's key. Reading the first window's summary
    // for every window is a one-character mistake that no component test can see — but it is NOT
    // one THIS fixture can catch (see the block comment above): both windows carry the same
    // `regions()` array, so a component that read the wrong window's summary would still pass here.
    await renderShell();
    await openWindow(1);
    expect(railCells().map((c) => c.getAttribute('data-region')))
      .toEqual(['all', 'Coast', 'Dales']);
  });

  it('withholds the whole popup when there is no catalogue to draw', async () => {
    // A scores fetch that failed, or a session with no roster. A field map of nothing is a picture
    // claiming there is nothing there — the strip's own rule. With no catalogue the matrix
    // withdraws too, so there is no cell to press and no dialog to open.
    await renderShell({ heatSpots: [] });
    // The strip's chunk resolves either way; with no catalogue it renders nothing.
    await act(async () => { await Promise.resolve(); });
    expect(screen.queryByTestId('wf-heat-strip')).toBeNull();
    expect(screen.queryByTestId('window-sheet')).toBeNull();
    expect(screen.queryByTestId('wf-row-map')).toBeNull();
  });

  it('marks only the card whose window carries no served rating', async () => {
    // The join this file exists for: the layer hands the map its OWN card's `bestRating`, and a
    // wrapper that passed the first card's — or the window's point count, which is what the first
    // cut read — leaves both component files green while every row on the page agrees with the
    // wrong window. The point sets here are deliberately the reverse of the ratings, so a
    // component reading them instead of the rating fails this outright.
    // Both lists, from one array: the matrix draws `heatStripCards` and the popup reads
    // `windowCards`, so overriding one and not the other would test a page whose two halves
    // disagree about which windows exist.
    const both = [card(KEY_A, TODAY), { ...card(KEY_B, '2026-08-09'), bestRating: null }];
    await renderShell({
      windowCards: both,
      paneItems: both.map((c) => ({ kind: 'card', key: c.key, card: c })),
      heatPointSets: new Map([[KEY_A, []], [KEY_B, [{ lat: 6, lng: 4, rid: 'Coast', r: [5] }]]]),
    });
    // ⚠️ Index 1, not 0 (matrix-axis plan §6.4) — the DOM's row-major default puts KEY_B (tomorrow's
    // sunrise) at position 0 and KEY_A (today's sunset) at position 1, the reverse of their order in
    // `heatStripCards`/`both`.
    await openWindow(1);
    expect(screen.queryByTestId('wf-row-map-unscored')).toBeNull();
    await closeWindow();

    await openWindow(0);
    expect(screen.getByTestId('wf-row-map-unscored')).toHaveTextContent('Not scored');
  });

  it('claims nothing about a rated card whose field has no points', async () => {
    // Both windows are rated and neither has a point to paint — the production state this whole
    // predicate had to be moved off.
    await renderShell({ heatPointSets: new Map([[KEY_A, []], [KEY_B, []]]) });
    await openWindow(1);
    expect(screen.queryAllByTestId('wf-row-map-unscored')).toHaveLength(0);
  });

  it('words the filters from the live lens, so the footer names what is actually in force', async () => {
    await renderShell();
    await openWindow(1);
    fireEvent.click(railCells()[1]);
    expect(screen.getByTestId('window-spot-filters'))
      .toHaveTextContent('· Coast · within 45 min');
  });
});

describe('WindowFirstShell — the popup’s region focus', () => {
  it('swaps the prose to the region it was given, with that region’s served narrative', async () => {
    await renderShell();
    await openWindow(1);
    // Unpicked, the slot reads the TOP region and labels it as such (A21) — never the window.
    expect(screen.getByTestId('wf-prose-name')).toHaveTextContent('Coast');
    expect(screen.getByTestId('wf-prose-kicker')).toHaveTextContent('the window’s top region');

    fireEvent.click(railCells()[2]);
    expect(screen.getByTestId('wf-prose-name')).toHaveTextContent('Dales');
    expect(screen.getByTestId('wf-prose-body')).toHaveTextContent('A thin high veil.');
    expect(screen.queryByTestId('wf-prose-kicker')).toBeNull();
  });

  it('⚠️ resets the focus when the popup steps to another window', async () => {
    // A focus is a question about ONE window's field. Carrying it into the next would silently
    // filter a list the reader has not looked at yet — the rule the per-card map enforced by being
    // per card, which one value has to enforce by being reset.
    await renderShell();
    await openWindow(1);
    fireEvent.click(railCells()[2]);
    expect(screen.getByTestId('wf-prose-name')).toHaveTextContent('Dales');

    await act(async () => { fireEvent.click(screen.getByTestId('window-sheet-next')); });
    expect(railCells()[0]).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('wf-prose-name')).toHaveTextContent('Coast');
  });

  it('clears the focus when the popup is closed and reopened', async () => {
    await renderShell();
    await openWindow(1);
    fireEvent.click(railCells()[2]);
    await closeWindow();

    await openWindow(1);
    expect(railCells()[0]).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('wf-prose-name')).toHaveTextContent('Coast');
  });
});
