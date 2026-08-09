import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { getAlmanac } from '../api/almanacApi.js';

/**
 * The tab bar the shell became at P13, and the Coming-up pane hanging off it.
 *
 * <p>Scoped the way {@code WindowFirstShellSheet.test.jsx} scopes itself, and for the same reason:
 * the shell has never had one general suite, and every rule here is about how the shell JOINS two
 * things — which pane a tab controls, what a tab change does to the lens bar, and when the feed is
 * allowed to be fetched. None of them is reachable from a component's own test.
 *
 * <p>The doors are stubbed for the reason that file gives: mounting them fires an astro request per
 * visible date, and this file is about neither.
 */
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));

vi.mock('../api/almanacApi.js', () => ({
  getAlmanac: vi.fn(),
  ALMANAC_DAYS: 90,
}));

const TODAY = '2026-08-08';

const FEED = [{
  startDate: '2026-08-12',
  endDate: '2026-08-12',
  kind: 'ALMANAC',
  type: 'meteor',
  title: 'Perseids',
  detail: 'Perseids peaks, around 100 meteors an hour at best under a dark sky.',
  meta: { zhr: '100', radiant: 'NE', bestHours: '01:00–04:00', moonIllumination: '0%' },
}];

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
    railTiles: [],
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
    ...overrides,
  };
};

const renderShell = (overrides = {}) => {
  const useBriefing = vi.spyOn(briefingContext, 'useWindowFirstBriefing');
  useBriefing.mockReturnValue(ctx(overrides));
  const props = {
    onExit: vi.fn(), onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(), locations: [],
  };
  const view = render(<WindowFirstShell {...props} />);
  /** Hands the shell a different briefing context, the way the provider would on a poll. */
  const withContext = (next) => {
    useBriefing.mockReturnValue(ctx({ ...overrides, ...next }));
    view.rerender(<WindowFirstShell {...props} />);
  };
  return { ...props, ...view, withContext };
};

const tab = (name) => screen.getByRole('tab', { name });

/** Selects Coming up and lets the fetch settle, which is what actually paints the rows. */
const openComingUp = async () => {
  fireEvent.click(tab('Coming up'));
  await act(async () => { await Promise.resolve(); });
};

