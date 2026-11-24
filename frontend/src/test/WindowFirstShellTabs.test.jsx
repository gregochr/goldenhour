import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, cleanup, within, waitFor } from '@testing-library/react';
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
 */
vi.mock('../api/almanacApi.js', () => ({
  getAlmanac: vi.fn(),
  ALMANAC_DAYS: 90,
}));

const TODAY = '2026-08-08';

const FEED_ENTRIES = [{
  startDate: '2026-08-12',
  endDate: '2026-08-12',
  kind: 'ALMANAC',
  type: 'meteor',
  title: 'Perseids',
  detail: 'Perseids peaks, around 100 meteors an hour at best under a dark sky.',
  meta: { zhr: '100', radiant: 'NE', bestHours: '01:00–04:00', moonIllumination: '0%' },
}];

/** The wire's wrapped shape ({@code ComingUpResponse}) — `getAlmanac` never resolves a bare array. */
const FEED = { builtFor: TODAY, bands: null, counts: null, conditions: [], entries: FEED_ENTRIES };

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
    // The heat strip's thumbnails replaced the day rail's tiles at P2 (plan D1). Empty here, with an
    // empty catalogue beside it: the strip withdraws entirely without spots to draw, which keeps
    // these files about the shell's wiring rather than about a canvas.
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
    // The second axis, as the provider hands it over. Frozen rather than the live hook, for the
    // same reason the reach lens is: these files are about the shell's wiring.
    ratingLens: {
      floor: { id: 'any', min: null, label: 'Any rating' },
      floorId: 'any',
      minRating: null,
      selectFloor: vi.fn(),
    },
    ...overrides,
  };
};

const renderShell = (overrides = {}, extraProps = {}) => {
  const useBriefing = vi.spyOn(briefingContext, 'useWindowFirstBriefing');
  useBriefing.mockReturnValue(ctx(overrides));
  const props = {
    onOpenSettings: vi.fn(), onSignOut: vi.fn(), onOpenMapTab: vi.fn(), locations: [],
    ...extraProps,
  };
  const view = render(<WindowFirstShell {...props} />);
  /** Hands the shell a different briefing context, the way the provider would on a poll. */
  const withContext = (next) => {
    useBriefing.mockReturnValue(ctx({ ...overrides, ...next }));
    view.rerender(<WindowFirstShell {...props} />);
  };
  return { ...props, ...view, withContext };
};

// jsdom applies no Tailwind, so BOTH GlanceLabel spans count: the tab's name is "At a GlanceGlance" here.
const tab = (name) => screen.getByRole('tab', { name: name === 'At a Glance' ? /^At a Glance/ : name });

/** An Operations pane, so a test has a second real tab to move to (Coming up is a door onto Further ahead, O2). */
const OPS = <p data-testid="ops-pane">operations pane</p>;

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


/**
 * jsdom applies no Tailwind and evaluates no media query in `getComputedStyle`, so the pair's two
 * states are simulated with the plain rules each breakpoint resolves to. From 640px `.hidden` and
 * `.sm:hidden` both apply and `.sm:inline` shows the long name; on a phone only `.hidden` applies, so the
 * long name is off and the short one shows. Exact accessible names then prove one span, not both.
 */
function withBreakpoint(band) {
  const style = document.createElement('style');
  style.textContent = band === 'desktop'
    ? '.hidden{display:none}.sm\\:inline{display:inline}.sm\\:hidden{display:none}'
    : '.hidden{display:none}';
  document.head.appendChild(style);
  return () => style.remove();
}

