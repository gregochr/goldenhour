import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { getAlmanac } from '../api/almanacApi.js';

/**
 * The one screen the shell became when the tabs went (`docs/engineering/one-screen-plan.md` O3): no tab list, This week
 * over Further ahead, the ☰ menu, and Operations as an admin-only full-screen view the shell draws around the node `App`
 * hands it. (This file began as the tab bar's suite; what survived is what the shell JOINS: the lens bar, Find, the feed's
 * fetch and the Further ahead doors.)
 *
 * <p>Scoped the way {@code WindowFirstShellSheet.test.jsx} scopes itself, and for the same reason: every rule here is
 * about how the shell JOINS two things, none of them reachable from a component's own test.
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


/** An Operations pane: the node `App` hands an admin and no one else. */
const OPS = <p data-testid="ops-pane">operations pane</p>;
const MAP = <p data-testid="map-pane">map pane</p>;

/** Lets the feed request every mount makes settle, which is what actually paints Further ahead's rows. */
const settle = async () => {
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

describe('WindowFirstShell — the screen has no tabs', () => {
  it('draws no tab list, no tab and no tab panel, and no "Map" button', () => {
    renderShell({}, { mapPane: MAP, operationsPane: OPS });
    expect(screen.queryByRole('tablist')).toBeNull();
    expect(screen.queryAllByRole('tab')).toHaveLength(0);
    expect(screen.queryByRole('tabpanel')).toBeNull();
    expect(screen.queryByTestId('window-first-tabs')).toBeNull();
    expect(screen.queryByTestId('window-first-tabrow')).toBeNull();
    expect(screen.queryByTestId('window-first-tab-map')).toBeNull();
  });

  it('opens on This week over Further ahead, with the lens bar and the map view closed', async () => {
    renderShell({}, { mapPane: MAP });
    await settle();
    expect(screen.getByTestId('window-first-pane')).toBeVisible();
    expect(screen.getByTestId('this-week-heading')).toHaveTextContent('This week');
    expect(screen.getByTestId('further-ahead')).toBeInTheDocument();
    expect(screen.getByTestId('window-first-lens')).toBeInTheDocument();
    // Every device opens here (owner, 2026-11-23): the view exists only once a door has opened it.
    expect(screen.queryByTestId('map-screen')).toBeNull();
  });

  it('keeps the masthead and its tick line, and a rule under the masthead rather than under a tab row', async () => {
    renderShell();
    await settle();
    expect(screen.getByTestId('window-first-masthead')).toBeInTheDocument();
    expect(screen.getByTestId('window-first-tickline')).toBeInTheDocument();
    expect(screen.queryByTestId('window-first-tabrule')).toBeNull();
  });
});

describe('WindowFirstShell — Operations, the admin\'s full-screen view (one-screen-plan.md D9)', () => {
  /** The shell with the Operations props an admin\'s `App` hands it. */
  const renderOps = (extra = {}, overrides = {}) => {
    const onOpenOperations = vi.fn();
    const onCloseOperations = vi.fn();
    const rendered = renderShell(overrides, {
      operationsPane: OPS, onOpenOperations, onCloseOperations, mapPane: MAP, ...extra,
    });
    return { ...rendered, onOpenOperations, onCloseOperations };
  };
  const openMenu = () => fireEvent.click(screen.getByTestId('masthead-menu-button'));

  it('draws no Operations content and no menu row when no pane was handed over (a non-admin)', async () => {
    renderShell({}, { onOpenOperations: vi.fn() });
    await settle();
    openMenu();
    expect(screen.queryByTestId('masthead-menu-operations')).toBeNull();
    expect(screen.queryByTestId('operations-screen')).toBeNull();
  });

  it('draws no row without an opener either, even with a pane: the row needs both', async () => {
    renderShell({}, { operationsPane: OPS });
    await settle();
    openMenu();
    expect(screen.queryByTestId('masthead-menu-operations')).toBeNull();
  });

  it('draws the row for an admin, after Guide and before Sign out', async () => {
    renderOps();
    await settle();
    openMenu();
    const rows = [...screen.getByTestId('masthead-menu').querySelectorAll('.wf-mast-menu-row')].map((r) => r.textContent);
    expect(rows).toEqual(['Settings', 'Change home postcode', 'Guide', 'Operations', 'Sign out']);
  });

  it('the row takes every dialog down and then asks App to open Operations', async () => {
    const { onOpenOperations } = renderOps();
    await settle();
    openMenu();
    fireEvent.click(screen.getByTestId('masthead-menu-operations'));
    expect(onOpenOperations).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('masthead-menu')).toBeNull();
  });

  it('is not mounted while closed: the pane\'s content is not rendered until the address opens it', async () => {
    renderOps();
    await settle();
    expect(screen.queryByTestId('operations-screen')).toBeNull();
    expect(screen.queryByTestId('ops-pane')).toBeNull();
    expect(screen.getByTestId('window-first-shell-col')).not.toHaveAttribute('hidden');
  });

  it('open: draws the labelled view with the pane, hides the screen and the masthead, and focuses its heading', async () => {
    renderOps({ operationsOpen: true });
    await settle();
    expect(screen.getByRole('region', { name: 'Operations' })).toBe(screen.getByTestId('operations-screen'));
    expect(screen.getByTestId('ops-pane')).toBeInTheDocument();
    expect(screen.getByTestId('operations-title')).toHaveFocus();
    // Hidden, not just covered: nothing behind it is reachable by Tab or a screen reader.
    expect(screen.getByTestId('window-first-shell-col')).toHaveAttribute('hidden');
    expect(screen.getByTestId('window-first-shell-col')).toContainElement(screen.getByTestId('window-first-masthead'));
  });

  it('open without a pane is ignored: a non-admin\'s #manage never draws a view', async () => {
    renderShell({}, { operationsOpen: true, onOpenOperations: vi.fn() });
    await settle();
    expect(screen.queryByTestId('operations-screen')).toBeNull();
    expect(screen.getByTestId('window-first-shell-col')).not.toHaveAttribute('hidden');
  });

  it('"‹ PhotoCast" asks App to close it', async () => {
    const { onCloseOperations } = renderOps({ operationsOpen: true });
    await settle();
    fireEvent.click(screen.getByTestId('operations-back'));
    expect(onCloseOperations).toHaveBeenCalledTimes(1);
  });

  it('closing returns focus to the ☰ button, by whichever route it closed', async () => {
    const { rerender, ...rest } = renderOps({ operationsOpen: true });
    await settle();
    expect(screen.getByTestId('operations-title')).toHaveFocus();
    rerender(<WindowFirstShell {...rest} operationsOpen={false} />);
    expect(screen.queryByTestId('operations-screen')).toBeNull();
    expect(screen.getByTestId('masthead-menu-button')).toHaveFocus();
    expect(screen.getByTestId('window-first-shell-col')).not.toHaveAttribute('hidden');
  });

  it('unmounts the pane on close, so its polls stop, and mounts it fresh on the next open', async () => {
    const { rerender, ...rest } = renderOps({ operationsOpen: true });
    await settle();
    expect(screen.getByTestId('ops-pane')).toBeInTheDocument();
    rerender(<WindowFirstShell {...rest} operationsOpen={false} />);
    expect(screen.queryByTestId('ops-pane')).toBeNull();
    rerender(<WindowFirstShell {...rest} operationsOpen />);
    expect(screen.getByTestId('ops-pane')).toBeInTheDocument();
  });

  it('is never a dialog or a Back layer: no aria-modal anywhere and no history layer registered', async () => {
    renderOps({ operationsOpen: true });
    await settle();
    expect(document.querySelector('[aria-modal]')).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('opening it takes the screen\'s dialogs down in the same commit (Find here)', async () => {
    const { rerender, ...rest } = renderOps();
    await settle();
    fireEvent.click(screen.getByRole('button', { name: /Find a place, region or day/ }));
    expect(await screen.findByTestId('plan-search')).toBeInTheDocument();
    rerender(<WindowFirstShell {...rest} operationsOpen />);
    expect(screen.queryByTestId('plan-search')).toBeNull();
  });

  it('opening it closes the map view, and closing it does not bring the view back', async () => {
    const { rerender, ...rest } = renderOps();
    await settle();
    const doors = { current: null };
    rerender(<WindowFirstShell {...rest} registerOpenMap={(d) => { doors.current = d; }} />);
    act(() => doors.current.open({}));
    expect(screen.getByTestId('map-screen')).not.toHaveAttribute('hidden');
    rerender(<WindowFirstShell {...rest} registerOpenMap={(d) => { doors.current = d; }} operationsOpen />);
    expect(screen.getByTestId('map-screen')).toHaveAttribute('hidden');
    rerender(<WindowFirstShell {...rest} registerOpenMap={(d) => { doors.current = d; }} operationsOpen={false} />);
    expect(screen.getByTestId('map-screen')).toHaveAttribute('hidden');
  });

  it('withholds Find and the lens, and offers no Ask entry, while it is up', async () => {
    renderOps({ operationsOpen: true });
    await settle();
    // The masthead (with Find) lives in the hidden column; the view itself carries neither.
    expect(screen.getByTestId('operations-screen').querySelector('[data-testid="window-first-search"]')).toBeNull();
    expect(screen.getByTestId('operations-screen').querySelector('[data-testid="ask-field"]')).toBeNull();
    expect(screen.getByTestId('window-first-shell-col')).toHaveAttribute('hidden');
  });
});

describe('WindowFirstShell — the ☰ menu at every width', () => {
  it('replaces the inline cog, guide and Sign out: they are rows of the menu, closed at rest', () => {
    renderShell();
    expect(screen.getByTestId('masthead-menu-button')).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('window-first-settings')).toBeNull();
    expect(screen.queryByTestId('window-first-guide')).toBeNull();
    expect(screen.queryByTestId('window-first-signout')).toBeNull();
    fireEvent.click(screen.getByTestId('masthead-menu-button'));
    expect(screen.getByTestId('window-first-settings')).toBeInTheDocument();
    expect(screen.getByTestId('window-first-signout')).toBeInTheDocument();
  });

  it('Settings and Sign out are the cog\'s and the button\'s handlers, unchanged', () => {
    const { onOpenSettings, onSignOut } = renderShell();
    fireEvent.click(screen.getByTestId('masthead-menu-button'));
    fireEvent.click(screen.getByTestId('window-first-settings'));
    expect(onOpenSettings).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByTestId('masthead-menu-button'));
    fireEvent.click(screen.getByTestId('window-first-signout'));
    expect(onSignOut).toHaveBeenCalledTimes(1);
  });

  it('opening Settings from the menu takes an open Find down first', async () => {
    renderShell();
    fireEvent.click(screen.getByRole('button', { name: /Find a place, region or day/ }));
    expect(await screen.findByTestId('plan-search')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('masthead-menu-button'));
    fireEvent.click(screen.getByTestId('window-first-settings'));
    expect(screen.queryByTestId('plan-search')).toBeNull();
  });
});

