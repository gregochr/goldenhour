import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

/**
 * The Plan's masthead, and specifically the status pill it inherited from the app header it
 * replaced.
 *
 * <p>Scoped its own file the way {@code WindowFirstShellTabs} and {@code WindowFirstShellSheet} are:
 * every rule here is about how the shell JOINS the pill to the masthead — that it is a slot rather
 * than a component, where it sits among the two buttons, and that a dead backend cannot take it with
 * the pane. None of those is reachable from {@code HealthIndicator}'s own test, which knows nothing
 * about a masthead.
 */
const TODAY = '2026-08-08';

// The phone masthead (map-mobile-compact-plan.md C1) is a `useIsMobile()` branch in the shell. jsdom's
// `matchMedia` defaults to the desktop answer, so every pre-existing test here keeps meaning what it
// did; the phone twins at the bottom flip this flag.
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

/** Stands in for the real pill — this file's subject is the slot, not what fills it. */
const PILL = <span data-testid="health-indicator">UP</span>;

const ctx = () => ({
  briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [] },
  loading: false,
  // The heat strip's thumbnails replaced the day rail's tiles at P2 (plan D1). Empty here, with an
  // empty catalogue beside it: the strip withdraws entirely without spots to draw, which keeps
  // these files about the shell's wiring rather than about a canvas.
  heatStripCards: [],
  heatSpots: [],
  heatPointSets: new Map(),
  windowCards: [],
  paneItems: [],
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
    floor: { id: 'any', min: null, label: 'Any rating' },
    floorId: 'any',
    minRating: null,
    selectFloor: vi.fn(),
  },
});

const renderShell = (extraProps = {}, ctxOverrides = {}) => {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing')
    .mockReturnValue({ ...ctx(), ...ctxOverrides });
  const props = {
    onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(), locations: [],
    ...extraProps,
  };
  return { ...props, ...render(<WindowFirstShell {...props} />) };
};

beforeEach(() => {
  localStorage.clear();
  mockIsMobile = false;
});
afterEach(() => vi.restoreAllMocks());