describe('WindowFirstShell — the tab bar', () => {
  it.each([['desktop', 'At a Glance'], ['phone', 'Glance']])(
    'names the first tab exactly the visible word on %s', (band, word) => {
      const off = withBreakpoint(band);
      renderShell();
      expect(screen.getAllByRole('tab')[0]).toHaveAccessibleName(word);
      off();
    },
  );

  it('names the tablist "Sections", not after one tab', () => {
    renderShell();
    expect(screen.getByRole('tablist', { name: 'Sections' })).toBeInTheDocument();
  });

  it('offers exactly Plan and Coming up, and no tab whose pane does not exist', () => {
    // The design draws four. Map and Manage arrive when this subtree takes over view state; a tab
    // that renders nothing is a demo control and §6 bans those.
    renderShell();

    const tabs = screen.getAllByRole('tab');
    expect(tabs.map((t) => t.textContent.trim())).toEqual(['◉ At a GlanceGlance', 'Coming up']);
    expect(tab('At a Glance')).toHaveAccessibleName('At a GlanceGlance');
    expect(tab('Coming up')).toHaveAccessibleName('Coming up');
  });

  // ── Slotted tabs: a tab exists only when its pane was handed over ──
  //
  // This is also the whole admin gate. `App` holds `isAdmin` and withholds `operationsPane`, so no
  // role, no boolean and nothing role-shaped reaches this component — which is why these tests can
  // exercise the gate without ever mentioning a role.
  describe('tabs that arrive with their panes', () => {
    const OPS = <p data-testid="ops-pane">operations pane</p>;

    it('shows no Operations tab when no pane was handed over', () => {
      renderShell();
      expect(screen.queryByRole('tab', { name: 'Operations' })).toBeNull();
      // The panel element must not be there either — a tab-less panel is unreachable markup.
      expect(screen.queryByTestId('window-first-panel-operations')).toBeNull();
    });

    it('shows it when the pane is handed over, at the end of the bar', () => {
      renderShell({}, { operationsPane: OPS });
      const names = screen.getAllByRole('tab').map((t) => t.textContent.trim());
      expect(names).toEqual(['◉ At a GlanceGlance', 'Coming up', 'Operations']);
      // No glyph: the cog is already the masthead's settings control a few pixels away.
      expect(screen.getByRole('tab', { name: 'Operations' })).toHaveAccessibleName('Operations');
    });

    // The panel element is always present so `aria-controls` resolves; the CONTENTS wait. Without
    // the split, every Plan-tab first paint would mount ManageView and fire its fetches.
    it('holds the panel from the start but not its contents', () => {
      renderShell({}, { operationsPane: OPS });
      expect(screen.getByTestId('window-first-panel-operations', { hidden: true })).toBeInTheDocument();
      expect(screen.queryByTestId('ops-pane')).toBeNull();
    });

    it('mounts the pane on first selection and keeps it mounted after', () => {
      renderShell({}, { operationsPane: OPS });
      fireEvent.click(screen.getByRole('tab', { name: 'Operations' }));
      expect(screen.getByTestId('ops-pane')).toBeInTheDocument();

      // Back to Plan: still mounted, just hidden. ManageView reads the hash at mount only, so a
      // remount would throw away whichever sub-view the reader was on.
      fireEvent.click(tab('At a Glance'));
      expect(screen.getByTestId('ops-pane', { hidden: true })).toBeInTheDocument();
      expect(screen.getByTestId('window-first-panel-operations', { hidden: true })).toHaveAttribute('hidden');
    });

    // The display class, pinned. It sits one line below `hidden={effectiveTab !== tab.id}` and reads
    // the condition the other way round, so inverting it gives a pane that is `display:none` while
    // its `hidden` attribute says visible — a blank pane under a green suite, since jsdom parses no
    // CSS and every other assertion here reads presence, `hidden` or aria. The Plan pane carries a
    // dedicated pin for exactly this; the slotted panel copied the pattern without it. `wf-body` is
    // asserted in the same breath because a panel without it renders flush to the frame while its
    // siblings sit at the arm's inset.
    it('carries the pane inset in both states, and the display class only when hidden', () => {
      renderShell({}, { operationsPane: OPS });
      const panel = () => screen.getByTestId('window-first-panel-operations', { hidden: true });

      expect(panel().className).toContain('wf-body');
      expect(panel().className).toContain('hidden');

      fireEvent.click(screen.getByRole('tab', { name: 'Operations' }));
      expect(panel().className).toContain('wf-body');
      expect(panel().className).not.toContain('hidden');
    });

    it('pairs the new tab and panel the way the widget requires', () => {
      renderShell({}, { operationsPane: OPS });
      const t = screen.getByRole('tab', { name: 'Operations' });
      const panel = screen.getByTestId('window-first-panel-operations', { hidden: true });
      expect(t).toHaveAttribute('aria-controls', panel.getAttribute('id'));
      expect(panel).toHaveAttribute('aria-labelledby', t.getAttribute('id'));
    });

    it('walks the keyboard over the tabs that exist, not the ones the build could have had', () => {
      renderShell({}, { operationsPane: OPS });
      fireEvent.keyDown(tab('At a Glance'), { key: 'End' });
      expect(screen.getByRole('tab', { name: 'Operations' })).toHaveAttribute('aria-selected', 'true');

      // And with no pane, End lands on the last tab that DOES exist rather than on nothing. Without
      // the derived list this indexed past the end: focus lost, no tab holding tabIndex 0, and the
      // bar unreachable by keyboard.
      cleanup();
      renderShell();
      fireEvent.keyDown(tab('At a Glance'), { key: 'End' });
      // The last tab that exists is Coming up, which is a door onto Further ahead (O2): Plan stays selected.
      expect(tab('At a Glance')).toHaveAttribute('aria-selected', 'true');
      expect(tab('Coming up')).toHaveAttribute('aria-selected', 'false');
    });

    // A selection can outlive its tab: a session that loses admin, or a stored id from a build with
    // one more pane. Every panel hidden and no tab holding tabIndex 0 is the state to avoid.
    it('falls back to the first tab when the selected one goes away', () => {
      const { rerender } = renderShell({}, { operationsPane: OPS });
      fireEvent.click(screen.getByRole('tab', { name: 'Operations' }));
      expect(screen.getByRole('tab', { name: 'Operations' })).toHaveAttribute('aria-selected', 'true');

      rerender(<WindowFirstShell
        onOpenSettings={vi.fn()} onSignOut={vi.fn()} onShowOnMap={vi.fn()}
        locations={[]} operationsPane={null}
      />);

      expect(screen.queryByRole('tab', { name: 'Operations' })).toBeNull();
      expect(tab('At a Glance')).toHaveAttribute('aria-selected', 'true');
      expect(screen.getByTestId('window-first-pane')).not.toHaveAttribute('hidden');
    });
  });

  it('opens on Plan, because the question on arriving is about tonight', () => {
    renderShell();

    expect(tab('At a Glance')).toHaveAttribute('aria-selected', 'true');
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

    for (const [name, testId] of [['At a Glance', 'window-first-pane'], ['Coming up', 'window-first-coming-up-door']]) {
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

    expect(tab('At a Glance')).toHaveAttribute('tabindex', '0');
    expect(tab('Coming up')).toHaveAttribute('tabindex', '-1');
  });
});

describe('WindowFirstShell — moving between tabs', () => {
  it('shows exactly one panel at a time, and both are always in the document', async () => {
    // Both panels are mounted for the aria-controls pairing, so "is it in the document" no longer
    // distinguishes anything — visibility is the assertion that still can fail. Coming up is a door
    // onto Further ahead (O2): its own panel is never the visible one.
    renderShell({}, { operationsPane: OPS });
    expect(screen.getByTestId('window-first-pane')).toBeVisible();
    expect(screen.getByTestId('window-first-coming-up-door')).not.toBeVisible();

    await openComingUp();

    expect(screen.getByTestId('window-first-pane')).toBeVisible();
    expect(screen.getByTestId('window-first-coming-up-door')).not.toBeVisible();
    expect(tab('Coming up')).toHaveAttribute('aria-selected', 'false');

    fireEvent.click(screen.getByRole('tab', { name: 'Operations' }));
    expect(screen.getByTestId('window-first-pane')).not.toBeVisible();
  });

  it('choosing Coming up lands the reader on Further ahead: Plan selected, its heading scrolled to and focused', async () => {
    Element.prototype.scrollIntoView = vi.fn();
    renderShell();
    await openComingUp();

    expect(tab('At a Glance')).toHaveAttribute('aria-selected', 'true');
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
    expect(screen.getByTestId('further-ahead-heading')).toHaveFocus();
    delete Element.prototype.scrollIntoView;
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
    renderShell({}, { operationsPane: OPS });
    // Plan selected: it lays out, and the always-hidden Coming up door carries the display rule that hides it.
    expect(screen.getByTestId('window-first-pane').className).toContain('flex');
    expect(screen.getByTestId('window-first-pane').className).not.toContain('hidden');
    expect(screen.getByTestId('window-first-coming-up-door').className).toContain('hidden');

    fireEvent.click(screen.getByRole('tab', { name: 'Operations' }));

    // And the other way round. `hidden` must REPLACE the display utility on the Plan pane, not sit
    // beside it — Tailwind's `.flex` and `.hidden` are the same property, and which wins would then
    // be source order rather than intent.
    expect(screen.getByTestId('window-first-pane').className).toContain('hidden');
    expect(screen.getByTestId('window-first-pane').className).not.toContain('flex flex-col');
  });

  it('keeps the Plan pane mounted, so returning to it costs no remount', async () => {
    // Unmounting the pane on every tab change would re-mount its matrix and lose the reader's open
    // window on every change back — which is what `ManageView` does to its sub-views and the
    // behaviour not to copy here.
    renderShell();
    await openComingUp();

    expect(screen.getByTestId('window-first-pane')).toBeInTheDocument();
    expect(screen.getByTestId('window-first-pane-status')).toBeInTheDocument();
  });

  it('takes the Plan pane out of the accessibility tree while it is not selected', async () => {
    // Hidden, not merely off-screen. The tab pattern requires an unselected panel to be
    // unreachable, and a CSS-only hide would leave it findable by a screen reader.
    renderShell({}, { operationsPane: OPS });
    fireEvent.click(screen.getByRole('tab', { name: 'Operations' }));

    expect(screen.getByTestId('window-first-pane')).toHaveAttribute('hidden');
  });

  it('moves selection and focus together with the arrow keys, wrapping at both ends', async () => {
    // Selection follows focus, which is the authoring-practices default and is right here because
    // both panes are cheap. This is the first roving-tabindex implementation in the codebase —
    // there was nothing to copy, so the whole contract is pinned.
    renderShell({}, { operationsPane: OPS });
    tab('At a Glance').focus();
    const ops = () => screen.getByRole('tab', { name: 'Operations' });

    // Coming up is a door onto Further ahead (O2), not a tab: the arrows step over it.
    fireEvent.keyDown(tab('At a Glance'), { key: 'ArrowRight' });
    expect(document.activeElement).toBe(ops());
    expect(ops()).toHaveAttribute('aria-selected', 'true');

    fireEvent.keyDown(ops(), { key: 'ArrowRight' });
    expect(document.activeElement).toBe(tab('At a Glance'));
    expect(tab('At a Glance')).toHaveAttribute('aria-selected', 'true');

    fireEvent.keyDown(tab('At a Glance'), { key: 'ArrowLeft' });
    expect(document.activeElement).toBe(ops());
    // The door never took selection, focus or the section's heading.
    expect(screen.getByTestId('further-ahead-heading')).not.toHaveFocus();
    expect(tab('Coming up')).toHaveAttribute('aria-selected', 'false');
  });

  it('arrowing from the Coming up button itself (clicked, so focused) goes on to the next real tab, either way', () => {
    renderShell({}, { operationsPane: OPS });
    tab('Coming up').focus();
    fireEvent.keyDown(tab('Coming up'), { key: 'ArrowRight' });
    expect(document.activeElement).toBe(screen.getByRole('tab', { name: 'Operations' }));
    tab('Coming up').focus();
    fireEvent.keyDown(tab('Coming up'), { key: 'ArrowLeft' });
    expect(document.activeElement).toBe(tab('At a Glance'));
  });

  it('jumps to the ends with Home and End', async () => {
    renderShell({}, { operationsPane: OPS });
    tab('At a Glance').focus();

    fireEvent.keyDown(tab('At a Glance'), { key: 'End' });
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByRole('tab', { name: 'Operations' })).toHaveAttribute('aria-selected', 'true');

    fireEvent.keyDown(screen.getByRole('tab', { name: 'Operations' }), { key: 'Home' });
    expect(tab('At a Glance')).toHaveAttribute('aria-selected', 'true');
  });

  it('leaves a modified arrow to the browser, so Alt+Left is still Back', () => {
    // The bar binds the UNMODIFIED keys only. Alt+Left and Cmd+Left are the browser's Back and
    // Ctrl/Cmd+Home is "top of document"; swallowing one of those to change tab is worse than not
    // handling the key at all, and preventDefault was unconditional before this.
    renderShell();
    tab('At a Glance').focus();

    for (const modifier of ['altKey', 'ctrlKey', 'metaKey', 'shiftKey']) {
      const event = new KeyboardEvent('keydown', {
        key: 'ArrowRight', bubbles: true, cancelable: true, [modifier]: true,
      });
      tab('At a Glance').dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
      expect(tab('At a Glance')).toHaveAttribute('aria-selected', 'true');
    }
  });

  it('takes any open dialog down with the tab it belonged to', async () => {
    // The four-day location sheet lives outside the pane and its state is independent of the tab, so without this a
    // reader who opened a place's sheet and then pressed Coming up would be left with a modal about a Plan place
    // floating over the almanac feed. `useDialogFocus` is not a focus trap, so closing it would hand focus to a
    // trigger that is no longer on screen. Opened the way a reader opens it from Plan now: through Find.
    renderShell({
      heatSpots: [{
        id: 1, name: 'Spot 1', lat: 55.61, lng: -1.71, regionName: 'Northumberland & Tyneside',
        rid: 'Northumberland & Tyneside', skySubject: true, bortleClass: 3, scores: [4],
      }],
    });

    fireEvent.click(screen.getByTestId('window-first-search'));
    fireEvent.change(await screen.findByTestId('plan-search-input'), { target: { value: 'Spot 1' } });
    await act(async () => { fireEvent.click(screen.getAllByTestId('plan-search-row')[0]); });
    await screen.findByTestId('location-sheet');
    expect(screen.getAllByRole('dialog').length).toBe(1);

    await openComingUp();

    expect(screen.queryAllByRole('dialog')).toHaveLength(0);
  });

  it('⚠️ takes SEARCH down with the tab too — the dialog the list left out until 2026-09-16', async () => {
    // Search is a shell dialog like the rest but lived outside the list: it is `searchSeed`, not a
    // sheet. The search box is no trap, and only the tick line leaves the tab order under it, so a
    // keyboard reader could Tab out onto this bar and arrow away with search still open — over the
    // map, where the four-day peek then landed `inert` beneath it rather than as the only layer. The
    // route is driven the way that reader drives it: focus on the tab, then the arrow key.
    renderShell();
    fireEvent.click(screen.getByTestId('window-first-search'));
    await screen.findByTestId('plan-search');
    // Control: the tab the reader lands on is still in the tab order with search up.
    expect(tab('At a Glance')).toHaveAttribute('tabindex', '0');

    act(() => { tab('At a Glance').focus(); });
    fireEvent.keyDown(tab('At a Glance'), { key: 'ArrowRight' });
    await act(async () => { await Promise.resolve(); });

    // Coming up is a door onto Further ahead (O2): Plan stays selected, and the dialog still goes down with the move.
    expect(tab('At a Glance')).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByTestId('plan-search')).toBeNull();
    expect(screen.queryAllByRole('dialog')).toHaveLength(0);
  });

  it('leaves keys it does not own alone, so the page can still be scrolled', () => {
    // Up/Down are deliberately unbound: this is a horizontal tab list, and taking the vertical keys
    // would cost the page scroll for nothing.
    renderShell();
    tab('At a Glance').focus();

    const event = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true });
    tab('At a Glance').dispatchEvent(event);

    expect(event.defaultPrevented).toBe(false);
    expect(tab('At a Glance')).toHaveAttribute('aria-selected', 'true');
  });

  it('starts on Plan again on the next visit, because a tab is not a saved preference', async () => {
    // The arm persists the reach lens and the rating floor, and both are settled taste. Which tab
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

    const { unmount } = renderShell({}, { operationsPane: OPS });
    fireEvent.click(screen.getByRole('tab', { name: 'Operations' }));
    expect(screen.getByRole('tab', { name: 'Operations' })).toHaveAttribute('aria-selected', 'true');
    unmount();

    renderShell();
    expect(tab('At a Glance')).toHaveAttribute('aria-selected', 'true');
    expect(keysNow()).toEqual(before);
  });
});