describe('WindowFirstShell — Find on the one screen (find-and-favourites-plan.md F1)', () => {
  const FIND_CONTROLS = ['masthead-menu-button', 'window-first-origin-chip', 'window-first-search'];

  /** The band's buttons in DOM order, as an exact list — a count passes when one is swapped. */
  const mastheadControls = () => within(screen.getByTestId('window-first-masthead'))
    .getAllByRole('button').map((b) => b.getAttribute('data-testid'));

  it.each([
    ['at home', {}, FIND_CONTROLS, 'Home · Newcastle'],
    ['away, where the way home stays', { origin: { id: 7, name: 'The Lake District', baseName: 'Keswick' } },
      [...FIND_CONTROLS, 'window-first-origin-home'],
      'The Lake District · from Keswick'],
  ])('⚠️ offers both Find doors, %s — the origin button and the Find button', async (
    _label, overrides, controls, place,
  ) => {
    renderShell(overrides);
    await settle();

    expect(mastheadControls()).toEqual(controls);
    expect(screen.getByRole('button', { name: /Find a place, region or day/ })).toBeInTheDocument();
    expect(screen.queryByTestId('window-first-origin-statement')).toBeNull();
    expect(screen.getByTestId('window-first-origin-chip')).toHaveTextContent(place);
  });

  it('keeps the postcode nudge, because it opens settings rather than Find', async () => {
    // CLAUDE.md: never re-gate the postcode input. The nudge is the origin button's empty state, but
    // its job is a setting. Find sits beside it.
    const { onOpenSettings } = renderShell({ homePlace: null });
    await settle();

    expect(mastheadControls()).toEqual(['masthead-menu-button', 'masthead-set-postcode', 'window-first-search']);
    fireEvent.click(screen.getByRole('button', { name: 'Set a postcode for light and drive times' }));
    expect(onOpenSettings).toHaveBeenCalledTimes(1);
  });

  it('opens Find from the place in the tick line', async () => {
    renderShell();
    await settle();
    fireEvent.click(within(screen.getByTestId('window-first-tickline')).getByText('Home · Newcastle'));
    expect(await screen.findByTestId('plan-search')).toBeInTheDocument();
  });

  it('⚠️ offers Find in the map view, with the "drive times from here" caption kept on its origin chip', async () => {
    // The caption is true on the map, where every drive time and leave-by on screen is measured from this place. The
    // masthead behind the view is inert and no longer carries the caption: the view's own header does.
    const doors = { current: null };
    renderShell({}, { mapPane: MAP, registerOpenMap: (d) => { doors.current = d; } });
    act(() => doors.current.open({}));
    expect(screen.getByTestId('map-screen-find')).toBeInTheDocument();
    expect(screen.getByTestId('map-screen-origin')).toHaveTextContent('Home · Newcastle');
    expect(screen.getByTestId('map-screen-origin')).toHaveTextContent('drive times from here');
    expect(screen.queryByTestId('masthead-origin-caption')).toBeNull();

    fireEvent.click(screen.getByTestId('map-screen-close'));
    expect(screen.getByTestId('window-first-origin-chip')).toHaveTextContent('Home · Newcastle');
    expect(screen.queryByTestId('masthead-origin-caption')).toBeNull();
  });
});

