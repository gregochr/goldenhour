import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  cleanup, fireEvent, render, screen, within,
} from '@testing-library/react';
import WindowSheetDialog from '../components/WindowSheetDialog.jsx';
import { SubRegionStatusProvider } from '../context/SubRegionStatusContext.jsx';
import { buildWindowCards } from '../utils/windowFirstCards.js';
import { buildHeatSpots } from '../utils/heatSpots.js';
import { buildGroundSets } from '../utils/pickScope.js';
import { buildRegionSeries } from '../utils/windowFirstRegions.js';
import { regionPickSummaries } from '../utils/pickGeography.js';
import { FIRST_CHOICE, OPEN } from './subRegionFixtures.js';

vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));
import { getSubRegions } from '../api/subRegionApi.js';

/**
 * The window popup for a Lite reader whose body the server shaped by their sub-region picks (sub-regions-plan §11
 * S5b-1, handoff task 6 and decision 15).
 *
 * <p><b>What breaks if these fail:</b> a region the reader has no pick in is a dead end (no words, no way in); the
 * strip ranks places the reader cannot see; a place outside the picks shows a star, "Not scored" or a count; the
 * popup's field is marked "Not scored" on every shaped window (the served best star is stripped); the rail's S4
 * action opens Settings over the popup (three dialogs) instead of closing the popup first; a Pro, Admin or
 * flag-off popup changes at all.
 */

vi.mock('../utils/heatField.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    load: vi.fn(() => Promise.resolve({ type: 'FeatureCollection', features: [] })),
    land: vi.fn(() => ({ type: 'FeatureCollection', features: [] })),
    drawGeo: vi.fn(() => ([lng, lat]) => [lng * 10, lat * 10]),
  };
});

const TODAY = '2026-08-20';
const TOMORROW = '2026-08-21';
const KEY = `${TODAY}:SUNSET`;
const EVENTS = [{ date: TODAY, targetType: 'SUNSET' }];

const slot = (id, name, rating, extra = {}) => ({
  locationId: id, locationName: name, claudeRating: rating, canopy: false, ...extra,
});

const LOCATIONS = [
  { id: 1, name: 'Bamburgh Beach', lat: 6, lon: 4, regionName: 'Coast', locationType: ['LANDSCAPE'], subRegionId: 11, subRegionName: 'Bamburgh to Howick' },
  { id: 3, name: 'Craster', lat: 26, lon: 4, regionName: 'Coast', locationType: ['LANDSCAPE'], subRegionId: 12, subRegionName: 'Alnwick & the Coquet Coast' },
  { id: 2, name: 'Malham Cove', lat: 6, lon: 24, regionName: 'Dales', locationType: ['LANDSCAPE'], subRegionId: 31, subRegionName: 'Malhamdale & Airedale' },
  { id: 4, name: 'Gordale', lat: 26, lon: 24, regionName: 'Dales', locationType: ['LANDSCAPE'], subRegionId: 32, subRegionName: 'Wharfedale' },
];

const REGION_PROSE = 'A thin high veil over the Dales.';

/** The server's shaping: a place outside the picks is marked gated and loses its star. */
const shape = (allowed) => (s) => (allowed.includes(s.locationId)
  ? s : { ...s, claudeRating: null, gated: true });

function summary({ rated = true, allowed = [1] } = {}) {
  const mark = shape(allowed);
  return {
    targetType: 'SUNSET',
    regions: [
      {
        regionName: 'Coast',
        displayVerdict: 'WORTH_IT',
        summary: 'A clean eastern horizon.',
        meanRating: null,
        bestRating: null,
        picksBestRating: rated ? 4 : undefined,
        slots: [slot(1, 'Bamburgh Beach', rated ? 4 : null), slot(3, 'Craster', 3)].map(mark),
      },
      {
        regionName: 'Dales',
        displayVerdict: 'MAYBE',
        summary: REGION_PROSE,
        meanRating: null,
        bestRating: null,
        slots: [slot(2, 'Malham Cove', 3), slot(4, 'Gordale', 2)].map(mark),
      },
    ],
    unregioned: [],
    shapedLeadRegion: 'Coast',
    window: { verdict: 'WORTH_IT', badges: [], bestRating: null },
  };
}