describe('WindowFirstShell — the reach lens across tabs', () => {
  it('shows the lens bar on Plan', () => {
    renderShell();
    expect(screen.getByTestId('window-first-lens')).toBeInTheDocument();
  });

  it('keeps the lens bar when Coming up is asked for, because Further ahead is on the Plan pane under it', async () => {
    // It used to be withdrawn on the Coming up tab ("Lens bar (Plan only)"). Further ahead is the bottom section of
    // the Plan pane now (one-screen-plan.md O2) and the lens stays sticky over the whole screen.
    renderShell();
    await openComingUp();

    expect(screen.getByTestId('window-first-lens')).toBeInTheDocument();
  });

  it('withdraws the lens bar on Operations, where it would gate nothing', () => {
    renderShell({}, { operationsPane: OPS });
    fireEvent.click(screen.getByRole('tab', { name: 'Operations' }));

    expect(screen.queryByTestId('window-first-lens')).toBeNull();
  });

  it('brings the lens bar back on the way to Plan', async () => {
    renderShell();
    await openComingUp();

    fireEvent.click(tab('At a Glance'));
    expect(screen.getByTestId('window-first-lens')).toBeInTheDocument();
  });
});

/**
 * Find is offered on Plan, Coming up and Map, and withheld on Operations
 * (find-and-favourites-plan.md F1).
 *
 * <p>Until F1 search was a Plan-tab control: everything it found was a Plan object, and a pick made on
 * Coming up opened the window popup or the four-day sheet over a pane that was not the Plan's, a state
 * `selectTab` exists to prevent (reproduced in jsdom through both buttons on 2026-09-16, which is when
 * the masthead withheld them). F1 lifts the gate by giving each tab its own meaning for a pick (the
 * per-tab pick tests are in `WindowFirstShellFind.test.jsx`), so the two buttons are back on all three
 * tabs. Operations has no meaning for a pick, so it still draws the origin as a statement, with no Find
 * button: withheld rather than refused (plan-matrix §3 rule 14). The controls that do something else
 * stay everywhere: the nudge opens settings, and ⌂ moves the origin home.
 */