describe('WindowFirstShell — the masthead status pill', () => {
  // The admin gate in full, and it is the same one the Operations tab uses: `App` holds `isAdmin`
  // and withholds the node, so nothing role-shaped crosses this boundary (plan §5c). A pilot user
  // has no use for a build id or a WorldTides latency, which is why the arm shipped without it.
  it('renders no pill when no node was handed over', () => {
    renderShell();
    expect(screen.queryByTestId('health-indicator')).toBeNull();
    // The rest of the masthead is unaffected — the gap collapses on its own.
    expect(screen.getByTestId('window-first-settings')).toBeInTheDocument();
    expect(screen.getByTestId('window-first-signout')).toBeInTheDocument();
  });

  it('renders the pill in the masthead when one is handed over', () => {
    renderShell({ healthPill: PILL });
    const masthead = screen.getByTestId('window-first-masthead');
    expect(masthead).toContainElement(screen.getByTestId('health-indicator'));
  });

  // Reading first, then the two controls. A pill dropped between the cog and Sign out would
  // split a pair the reader treats as one group.
  it('places it ahead of the settings cog and Sign out', () => {
    renderShell({ healthPill: PILL });
    const pill = screen.getByTestId('health-indicator');
    const cog = screen.getByTestId('window-first-settings');
    expect(pill.compareDocumentPosition(cog) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  /**
   * The case the pill exists for. `contentDisabled` is `opacity-50 pointer-events-none`, and the
   * masthead is deliberately outside it so a DOWN backend cannot strand a reader with no working
   * control — this file's own component says so at length about the cog and Sign out. The pill is
   * the one element in that row whose whole purpose is the DOWN state: greying it would make the
   * status readout unreadable and unopenable at exactly the moment it is the only thing worth
   * reading.
   */
  it('stays outside the greying a DOWN backend applies to the pane', () => {
    renderShell({ healthPill: PILL, contentDisabled: true });

    const masthead = screen.getByTestId('window-first-masthead');
    expect(masthead.className).not.toContain('pointer-events-none');
    expect(masthead.className).not.toContain('opacity-50');
    // And the pane it is NOT part of does take the treatment, so this is a statement about where
    // the boundary is rather than about the treatment having been dropped.
    expect(screen.getByTestId('window-first-pane').className).toContain('pointer-events-none');
  });
});

/**
 * The lit band — the masthead's second job.
 *
 * <p>The arm shipped with a slim bar that had brand presence taken out of it: a 20px wordmark, no
 * kicker, and a spine reduced to a tick. This restores the presence without restoring the height,
 * and gives the band something to do — the rule under the wordmark draws today's light, so the top
 * of the screen is the first piece of forecast rather than ornament.
 *
 * <p>Everything about how the rule itself is drawn lives in {@code MastheadLight.test.jsx}. What is
 * pinned here is only what the shell is responsible for: which lockup it asks for, that the band is
 * part of the masthead, and that the nudge always has somewhere to go.
 */
describe('WindowFirstShell — the lit band', () => {
  const LIGHT = {
    label: 'Home · NE66 1NG',
    shortLabel: 'NE66 1NG',
    civilDawn: '05:32',
    sunrise: '06:04',
    sunset: '19:58',
    civilDusk: '20:31',
    stops: [{ key: 'SUNRISE', position: 25.28 }, { key: 'SUNSET', position: 74.86 }],
  };

  it('asks for the masthead lockup, which carries the wordmark and the kicker', () => {
    // Not `compact`. That variant drops the kicker and takes the wordmark to 20px, which is the
    // flatness this work exists to fix — and it is still in use elsewhere, so a revert here would
    // be invisible to every other test.
    renderShell();

    expect(screen.getByTestId('brand-lockup')).toHaveAttribute('data-variant', 'masthead');
    expect(screen.getByText('Field guide to light')).toBeInTheDocument();
  });

  it('puts the light rule inside the band, under the lockup', () => {
    renderShell({ light: LIGHT });
    const masthead = screen.getByTestId('window-first-masthead');

    expect(masthead).toContainElement(screen.getByTestId('masthead-light-rule'));
    expect(masthead).toContainElement(screen.getByTestId('masthead-light-times'));
    const lockup = screen.getByTestId('brand-lockup');
    expect(lockup.compareDocumentPosition(screen.getByTestId('masthead-light-rule'))
      & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('hands the day straight through, rather than deriving one of its own', () => {
    renderShell({ light: LIGHT });
    expect(screen.getByTestId('masthead-light-times')).toHaveTextContent('06:04');
    expect(screen.getByTestId('masthead-light-times')).toHaveTextContent('19:58');
  });

  it('sends the nudge to the postcode field when one handler is given', () => {
    // ⚠️ `homePlace: null` as well as `light: null`. `homePlace` is the AUTHORITY for this state
    // and the light is consulted only while it is unknown, so a fixture with a saved home renders
    // the origin button and this test would look for a control that is not there. That narrowing is
    // deliberate — see `MastheadTickLine`'s note on the save-flicker it removes.
    const onSetPostcode = vi.fn();
    renderShell({ light: null, onSetPostcode }, { homePlace: null });

    fireEvent.click(screen.getByTestId('masthead-set-postcode'));
    expect(onSetPostcode).toHaveBeenCalledTimes(1);
  });

  it('falls back to plain Settings rather than leaving the nudge inert', () => {
    // The nudge's whole job is getting a postcode saved. A caller that forgets the focused handler
    // should get the general settings screen, not a button that does nothing — a dead end here
    // leaves the rule permanently dim, which is the state the nudge exists to end.
    const { onOpenSettings } = renderShell({ light: null }, { homePlace: null });

    fireEvent.click(screen.getByTestId('masthead-set-postcode'));
    expect(onOpenSettings).toHaveBeenCalledTimes(1);
  });

  /**
   * The band's control inventory, which M3 doubled.
   *
   * <p>Before the tick line it was exactly ⚙ and Sign out, plus a nudge in the one state that needs
   * one — and two sibling files still assert that pair. What the tick line adds is fixed and
   * enumerable: an origin button, a search affordance, and a home button ONLY while the origin has
   * moved. Asserted as the whole set rather than as a count, because a count passes when one
   * control is swapped for another — which is precisely the substitution a later phase could make
   * without noticing.
   */
  it('⚠️ keeps the origin button when only the LIGHT says there is no home', () => {
    // The narrowing M3's review forced. `homePlace` is the authority; a stale or 204 light beside a
    // saved home must not delete the origin control, which is what an OR did on the round trip
    // right after a reader saved their postcode.
    renderShell({ light: null });

    expect(screen.queryByTestId('masthead-set-postcode')).toBeNull();
    expect(screen.getByTestId('window-first-origin-chip')).toHaveTextContent('Home · Newcastle');
  });

  it('carries exactly the tick line\'s controls beside the two it already had', () => {
    renderShell({ light: LIGHT });
    const masthead = screen.getByTestId('window-first-masthead');

    expect(within(masthead).getAllByRole('button').map((b) => b.getAttribute('data-testid')))
      .toEqual(['window-first-settings', 'window-first-guide', 'window-first-signout',
        'window-first-origin-chip', 'window-first-search']);
    // No home button at home, and no nudge once the light has resolved.
    expect(screen.queryByTestId('window-first-origin-home')).toBeNull();
    expect(screen.queryByTestId('masthead-set-postcode')).toBeNull();
  });

  it('swaps the origin button for the nudge when no home is saved, rather than adding one', () => {
    // ⚠️ A SWAP, not an addition. The nudge IS the origin button's empty state (M3.1), so the band
    // gains no control — which is what keeps the phone row the same width in the state that also
    // has the longest label.
    renderShell({ light: null }, { homePlace: null });
    const masthead = screen.getByTestId('window-first-masthead');

    expect(within(masthead).getAllByRole('button').map((b) => b.getAttribute('data-testid')))
      .toEqual(['window-first-settings', 'window-first-guide', 'window-first-signout',
        'masthead-set-postcode', 'window-first-search']);
  });

  it('stays out of the greying a DOWN backend applies, like the rest of the band', () => {
    // The nudge is a route to a setting, and the pane being dead is no reason to close it.
    renderShell({ light: null, contentDisabled: true }, { homePlace: null });

    expect(screen.getByTestId('masthead-set-postcode').closest('.pointer-events-none')).toBeNull();
  });

  it('puts the tick line inside the band, under the rule', () => {
    // The rail footer it replaces sat OUTSIDE the band; a reader who has just moved the origin
    // should not have to look in two places to see where they moved it to.
    renderShell({ light: LIGHT });
    const masthead = screen.getByTestId('window-first-masthead');
    const tick = screen.getByTestId('window-first-tickline');

    expect(masthead).toContainElement(tick);
    expect(screen.getByTestId('masthead-light-rule')
      .compareDocumentPosition(tick) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});

/**
 * The compact PHONE masthead (map-mobile-compact-plan.md C1, README §1–§2), and the proof that
 * every width above it is untouched.
 *
 * <p>Each phone test has a desktop twin asserting the opposite, because a branch that fired on both
 * would pass the phone half alone. The desktop arm is the pre-existing control set — cog, Sign out,
 * kicker, tick line with times — asserted again here under the SAME mocked hook.
 */
describe('WindowFirstShell — the compact phone masthead', () => {
  const LIGHT = {
    label: 'Home · NE66 1NG',
    shortLabel: 'NE66 1NG',
    civilDawn: '05:32',
    sunrise: '06:04',
    sunset: '19:58',
    civilDusk: '20:31',
    stops: [{ key: 'SUNRISE', position: 25.28 }, { key: 'SUNSET', position: 74.86 }],
  };
  const MAP = <p data-testid="map-pane">map pane</p>;
  const LAKES = { name: 'The Lake District', baseName: 'Keswick' };
  const DOT = <span data-testid="health-dot">dot</span>;
  const LINE = <button type="button" data-testid="health-status-line">UP</button>;
  const openMenu = () => fireEvent.click(screen.getByTestId('masthead-menu-button'));
  const mapTab = () => fireEvent.click(screen.getByRole('tab', { name: 'Map' }));

  describe('on the phone', () => {
    beforeEach(() => { mockIsMobile = true; });

    it('draws the compact lockup: no kicker, wordmark kept as the page heading', () => {
      renderShell({ light: LIGHT });
      expect(screen.getByTestId('brand-lockup')).toHaveAttribute('data-variant', 'compact');
      expect(screen.queryByText('Field guide to light')).toBeNull();
      expect(screen.getByRole('heading', { name: /PhotoCast/ })).toBeInTheDocument();
    });

    it('renders no cog and no Sign out button at rest — they live in the ⋯ menu', () => {
      renderShell({ light: LIGHT });
      expect(screen.queryByTestId('window-first-settings')).toBeNull();
      expect(screen.queryByTestId('window-first-signout')).toBeNull();
      expect(screen.getByTestId('masthead-menu-button')).toBeInTheDocument();
    });

    it('draws the golden times as ↑/↓ with the event words for assistive tech', () => {
      renderShell({ light: LIGHT });
      const times = screen.getByTestId('masthead-phone-times');
      expect(screen.getByTestId('masthead-phone-sunrise')).toHaveTextContent('↑06:04 sunrise');
      expect(screen.getByTestId('masthead-phone-sunset')).toHaveTextContent('↓19:58 sunset');
      expect(times).toHaveTextContent('Home · NE66 1NG');
      // The blue hours stay off the phone, and the tick line draws no times of its own.
      expect(times).not.toHaveTextContent('05:32');
      expect(screen.queryByTestId('masthead-light-times')).toBeNull();
    });

    it('draws no times while the light is unanswered or no home is saved', () => {
      renderShell({ light: undefined });
      expect(screen.queryByTestId('masthead-phone-times')).toBeNull();
    });

    it('R7 — always the HOME light: a small ⌂ prefixes the times while away, none at home', () => {
      const { unmount } = renderShell({ light: LIGHT });
      expect(screen.getByTestId('masthead-phone-times').querySelector('.wf-mast-times-home')).toBeNull();
      unmount();
      renderShell({ light: LIGHT }, { origin: LAKES });
      const home = screen.getByTestId('masthead-phone-times').querySelector('.wf-mast-times-home');
      expect(home).toHaveTextContent('⌂');
      expect(home).toHaveAttribute('aria-hidden', 'true');
      // …and the label is still there as words.
      expect(screen.getByTestId('masthead-phone-times')).toHaveTextContent('Home · NE66 1NG');
    });

    it('draws the thinner rule', () => {
      renderShell({ light: LIGHT });
      expect(screen.getByTestId('masthead-light-rule')).toHaveClass('h-0.5');
      expect(screen.getByTestId('masthead-light-rule')).not.toHaveClass('h-1');
    });

    it('draws the admin dot beside ⋯ and NO pill; draws neither for a reader handed none', () => {
      const { unmount } = renderShell({ healthPill: PILL, healthDot: DOT });
      const masthead = screen.getByTestId('window-first-masthead');
      expect(masthead).toContainElement(screen.getByTestId('health-dot'));
      expect(screen.queryByTestId('health-indicator')).toBeNull();
      unmount();
      renderShell();
      expect(screen.queryByTestId('health-dot')).toBeNull();
    });

    it('places the dot ahead of the ⋯ button and outside a DOWN backend\'s greying', () => {
      renderShell({ healthDot: DOT, contentDisabled: true });
      const dot = screen.getByTestId('health-dot');
      expect(dot.compareDocumentPosition(screen.getByTestId('masthead-menu-button'))
        & Node.DOCUMENT_POSITION_FOLLOWING).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
      expect(dot.closest('.pointer-events-none')).toBeNull();
    });

    it('shows the status line in the menu for an admin and not for anyone else', () => {
      const { unmount } = renderShell({ healthStatusLine: LINE });
      openMenu();
      expect(screen.getByTestId('health-status-line')).toBeInTheDocument();
      unmount();
      renderShell();
      openMenu();
      expect(screen.queryByTestId('health-status-line')).toBeNull();
    });

    it('the menu\'s Settings row runs the cog\'s handler', () => {
      const { onOpenSettings } = renderShell();
      openMenu();
      fireEvent.click(screen.getByTestId('window-first-settings'));
      expect(onOpenSettings).toHaveBeenCalledTimes(1);
      expect(screen.queryByTestId('masthead-menu')).toBeNull();
    });

    it('the menu\'s Sign out row signs out', () => {
      const { onSignOut } = renderShell();
      openMenu();
      fireEvent.click(screen.getByTestId('window-first-signout'));
      expect(onSignOut).toHaveBeenCalledTimes(1);
    });

    it('Change home postcode runs the nudge\'s handler, naming ⋯ as the focus fallback', () => {
      const onSetPostcode = vi.fn();
      renderShell({ onSetPostcode });
      openMenu();
      fireEvent.click(screen.getByTestId('masthead-menu-postcode'));

      expect(onSetPostcode).toHaveBeenCalledTimes(1);
      const resolve = onSetPostcode.mock.calls[0][0];
      expect(resolve()).toBe(screen.getByTestId('masthead-menu-button'));
    });

    it('Change home postcode falls back to plain Settings when no handler was given', () => {
      const { onOpenSettings } = renderShell();
      openMenu();
      fireEvent.click(screen.getByTestId('masthead-menu-postcode'));
      expect(onOpenSettings).toHaveBeenCalledTimes(1);
    });

    it('⚠️ does not reopen the menu after a rotation to tablet and back', () => {
      const { rerender, ...rest } = renderShell();
      openMenu();
      expect(screen.getByTestId('masthead-menu')).toBeInTheDocument();

      mockIsMobile = false;
      rerender(<WindowFirstShell {...rest} />);
      expect(screen.queryByTestId('masthead-menu-button')).toBeNull();
      mockIsMobile = true;
      rerender(<WindowFirstShell {...rest} />);

      expect(screen.getByTestId('masthead-menu-button')).toHaveAttribute('aria-expanded', 'false');
      expect(screen.queryByTestId('masthead-menu')).toBeNull();
    });

    it('closes the menu when a tab is chosen (selectTab takes it down)', () => {
      renderShell({}, { });
      openMenu();
      expect(screen.getByTestId('masthead-menu')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('tab', { name: 'Coming up' }));
      expect(screen.queryByTestId('masthead-menu')).toBeNull();
    });

    it('R2 — hides the origin row at Home on the Map tab, and still draws it on the other tabs', () => {
      renderShell({ light: LIGHT, mapPane: MAP }, { });
      // Plan tab (the default): the row is search's only way in, so it stays — without the times.
      expect(screen.getByTestId('window-first-tickline')).toBeInTheDocument();
      expect(screen.getByTestId('window-first-origin-chip')).toBeInTheDocument();
      expect(screen.queryByTestId('masthead-light-times')).toBeNull();
      mapTab();
      expect(screen.queryByTestId('window-first-tickline')).toBeNull();
    });

    it('draws the compact row on the Map tab when away, with ⌂ first', () => {
      renderShell({ light: LIGHT, mapPane: MAP }, { origin: LAKES });
      mapTab();
      expect(screen.getByTestId('window-first-tickline')).toHaveAttribute('data-compact', 'true');
      expect(screen.getByTestId('window-first-origin-statement')).toHaveTextContent('The Lake District');
      expect(screen.getByTestId('window-first-origin-home')).toBeInTheDocument();
    });

    it('draws the red nudge row on the Map tab with no postcode', () => {
      renderShell({ light: null, mapPane: MAP }, { homePlace: null });
      mapTab();
      expect(screen.getByTestId('masthead-set-postcode').closest('.wf-tick-group'))
        .toHaveAttribute('data-unset', 'true');
    });

    it('⚠️ ⌂ on the phone Map (Away to Home) leaves focus on ⋯, not <body>', () => {
      // The route through the real shell: `setOrigin` is the briefing context's, so this stands in
      // for it by letting the mocked context answer a null origin after the press.
      const setOrigin = vi.fn();
      const { rerender, ...rest } = renderShell(
        { light: LIGHT, mapPane: MAP }, { origin: LAKES, setOrigin },
      );
      mapTab();
      const home = screen.getByTestId('window-first-origin-home');
      home.focus();
      fireEvent.click(home);
      expect(setOrigin).toHaveBeenCalledWith(null);
      vi.spyOn(briefingContext, 'useWindowFirstBriefing')
        .mockReturnValue({ ...ctx(), origin: null, setOrigin });
      rerender(<WindowFirstShell {...rest} />);

      expect(screen.queryByTestId('window-first-tickline')).toBeNull();
      expect(document.activeElement).toBe(screen.getByTestId('masthead-menu-button'));
    });
  });

  describe('off the phone (desktop and tablet provably unchanged)', () => {
    it('keeps the masthead lockup with its kicker, the cog, Sign out and the tick line with times', () => {
      renderShell({ light: LIGHT, healthPill: PILL, healthDot: DOT, healthStatusLine: LINE });
      expect(screen.getByTestId('brand-lockup')).toHaveAttribute('data-variant', 'masthead');
      expect(screen.getByText('Field guide to light')).toBeInTheDocument();
      expect(screen.getByTestId('window-first-settings')).toBeInTheDocument();
      expect(screen.getByTestId('window-first-signout')).toBeInTheDocument();
      expect(screen.getByTestId('masthead-light-times')).toHaveTextContent('06:04');
      expect(screen.getByTestId('health-indicator')).toBeInTheDocument();
    });

    it('renders none of the phone chrome', () => {
      renderShell({ light: LIGHT, healthDot: DOT, healthStatusLine: LINE });
      expect(screen.queryByTestId('masthead-menu-button')).toBeNull();
      expect(screen.queryByTestId('masthead-phone-times')).toBeNull();
      expect(screen.queryByTestId('window-first-mast-row')).toBeNull();
      expect(screen.queryByTestId('health-dot')).toBeNull();
      expect(screen.queryByTestId('health-status-line')).toBeNull();
    });

    it('keeps the 4px rule and its sm: offsets', () => {
      renderShell({ light: LIGHT });
      const rule = screen.getByTestId('masthead-light-rule');
      expect(rule).toHaveClass('h-1');
      expect(rule).toHaveClass('sm:mt-[14px]');
    });

    it('keeps the tick line at Home on the Map tab, as the statement, with no compact hook', () => {
      renderShell({ light: LIGHT, mapPane: MAP });
      mapTab();
      expect(screen.getByTestId('window-first-origin-statement')).toHaveTextContent('Home · Newcastle');
      expect(screen.getByTestId('window-first-tickline')).not.toHaveAttribute('data-compact');
    });

    it('is red when unset here too', () => {
      renderShell({ light: null }, { homePlace: null });
      expect(screen.getByTestId('masthead-set-postcode').closest('.wf-tick-group'))
        .toHaveAttribute('data-unset', 'true');
    });
  });
});
