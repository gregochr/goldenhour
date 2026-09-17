import { describe, it, expect, afterEach, vi } from 'vitest';
import {
  render, screen, fireEvent, within, act, waitFor,
} from '@testing-library/react';
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

    /**
     * ⌂ goes on its own press, and the reader goes with it to the origin button (see
     * {@code MastheadTickLine}'s class comment) — asserted here through the shell's REAL
     * {@code onGoHome}, which closes the window popup in the same commit.
     *
     * <p>{@code renderShell}'s {@code setOrigin} is a bare mock, so its press changes nothing on
     * screen and ⌂ — which leaves only when the origin does — never leaves. These hold the origin in
     * state behind the stubbed context, the provider's own shape, so the removal lands in the press's
     * commit. And every one ends by forcing a frame: the shell defers focus moves of its own by one,
     * and the claim is where focus RESTS.
     */
    describe('⌂ keeps the reader\'s place as its press removes it', () => {
      const Held = React.createContext(null);
      function useHeldBriefing() { return React.useContext(Held); }
      function HeldOrigin() {
        const [origin, setHeldOrigin] = React.useState(ORIGIN);
        const value = ctx({
          origin,
          setOrigin: (region) => setHeldOrigin(
            region ? { id: region.id, name: region.name, baseName: region.baseName } : null,
          ),
        });
        return <Held.Provider value={value}><WindowFirstShell {...shellProps()} /></Held.Provider>;
      }
      const renderHeld = () => {
        vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockImplementation(useHeldBriefing);
        return render(<HeldOrigin />);
      };
      const nextFrames = () => act(async () => {
        await new Promise((resolve) => { requestAnimationFrame(() => requestAnimationFrame(resolve)); });
      });
      const HOME_NAME = 'Planning from home · Durham. Search to change it.';

      it('⚠️ puts the reader on the origin button it leaves behind, not on <body>', async () => {
        renderHeld();
        // The strip is lazy: waiting for it keeps its mount out of the commit under test.
        await screen.findByTestId('wf-heat-card');
        const home = screen.getByRole('button', { name: 'Plan from home again' });
        home.focus();

        fireEvent.click(home);

        const origin = screen.getByRole('button', { name: HOME_NAME });
        expect(document.activeElement).toBe(origin);
        await nextFrames();
        expect(document.activeElement).toBe(origin);
      });

      it('⚠️ lands there from inside an open popup too — not back on the card that opened it', async () => {
        // The one route that did not end on <body>. The popup holds no trap and leaves the masthead
        // in the Tab order, so a reader can Tab out of it onto ⌂, and `onGoHome` closes the popup in
        // that press's commit. The popup's `useDialogFocus` cleanup — a PASSIVE effect — then found
        // focus nowhere and put them back on its opener card. An owner decision (2026-09-16) makes it
        // the origin control here too: the tick line's layout effect lands first, and the restore,
        // finding focus somewhere real with no modal layer left, stands down. With `App`'s map
        // overlay or settings dialog ALSO open the restore still picks the card, because that layer
        // claims modality and the origin control reads as stranded. This test opens neither.
        renderHeld();
        const card = await screen.findByTestId('wf-heat-card');
        card.focus(); // a keyboard reader opens it from the card, which becomes its return address
        fireEvent.click(card);
        const sheet = await screen.findByTestId('window-sheet');
        // Spend the popup's mount frame first: left pending, its focus move could land anywhere in
        // the steps below and supply an answer of its own.
        await waitFor(() => expect(sheet.contains(document.activeElement)).toBe(true));
        const home = screen.getByRole('button', { name: 'Plan from home again' });
        home.focus(); // Tabbed out of the popup onto ⌂

        fireEvent.click(home);

        expect(screen.queryByTestId('window-sheet'), 'precondition: the press closed the popup').toBeNull();
        // What keeps this test guarding the ORDER: were the card gone, the popup's restore would have
        // nowhere to go, and a handoff moved to a passive effect — running after the restore — would
        // pass here too.
        expect(card.isConnected, 'precondition: the popup\'s return address is still there').toBe(true);
        const origin = screen.getByRole('button', { name: HOME_NAME });
        expect(document.activeElement).toBe(origin);
        await nextFrames();
        expect(document.activeElement).toBe(origin);
      });
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
    /**
     * Opens search with {@code /} and closes it again: the positive control every refusal below
     * runs before its own press, when what that refusal asserts is an absence.
     *
     * <p>⚠️ <b>Without it, each absence below holds with its guard deleted, when run alone.</b>
     * {@code PlanSearch} is {@code lazy()}, and the first time the shell renders it, it suspends,
     * even when its module is already loaded (measured). A press the guard failed to refuse then
     * commits only the {@code Suspense} fallback, which draws nothing, so
     * {@code queryByTestId('plan-search')} is null whether or not the press opened search. In a
     * whole-file run the refusals did fail on their mutants, but only because an earlier test had
     * already opened search.
     *
     * <p>Opening search once through the shell, and waiting for it, settles that first suspension.
     * From then on, a press that opens search renders it in that press's own commit, so an absence
     * asserted straight after the press means the press was refused. Escape closes it again, so
     * each refusal starts with no dialog open.
     */
    const openAndCloseSearch = async () => {
      fireEvent.keyDown(document, { key: '/' });
      expect(await screen.findByTestId('plan-search')).toBeInTheDocument();
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.queryByTestId('plan-search')).toBeNull();
    };

    it('opens search on the Plan tab', async () => {
      renderShell();
      fireEvent.keyDown(document, { key: '/' });
      expect(await screen.findByTestId('plan-search')).toBeInTheDocument();
    });

    /**
     * The four kinds of field the guard names, one case each, because each is its own clause:
     * while only the input was tested, deleting any of the other three failed nothing.
     *
     * <p>⚠️ jsdom has no {@code isContentEditable}, and no {@code contentEditable} either: on jsdom
     * 30.0.1, {@code 'isContentEditable' in HTMLElement.prototype} is false. An element carrying
     * only the attribute is therefore no field here, and correct code opens search over it. So the
     * host is given the {@code true} a browser computes from that attribute, and keeps the
     * attribute too, so a guard that reads either one still refuses.
     */
    const FIELDS = [
      ['an input', () => document.createElement('input')],
      ['a textarea', () => document.createElement('textarea')],
      ['a select', () => document.createElement('select')],
      ['a contenteditable element', () => {
        const host = document.createElement('div');
        host.setAttribute('contenteditable', 'true');
        Object.defineProperty(host, 'isContentEditable', { value: true });
        return host;
      }],
    ];

    it.each(FIELDS)('⚠️ is ignored while the reader is typing in %s', async (_kind, makeField) => {
      renderShell();
      await openAndCloseSearch();
      const field = makeField();
      document.body.appendChild(field);
      try {
        field.focus();
        expect(field).toHaveFocus();
        const press = new KeyboardEvent('keydown', { key: '/', bubbles: true, cancelable: true });
        fireEvent(field, press);
        expect(screen.queryByTestId('plan-search')).toBeNull();
        expect(screen.queryAllByRole('dialog')).toHaveLength(0);
        // The `/` is the reader's own character here, so opening nothing is not enough: the press
        // must still reach the field.
        expect(press.defaultPrevented).toBe(false);
      } finally {
        field.remove();
      }
    });

    it.each(['metaKey', 'ctrlKey', 'altKey'])(
      '⚠️ is ignored when %s is held, so browser shortcuts are untouched',
      async (modifier) => {
        renderShell();
        await openAndCloseSearch();
        const press = new KeyboardEvent('keydown', {
          key: '/', bubbles: true, cancelable: true, [modifier]: true,
        });
        fireEvent(document, press);
        expect(screen.queryByTestId('plan-search')).toBeNull();
        expect(screen.queryAllByRole('dialog')).toHaveLength(0);
        // Untouched means the browser still gets the press, not only that search stays shut.
        expect(press.defaultPrevented).toBe(false);
      },
    );

    it('⚠️ still opens with Shift held, because some keyboards need Shift to type it', async () => {
      // On a German layout `/` is Shift+7, so the press arrives as `key: '/'` with `shiftKey` set.
      // Shift is left out of the refusal above on purpose. The shell's arrow-key rule does refuse
      // Shift, so a modifier check shared by the two would take this shortcut away from those
      // readers, and until this test nothing failed when Shift was added.
      renderShell();
      const press = new KeyboardEvent('keydown', {
        key: '/', bubbles: true, cancelable: true, shiftKey: true,
      });
      fireEvent(document, press);
      expect(await screen.findByTestId('plan-search')).toBeInTheDocument();
    });

    it('⚠️ is ignored while search is open, so what the reader typed survives', async () => {
      // Search is keyed on its seed, so a `/` this guard let through would set the seed back to ''
      // and remount the box empty. The seed comes from the beyond line's link: a box opened with
      // `/` already has '' for a seed, and setting it again changes nothing on screen.
      // No `openAndCloseSearch` first: what is asserted is a box that is already on screen, so the
      // lazy boundary has resolved before the press.
      renderShell({
        reachById: new Map([[1, { driveMinutes: 400 }], [2, { driveMinutes: 40 }]]),
      });
      fireEvent.click(await screen.findByTestId('wf-heat-beyond-search'));
      const input = await screen.findByTestId('plan-search-input');
      expect(input).toHaveValue('Lake District');
      fireEvent.change(input, { target: { value: 'Lake' } });
      // Focus moves off the field first, because a `/` typed into the field is refused by the field
      // guard whether or not this one works. A click on the panel that misses its controls, such
      // as on a group heading, leaves focus on the dialog root (measured in Chromium, WebKit and
      // Firefox, on a static page with the same structure).
      const dialog = screen.getByRole('dialog', { name: 'Search days, regions and places' });
      dialog.focus();
      expect(dialog).toHaveFocus();

      fireEvent.keyDown(dialog, { key: '/' });

      expect(screen.getByTestId('plan-search-input')).toHaveValue('Lake');
    });

    it('⚠️ is ignored while a dialog this shell does not own is open', async () => {
      // `UserSettingsModal` is a SIBLING of the shell in `App`, so the shell's own `modalOpen` flag
      // cannot see it — and `/` over it stacked a second `aria-modal` overlay, with two
      // document-level Escape handlers and two interleaved focus restores.
      renderShell();
      // Before the foreign dialog exists, because that dialog refuses the control's own press.
      await openAndCloseSearch();
      const foreign = document.createElement('div');
      foreign.setAttribute('role', 'dialog');
      document.body.appendChild(foreign);
      try {
        fireEvent.keyDown(document, { key: '/' });
        expect(screen.queryByTestId('plan-search')).toBeNull();
        // `getByRole` throws on a second dialog, so this also says nothing else opened.
        expect(screen.getByRole('dialog')).toBe(foreign);
      } finally {
        foreign.remove();
      }
    });

    it('is ignored while the arm is greyed for a dead backend', async () => {
      // The shell is `pointer-events: none` under `contentDisabled`; a keyboard shortcut into it
      // would be the one live control on a surface that says it is not.
      const value = ctx();
      vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(value);
      const props = shellProps();
      const view = render(<WindowFirstShell {...props} />);
      // The control needs a live arm, because a greyed one refuses it too, so the arm greys after
      // it. That is also the app's order: health status starts unknown, so the shell first mounts
      // live and greys only once the status reads DOWN.
      await openAndCloseSearch();
      view.rerender(<WindowFirstShell {...props} contentDisabled />);

      fireEvent.keyDown(document, { key: '/' });
      expect(screen.queryByTestId('plan-search')).toBeNull();
      expect(screen.queryAllByRole('dialog')).toHaveLength(0);
    });

    it('is ignored on another tab, where there is no window list to search into', async () => {
      renderShell();
      // The control runs on Plan, the only tab where `/` opens search. Without it, this absence is
      // only the unresolved lazy boundary (see `openAndCloseSearch`), and the test passed alone
      // with the tab guard deleted.
      await openAndCloseSearch();
      fireEvent.click(screen.getByTestId('window-first-tab-coming-up'));
      fireEvent.keyDown(document, { key: '/' });
      expect(screen.queryByTestId('plan-search')).toBeNull();
      expect(screen.queryAllByRole('dialog')).toHaveLength(0);
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