describe('WindowFirstShell — Find across tabs', () => {
  const FIND_CONTROLS = ['window-first-settings', 'window-first-guide', 'window-first-signout',
    'window-first-origin-chip', 'window-first-search'];

  /** The band's buttons in DOM order, as an exact list — a count passes when one is swapped. */
  const mastheadControls = () => within(screen.getByTestId('window-first-masthead'))
    .getAllByRole('button').map((b) => b.getAttribute('data-testid'));

  it.each([
    ['at home', {}, FIND_CONTROLS, 'Home · Newcastle'],
    ['away, where the way home stays', { origin: { id: 7, name: 'The Lake District', baseName: 'Keswick' } },
      [...FIND_CONTROLS, 'window-first-origin-home'],
      'The Lake District · from Keswick'],
  ])('⚠️ offers both Find doors on Coming up, %s — the origin button and the Find button', async (
    _label, overrides, controls, place,
  ) => {
    renderShell(overrides);
    await openComingUp();

    expect(mastheadControls()).toEqual(controls);
    expect(screen.getByRole('button', { name: /Find a place, region or day/ })).toBeInTheDocument();
    expect(screen.queryByTestId('window-first-origin-statement')).toBeNull();
    expect(screen.getByTestId('window-first-origin-chip')).toHaveTextContent(place);
  });

  it('keeps the postcode nudge on Coming up, because it opens settings rather than Find', async () => {
    // CLAUDE.md: never re-gate the postcode input. The nudge is the origin button's empty state, but
    // its job is a setting, and that job exists on every tab. Find sits beside it.
    const { onOpenSettings } = renderShell({ homePlace: null });
    await openComingUp();

    expect(mastheadControls()).toEqual(['window-first-settings', 'window-first-guide', 'window-first-signout',
      'masthead-set-postcode', 'window-first-search']);
    fireEvent.click(screen.getByRole('button', { name: 'Set a postcode for light and drive times' }));
    expect(onOpenSettings).toHaveBeenCalledTimes(1);
  });

  it('opens Find from the place in the tick line, on Coming up as on Plan', async () => {
    renderShell();
    await openComingUp();
    fireEvent.click(within(screen.getByTestId('window-first-tickline')).getByText('Home · Newcastle'));
    expect(await screen.findByTestId('plan-search')).toBeInTheDocument();
  });

  it('opens Find from the Find button on the way back to Plan too', async () => {
    renderShell();
    await openComingUp();
    fireEvent.click(tab('At a Glance'));

    expect(mastheadControls()).toEqual(FIND_CONTROLS);
    fireEvent.click(screen.getByRole('button', { name: /Find a place, region or day/ }));
    expect(await screen.findByTestId('plan-search')).toBeInTheDocument();
  });

  it('⚠️ withholds Find on Operations — a pick there would have no meaning', async () => {
    renderShell({}, { operationsPane: <p data-testid="ops-pane">operations pane</p> });
    fireEvent.click(screen.getByRole('tab', { name: 'Operations' }));
    // Settles the feed request every mount makes, which would otherwise land outside `act`.
    await act(async () => { await Promise.resolve(); });

    expect(mastheadControls()).toEqual(['window-first-settings', 'window-first-signout']);
    expect(screen.getByTestId('window-first-origin-statement')).toHaveTextContent('Home · Newcastle');
  });

  it('⚠️ offers Find in the map view, with the "drive times from here" caption kept on its origin chip', async () => {
    // The caption is true on the map, where every drive time and leave-by on screen is measured from this place.
    // Coming up shows no drive time at all, so there it would be a claim about nothing. The masthead behind the view
    // is inert and no longer carries the caption: the view's own header does.
    renderShell({}, { mapPane: <p data-testid="map-pane">map pane</p> });
    fireEvent.click(screen.getByTestId('window-first-tab-map'));
    expect(screen.getByTestId('map-screen-find')).toBeInTheDocument();
    expect(screen.getByTestId('map-screen-origin')).toHaveTextContent('Home · Newcastle');
    expect(screen.getByTestId('map-screen-origin')).toHaveTextContent('drive times from here');
    expect(screen.queryByTestId('masthead-origin-caption')).toBeNull();

    fireEvent.click(screen.getByTestId('map-screen-close'));
    await openComingUp();
    expect(screen.getByTestId('window-first-origin-chip')).toHaveTextContent('Home · Newcastle');
    expect(screen.queryByTestId('masthead-origin-caption')).toBeNull();
  });
});

