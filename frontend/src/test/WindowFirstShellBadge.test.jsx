import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, cleanup } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { getAlmanac } from '../api/almanacApi.js';
import { markComingUpSeen } from '../api/settingsApi.js';

/**
 * The Coming-up tab badge, `lastSeenAt` bootstrap and `Mark seen` (plan D3/D4/D12, P5).
 *
 * <p>Scoped the way {@code WindowFirstShellTabs.test.jsx} scopes itself — a shell-level JOIN
 * between the eagerly-fetched feed and the per-user `comingUpLastSeenDate` the context supplies —
 * so it lives beside that file rather than inside it, matching this codebase's one-concern-per-file
 * convention for the shell's test suite. The context is mocked wholesale, as every sibling shell
 * test file mocks it; `withContext` simulates the re-render a real state update would cause after
 * the shell calls `setComingUpLastSeenAt`.
 */
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));

vi.mock('../api/almanacApi.js', () => ({
  getAlmanac: vi.fn(),
  ALMANAC_DAYS: 90,
}));

vi.mock('../api/settingsApi.js', () => ({
  markComingUpSeen: vi.fn(),
}));

const TODAY = '2026-08-08';

/** Bands matching the backend's own census-set edges (plan D4) — not load-bearing for these
 * tests, which only need SOME edges, but kept realistic rather than inventing arbitrary ones. */
const BANDS = { list: 5.0, announce: 7.5, interrupt: 10.0 };

/** Clears the announce band, arrived after 1 Aug — used across the announce-state tests. */
const ANNOUNCE_ENTRY = {
  id: 'supermoon:2026-08-08:2026-08-08',
  startDate: '2026-08-08',
  endDate: '2026-08-08',
  kind: 'ALMANAC',
  type: 'supermoon',
  family: 'sun-moon',
  title: 'Supermoon',
  detail: 'The full moon coincides with its closest approach.',
  meta: {},
  enteredWindow: '2026-08-08',
  bits: 8.2,
  interim: false,
  scoreNote: 'Rarity alone carries it over the top contour.',
};

/** Clears the interrupt band — used across the interrupt-state tests. */
const INTERRUPT_ENTRY = {
  id: 'eclipse:2027-08-02:2027-08-02',
  startDate: '2027-08-02',
  endDate: '2027-08-02',
  kind: 'ALMANAC',
  type: 'eclipse',
  family: 'eclipse',
  title: 'Solar eclipse',
  detail: 'A rare total solar eclipse.',
  meta: {},
  enteredWindow: '2026-08-08',
  bits: 11.6,
  interim: false,
  scoreNote: 'Annual, so rarity alone carries it over the top contour.',
};

const FEED = (entries) => ({ builtFor: TODAY, bands: BANDS, counts: null, conditions: [], entries });

const card = () => ({
  key: `${TODAY}:SUNSET`,
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
  spots: [],
  allSpots: [],
  reachTotal: 0,
});

const ctx = (overrides = {}) => {
  const cards = overrides.windowCards ?? [card()];
  return {
    briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [] },
    loading: false,
    heatStripCards: [],
    heatSpots: [],
    heatPointSets: new Map(),
    windowCards: cards,
    paneItems: cards.map((c) => ({ kind: 'card', key: c.key, card: c })),
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
    comingUpLastSeenDate: undefined,
    setComingUpLastSeenAt: vi.fn(),
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
    ...overrides,
  };
};

const renderShell = (overrides = {}) => {
  const useBriefing = vi.spyOn(briefingContext, 'useWindowFirstBriefing');
  useBriefing.mockReturnValue(ctx(overrides));
  const props = {
    onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(), locations: [],
  };
  const view = render(<WindowFirstShell {...props} />);
  const withContext = (next) => {
    useBriefing.mockReturnValue(ctx({ ...overrides, ...next }));
    view.rerender(<WindowFirstShell {...props} />);
  };
  return { ...view, withContext };
};

const tab = (name) => screen.getByRole('tab', { name: new RegExp(`^${name}`) });

const openComingUp = async () => {
  fireEvent.click(screen.getByRole('tab', { name: /^Coming up/ }));
  await act(async () => { await Promise.resolve(); });
};