describe('WindowFirstShell — what the screen holds', () => {
  it('draws the window summary inside the screen\'s column, never inside Further ahead', async () => {
    // ⚠️ A RECORDED REVERSAL (heat-field-plan.md D1, 2026-08-18): the strip replaced the day rail and sits INSIDE the
    // screen's pane, under the lens bar. The harness is given strip data on purpose: with the file's default
    // (`heatStripCards: []`, `heatSpots: []`) the strip withdraws everywhere and "in the pane" would pass vacuously.
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
    expect(screen.getByTestId('window-first-pane')).not.toHaveAttribute('hidden');
    expect(within(screen.getByTestId('further-ahead')).queryByTestId('wf-heat-strip')).toBeNull();
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
});

describe('WindowFirstShell — when the feed is fetched', () => {
  it('fetches immediately on mount, before the reader scrolls anywhere (plan D13)', async () => {
    // This week is every reader's first screen. The "Further ahead ↓" jump's badge (D3/D4) needs to know about
    // arrivals whether or not the reader ever scrolls there, so the fetch is eager: see useComingUpFeed.js's class doc.
    renderShell();
    await settle();

    expect(getAlmanac).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('further-ahead-jump')).toBeInTheDocument();
  });

  it('paints the already-fetched feed in Further ahead with nothing pressed', async () => {
    renderShell();
    await settle();

    expect(getAlmanac).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('Perseids')).toBeInTheDocument();
  });

  it('does not fetch again when the reader jumps to Further ahead and back', async () => {
    // The latch: this feed is rebuilt by the backend once a UTC day, so a second request buys nothing.
    renderShell();
    await settle();
    fireEvent.click(screen.getByTestId('further-ahead-jump'));
    await settle();
    fireEvent.click(screen.getByTestId('further-ahead-jump'));
    await settle();

    expect(getAlmanac).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Perseids')).toBeInTheDocument();
  });

  it('refetches on a date roll even though the reader never looked at Further ahead (D13)', async () => {
    // The eager fetch's whole point: the latch is keyed on the date, so a badge computed from a stale feed does not
    // survive past midnight just because nobody ever scrolled to the section it came from.
    const { withContext } = renderShell();
    await settle();
    expect(getAlmanac).toHaveBeenCalledTimes(1);

    withContext({ todayStr: '2026-08-09', tomorrowStr: '2026-08-10' });
    await settle();

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
    await settle();
    // The first request is still out. Midnight passes; the second goes out and comes back first.
    withContext({ todayStr: '2026-08-09', tomorrowStr: '2026-08-10' });
    await settle();
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
    await settle();

    expect(await screen.findByTestId('coming-up-error')).toBeInTheDocument();
    expect(screen.queryByTestId('coming-up-empty')).toBeNull();
  });

  it('does not retry in a loop after a failure — the reader presses the button', async () => {
    // The latch stays SET on failure deliberately. Without that a re-render would re-enter the
    // effect and hammer a backend that has just said no.
    getAlmanac.mockRejectedValue(new Error('502'));
    renderShell();
    await settle();
    await settle();

    expect(getAlmanac).toHaveBeenCalledTimes(1);

    getAlmanac.mockResolvedValue(FEED);
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await settle();

    expect(getAlmanac).toHaveBeenCalledTimes(2);
    expect(screen.getByText('Perseids')).toBeInTheDocument();
  });
});

