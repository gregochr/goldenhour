import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, cleanup } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { getAlmanac } from '../api/almanacApi.js';
import { markComingUpSeen } from '../api/settingsApi.js';
import { withFavourites } from './favouritesKit.jsx';

/**
 * The NEW badge on the "Further ahead ↓" jump and on Further ahead's heading, the `lastSeenAt` bootstrap
 * and `Mark seen` (coming-up plan D3/D4/D12, P5; one-screen-plan.md D6). It was the Coming up tab's badge.
 *
 * <p>Scoped the way {@code WindowFirstShellTabs.test.jsx} scopes itself — a shell-level JOIN
 * between the eagerly-fetched feed and the per-user `comingUpLastSeenDate` the context supplies —
 * so it lives beside that file rather than inside it, matching this codebase's one-concern-per-file
 * convention for the shell's test suite. The context is mocked wholesale, as every sibling shell
 * test file mocks it; `withContext` simulates the re-render a real state update would cause after
 * the shell calls `setComingUpLastSeenAt`.
 */
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

const renderShell = (overrides = {}, favourites = null) => {
  const useBriefing = vi.spyOn(briefingContext, 'useWindowFirstBriefing');
  useBriefing.mockReturnValue(ctx(overrides));
  const props = {
    onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(), locations: [],
  };
  const tree = () => {
    const shell = <WindowFirstShell {...props} />;
    return favourites ? withFavourites(shell, favourites) : shell;
  };
  const view = render(tree());
  const withContext = (next) => {
    useBriefing.mockReturnValue(ctx({ ...overrides, ...next }));
    view.rerender(tree());
  };
  return { ...view, withContext };
};

/** The observers Further ahead's heading installed, so a test can bring the heading into view. */
let observers;

/** Scrolls Further ahead's heading into view, which is when the one-off bootstrap fires (D6). */
const reachFurtherAhead = async () => {
  const heading = screen.getByTestId('further-ahead-heading');
  act(() => {
    observers.filter((observer) => observer.nodes.includes(heading))
      .forEach((observer) => observer.callback([{ isIntersecting: true }]));
  });
  await act(async () => { await Promise.resolve(); });
};

const settle = () => act(async () => { await Promise.resolve(); });