describe('WindowFirstShell — what stays put across a tab change', () => {
  it('withdraws the window summary on Coming up, because it is now a Plan-pane element', async () => {
    // ⚠️ THIS TEST IS A RECORDED REVERSAL, and its previous form is the thing to read before
    // changing it back. Through P14 it asserted the opposite — "keeps the day rail, which is the
    // whole screen's date context and not one pane's… It sits ABOVE the tab bar for exactly this
    // reason: Coming up asks about the same days."
    //
    // Decision D1 of `heat-field-plan.md` (owner-confirmed 2026-08-18, reasoning in §1.1) replaces
    // the rail with `WindowFirstHeatStrip`, INSIDE the Plan tabpanel and under the lens bar. The
    // rail's cross-tab job is the one thing genuinely lost, and it is acceptable now because each
    // tab has since grown its own date context: the Map pane's window control (map-tab-v2-plan.md
    // §3 P6's `WindowControl`/`mapEvents.js`, which retired the pane's earlier `DateStrip` mount)
    // browses the full forecast horizon, and every Coming-up row carries its dates. Stacking a day
    // summary and a window summary would be two summaries of one forecast at two grains, roughly
    // two screens of chrome before the first window row.
    //
    // ⚠️ The harness is given strip data on purpose. With the file's default (`heatStripCards: []`,
    // `heatSpots: []`) the strip withdraws everywhere, so "absent from Coming up" would be true of a
    // build that rendered it above the tab bar too — the very position this test exists to reverse.
    // Containment in the PLAN panel is the half that discriminates.
    renderShell({
      heatStripCards: [{
        key: `${TODAY}:SUNSET`,
        date: TODAY,
        targetType: 'SUNSET',
        dow: 'Sat',
        sunrise: false,
        label: 'Tonight Sunset',
        time: '20:41',
        verdict: 'WORTH_IT',
        verdictLabel: 'Worth it',
        pickKind: null,
        away: false,
        confidence: 'high',
      }],
      heatSpots: [{
        id: 1, name: 'Bamburgh Beach', lat: 55.61, lng: -1.71, regionName: 'N&T', rid: 'N&T', skySubject: true, bortleClass: 3, scores: [4],
      }],
    });

    const strip = await screen.findByTestId('wf-heat-strip');
    expect(screen.getByTestId('window-first-pane')).toContainElement(strip);

    // Further ahead is part of the Plan pane (O2), so asking for Coming up leaves the strip where it was; the strip
    // is the first section's and never the almanac's.
    expect(screen.getByTestId('window-first-pane')).not.toHaveAttribute('hidden');
    expect(screen.getByTestId('window-first-pane')).toContainElement(strip);
    expect(within(screen.getByTestId('further-ahead')).queryByTestId('wf-heat-strip'))
      .toBeNull();
  });

  it('hands the heat strip the run age, so the change line dates its own movement', async () => {
    // The wiring tripwire for P6's change line. The age is `briefing.generatedAt` formatted by the
    // shell — one computation, two renders (the footer's stamp and this line), so the two can never
    // disagree by a rounding boundary. Drop the prop and the line still renders, silently losing
    // the only thing that says WHEN the movement happened.
    //
    // The stamp is an OFFSET from now, not a literal date. `formatRelativeAge` reads `Date.now()`,
    // so a hard-coded pair would be a fixture about the day it was written — and faking the timers
    // instead wedges this file's `findBy*` waits, which poll on real ones. Fifty-two minutes back
    // rounds to "52m ago" for any wall clock.
    const generatedAt = new Date(Date.now() - 52 * 60 * 1000).toISOString();
    {
      renderShell({
        briefing: { generatedAt, hotTopics: [] },
        heatStripCards: [{
          key: `${TODAY}:SUNSET`,
          date: TODAY,
          targetType: 'SUNSET',
          dow: 'Sat',
          sunrise: false,
          label: 'Tonight Sunset',
          time: '20:41',
          verdict: 'WORTH_IT',
          verdictLabel: 'Worth it',
          pickKind: null,
          away: false,
          confidence: 'high',
          movement: { regionName: 'Northumberland', delta: 0.6 },
        }],
        heatSpots: [{
          id: 1, name: 'Bamburgh Beach', lat: 55.61, lng: -1.71, regionName: 'N&T', rid: 'N&T', skySubject: true, bortleClass: 3, scores: [4],
        }],
      });

      const line = await screen.findByTestId('wf-heat-change');
      expect(line).toHaveTextContent('Changed at the last forecast run, 52m ago');
    }
  });

  it('keeps the masthead and its tick line', async () => {
    // The rail footer used to be a third fixture here and M3 deleted it; the tick line is where its
    // origin control went, and the claim is unchanged — the chrome that frames the page must not
    // come and go with the tab.
    renderShell();
    await openComingUp();

    expect(screen.getByTestId('window-first-masthead')).toBeInTheDocument();
    expect(screen.getByTestId('window-first-tickline')).toBeInTheDocument();
  });
});

