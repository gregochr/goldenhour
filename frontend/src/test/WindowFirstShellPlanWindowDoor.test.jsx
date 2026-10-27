import React from 'react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, cleanup, waitFor } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { getAlmanac } from '../api/almanacApi.js';

/**
 * The Plan window door (hot-topics-by-day-plan §3 C1) — a Coming up lane's tap-through, as the
 * shell wires it: {@code goToPlanWindow(key, topicType)} switches to Plan, focuses the Plan tab,
 * opens that window's popup and emphasises the tapped topic's row.
 *
 * <p>Scoped like {@code WindowFirstShellDoor1.test.jsx}: what only the SHELL can be wrong about is
 * the order of those steps, the scope check before emphasising, and when the emphasis is cleared.
 * The lane's own model and markup have their own suites.
 *
 * <p>The fixture is AWAY (origin: Lake District), so the popup's scope is one named region and a
 * topic whose {@code regions} does not include it is scoped out of the popup while the lanes —
 * which are not scope-filtered — still draw it. That asymmetry is the case the "emphasise only if
 * the popup has the row" rule exists for.
 */

vi.mock('../api/almanacApi.js', () => ({
  getAlmanac: vi.fn(),
  ALMANAC_DAYS: 90,
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
const TOMORROW = '2026-08-09';
const KEY_A = `${TODAY}:SUNSET`;
const KEY_B = `${TOMORROW}:SUNRISE`;

const DUST_BADGE = { type: 'DUST', label: 'Saharan dust', rarityRank: 3 };
const INVERSION_BADGE = { type: 'INVERSION', label: 'Inversion', rarityRank: 5 };
const AURORA_BADGE = { type: 'AURORA', label: 'Aurora possible', rarityRank: 1 };

const HOT_TOPICS = [
  // In scope for the Lake District origin.
  {
    type: 'DUST', label: 'Saharan dust', date: TODAY, eventType: 'SUNSET', regions: ['Lake District'],
  },
  // Region-scoped and NOT in scope: the lanes draw it, the popup does not.
  {
    type: 'INVERSION', label: 'Inversion', date: TODAY, eventType: 'SUNSET', regions: ['Coast'],
  },
  // Whole-sky, so exempt from the scope whatever its regions say.
  {
    type: 'AURORA', label: 'Aurora possible', date: TODAY, eventType: 'NIGHT', regions: ['Coast'],
  },
];

const SPOTS = [{
  key: 'Keswick', name: 'Keswick', locationName: 'Keswick', regionName: 'Lake District',
  rating: 4, driveMinutes: 30,
}];

const card = (key, date, allBadges) => ({
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
  badges: allBadges,
  allBadges,
  rows: [],
  pick: null,
  spots: SPOTS,
  allSpots: SPOTS,
  reachMeasured: true,
  reachTotal: SPOTS.length,
});

const CARDS = [
  card(KEY_A, TODAY, [AURORA_BADGE, DUST_BADGE, INVERSION_BADGE]),
  card(KEY_B, TOMORROW, [AURORA_BADGE]),
];

const DAYS = [
  { date: TODAY, eventSummaries: [{ targetType: 'SUNSET', regions: [] }] },
  { date: TOMORROW, eventSummaries: [{ targetType: 'SUNRISE', regions: [] }] },
];

const ctx = (overrides = {}) => ({
  briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: HOT_TOPICS, days: DAYS },
  loading: false,
  heatStripCards: [
    { key: KEY_A, date: TODAY, targetType: 'SUNSET', dow: 'Sat', sunrise: false, label: 'Tonight Sunset', time: '20:41' },
    { key: KEY_B, date: TOMORROW, targetType: 'SUNRISE', dow: 'Sun', sunrise: true, label: 'Tomorrow sunrise', time: '05:20' },
  ],
  heatSpots: [{
    id: 1, name: 'Keswick', lat: 6, lng: 4, regionName: 'Lake District', rid: 'Lake District', scores: [5, 4],
  }],
  heatPointSets: new Map(),
  regionSeries: new Map(),
  windowCards: CARDS,
  paneItems: CARDS.map((c) => ({ kind: 'card', key: c.key, card: c })),
  upcomingEvents: [],
  travelDayDates: new Set(),
  evaluationScores: new Map(),
  scoreIndex: new Map(),
  reachById: new Map(),
  todayStr: TODAY,
  tomorrowStr: TOMORROW,
  homePlace: 'Newcastle',
  isPro: true,
  isLiteUser: false,
  origin: { id: 'lakes', name: 'Lake District', baseName: 'Keswick' },
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

async function renderShell(overrides = {}, { onOpenMapTab = vi.fn(), forecastDates } = {}) {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(overrides));
  await act(async () => {
    render(
      <WindowFirstShell
        onOpenSettings={vi.fn()}
        onSignOut={vi.fn()}
        onShowOnMap={vi.fn()}
        onOpenMapTab={onOpenMapTab}
        forecastDates={forecastDates}
        locations={[]}
      />,
    );
  });
}

beforeEach(() => {
  getAlmanac.mockReset();
  getAlmanac.mockResolvedValue({
    builtFor: TODAY, bands: null, counts: null, conditions: [], entries: [],
  });
});

afterEach(() => {
  if (originalGetContext) HTMLCanvasElement.prototype.getContext = originalGetContext;
  cleanup();
  vi.restoreAllMocks();
});

// jsdom applies no Tailwind, so BOTH GlanceLabel spans count: the tab's name is "At a GlanceGlance" here.
const tab = (name) => screen.getByRole('tab', { name: name === 'At a Glance' ? /^At a Glance/ : name });

/** Goes to Coming up and presses the lane button for one topic type. */
async function pressLane(type) {
  await act(async () => { fireEvent.click(tab('Coming up')); });
  const button = screen.getAllByTestId('coming-up-fourdays-chip')
    .find((b) => b.getAttribute('data-topic') === type);
  expect(button).toBeDefined();
  await act(async () => { fireEvent.click(button); });
}

const topicRow = (type) => screen.queryAllByTestId('wf-topic-row')
  .find((row) => row.getAttribute('data-topic-type') === type);

describe('WindowFirstShell — the Plan window door (a Coming up lane’s tap-through)', () => {
  it('opens that window’s popup on the Plan tab with the tapped topic’s row emphasised', async () => {
    await renderShell();
    await pressLane('DUST');

    expect(tab('At a Glance')).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByTestId('window-sheet')).toBeInTheDocument();
    expect(topicRow('DUST')).toHaveAttribute('data-emphasis', 'true');
    expect(screen.getByTestId('wf-topic-rows')).toHaveAttribute('data-emphasis-topic', 'DUST');
    // Only the tapped topic's row — its neighbours are not.
    expect(topicRow('AURORA')).not.toHaveAttribute('data-emphasis');
  });

  it('names the emphasised topic in the dialog\'s live region, only when the popup has that row', async () => {
    await renderShell();
    await pressLane('DUST');
    await screen.findByTestId('window-sheet');
    expect(screen.getByTestId('window-sheet-live')).toHaveTextContent('highlighting Saharan dust');
    await act(async () => { fireEvent.click(screen.getByTestId('window-sheet-close')); });

    await pressLane('INVERSION'); // scoped out of the popup
    await screen.findByTestId('window-sheet');
    expect(screen.getByTestId('window-sheet-live')).not.toHaveTextContent('highlighting');
  });

  it('sends a weather chip with a forecast to the Map, not to the Plan popup', async () => {
    const onOpenMapTab = vi.fn();
    const mist = {
      type: 'MIST', label: 'Valley mist', date: TODAY, eventType: 'SUNRISE', regions: ['Lake District'],
      glance: { value: 'dawn · likely at 2 sites', shortValue: 'likely', weather: true },
    };
    await renderShell(
      { briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [mist], days: DAYS } },
      { onOpenMapTab, forecastDates: [TODAY] },
    );
    await pressLane('MIST');
    expect(onOpenMapTab).toHaveBeenCalledTimes(1);
    expect(onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({ date: TODAY }));
    expect(screen.queryByTestId('window-sheet')).toBeNull();
  });

  it('emphasises a whole-sky topic, which the scope never filters', async () => {
    await renderShell();
    await pressLane('AURORA');
    await screen.findByTestId('window-sheet');
    expect(topicRow('AURORA')).toHaveAttribute('data-emphasis', 'true');
  });

  it('opens the popup but emphasises nothing when the topic is scoped out of it', async () => {
    await renderShell();
    await pressLane('INVERSION');

    await screen.findByTestId('window-sheet');
    // The lane drew it (the lanes are not scope-filtered); the popup, which is, does not have it.
    expect(topicRow('INVERSION')).toBeUndefined();
    expect(screen.getAllByTestId('wf-topic-row').some((row) => row.hasAttribute('data-emphasis')))
      .toBe(false);
    // And the shell never handed the dialog an emphasis it has no row for.
    expect(screen.getByTestId('wf-topic-rows')).not.toHaveAttribute('data-emphasis-topic');
  });

  it('clears the emphasis when the popup steps to another window', async () => {
    await renderShell();
    await pressLane('DUST');
    await screen.findByTestId('window-sheet');
    expect(topicRow('DUST')).toHaveAttribute('data-emphasis', 'true');

    await act(async () => { fireEvent.click(screen.getByTestId('window-sheet-next')); });
    await act(async () => { fireEvent.click(screen.getByTestId('window-sheet-prev')); });

    // Back on the original window, but the emphasis belonged to the first visit.
    expect(topicRow('DUST')).toBeDefined();
    expect(topicRow('DUST')).not.toHaveAttribute('data-emphasis');
  });

  it('clears the emphasis on close, and a reopened popup does not revive it', async () => {
    await renderShell();
    await pressLane('DUST');
    await screen.findByTestId('window-sheet');

    await act(async () => { fireEvent.click(screen.getByTestId('window-sheet-close')); });
    expect(screen.queryByTestId('window-sheet')).toBeNull();

    await act(async () => { fireEvent.click(tab('At a Glance')); });
    await act(async () => { fireEvent.click(screen.getAllByTestId('wf-heat-card')[1]); });
    await screen.findByTestId('window-sheet');
    expect(topicRow('DUST')).toBeDefined();
    expect(topicRow('DUST')).not.toHaveAttribute('data-emphasis');
  });

  it('closing the popup returns focus to the Plan tab, not to the hidden lane button', async () => {
    await renderShell();
    await pressLane('DUST');
    const dialog = await screen.findByTestId('window-sheet');
    // The dialog has taken focus (so the tab really was captured as the opener) ...
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
    expect(tab('At a Glance')).not.toHaveFocus();

    await act(async () => { fireEvent.click(screen.getByTestId('window-sheet-close')); });
    await act(async () => { await Promise.resolve(); });

    // ... and gives it back to the Plan tab on close.
    expect(tab('At a Glance')).toHaveFocus();
  });

  it('hands the lanes the shell\'s todayStr — the first day column is today\'s date', async () => {
    // `todayStr` comes from the briefing provider (Rewind-aware, via appNow); the lanes must read
    // that value and not a clock of their own.
    await renderShell({
      todayStr: '2026-12-31',
      briefing: {
        generatedAt: '2026-12-31T12:00:00',
        days: DAYS,
        hotTopics: [{ type: 'DUST', label: 'Saharan dust', date: '2026-12-31', eventType: 'SUNSET' }],
      },
    });
    await act(async () => { fireEvent.click(tab('Coming up')); });
    const days = screen.getAllByTestId('coming-up-fourdays-day');
    expect(days[0]).toHaveTextContent('Thu31');
    expect(days[1]).toHaveTextContent('Fri1');
  });
});