beforeEach(() => {
  observers = [];
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback) { this.callback = callback; this.nodes = []; observers.push(this); }

    observe(node) { this.nodes.push(node); }

    disconnect() { this.nodes = []; }
  });
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
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('WindowFirstShell — the NEW badge on the jump and the heading', () => {
  it('shows nothing when the reader has already seen every arrival', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-08' });
    await settle();

    expect(screen.queryByTestId('further-ahead-jump-badge')).toBeNull();
    expect(screen.queryByTestId('further-ahead-heading-badge')).toBeNull();
  });

  it('shows one count, on both the jump and the heading, when a new arrival clears the announce band', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();

    for (const id of ['further-ahead-jump-badge', 'further-ahead-heading-badge']) {
      const badge = screen.getByTestId(id);
      expect(badge).toHaveTextContent('1');
      expect(badge.className).not.toContain('wf-jump-badge-rare');
    }
  });

  it('shows a solid diamond, no number, when an arrival clears the interrupt band', async () => {
    getAlmanac.mockResolvedValue(FEED([INTERRUPT_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();

    for (const id of ['further-ahead-jump-badge', 'further-ahead-heading-badge']) {
      const badge = screen.getByTestId(id);
      expect(badge).toHaveTextContent('◆');
      expect(badge.className).toContain('wf-jump-badge-rare');
    }
  });

  it('never badges a FORECAST-kind entry, however high its bits', async () => {
    getAlmanac.mockResolvedValue(FEED([{ ...ANNOUNCE_ENTRY, kind: 'FORECAST', bits: 11.6 }]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();

    expect(screen.queryByTestId('further-ahead-jump-badge')).toBeNull();
  });

  it('never badges an interim (unconfirmed) entry, however high its bits', async () => {
    getAlmanac.mockResolvedValue(FEED([{ ...ANNOUNCE_ENTRY, interim: true, bits: 11.6 }]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();

    expect(screen.queryByTestId('further-ahead-jump-badge')).toBeNull();
  });

  it('is visible from the top of the screen, without the reader ever scrolling to Further ahead', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();

    expect(screen.getByTestId('this-week-heading')).toBeInTheDocument();
    expect(screen.getByTestId('this-week-bar')).toContainElement(screen.getByTestId('further-ahead-jump-badge'));
  });

  it('carries the count in the jump\'s aria-label, not just the badge span', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();

    expect(screen.getByRole('button', { name: 'Further ahead, 1 new date' })).toBe(screen.getByTestId('further-ahead-jump'));
    // The badge span is aria-hidden: the label is the only place a screen reader hears about it, so the name must
    // not ALSO run the badge text into the label by accident.
    expect(screen.getByTestId('further-ahead-jump-badge')).toHaveAttribute('aria-hidden', 'true');
  });

  it('says "dates" for more than one, and a rare date for an interrupt arrival', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY, { ...ANNOUNCE_ENTRY, id: 'second', bits: 8.0 }]));
    const first = renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();
    expect(screen.getByRole('button', { name: 'Further ahead, 2 new dates' })).toBeInTheDocument();
    first.unmount();

    getAlmanac.mockResolvedValue(FEED([INTERRUPT_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();
    expect(screen.getByRole('button', { name: 'Further ahead, one rare date added' })).toBeInTheDocument();
  });

  it('leaves the heading\'s own accessible name clean, whatever the badge says', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();

    expect(screen.getByTestId('further-ahead-heading')).toHaveAccessibleName('Further ahead · dates known in advance');
    expect(screen.getByTestId('further-ahead-heading-badge')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByTestId('this-week-heading')).toHaveAccessibleName('This week · forecast');
  });

  it('falls back to the plain jump name with no aria-label override when there is no badge', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-08' });
    await settle();

    const jump = screen.getByRole('button', { name: 'Further ahead' });
    expect(jump).toBe(screen.getByTestId('further-ahead-jump'));
    expect(jump).not.toHaveAttribute('aria-label');
  });

  it('presses through to Further ahead: scrolled into view, heading focused, nothing marked seen', async () => {
    Element.prototype.scrollIntoView = vi.fn();
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();

    fireEvent.click(screen.getByTestId('further-ahead-jump'));
    await settle();

    expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
    expect(screen.getByTestId('further-ahead-heading')).toHaveFocus();
    // Reaching the section is not "seen": only Mark seen clears the badge (owner, 2026-11-23).
    expect(markComingUpSeen).not.toHaveBeenCalled();
    expect(screen.getByTestId('further-ahead-jump-badge')).toBeInTheDocument();
    delete Element.prototype.scrollIntoView;
  });

  it('has no badge on a tab, because there is no tab: the badge is the jump\'s and the heading\'s', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();

    expect(screen.queryByTestId('coming-up-tab-badge')).toBeNull();
    expect(screen.queryAllByRole('tab')).toHaveLength(0);
  });
});