describe('WindowFirstShell — when the feed is fetched', () => {
  it('fetches immediately on mount, before any tab is ever opened (plan D13)', async () => {
    // Plan is the default tab and therefore every reader's first screen — this used to be exactly
    // why the fetch waited for the tab to open, spending a request on every reader for a pane most
    // would never look at. The tab badge (D3/D4) needs to know about arrivals whether or not the
    // reader ever opens Coming up, so the reversal is deliberate: see useComingUpFeed.js's own
    // class doc for the full argument.
    renderShell();
    await act(async () => { await Promise.resolve(); });

    expect(getAlmanac).toHaveBeenCalledTimes(1);
    // Plan, not Coming up, is still what is on screen — the fetch ran without a tab switch.
    expect(screen.getByRole('tab', { name: /^At a Glance/ })).toHaveAttribute('aria-selected', 'true');
  });

  it('shows the already-fetched feed the moment the reader opens the tab', async () => {
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
    fireEvent.click(tab('At a Glance'));
    await openComingUp();
    fireEvent.click(tab('At a Glance'));
    await openComingUp();

    expect(getAlmanac).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Perseids')).toBeInTheDocument();
  });

  it('refetches on a date roll even though the reader never opened the tab (D13)', async () => {
    // The eager fetch's whole point: the latch is keyed on the date regardless of which tab is on
    // screen, so a badge computed from a stale feed does not survive past midnight just because
    // nobody ever looked at the pane it came from.
    const { withContext } = renderShell();
    await act(async () => { await Promise.resolve(); });
    expect(getAlmanac).toHaveBeenCalledTimes(1);

    withContext({ todayStr: '2026-08-09', tomorrowStr: '2026-08-10' });
    await act(async () => { await Promise.resolve(); });

    expect(getAlmanac).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('tab', { name: /^At a Glance/ })).toHaveAttribute('aria-selected', 'true');
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
    const STALE = {
      ...FEED,
      entries: [{ ...FEED_ENTRIES[0], title: 'Yesterday’s feed' }],
    };
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
    fireEvent.click(tab('At a Glance'));
    await openComingUp();

    expect(getAlmanac).toHaveBeenCalledTimes(1);

    getAlmanac.mockResolvedValue(FEED);
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await act(async () => { await Promise.resolve(); });

    expect(getAlmanac).toHaveBeenCalledTimes(2);
    expect(screen.getByText('Perseids')).toBeInTheDocument();
  });
  // ── A tab asked for from outside the bar (P15b's map hatch) ──
  describe('a tab requested from outside the bar', () => {
    const MAP = <p data-testid="map-pane">map pane</p>;

    /** Re-renders the shell with new PROPS, which `withContext` (context-only) cannot do. */
    const renderWithRequest = (extra = {}) => {
      vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
      const base = {
        onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(),
        locations: [], mapPane: MAP, ...extra,
      };
      const view = render(<WindowFirstShell {...base} />);
      return { ...view, setRequest: (tabRequest) => view.rerender(<WindowFirstShell {...base} tabRequest={tabRequest} />) };
    };

    it('selects the requested tab and mounts its pane', async () => {
      // Driven by `tabRequest`'s nonce (the aurora banner's Operations door in `App`). The Map is no longer a tab to
      // request: it is the map view, opened by `openMap`, and a request naming it is ignored (below).
      const { setRequest } = renderWithRequest({ operationsPane: <p data-testid="ops-pane">operations pane</p> });
      expect(screen.getByRole('tab', { name: /^At a Glance/ })).toHaveAttribute('aria-selected', 'true');
      act(() => setRequest({ id: 'operations', nonce: 1 }));
      expect(screen.getByRole('tab', { name: 'Operations' })).toHaveAttribute('aria-selected', 'true');
      expect(screen.getByTestId('ops-pane')).toBeInTheDocument();
      await act(async () => { await Promise.resolve(); });
    });

    it('ignores a request naming the Map, which is a view and not a tab', () => {
      const { setRequest } = renderWithRequest();
      act(() => setRequest({ id: 'map', nonce: 1 }));
      expect(screen.getByRole('tab', { name: /^At a Glance/ })).toHaveAttribute('aria-selected', 'true');
      expect(screen.queryByTestId('map-screen')).toBeNull();
    });

    it('lands again when the same tab is asked for twice, because the nonce moved', () => {
      // The reason it is nonce'd rather than keyed on the id. Open the map, go back to Plan by
      // hand, press the hatch again: an id-keyed request would see no change and do nothing.
      const { setRequest } = renderWithRequest();
      act(() => setRequest({ id: 'coming-up', nonce: 1 }));
      expect(screen.getByTestId('further-ahead-heading')).toHaveFocus();
      screen.getByRole('tab', { name: /^At a Glance/ }).focus();
      expect(screen.getByTestId('further-ahead-heading')).not.toHaveFocus();
      act(() => setRequest({ id: 'coming-up', nonce: 2 }));
      expect(screen.getByTestId('further-ahead-heading')).toHaveFocus();
    });

    describe('Operations, and the control a return carries (overlay convergence C4)', () => {
      const OPS = <p data-testid="ops-pane">operations pane</p>;

      it('honours a request for the Operations tab when it was handed the pane, and focuses its button', async () => {
        const { setRequest } = renderWithRequest({ operationsPane: OPS });
        act(() => setRequest({ id: 'operations', nonce: 1 }));
        expect(screen.getByRole('tab', { name: 'Operations' })).toHaveAttribute('aria-selected', 'true');
        expect(screen.getByTestId('ops-pane')).toBeInTheDocument();
        await waitFor(() => expect(screen.getByRole('tab', { name: 'Operations' })).toHaveFocus());
      });

      it('ignores a request for Operations when the shell was handed no pane (a non-admin)', () => {
        const { setRequest } = renderWithRequest();
        act(() => setRequest({ id: 'operations', nonce: 1 }));
        expect(screen.queryByRole('tab', { name: 'Operations' })).toBeNull();
        expect(screen.getByRole('tab', { name: /^At a Glance/ })).toHaveAttribute('aria-selected', 'true');
      });

      it('lands a second request after the reader navigated away, because the nonce moved', () => {
        const { setRequest } = renderWithRequest({ operationsPane: OPS });
        act(() => setRequest({ id: 'operations', nonce: 1 }));
        fireEvent.click(screen.getByRole('tab', { name: /^At a Glance/ }));
        expect(screen.getByRole('tab', { name: /^At a Glance/ })).toHaveAttribute('aria-selected', 'true');
        act(() => setRequest({ id: 'operations', nonce: 2 }));
        expect(screen.getByRole('tab', { name: 'Operations' })).toHaveAttribute('aria-selected', 'true');
      });

      it('arriving on a tab by a request puts focus on its tab button, not <body>', async () => {
        const { setRequest } = renderWithRequest();
        act(() => setRequest({ id: 'coming-up', nonce: 1 }));
        // Coming up is a door onto Further ahead (O2): focus lands on that section's heading.
        await waitFor(() => expect(screen.getByTestId('further-ahead-heading')).toHaveFocus());
      });
    });

    it('ignores a request for a pane it was handed nothing for — and does not jump later', () => {
      // ⚠️ The assertion that bites is the SECOND one. Without the guard, `selectTab('operations')` still sets
      // `activeTab`; `effectiveTab` masks it while no Operations tab exists, so a test that only checked "Plan is
      // selected" passes against the broken code. The damage is deferred: the moment `App` starts handing over an
      // `operationsPane`, the fallback stops applying and the reader is silently moved to a tab they never chose.
      vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
      const base = {
        onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(),
        locations: [], mapPane: MAP,
      };
      const view = render(<WindowFirstShell {...base} />);
      act(() => view.rerender(<WindowFirstShell {...base} tabRequest={{ id: 'operations', nonce: 1 }} />));
      expect(screen.queryByRole('tab', { name: 'Operations' })).toBeNull();
      expect(screen.getByRole('tab', { name: /^At a Glance/ })).toHaveAttribute('aria-selected', 'true');

      // The pane appears. Plan must still be the selected tab.
      const OPS2 = <p data-testid="ops-pane">operations pane</p>;
      act(() => view.rerender(<WindowFirstShell {...base} operationsPane={OPS2} tabRequest={{ id: 'operations', nonce: 1 }} />));
      expect(screen.getByRole('tab', { name: 'Operations' })).toHaveAttribute('aria-selected', 'false');
      expect(screen.getByRole('tab', { name: /^At a Glance/ })).toHaveAttribute('aria-selected', 'true');
    });

    it('ignores a request naming a tab that does not exist at all', () => {
      // Same shape, and the same reason the naive assertion is not enough: `effectiveTab` would
      // hide an `activeTab` of 'nowhere' forever, so the observable proof is that the pane which
      // WOULD have been mounted was not.
      const { setRequest } = renderWithRequest();
      act(() => setRequest({ id: 'nowhere', nonce: 1 }));
      expect(screen.getByRole('tab', { name: /^At a Glance/ })).toHaveAttribute('aria-selected', 'true');
      expect(screen.queryByTestId('map-pane')).toBeNull();
    });

    it('does not replay a request that was already in flight when it mounted', () => {
      // `App` holds `tabRequest` and never clears it, and it outlives this component. With the
      // handled-nonce seeded to null, remounting this shell would replay the last request and
      // land the reader on the Map tab when they were headed for Plan — which contradicts this
      // file's own rule that tab selection is not persisted.
      vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
      const props = {
        onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(),
        locations: [], mapPane: MAP, tabRequest: { id: 'coming-up', nonce: 4 },
      };
      render(<WindowFirstShell {...props} />);
      expect(screen.getByRole('tab', { name: /^At a Glance/ })).toHaveAttribute('aria-selected', 'true');
      expect(screen.getByRole('tab', { name: 'Coming up' })).toHaveAttribute('aria-selected', 'false');
    });

    it('moves focus to where it sent the reader, because the asker just closed itself', () => {
      // A click leaves focus on the tab the reader already put it on; a request arrives with focus
      // wherever the CALLER left it, and the caller is the map overlay, which closes on the same
      // press. Measured on the running app before this: `activeElement` was the document root — a
      // keyboard reader dropped at the top of the page having just asked to go somewhere specific.
      const raf = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => { cb(); return 0; });
      const { setRequest } = renderWithRequest();
      act(() => setRequest({ id: 'coming-up', nonce: 1 }));
      expect(document.activeElement).toBe(screen.getByTestId('further-ahead-heading'));
      raf.mockRestore();
    });

    it('a request for Coming up does not let the next frame move focus back onto the tab button', () => {
      const frames = [];
      const raf = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => { frames.push(cb); return 0; });
      const { setRequest } = renderWithRequest();
      act(() => setRequest({ id: 'coming-up', nonce: 1 }));
      act(() => frames.forEach((cb) => cb()));
      expect(document.activeElement).toBe(screen.getByTestId('further-ahead-heading'));
      raf.mockRestore();
    });

    it('does nothing at all when no request has ever arrived', () => {
      // A null `tabRequest` is the resting state for every session that never opens the overlay,
      // and it must not steal the initial selection.
      renderWithRequest();
      expect(screen.getByRole('tab', { name: /^At a Glance/ })).toHaveAttribute('aria-selected', 'true');
      expect(screen.queryByTestId('map-pane')).toBeNull();
    });
  });
});