/** Everything the shell would hand the popup for a shaped body, from the real builders. */
function shapedFixture({ allowed = [1], rated = true } = {}) {
  const sum = summary({ rated, allowed });
  const days = [{ date: TODAY, eventSummaries: [sum] }];
  const allowedIds = new Set(allowed);
  const [card] = buildWindowCards(EVENTS, days, TODAY, TOMORROW, new Set(), new Map(), {
    limitMinutes: null, defaultLimitMinutes: null, allowedIds,
  });
  const rows = [
    { locationId: 1, locationName: 'Bamburgh Beach', date: TODAY, targetType: 'SUNSET', rating: rated ? 4 : null },
    { locationId: 3, locationName: 'Craster', date: TODAY, targetType: 'SUNSET', rating: 3 },
    { locationId: 2, locationName: 'Malham Cove', date: TODAY, targetType: 'SUNSET', rating: 3 },
    { locationId: 4, locationName: 'Gordale', date: TODAY, targetType: 'SUNSET', rating: 2 },
  ].map((row) => (allowed.includes(row.locationId) ? row : { ...row, rating: null, gated: true }));
  const heatSpots = buildHeatSpots(LOCATIONS, rows, EVENTS, allowedIds);
  return {
    card,
    field: {
      eventSummary: sum,
      spots: heatSpots,
      points: [],
      unrated: [],
      stood: [],
      windows: [{ key: KEY, dow: 'Thu', sunrise: false, label: 'Tonight Sunset', time: '20:41' }],
      series: buildRegionSeries(EVENTS, days),
      reachById: new Map(),
      lens: { limitMinutes: null, tierLabel: 'Any', minRating: null, ratingLabel: 'Any rating' },
      onSelectRegion: vi.fn(),
      selectedRegion: null,
      singleRegionScope: false,
      origin: null,
      homeCoords: null,
      regionPicks: regionPickSummaries(heatSpots, allowed),
      ground: buildGroundSets(heatSpots, EVENTS, true).get(KEY) ?? null,
    },
  };
}

function renderDialog({ fixture = shapedFixture(), fieldOverrides = {}, props = {}, status = null } = {}) {
  const openSettings = vi.fn();
  const onOpenSubRegions = vi.fn();
  const onClose = vi.fn();
  if (status) getSubRegions.mockResolvedValue(status);
  const view = render(
    <SubRegionStatusProvider enabled={Boolean(status)} onOpen={openSettings}>
      <WindowSheetDialog
        card={fixture.card}
        index={0}
        total={6}
        field={{ ...fixture.field, ...fieldOverrides }}
        topicIndex={new Map()}
        scopeNames={['Coast', 'Dales']}
        todayStr={TODAY}
        onClose={onClose}
        onStep={vi.fn()}
        onOpenSubRegions={onOpenSubRegions}
        {...props}
      />
    </SubRegionStatusProvider>,
  );
  return {
    openSettings, onOpenSubRegions, onClose, ...view,
  };
}

let originalGetContext;
beforeEach(() => {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
});
afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  cleanup();
  vi.clearAllMocks();
});

const cell = (name) => screen.getAllByTestId('wf-region-cell').find((c) => c.dataset.region === name);
const meta = (name) => cell(name).querySelector('.wf-rr-m').textContent;

describe('the popup of a shaped body: the rail and the header', () => {
  it('orders the rail picks first, says so, and counts the places in and out on the All cell', () => {
    renderDialog();

    expect(screen.getByTestId('wf-region-rail-label').textContent).toBe('By region · your sub-regions first');
    expect(screen.getAllByTestId('wf-region-cell').map((c) => c.dataset.region)).toEqual(['all', 'Coast', 'Dales']);
    expect(meta('all')).toBe('1 in your sub-regions · 3 outside');
    expect(meta('Coast')).toBe('best 4★ · 1 place · 1 outside');
    expect(meta('Dales')).toBe('outside your sub-regions');
    expect(cell('Dales').querySelector('.wf-rr-v').textContent).toBe('Maybe');
  });

  it('names the best of the reader\'s own places in the header, never "nothing in reach"', () => {
    renderDialog();

    expect(screen.getByTestId('window-sheet-best')).toHaveTextContent('best 4');
    expect(screen.getByTestId('window-sheet-best')).not.toHaveTextContent(/reach/);
  });

  it('leaves the best line out for a reader with no place of their own', () => {
    renderDialog({ fixture: shapedFixture({ allowed: [] }) });

    expect(screen.queryByTestId('window-sheet-best')).toBeNull();
  });

  it('says "nothing rated yet" when places are theirs and none is rated', () => {
    renderDialog({ fixture: shapedFixture({ rated: false }) });

    expect(screen.getByTestId('window-sheet-best')).toHaveTextContent('nothing rated yet');
  });

  it('is silent about the stripped means: no region best window, no "not scored"', () => {
    renderDialog({ fieldOverrides: { selectedRegion: 'Coast' } });

    const prose = screen.getByTestId('wf-prose');
    expect(prose).not.toHaveTextContent(/not scored|not rated|own best is/i);
    expect(screen.queryByTestId('wf-prose-moved')).toBeNull();
  });
});

