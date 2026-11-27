import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, within, act } from '@testing-library/react';
import React from 'react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

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

/**
 * The four-day location sheet's wiring through the shell (plan D10, P8).
 *
 * <p><b>What breaks if these fail.</b> A search result that opens nothing, or two dialogs on the page with two Escape
 * listeners between them. (The popup's spot cards and field chips, its other entries, went with the popup in G2b.)
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

  /** Opens search and picks the named location. */
  const openSheetFor = async (name) => {
    fireEvent.click(screen.getByTestId('window-first-search'));
    const input = await screen.findByTestId('plan-search-input');
    fireEvent.change(input, { target: { value: name } });
    fireEvent.click(screen.getByRole('option', { name: new RegExp(name) }));
    return screen.findByTestId('location-sheet');
  };

  afterEach(() => vi.restoreAllMocks());

  /** The sheet for the fixture's first place, through Find: the way a reader reaches a four-day sheet on Plan (G2b). */
  const openStackedSheet = () => openSheetFor('Derwentwater');

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

  it('⚠️ a wildlife hide picked from search shows its hourly comfort tables, not window rows (shell → sheet, both joins real)', async () => {
    // The sheet is reached by Plan search too, so the hide's table is not a Map-tab-only feature. The
    // heat spot carries `skySubject: false`; the roster record (with `forecastsByDate`) arrives on the
    // shell's `locations` prop. Nothing is injected into the sheet directly.
    const HIDE_SPOT = {
      id: 9, name: 'Gosforth Nature Reserve', lat: 55.0, lng: -1.62, regionName: 'Northumberland',
      rid: 'Northumberland', skySubject: false, bortleClass: 5, scores: [null],
    };
    const HIDE_RECORD = {
      id: 9, name: 'Gosforth Nature Reserve', lat: 55.0, lon: -1.62, locationType: ['WILDLIFE'],
      forecastsByDate: new Map([['2026-08-14', {
        sunrise: null,
        sunset: null,
        hourly: [{
          solarEventTime: '2026-08-14T08:00:00', temperatureCelsius: 12, apparentTemperatureCelsius: 10,
          windSpeed: 4, windDirection: 270, precipitationProbabilityPercent: 30,
        }],
      }]]),
    };
    renderShell({ heatSpots: [...SPOTS, HIDE_SPOT] }, { locations: [HIDE_RECORD] });
    const sheet = await openSheetFor('Gosforth');
    const day = await within(sheet).findByTestId('location-sheet-hourly-day');
    expect(day).toHaveAttribute('data-date', '2026-08-14');
    expect(within(day).getByRole('table')).toHaveTextContent('12°C · feels 10°C');
    expect(within(sheet).queryAllByTestId('location-sheet-row')).toEqual([]);
    expect(within(sheet).queryByText(/Not scored/)).toBeNull();
  });

  describe('the sheet\'s forecast-detail gating (overlay convergence C2)', () => {
    // A roster record whose sunset row already carries its detail (`lowCloud` defined), so no fetch
    // is involved: the only thing that can differ between the two cases is the `isPro` boolean the
    // shell reads off the provider and hands to the sheet.
    const BAMBURGH_RECORD = {
      id: 2, name: 'Bamburgh Beach', lat: 55.61, lon: -1.71, locationType: ['SEASCAPE'],
      forecastsByDate: new Map([['2026-08-14', {
        sunrise: null,
        sunset: {
          id: 77, lowCloud: 5, aerosolOpticalDepth: 0.6, targetDate: '2026-08-14', targetType: 'SUNSET',
          solarEventTime: '2026-08-14T19:38:00',
        },
        hourly: [],
      }]]),
    };

    it('hands the sheet isPro = true: the PRO dust wording', async () => {
      renderShell({ isPro: true }, { locations: [BAMBURGH_RECORD] });
      await openSheetFor('Bamburgh');
      expect(await screen.findByTestId('location-sheet-fact-dust'))
        .toHaveTextContent('Elevated dust (e.g. Saharan dust)');
    });

    it('hands the sheet isPro = false: the LITE dust wording', async () => {
      renderShell({ isPro: false }, { locations: [BAMBURGH_RECORD] });
      await openSheetFor('Bamburgh');
      const dust = await screen.findByTestId('location-sheet-fact-dust');
      expect(dust).toHaveTextContent('High aerosols');
      expect(dust).not.toHaveTextContent('Saharan');
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
    // `inPlace: false` — this shell's fixture has no map pane, so the view is closed and the
    // press IS a door. The peek's own in-place stamp is pinned in
    // `WindowFirstShellLocationSheetHandoff.test.jsx`.
    // One door, `openMap`'s: the location trigger with the Plan's live lens, and no `from` (the breadcrumb that named it
    // is gone, one-screen-plan.md D12).
    expect(view.props.onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'location', date: '2026-08-14', eventType: 'SUNSET', locationName: 'Bamburgh Beach',
      minRating: null, limitMinutes: 45, inPlace: false,
    }));
    expect(view.props.onOpenMapTab.mock.calls[0][0]).not.toHaveProperty('from');
    // Never the retired route — a re-pointing must not also ring the old bell, or the overlay would
    // open silently behind the Map tab.
    expect(view.props.onShowOnMap).not.toHaveBeenCalled();
    // Closed BEFORE the handoff: the map overlay is itself an `aria-modal` dialog, and leaving
    // this one mounted underneath puts two on the page at once. The literal call ORDER
    // (`openSheetLayer`, then `openWindow`, then `onOpenMapTab`) is pinned one level down, in
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

  describe('the sheet and the search guard (plan-matrix §6 M2.5)', () => {
    /**
     * <p><b>What breaks if this fails.</b> {@code Modal} installs a document-level Escape listener
     * PER INSTANCE, so two open dialogs answer one press twice. {@code Modal} also gives every dialog
     * {@code fixed inset-0 z-50}, so with equal z-index paint order is DOM order, and
     * {@code PlanSearch} renders BEFORE the location sheet in the shell: search opened over the sheet
     * paints underneath it, and the reader types into a box behind a dead, dimmed sheet. So the masthead
     * button and the beyond line's link obey the same guard the shortcut does: no search while a sheet is up.
     */
    it('⚠️ refuses a THIRD layer rather than stacking one that would paint underneath', async () => {
      renderShell();
      await openStackedSheet();
      expect(screen.getAllByRole('dialog').length).toBe(1);

      fireEvent.click(screen.getByTestId('window-first-search'));
      // Asserted on the SHEET's own state as well as on search's absence: `PlanSearch` is `lazy()`,
      // so a bare `queryByTestId(...).toBeNull()` here is satisfied by the chunk not having resolved
      // and survives the guard being deleted. `stacked` is a prop on an already-mounted dialog, so
      // it flips in the same commit and there is nothing to wait for.
      expect(screen.getByTestId('location-sheet')).not.toHaveAttribute('inert');
      expect(screen.getByTestId('location-sheet')).toHaveAttribute('aria-modal', 'true');
      expect(screen.getAllByRole('dialog').length).toBe(1);
      // And the button is out of the tab order, so it is not a control with no visible effect.
      expect(screen.getByTestId('window-first-search').tabIndex).toBe(-1);
    });

    it('⚠️ the beyond line\'s search link refuses a THIRD layer too, not just the masthead button', async () => {
      // The masthead search button and the `/` shortcut both guard on `stackedOverLayer` — this is
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
      expect(screen.getAllByRole('dialog').length).toBe(1);

      const beyondSearch = await screen.findByTestId('wf-heat-beyond-search');
      expect(beyondSearch).toHaveTextContent('Plan from Lake District');
      fireEvent.click(beyondSearch);

      expect(screen.queryByTestId('plan-search')).toBeNull();
      expect(screen.getByTestId('location-sheet')).not.toHaveAttribute('inert');
      expect(screen.getByTestId('location-sheet')).toHaveAttribute('aria-modal', 'true');
      expect(screen.getAllByRole('dialog').length).toBe(1);
    });

  });

  describe('one layer, and search\'s picks', () => {
    it('⚠️ takes every layer down for search\'s region pick, so no origin moves under an open surface', async () => {
      // M4.3 goes to considerable trouble to guarantee "the origin never moves under an open
      // surface". One rule, every route.
      const view = renderShell();
      fireEvent.click(screen.getByTestId('window-first-search'));
      const input = await screen.findByTestId('plan-search-input');
      fireEvent.change(input, { target: { value: 'Northumberland' } });
      fireEvent.click(screen.getByRole('option', { name: /Northumberland/ }));
      expect(view.setOrigin).toHaveBeenCalledWith(NORTHUMBERLAND);
      expect(screen.queryByTestId('location-sheet')).toBeNull();
    });
  });

  describe('the Plan-from footer (plan-matrix §6 M4.3, D-4)', () => {
    it('⚠️ closes the sheet, and only then moves the origin', async () => {
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
      // ⚠️ The card's inner BUTTON, not the card: the card is a `<div>`, and an away window keeps its
      // cell as a `<div>` with no button, so `querySelector` (DOM order) finds only a control on a
      // plan whose first rendered day is a travel day. And ⚠️ NOT verifiable in the browser pane — its
      // document reports `visibilityState: 'hidden'`, where `requestAnimationFrame` never fires at
      // all (checked, 2026-08-21; the same class of pane limitation M3 recorded for
      // Resize/IntersectionObserver). This test is the only place the move is exercised.
      const raf = vi.spyOn(window, 'requestAnimationFrame')
        .mockImplementation((cb) => { cb(); return 0; });
      renderShell();
      // ⚠️ Opened from SEARCH, not from a card. A pointer click on a card now focuses the card's button before
      // the popup opens (so the popup's own restore has an address), which means a sheet stacked on a popup
      // opened from the first card would hand focus back to that very button and this test would pass with the
      // route's lookup broken. From search there is no card in the chain: whatever the sheet's restore does,
      // it cannot land on a matrix card, so only the route's own move (`wf-heat-card-button`) can.
      await screen.findByTestId('wf-heat-strip');
      await openSheetFor('Derwentwater');
      expect(document.activeElement).not.toBe(screen.getAllByTestId('wf-heat-card-button')[0]);
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Plan from Lake District' }));
      });
      // The first card's inner BUTTON (the card is a `div`): `WindowFirstShell`'s "Plan from region" route
      // looks it up by `wf-heat-card-button`, so reverting that selector sends focus nowhere and fails here.
      expect(document.activeElement).toBe(screen.getAllByTestId('wf-heat-card-button')[0]);
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

});
