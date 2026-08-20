import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, within, act } from '@testing-library/react';
import React from 'react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

/**
 * The four-day location sheet's wiring through the shell (plan D10, P8).
 *
 * <p><b>What breaks if these fail.</b> §9.9 was resolved (owner, 2026-08-20) in favour of the
 * sheet hanging off the <em>search only</em>: a spot card's click and the peek's keep today's
 * map-open behaviour byte-for-byte. Two halves, and each one is a real regression if it slips —
 * a card that stops opening the map is a control that changed under a reader who never asked, and
 * a search result that jumps to the map is P8 not shipped.
 *
 * <p>The other half of this file is the <b>drive basis</b>. The sheet is the first surface able to
 * render a base-measured drive to a place the origin's scope does not contain, so which map it
 * reads is not a detail: reading the home map under an away origin would put two journeys on one
 * screen, with the lens bar above them naming a third.
 *
 * <p>The context is stubbed rather than driven, as `planOriginShell.test.jsx` does it — this is a
 * test about wiring, and the derivations have their own files.
 */
describe('WindowFirstShell — the four-day location sheet', () => {
  const LENS = {
    tier: { id: '45', limitMinutes: 45, label: '45 min' },
    tierId: '45',
    defaultTier: { id: '45', limitMinutes: 45, label: '45 min' },
    defaultTierId: '45',
    weekend: false,
    overridden: false,
    locked: false,
    selectTier: vi.fn(),
    resetToDefault: vi.fn(),
  };
  const RATING_LENS = {
    floor: { id: 'any', min: null, label: 'Any rating' }, floorId: 'any', minRating: null, selectFloor: vi.fn(),
  };
  const ORDER_LENS = { order: { id: 'when', label: 'When' }, orderId: 'when', selectOrder: vi.fn() };

  const LAKES = { id: 7, name: 'Lake District', baseName: 'Keswick', baseLat: 54.6, baseLon: -3.1 };
  const NORTHUMBERLAND = { id: 8, name: 'Northumberland', baseName: 'Alnwick', baseLat: 55.4, baseLon: -1.7 };
  const ORIGIN = { id: 7, name: 'Lake District', baseName: 'Keswick' };

  const CARD = {
    key: '2026-08-14:SUNSET',
    date: '2026-08-14',
    targetType: 'SUNSET',
    lead: true,
    kicker: 'Tonight',
    when: 'Sunset',
    time: '20:37',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    bestRating: 4,
    confidence: 'high',
    badges: [],
    rows: [],
    spots: [{
      key: '1',
      locationId: 1,
      locationName: 'Derwentwater',
      regionName: 'Lake District',
      solarEventTime: '2026-08-14T19:41:00',
      rating: 4,
      driveMinutes: 12,
    }],
    allSpots: [],
    reachTotal: 1,
    reachedTotal: 1,
  };

  const STRIP_CARD = {
    key: '2026-08-14:SUNSET',
    date: '2026-08-14',
    targetType: 'SUNSET',
    dow: 'Fri',
    sunrise: false,
    label: 'Tonight Sunset',
    time: '20:37',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    pickKind: null,
    away: false,
    confidence: 'high',
  };

  const SPOTS = [
    {
      id: 1, name: 'Derwentwater', lat: 54.58, lng: -3.14, regionName: 'Lake District',
      rid: 'Lake District', skySubject: true, bortleClass: 3, scores: [4],
    },
    {
      id: 2, name: 'Bamburgh Beach', lat: 55.61, lng: -1.71, regionName: 'Northumberland',
      rid: 'Northumberland', skySubject: true, bortleClass: 3, scores: [3],
    },
  ];

  const EVENT_SUMMARY = {
    targetType: 'SUNSET',
    regions: [
      {
        regionName: 'Lake District',
        displayVerdict: 'WORTH_IT',
        meanRating: 4.2,
        bestRating: 4,
        confidence: 'high',
        slots: [{ canopy: false, locationId: 1, locationName: 'Derwentwater', solarEventTime: '2026-08-14T19:41:00' }],
      },
      {
        regionName: 'Northumberland',
        displayVerdict: 'MAYBE',
        meanRating: 3.0,
        bestRating: 3,
        // Deliberately not the strip card's `high`: the sheet must read the LOCATION'S region.
        confidence: 'low',
        slots: [{ canopy: false, locationId: 2, locationName: 'Bamburgh Beach', solarEventTime: '2026-08-14T19:38:00' }],
      },
    ],
    unregioned: [],
  };

  const ctx = (extra = {}) => ({
    briefing: {
      generatedAt: '2026-08-14T12:00:00',
      days: [{ date: '2026-08-14', eventSummaries: [EVENT_SUMMARY] }],
    },
    loading: false,
    windowCards: [CARD],
    paneItems: [{ kind: 'card', key: CARD.key, card: CARD }],
    promotedStrip: null,
    upcomingEvents: [],
    travelDayDates: new Set(),
    // Home is two hours from the Lakes and forty minutes from the coast — the ordinary shape of a
    // per-user map, and the comparand for the away one below.
    reachById: new Map([[1, { driveMinutes: 120 }], [2, { driveMinutes: 40 }]]),
    isPro: true,
    isLiteUser: false,
    evaluationScores: new Map(),
    scoresLoaded: true,
    // ⚠️ The RAW rows, which is what the sheet's ratings come from — id-bearing, where the
    // provider's `scoreIndex` beside it is name-keyed. The two carry deliberately DIFFERENT ratings
    // here, so a regression that read the name-keyed index would be visible rather than silent.
    scoreRows: [{
      locationId: 2, locationName: 'Bamburgh Beach', date: '2026-08-14', targetType: 'SUNSET',
      rating: 3, summary: 'A thin band of high cloud.',
    }],
    scoreIndex: new Map([
      ['2026-08-14|SUNSET|Bamburgh Beach', { rating: 5, summary: 'The name-keyed index, which must not be read.' }],
    ]),
    heatStripCards: [STRIP_CARD],
    heatSpots: SPOTS,
    heatPointSets: new Map([[CARD.key, [{ id: 1, lat: 54.58, lng: -3.14, r: [4] }]]]),
    regionSeries: new Map(),
    todayStr: '2026-08-14',
    tomorrowStr: '2026-08-15',
    reachLens: LENS,
    ratingLens: RATING_LENS,
    orderLens: ORDER_LENS,
    homePlace: 'Durham',
    origin: null,
    setOrigin: vi.fn(),
    regions: [LAKES, NORTHUMBERLAND],
    // At home the provider publishes the per-user map here unchanged; the away fixtures below
    // replace it, which is exactly what `originReachMap` does.
    effectiveReachById: new Map([[1, { driveMinutes: 120 }], [2, { driveMinutes: 40 }]]),
    ...extra,
  });

  const shellProps = (over = {}) => ({
    onExit: vi.fn(), onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(), ...over,
  });

  const renderShell = (extra = {}, props = {}) => {
    const value = ctx(extra);
    const merged = shellProps(props);
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(value);
    render(<WindowFirstShell {...merged} />);
    return { ...value, props: merged };
  };

  /** Opens search and picks the named location — the sheet's one and only entry point. */
  const openSheetFor = async (name) => {
    fireEvent.keyDown(document, { key: '/' });
    const input = await screen.findByTestId('plan-search-input');
    fireEvent.change(input, { target: { value: name } });
    fireEvent.click(screen.getByRole('option', { name: new RegExp(name) }));
    return screen.findByTestId('location-sheet');
  };

  afterEach(() => vi.restoreAllMocks());

  it('opens the sheet from a search result rather than jumping to the map', async () => {
    const view = renderShell();
    await openSheetFor('Bamburgh');
    expect(screen.getByTestId('location-sheet-title')).toHaveTextContent('Bamburgh Beach');
    // ⚠️ P7 sent this straight to `onShowOnMap`. That handoff has MOVED into the sheet's footer,
    // which is what "the map is one tap further, never lost" means — it must not also fire here,
    // or the map would open behind the dialog.
    expect(view.props.onShowOnMap).not.toHaveBeenCalled();
    // Search closes behind it: two `aria-modal` dialogs on one page is two Escape listeners and
    // two focus restores.
    expect(screen.queryByTestId('plan-search')).toBeNull();
  });

  it('⚠️ leaves a spot card\'s click on the map, byte-for-byte', () => {
    // §9.9's other half. The card is the surface a reader already knows; P8 changed the search and
    // nothing else. `WindowSpotStrip.test.jsx` passing unedited is the wider proof — this is the
    // assertion at the seam where the change was actually made.
    const view = renderShell();
    fireEvent.click(screen.getByTestId('window-spot'));
    expect(view.props.onShowOnMap).toHaveBeenCalledWith('2026-08-14', 'SUNSET', 'Derwentwater');
    expect(screen.queryByTestId('location-sheet')).toBeNull();
  });

  it('measures the drive from HOME while the origin is home', async () => {
    renderShell();
    await openSheetFor('Bamburgh');
    expect(screen.getByTestId('location-sheet-meta'))
      .toHaveTextContent('Northumberland · 40 min from Durham');
  });

  it('⚠️ measures the drive from the BASE once the origin has moved', async () => {
    // The single most damaging thing this surface could do is print a home drive under a lens bar
    // reading "Drive from Keswick". `effectiveReachById` is the provider's one switched map, and
    // the sheet must read it rather than the per-user one beside it.
    renderShell({
      origin: ORIGIN,
      effectiveReachById: new Map([[1, { driveMinutes: 15 }], [2, { driveMinutes: 195 }]]),
    });
    await openSheetFor('Bamburgh');
    expect(screen.getByTestId('location-sheet-meta'))
      .toHaveTextContent('Northumberland · 3h 15min from Keswick');
    // The home figure for the same place, which must NOT be what the sheet printed.
    expect(screen.getByTestId('location-sheet-meta')).not.toHaveTextContent('40 min');
  });

  it('⚠️ marks a place outside the origin\'s own region, and NAMES that region', async () => {
    // Away scope is the origin's region alone (`scopeRegions`), so a coastal spot searched from a
    // Lakes base is genuinely outside the plan. It names which plan: a bare "outside your plan"
    // means the home planning area at home and a region membership away, and only one of the two is
    // about distance — a near spot in a neighbouring region wore the badge over its own short drive
    // and read as a broken filter.
    renderShell({
      origin: ORIGIN,
      effectiveReachById: new Map([[2, { driveMinutes: 195 }]]),
    });
    await openSheetFor('Bamburgh');
    expect(screen.getByTestId('location-sheet-outside'))
      .toHaveTextContent('outside Lake District');
  });

  it('⚠️ rates from the RAW rows, never the name-keyed score index', async () => {
    // The provider's `scoreIndex` is keyed on the location NAME alone; the sheet joins id-first,
    // like every other join in the arm. The two fixtures carry different ratings for the same slot,
    // so reading the wrong one is visible. A name is a display string a user can edit.
    renderShell();
    const sheet = await openSheetFor('Bamburgh');
    expect(within(sheet).getByTestId('location-sheet-rating')).toHaveTextContent('3★');
    expect(within(sheet).queryByText(/must not be read/)).toBeNull();
  });

  it('⚠️ takes the confidence from the location\'s own region', async () => {
    // The strip card says `high` (the window's top region); Northumberland's own is `low`. A sheet
    // reading the card's would leave a low-confidence forecast unmarked.
    expect(STRIP_CARD.confidence).toBe('high');
    renderShell();
    const sheet = await openSheetFor('Bamburgh');
    expect(within(sheet).getByTestId('provisional-mark'))
      .toHaveAttribute('aria-label', 'Low confidence · provisional');
  });

  it('⚠️ makes no claim about the pipeline while the ratings are unfetched', async () => {
    // `scoresLoaded` false is an in-flight or failed request, which is not evidence that nothing
    // was rated. The shell must pass it through, or the sheet reports our own failure as a forecast.
    renderShell({ scoresLoaded: false, scoreRows: [] });
    const sheet = await openSheetFor('Bamburgh');
    expect(within(sheet).getAllByTestId('location-sheet-state')[0])
      .toHaveTextContent('Loading ratings…');
  });

  it('marks nothing while the planning area is unmeasured', async () => {
    // No reach entries at all — the first-run state for a reader with no home postcode. The
    // planning area is then the whole roster, so nothing is outside it. A badge here would be a
    // claim about a drive nobody has computed.
    renderShell({ reachById: new Map(), effectiveReachById: new Map() });
    await openSheetFor('Bamburgh');
    expect(screen.queryByTestId('location-sheet-outside')).toBeNull();
  });

  it('hands the map the window it names, and closes first', async () => {
    const view = renderShell();
    await openSheetFor('Bamburgh');
    fireEvent.click(screen.getByRole('button', { name: /Show on map/ }));
    expect(view.props.onShowOnMap).toHaveBeenCalledWith('2026-08-14', 'SUNSET', 'Bamburgh Beach');
    // Closed BEFORE the handoff: the map overlay is itself an `aria-modal` dialog, and leaving
    // this one mounted underneath puts two on the page at once.
    expect(screen.queryByTestId('location-sheet')).toBeNull();
  });

  it('reads the departure from the location\'s own slot time', async () => {
    // Bamburgh's own sunset is 19:38 UTC = 20:38 BST; 40 min drive + 20 min setup = 19:38 BST.
    // The window header says 20:37, which is the earliest across the region set — deriving from it
    // would answer 19:37, one minute of somebody else's sunset.
    renderShell();
    const sheet = await openSheetFor('Bamburgh');
    expect(within(sheet).getByTestId('location-sheet-leave')).toHaveTextContent('leave 19:38');
  });

  it('closes on Escape and can be reopened for a different place', async () => {
    renderShell();
    await openSheetFor('Bamburgh');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByTestId('location-sheet')).toBeNull();
    await openSheetFor('Derwentwater');
    expect(screen.getByTestId('location-sheet-title')).toHaveTextContent('Derwentwater');
  });

  describe('the sheet suppresses the spot peek', () => {
    /**
     * `.wf-peek` is portalled to the body at z-index 60 while every {@code Modal} renders inside
     * Tailwind's z-50, and {@code useDialogFocus} is explicitly not a trap — so from an open dialog
     * a keyboard user can Tab back onto a card behind the backdrop and paint a panel over it.
     * {@code WindowFirstShellSheet.test.jsx} pins the drill-down and the pick operands of
     * {@code modalOpen}; this pins the one P8 added.
     *
     * <p>⚠️ <b>The control case is not optional and was not optional here.</b> The first cut of
     * this block hovered a card with no timers at all and passed <em>with the operand removed</em>
     * — it was asserting that a peek needs its 180 ms delay, which is a fact about
     * {@code useSpotPeek}, not about suppression. A mutation sweep caught it. The same trap is
     * recorded one file over, from the focus-timing direction.
     */
    const OPEN_DELAY = 180;
    const PEEKABLE = new Map([[
      '2026-08-14|SUNSET|Derwentwater',
      { fierySkyPotential: 68, goldenHourPotential: 74, summary: 'Mid cloud should catch the last light.' },
    ]]);
    // `shouldAdvanceTime` so the lazy dialogs' dynamic imports and RTL's own awaits still resolve
    // while `advanceTimersByTime` drives the peek's delay.
    beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
    afterEach(() => vi.useRealTimers());

    const hoverCard = () => {
      fireEvent.mouseEnter(screen.getByTestId('window-spot'));
      act(() => { vi.advanceTimersByTime(OPEN_DELAY * 2); });
    };

    it('opens one with no dialog up — the control the next case rests on', () => {
      renderShell({ scoreIndex: PEEKABLE });
      hoverCard();
      expect(screen.getByTestId('wf-peek')).toBeInTheDocument();
    });

    it('opens none while the four-day sheet is up', async () => {
      renderShell({ scoreIndex: PEEKABLE });
      await openSheetFor('Derwentwater');
      // Lets the dialog's focus move land before the hover: the peek's own `focusin` listener
      // dismisses a panel whose anchor is not the focused element, so hovering too early would
      // pin the focus rule rather than the suppression.
      act(() => { vi.advanceTimersByTime(OPEN_DELAY * 2); });
      hoverCard();
      expect(screen.queryByTestId('wf-peek')).toBeNull();
    });
  });
});