describe('WindowFirstShell — what the shell tells App about the screen', () => {
  function renderWithMap(extra = {}) {
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
    const onScreenChange = vi.fn();
    const doors = { current: null };
    const view = render(
      <WindowFirstShell
        onOpenSettings={vi.fn()} onSignOut={vi.fn()} onShowOnMap={vi.fn()}
        locations={[]} mapPane={MAP} onScreenChange={onScreenChange}
        registerOpenMap={(d) => { doors.current = d; }} {...extra}
      />,
    );
    return { ...view, onScreenChange, doors };
  }

  it('fires onScreenChange with plan on mount, before any press', () => {
    const { onScreenChange } = renderWithMap();
    expect(onScreenChange).toHaveBeenCalledWith('plan');
  });

  it('says map while the view is open, plan again when it closes, and operations while that is up', () => {
    const { onScreenChange, doors, rerender } = renderWithMap({ operationsPane: OPS });
    onScreenChange.mockClear();
    act(() => doors.current.open({}));
    expect(onScreenChange).toHaveBeenLastCalledWith('map');
    fireEvent.click(screen.getByTestId('map-screen-close'));
    expect(onScreenChange).toHaveBeenLastCalledWith('plan');
    rerender(
      <WindowFirstShell
        onOpenSettings={vi.fn()} onSignOut={vi.fn()} locations={[]} mapPane={MAP} onScreenChange={onScreenChange}
        operationsPane={OPS} operationsOpen
      />,
    );
    expect(onScreenChange).toHaveBeenLastCalledWith('operations');
  });

  it('says nothing is showing when the shell goes (a crash under the boundary, a sign-out)', () => {
    const { onScreenChange, unmount } = renderWithMap();
    onScreenChange.mockClear();
    unmount();
    expect(onScreenChange).toHaveBeenLastCalledWith(null);
  });

  it('the page keeps one layout: no flex-column recast and no wf-body--map panel, opened or not', () => {
    const { doors } = renderWithMap();
    act(() => doors.current.open({}));
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
    await settle();
    expect(screen.getByRole('button', { name: /Show coastal spots/ })).toBeInTheDocument();
  });

  it('withholds it, with the reason, when the date is not among the forecast dates', async () => {
    getAlmanac.mockResolvedValue(MAP_FEED);
    renderShell({}, { forecastDates: ['2026-08-08', '2026-08-09'] });
    await settle();
    expect(screen.queryByRole('button', { name: /Spring tide run|Coastal spots/ })).toBeNull();
    expect(screen.getByTestId('coming-up-card').tagName).toBe('DIV');
    expect(screen.getByTestId('coming-up-action'))
      .toHaveTextContent('Coastal spots: no forecast for 12 Aug');
  });

  it('withholds it when the forecast dates are known to be empty', async () => {
    getAlmanac.mockResolvedValue(MAP_FEED);
    renderShell({}, { forecastDates: [] });
    await settle();
    expect(screen.queryByRole('button', { name: /Spring tide run|Coastal spots/ })).toBeNull();
  });

  it('leaves it live when no forecast dates are passed — not known yet is no claim', async () => {
    getAlmanac.mockResolvedValue(MAP_FEED);
    renderShell();
    await settle();
    expect(screen.getByRole('button', { name: /Show coastal spots/ })).toBeInTheDocument();
  });

  it('⚠️ withholds every door when the shell has NO map door, even while the dates are not known yet', async () => {
    // Not known yet is no claim about the DATES, but with nothing to open the control must not be
    // drawn (a control that opens nothing is banned): the shell hands the pane "known empty".
    getAlmanac.mockResolvedValue(MAP_FEED);
    renderShell({}, { onOpenMapTab: undefined });
    await settle();
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

  it('the card sends the coming-up trigger with its subject filter and the lens', async () => {
    getAlmanac.mockResolvedValue({ ...FEED, entries: [CARD_ENTRY] });
    const { onOpenMapTab } = renderShell({}, { forecastDates: DATES });
    await settle();

    fireEvent.click(screen.getByRole('button', { name: /Show coastal spots/ }));

    expect(onOpenMapTab).toHaveBeenCalledExactlyOnceWith({
      kind: 'further-ahead', filterAction: 'SEASCAPE', darkSky: false, label: 'Spring tide run',
      date: '2026-08-12', inPlace: false, minRating: null, limitMinutes: 45,
      outlierKeys: expect.any(Set),
    });
  });

  it('a real day chip sends the dark-sky trigger, headed by the card and the day', async () => {
    getAlmanac.mockResolvedValue({ ...FEED, entries: [CHIP_ENTRY] });
    const { onOpenMapTab } = renderShell({}, { forecastDates: DATES });
    await settle();

    fireEvent.click(screen.getAllByTestId('coming-up-day-chip')[0]);

    expect(onOpenMapTab).toHaveBeenCalledTimes(1);
    expect(onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'further-ahead', darkSky: true, filterAction: null, date: '2026-08-12',
      inPlace: false,
    }));
    expect(onOpenMapTab.mock.calls[0][0].label).toMatch(/^Perseids · /);
  });
});