describe('WindowFirstShell — what the shell tells App about the tab and the map view', () => {
  const MAP = <p data-testid="map-pane">map pane</p>;

  function renderWithMap(extra = {}) {
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
    const onTabChange = vi.fn();
    const view = render(
      <WindowFirstShell
        onOpenSettings={vi.fn()} onSignOut={vi.fn()} onShowOnMap={vi.fn()}
        locations={[]} mapPane={MAP} onTabChange={onTabChange} {...extra}
      />,
    );
    return { ...view, onTabChange };
  }

  it('fires onTabChange with the opening tab on mount, before any click', () => {
    const { onTabChange } = renderWithMap();
    expect(onTabChange).toHaveBeenCalledWith('plan');
  });

  it('fires onTabChange on every tab switch, and with map while the view is open', () => {
    const { onTabChange } = renderWithMap();
    onTabChange.mockClear();
    fireEvent.click(screen.getByRole('tab', { name: 'Coming up' }));
    // Coming up is a door onto Further ahead (O2), so the tab in force is still Plan and nothing changed.
    expect(onTabChange).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('window-first-tab-map'));
    expect(onTabChange).toHaveBeenLastCalledWith('map');
    fireEvent.click(screen.getByTestId('map-screen-close'));
    expect(onTabChange).toHaveBeenLastCalledWith('plan');
  });

  it('the page keeps one layout: no flex-column recast and no wf-body--map panel, opened or not', () => {
    renderWithMap();
    fireEvent.click(screen.getByTestId('window-first-tab-map'));
    expect(screen.getByTestId('window-first-shell').className).not.toMatch(/flex-1|min-h-0|flex-col/);
    expect(document.querySelector('.wf-body--map')).toBeNull();
    expect(screen.queryByTestId('window-first-panel-map')).toBeNull();
  });
});