describe('WindowFirstShell — the Further ahead bootstrap write (plan D3, D6)', () => {
  it('fires once, the first time the heading reaches the viewport with a null last-seen date, quietly', async () => {
    getAlmanac.mockResolvedValue(FEED([]));
    const { withContext } = renderShell({ comingUpLastSeenDate: null });
    await settle();

    expect(markComingUpSeen).not.toHaveBeenCalled();

    await reachFurtherAhead();

    expect(markComingUpSeen).toHaveBeenCalledTimes(1);
    // The write settles asynchronously; the setter is called with whatever the response echoes
    // back, simulating what the real context would then re-render with.
    withContext({ comingUpLastSeenDate: TODAY });
    expect(screen.queryByTestId('further-ahead-jump-badge')).toBeNull();
  });

  it('does not fire again when the heading is reached a second time', async () => {
    getAlmanac.mockResolvedValue(FEED([]));
    renderShell({ comingUpLastSeenDate: null });
    await settle();
    await reachFurtherAhead();
    expect(markComingUpSeen).toHaveBeenCalledTimes(1);

    await reachFurtherAhead();

    expect(markComingUpSeen).toHaveBeenCalledTimes(1);
  });

  it('never fires while the last-seen date is merely unresolved (undefined, not null)', async () => {
    getAlmanac.mockResolvedValue(FEED([]));
    renderShell({ comingUpLastSeenDate: undefined });
    await settle();
    await reachFurtherAhead();

    // undefined means "settings request not answered yet or failed" (the same three-state shape
    // homePlace already uses) — treating it as "never seen" would double-fire the bootstrap once
    // the real value (possibly a real date) arrives.
    expect(markComingUpSeen).not.toHaveBeenCalled();
  });

  it('does not fire at all for an account that has already seen the feed', async () => {
    getAlmanac.mockResolvedValue(FEED([]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();
    await reachFurtherAhead();

    expect(markComingUpSeen).not.toHaveBeenCalled();
  });

  it('does not clear an account that HAS a last-seen date by scrolling past: only Mark seen does', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();
    await reachFurtherAhead();

    expect(markComingUpSeen).not.toHaveBeenCalled();
    expect(screen.getByTestId('further-ahead-jump-badge')).toBeInTheDocument();
  });

  it('retries the NEXT time the heading is reached after a failed write, but does not loop on this one', async () => {
    getAlmanac.mockResolvedValue(FEED([]));
    markComingUpSeen.mockRejectedValueOnce(new Error('502'));
    renderShell({ comingUpLastSeenDate: null });
    await settle();

    await reachFurtherAhead();
    expect(markComingUpSeen).toHaveBeenCalledTimes(1);

    // Nothing intersected again, so nothing retried: the reset guard must not by itself refire the write.
    await settle();
    expect(markComingUpSeen).toHaveBeenCalledTimes(1);

    markComingUpSeen.mockResolvedValue({ comingUpLastSeenDate: TODAY });
    await reachFurtherAhead();

    expect(markComingUpSeen).toHaveBeenCalledTimes(2);
  });

  it('writes nothing in a browser that cannot observe, because the bootstrap is a write', async () => {
    vi.stubGlobal('IntersectionObserver', undefined);
    getAlmanac.mockResolvedValue(FEED([]));
    renderShell({ comingUpLastSeenDate: null });
    await settle();

    expect(markComingUpSeen).not.toHaveBeenCalled();
  });
});

describe('WindowFirstShell — Mark seen', () => {
  it('optimistically publishes today\'s date and fires the write', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    const { withContext } = renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();

    fireEvent.click(screen.getByTestId('coming-up-since-mark-seen'));

    expect(markComingUpSeen).toHaveBeenCalledTimes(1);
    const setter = briefingContext.useWindowFirstBriefing().setComingUpLastSeenAt;
    expect(setter).toHaveBeenCalledWith(TODAY);

    // Simulate the re-render the real context would cause once that setter's state lands.
    withContext({ comingUpLastSeenDate: TODAY });
    expect(screen.queryByTestId('further-ahead-jump-badge')).toBeNull();
    expect(screen.queryByTestId('coming-up-since')).toBeNull();
  });

  it('clears both badges and the since-line together, not the badge alone', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    const { withContext } = renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();

    expect(screen.getByTestId('further-ahead-jump-badge')).toBeInTheDocument();
    expect(screen.getByTestId('further-ahead-heading-badge')).toBeInTheDocument();
    expect(screen.getByTestId('coming-up-since')).toBeInTheDocument();

    withContext({ comingUpLastSeenDate: TODAY });

    expect(screen.queryByTestId('further-ahead-jump-badge')).toBeNull();
    expect(screen.queryByTestId('further-ahead-heading-badge')).toBeNull();
    expect(screen.queryByTestId('coming-up-since')).toBeNull();
  });

  it('leaves the optimistic date in place even when the write itself fails', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY]));
    markComingUpSeen.mockRejectedValue(new Error('502'));
    const { withContext } = renderShell({ comingUpLastSeenDate: '2026-08-01' });
    await settle();

    fireEvent.click(screen.getByTestId('coming-up-since-mark-seen'));
    await settle();

    const setter = briefingContext.useWindowFirstBriefing().setComingUpLastSeenAt;
    expect(setter).toHaveBeenCalledWith(TODAY);
    // The design's own safe-failure bias: a dropped write must not flash the badge back on.
    withContext({ comingUpLastSeenDate: TODAY });
    expect(screen.queryByTestId('further-ahead-jump-badge')).toBeNull();
  });
});

