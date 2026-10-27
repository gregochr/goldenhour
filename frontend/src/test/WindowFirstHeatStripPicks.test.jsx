import React from 'react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import WindowFirstHeatStrip from '../components/WindowFirstHeatStrip.jsx';
import { SubRegionStatusProvider } from '../context/SubRegionStatusContext.jsx';
import { buildWindowCards } from '../utils/windowFirstCards.js';
import { buildHeatStripCards } from '../utils/windowFirstStrip.js';
import { buildHeatPointSets, buildHeatSpots } from '../utils/heatSpots.js';
import { buildGroundSets } from '../utils/pickScope.js';
import { picksNoteText } from '../utils/pickGeography.js';
import { drawGeo } from '../utils/heatField.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { FIRST_CHOICE } from './subRegionFixtures.js';

vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));
import { getSubRegions } from '../api/subRegionApi.js';

/**
 * The Plan strip for a Lite reader whose body the server shaped by their sub-region picks (sub-regions-plan §11 S5b-1,
 * B1, B2 and handoff task 6).
 *
 * <p><b>What breaks if these fail:</b> every shaped card reads "not scored" (B1: the server strips the window's best
 * star, so the served best can no longer say it); a place outside the picks is counted in the Spread, the thin
 * line, the pool phrase or the "N rated" of the card (B2); a thumbnail is hatched over ground that is outside the
 * picks, or the veil and outline reach a Pro, Admin or flag-off reader; the Best row says "nothing in reach" for a
 * pool the picks emptied; the legend names ground that is not drawn.
 *
 * <p>The kernel is mocked at its boundary (jsdom paints no canvas): what is asserted is the options each thumbnail
 * is handed, and every word the strip prints.
 */

vi.mock('../utils/heatField.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    load: vi.fn(() => Promise.resolve({ type: 'FeatureCollection', features: [] })),
    land: vi.fn(() => ({ type: 'FeatureCollection', features: [] })),
    drawGeo: vi.fn(() => null),
  };
});
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn(() => false) }));

const TODAY = '2026-08-04';
const TOMORROW = '2026-08-05';
const EVENTS = [{ date: TODAY, targetType: 'SUNSET' }];

const slot = (id, name, rating, extra = {}) => ({
  locationId: id, locationName: name, claudeRating: rating, canopy: false, ...extra,
});

/** The reader holds Bamburgh (rated 4) and Craster (unrated); Malham and Malham Tarn are outside. */
function summary({ mine = [slot(1, 'Bamburgh', 4), slot(7, 'Craster', null)] } = {}) {
  return {
    targetType: 'SUNSET',
    regions: [
      {
        regionName: 'Coast', displayVerdict: 'WORTH_IT', meanRating: null, bestRating: null, slots: [
          ...mine, slot(2, 'Malham', null, { gated: true }), slot(3, 'Malham Tarn', null, { gated: true }),
        ],
      },
    ],
    unregioned: [],
    window: { verdict: 'WORTH_IT', badges: [], bestRating: null },
  };
}

const LOCATIONS = [
  { id: 1, name: 'Bamburgh', lat: 55.6, lon: -1.7, regionName: 'Coast', locationType: ['LANDSCAPE'] },
  { id: 7, name: 'Craster', lat: 55.47, lon: -1.59, regionName: 'Coast', locationType: ['LANDSCAPE'] },
  { id: 2, name: 'Malham', lat: 54.07, lon: -2.16, regionName: 'Coast', locationType: ['LANDSCAPE'] },
  { id: 3, name: 'Malham Tarn', lat: 54.09, lon: -2.17, regionName: 'Coast', locationType: ['LANDSCAPE'] },
];

/** The rows /api/briefing/evaluate/scores serves a Lite reader: the reader's own, and the rest gated. */
const scoreRows = ({ rated = true } = {}) => ([
  { locationId: 1, locationName: 'Bamburgh', date: TODAY, targetType: 'SUNSET', rating: rated ? 4 : null },
  { locationId: 7, locationName: 'Craster', date: TODAY, targetType: 'SUNSET', rating: null },
  { locationId: 2, locationName: 'Malham', date: TODAY, targetType: 'SUNSET', rating: null, gated: true },
  { locationId: 3, locationName: 'Malham Tarn', date: TODAY, targetType: 'SUNSET', rating: null, gated: true },
]);

