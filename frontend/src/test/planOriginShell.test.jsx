import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import React from 'react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

/**
 * The origin's wiring through the shell (plan §4.8, P7) — the design's headline state.
 *
 * <p><b>What breaks if these fail.</b> Moving the origin is one gesture with six consequences (the
 * pool, the frame, the drive figures, the reach tier, the lens label and the region rail), and the
 * whole claim of the feature is that they move <em>together</em>. A half-applied origin is worse
 * than none: a page framed on the Lakes with drive times from Durham is a plan nobody can act on.
 *
 * <p>The context is stubbed rather than driven, exactly as {@code WindowFirstShell.test.jsx} does it:
 * these are tests about the shell's wiring, and the provider's own derivations have their own
 * files ({@code planOrigin.test.js}, {@code windowFirstCards.test.js}).
 */
describe('WindowFirstShell — the origin', () => {
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
    floor: { id: 'any', min: null, label: 'Any rating' },
    floorId: 'any',
    minRating: null,
    selectFloor: vi.fn(),
  };

  const LAKES = { id: 7, name: 'Lake District', baseName: 'Keswick', baseLat: 54.6, baseLon: -3.1 };
  const NORTHUMBERLAND = {
    id: 8, name: 'Northumberland', baseName: null, baseLat: null, baseLon: null,
  };
  const ORIGIN = { id: 7, name: 'Lake District', baseName: 'Keswick' };

  const CARD = {
    key: '2026-08-04:SUNSET',
    date: '2026-08-04',
    targetType: 'SUNSET',
    lead: true,
    kicker: 'Tonight',
    when: 'Sunset',
    time: '21:11',
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
      rating: 4,
      driveMinutes: 12,
    }],
    allSpots: [],
    // ⚠️ TRUE even though `allSpots` is empty here: this fixture models the reader's account, not
    // the scope, and these tests are about the origin rather than about the no-postcode wording.
    // `buildWindowCards` derives it from a populated `allSpots`; the empty array is this file's own
    // shortcut and would otherwise flip every "within reach" clause the origin tests read past.
    reachMeasured: true,
    reachTotal: 1,
    reachedTotal: 1,
  };

  const STRIP_CARD = {
    key: '2026-08-04:SUNSET',
    date: '2026-08-04',
    targetType: 'SUNSET',
    dow: 'Tue',
    sunrise: false,
    label: 'Tonight Sunset',
    time: '21:11',
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

  /**
   * The served region records the open row's rail renders, on the day the one card names.
   *
   * <p>The shell looks these up by window key from {@code briefing.days} — {@code buildWindowCards}
   * deliberately does not copy them onto its descriptor — so a fixture without them renders a card
   * with no region layer at all, and every assertion about the rail passes vacuously.
   */
  const EVENT_SUMMARY = {
    targetType: 'SUNSET',
    regions: [
      {
        regionName: 'Lake District',
        displayVerdict: 'WORTH_IT',
        meanRating: 4.2,
        bestRating: 4,
        slots: [{ canopy: false }],
      },
      {
        regionName: 'Northumberland',
        displayVerdict: 'MAYBE',
        meanRating: 3.0,
        bestRating: 3,
        slots: [{ canopy: false }],
      },
    ],
    unregioned: [],
  };

  const ctx = (extra = {}) => ({
    briefing: {
      generatedAt: '2026-08-04T12:00:00',
      days: [{ date: '2026-08-04', eventSummaries: [EVENT_SUMMARY] }],
    },
    loading: false,
    windowCards: [CARD],
    paneItems: [{ kind: 'card', key: CARD.key, card: CARD }],
    upcomingEvents: [],
    travelDayDates: new Set(),
    reachById: new Map([[1, { driveMinutes: 220 }], [2, { driveMinutes: 40 }]]),
    isPro: true,
    isLiteUser: false,
    evaluationScores: new Map(),
    scoreIndex: new Map(),
    heatStripCards: [STRIP_CARD],
    heatSpots: SPOTS,
    heatPointSets: new Map([[CARD.key, [{ id: 1, lat: 54.58, lng: -3.14, r: [4] }]]]),
    regionSeries: new Map(),
    todayStr: '2026-08-04',
    tomorrowStr: '2026-08-05',
    reachLens: LENS,
    ratingLens: RATING_LENS,
    homePlace: 'Durham',
    origin: null,
    setOrigin: vi.fn(),
    regions: [LAKES, NORTHUMBERLAND],
    effectiveReachById: new Map(),
    ...extra,
  });

  const shellProps = () => ({
    onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(),
  });

  const renderShell = (extra = {}) => {
    const value = ctx(extra);
    const spy = vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(value);
    const view = render(<WindowFirstShell {...shellProps()} />);
    // `moveOrigin` re-renders the same tree with a different context value, which is the only way
    // to exercise a TRANSITION here — the context is stubbed, so a second `renderShell` would be a
    // fresh mount and would lose exactly the state a transition has to survive.
    value.moveOrigin = (next) => {
      spy.mockReturnValue(ctx({ ...extra, ...next }));
      view.rerender(<WindowFirstShell {...shellProps()} />);
    };
    return value;
  };

  afterEach(() => vi.restoreAllMocks());

  describe('the origin control', () => {
    it('sits in the masthead tick line, which M3 made its only home', () => {
      // It was in the rail footer until M3 deleted that row. The design's own words for why it
      // moved: the tick line is "the ONLY statement of where the plan is computed from; there is no
      // separate origin chip or breadcrumb anywhere in the tab".
      renderShell();
      const tick = screen.getByTestId('window-first-tickline');
      expect(within(tick).getByTestId('window-first-origin-chip')).toHaveTextContent('Home · Durham');
    });

    it('names the base town once the origin has moved', () => {
      renderShell({ origin: ORIGIN });
      expect(screen.getByTestId('window-first-origin-chip')).toHaveTextContent('Keswick');
    });

    it('opens search', async () => {
      renderShell();
      fireEvent.click(screen.getByTestId('window-first-origin-chip'));
      expect(await screen.findByTestId('plan-search')).toBeInTheDocument();
    });

    it('⌂ hands the origin back to home', () => {
      const value = renderShell({ origin: ORIGIN });
      fireEvent.click(screen.getByTestId('window-first-origin-home'));
      expect(value.setOrigin).toHaveBeenCalledWith(null);
    });

    it('withholds the home prompt while away — it is about a home nobody is planning from', () => {
      // Unchanged behaviour, new carrier: the rail footer's "Home not set" line became the origin
      // button's empty state (M3.5), and it is withheld in the same state for the same reason.
      renderShell({ origin: ORIGIN, homePlace: null });
      expect(screen.queryByTestId('masthead-set-postcode')).toBeNull();
      expect(screen.getByTestId('window-first-origin-chip')).toHaveTextContent('Keswick');
    });

    it('still prompts at home, and the prompt is the origin button itself', () => {
      renderShell({ homePlace: null });
      expect(screen.getByTestId('masthead-set-postcode')).toHaveTextContent('Set a postcode');
      // A SWAP, not an addition — the band gains no control in this state.
      expect(screen.queryByTestId('window-first-origin-chip')).toBeNull();
    });
  });

  describe('the / shortcut', () => {
    it('opens search on the Plan tab', async () => {
      renderShell();
      fireEvent.keyDown(document, { key: '/' });
      expect(await screen.findByTestId('plan-search')).toBeInTheDocument();
    });

    it('⚠️ is ignored while the reader is typing in a field', () => {
      renderShell();
      const field = document.createElement('input');
      document.body.appendChild(field);
      field.focus();
      fireEvent.keyDown(field, { key: '/' });
      expect(screen.queryByTestId('plan-search')).toBeNull();
      field.remove();
    });

    it('⚠️ is ignored when a modifier is held, so browser shortcuts are untouched', () => {
      renderShell();
      fireEvent.keyDown(document, { key: '/', metaKey: true });
      expect(screen.queryByTestId('plan-search')).toBeNull();
    });

    it('⚠️ is ignored while a dialog this shell does not own is open', () => {
      // `UserSettingsModal` is a SIBLING of the shell in `App`, so the shell's own `modalOpen` flag
      // cannot see it — and `/` over it stacked a second `aria-modal` overlay, with two
      // document-level Escape handlers and two interleaved focus restores.
      renderShell();
      const foreign = document.createElement('div');
      foreign.setAttribute('role', 'dialog');
      document.body.appendChild(foreign);
      try {
        fireEvent.keyDown(document, { key: '/' });
        expect(screen.queryByTestId('plan-search')).toBeNull();
      } finally {
        foreign.remove();
      }
    });

    it('is ignored while the arm is greyed for a dead backend', () => {
      // The shell is `pointer-events: none` under `contentDisabled`; a keyboard shortcut into it
      // would be the one live control on a surface that says it is not.
      const value = ctx();
      vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(value);
      render(<WindowFirstShell
        onOpenSettings={vi.fn()}
        onSignOut={vi.fn()}
        onShowOnMap={vi.fn()}
        contentDisabled
      />);
      fireEvent.keyDown(document, { key: '/' });
      expect(screen.queryByTestId('plan-search')).toBeNull();
    });

    it('is ignored on another tab, where there is no window list to search into', () => {
      renderShell();
      fireEvent.click(screen.getByTestId('window-first-tab-coming-up'));
      fireEvent.keyDown(document, { key: '/' });
      expect(screen.queryByTestId('plan-search')).toBeNull();
    });
  });

  describe('search moves the origin', () => {
    it('hands the region RECORD to setOrigin, so a baseless one cannot become an origin', async () => {
      const value = renderShell();
      fireEvent.keyDown(document, { key: '/' });
      const input = await screen.findByTestId('plan-search-input');
      fireEvent.change(input, { target: { value: 'lake' } });
      fireEvent.click(screen.getByRole('option', { name: /Lake District/ }));
      expect(value.setOrigin).toHaveBeenCalledWith(LAKES);
    });
  });

  describe('the lens bar relabels', () => {
    /**
     * The caption is the element {@code aria-labelledby} points at, so asserting the GROUP's
     * accessible name pins the visible words and WCAG 2.5.3 in one expectation: they can only
     * differ if someone adds an {@code aria-label}, which is exactly the regression this guards.
     */
    it('names the base every figure it gates is measured from', () => {
      renderShell({ origin: ORIGIN });
      expect(screen.getByRole('group', { name: 'Drive from Keswick' })).toBeInTheDocument();
      expect(screen.queryByRole('group', { name: 'Drive from home' })).toBeNull();
    });

    it('keeps its home caption at home', () => {
      renderShell();
      expect(screen.getByRole('group', { name: 'Drive from home' })).toBeInTheDocument();
    });
  });

  describe('the strip', () => {
    it('withholds the beyond line when away — it is a statement about the home area', async () => {
      renderShell({ origin: ORIGIN });
      // The strip is lazy; wait for it before asserting an absence, or the absence is only the
      // Suspense fallback.
      await screen.findByTestId('wf-heat-strip');
      expect(screen.queryByTestId('wf-heat-beyond')).toBeNull();
    });

    it('offers a search link on the beyond line at home, pre-filled with the nearest one', async () => {
      renderShell({
        reachById: new Map([[1, { driveMinutes: 400 }], [2, { driveMinutes: 40 }]]),
      });
      const link = await screen.findByTestId('wf-heat-beyond-search');
      expect(link).toHaveTextContent('Plan from Lake District');
      fireEvent.click(link);
      expect(await screen.findByTestId('plan-search-input')).toHaveValue('Lake District');
    });
  });

  describe('the open POPUP under an away origin', () => {
    it('⚠️ ignores a region focus made at home, which nothing on screen could then clear', async () => {
      // The focus is NOT cleared on an origin move — deliberately, since a reader who goes home
      // finds the page as they left it — and away the rail that would clear it is withheld. Left
      // live it filtered an already-scoped strip to a region the reader had scoped out and printed
      // "Nothing in Northumberland for this window" under a chip naming Keswick.
      //
      // The surface moved at M2 (an accordion row became a dialog) and the rule did not.
      const value = renderShell();
      fireEvent.click(await screen.findByTestId('wf-heat-card'));

      const rail = await screen.findByTestId('wf-region-rail');
      fireEvent.click(within(rail).getByRole('button', { name: /Northumberland/ }));
      // The card's spot is in the Lake District, so focusing Northumberland empties the strip and
      // the popup's quiet sentence appears — the state that must NOT survive the move.
      // Every filter in force is named, region clause included — the sentence is true of all of
      // them ("nothing within 45 min in Northumberland"), and naming only the region would credit
      // one control with three controls' work, which is the rule the strip footer already follows.
      expect(screen.getByTestId('window-sheet-empty'))
        .toHaveTextContent('Nothing within 45 min in Northumberland for this sunset.');

      value.moveOrigin({ origin: ORIGIN });

      // The rail is gone, and so is every trace of the focus it set.
      expect(screen.queryByTestId('wf-region-rail')).toBeNull();
      expect(screen.queryByTestId('window-sheet-empty')).toBeNull();
      expect(screen.getByTestId('window-spot-strip')).toBeInTheDocument();
    });

    /**
     * ⚠️ CLOSE-WITH-MOVE, and it is the P8 invariant rather than tidiness.
     *
     * <p>M3 lets search sit OVER an open popup, so without the close a reader could move the origin
     * while the popup watched: the reach default drops to 90, `effectiveReachById` swaps, and the
     * popup's spot strip, best-in-reach figure, spread histogram, region rail and every leave-by
     * re-derive underneath them. P8 refused to build exactly that ("moving the origin from inside
     * an open sheet would swap the drive, the base named beside it, the outside badge and every
     * departure on every row while the reader watches"), and M4.3's `Plan from <region>` footer is
     * specified with the same semantics.
     *
     * <p>⚠️ The guarantee is <b>one commit</b>, not a sequence — and the first cut of this test
     * asserted the wrong thing. Both setters are called from one handler, so React batches them: at
     * the moment `setOrigin` runs, the DOM has not been touched and the popup is still there. That
     * is not a defect, it is a stronger property than ordering — there is no frame in which the
     * popup is rendered against the new origin, because the unmount and the origin change land in
     * the same commit. So what is asserted is the committed state, which is the thing a reader can
     * actually see.
     */
    it('⚠️ never leaves the popup standing when a search result moves the origin', async () => {
      const value = renderShell();
      fireEvent.click(await screen.findByTestId('wf-heat-card'));
      expect(screen.getByTestId('window-sheet')).toBeInTheDocument();

      fireEvent.keyDown(document, { key: '/' });
      const input = await screen.findByTestId('plan-search-input');
      fireEvent.change(input, { target: { value: 'lake' } });
      fireEvent.click(screen.getByRole('option', { name: /Lake District/ }));

      expect(value.setOrigin).toHaveBeenCalledWith(LAKES);
      expect(screen.queryByTestId('window-sheet'),
        'the popup must not survive the move that re-derives everything inside it').toBeNull();
      expect(screen.queryByTestId('plan-search')).toBeNull();
    });

    it('closes it for a LOCATION result too, which M4 will stack differently from its own chips', async () => {
      // M4 does open this sheet OVER the popup — but from the popup's own field chips, where the
      // reader is already looking at that window. Arriving from search is a different gesture, and
      // it is the one the shell's own "closes FIRST" rule already governs everywhere else.
      renderShell();
      fireEvent.click(await screen.findByTestId('wf-heat-card'));
      fireEvent.keyDown(document, { key: '/' });
      fireEvent.change(await screen.findByTestId('plan-search-input'), { target: { value: 'derwent' } });
      const row = screen.queryAllByRole('option').find((o) => o.dataset.kind === 'location');
      expect(row, 'the fixture must offer a location row, or this test proves nothing').toBeTruthy();
      fireEvent.click(row);

      // The sheet is lazy, so it arrives a tick later — what matters is that the popup has already
      // gone by then rather than being left underneath it.
      expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
      expect(screen.queryByTestId('window-sheet')).toBeNull();
    });
  });

  describe('an away plan the lens has shut offers the way home', () => {
    it('sends the origin action to setOrigin(null), never to a lens control', () => {
      // The page-level conflict, which is where the per-card ladder's plan-wide job went at M2.
      const emptied = {
        ...CARD,
        spots: [],
        pool: [],
        allSpots: [{
          key: '1', locationId: 1, locationName: 'Buttermere', rating: 2, driveMinutes: 120, regionName: 'Lake District',
        }],
        reachTotal: 1,
        reachedTotal: 0,
      };
      const value = renderShell({
        origin: ORIGIN,
        windowCards: [emptied],
        paneItems: [{ kind: 'card', key: emptied.key, card: emptied }],
        reachLens: { ...LENS, tier: { id: '45', limitMinutes: 45, label: '45 min' }, tierId: '45' },
      });
      const raf = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => { cb(); return 0; });
      try {
        const actions = screen.getAllByTestId('window-first-conflict-act');
        const home = actions.find((b) => b.dataset.loosen === 'origin');
        fireEvent.click(home);
        expect(value.setOrigin).toHaveBeenCalledWith(null);
        expect(LENS.selectTier).not.toHaveBeenCalled();
        expect(RATING_LENS.selectFloor).not.toHaveBeenCalled();
      } finally {
        raf.mockRestore();
      }
    });
  });
});