describe('WindowFirstShell — the watched count on the jump (screen-spec.md D2)', () => {
  const MOON = {
    ...ANNOUNCE_ENTRY, id: 'supermoon:2026-08-20:2026-08-20', startDate: '2026-08-20', endDate: '2026-08-20',
  };

  it('shows the mark and the number beside the jump, and names them: ", N watching"', async () => {
    getAlmanac.mockResolvedValue(FEED([MOON]));
    renderShell({ comingUpLastSeenDate: '2026-08-08' }, { dates: ['supermoon:2026-08-20'] });
    await settle();

    const count = screen.getByTestId('further-ahead-jump-watching');
    expect(count).toHaveTextContent('1');
    expect(count.querySelector('[data-testid="bookmark-mark"]')).not.toBeNull();
    expect(count).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByTestId('further-ahead-jump')).toHaveAccessibleName('Further ahead, 1 watching');
  });

  it('sits beside the NEW badge, and the name carries both', async () => {
    getAlmanac.mockResolvedValue(FEED([ANNOUNCE_ENTRY, MOON]));
    renderShell({ comingUpLastSeenDate: '2026-08-01' }, { dates: ['supermoon:2026-08-20'] });
    await settle();

    expect(screen.getByTestId('further-ahead-jump-watching')).toBeInTheDocument();
    expect(screen.getByTestId('further-ahead-jump-badge')).toBeInTheDocument();
    expect(screen.getByTestId('further-ahead-jump')).toHaveAccessibleName('Further ahead, 2 new dates, 1 watching');
  });

  it('is never "0": nothing watched, a Lite reader, a list that has not landed or failed, or a date that has passed', async () => {
    getAlmanac.mockResolvedValue(FEED([MOON]));
    for (const favourites of [
      { dates: [] },
      { status: 'locked', dates: ['supermoon:2026-08-20'] },
      { status: 'loading', dates: ['supermoon:2026-08-20'] },
      { status: 'error', dates: ['supermoon:2026-08-20'] },
      { dates: ['supermoon:2026-08-01'] },
    ]) {
      const { unmount } = renderShell({ comingUpLastSeenDate: '2026-08-08' }, favourites);
      await settle();
      expect(screen.queryByTestId('further-ahead-jump-watching')).toBeNull();
      expect(screen.getByTestId('further-ahead-jump')).not.toHaveAttribute('aria-label');
      unmount();
    }
  });

  it('counts a watch the feed no longer names by its own date, once the feed has arrived', async () => {
    getAlmanac.mockResolvedValue(FEED([]));
    renderShell({ comingUpLastSeenDate: '2026-08-08' }, { dates: ['meteor:2026-09-14', 'king-tide:2026-10-05'] });
    await settle();
    expect(screen.getByTestId('further-ahead-jump-watching')).toHaveTextContent('2');
  });
});