/**
 * Everything the context hands the strip for a shaped body, built by the real functions.
 *
 * @param {object} [opts]
 * @param {number[]} [opts.allowed] the served allowedLocationIds
 * @param {boolean} [opts.rated] whether Bamburgh is rated in the scores
 * @param {boolean} [opts.scoresLoaded] whether the scores have answered
 */
function shapedProps({
  allowed = [1, 7], rated = true, scoresLoaded = true, summaryOverrides,
} = {}) {
  const sum = summary(summaryOverrides);
  const days = [{ date: TODAY, eventSummaries: [sum] }];
  const allowedIds = new Set(allowed);
  const cards = buildWindowCards(EVENTS, days, TODAY, TOMORROW, new Set(), new Map(), {
    limitMinutes: null, defaultLimitMinutes: null, allowedIds,
  });
  const stripCards = buildHeatStripCards(EVENTS, cards, new Set(), days, TODAY, TOMORROW);
  const spots = buildHeatSpots(LOCATIONS, scoreRows({ rated }), EVENTS, allowedIds);
  return {
    cards: stripCards,
    spots,
    pointSets: buildHeatPointSets(spots, EVENTS),
    groundSets: buildGroundSets(spots, EVENTS, scoresLoaded),
    picks: { allowed: allowed.length, total: LOCATIONS.length },
  };
}

async function renderStrip(props, { enabled = false } = {}) {
  let result;
  await act(async () => {
    result = render(
      <SubRegionStatusProvider enabled={enabled} onOpen={vi.fn()}>
        <WindowFirstHeatStrip todayStr={TODAY} onOpenWindow={vi.fn()} {...props} />
      </SubRegionStatusProvider>,
    );
  });
  return result;
}

async function withMeasuredThumbs(px, run) {
  const original = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');
  Object.defineProperty(Element.prototype, 'clientWidth', { configurable: true, get: () => px });
  try {
    await run();
  } finally {
    if (original) Object.defineProperty(Element.prototype, 'clientWidth', original);
    else delete Element.prototype.clientWidth;
  }
}

let originalGetContext;
beforeEach(() => {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  useIsMobile.mockReturnValue(false);
});
afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  vi.clearAllMocks();
  cleanup();
});

const cardButton = () => screen.getByTestId('wf-heat-card');

describe('B1: a shaped card is not "not scored" because the server stripped the best star', () => {
  it('reads as scored when an allowed place is rated, though the served window best is null', async () => {
    const props = shapedProps();
    expect(props.cards[0].bestRating).toBeNull();

    await renderStrip(props);

    expect(cardButton()).not.toHaveAttribute('data-unscored');
    expect(cardButton().getAttribute('aria-labelledby')).toBeTruthy();
    expect(document.getElementById(cardButton().getAttribute('aria-labelledby'))).not.toHaveTextContent(/not scored/i);
  });

  it('shows the thin "· N rated" count again, over the reader\'s places only', async () => {
    await renderStrip(shapedProps());

    // One of two places is rated: the thin line says 1, never counting the two outside.
    expect(screen.getByTestId('wf-heat-thin')).toHaveTextContent('· 1 rated');
  });

  it('is not scored only when the reader holds places in the window and none is rated', async () => {
    await renderStrip(shapedProps({ rated: false }));

    expect(cardButton()).toHaveAttribute('data-unscored', 'true');
    expect(document.getElementById(cardButton().getAttribute('aria-labelledby'))).toHaveTextContent(/not scored/i);
    expect(screen.queryByTestId('wf-heat-thin')).toBeNull();
  });

  it('says nothing about scoring before the scores have loaded', async () => {
    await renderStrip(shapedProps({ rated: false, scoresLoaded: false }));

    expect(cardButton()).not.toHaveAttribute('data-unscored');
  });

  it('never says "not scored" to a reader with no pick: nothing is theirs to score', async () => {
    await renderStrip(shapedProps({ allowed: [], rated: false }));

    expect(cardButton()).not.toHaveAttribute('data-unscored');
    expect(document.getElementById(cardButton().getAttribute('aria-labelledby'))).not.toHaveTextContent(/not scored|rated/i);
  });

  it('keeps the served best deciding for every other reader', async () => {
    const props = shapedProps();
    await renderStrip({
      cards: [{ ...props.cards[0], bestRating: null, picksScoped: undefined, outsideCount: undefined }],
      spots: props.spots,
      pointSets: props.pointSets,
    });

    expect(cardButton()).toHaveAttribute('data-unscored', 'true');
  });
});

