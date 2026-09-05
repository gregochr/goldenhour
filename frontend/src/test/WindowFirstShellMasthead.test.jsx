import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach, beforeAll } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import warmPlanChunks from './warmPlanChunks.js';

/**
 * The Plan's masthead, and specifically the status pill it inherited from the app header it
 * replaced.
 *
 * <p>Scoped its own file the way {@code WindowFirstShellTabs} and {@code WindowFirstShellSheet} are:
 * every rule here is about how the shell JOINS the pill to the masthead — that it is a slot rather
 * than a component, where it sits among the two buttons, and that a dead backend cannot take it with
 * the pane. None of those is reachable from {@code HealthIndicator}'s own test, which knows nothing
 * about a masthead.
 *
 * <p>The doors are stubbed for the reason both sibling files give: mounting them fires an astro
 * request per visible date, and this file is about neither.
 */
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));

const TODAY = '2026-08-08';

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

// Pays the shell's four `lazy()` boundaries once per FILE, in a hook with its own budget, rather
// than inside whichever test happens to run first. See `warmPlanChunks.js` for the measurements
// and for the full-suite reproduction that made this necessary.
beforeAll(warmPlanChunks);

beforeEach(() => {
  localStorage.clear();
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
      .toEqual(['window-first-settings', 'window-first-signout',
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
      .toEqual(['window-first-settings', 'window-first-signout',
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
