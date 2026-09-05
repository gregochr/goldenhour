import { describe, it, expect, afterEach, beforeEach, vi, beforeAll } from 'vitest';
import { render, screen, fireEvent, within, act } from '@testing-library/react';
import React from 'react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import warmPlanChunks from './warmPlanChunks.js';

/**
 * The heat kernel, stubbed at its module boundary — the same three exports every suite that mounts
 * a field replaces.
 *
 * <p>File-wide rather than per-block, and harmless outside `the field chip route`: jsdom measures
 * every element at zero, so no canvas in this file paints until that block installs its measurement
 * shim. Inside it the real kernel would need a full 2d context AND d3-geo's own path context — a
 * stub deep enough to run a rasteriser is a stub deep enough to be wrong about it, and what the
 * canvas paints is a browser-verification claim (§9), never a jsdom one.
 *
 * <p>`drawGeo` returns a projection, because the chip placer calls it: the returned function is how
 * a location's lat/lng becomes the pixel a chip is anchored to.
 */
vi.mock('../utils/heatField.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    load: vi.fn(() => Promise.resolve({ type: 'FeatureCollection', features: [] })),
    land: vi.fn(() => ({ type: 'FeatureCollection', features: [] })),
    drawGeo: vi.fn(() => ([lng, lat]) => [(lng + 4) * 100, (56 - lat) * 100]),
  };
});

// Pays the shell's four `lazy()` boundaries once per FILE, in a hook with its own budget, rather
// than inside whichever test happens to run first. See `warmPlanChunks.js` for the measurements
// and for the full-suite reproduction that made this necessary.
beforeAll(warmPlanChunks);