describe('WindowFirstShell — the forecast dates reach the Coming up cards (plan §11.24)', () => {
  const MAP_FEED = {
    ...FEED,
    entries: [{
      id: 'spring-tide:2026-08-12:2026-12-31',
      type: 'spring-tide',
      startDate: '2026-08-12',
      // Over the day-chip cap, so the entry keeps its single door (a short or one-day one gets chips).
      endDate: '2026-12-31',
      kind: 'ALMANAC',
      family: 'coastal',
      title: 'Spring tide run',
      kindTag: 'Almanac',
      facts: [],
      action: { label: 'Show coastal spots for 12 Aug →', kind: 'coastal-spots', date: '2026-08-12' },
    }],
  };

  it('draws a live map door when the shell is handed the entry’s date', async () => {
    getAlmanac.mockResolvedValue(MAP_FEED);
    renderShell({}, { forecastDates: ['2026-08-08', '2026-08-12'] });
    await openComingUp();
    expect(screen.getByRole('button', { name: /Show coastal spots/ })).toBeInTheDocument();
  });

  it('withholds it, with the reason, when the date is not among the forecast dates', async () => {
    getAlmanac.mockResolvedValue(MAP_FEED);
    renderShell({}, { forecastDates: ['2026-08-08', '2026-08-09'] });
    await openComingUp();
    expect(screen.queryByRole('button', { name: /Spring tide run|Coastal spots/ })).toBeNull();
    expect(screen.getByTestId('coming-up-card').tagName).toBe('DIV');
    expect(screen.getByTestId('coming-up-action'))
      .toHaveTextContent('Coastal spots: no forecast for 12 Aug');
  });

  it('withholds it when the forecast dates are known to be empty', async () => {
    getAlmanac.mockResolvedValue(MAP_FEED);
    renderShell({}, { forecastDates: [] });
    await openComingUp();
    expect(screen.queryByRole('button', { name: /Spring tide run|Coastal spots/ })).toBeNull();
  });

  it('leaves it live when no forecast dates are passed — not known yet is no claim', async () => {
    getAlmanac.mockResolvedValue(MAP_FEED);
    renderShell();
    await openComingUp();
    expect(screen.getByRole('button', { name: /Show coastal spots/ })).toBeInTheDocument();
  });

  it('⚠️ withholds every door when the shell has NO map door, even while the dates are not known yet', async () => {
    // Not known yet is no claim about the DATES, but with nothing to open the control must not be
    // drawn (a control that opens nothing is banned): the shell hands the pane "known empty".
    getAlmanac.mockResolvedValue(MAP_FEED);
    renderShell({}, { onOpenMapTab: undefined });
    await openComingUp();
    expect(screen.queryByRole('button', { name: /Show coastal spots|Spring tide run/ })).toBeNull();
    expect(screen.getByTestId('coming-up-card').tagName).toBe('DIV');
  });
});

describe('WindowFirstShell — the REAL Coming up card and day chip reach onOpenMapTab (C4)', () => {
  const CARD_ENTRY = {
    id: 'spring-tide:2026-08-12:2026-12-31', type: 'spring-tide', startDate: '2026-08-12',
    endDate: '2026-12-31', kind: 'ALMANAC', family: 'coastal', title: 'Spring tide run',
    kindTag: 'Almanac', facts: [],
    action: { label: 'Show coastal spots for 12 Aug →', kind: 'coastal-spots', date: '2026-08-12' },
  };
  const CHIP_ENTRY = {
    id: 'meteor:2026-08-12:2026-08-12', type: 'meteor', startDate: '2026-08-12',
    endDate: '2026-08-12', kind: 'ALMANAC', family: 'night-sky', title: 'Perseids',
    kindTag: 'Almanac', facts: [],
    action: { label: 'Show dark-sky spots for 12 Aug →', kind: 'dark-sky-spots', date: '2026-08-12' },
  };
  const DATES = ['2026-08-08', '2026-08-12'];

  it('the card sends the coming-up trigger with its subject filter and the Plan lens', async () => {
    getAlmanac.mockResolvedValue({ ...FEED, entries: [CARD_ENTRY] });
    const { onOpenMapTab } = renderShell({}, { forecastDates: DATES });
    await openComingUp();

    fireEvent.click(screen.getByRole('button', { name: /Show coastal spots/ }));

    expect(onOpenMapTab).toHaveBeenCalledExactlyOnceWith({
      kind: 'coming-up', filterAction: 'SEASCAPE', darkSky: false, label: 'Spring tide run',
      date: '2026-08-12', inPlace: false, minRating: null, limitMinutes: 45,
      outlierKeys: expect.any(Set),
    });
  });

  it('a real day chip sends the dark-sky trigger, headed by the card and the day', async () => {
    getAlmanac.mockResolvedValue({ ...FEED, entries: [CHIP_ENTRY] });
    const { onOpenMapTab } = renderShell({}, { forecastDates: DATES });
    await openComingUp();

    fireEvent.click(screen.getAllByTestId('coming-up-day-chip')[0]);

    expect(onOpenMapTab).toHaveBeenCalledTimes(1);
    expect(onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'coming-up', darkSky: true, filterAction: null, date: '2026-08-12',
      inPlace: false,
    }));
    expect(onOpenMapTab.mock.calls[0][0].label).toMatch(/^Perseids · /);
  });
});
