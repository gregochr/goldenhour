import React from 'react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, cleanup, waitFor } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { getAlmanac } from '../api/almanacApi.js';

/**
 * The shell's join between the days-ahead lanes and Further ahead (one-screen-plan.md O2, D5). A lane that carries an
 * almanac entry's jump draws that entry on the screen, so Further ahead leaves it out and the lane's tap goes to its
 * window; an entry no lane carries stays listed; a lane with nowhere to go is text. And the "Further ahead ↓" jump
 * stores the {@code comingUpFocus} request {@code openFurtherAhead} makes, which {@code FurtherAhead} answers.
 *
 * <p>Scoped like {@code WindowFirstShellPlanWindowDoor.test.jsx}: what only the SHELL can be wrong about is that
 * the strip's reported lanes reach the section, and that the request reaches it and is cleared exactly once.
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

async function renderShell(ctxOverrides = {}) {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(ctxOverrides));
  const onOpenMapTab = vi.fn();
  await act(async () => {
    render(
      <WindowFirstShell
        onOpenSettings={vi.fn()}
        onSignOut={vi.fn()}
        onShowOnMap={vi.fn()}
        onOpenMapTab={onOpenMapTab}
        locations={[]}
      />,
    );
  });
  return { onOpenMapTab };
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


const pillFor = async (type) => (await screen.findAllByTestId('wf-glance-lane'))
  .find((b) => b.getAttribute('data-topic') === type);

/** The entries Further ahead lists, by id. */
const listedIds = () => Array.from(document.querySelectorAll('[data-testid="further-ahead"] [data-entry-id]'))
  .map((node) => node.getAttribute('data-entry-id'));

describe('WindowFirstShell — Further ahead lists what This week does not draw as a lane (D5)', () => {
  it('lists every entry until the strip has drawn its lanes, then leaves out the one a lane carries', async () => {
    await renderShell();
    await waitFor(() => expect(getAlmanac).toHaveBeenCalled());
    // The tide has a lane (it carries its entry's jump); the meteor shower has none, so it stays listed.
    await waitFor(() => expect(listedIds()).toEqual(['meteor:2026-08-12:2026-08-12']));
    expect(document.querySelector(`[data-entry-id="${TIDE_ENTRY.id}"]`)).toBeNull();
  });

  it('keeps an entry listed when no lane carries it, in date order', async () => {
    getAlmanac.mockResolvedValue({ ...FEED, entries: [entry({ id: 'meteor:2026-09-01', startDate: '2026-09-01', endDate: '2026-09-01' }), TIDE_ENTRY] });
    await renderShell();
    await waitFor(() => expect(listedIds()).toEqual(['meteor:2026-09-01']));
  });

  it('counts the chips over what is listed', async () => {
    await renderShell();
    await waitFor(() => expect(listedIds()).toHaveLength(1));
    const all = screen.getAllByTestId('coming-up-chip').find((c) => c.getAttribute('data-chip') === 'all');
    const coastal = screen.getAllByTestId('coming-up-chip').find((c) => c.getAttribute('data-chip') === 'coastal');
    expect(all.querySelector('.wf-cu-chip-count').textContent).toBe('1');
    expect(coastal.querySelector('.wf-cu-chip-count').textContent).toBe('0');
  });
});

describe('WindowFirstShell — one badge, counted from what Further ahead lists', () => {
  it('shows the same number on the jump and the heading, leaving out a new entry a lane already draws', async () => {
    const fresh = { bits: 8.2, interim: false, enteredWindow: '2026-08-08' };
    getAlmanac.mockResolvedValue({
      ...FEED,
      bands: { list: 5, announce: 7.5, interrupt: 10 },
      entries: [entry(fresh), { ...TIDE_ENTRY, ...fresh }],
    });
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx({ comingUpLastSeenDate: '2026-08-01' }));
    await act(async () => {
      render(<WindowFirstShell onOpenSettings={vi.fn()} onSignOut={vi.fn()} onShowOnMap={vi.fn()} onOpenMapTab={vi.fn()} locations={[]} />);
    });
    // The tide has a lane (graduated), so only the meteor shower is listed and only it is counted.
    await waitFor(() => expect(listedIds()).toEqual(['meteor:2026-08-12:2026-08-12']));
    expect(screen.getByTestId('further-ahead-jump-badge')).toHaveTextContent('1');
    expect(screen.getByTestId('further-ahead-heading-badge')).toHaveTextContent('1');
    expect(screen.getByRole('button', { name: 'Further ahead, 1 new date' })).toBeInTheDocument();
  });
});

