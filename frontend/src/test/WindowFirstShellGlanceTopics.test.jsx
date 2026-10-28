import React from 'react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, cleanup, within, waitFor } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { getAlmanac } from '../api/almanacApi.js';

/**
 * The shell's route from At a Glance's topic pills into Coming up (glance-topic-strip-plan.md R1, R4): the
 * {@code comingUpFocus} request {@code openComingUp} stores, survives {@code selectTab}, and
 * {@code WindowFirstComingUp} answers — scrolling to the entry, resetting a filter that hides it, or falling back
 * to the top of the tab with focus on the Coming up tab.
 *
 * <p>Scoped like {@code WindowFirstShellPlanWindowDoor.test.jsx}: what only the SHELL can be wrong about is that
 * the request outlives the tab change, reaches the pane, and is cleared exactly once.
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
  // Has an almanac counterpart: a pill for it jumps to the Coming up entry below.
  {
    type: 'SPRING_TIDE', label: 'Spring tide', date: TODAY, eventType: null, regions: [],
    tideRun: { dayNumber: 1, dayCount: 3, peak: false },
  },
  // No almanac counterpart (dust) and no window to anchor to: a pill for it has nowhere to go but Coming up itself.
  {
    type: 'DUST', label: 'Saharan dust', date: TODAY, eventType: null, regions: [],
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
  origin: null,
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

const entry = (over = {}) => ({
  id: 'meteor:2026-08-12:2026-08-12',
  type: 'meteor',
  startDate: '2026-08-12',
  endDate: '2026-08-12',
  kind: 'ALMANAC',
  family: 'night-sky',
  title: 'Perseids',
  kindTag: 'Almanac',
  superlative: null,
  metric: null,
  prose: null,
  facts: [],
  threshold: null,
  action: { label: 'Show dark-sky spots for 12 Aug →', kind: 'dark-sky-spots', date: '2026-08-12' },
  coincidence: [],
  dayRanges: [],
  ...over,
});
const TIDE_ENTRY = entry({
  id: 'spring-tide:2026-08-08:2026-08-11',
  type: 'spring-tide',
  family: 'coastal',
  title: 'Spring tide run',
  startDate: TODAY,
  endDate: '2026-08-11',
  action: { label: 'Show coastal spots for 8 Aug →', kind: 'coastal-spots', date: TODAY },
});
const FEED = {
  builtFor: TODAY,
  bands: null,
  counts: { fixed: 2, forecast: 0, byFamily: { 'night-sky': 1, coastal: 1 } },
  conditions: [],
  entries: [entry(), TIDE_ENTRY],
};

let originalGetContext;

async function renderShell() {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  await act(async () => {
    render(
      <WindowFirstShell
        onOpenSettings={vi.fn()}
        onSignOut={vi.fn()}
        onShowOnMap={vi.fn()}
        onOpenMapTab={vi.fn()}
        locations={[]}
      />,
    );
  });
}

beforeEach(() => {
  getAlmanac.mockReset();
  getAlmanac.mockResolvedValue(FEED);
  Element.prototype.scrollIntoView = vi.fn();
  window.scrollTo = vi.fn();
});

afterEach(() => {
  if (originalGetContext) HTMLCanvasElement.prototype.getContext = originalGetContext;
  delete Element.prototype.scrollIntoView;
  cleanup();
  vi.restoreAllMocks();
});

// jsdom applies no Tailwind, so BOTH GlanceLabel spans count: the tab's name is "At a GlanceGlance" here.
const tab = (name) => screen.getByRole('tab', { name: name === 'At a Glance' ? /^At a Glance/ : name });

const pillFor = async (type) => (await screen.findAllByTestId('wf-glance-topic'))
  .find((b) => b.getAttribute('data-topic') === type);

describe('WindowFirstShell — a topic pill opens Coming up', () => {
  it('a pill with an almanac entry opens Coming up at that entry, ringed and focused', async () => {
    await renderShell();
    // Wait for the feed: until it is ready, no pill names a jump.
    await waitFor(() => expect(getAlmanac).toHaveBeenCalled());
    await waitFor(async () => expect(await pillFor('SPRING_TIDE')).toHaveAccessibleName(/shown in Coming up$/));

    await act(async () => { fireEvent.click(await pillFor('SPRING_TIDE')); });

    expect(tab('Coming up')).toHaveAttribute('aria-selected', 'true');
    const card = document.querySelector(`[data-entry-id="${TIDE_ENTRY.id}"]`);
    expect(card).toHaveClass('wf-cu-ent-highlight');
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1);
    // Found: no fallback scroll to the top.
    expect(window.scrollTo).not.toHaveBeenCalled();
  });

  it('⚠️ resets a Coming up filter that hides the entry, so the jump never lands on nothing', async () => {
    await renderShell();
    await waitFor(async () => expect(await pillFor('SPRING_TIDE')).toHaveAccessibleName(/shown in Coming up$/));

    // Filter Coming up to Night sky (hides the coastal spring tide), then go back to At a Glance.
    await act(async () => { fireEvent.click(tab('Coming up')); });
    const nightSky = screen.getAllByTestId('coming-up-chip').find((c) => within(c).queryByText('Night sky'));
    await act(async () => { fireEvent.click(nightSky); });
    expect(document.querySelector(`[data-entry-id="${TIDE_ENTRY.id}"]`)).toBeNull();
    await act(async () => { fireEvent.click(tab('At a Glance')); });

    await act(async () => { fireEvent.click(await pillFor('SPRING_TIDE')); });

    const all = screen.getAllByTestId('coming-up-chip').find((c) => within(c).queryByText('All'));
    expect(all).toHaveAttribute('aria-pressed', 'true');
    expect(document.querySelector(`[data-entry-id="${TIDE_ENTRY.id}"]`)).toHaveClass('wf-cu-ent-highlight');
  });

  it('a pill with no almanac counterpart opens the tab, scrolls to the top and focuses the Coming up tab', async () => {
    await renderShell();
    await act(async () => { fireEvent.click(await pillFor('DUST')); });
    await act(async () => { await Promise.resolve(); });

    expect(tab('Coming up')).toHaveAttribute('aria-selected', 'true');
    expect(window.scrollTo).toHaveBeenCalledWith({ top: 0 });
    expect(tab('Coming up')).toHaveFocus();
    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled();
  });

  it('the door opens Coming up the same way', async () => {
    await renderShell();
    await act(async () => { fireEvent.click(await screen.findByTestId('wf-glance-topics-door')); });
    await act(async () => { await Promise.resolve(); });

    expect(tab('Coming up')).toHaveAttribute('aria-selected', 'true');
    expect(tab('Coming up')).toHaveFocus();
  });

  it('names no jump until the feed is ready: while it loads, a tide pill falls through to its window', async () => {
    getAlmanac.mockReturnValue(new Promise(() => {}));
    await renderShell();
    const pill = await pillFor('SPRING_TIDE');
    expect(pill).not.toHaveAccessibleName(/shown in Coming up/);
    expect(pill).toHaveAccessibleName(/opens the At a Glance window$/);
  });

  it('a second press is a new request, answered again', async () => {
    await renderShell();
    await waitFor(async () => expect(await pillFor('SPRING_TIDE')).toHaveAccessibleName(/shown in Coming up$/));
    await act(async () => { fireEvent.click(await pillFor('SPRING_TIDE')); });
    await act(async () => { fireEvent.click(tab('At a Glance')); });
    await act(async () => { fireEvent.click(await pillFor('SPRING_TIDE')); });
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(2);
  });
});
