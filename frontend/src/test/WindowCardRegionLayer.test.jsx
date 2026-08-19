import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, render, screen, fireEvent, within } from '@testing-library/react';
import WindowFirstWindowCard from '../components/WindowFirstWindowCard.jsx';

/**
 * The open row's region layer, mounted on the card that owns it.
 *
 * <p>The exit criterion this phase is held to is that the tide row and the spot strip did not move,
 * and their own files prove that by passing unedited. What this file proves is the other half: the
 * three new pieces are ABOVE them, they compose their filter onto the lens rather than replacing it,
 * and a card handed no {@code field} renders exactly what it rendered before the layer existed.
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

const TODAY = '2026-08-04';
const KEY = '2026-08-04:SUNSET';

function cardSpot(name, regionName, rating, driveMinutes) {
  return {
    key: name, name, locationName: name, regionName, rating, driveMinutes,
  };
}

const COAST_SPOTS = [
  cardSpot('Bamburgh', 'Coast', 5, 40),
  cardSpot('Whitburn', 'Coast', 4, 30),
];
const DALES_SPOTS = [cardSpot('Malham', 'Dales', 4, 80)];
const ALL_SPOTS = [...COAST_SPOTS, ...DALES_SPOTS];

const EVENT_SUMMARY = {
  targetType: 'SUNSET',
  regions: [
    {
      regionName: 'Coast',
      displayVerdict: 'WORTH_IT',
      summary: 'A clean eastern horizon.',
      meanRating: 4.5,
      bestRating: 5,
      slots: [{ canopy: false }],
    },
    {
      regionName: 'Dales',
      displayVerdict: 'MAYBE',
      summary: null,
      meanRating: 3.1,
      bestRating: 4,
      slots: [{ canopy: false }],
    },
  ],
};

function card(overrides = {}) {
  return {
    key: KEY,
    date: TODAY,
    targetType: 'SUNSET',
    lead: false,
    kicker: null,
    when: 'Tonight Sunset',
    time: '21:11',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    // Deliberately NOT Coast's own 5: the All cell states the WINDOW's number and each region cell
    // states its own, and equal fixtures let one be swapped for the other undetected (P1's two
    // levels of "best").
    bestRating: 4,
    confidence: 'high',
    badges: [],
    allBadges: [],
    pick: null,
    spots: ALL_SPOTS,
    allSpots: ALL_SPOTS,
    reachTotal: ALL_SPOTS.length,
    reachedTotal: ALL_SPOTS.length,
    withinReachCount: null,
    lensEmpty: null,
    rows: [],
    ...overrides,
  };
}

const WINDOWS = [
  { key: KEY, dow: 'Tue', sunrise: false, label: 'Tonight Sunset', time: '21:11' },
  { key: '2026-08-05:SUNRISE', dow: 'Wed', sunrise: true, label: 'Tomorrow sunrise', time: '05:20' },
];

function field(overrides = {}) {
  return {
    eventSummary: EVENT_SUMMARY,
    spots: [
      { lat: 6, lng: 4, regionName: 'Coast', rid: 'Coast' },
      { lat: 6, lng: 24, regionName: 'Dales', rid: 'Dales' },
    ],
    points: [],
    windows: WINDOWS,
    series: new Map([['Coast', new Map([[KEY, 4.5]])]]),
    selectedRegion: null,
    lens: { limitMinutes: null, tierLabel: 'Any', minRating: null, ratingLabel: 'Any rating' },
    onSelectRegion: vi.fn(),
    onJumpWindow: vi.fn(),
    ...overrides,
  };
}

let originalGetContext;
beforeEach(() => {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
});
afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  vi.clearAllMocks();
});

async function renderCard(props = {}) {
  // The layer is behind a `lazy()` boundary the card owns (a scope-guard decision — see the card's
  // own note), so its chunk has to be resolved before any assertion about it. Preloaded here rather
  // than left to a `findBy*` in each test: only the FIRST render in the file pays for the import,
  // and a test that forgot the wait would otherwise fail intermittently under parallel load rather
  // than deterministically.
  if (props.field) await import('../components/WindowRowRegionLayer.jsx');
  await act(async () => {
    render(
      <WindowFirstWindowCard
        card={card()}
        todayStr={TODAY}
        open
        {...props}
      />,
    );
  });
}

describe('WindowFirstWindowCard — without a field', () => {
  it('renders no map, no rail and no band, exactly as it did before the layer existed', async () => {
    await renderCard();
    expect(screen.queryByTestId('wf-row-map')).toBeNull();
    expect(screen.queryByTestId('wf-region-rail')).toBeNull();
    expect(screen.queryByTestId('wf-region-band')).toBeNull();
  });

  it('still draws its spot strip', async () => {
    await renderCard();
    expect(screen.getByTestId('window-spot-strip')).toBeInTheDocument();
  });
});

describe('WindowFirstWindowCard — where the region layer sits', () => {
  it('puts the map, then the rail, then the tide row, then the strip', async () => {
    // The exit criterion: the rows and the strip did not move, they were preceded.
    await renderCard({
      card: card({ rows: [{ key: 'tide', channel: 'tide', facts: [] }] }),
      field: field(),
    });
    // Asserted with `compareDocumentPosition` rather than by walking the DOM: the standards forbid
    // `querySelector` in a test, and the question here is genuinely "which comes first", which is
    // exactly what that API answers.
    const inOrder = ['wf-row-map', 'wf-region-rail', 'window-card-rows', 'window-spot-strip']
      .map((id) => screen.getByTestId(id));
    for (let i = 0; i < inOrder.length - 1; i += 1) {
      const follows = inOrder[i].compareDocumentPosition(inOrder[i + 1])
        & Node.DOCUMENT_POSITION_FOLLOWING;
      expect(follows).toBeTruthy();
    }
  });

  it('renders nothing of the layer while the card is collapsed', async () => {
    // Canvas drawing is lazy by construction — a collapsed card must not mount one.
    await renderCard({ open: false, field: field() });
    expect(screen.queryByTestId('wf-row-map')).toBeNull();
    expect(screen.queryByTestId('wf-region-rail')).toBeNull();
  });
});

describe('WindowFirstWindowCard — the rail’s figures', () => {
  it('ranks the regions by their served mean, best first', async () => {
    await renderCard({ field: field() });
    const cells = screen.getAllByTestId('wf-region-cell');
    expect(cells.map((c) => c.getAttribute('data-region'))).toEqual(['all', 'Coast', 'Dales']);
  });

  it('prints each region’s SERVED best, never a maximum of the spots drawn there', async () => {
    // Dales draws one spot rated 4 and its served best is 4; Coast draws a 5 and a 4 and its served
    // best is 5. The tell is the payload: change `bestRating` and the cell must follow.
    await renderCard({
      field: field({
        eventSummary: {
          ...EVENT_SUMMARY,
          regions: [
            { ...EVENT_SUMMARY.regions[0], bestRating: 2 },
            EVENT_SUMMARY.regions[1],
          ],
        },
      }),
    });
    expect(screen.getAllByTestId('wf-region-cell')[1]).toHaveTextContent('best 2★');
  });

  it('prints the WINDOW’s served best on the All cell, which is the header’s own number', async () => {
    await renderCard({ field: field() });
    expect(screen.getAllByTestId('wf-region-cell')[0]).toHaveTextContent('best 4★');
    expect(screen.getByTestId('window-card-best')).toHaveTextContent('best spot 4★');
    // And the rank-1 region prints its OWN 5, one cell along. Two levels, two labelled cells.
    expect(screen.getAllByTestId('wf-region-cell')[1]).toHaveTextContent('best 5★');
  });
});

describe('WindowFirstWindowCard — the region filter', () => {
  it('narrows the spot strip to the selected region', async () => {
    await renderCard({ field: field({ selectedRegion: 'Coast' }) });
    const strip = screen.getByTestId('window-spot-scroller');
    expect(within(strip).getByText('Bamburgh')).toBeInTheDocument();
    expect(within(strip).queryByText('Malham')).toBeNull();
  });

  it('composes onto the lens rather than replacing it, so both gates still hold', async () => {
    // `card.spots` arrives already reach- and rating-gated; the region runs last, on that array.
    await renderCard({
      card: card({ spots: [COAST_SPOTS[0], DALES_SPOTS[0]], allSpots: ALL_SPOTS }),
      field: field({ selectedRegion: 'Coast' }),
    });
    const strip = screen.getByTestId('window-spot-scroller');
    expect(within(strip).getByText('Bamburgh')).toBeInTheDocument();
    // Whitburn was removed by the LENS upstream and the region filter cannot bring it back.
    expect(within(strip).queryByText('Whitburn')).toBeNull();
  });

  it('shows every spot again when no region is selected', async () => {
    await renderCard({ field: field({ selectedRegion: null }) });
    const strip = screen.getByTestId('window-spot-scroller');
    expect(within(strip).getByText('Malham')).toBeInTheDocument();
  });

  it('states the region emptied it, rather than leaving the row blank', async () => {
    // A blank space is indistinguishable from a card that never had any spots. This line names the
    // region, says how many are elsewhere, and carries the way back.
    await renderCard({ field: field({ selectedRegion: 'Nowhere' }) });
    expect(screen.queryByTestId('window-spot-strip')).toBeNull();
    expect(screen.getByTestId('window-card-region-empty-head'))
      .toHaveTextContent('Nothing in Nowhere for this window.');
    expect(screen.getByTestId('window-card-region-empty-body'))
      .toHaveTextContent('3 spots are here in other regions.');
  });

  it('names the other filters in force in that line, but never the region twice', async () => {
    await renderCard({
      field: field({
        selectedRegion: 'Nowhere',
        lens: { limitMinutes: 150, tierLabel: '2h 30min', minRating: 4, ratingLabel: '4★+' },
      }),
    });
    expect(screen.getByTestId('window-card-region-empty-body'))
      .toHaveTextContent('3 spots are here under 4★+ · within 2h 30min, in other regions.');
  });

  it('adds no control of its own, because the rail’s All cell is already on screen', async () => {
    // A third button saying the same words in the same row is the "four affordances for one
    // intention" the pre-pilot sweep convicts — and unlike the lens's empty state, the controls that
    // undo this one cannot have been scrolled away.
    await renderCard({ field: field({ selectedRegion: 'Nowhere' }) });
    const empty = screen.getByTestId('window-card-region-empty');
    expect(within(empty).queryAllByRole('button')).toHaveLength(0);
    // The route is real and it is right there.
    expect(screen.getAllByTestId('wf-region-cell')[0]).toHaveAttribute('data-region', 'all');
  });

  it('leaves the rail’s All cell as the live route back out of an emptied region', async () => {
    const onSelectRegion = vi.fn();
    await renderCard({ field: field({ selectedRegion: 'Nowhere', onSelectRegion }) });
    fireEvent.click(screen.getAllByTestId('wf-region-cell')[0]);
    expect(onSelectRegion).toHaveBeenCalledWith(null);
  });

  it('renders no region-empty line on a card that had no spots to begin with', async () => {
    // The region touched nothing there, so a line about it would be a statement about a control
    // that did not act — the same rule `buildLensEmptyState` applies one state along.
    await renderCard({
      card: card({ spots: [], allSpots: [] }),
      field: field({ selectedRegion: 'Coast' }),
    });
    expect(screen.queryByTestId('window-card-region-empty')).toBeNull();
  });

  it('does not offer to loosen the LENS on a card the REGION emptied', async () => {
    // The empty-state buttons widen the tier and the floor. Offering them where a region selection
    // removed the spots would point the reader at two controls that are not what did it.
    await renderCard({
      card: card({
        lensEmpty: {
          headline: 'Nothing here', body: 'Widen the lens.', actions: [],
        },
      }),
      field: field({ selectedRegion: 'Nowhere' }),
    });
    expect(screen.queryByTestId('window-card-lens-empty')).toBeNull();
  });
});

describe('WindowFirstWindowCard — the band', () => {
  it('appears only once a region is selected', async () => {
    await renderCard({ field: field({ selectedRegion: null }) });
    expect(screen.queryByTestId('wf-region-band')).toBeNull();
  });

  it('renders the selected region’s served summary verbatim', async () => {
    await renderCard({ field: field({ selectedRegion: 'Coast' }) });
    expect(screen.getByTestId('wf-region-band-narrative'))
      .toHaveTextContent('A clean eastern horizon.');
  });

  it('renders the design’s line for a region the payload gave no summary', async () => {
    await renderCard({ field: field({ selectedRegion: 'Dales' }) });
    expect(screen.getByTestId('wf-region-band-none'))
      .toHaveTextContent('No narrative generated for this window.');
  });

  it('renders no lens figures while neither axis gates', async () => {
    await renderCard({ field: field({ selectedRegion: 'Coast' }) });
    const figs = screen.getAllByTestId('wf-region-band-fig');
    expect(figs.map((f) => f.getAttribute('data-fig'))).toEqual(['best']);
  });

  it('renders both lens figures on their own populations once both gate', async () => {
    // Coast holds two spots, both rated 4 or better, and one is beyond a 45-minute tier.
    await renderCard({
      card: card({ spots: [COAST_SPOTS[1]], allSpots: ALL_SPOTS }),
      field: field({
        selectedRegion: 'Coast',
        lens: { limitMinutes: 45, tierLabel: '45 min', minRating: 4, ratingLabel: '4★+' },
      }),
    });
    expect(screen.getByText('at 4★+').parentElement).toHaveTextContent('2 of 2');
    expect(screen.getByText('within 45 min').parentElement).toHaveTextContent('1');
  });
});

describe('WindowFirstWindowCard — the band’s six dots at the layer seam', () => {
  it('reads this region’s own series, which is a map INSIDE the series map', async () => {
    // `series` is `Map<region, Map<windowKey, mean>>`. Handing the band the outer map instead makes
    // every lookup miss, so every dot silently reads "not scored", every swatch loses its colour and
    // the ◎ disappears — with nothing failing.
    await renderCard({ field: field({ selectedRegion: 'Coast' }) });
    expect(screen.getByRole('button', { name: 'Tue sunset, Coast 4.5★' })).toBeInTheDocument();
  });

  it('jumps to the window a dot names, through the shell’s own reveal', async () => {
    const onJumpWindow = vi.fn();
    await renderCard({ field: field({ selectedRegion: 'Coast', onJumpWindow }) });
    fireEvent.click(screen.getAllByTestId('wf-region-band-dot')[1]);
    expect(onJumpWindow).toHaveBeenCalledWith('2026-08-05:SUNRISE');
  });

  it('clears the region from the band and puts focus on the All cell it did not destroy', async () => {
    // The clear control unmounts itself; without the focus move, focus falls to <body> and the next
    // Tab restarts at the top of the document (WCAG 2.4.3).
    const onSelectRegion = vi.fn();
    await renderCard({ field: field({ selectedRegion: 'Coast', onSelectRegion }) });
    fireEvent.click(screen.getByTestId('wf-region-band-clear'));
    expect(onSelectRegion).toHaveBeenCalledWith(null);
    expect(document.activeElement).toBe(screen.getAllByTestId('wf-region-cell')[0]);
  });
});

describe('WindowFirstWindowCard — the footer names the filters in force', () => {
  it('names all three when all three gate', async () => {
    await renderCard({
      field: field({
        selectedRegion: 'Coast',
        lens: { limitMinutes: 150, tierLabel: '2h 30min', minRating: 4, ratingLabel: '4★+' },
      }),
    });
    expect(screen.getByTestId('window-spot-filters'))
      .toHaveTextContent('· Coast · 4★+ · within 2h 30min');
  });

  it('names only the region when the lens gates nothing', async () => {
    await renderCard({ field: field({ selectedRegion: 'Coast' }) });
    expect(screen.getByTestId('window-spot-filters')).toHaveTextContent('· Coast');
  });

  it('names nothing at all when no filter is in force', async () => {
    await renderCard({ field: field({ selectedRegion: null }) });
    expect(screen.queryByTestId('window-spot-filters')).toBeNull();
  });
});