/**
 * The four-day location sheet's wiring through the shell (plan D10, P8).
 *
 * <p><b>What breaks if these fail.</b> M4 (D-3, resolved 2026-08-20) gave this sheet its other two
 * entries: the popup's ranked spot cards and its field chips, both opening it <em>over</em> the
 * popup. Search keeps its own gesture and closes the popup first. Three routes, three real
 * regressions if one slips — a card that goes back to the map loses the sheet, a chip that stays
 * inert is a name a reader can see and not reach, and a search result that closes nothing puts two
 * dialogs on the page with two Escape listeners between them.
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
    allBadges: [],
    rows: [],
    // The popup's header pick badge — a second `Modal` the reader can stack over the popup, which
    // is what the peek-suppression case below drives.
    pick: { kind: 'best', regionName: 'Lake District', locationName: 'Derwentwater' },
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
      // ⚠️ CATALOGUE ONLY — no slot, no score row, and no entry on `CARD.spots`, so it draws no
      // chip and appears in no list. It exists to move the Lake District's CENTROID off
      // Derwentwater, which is the one thing that lets that chip be placed at all: region labels
      // are seeded as occupied boxes and are never dropped, so with a single-location region the
      // label lands exactly on its own chip's anchor and the greedy pass discards the chip every
      // time. A real region holds a dozen locations spread over degrees; a two-location fixture is
      // the smallest thing that is not a special case.
      id: 3, name: 'Buttermere', lat: 54.10, lng: -3.90, regionName: 'Lake District',
      rid: 'Lake District', skySubject: true, bortleClass: 3, scores: [],
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
    }, {
      // ⚠️ TWO rows for one window, the id-bearing one second. `buildScoreIndex` keys id-first and
      // name-second with first-inserted winning in each, so the name key resolves to the DECOY and
      // the id key to this — which is what makes a lost id visible as a wrong star rather than as
      // nothing at all. It is the shape of the production defect: two roster entries sharing a
      // display name, or a location renamed since the last run.
      locationId: null, locationName: 'Derwentwater', date: '2026-08-14', targetType: 'SUNSET',
      rating: 1, summary: 'The name-keyed decoy, which an id-first join must never reach.',
    }, {
      locationId: 1, locationName: 'Derwentwater', date: '2026-08-14', targetType: 'SUNSET',
      rating: 4, summary: 'Broken cloud clearing from the west.',
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
    onOpenSettings: vi.fn(),
    onSignOut: vi.fn(),
    onShowOnMap: vi.fn(),
    // Doors D3 (`plan-to-map-doors-plan.md` §3 D3 task 1): the sheet footer's map action now goes
    // through this, not `onShowOnMap`. A default here — rather than leaving it `null` as
    // `WindowFirstShell`'s own default is — keeps every existing test in this file describing a
    // reader who HAS a map door, which is the ordinary case; the withholding rule itself (no door →
    // the footer's `location-sheet-nomap` note, not a dead button) is `LocationFourDaySheet`'s own
    // to pin, and does not need re-proving through this shell.
    onOpenMapTab: vi.fn(),
    ...over,
  });

  const renderShell = (extra = {}, props = {}) => {
    const value = ctx(extra);
    const merged = shellProps(props);
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(value);
    render(<WindowFirstShell {...merged} />);
    return { ...value, props: merged };
  };

  /** Opens search and picks the named location — the entry that CLOSES the popup first. */
  const openSheetFor = async (name) => {
    fireEvent.keyDown(document, { key: '/' });
    const input = await screen.findByTestId('plan-search-input');
    fireEvent.change(input, { target: { value: name } });
    fireEvent.click(screen.getByRole('option', { name: new RegExp(name) }));
    return screen.findByTestId('location-sheet');
  };

  /**
   * Opens the first window's popup — where the ranked spot cards live since M2.
   *
   * <p>Awaits the matrix's own lazy boundary first, so the first test in the file behaves like the
   * rest rather than like a race the module cache happens to win.
   */
  const openPopup = async () => {
    await screen.findByTestId('wf-heat-strip');
    await act(async () => { fireEvent.click(screen.getAllByTestId('wf-heat-card')[0]); });
    return screen.findByTestId('window-sheet');
  };

  afterEach(() => vi.restoreAllMocks());

  /**
   * Opens the sheet from a ranked spot card, which is the M4 entry that leaves the popup standing.
   *
   * <p>Used by the Escape walk below rather than the field chip, deliberately: the chip needs a
   * measurement shim to exist at all, and the walk is about the SHELL's layering, not about the
   * placer.
   */
  const openStackedSheet = async () => {
    await openPopup();
    fireEvent.click(screen.getByTestId('window-spot'));
    return screen.findByTestId('location-sheet');
  };

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

  it('⚠️ opens a spot card\'s sheet OVER the popup, and does not touch the map', async () => {
    // D-3's reversal, at the seam where it was made. P8 left this click on the map because the card
    // was then a page-level surface; M2 moved the cards into a dialog, which is the fact D-3 says
    // changed the answer. The popup MUST survive: closing it would discard the window the reader is
    // reading to answer a question about one of its rows.
    const view = renderShell();
    await openPopup();
    fireEvent.click(screen.getByTestId('window-spot'));
    const sheet = await screen.findByTestId('location-sheet');
    expect(within(sheet).getByTestId('location-sheet-title')).toHaveTextContent('Derwentwater');
    expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
    expect(view.props.onShowOnMap).not.toHaveBeenCalled();
  });

  describe('the field chip route', () => {
    /**
     * jsdom measures every element at zero, and the field's chips are placed by a greedy pass over
     * REAL measured boxes — so without this shim the layer renders nothing and a wiring test would
     * pass by asserting on an empty set. The same shim, at the same three properties, is what
     * {@code WindowSheetDialog.test.jsx} and {@code WindowRowFieldMap.test.jsx} install for the
     * same reason; it is local to this block so the rest of the file keeps jsdom's real zeroes.
     */
    const MEASURED = [
      [Element.prototype, 'clientWidth', 400],
      [HTMLElement.prototype, 'offsetWidth', 60],
      [HTMLElement.prototype, 'offsetHeight', 14],
    ];
    let originals;
    let originalGetContext;
    beforeEach(() => {
      originals = MEASURED.map(([target, name]) => [target, name,
        Object.getOwnPropertyDescriptor(target, name)]);
      for (const [target, name, value] of MEASURED) {
        Object.defineProperty(target, name, { configurable: true, get: () => value });
      }
      // jsdom's own `getContext('2d')` returns null, and the arm reads null as "this browser cannot
      // give us a canvas" and drops the canvases entirely — chips included. The smallest thing that
      // is not null is enough, because `drawGeo` is mocked above and nothing paints into it.
      originalGetContext = HTMLCanvasElement.prototype.getContext;
      HTMLCanvasElement.prototype.getContext = () => ({});
    });
    afterEach(() => {
      for (const [target, name, descriptor] of originals) {
        if (descriptor) Object.defineProperty(target, name, descriptor);
        else delete target[name];
      }
      HTMLCanvasElement.prototype.getContext = originalGetContext;
    });

    it('⚠️ opens a chip\'s sheet over the popup, and the chip is a NAMED control', async () => {
      // The chip's three M4 properties land together or not at all — the click, the `title`, and
      // the exit from `aria-hidden`. M2 deferred all three as a set precisely because any one alone
      // is useless: a tooltip on a `pointer-events: none` span reaches nobody, and a control inside
      // an `aria-hidden` subtree cannot be found by the readers most likely to need the name.
      const view = renderShell();
      await openPopup();
      const chips = await screen.findAllByTestId('wf-row-map-chip');
      const chip = chips.find((node) => node.dataset.location === 'Derwentwater');
      expect(chip.tagName).toBe('BUTTON');
      // ⚠️ ASKED OF THE ACCESSIBILITY TREE, not of an attribute. A `toContain` on an `aria-label`
      // is not a WCAG 2.5.3 test — M3 lost two names to exactly that shortcut — and here the query
      // proves a second thing no attribute check can: an `aria-hidden` subtree contains no buttons
      // at all, so finding this one BY ROLE is the assertion that the layer left `aria-hidden`.
      //
      // The name is anchored at both ends (an exact match) and tolerant only of the SEPARATOR,
      // because the separator is the one part of it jsdom gets differently from a browser:
      // `.wf-mchip` is `inline-flex`, so a real engine blockifies its children and joins their text
      // with a space, while `css: false` leaves them inline and joins with none. `\\s*` pins the
      // words and their ORDER — which is what 2.5.3 is about — and pins nothing about a stylesheet
      // this environment cannot see.
      const named = screen.getByRole('button', { name: /^Derwentwater\s*4 stars$/ });
      expect(named).toBe(chip);
      // The visible label leads the name. `★` is `aria-hidden` and spoken as "4 stars" instead —
      // NVDA at its default symbol level does not speak U+2605, which is the sheet's own idiom and
      // `HeatmapGrid`'s original measurement.
      expect(chip.querySelector('.wf-mchip-n')).toHaveTextContent('Derwentwater');
      // Region · drive · leave-by — deferred from M2 with the click, because a `title` on a
      // `pointer-events: none` span inside an `aria-hidden` subtree reaches nobody. The drive is
      // the CARD's spot figure, which is the one the ranked strip below prints for the same place.
      expect(chip).toHaveAttribute('title', 'Lake District · 12 min · leave 20:09');

      fireEvent.click(chip);
      const sheet = await screen.findByTestId('location-sheet');
      expect(within(sheet).getByTestId('location-sheet-title')).toHaveTextContent('Derwentwater');
      expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
      expect(view.props.onShowOnMap).not.toHaveBeenCalled();
      // ⚠️ THE REGION CROSSED THE SEAM. `sheetSpotOf` reads it off the chip descriptor, and the
      // dialog has to put it there — drop that one field and this sheet loses its `region · N min
      // from <base>` line AND its whole Plan-from footer, silently, on this route only. The title
      // alone cannot see it.
      expect(within(sheet).getByTestId('location-sheet-meta'))
        .toHaveTextContent('Lake District · 2h from Durham');
      expect(within(sheet).getByRole('button', { name: 'Plan from Lake District' }))
        .toBeInTheDocument();
      // ⚠️ And the ID crossed it. The fixture rates Derwentwater 4 under its id and 1 under its
      // NAME, so a translation that dropped `locationId` would fall through to the name key and
      // print another population's star — the P8 defect this arm's join policy exists to prevent.
      expect(within(sheet).getByTestId('location-sheet-rating')).toHaveTextContent('4★');
    });
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

  it('⚠️ hands the map tab the window it names, and closes first (doors D3)', async () => {
    // Re-pointed from the frozen overlay to the Map tab (plan §3 D3 task 1, §6 Q3 decided yes) —
    // `onOpenMapTab`, never `onShowOnMap`, and carrying the full door shape: the location, `region:
    // null` (this door names a PLACE, not a region), and the Plan's live lens values read through
    // at the moment of the tap (`RATING_LENS.minRating` is null — Any; `LENS.tier.limitMinutes` is
    // 45 — the fixtures' own defaults, not the increment's 4★+/2h30 example, per §4 #6).
    const view = renderShell();
    await openSheetFor('Bamburgh');
    fireEvent.click(screen.getByRole('button', { name: /Show on map/ }));
    // `inPlace: false` — this shell's fixture has no map pane, so `effectiveTab` is 'plan' and the
    // press IS a door. The peek's own in-place stamp is pinned in
    // `WindowFirstShellLocationSheetHandoff.test.jsx`.
    expect(view.props.onOpenMapTab).toHaveBeenCalledWith({
      date: '2026-08-14', targetType: 'SUNSET', locationName: 'Bamburgh Beach', region: null,
      minRating: null, limitMinutes: 45, inPlace: false,
    });
    // Never the retired route — a re-pointing must not also ring the old bell, or the overlay would
    // open silently behind the Map tab.
    expect(view.props.onShowOnMap).not.toHaveBeenCalled();
    // Closed BEFORE the handoff: the map overlay is itself an `aria-modal` dialog, and leaving
    // this one mounted underneath puts two on the page at once. The literal call ORDER
    // (`openOverPopup`, then `openWindow`, then `onOpenMapTab`) is pinned one level down, in
    // `mapDoors.test.js`'s `openMapDoor` suite — unchanged by this phase, since D3 only changes
    // WHICH callback the shell hands to that pure function's `door` slot, never the close-then-move
    // logic itself. What is observable HERE, at the rendered shell, is the single-commit OUTCOME
    // the ordering produces: no dialog survives the tap that opened the door.
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

  describe('the Escape walk, three layers deep (plan-matrix §6 M2.5)', () => {
    /**
     * <p><b>What breaks if this fails.</b> {@code Modal} installs a document-level Escape listener
     * PER INSTANCE, so three open dialogs answer one press three times and the whole stack vanishes.
     * The remedy is a guard per layer rather than a shared stack: whichever layer is not on top
     * declines the key. The order is the bundle README's — search → the location sheet → the window
     * popup — and M4 is the first phase where all three can be on screen at once, because it is
     * what gives the sheet an entry that leaves the popup open.
     *
     * <p>⚠️ <b>THE THIRD LAYER IS GONE, and its removal is M5's adjudication of A22.</b> Through M4
     * this block opened search by CLICKING the tick line, because the {@code /} shortcut had refused
     * the state since M3 ("a third layer has nowhere to go") while the button had not — and the
     * state was reachable, since {@code useDialogFocus} is not a focus trap and a keyboard reader
     * could Tab out of the sheet onto that button. M4's own note called it "the one place M4 widened
     * the surface it was told not to deepen" and handed the ruling to M5.
     *
     * <p>M5 measured what it actually rendered rather than arguing about it. {@code Modal} gives
     * every dialog {@code fixed inset-0 z-50}, so with equal z-index paint order is DOM order — and
     * {@code PlanSearch} renders BEFORE the location sheet in the shell, so the sheet painted its
     * scrim and its whole card over the search panel. The reader was typing into a box behind a
     * dead, dimmed sheet. The ruling is therefore the one M3 had already written down: the button
     * obeys the shortcut's guard, and the supported stack is two layers deep — search over the
     * popup, or a sheet over the popup, never both.
     *
     * <p>What survives from the three-press walk is the RULE it was there for — one layer per press,
     * topmost first — which is now asserted at two layers, plus the refusal itself.
     */
    const esc = () => fireEvent.keyDown(document, { key: 'Escape' });

    it('⚠️ refuses a THIRD layer rather than stacking one that would paint underneath', async () => {
      renderShell();
      await openStackedSheet();
      expect(screen.getAllByRole('dialog').length).toBe(2);

      fireEvent.click(screen.getByTestId('window-first-search'));
      // Asserted on the SHEET's own state as well as on search's absence: `PlanSearch` is `lazy()`,
      // so a bare `queryByTestId(...).toBeNull()` here is satisfied by the chunk not having resolved
      // and survives the guard being deleted. `stacked` is a prop on an already-mounted dialog, so
      // it flips in the same commit and there is nothing to wait for.
      expect(screen.getByTestId('location-sheet')).not.toHaveAttribute('inert');
      expect(screen.getByTestId('location-sheet')).toHaveAttribute('aria-modal', 'true');
      expect(screen.getAllByRole('dialog').length).toBe(2);
      // And the button is out of the tab order, so it is not a control with no visible effect.
      expect(screen.getByTestId('window-first-search').tabIndex).toBe(-1);
    });

    it('⚠️ the beyond line\'s search link refuses a THIRD layer too, not just the masthead button', async () => {
      // The masthead search button and the `/` shortcut both guard on `stackedOverPopup` — this is
      // the strip's OWN route into the same `setSearchSeed` call, and it had no guard at all: a
      // reader could Tab from an open location sheet to this button and open `PlanSearch`
      // UNDERNEATH it (both dialogs are `fixed inset-0 z-50`, so paint order is DOM order and
      // `PlanSearch` mounts before the sheet), leaving a sheet on screen with nothing in it reachable.
      renderShell({
        // Lake District now measures beyond GLANCE_MINUTES (180), so the strip's beyond line
        // renders with its search link naming it — CARD/STRIP_CARD both reference this region.
        reachById: new Map([[1, { driveMinutes: 200 }], [2, { driveMinutes: 40 }]]),
        effectiveReachById: new Map([[1, { driveMinutes: 200 }], [2, { driveMinutes: 40 }]]),
      });
      await openStackedSheet();
      expect(screen.getAllByRole('dialog').length).toBe(2);

      const beyondSearch = await screen.findByTestId('wf-heat-beyond-search');
      expect(beyondSearch).toHaveTextContent('Plan from Lake District');
      fireEvent.click(beyondSearch);

      expect(screen.queryByTestId('plan-search')).toBeNull();
      expect(screen.getByTestId('location-sheet')).not.toHaveAttribute('inert');
      expect(screen.getByTestId('location-sheet')).toHaveAttribute('aria-modal', 'true');
      expect(screen.getAllByRole('dialog').length).toBe(2);
    });

    it('closes topmost-first, exactly one layer per press', async () => {
      renderShell();
      await openStackedSheet();
      expect(screen.getAllByRole('dialog').length).toBe(2);

      esc();
      expect(screen.queryByTestId('location-sheet')).toBeNull();
      expect(screen.getByTestId('window-sheet')).toBeInTheDocument();

      esc();
      expect(screen.queryByTestId('window-sheet')).toBeNull();
    });

    it('⚠️ keeps search over the POPUP alone, which is the stack M3 anchored it for', async () => {
      // The other half of the ruling, and the reason the guard is `stackedOverPopup` rather than
      // "any dialog": search is anchored to the masthead, a surface the popup is drawn OVER rather
      // than inside, so this pair is the one stack this arm supports. Two layers, one modal, and
      // Escape still takes them one at a time.
      renderShell();
      await openPopup();
      fireEvent.click(screen.getByTestId('window-first-search'));
      await screen.findByTestId('plan-search');
      expect(screen.getAllByRole('dialog').length).toBe(2);
      expect(screen.getByTestId('window-sheet')).toHaveAttribute('inert');

      esc();
      expect(screen.queryByTestId('plan-search')).toBeNull();
      expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
      expect(screen.getByTestId('window-sheet')).not.toHaveAttribute('inert');
    });

    it('⚠️ takes the SHEET and not the popup while only those two are up', async () => {
      // The middle rung on its own, because a guard that keyed on "any other dialog" rather than on
      // "something over ME" would pass the three-press walk above and still close both here.
      renderShell();
      await openStackedSheet();
      esc();
      expect(screen.queryByTestId('location-sheet')).toBeNull();
      expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
    });
  });

  describe('only ONE layer may sit over the popup', () => {
    /**
     * <p><b>What breaks if this fails.</b> Three dialogs can stack on the popup — the drill-down
     * sheet, this location sheet and the pick — and all three carry the same
     * {@code escapeEnabled={searchSeed == null}}, because each was written as <em>the</em> stacked
     * layer. Any two open together answer one Escape press twice, straight through the
     * one-layer-per-press rule the popup beneath them relies on (M2.5).
     *
     * <p>Reachable, and made reachable by M4: {@code useDialogFocus} is not a focus trap, so from
     * an open location sheet a keyboard reader can Tab onto the popup's pick badge behind the
     * backdrop and press Enter. Before M4 the location sheet could not coexist with the popup.
     */
    it('takes the location sheet down when the pick dialog opens over the same popup', async () => {
      renderShell();
      await openStackedSheet();
      fireEvent.click(screen.getByTestId('window-sheet-pick'));
      expect(await screen.findByTestId('window-pick-dialog')).toBeInTheDocument();
      expect(screen.queryByTestId('location-sheet')).toBeNull();
      // Two dialogs, not three — and the popup is still the one underneath.
      expect(screen.getAllByRole('dialog').length).toBe(2);
    });

    it('and takes the pick dialog down when a spot card opens the sheet', async () => {
      // The other direction, because a guard written into only one entry point passes the case
      // above and leaves the reverse open.
      renderShell();
      await openPopup();
      fireEvent.click(screen.getByTestId('window-sheet-pick'));
      await screen.findByTestId('window-pick-dialog');
      fireEvent.click(screen.getByTestId('window-spot'));
      await screen.findByTestId('location-sheet');
      expect(screen.queryByTestId('window-pick-dialog')).toBeNull();
      expect(screen.getAllByRole('dialog').length).toBe(2);
    });

    it('⚠️ closes the POPUP too when the sheet hands off to the map (doors D3)', async () => {
      // Pre-D3 this was `MapOverlay`, itself an `aria-modal` dialog with its own unconditional
      // document Escape listener, and `stackedOverPopup` going false the instant the sheet unmounts
      // meant a sheet-only close would leave the popup's listener re-armed under the overlay and
      // one press taking two layers. The destination has moved (the Map tab, not the overlay), but
      // the invariant has not: `openMapTab`'s own `openMapDoor` closes the popup and the sheet
      // FIRST, before it ever reads `onOpenMapTab` — this is the outcome half of that rule; the
      // literal call order is `mapDoors.test.js`'s ("hands the map the window it names" above
      // states why that pin lives there and not here).
      const view = renderShell();
      await openStackedSheet();
      fireEvent.click(screen.getByTestId('location-sheet-map'));
      expect(view.props.onOpenMapTab).toHaveBeenCalledWith({
        date: '2026-08-14', targetType: 'SUNSET', locationName: 'Derwentwater', region: null,
        minRating: null, limitMinutes: 45, inPlace: false,
      });
      expect(view.props.onShowOnMap).not.toHaveBeenCalled();
      expect(screen.queryByTestId('location-sheet')).toBeNull();
      expect(screen.queryByTestId('window-sheet')).toBeNull();
    });

    /**
     * ⚠️ Both of these opened search OVER the stacked sheet through M4. M5 refuses that third layer
     * (see the Escape block above for what it was measured rendering), so they now open search over
     * the POPUP — which is the stack that exists — and the rule they pin is unchanged and still
     * load-bearing: search's picks take every layer down before they act, so nothing moves under a
     * surface the reader is reading. `openOverPopup(null)` stays in both handlers because the pick
     * dialog and the drill-down sheet can still be the stacked layer when search opens... and
     * cannot, since M5 refuses search over any of them. Kept anyway: the handler is the last line
     * of defence for a route someone adds later, and it costs one call on a state that is already
     * being torn down.
     */
    it('⚠️ closes every layer for search\'s region pick, so no origin moves under an open surface', async () => {
      // M4.3 goes to considerable trouble to guarantee "the origin never moves under an open
      // surface". One rule, every route — otherwise the popup's drive figures, its base, its outside
      // badges and every departure change while the reader looks at them.
      const view = renderShell();
      await openPopup();
      fireEvent.click(screen.getByTestId('window-first-search'));
      const input = await screen.findByTestId('plan-search-input');
      fireEvent.change(input, { target: { value: 'Northumberland' } });
      fireEvent.click(screen.getByRole('option', { name: /Northumberland/ }));
      expect(view.setOrigin).toHaveBeenCalledWith(NORTHUMBERLAND);
      expect(screen.queryByTestId('location-sheet')).toBeNull();
      expect(screen.queryByTestId('window-sheet')).toBeNull();
    });

    it('⚠️ moves the popup for search\'s WINDOW pick, and leaves nothing stacked over it', async () => {
      // Search closes and `openWindowKey` moves; anything stacked would sit on top of the window
      // just chosen, so from the reader's side choosing it would have done nothing until the next
      // Escape.
      renderShell();
      await openPopup();
      fireEvent.click(screen.getByTestId('window-first-search'));
      const input = await screen.findByTestId('plan-search-input');
      fireEvent.change(input, { target: { value: 'Fri' } });
      fireEvent.click(screen.getAllByRole('option')[0]);
      expect(screen.queryByTestId('location-sheet')).toBeNull();
      expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
      expect(screen.getByTestId('window-sheet')).not.toHaveAttribute('inert');
    });
  });

  describe('the Plan-from footer (plan-matrix §6 M4.3, D-4)', () => {
    it('⚠️ closes the sheet AND the popup, and only then moves the origin', async () => {
      // P8's invariant, honoured by removing the condition rather than the action: the origin never
      // moves under an open surface. React batches the three updates out of one handler, so the
      // commit that first renders the new origin is the same one that unmounts both dialogs —
      // asserted here as exactly that, a single commit carrying the away framing and no dialog.
      // The literal call ORDER (`onClose` before `onPlanFrom`) is pinned one level down, in
      // `LocationFourDaySheet.test.jsx`, where both are mocks and the sequence is observable.
      let current;
      const setOrigin = vi.fn((region) => {
        current = { ...current, origin: { id: region.id, name: region.name, baseName: region.baseName } };
      });
      current = ctx({ setOrigin });
      const merged = shellProps();
      vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockImplementation(() => current);
      render(<WindowFirstShell {...merged} />);

      await openStackedSheet();
      fireEvent.click(screen.getByRole('button', { name: 'Plan from Lake District' }));

      expect(setOrigin).toHaveBeenCalledWith(LAKES);
      expect(screen.queryByTestId('location-sheet')).toBeNull();
      expect(screen.queryByTestId('window-sheet')).toBeNull();
      // The same commit is already framed from Keswick — so there was no render in which an open
      // dialog described the new origin, which is what the invariant actually asks.
      expect(screen.getByTestId('window-first-origin-chip')).toHaveTextContent('Keswick');
    });

    it('⚠️ puts focus on the plan, rather than dropping the reader at <body>', async () => {
      // Both dialogs unmount in the same commit, so `useDialogFocus`'s restore finds its captured
      // trigger — a chip or a card that lived INSIDE the popup — detached, declines to focus it (as
      // it must: focusing a detached node throws the reader's place away entirely) and leaves focus
      // on `<body>` while the whole page re-frames underneath them. `applyConflictAction` records
      // the identical failure and the identical remedy one screen up.
      //
      // ⚠️ `button[…]`, not `[…]`: an away window keeps its matrix cell as a non-focusable `<div>`
      // and `querySelector` returns DOM order, so the bare selector is a no-op on a plan whose
      // first rendered day is a travel day. And ⚠️ NOT verifiable in the browser pane — its
      // document reports `visibilityState: 'hidden'`, where `requestAnimationFrame` never fires at
      // all (checked, 2026-08-21; the same class of pane limitation M3 recorded for
      // Resize/IntersectionObserver). This test is the only place the move is exercised.
      const raf = vi.spyOn(window, 'requestAnimationFrame')
        .mockImplementation((cb) => { cb(); return 0; });
      renderShell();
      await openStackedSheet();
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Plan from Lake District' }));
      });
      expect(document.activeElement).toBe(screen.getAllByTestId('wf-heat-card')[0]);
      raf.mockRestore();
    });

    it('states the reason instead of a dead control when the region is already the origin', async () => {
      // Rule 14: a control with no visible effect is banned. `originAction` owns the verdict, so the
      // search box and this footer can never disagree about one region.
      renderShell({
        origin: { id: 7, name: 'Lake District', baseName: 'Keswick' },
        effectiveReachById: new Map([[1, { driveMinutes: 15 }], [2, { driveMinutes: 195 }]]),
      });
      const sheet = await openStackedSheet();
      // Scoped to the sheet: the masthead's own origin button names the away base in words that
      // also contain "Plan from", and a page-wide query would match it instead.
      expect(within(sheet).queryByRole('button', { name: /Plan from/ })).toBeNull();
      // ⚠️ It NAMES the region. The search box's own wording for the same verdict is "You are
      // already planning from here" — unambiguous on a region row, and a claim that the origin is
      // Bamburgh when it sits under a heading naming a place. `originAction` shares the test and
      // not the sentence for exactly this.
      expect(within(sheet).getByTestId('location-sheet-plan-note'))
        .toHaveTextContent('Already planning from Lake District');
    });

    it('offers no origin action at all for a region the shell holds no record for', async () => {
      // ⚠️ NOT one of `originAction`'s three reasons — every one of those is a statement ABOUT a
      // record (switched off, no base town, already the origin), and printing one for a region
      // nobody has seen would be a guess dressed as a fact. Silence is the degrade rule.
      renderShell({ regions: [NORTHUMBERLAND] });
      const sheet = await openStackedSheet();
      expect(within(sheet).queryByRole('button', { name: /Plan from/ })).toBeNull();
      expect(within(sheet).queryByTestId('location-sheet-plan-note')).toBeNull();
      // The map action is untouched by any of it — the footer never empties.
      expect(screen.getByTestId('location-sheet-map')).toBeInTheDocument();
    });
  });

  it('⚠️ withholds the map action when the shell itself has no map door (doors D3 task 2)', async () => {
    // `App` withholds `onOpenMapTab` entirely when there is nothing to map — the same rule the
    // overlay hatch already lives by. The sheet must not paper over that with a button whose
    // `onClick` calls nothing (the dead-control ban `onPlanFrom`'s footer note already states);
    // it shows the "opens once the forecast loads" sentence instead, and that sentence now covers
    // TWO absences — no window (pre-existing) and no door (this phase) — with the same words.
    renderShell({}, { onOpenMapTab: undefined });
    const sheet = await openStackedSheet();
    expect(within(sheet).queryByRole('button', { name: /Show on map/ })).toBeNull();
    expect(within(sheet).getByTestId('location-sheet-nomap'))
      .toHaveTextContent('The map opens once the forecast loads.');
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

    it('opens one with the popup and nothing else up — the control the next case rests on', async () => {
      // ⚠️ The peek survives M2 and is suppressed by what is OVER the popup, never by the popup
      // itself: a hover panel is portalled above every `Modal`, so it may only be opened from the
      // topmost surface. This case is the popup being topmost.
      renderShell({ scoreIndex: PEEKABLE });
      await openPopup();
      hoverCard();
      expect(screen.getByTestId('wf-peek')).toBeInTheDocument();
    });

    it('opens none while another dialog is stacked over the popup', async () => {
      // ⚠️ The FOUR-DAY sheet cannot be the stacked dialog this phase, and that is a fact about M2
      // rather than a gap in the test. Its only entry point is search, and `/` is guarded on "any
      // dialog is open" — so with the popup up there is no route to it. M4 gives the popup's field
      // chips and spot cards that route, and M4's own file asserts the stacked case.
      //
      // The pick dialog IS reachable from the popup's header, takes the same `Modal` at the same
      // `z-50`, and exercises the same suppression, so it is what stands in.
      renderShell({ scoreIndex: PEEKABLE });
      await openPopup();
      fireEvent.click(screen.getByTestId('window-sheet-pick'));
      // Lets the dialog's focus move land before the hover: the peek's own `focusin` listener
      // dismisses a panel whose anchor is not the focused element, so hovering too early would
      // pin the focus rule rather than the suppression.
      act(() => { vi.advanceTimersByTime(OPEN_DELAY * 2); });
      hoverCard();
      expect(screen.queryByTestId('wf-peek')).toBeNull();
    });
  });
});
