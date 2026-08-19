import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act, cleanup, within } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

/**
 * The shell's half of the open row's region layer.
 *
 * <p>Scoped the way {@code WindowFirstShellTabs} and {@code WindowFirstShellSheet} are, and for the
 * same reason: every rule here is about how the shell JOINS things, and none of them is reachable
 * from a component's own test. The three components all take their inputs as props, so their files
 * pass {@code field} by hand — which leaves the map that BUILDS it, the per-card selection state and
 * the withholding rule pinned by nothing at all.
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
  orderLens: { order: { id: 'when', label: 'When' }, orderId: 'when', selectOrder: vi.fn() },
  ...overrides,
});

let originalGetContext;
async function renderShell(overrides = {}) {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(overrides));
  await import('../components/WindowRowRegionLayer.jsx');
  await act(async () => {
    render(
      <WindowFirstShell
        onExit={vi.fn()}
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

/** The rail belonging to the nth open card — scoped with `within`, never `querySelector`. */
function railCells(nth = 0) {
  return within(screen.getAllByTestId('window-card')[nth]).queryAllByTestId('wf-region-cell');
}

/** Whether the nth card has its band open. */
function hasBand(nth) {
  return within(screen.getAllByTestId('window-card')[nth])
    .queryByTestId('wf-region-band') !== null;
}

describe('WindowFirstShell — building the region layer', () => {
  it('ranks each card’s rail from ITS OWN window’s regions', async () => {
    // The event summary is looked up by the card's key. Handing every card the first window's
    // summary is a one-character mistake that no component test can see.
    await renderShell();
    expect(railCells(0).map((c) => c.getAttribute('data-region')))
      .toEqual(['all', 'Coast', 'Dales']);
  });

  it('withholds the whole layer when there is no catalogue to draw', async () => {
    // A scores fetch that failed, or a session with no roster. A field map of nothing is a picture
    // claiming there is nothing there — the strip's own rule.
    await renderShell({ heatSpots: [] });
    expect(screen.queryByTestId('wf-row-map')).toBeNull();
    expect(screen.queryByTestId('wf-region-rail')).toBeNull();
    // …and the row itself is untouched.
    expect(screen.getAllByTestId('window-card').length).toBe(2);
  });

  it('marks only the card whose window carries no ratings', async () => {
    // The join this file exists for: `scoresKnown` comes off the provider and `points` off the
    // keyed map, and the mark is the two of them together. Neither component can see the pairing —
    // the map is handed both as props — so a shell that passed the flag and forgot the lookup, or
    // handed every card the same window's points, would leave both files green.
    await renderShell({
      scoresLoaded: true,
      heatPointSets: new Map([
        [KEY_A, [{ lat: 6, lng: 4, rid: 'Coast', r: [5] }]],
        [KEY_B, []],
      ]),
    });
    // The second row is closed on arrival — only the lead opens itself — and the region layer
    // mounts per OPEN card, so the comparison needs it opened.
    await act(async () => {
      fireEvent.click(screen.getAllByTestId('window-card-expander')[1]);
    });
    const cards = screen.getAllByTestId('window-card');
    expect(within(cards[0]).queryByTestId('wf-row-map-unscored')).toBeNull();
    expect(within(cards[1]).getByTestId('wf-row-map-unscored')).toHaveTextContent('Not scored');
  });

  it('claims nothing about either card until the ratings response has arrived', async () => {
    // Every window is pointless on mount, which is why the flag exists rather than a bare
    // `points.length === 0` inside the map.
    await renderShell({ heatPointSets: new Map([[KEY_A, []], [KEY_B, []]]) });
    expect(screen.queryAllByTestId('wf-row-map-unscored')).toHaveLength(0);
  });

  it('words the filters from the live lens, so the footer names what is actually in force', async () => {
    await renderShell();
    fireEvent.click(railCells(0)[1]);
    expect(screen.getAllByTestId('window-spot-filters')[0])
      .toHaveTextContent('· Coast · within 45 min');
  });
});

describe('WindowFirstShell — the per-card selection', () => {
  it('narrows only the card the reader selected in', async () => {
    // Page-wide state here would silently narrow five cards nobody touched — the reason §4.4 puts
    // this beside the card's other open-state rather than beside the lens.
    await renderShell();
    fireEvent.click(railCells(0)[1]);
    expect(hasBand(0)).toBe(true);
    expect(hasBand(1)).toBe(false);
  });

  it('opens the band on the region it was given, with that region’s served narrative', async () => {
    await renderShell();
    fireEvent.click(railCells(0)[2]);
    expect(screen.getByTestId('wf-region-band-name')).toHaveTextContent('Dales');
    expect(screen.getByTestId('wf-region-band-narrative')).toHaveTextContent('A thin high veil.');
  });

  it('clears the selection when the card is collapsed', async () => {
    // The band and the rail are INSIDE the row, so a selection surviving a collapse would gate the
    // strip of a row the reader reopens later with the filter's own UI gone.
    await renderShell();
    fireEvent.click(railCells(0)[1]);
    expect(screen.getAllByTestId('wf-region-band').length).toBe(1);

    const expander = screen.getAllByTestId('window-card-expander')[0];
    fireEvent.click(expander); // collapse
    fireEvent.click(expander); // reopen
    expect(screen.queryByTestId('wf-region-band')).toBeNull();
    expect(railCells(0)[0]).toHaveAttribute('aria-pressed', 'true');
  });
});