beforeEach(() => {
  getAlmanac.mockReset();
  markComingUpSeen.mockReset();
  markComingUpSeen.mockResolvedValue({ comingUpLastSeenDate: TODAY });
  window.matchMedia = (query) => ({
    matches: false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('WindowFirstShell — the tab badge', () => {
  it('shows nothing when the reader has already seen every arrival', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-08' });
    await act(async () => { await Promise.resolve(); });

    expect(screen.queryByTestId('coming-up-tab-badge')).toBeNull();
  });

  it('shows a count when a new arrival clears the announce band', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await act(async () => { await Promise.resolve(); });

    const badge = screen.getByTestId('coming-up-tab-badge');
    expect(badge).toHaveTextContent('1');
    expect(badge.className).not.toContain('wf-tab-badge-rare');
  });

  it('shows a solid diamond, no number, when an arrival clears the interrupt band', async () => {
    getAlmanac.mockResolvedValue(FEED([INTERRUPT_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await act(async () => { await Promise.resolve(); });

    const badge = screen.getByTestId('coming-up-tab-badge');
    expect(badge).toHaveTextContent('◆');
    expect(badge.className).toContain('wf-tab-badge-rare');
  });

  it('never badges a FORECAST-kind entry, however high its bits', async () => {
    getAlmanac.mockResolvedValue(FEED([{ ...ANNOUNCE_ENTRY, kind: 'FORECAST', bits: 11.6 }]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await act(async () => { await Promise.resolve(); });

    expect(screen.queryByTestId('coming-up-tab-badge')).toBeNull();
  });

  it('never badges an interim (unconfirmed) entry, however high its bits', async () => {
    getAlmanac.mockResolvedValue(FEED([{ ...ANNOUNCE_ENTRY, interim: true, bits: 11.6 }]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await act(async () => { await Promise.resolve(); });

    expect(screen.queryByTestId('coming-up-tab-badge')).toBeNull();
  });

  it('is visible from the Plan tab, without the reader ever opening Coming up (D13\'s whole point)', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await act(async () => { await Promise.resolve(); });

    expect(screen.getByRole('tab', { name: 'Plan' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('coming-up-tab-badge')).toBeInTheDocument();
  });

  it('carries the count in the tab button\'s aria-label, not just the badge span', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await act(async () => { await Promise.resolve(); });

    expect(screen.getByRole('tab', { name: 'Coming up, 1 new event' })).toBeInTheDocument();
    // The badge span itself is aria-hidden — the aria-label above is the only place a screen
    // reader hears about it, so the accessible name must not ALSO run the badge text into the
    // label by accident (which would read "Coming up, 1 new event1").
    expect(screen.getByTestId('coming-up-tab-badge')).toHaveAttribute('aria-hidden', 'true');
  });

  it('falls back to the plain tab name with no aria-label override when there is no badge', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-08' });
    await act(async () => { await Promise.resolve(); });

    expect(screen.getByRole('tab', { name: 'Coming up' })).toBeInTheDocument();
  });
});

describe('WindowFirstShell — the Coming-up bootstrap write (plan D3)', () => {
  it('fires once on the first open with a null last-seen date, quietly', async () => {
    getAlmanac.mockResolvedValue(FEED([]));
    const { withContext } = renderShell({ comingUpLastSeenDate: null });
    await act(async () => { await Promise.resolve(); });

    expect(markComingUpSeen).not.toHaveBeenCalled();

    await openComingUp();

    expect(markComingUpSeen).toHaveBeenCalledTimes(1);
    // The write settles asynchronously; the setter is called with whatever the response echoes
    // back, simulating what the real context would then re-render with.
    withContext({ comingUpLastSeenDate: TODAY });
    expect(screen.queryByTestId('coming-up-tab-badge')).toBeNull();
  });

  it('does not fire again on a later open of the same tab', async () => {
    getAlmanac.mockResolvedValue(FEED([]));
    renderShell({ comingUpLastSeenDate: null });
    await act(async () => { await Promise.resolve(); });
    await openComingUp();
    expect(markComingUpSeen).toHaveBeenCalledTimes(1);

    fireEvent.click(tab('Plan'));
    await openComingUp();

    expect(markComingUpSeen).toHaveBeenCalledTimes(1);
  });

  it('never fires while the last-seen date is merely unresolved (undefined, not null)', async () => {
    getAlmanac.mockResolvedValue(FEED([]));
    renderShell({ comingUpLastSeenDate: undefined });
    await act(async () => { await Promise.resolve(); });
    await openComingUp();

    // undefined means "settings request not answered yet or failed" (the same three-state shape
    // homePlace already uses) — treating it as "never seen" would double-fire the bootstrap once
    // the real value (possibly a real date) arrives.
    expect(markComingUpSeen).not.toHaveBeenCalled();
  });

  it('does not fire at all for an account that has already seen the feed', async () => {
    getAlmanac.mockResolvedValue(FEED([]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await act(async () => { await Promise.resolve(); });
    await openComingUp();

    expect(markComingUpSeen).not.toHaveBeenCalled();
  });

  it('retries on the NEXT visit after a failed write, but does not loop on this one', async () => {
    getAlmanac.mockResolvedValue(FEED([]));
    markComingUpSeen.mockRejectedValueOnce(new Error('502'));
    renderShell({ comingUpLastSeenDate: null });
    await act(async () => { await Promise.resolve(); });

    await openComingUp();
    expect(markComingUpSeen).toHaveBeenCalledTimes(1);

    // Still sitting on the SAME open tab — no dependency the effect reads has changed, so a reset
    // guard must not by itself refire the write.
    await act(async () => { await Promise.resolve(); });
    expect(markComingUpSeen).toHaveBeenCalledTimes(1);

    markComingUpSeen.mockResolvedValue({ comingUpLastSeenDate: TODAY });
    fireEvent.click(tab('Plan'));
    await openComingUp();

    expect(markComingUpSeen).toHaveBeenCalledTimes(2);
  });
});

describe('WindowFirstShell — Mark seen', () => {
  it('optimistically publishes today\'s date and fires the write', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    const { withContext } = renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await act(async () => { await Promise.resolve(); });
    await openComingUp();

    fireEvent.click(screen.getByTestId('coming-up-since-mark-seen'));

    expect(markComingUpSeen).toHaveBeenCalledTimes(1);
    const setter = briefingContext.useWindowFirstBriefing().setComingUpLastSeenAt;
    expect(setter).toHaveBeenCalledWith(TODAY);

    // Simulate the re-render the real context would cause once that setter's state lands.
    withContext({ comingUpLastSeenDate: TODAY });
    expect(screen.queryByTestId('coming-up-tab-badge')).toBeNull();
    expect(screen.queryByTestId('coming-up-since')).toBeNull();
  });

  it('clears the badge and the since-line together, not the badge alone', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    const { withContext } = renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await act(async () => { await Promise.resolve(); });
    await openComingUp();

    expect(screen.getByTestId('coming-up-tab-badge')).toBeInTheDocument();
    expect(screen.getByTestId('coming-up-since')).toBeInTheDocument();

    withContext({ comingUpLastSeenDate: TODAY });

    expect(screen.queryByTestId('coming-up-tab-badge')).toBeNull();
    expect(screen.queryByTestId('coming-up-since')).toBeNull();
  });

  it('leaves the optimistic date in place even when the write itself fails', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    markComingUpSeen.mockRejectedValue(new Error('502'));
    const { withContext } = renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await act(async () => { await Promise.resolve(); });
    await openComingUp();

    fireEvent.click(screen.getByTestId('coming-up-since-mark-seen'));
    await act(async () => { await Promise.resolve(); });

    const setter = briefingContext.useWindowFirstBriefing().setComingUpLastSeenAt;
    expect(setter).toHaveBeenCalledWith(TODAY);
    // The design's own safe-failure bias: a dropped write must not flash the badge back on.
    withContext({ comingUpLastSeenDate: TODAY });
    expect(screen.queryByTestId('coming-up-tab-badge')).toBeNull();
  });
});