describe('B2: a place outside the picks is in neither half of any count', () => {
  it('counts the Spread, the pool phrase and the thin line over the places the reader holds', async () => {
    await renderStrip(shapedProps());

    const name = document.getElementById(cardButton().getAttribute('aria-labelledby')).textContent;
    expect(name).toContain('2 locations, in your sub-regions');
    expect(name).not.toMatch(/4 locations/);
    // Below the minimum sample the row is the plain "1/2 rated", over two places and never four.
    expect(screen.getByTestId('wf-heat-spread-note')).toHaveTextContent('1/2 rated');
  });

  it('labels the Spread row (your sub-regions), not the drive tier it is pinned to', async () => {
    await renderStrip(shapedProps());

    expect(screen.getByTestId('wf-heat-spread-label')).toHaveTextContent('Spread(your sub-regions)');
    expect(screen.getByTestId('wf-heat-spread-qualifier')).toHaveTextContent('(your sub-regions)');
    expect(screen.getByTestId('wf-heat-spread-label')).not.toHaveTextContent(/any drive/);
  });

  it('names the best of the reader\'s own places with its own star', async () => {
    await renderStrip(shapedProps());

    expect(screen.getByTestId('wf-heat-best-rating')).toHaveTextContent('4★');
    expect(screen.getByTestId('wf-heat-best')).toHaveTextContent('Bamburgh');
  });

  it('says none of the sub-regions is rated, in the row and in the sentence, when places exist but none is', async () => {
    await renderStrip(shapedProps({ rated: false, summaryOverrides: { mine: [slot(1, 'Bamburgh', null), slot(7, 'Craster', null)] } }));

    expect(screen.getByTestId('wf-heat-best'))
      .toHaveTextContent('None of your sub-regions are rated for this sunset yet.');
    expect(screen.getByTestId('wf-heat-best')).not.toHaveTextContent(/in reach|not scored yet|nothing to show/);
  });

  it('omits the Spread and Best rows for a reader with no pick, and the thin line', async () => {
    await renderStrip(shapedProps({ allowed: [], summaryOverrides: { mine: [] } }));

    expect(screen.queryByTestId('wf-heat-spread-row')).toBeNull();
    expect(screen.queryByTestId('wf-heat-best')).toBeNull();
    expect(screen.queryByTestId('wf-heat-best-rating')).toBeNull();
    expect(screen.queryByTestId('wf-heat-thin')).toBeNull();
    const name = document.getElementById(cardButton().getAttribute('aria-labelledby')).textContent;
    expect(name).not.toMatch(/locations|rated|nothing in reach|nothing to show|not scored/i);
  });
});

describe('the thumbnails', () => {
  const lastOpts = () => drawGeo.mock.calls.at(-1)[5];

  it('are handed the window\'s two grounds and a 1px outline for a shaped body', async () => {
    const props = shapedProps();
    await withMeasuredThumbs(160, async () => { await renderStrip(props); });

    expect(lastOpts().outside.map((p) => p.id)).toEqual([2, 3]);
    expect(lastOpts().allowed.map((p) => p.id)).toEqual([1, 7]);
    expect(lastOpts().outlineWidth).toBe(1);
  });

  it('are handed no ground at all for a Pro, Admin or flag-off body', async () => {
    const props = shapedProps();
    await withMeasuredThumbs(160, async () => {
      await renderStrip({ ...props, picks: null, groundSets: new Map() });
    });

    expect(lastOpts()).not.toHaveProperty('outside');
    expect(lastOpts()).not.toHaveProperty('allowed');
    expect(lastOpts()).not.toHaveProperty('outlineWidth');
  });

  it('are handed no ground when the body is not shaped, even if a stale set is still in hand', async () => {
    const props = shapedProps();
    await withMeasuredThumbs(160, async () => { await renderStrip({ ...props, picks: null }); });

    expect(lastOpts()).not.toHaveProperty('outside');
  });

  it('cover all the land and draw no outline for a reader with no pick (every place is outside)', async () => {
    const props = shapedProps({ allowed: [], summaryOverrides: { mine: [] } });
    await withMeasuredThumbs(160, async () => { await renderStrip(props); });

    expect(lastOpts().outside).toHaveLength(4);
    expect(lastOpts().allowed).toEqual([]);
    expect(lastOpts().hatch).toBe(false);
  });

  it('never hatch the whole plate of a shaped window; an unscored one hatches around the reader\'s own unrated places', async () => {
    const props = shapedProps({ rated: false });
    await withMeasuredThumbs(160, async () => { await renderStrip(props); });

    expect(lastOpts().hatch).toBe(false);
    expect(lastOpts().unrated.map((p) => p.id)).toEqual([1, 7]);
  });

  it('keep the whole-plate hatch for an unscored window of an unshaped body', async () => {
    const props = shapedProps({ rated: false });
    const open = props.cards.map((card) => ({ ...card, picksScoped: undefined, bestRating: null }));
    await withMeasuredThumbs(160, async () => {
      await renderStrip({ ...props, cards: open, picks: null, groundSets: new Map() });
    });

    expect(lastOpts().hatch).toBe(true);
  });
});