describe('the popup field map', () => {
  it('is not marked "Not scored" because the served window best is null', () => {
    renderDialog();

    expect(screen.queryByTestId('wf-row-map-unscored')).toBeNull();
  });

  it('keeps the "Not scored" chip when none of the reader\'s places is rated, and hatches around them, not the plate', async () => {
    const { drawGeo } = await import('../utils/heatField.js');
    renderDialog({ fixture: shapedFixture({ rated: false }) });

    expect(screen.getByTestId('wf-row-map-unscored')).toHaveTextContent('Not scored');
    const opts = drawGeo.mock.calls.at(-1)?.[5];
    if (opts) expect(opts.hatch).toBe(false);
  });

  it('keeps the mark for an unshaped body whose served best is null', () => {
    const fixture = shapedFixture();
    renderDialog({
      fixture: { card: { ...fixture.card, bestRating: null }, field: fixture.field },
      fieldOverrides: { regionPicks: null, ground: null },
    });

    expect(screen.getByTestId('wf-row-map-unscored')).toHaveTextContent('Not scored');
  });
});

describe('the spot list', () => {
  it('lists the reader\'s places first and the outside ones after, labelled, with no star', () => {
    renderDialog();

    const spots = screen.getAllByTestId('window-spot');
    expect(spots.map((s) => within(s).getByText(/Bamburgh Beach|Craster|Malham Cove|Gordale/).textContent))
      .toEqual(['Bamburgh Beach', 'Craster', 'Gordale', 'Malham Cove']);
    expect(within(spots[0]).getByTestId('window-spot-rating')).toHaveTextContent('4★');
    expect(within(spots[0]).queryByTestId('window-spot-outside')).toBeNull();
    for (const outside of spots.slice(1)) {
      expect(outside).toHaveAttribute('data-outside', 'true');
      expect(within(outside).getByTestId('window-spot-outside')).toHaveTextContent('outside your sub-regions');
      expect(within(outside).queryByTestId('window-spot-rating')).toBeNull();
      expect(outside).not.toHaveAttribute('data-rating');
      expect(outside.textContent).not.toMatch(/★|not scored|not rated/i);
    }
  });

  it('counts the reader\'s places only, and the outside ones apart', () => {
    renderDialog();

    expect(screen.getByTestId('window-spot-count')).toHaveTextContent('1 spot');
    expect(screen.getByTestId('window-spot-outside-count')).toHaveTextContent('· 3 outside');
    expect(screen.getByTestId('window-spot-order')).toHaveTextContent('Ranked by rating.');
  });

  it('counts two places and two outside, each by its own number', () => {
    const fixture = shapedFixture({ allowed: [1, 3] });
    renderDialog({ fixture });

    expect(screen.getByTestId('window-spot-count')).toHaveTextContent('2 spots');
    expect(screen.getByTestId('window-spot-outside-count')).toHaveTextContent('· 2 outside');
  });

  it('says "1 outside" when exactly one place lies outside', () => {
    renderDialog({ fixture: shapedFixture({ allowed: [1, 3, 2] }) });

    expect(screen.getByTestId('window-spot-outside-count').textContent).toBe('· 1 outside');
  });

  it('opens no peek over an outside place', () => {
    renderDialog();

    const outside = screen.getAllByTestId('window-spot')[1];
    fireEvent.mouseEnter(outside);
    fireEvent.focus(outside);

    expect(screen.queryByTestId('window-spot-peek')).toBeNull();
  });

  it('a Pro, Admin or flag-off strip carries no outside label and no outside count', () => {
    const fixture = shapedFixture();
    // The same body as Pro gets it: nothing is gated, and no list is handed to the card builder.
    const everyone = summary({ allowed: [1, 2, 3, 4] });
    const [open] = buildWindowCards(EVENTS, [{ date: TODAY, eventSummaries: [everyone] }], TODAY, TOMORROW, new Set(), new Map(), {
      limitMinutes: null, defaultLimitMinutes: null,
    });
    renderDialog({
      fixture: { card: open, field: fixture.field },
      fieldOverrides: { regionPicks: null, ground: null },
    });

    expect(screen.queryByTestId('window-spot-outside')).toBeNull();
    expect(screen.queryByTestId('window-spot-outside-count')).toBeNull();
    expect(screen.getByTestId('wf-region-rail-label').textContent).toBe('By region · best first');
  });
});