beforeEach(() => {
  localStorage.clear();
  // Re-asserted here rather than inherited: `vi.clearAllMocks` clears calls, not implementations,
  // so a resolved value set in one test is still in force in the next.
  getAlmanac.mockReset();
  getAlmanac.mockResolvedValue(FEED);
  window.matchMedia = (query) => ({
    matches: false,
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

describe('WindowFirstShell — the tab bar', () => {
  it('offers exactly Plan and Coming up, and no tab whose pane does not exist', () => {
    // The design draws four. Map and Manage arrive when this subtree takes over view state; a tab
    // that renders nothing is a demo control and §6 bans those.
    renderShell();

    const tabs = screen.getAllByRole('tab');
    expect(tabs.map((t) => t.textContent.trim())).toEqual(['◉ Plan', 'Coming up']);
    expect(tab('Plan')).toHaveAccessibleName('Plan');
    expect(tab('Coming up')).toHaveAccessibleName('Coming up');
  });

  it('opens on Plan, because the question on arriving is about tonight', () => {
    renderShell();

    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
    expect(tab('Coming up')).toHaveAttribute('aria-selected', 'false');
  });

  it('ties BOTH tabs to their panel, in both directions, whichever is selected', () => {
    // Without the pairing a screen reader is told there is a tab list and then given no way to
    // learn what either tab controls — which is what the bar was through P12.
    //
    // Both tabs, and in the DEFAULT state: an aria-controls whose target is not in the document
    // resolves to nothing, so while the Coming up panel was mounted only when selected, the half of
    // the pairing that was missing was always the tab the reader had not reached yet. Asserting
    // only the selected tab's pairing is what let that through.
    renderShell();

    for (const [name, testId] of [['Plan', 'window-first-pane'], ['Coming up', 'window-first-coming-up']]) {
      const panel = screen.getByTestId(testId);
      expect(panel.id).not.toBe('');
      expect(tab(name)).toHaveAttribute('aria-controls', panel.id);
      expect(panel).toHaveAttribute('aria-labelledby', tab(name).id);
      // The id the tab points at must actually resolve, which is the thing that was broken.
      expect(document.getElementById(tab(name).getAttribute('aria-controls'))).toBe(panel);
    }
  });

  it('is one tab stop, not two, so a keyboard user does not walk the whole bar', () => {
    // Roving tabindex. Without it every tab added to this bar costs every keyboard user another
    // press to reach the pane.
    renderShell();

    expect(tab('Plan')).toHaveAttribute('tabindex', '0');
    expect(tab('Coming up')).toHaveAttribute('tabindex', '-1');
  });
});

describe('WindowFirstShell — moving between tabs', () => {
  it('shows exactly one panel at a time, and both are always in the document', async () => {
    // Both panels are mounted for the aria-controls pairing, so "is it in the document" no longer
    // distinguishes anything — visibility is the assertion that still can fail.
    renderShell();
    expect(screen.getByTestId('window-first-pane')).toBeVisible();
    expect(screen.getByTestId('window-first-coming-up')).not.toBeVisible();

    await openComingUp();

    expect(screen.getByTestId('window-first-coming-up')).toBeVisible();
    expect(screen.getByTestId('window-first-pane')).not.toBeVisible();
    expect(tab('Coming up')).toHaveAttribute('aria-selected', 'true');
  });

  it('swaps the Plan pane’s display utility with the tab, not just its hidden attribute', async () => {
    // What this actually protects, stated correctly — an earlier version of this comment claimed
    // the class was load-bearing for hiding, and it is not: preflight's
    // `[hidden]:where(...) { display: none !important }` is author-origin AND important, so the
    // attribute alone hides the pane whatever `.flex` says (verified on the running app).
    //
    // The class still needs pinning, for a different failure. jsdom applies no CSS, so every other
    // assertion in this file resolves from the attribute alone — which leaves the ternary that
    // picks `flex` vs `hidden` completely untested. Invert it and the Plan pane renders
    // `display: none` on first paint while `hidden` is false: a blank screen on load, in a browser,
    // with a green suite. This is the only assertion that catches that.
    renderShell();
    // Plan selected: it lays out, the feed carries the display rule that hides it.
    expect(screen.getByTestId('window-first-pane').className).toContain('flex');
    expect(screen.getByTestId('window-first-pane').className).not.toContain('hidden');
    expect(screen.getByTestId('window-first-coming-up').className).toContain('hidden');

    await openComingUp();

    // And the other way round. `hidden` must REPLACE the display utility on the Plan pane, not sit
    // beside it — Tailwind's `.flex` and `.hidden` are the same property, and which wins would then
    // be source order rather than intent.
    expect(screen.getByTestId('window-first-pane').className).toContain('hidden');
    expect(screen.getByTestId('window-first-pane').className).not.toContain('flex flex-col');
    expect(screen.getByTestId('window-first-coming-up').className).not.toContain('hidden');
  });

  it('keeps the Plan pane mounted, so returning to it costs no requests', async () => {
    // `WindowFirstDoors` fires an astro request per visible date on mount. Unmounting the pane on
    // every tab change would re-fire all of them on every change back — which is what `ManageView`
    // does to its sub-views and the behaviour not to copy here.
    renderShell();
    await openComingUp();

    expect(screen.getByTestId('window-first-pane')).toBeInTheDocument();
    expect(screen.getByTestId('stub-doors')).toBeInTheDocument();
  });

  it('takes the Plan pane out of the accessibility tree while it is not selected', async () => {
    // Hidden, not merely off-screen. The tab pattern requires an unselected panel to be
    // unreachable, and a CSS-only hide would leave it findable by a screen reader.
    renderShell();
    await openComingUp();

    expect(screen.getByTestId('window-first-pane')).toHaveAttribute('hidden');
  });

  it('moves selection and focus together with the arrow keys, wrapping at both ends', async () => {
    // Selection follows focus, which is the authoring-practices default and is right here because
    // both panes are cheap. This is the first roving-tabindex implementation in the codebase —
    // there was nothing to copy, so the whole contract is pinned.
    renderShell();
    tab('Plan').focus();

    fireEvent.keyDown(tab('Plan'), { key: 'ArrowRight' });
    await act(async () => { await Promise.resolve(); });
    expect(tab('Coming up')).toHaveAttribute('aria-selected', 'true');
    expect(tab('Coming up')).toHaveFocus();

    fireEvent.keyDown(tab('Coming up'), { key: 'ArrowRight' });
    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
    expect(tab('Plan')).toHaveFocus();

    fireEvent.keyDown(tab('Plan'), { key: 'ArrowLeft' });
    await act(async () => { await Promise.resolve(); });
    expect(tab('Coming up')).toHaveAttribute('aria-selected', 'true');
  });

  it('jumps to the ends with Home and End', async () => {
    renderShell();
    tab('Plan').focus();

    fireEvent.keyDown(tab('Plan'), { key: 'End' });
    await act(async () => { await Promise.resolve(); });
    expect(tab('Coming up')).toHaveAttribute('aria-selected', 'true');

    fireEvent.keyDown(tab('Coming up'), { key: 'Home' });
    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
  });

  it('leaves a modified arrow to the browser, so Alt+Left is still Back', () => {
    // The bar binds the UNMODIFIED keys only. Alt+Left and Cmd+Left are the browser's Back and
    // Ctrl/Cmd+Home is "top of document"; swallowing one of those to change tab is worse than not
    // handling the key at all, and preventDefault was unconditional before this.
    renderShell();
    tab('Plan').focus();

    for (const modifier of ['altKey', 'ctrlKey', 'metaKey', 'shiftKey']) {
      const event = new KeyboardEvent('keydown', {
        key: 'ArrowRight', bubbles: true, cancelable: true, [modifier]: true,
      });
      tab('Plan').dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
      expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
    }
  });

  it('takes any open dialog down with the tab it belonged to', async () => {
    // The sheet and the pick dialog live outside the pane and their state is independent of the
    // tab, so without this a reader who opened a window's spot list and then pressed Coming up
    // would be left with a modal about a Plan window floating over the almanac feed — and
    // `useDialogFocus` is not a focus trap, so closing it would hand focus to a trigger that is no
    // longer on screen.
    // Five rated spots, because `sheetOffersMore` withholds the trigger below four and when
    // nothing is rated. No conditional here: if the trigger stops appearing this test must fail
    // rather than skip itself.
    const spots = [1, 2, 3, 4, 5].map((n) => ({
      key: String(n),
      locationId: n,
      locationName: `Spot ${n}`,
      regionName: 'Northumberland & Tyneside',
      rating: n === 5 ? 3 : 4,
      driveMinutes: 20 + n,
      distanceMiles: 10 + n,
      far: false,
    }));
    const withSpots = { ...card(), spots, allSpots: spots, reachTotal: spots.length };
    renderShell({ windowCards: [withSpots] });

    fireEvent.click(screen.getByTestId('window-spot-all'));
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    await openComingUp();

    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('leaves keys it does not own alone, so the page can still be scrolled', () => {
    // Up/Down are deliberately unbound: this is a horizontal tab list, and taking the vertical keys
    // would cost the page scroll for nothing.
    renderShell();
    tab('Plan').focus();

    const event = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true });
    tab('Plan').dispatchEvent(event);

    expect(event.defaultPrevented).toBe(false);
    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
  });

  it('starts on Plan again on the next visit, because a tab is not a saved preference', async () => {
    // The arm persists the layout flag and the rating floor, and both are settled taste. Which tab
    // you last had open is not: restoring a ninety-day almanac answers a question the reader is
    // almost certainly not asking at 05:00, and it spends the first paint on a fetch.
    // The storage is watched as well as the remount. Without that this passes for a shell that
    // persists the tab and simply fails to read it back — a different bug wearing the same tick.
    //
    // ⚠️ Read through the PUBLIC API (`length`/`key`), never a spy on `setItem`, and the reason is
    // that a spy here is environment-dependent in a way that only CI can see. `setup.js:27-37`
    // installs a plain-object `localStorage` **only when jsdom does not supply one** — which is
    // what happens on this project's Macs and is not what happens on the CI runner, where jsdom's
    // real Proxy-backed `Storage` is present and an instance spy on `setItem` is bypassed
    // entirely. A `Storage.prototype` spy has the same problem in reverse. Both spellings pass
    // locally and one of them records nothing on CI; the first version of this test did exactly
    // that and CI caught it. `length` and `key` are implemented by both.
    const keysNow = () => Array.from({ length: localStorage.length }, (_, i) => localStorage.key(i));

    // Control: prove the observation can see a write at all, in whichever storage this environment
    // supplied. Without it, "no tab key was written" passes on a mechanism that sees nothing.
    localStorage.setItem('control', '1');
    expect(keysNow()).toContain('control');
    localStorage.removeItem('control');

    // Compared against a snapshot rather than against `[]`, so the assertion is about what THIS
    // interaction wrote and cannot be broken by an unrelated key the environment happens to carry.
    const before = keysNow();

    const { unmount } = renderShell();
    await openComingUp();
    expect(tab('Coming up')).toHaveAttribute('aria-selected', 'true');
    unmount();

    renderShell();
    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
    expect(keysNow()).toEqual(before);
  });
});

describe('WindowFirstShell — the reach lens across tabs', () => {
  it('shows the lens bar on Plan', () => {
    renderShell();
    expect(screen.getByTestId('window-first-lens')).toBeInTheDocument();
  });

  it('withdraws the lens bar on Coming up, where it would gate nothing', async () => {
    // The design's own heading for it is "Lens bar (Plan only)". The bar filters SPOTS and the
    // almanac feed has none — §6 bans a control that gates data which does not exist, and its
    // footer would otherwise read "0 spots across 5 windows" over a pane containing neither.
    renderShell();
    await openComingUp();

    expect(screen.queryByTestId('window-first-lens')).toBeNull();
  });

  it('brings the lens bar back on the way to Plan', async () => {
    renderShell();
    await openComingUp();

    fireEvent.click(tab('Plan'));
    expect(screen.getByTestId('window-first-lens')).toBeInTheDocument();
  });
});

describe('WindowFirstShell — what stays put across a tab change', () => {
  it('keeps the day rail, which is the whole screen’s date context and not one pane’s', async () => {
    // It sits ABOVE the tab bar for exactly this reason: Coming up asks about the same days.
    renderShell();
    await openComingUp();

    expect(screen.getByTestId('window-first-rail-region')).toBeInTheDocument();
  });

  it('keeps the masthead, the rail footer and the way back out', async () => {
    renderShell();
    await openComingUp();

    expect(screen.getByTestId('window-first-masthead')).toBeInTheDocument();
    expect(screen.getByTestId('window-first-railfoot')).toBeInTheDocument();
    expect(screen.getByTestId('window-first-exit')).toBeInTheDocument();
  });
});

describe('WindowFirstShell — when the feed is fetched', () => {
  it('asks for nothing until the tab is opened', () => {
    // Plan is the default tab and therefore every reader's first screen. An eager fetch would spend
    // a request on every one of them for a pane most will never open.
    renderShell();
    expect(getAlmanac).not.toHaveBeenCalled();
  });

  it('fetches once when the tab is first opened', async () => {
    renderShell();
    await openComingUp();

    expect(getAlmanac).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('Perseids')).toBeInTheDocument();
  });

  it('does not fetch again when the reader comes back to the tab', async () => {
    // The latch. `ManageView` re-mounts its sub-views on every tab change and refetches every time;
    // this feed is rebuilt by the backend once a UTC day, so a second request buys nothing.
    renderShell();
    await openComingUp();
    fireEvent.click(tab('Plan'));
    await openComingUp();
    fireEvent.click(tab('Plan'));
    await openComingUp();

    expect(getAlmanac).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Perseids')).toBeInTheDocument();
  });

  it('refetches once the date has rolled under an open tab', async () => {
    // The latch key is the date rather than a boolean, so a session left open past midnight does
    // not go on showing yesterday's feed with a row still reading "Today".
    const { withContext } = renderShell();
    await openComingUp();
    expect(getAlmanac).toHaveBeenCalledTimes(1);

    withContext({ todayStr: '2026-08-09', tomorrowStr: '2026-08-10' });
    await act(async () => { await Promise.resolve(); });

    expect(getAlmanac).toHaveBeenCalledTimes(2);
  });

  it('lets the newer feed win when the date roll puts two requests in flight', async () => {
    // The only overlap this hook can produce, and responses carry no ordering guarantee — so
    // without a run id the older request landing last repaints yesterday's feed over today's.
    const STALE = [{ ...FEED[0], title: 'Yesterday’s feed' }];
    let resolveStale;
    getAlmanac.mockImplementationOnce(() => new Promise((resolve) => { resolveStale = resolve; }));
    getAlmanac.mockResolvedValue(FEED);

    const { withContext } = renderShell();
    await openComingUp();
    // The first request is still out. Midnight passes; the second goes out and comes back first.
    withContext({ todayStr: '2026-08-09', tomorrowStr: '2026-08-10' });
    await act(async () => { await Promise.resolve(); });
    expect(await screen.findByText('Perseids')).toBeInTheDocument();

    // Now yesterday's answer arrives. It must be dropped, not painted.
    // Settled rather than abandoned: an unresolved promise left in flight entangles every later
    // async action in the file, which the standards call out by name.
    await act(async () => { resolveStale(STALE); await Promise.resolve(); });

    expect(screen.getByText('Perseids')).toBeInTheDocument();
    expect(screen.queryByText('Yesterday’s feed')).toBeNull();
  });

  it('shows the failure rather than an empty sky when the request rejects', async () => {
    getAlmanac.mockRejectedValue(new Error('502'));
    renderShell();
    await openComingUp();

    expect(await screen.findByTestId('coming-up-error')).toBeInTheDocument();
    expect(screen.queryByTestId('coming-up-empty')).toBeNull();
  });

  it('does not retry in a loop after a failure — the reader presses the button', async () => {
    // The latch stays SET on failure deliberately. Without that a re-render would re-enter the
    // effect and hammer a backend that has just said no.
    getAlmanac.mockRejectedValue(new Error('502'));
    renderShell();
    await openComingUp();
    fireEvent.click(tab('Plan'));
    await openComingUp();

    expect(getAlmanac).toHaveBeenCalledTimes(1);

    getAlmanac.mockResolvedValue(FEED);
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await act(async () => { await Promise.resolve(); });

    expect(getAlmanac).toHaveBeenCalledTimes(2);
    expect(screen.getByText('Perseids')).toBeInTheDocument();
  });
});