describe('the legend line under the strip', () => {
  it('names outside your sub-regions with a Pro pill (a label, not a door), and your sub-regions', async () => {
    await renderStrip(shapedProps());

    const outside = screen.getByTestId('wf-heat-key-outside');
    expect(outside).toHaveTextContent('outside your sub-regions');
    expect(within(outside).getByText('Pro')).toBeInTheDocument();
    expect(within(outside).queryByRole('button')).toBeNull();
    expect(within(outside).queryByRole('link')).toBeNull();
    expect(screen.getByTestId('wf-heat-key-picks')).toHaveTextContent('your sub-regions');
  });

  it('prints the shared note, never a restatement of it', async () => {
    const props = shapedProps();
    await renderStrip(props);

    expect(screen.getByTestId('wf-heat-picks-note')).toHaveTextContent(picksNoteText(props.picks));
    expect(screen.getByTestId('wf-heat-picks-note'))
      .toHaveTextContent('Colour shows the 2 places in your sub-regions. Pro rates all 4.');
  });

  it('puts the swatches and the note in one group that takes its own line on a phone', () => {
    const css = readFileSync(resolve(__dirname, '../index.css'), 'utf8');

    expect(css).toMatch(/@media \(max-width: 639px\)\s*\{\s*\.wf-hstrip-picks \{ flex: 0 0 100%; \}/);
    expect(css).toMatch(/\.wf-hstrip-picks-note \{[^}]*overflow-wrap: anywhere/);
  });

  it('for a reader with no pick: the Choose note, one Choose button, and no outline swatch', async () => {
    getSubRegions.mockResolvedValue(FIRST_CHOICE);
    await renderStrip(shapedProps({ allowed: [], summaryOverrides: { mine: [] } }), { enabled: true });

    expect(screen.getByTestId('wf-heat-picks-note'))
      .toHaveTextContent('Choose your sub-regions in Settings to see ratings.');
    expect(screen.queryByTestId('wf-heat-key-picks')).toBeNull();
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Choose your sub-regions' })).toHaveLength(1));
    expect(screen.getByTestId('wf-heat-picks-action')).toBeInTheDocument();
  });

  it('offers no Choose button to a reader who holds picks', async () => {
    getSubRegions.mockResolvedValue(FIRST_CHOICE);
    await renderStrip(shapedProps(), { enabled: true });

    await Promise.resolve();
    expect(screen.queryByTestId('wf-heat-picks-action')).toBeNull();
  });

  it('is absent for every Pro, Admin and flag-off reader', async () => {
    const props = shapedProps();
    await renderStrip({ ...props, picks: null, groundSets: new Map() });

    expect(screen.queryByTestId('wf-heat-picks')).toBeNull();
    expect(screen.queryByTestId('wf-heat-key-outside')).toBeNull();
    expect(screen.queryByText(/sub-regions/)).toBeNull();
  });

  it('keeps the best-bets door as the surface\'s one door', async () => {
    await renderStrip({ ...shapedProps(), picksLocked: true });

    expect(screen.getAllByTestId('wf-heat-bestbets-upsell-trigger')).toHaveLength(1);
    expect(within(screen.getByTestId('wf-heat-picks')).queryByRole('button')).toBeNull();
  });
});