describe('a region the reader holds no pick in', () => {
  it('fills the prose slot with the region\'s own prose, the S4 line and action, and one door', async () => {
    renderDialog({ fieldOverrides: { selectedRegion: 'Dales' }, status: OPEN });

    expect(screen.getByTestId('wf-prose-name')).toHaveTextContent('Dales');
    expect(screen.getByTestId('wf-prose-body')).toHaveTextContent(REGION_PROSE);
    const slotBox = screen.getByTestId('wf-prose-outside');
    expect(await within(slotBox).findByTestId('wf-region-picks-action')).toHaveTextContent('Change your sub-regions');
    expect(within(slotBox).getByTestId('wf-region-picks-line').textContent).toMatch(/sub-regions/);
    // One door: the region's. The Pill is inside its trigger.
    expect(screen.getAllByTestId('wf-subregions-region-upsell-trigger')).toHaveLength(1);
    expect(within(screen.getByTestId('wf-subregions-region-upsell-trigger')).getByText('Pro')).toBeInTheDocument();
    expect(slotBox.querySelector('a')).toBeNull();
  });

  it('says "None of your sub-regions are in {region}." where the spot list would rank places', () => {
    renderDialog({ fieldOverrides: { selectedRegion: 'Dales' } });

    expect(screen.getByTestId('window-sheet-empty')).toHaveTextContent('None of your sub-regions are in Dales.');
    expect(screen.queryByTestId('window-spot-strip')).toBeNull();
    expect(screen.getByTestId('window-sheet-empty').textContent).not.toMatch(/Nothing|not scored|not rated|0 /i);
    expect(screen.getByTestId('window-sheet-live')).toHaveTextContent('showing Dales, 0 locations');
  });

  it('closes the popup first: the action calls the shell\'s opener, never the provider\'s', async () => {
    const { onOpenSubRegions, openSettings } = renderDialog({
      fieldOverrides: { selectedRegion: 'Dales' }, status: OPEN,
    });

    fireEvent.click(await screen.findByTestId('wf-region-picks-action'));

    expect(onOpenSubRegions).toHaveBeenCalledTimes(1);
    expect(openSettings).not.toHaveBeenCalled();
  });

  it('keeps the popup open on Escape inside the door, closing only the door', async () => {
    const { onClose } = renderDialog({ fieldOverrides: { selectedRegion: 'Dales' } });

    fireEvent.click(screen.getByTestId('wf-subregions-region-upsell-trigger'));
    expect(screen.getByTestId('wf-subregions-region-upsell-popover')).toBeInTheDocument();
    fireEvent.keyDown(screen.getByTestId('wf-subregions-region-upsell-popover'), { key: 'Escape' });

    expect(screen.queryByTestId('wf-subregions-region-upsell-popover')).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('the door names the region, its places and its sub-regions in served counts, never "upgrade"', () => {
    renderDialog({ fieldOverrides: { selectedRegion: 'Dales' } });

    fireEvent.click(screen.getByTestId('wf-subregions-region-upsell-trigger'));
    const body = screen.getByTestId('wf-subregions-region-upsell-body').textContent;
    expect(body).toMatch(/Dales/);
    expect(body).toMatch(/2 places/);
    expect(body).toMatch(/two sub-regions/);
    expect(screen.getByTestId('wf-subregions-region-upsell-popover').textContent).not.toMatch(/upgrade/i);
  });

  it('does not say none of their sub-regions are in a region they hold a pick in', () => {
    const fixture = shapedFixture({ allowed: [1] });
    // Their place there is not in this window's list (no slot), yet the region holds a pick.
    const card = { ...fixture.card, spots: [], allSpots: [] };
    renderDialog({ fixture: { card, field: fixture.field }, fieldOverrides: { selectedRegion: 'Coast' } });

    expect(screen.getByTestId('window-sheet-empty')).toHaveTextContent('Nothing in your sub-regions in Coast for this sunset.');
    expect(screen.getByTestId('window-sheet-empty')).not.toHaveTextContent(/None of your/);
  });

  it('adds nothing under the prose of a region the reader does hold', () => {
    renderDialog({ fieldOverrides: { selectedRegion: 'Coast' } });

    expect(screen.queryByTestId('wf-prose-outside')).toBeNull();
    expect(screen.queryByTestId('wf-subregions-region-upsell-trigger')).toBeNull();
    expect(screen.queryByTestId('window-sheet-empty')).toBeNull();
    expect(screen.getByTestId('window-spot-strip')).toBeInTheDocument();
  });
});

describe('a reader with no pick at all', () => {
  const none = () => shapedFixture({ allowed: [] });

  it('says "By region" alone: there is no order to speak of', () => {
    renderDialog({ fixture: none() });

    expect(screen.getByTestId('wf-region-rail-label').textContent).toBe('By region');
  });

  it('offers no "See all" that leads only to places outside the picks', () => {
    renderDialog({ fixture: none(), props: { onSeeAllSpots: vi.fn() } });

    expect(screen.queryByTestId('window-sheet-see-all')).toBeNull();
  });

  it('still draws the dawn race: an eclipse is what is happening, never silenced by the picks', () => {
    const fixture = none();
    const sight = {
      type: 'LUNAR_ECLIPSE', race: 'DAWN', moonAltAtMax: 7, moonAzAtMax: 244, moonAzCardinal: 'WSW',
      maximum: `${TODAY}T05:13:00`, umbraStart: `${TODAY}T03:34:00`, umbraEnd: `${TODAY}T06:52:00`,
      moonset: `${TODAY}T06:16:00`, moonrise: null, setsInShadow: true, risesInShadow: false,
      stops: [
        { key: 'NAUTICAL_DAWN', time: `${TODAY}T03:09:00` },
        { key: 'CIVIL_DAWN', time: `${TODAY}T05:24:00` },
        { key: 'SUNRISE', time: `${TODAY}T06:11:00` },
        { key: 'GOLDEN_MORNING_END', time: `${TODAY}T06:41:00` },
      ],
    };
    const byId = new Map(fixture.card.spots.map((s) => [`${s.locationId}|${TODAY}|SUNSET`, sight]));
    renderDialog({
      fixture: {
        card: { ...fixture.card, allBadges: [{ type: 'LUNAR_ECLIPSE', label: 'Lunar eclipse', detail: '93% at 05:13', rarityRank: 2 }] },
        field: fixture.field,
      },
      props: { eclipseIndex: { byId, byName: new Map() } },
    });

    expect(screen.getByTestId('dawn-race')).toBeInTheDocument();
  });

  it('says "{m} outside your sub-regions" on the All cell, never "0 in", and lists no ranked strip', () => {
    renderDialog({ fixture: none() });

    expect(meta('all')).toBe('4 outside your sub-regions');
    expect(screen.queryByTestId('window-spot-strip')).toBeNull();
    expect(screen.getByTestId('window-sheet-empty'))
      .toHaveTextContent('Choose your sub-regions in Settings to see ratings.');
  });

  it('gives a no-pick region the Choose button and no region door: the one way in is one button', async () => {
    renderDialog({ fixture: none(), fieldOverrides: { selectedRegion: 'Dales' }, status: FIRST_CHOICE });

    expect(await screen.findByTestId('wf-region-picks-action')).toHaveTextContent('Choose your sub-regions');
    expect(screen.queryByTestId('wf-subregions-region-upsell-trigger')).toBeNull();
    expect(screen.getByTestId('window-sheet-empty')).toHaveTextContent('None of your sub-regions are in Dales.');
  });
});