describe('WindowFirstShell — an entry never vanishes from both places', () => {
  it('lists every entry when the strip draws nothing (no spots), because no lane carries any', async () => {
    await renderShell({ heatSpots: [] });
    await waitFor(() => expect(getAlmanac).toHaveBeenCalled());
    await waitFor(() => expect(listedIds()).toContain(TIDE_ENTRY.id));
    expect(listedIds()).toContain('meteor:2026-08-12:2026-08-12');
  });
});

describe('WindowFirstShell — a days-ahead lane goes to its window, never to Coming up', () => {
  it('a lane with an almanac entry opens its window on the map, with the topic emphasised', async () => {
    const { onOpenMapTab } = await renderShell();
    await waitFor(async () => expect(await pillFor('SPRING_TIDE')).toHaveAccessibleName(/opens that window on the map$/));

    await act(async () => { fireEvent.click(await pillFor('SPRING_TIDE')); });

    expect(onOpenMapTab).toHaveBeenCalledTimes(1);
    // Nothing scrolled to an entry, nothing moved to a tab.
    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled();
    expect(screen.getByTestId('this-week-heading')).toBeInTheDocument();
  });

  it('names no Coming up destination on any lane', async () => {
    await renderShell();
    await waitFor(() => expect(getAlmanac).toHaveBeenCalled());
    await waitFor(async () => expect(await pillFor('SPRING_TIDE')).toBeTruthy());
    for (const lane of await screen.findAllByTestId('wf-glance-lane')) {
      expect(lane.textContent).not.toMatch(/Coming up/);
    }
  });

  it('a lane with no route (dust, no window to anchor to) is text, not a button that opens nothing', async () => {
    await renderShell();
    const dust = await pillFor('DUST');
    expect(dust.tagName).toBe('DIV');
    await act(async () => { fireEvent.click(dust); });
    expect(screen.getByTestId('this-week-heading')).toBeInTheDocument();
    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled();
  });
});

describe('WindowFirstShell — a Further ahead card\'s "plan" action goes up to This week', () => {
  it('scrolls This week\'s heading into view and focuses it, staying on the Plan pane', async () => {
    getAlmanac.mockResolvedValue({
      ...FEED,
      entries: [entry({
        id: 'meteor:2026-12-01', startDate: '2026-12-01', endDate: '2027-02-28',
        action: { label: 'See the plan for 1 Dec →', kind: 'plan', date: '2026-12-01' },
      })],
    });
    await renderShell();
    await waitFor(() => expect(screen.getByTestId('coming-up-card')).toBeInTheDocument());

    await act(async () => { fireEvent.click(screen.getByTestId('coming-up-card')); });

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('this-week-heading')).toHaveFocus();
    expect(screen.getByTestId('this-week-heading')).toBeInTheDocument();
  });
});

describe('WindowFirstShell — the "Further ahead ↓" jump', () => {
  it('scrolls Further ahead\'s heading to the top and focuses it, once the request is answered', async () => {
    await renderShell();
    await waitFor(() => expect(getAlmanac).toHaveBeenCalled());

    await act(async () => { fireEvent.click(screen.getByTestId('further-ahead-jump')); });
    await act(async () => { await Promise.resolve(); });

    // The request names no entry, so the section answers "not found" and the shell falls back to its heading.
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('further-ahead-heading')).toHaveFocus();
    expect(screen.getByTestId('this-week-heading')).toBeInTheDocument();
  });

  it('a second press is a new request, answered again', async () => {
    await renderShell();
    await act(async () => { fireEvent.click(screen.getByTestId('further-ahead-jump')); });
    await act(async () => { fireEvent.click(screen.getByTestId('further-ahead-jump')); });
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(2);
  });

  it('opens the section at the heading even while the feed has not loaded', async () => {
    getAlmanac.mockReturnValue(new Promise(() => {}));
    await renderShell();
    await act(async () => { fireEvent.click(screen.getByTestId('further-ahead-jump')); });
    expect(screen.getByTestId('further-ahead-heading')).toHaveFocus();
  });
});
