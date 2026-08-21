import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, cleanup, render, screen, fireEvent, within } from '@testing-library/react';
import WindowFirstHeatStrip, {
  THUMB_ASPECT_MAX, THUMB_ASPECT_MIN, thumbAspect,
} from '../components/WindowFirstHeatStrip.jsx';
import { aspect, BBOX, bbox, drawGeo, land, load } from '../utils/heatField.js';
import { POINT_SCORE_INDEX } from '../utils/heatSpots.js';
import { GLANCE_MINUTES } from '../utils/planningArea.js';
import { RAMP_STOPS } from '../utils/scoreRamp.js';

/**
 * The Plan pane's heat strip — six solar-window thumbnails, replacing the day rail (plan D1, §1.1).
 *
 * <h2>What this file can and cannot see</h2>
 *
 * <p>jsdom implements no layout and no canvas: every element measures 0, and
 * {@code getContext('2d')} returns null. So the FIELD is not asserted here at all — the kernel's own
 * arithmetic is pinned in {@code heatField.test.js} through a stub context, and what the six
 * canvases actually paint is browser-verified (§7.3 of the plan). A test claiming otherwise would be
 * the shape of test this project keeps finding and deleting.
 *
 * <p>What IS asserted is everything the strip is for besides the picture: which windows get a
 * thumbnail and in what order, the words each one carries, the accessible name they compose, which
 * cells are controls and which are not, and the two lines beneath. The canvas stub below exists so
 * the paint effect can run without throwing, not so it can be inspected.
 *
 * <p>{@code load()} is mocked at the kernel boundary rather than left to fetch the vendored
 * topology: it is a dynamic import of a 9 KB asset that every test here would pay for, and its own
 * behaviour (the shared in-flight promise, the rejection that does not latch) is pinned in
 * {@code heatField.test.js}.
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

const TODAY = '2026-08-04';

/** A pool entry, as `buildWindowSpots` shapes the fields the card face reads. */
function poolSpot(overrides = {}) {
  return {
    key: '1',
    locationId: 1,
    locationName: 'Bamburgh Beach',
    regionName: 'Northumberland & Tyneside',
    rating: 4,
    driveMinutes: 40,
    solarEventTime: `${TODAY}T20:11:00`,
    ...overrides,
  };
}

/**
 * A thumbnail descriptor as `buildHeatStripCards` shapes it.
 *
 * <p>The default pool is EMPTY, which is the state a card with no reach data lands in and is what
 * every block not about the pool should describe. Blocks that are about it hand their own.
 */
function stripCard(overrides = {}) {
  return {
    key: `${TODAY}:SUNSET`,
    date: TODAY,
    targetType: 'SUNSET',
    dow: 'Tue',
    sunrise: false,
    label: 'Tonight Sunset',
    time: '21:11',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    // Rated, because that is what all but one block below is describing. `null` is the payload's
    // "nothing in this window is rated" and is what draws the mark.
    bestRating: 4,
    pickKind: null,
    away: false,
    confidence: 'high',
    pool: [],
    // ⚠️ TRUE by default, which is the ordinary reader: a saved home postcode and drive times, so
    // the reach tier really does gate. It decides whether this card's empty-pool words may say "in
    // reach" at all — `buildWindowCards` computes it and `buildHeatStripCards` folds it on, and a
    // fixture that omitted it would put every card on the no-postcode wording and quietly change
    // what half this file is asserting. The false case has its own block.
    reachMeasured: true,
    bestReach: null,
    badges: [],
    ...overrides,
  };
}

/** A card whose pool holds one 4★ spot 40 minutes away — the ordinary "something in reach" case. */
function ratedCard(overrides = {}) {
  const pool = overrides.pool ?? [poolSpot()];
  return stripCard({ ...overrides, pool, bestReach: overrides.bestReach ?? pool[0] ?? null });
}

/** One joined location, which is all the strip needs to decide it has something to draw. */
function spot(overrides = {}) {
  return {
    id: 1,
    name: 'Bamburgh Beach',
    lat: 55.61,
    lng: -1.71,
    regionName: 'Northumberland & Tyneside',
    rid: 'Northumberland & Tyneside',
    skySubject: true,
    bortleClass: 3,
    scores: [4],
    ...overrides,
  };
}

/**
 * One point per non-away window — the ORDINARY case, and the default fixture for that reason.
 *
 * <p>The default used to be an empty {@code Map}, which every assertion survived because nothing
 * read it but the mocked {@code drawGeo}. It no longer decides the unscored mark (that is
 * {@code bestRating}), but a strip whose every window has an empty field is still not the state
 * these tests describe. Built FROM the cards being rendered, so a test that passes its own set
 * gets points for its own windows rather than for a key it never used.
 *
 * @param {Array} cards the thumbnail descriptors being rendered
 * @returns {Map} window key → one kernel point
 */
function scoredWindows(cards) {
  return new Map(cards
    .filter((card) => !card.away)
    .map((card) => [card.key, [{
      id: 1, name: 'Bamburgh Beach', lat: 55.61, lng: -1.71, rid: 'Northumberland & Tyneside', r: [4],
    }]]));
}

/**
 * Renders and lets the geometry promise settle.
 *
 * <p>`await act` rather than a bare render: the strip resolves {@link load} on mount and bumps a
 * nonce when it lands, which is a state update React warns about if it happens outside an act
 * scope. Flushing it here also means every assertion below runs against the state the strip
 * settles into rather than its first frame.
 */
async function renderStrip(props = {}) {
  const onOpenWindow = vi.fn();
  const cards = props.cards ?? [stripCard()];
  await act(async () => {
    render(
      <WindowFirstHeatStrip
        spots={[spot()]}
        todayStr={TODAY}
        onOpenWindow={onOpenWindow}
        {...props}
        cards={cards}
        pointSets={props.pointSets ?? scoredWindows(cards)}
      />,
    );
  });
  return { onOpenWindow };
}

/**
 * jsdom's `getContext('2d')` returns null, and the strip now reads a null context as "this browser
 * cannot give us a canvas" — the guard P0 left to whoever mounts one — and answers by dropping the
 * canvases entirely. So the stub returns an OBJECT, the smallest thing that is not null: `drawGeo`
 * is mocked and nothing here paints into it. The null case is a named test of its own below.
 */
let originalGetContext;
beforeEach(() => {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
});
afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  vi.clearAllMocks();
});

/**
 * Gives every element a measurable content box, which jsdom otherwise reports as 0.
 *
 * <p>Without it the strip's measurement gate declines and nothing paints — which is the correct
 * behaviour under jsdom and is asserted in its own test, but it makes every assertion about the
 * CALL unreachable. The stub is per test and restored after, so nothing else in the file sees a
 * fake layout.
 *
 * <p><b>What it does NOT prove.</b> The property is defined on {@code Element.prototype}, so every
 * element answers the same number — the button, the grid and the well alike. The component's choice
 * to measure the unpadded well rather than the padded button (which would need the prototype's
 * `- 12` constant subtracted) is therefore invisible here, and is a browser claim.
 */
async function withMeasuredThumbs(px, run) {
  const original = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');
  Object.defineProperty(Element.prototype, 'clientWidth', { configurable: true, get: () => px });
  try {
    // AWAITED inside the try, not returned from it: `return run()` hands the promise back and the
    // `finally` restores the real descriptor before the render has measured anything, so every
    // assertion reads jsdom's 0 and the calls never happen.
    await run();
  } finally {
    if (original) Object.defineProperty(Element.prototype, 'clientWidth', original);
    else delete Element.prototype.clientWidth;
  }
}

/**
 * Gives each canvas well its OWN measurable width, in render order, while every other element
 * answers the first of them.
 *
 * <p>The discriminating half of {@link withMeasuredThumbs}. That stub defines one getter on
 * `Element.prototype`, so the grid, the button and every well answer the same number and a paint
 * that measured once and spent it on all six would pass — which is exactly the mutation the matrix's
 * per-card measurement exists to prevent. This keys on the well's own test id and hands out the
 * supplied widths in the order the wells are read.
 *
 * @param {number[]} widths one width per well, in DOM order
 * @param {Function} run    the render to make under the stub
 */
async function withWellWidths(widths, run) {
  const original = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth');
  const seen = new Map();
  Object.defineProperty(Element.prototype, 'clientWidth', {
    configurable: true,
    get() {
      if (this.dataset?.testid !== 'wf-heat-well') return widths[0];
      if (!seen.has(this)) seen.set(this, seen.size);
      return widths[seen.get(this)] ?? widths[widths.length - 1];
    },
  });
  try {
    // AWAITED inside the try — see `withMeasuredThumbs` for why returning the promise restores the
    // real descriptor before anything has measured.
    await run();
  } finally {
    if (original) Object.defineProperty(Element.prototype, 'clientWidth', original);
    else delete Element.prototype.clientWidth;
  }
}

describe('WindowFirstHeatStrip — which windows it draws', () => {
  it('⚠️ heads the matrix with a real level-2 HEADING, not a styled span', async () => {
    // It looks like a section heading — 9.5px mono, 600, letter-spaced, uppercase, beside a
    // full-width hairline — and it was a `<span>`, which left the whole v2 Plan tab with exactly one
    // heading (the masthead wordmark's `h1`): a browse-mode reader pressing `H` went from the
    // wordmark to the end of the page. SC 1.3.1. Asserted through the ROLE with its level, which is
    // the only query that can see the difference; axe could not, because nothing was out of order.
    await renderStrip({ cards: [stripCard()] });
    expect(screen.getByRole('heading', { level: 2, name: 'The days ahead' })).toBeInTheDocument();
  });

  it('draws one thumbnail per card, in the order it was handed them', async () => {
    // The strip's whole claim is that it is a time axis, so the order is the payload's and nothing
    // here may sort. (Through M1 an Order control could rank the card list below; that control and
    // that list both went at M2, so time is the only order this pane has.)
    await renderStrip({
      cards: [
        stripCard(),
        stripCard({ key: '2026-08-05:SUNRISE', date: '2026-08-05', dow: 'Wed', sunrise: true, label: 'Tomorrow sunrise', time: '05:20' }),
      ],
    });

    const names = screen.getAllByTestId('wf-heat-card').map((c) => c.textContent);
    expect(names[0]).toContain('Tonight Sunset');
    expect(names[1]).toContain('Tomorrow sunrise');
  });

  it('withdraws entirely when there is no catalogue to draw', async () => {
    // §7.2's named degrade: a scores fetch that failed, or a session with no roster. Six empty
    // coastlines under a header claiming to summarise them would be worse than nothing, and the
    // window rows below are unaffected either way.
    await act(async () => {
      render(<WindowFirstHeatStrip cards={[stripCard()]} spots={[]} todayStr={TODAY} />);
    });
    expect(screen.queryByTestId('wf-heat-strip')).toBeNull();
  });

  it('withdraws entirely when there are no windows', async () => {
    await act(async () => {
      render(<WindowFirstHeatStrip cards={[]} spots={[spot()]} todayStr={TODAY} />);
    });
    expect(screen.queryByTestId('wf-heat-strip')).toBeNull();
  });
});

describe('WindowFirstHeatStrip — what a card says', () => {
  it('names its window, its time and its verdict in the accessible name', async () => {
    // Composed from REAL TEXT rather than an aria-label, which would REPLACE name-from-contents and
    // drop the verdict — the defect that cost a review round when the heatmap cells were named. The
    // day heading and the sun word are aria-hidden because the hidden phrase says the same thing in
    // words. With an empty pool the best-reach clause is the design's own "nothing in reach".
    await renderStrip();
    expect(screen.getByRole('button', { name: 'Tonight Sunset, 21:11, Worth it, best, nothing in reach' }))
      .toBeInTheDocument();
  });

  it('counts the pool and names the best-reach spot in the accessible name', async () => {
    // Plan §3 rule 15: the sentence counts what is actually RENDERED. The histogram draws the pool
    // and the row beneath it names its head, so both are in the name — the pool count verbatim, and
    // the spot's stars spelled out because ★ is an icon rather than a word.
    await renderStrip({
      cards: [ratedCard({ pool: [poolSpot(), poolSpot({ locationId: 2, locationName: 'Blyth', rating: 2 })] })],
    });
    expect(screen.getByRole('button', {
      name: 'Tonight Sunset, 21:11, Worth it, 2 locations within reach, best Bamburgh Beach, 4 stars, Northumberland & Tyneside, 40 min, leave 20:11',
    })).toBeInTheDocument();
  });

  it('drops the words "within reach" from the name when a drive in the pool is unknown', async () => {
    await renderStrip({
      cards: [ratedCard({ pool: [poolSpot({ driveMinutes: null })] })],
    });
    expect(screen.getByRole('button', {
      name: 'Tonight Sunset, 21:11, Worth it, 1 location, best Bamburgh Beach, 4 stars, Northumberland & Tyneside',
    })).toBeInTheDocument();
  });

  it('names every topic on the night in the accessible name', async () => {
    await renderStrip({
      cards: [ratedCard({
        badges: [
          { type: 'AURORA', label: 'Aurora', rarityRank: 1 },
          { type: 'KING_TIDE', label: 'King tide', rarityRank: 3 },
        ],
      })],
    });
    expect(screen.getByRole('button', {
      name: 'Tonight Sunset, 21:11, Worth it, 1 location within reach, best Bamburgh Beach, 4 stars, Northumberland & Tyneside, 40 min, leave 20:11, Aurora, King tide',
    })).toBeInTheDocument();
  });

  it('adds "best bet" to the name of the window the forecast picked', async () => {
    await renderStrip({ cards: [stripCard({ pickKind: 'best' })] });
    expect(screen.getByRole('button', {
      name: 'Tonight Sunset, 21:11, Worth it, best, nothing in reach, best bet',
    })).toBeInTheDocument();
  });

  it('adds "also good" for the runner-up, which P2\'s strip had no room to say', async () => {
    await renderStrip({ cards: [stripCard({ pickKind: 'also' })] });
    expect(screen.getByRole('button', {
      name: 'Tonight Sunset, 21:11, Worth it, best, nothing in reach, also good',
    })).toBeInTheDocument();
  });

  it('renders each pick as a passive span in the border, never a nested control', async () => {
    // Interactive content inside a <button> is invalid HTML, and the day rail's own equivalent WAS
    // a button (it opened the pick dialog). The legend states the pick; the dialog stays reachable
    // from the window row's own pick badge, which is where the prose it opens belongs.
    await renderStrip({ cards: [stripCard({ pickKind: 'best' })] });

    // The card IS the button, so the query is for a button INSIDE it — `queryAllByRole` because the
    // assertion is an absence and `getAllByRole` throws before it can be made.
    const card = screen.getByTestId('wf-heat-card');
    expect(within(card).queryAllByRole('button')).toHaveLength(0);
    expect(card.tagName).toBe('BUTTON');

    const legend = screen.getByTestId('wf-heat-legend');
    expect(legend.tagName).toBe('SPAN');
    expect(legend).toHaveTextContent('Best bet');
    expect(legend).toHaveAttribute('data-pick', 'best');
    expect(card).toHaveClass('best');
  });

  /**
   * ⚠️ §6 clause 7 on the card's two empty-pool words.
   *
   * <p>An unknown drive passes every tier (plan §2.5), so a reader with no home postcode has every
   * gate open — an empty pool then means this window has no sky-gated slots at all, and "in reach"
   * blames a control that did nothing. Found by an adversarial review of M5, which had fixed the
   * same claim in the window popup and stopped there. Both the visible line and the histogram's
   * tooltip read the SAME field, so they cannot come apart.
   */
  describe('when no drive time exists for the tier to gate on', () => {
    it('says "nothing to show" rather than "nothing in reach"', async () => {
      await renderStrip({ cards: [stripCard({ pool: [], reachMeasured: false })] });
      expect(screen.getByTestId('wf-heat-best')).toHaveTextContent('nothing to show');
      expect(screen.getByTestId('wf-heat-best')).not.toHaveTextContent('reach');
    });

    it('says it the same way in the histogram tooltip', async () => {
      await renderStrip({ cards: [stripCard({ pool: [], reachMeasured: false })] });
      expect(screen.getByTestId('wf-heat-spread'))
        .toHaveAttribute('title', 'Nothing to show for this window.');
    });

    it('still says "in reach" where a drive time exists, which is the ordinary case', async () => {
      // The control, so neither branch can be deleted by making the other unconditional.
      await renderStrip({ cards: [stripCard({ pool: [], reachMeasured: true })] });
      expect(screen.getByTestId('wf-heat-best')).toHaveTextContent('nothing in reach');
      expect(screen.getByTestId('wf-heat-spread'))
        .toHaveAttribute('title', 'Nothing within reach for this window.');
    });
  });

  it('renders the runner-up legend with its own word and its own border class', async () => {
    await renderStrip({ cards: [stripCard({ pickKind: 'also' })] });
    const legend = screen.getByTestId('wf-heat-legend');
    expect(legend).toHaveTextContent('Also good');
    expect(legend).toHaveAttribute('data-pick', 'also');
    expect(screen.getByTestId('wf-heat-card')).toHaveClass('also');
  });

  it('renders NO legend when the payload carries no pick for this window', async () => {
    // The state an away origin produces: `buildWindowCards` withholds a pick naming a scoped-out
    // region, and nothing here may reconstruct one (plan-matrix A4/D-5).
    await renderStrip({ cards: [stripCard({ pickKind: null })] });
    expect(screen.queryByTestId('wf-heat-legend')).toBeNull();
    const card = screen.getByTestId('wf-heat-card');
    expect(card).not.toHaveClass('best');
    expect(card).not.toHaveClass('also');
  });

  it('takes the verdict word from the payload and publishes the verdict it came from', async () => {
    // D3/A1: the vocabulary is `VERDICT_LABEL`'s and the design's own ≥3.7/≥2.8 thresholds are not
    // ported. The attribute is what the stylesheet colours off, so the word and the hue come from
    // one field rather than two.
    await renderStrip({ cards: [stripCard({ verdict: 'STAND_DOWN', verdictLabel: 'Poor' })] });

    const verdict = screen.getByTestId('wf-heat-verdict');
    expect(verdict).toHaveTextContent('Poor');
    expect(verdict).toHaveAttribute('data-verdict', 'STAND_DOWN');
  });

  it('⚠️ prints the served LABEL even when it contradicts every rating on the card', async () => {
    // The mutation this file exists to survive. A client threshold (the design's `vWord(avg)`)
    // would read the ratings and print "Worth it" here; the served verdict says STAND_DOWN and the
    // card must say Poor. The pool is deliberately all 5★ so the two answers cannot coincide.
    await renderStrip({
      cards: [ratedCard({
        verdict: 'STAND_DOWN',
        verdictLabel: 'Poor',
        pool: [poolSpot({ rating: 5 }), poolSpot({ locationId: 2, locationName: 'Blyth', rating: 5 })],
      })],
    });
    expect(screen.getByTestId('wf-heat-verdict')).toHaveTextContent('Poor');
    expect(screen.getByTestId('wf-heat-card')).toHaveClass('vp');
  });

  it('tints the whole card by the served verdict, one class per band', async () => {
    const tint = async (verdict, verdictLabel) => {
      cleanup();
      await renderStrip({ cards: [stripCard({ verdict, verdictLabel })] });
      return screen.getByTestId('wf-heat-card').className;
    };
    expect(await tint('WORTH_IT', 'Worth it')).toContain('vg');
    expect(await tint('MAYBE', 'Maybe')).toContain('vm');
    expect(await tint('STAND_DOWN', 'Poor')).toContain('vp');
  });

  it('renders an Awaiting window as Awaiting, and takes no tint at all', async () => {
    // Neither AWAITING nor an away cell is a judgement about the sky, so a coloured card would read
    // as a fourth grade.
    await renderStrip({ cards: [stripCard({ verdict: 'AWAITING', verdictLabel: 'Awaiting' })] });
    expect(screen.getByTestId('wf-heat-verdict')).toHaveTextContent('Awaiting');
    const card = screen.getByTestId('wf-heat-card');
    expect(card.className).not.toMatch(/\bv[gmp]\b/);
  });

  it('says SUNSET or SUNRISE as a word, never an arrow', async () => {
    // The design's own call and it is right: a down arrow for sunset reads as a falling forecast.
    await renderStrip({
      cards: [
        stripCard(),
        stripCard({ key: '2026-08-05:SUNRISE', date: '2026-08-05', targetType: 'SUNRISE', dow: 'Wed', sunrise: true, label: 'Tomorrow sunrise', time: '05:20' }),
      ],
    });
    expect(screen.getAllByTestId('wf-heat-sun').map((s) => s.textContent))
      .toEqual(['SUNSET', 'SUNRISE']);
  });

  it('marks the open row\'s card, so the matrix says which row you are in', async () => {
    await renderStrip({ openKeys: new Set([`${TODAY}:SUNSET`]) });
    const card = screen.getByTestId('wf-heat-card');
    expect(card).toHaveAttribute('data-open', 'true');
    expect(card).toHaveAttribute('aria-expanded', 'true');
    expect(card).toHaveClass('on');
  });

  it('leaves every other card unmarked', async () => {
    await renderStrip({ openKeys: new Set(['2026-08-05:SUNRISE']) });
    const card = screen.getByTestId('wf-heat-card');
    expect(card).not.toHaveAttribute('data-open');
    expect(card).toHaveAttribute('aria-expanded', 'false');
  });
});

describe('WindowFirstHeatStrip — the spread histogram', () => {
  const pool = (...ratings) => ratings.map((rating, index) => poolSpot({
    key: String(index + 1), locationId: index + 1, locationName: `Spot ${index + 1}`, rating,
  }));

  it('draws one bar per star band, lowest first, and fills only the bands that hold something', async () => {
    // Five bars always, so the row reads as a distribution rather than as a variable-length list —
    // an empty band is a hairline, not an absence.
    await renderStrip({ cards: [ratedCard({ pool: pool(3, 3, 5) })] });
    const bars = screen.getAllByTestId('wf-heat-spread-bar');
    expect(bars.map((b) => b.getAttribute('data-star'))).toEqual(['1', '2', '3', '4', '5']);
    // The 3★ band holds two and is the tallest, so it takes the full 13px; the 5★ band holds one
    // of that two and takes half of it. An empty band is a 1px hairline rather than nothing.
    // (The one-in-twenty case, where the proportion would round away and the floor catches it, is
    // pinned in `windowFirstSpread.test.js`.)
    expect(bars[2]).toHaveStyle({ height: '13px' });
    expect(bars[4]).toHaveStyle({ height: '7px' });
    expect(bars[0]).toHaveStyle({ height: '1px' });
  });

  it('counts the POOL in its tooltip, not the sum of the bars, and names the remainder', async () => {
    // A10, and the property the copy exists to disclose. Two rated and two not: the bars sum to
    // two, the count says four, and the difference is stated rather than left to be noticed.
    await renderStrip({ cards: [ratedCard({ pool: pool(5, 3, null, null) })] });
    expect(screen.getByTestId('wf-heat-spread')).toHaveAttribute(
      'title',
      '4 locations within reach — 1 at 5★, 0 at 4★, 1 at 3★, 0 at 2★, 0 at 1★ · 2 not yet rated',
    );
  });

  it('never says "scored" of the pool count', async () => {
    // Plan §3 rule 5: the count is places to go, which is the statement the lens readout already
    // makes — never "N of M scored", a count of our own data dressed as a fact about the sky.
    await renderStrip({ cards: [ratedCard({ pool: pool(5, null) })] });
    const title = screen.getByTestId('wf-heat-spread').getAttribute('title');
    expect(title).toContain('2 locations within reach');
    expect(title).not.toMatch(/scored\b(?! yet)/);
  });

  it('says nothing is within reach for an empty pool', async () => {
    await renderStrip({ cards: [stripCard()] });
    expect(screen.getByTestId('wf-heat-spread'))
      .toHaveAttribute('title', 'Nothing within reach for this window.');
  });

  it('draws no histogram at all on an away cell', async () => {
    // There is no card behind an away window, so there is no pool — and an empty histogram beside
    // "nothing in reach" would be a claim about a night nobody forecast.
    await renderStrip({ cards: [stripCard({ away: true, verdict: null, verdictLabel: 'Not forecast' })] });
    expect(screen.queryByTestId('wf-heat-spread')).toBeNull();
  });
});

describe('WindowFirstHeatStrip — the best you could actually reach', () => {
  it('names the pool head and prints its rating in the ramp colour', async () => {
    await renderStrip({ cards: [ratedCard()] });
    expect(screen.getByTestId('wf-heat-best')).toHaveTextContent('Bamburgh Beach');
    expect(screen.getByTestId('wf-heat-best-rating')).toHaveTextContent('4★');
  });

  it('carries region, drive and leave-by in the title, and only the parts it has', async () => {
    await renderStrip({ cards: [ratedCard()] });
    // 20:11 UTC event, 40 min drive, 20 min setup → 21:11 BST minus an hour = leave 20:11 BST.
    expect(screen.getByTestId('wf-heat-best'))
      .toHaveAttribute('title', 'Northumberland & Tyneside · 40 min · leave 20:11');
  });

  it('drops the drive and the leave time from the title when the drive is unknown', async () => {
    // The first-run user with no home postcode: a title reading "Region · · leave " is worse than
    // one naming the region alone.
    await renderStrip({
      cards: [ratedCard({ pool: [poolSpot({ driveMinutes: null })] })],
    });
    expect(screen.getByTestId('wf-heat-best'))
      .toHaveAttribute('title', 'Northumberland & Tyneside');
  });

  it('says nothing is in reach when the pool is empty, with no rating chip', async () => {
    await renderStrip({ cards: [stripCard()] });
    expect(screen.getByTestId('wf-heat-best')).toHaveTextContent('nothing in reach');
    expect(screen.queryByTestId('wf-heat-best-rating')).toBeNull();
  });

  it('says NOT SCORED YET when the pool holds places but none of them is rated', async () => {
    // The ordinary far-horizon state, and the one the card must not read as "nothing in reach":
    // there ARE places, they are simply unrated, and naming the alphabetical head "best" would
    // claim a ranking that never ran.
    await renderStrip({
      cards: [stripCard({ pool: [poolSpot({ rating: null })], bestReach: null })],
    });
    expect(screen.getByTestId('wf-heat-best')).toHaveTextContent('not scored yet');
    expect(screen.getByTestId('wf-heat-best')).not.toHaveTextContent('nothing in reach');
    expect(screen.queryByTestId('wf-heat-best-rating')).toBeNull();
  });

  it('draws no best line at all on an away cell', async () => {
    await renderStrip({ cards: [stripCard({ away: true, verdict: null, verdictLabel: 'Not forecast' })] });
    expect(screen.queryByTestId('wf-heat-best')).toBeNull();
  });
});

describe('WindowFirstHeatStrip — topics on the card', () => {
  const AURORA_TOPIC = {
    type: 'AURORA',
    label: 'Aurora',
    detail: 'Kp 6 forecast',
    date: TODAY,
    eventType: 'NIGHT',
    // ⚠️ POPULATED, and it is Bortle-enrichment coverage rather than an eligibility roster — the
    // exact shape an unexempted scope intersection would delete from every away plan.
    regions: ['Galloway'],
    rarityRank: 1,
  };
  const TIDE_TOPIC = {
    type: 'KING_TIDE',
    label: 'King tide',
    detail: 'HW 06:18',
    date: TODAY,
    eventType: 'SUNSET',
    regions: ['Northumberland & Tyneside'],
    rarityRank: 3,
  };

  it('names every topic on the night in full, rarest first', async () => {
    // Nothing collapsed behind a `+2`: a night carrying three topics is the most interesting night
    // of the week, so hiding two behind a count had it exactly backwards.
    await renderStrip({
      cards: [ratedCard({
        badges: [
          { type: 'KING_TIDE', label: 'King tide', rarityRank: 3 },
          { type: 'AURORA', label: 'Aurora', rarityRank: 1 },
        ],
      })],
      hotTopics: [AURORA_TOPIC, TIDE_TOPIC],
    });
    expect(screen.getAllByTestId('wf-heat-topic').map((t) => t.textContent))
      .toEqual(['Aurora', 'King tide']);
  });

  it('colours each topic by its own channel', async () => {
    await renderStrip({
      cards: [ratedCard({ badges: [{ type: 'KING_TIDE', label: 'King tide', rarityRank: 3 }] })],
      hotTopics: [TIDE_TOPIC],
    });
    expect(screen.getByTestId('wf-heat-topic')).toHaveAttribute('data-channel', 'tide');
  });

  it('drops a region-scoped topic when the origin scopes its regions away', async () => {
    // Planning from a region the king tide does not name drops it by itself — A8's whole point.
    await renderStrip({
      cards: [ratedCard({ badges: [{ type: 'KING_TIDE', label: 'King tide', rarityRank: 3 }] })],
      hotTopics: [TIDE_TOPIC],
      origin: { name: 'The Lake District', baseName: 'Keswick' },
      spots: [spot({ regionName: 'The Lake District', rid: 'The Lake District' })],
    });
    expect(screen.queryByTestId('wf-heat-topic')).toBeNull();
  });

  it('⚠️ KEEPS a whole-sky topic under the same away origin, populated regions and all', async () => {
    // The live hazard A8 names. Aurora's served `regions` is Bortle coverage, so the intersection
    // test that is right for a tide deletes aurora from every away plan — while every naive test
    // passes, because a home-origin fixture never empties the intersection.
    await renderStrip({
      cards: [ratedCard({ badges: [{ type: 'AURORA', label: 'Aurora', rarityRank: 1 }] })],
      hotTopics: [AURORA_TOPIC],
      origin: { name: 'The Lake District', baseName: 'Keswick' },
      spots: [spot({ regionName: 'The Lake District', rid: 'The Lake District' })],
    });
    expect(screen.getByTestId('wf-heat-topic')).toHaveTextContent('Aurora');
  });

  it('joins a NIGHT topic to the badge on the NEXT morning\'s card', async () => {
    // The branch a `topic.date === card.date` equality loses: the aurora is dated today, and the
    // card that must name it is tomorrow's sunrise.
    await renderStrip({
      cards: [stripCard({
        key: '2026-08-05:SUNRISE',
        date: '2026-08-05',
        targetType: 'SUNRISE',
        sunrise: true,
        dow: 'Wed',
        label: 'Tomorrow sunrise',
        time: '05:20',
        badges: [{ type: 'AURORA', label: 'Aurora', rarityRank: 1 }],
      })],
      hotTopics: [AURORA_TOPIC],
    });
    expect(screen.getByTestId('wf-heat-topic')).toHaveTextContent('Aurora');
  });

  it('keeps a badge whose topic is missing from the payload entirely', async () => {
    // The degrade: with no topic there is no scope information, so dropping it would be a filter
    // applied on the strength of a missing join.
    await renderStrip({
      cards: [ratedCard({ badges: [{ type: 'KING_TIDE', label: 'King tide', rarityRank: 3 }] })],
      hotTopics: [],
    });
    expect(screen.getByTestId('wf-heat-topic')).toHaveTextContent('King tide');
  });

  it('reserves the topics row even on a card with no topics', async () => {
    // So a topic-free card's value rows land on the same baselines as its neighbours'.
    await renderStrip({ cards: [ratedCard()] });
    expect(screen.getByTestId('wf-heat-topics')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-heat-topic')).toBeNull();
  });

  it('draws no topics row at all on an away cell', async () => {
    await renderStrip({
      cards: [stripCard({
        away: true,
        verdict: null,
        verdictLabel: 'Not forecast',
        badges: [{ type: 'AURORA', label: 'Aurora', rarityRank: 1 }],
      })],
      hotTopics: [AURORA_TOPIC],
    });
    expect(screen.queryByTestId('wf-heat-topics')).toBeNull();
  });
});

describe('WindowFirstHeatStrip — the grid it lays out', () => {
  /** The ordinary week: today's sunrise gone, four days, the last sunset past the cap. */
  const WEEK = [
    stripCard(),
    stripCard({ key: '2026-08-05:SUNRISE', date: '2026-08-05', targetType: 'SUNRISE', sunrise: true, dow: 'Wed', label: 'Tomorrow sunrise', time: '05:20' }),
    stripCard({ key: '2026-08-05:SUNSET', date: '2026-08-05', targetType: 'SUNSET', dow: 'Wed', label: 'Tomorrow sunset', time: '21:09' }),
    stripCard({ key: '2026-08-06:SUNRISE', date: '2026-08-06', targetType: 'SUNRISE', sunrise: true, dow: 'Thu', label: 'Thursday sunrise', time: '05:22' }),
  ];

  it('publishes the day count so the stylesheet can make a column per day', async () => {
    await renderStrip({ cards: WEEK });
    expect(screen.getByTestId('wf-heat-grid')).toHaveStyle({ '--dc': '3' });
  });

  it('places each cell in its own column and row rather than flowing them', async () => {
    // Explicit placement is what lets the phone transpose the identical markup into two columns
    // under a spanning day heading with no second render path.
    await renderStrip({ cards: WEEK });
    const cards = screen.getAllByTestId('wf-heat-card');
    expect(cards[0]).toHaveStyle({ '--c': '1', '--r': '3' });
    expect(cards[1]).toHaveStyle({ '--c': '2', '--r': '2' });
    expect(cards[2]).toHaveStyle({ '--c': '2', '--r': '3' });
    expect(cards[3]).toHaveStyle({ '--c': '3', '--r': '2' });
  });

  it('heads each column with its weekday and date, once per day', async () => {
    await renderStrip({ cards: WEEK });
    expect(screen.getAllByTestId('wf-heat-day').map((d) => d.textContent))
      .toEqual(['Today4', 'Wed5', 'Thu6']);
  });

  it('⚠️ names today in WORDS, not by colour alone', async () => {
    // SC 1.4.1. The design marks today with a gold tile border and a gold digit and nothing else, on
    // the stated grounds that the cards carry "Today"/"Tonight" in their own labels — which was true
    // of P2's thumbnail and is false of the v3 card face, where `card.label` reaches the DOM only
    // inside the visually-hidden sentence. The word goes in the weekday's own slot.
    await renderStrip({ cards: WEEK });
    const days = screen.getAllByTestId('wf-heat-day');
    expect(days[0]).toHaveTextContent('Today');
    expect(days[0]).not.toHaveTextContent('Tue');
    expect(days[1]).toHaveTextContent('Wed');
    expect(days[1]).not.toHaveTextContent('Today');
  });

  it('marks the today column and no other', async () => {
    await renderStrip({ cards: WEEK });
    const days = screen.getAllByTestId('wf-heat-day');
    expect(days[0]).toHaveAttribute('data-today', 'true');
    expect(days[1]).not.toHaveAttribute('data-today');
  });

  it('says why this morning is missing, and why the last evening is', async () => {
    await renderStrip({ cards: WEEK });
    expect(screen.getAllByTestId('wf-heat-empty').map((e) => e.textContent))
      .toEqual(['this morning has gone', 'past the end of the forecast']);
  });

  it('renders a mid-span hole as a dashed cell with NO words', async () => {
    // Degrade is silence: a cell that cannot explain itself must not borrow a sentence from one of
    // the two holes that can.
    await renderStrip({
      cards: [
        stripCard(),
        stripCard({ key: '2026-08-05:SUNSET', date: '2026-08-05', targetType: 'SUNSET', dow: 'Wed', label: 'Tomorrow sunset', time: '21:09' }),
        stripCard({ key: '2026-08-06:SUNRISE', date: '2026-08-06', targetType: 'SUNRISE', sunrise: true, dow: 'Thu', label: 'Thursday sunrise', time: '05:22' }),
        stripCard({ key: '2026-08-06:SUNSET', date: '2026-08-06', targetType: 'SUNSET', dow: 'Thu', label: 'Thursday sunset', time: '21:06' }),
      ],
    });
    const holes = screen.getAllByTestId('wf-heat-empty');
    expect(holes.map((e) => e.textContent)).toEqual(['this morning has gone', '']);
  });

  it('marks a day holding one window as solo, which is what widens it on a phone', async () => {
    // jsdom has no layout, so the media query itself is a browser claim — what is asserted here is
    // the class the rule selects on.
    await renderStrip({ cards: WEEK });
    const cards = screen.getAllByTestId('wf-heat-card');
    expect(cards[0]).toHaveClass('solo');
    expect(cards[1]).not.toHaveClass('solo');
    expect(cards[3]).toHaveClass('solo');
  });
});

describe('WindowFirstHeatStrip — the click', () => {
  it('opens the window its thumbnail names', async () => {
    const { onOpenWindow } = await renderStrip();
    fireEvent.click(screen.getByRole('button', { name: /Tonight Sunset/ }));
    expect(onOpenWindow).toHaveBeenCalledWith(`${TODAY}:SUNSET`);
  });

  it('renders without a handler rather than throwing, so the strip is legible either way', async () => {
    await act(async () => {
      render(<WindowFirstHeatStrip cards={[stripCard()]} spots={[spot()]} todayStr={TODAY} />);
    });
    fireEvent.click(screen.getByRole('button', { name: /Tonight Sunset/ }));
    expect(screen.getByTestId('wf-heat-card')).toBeInTheDocument();
  });
});

describe('WindowFirstHeatStrip — the away window', () => {
  const AWAY = stripCard({
    key: '2026-08-05:SUNRISE',
    date: '2026-08-05',
    dow: 'Wed',
    sunrise: true,
    label: 'Tomorrow sunrise',
    time: '05:20',
    verdict: null,
    verdictLabel: 'Not forecast',
    away: true,
  });

  it('is not a control, because there is no row for it to open', async () => {
    // §6 bans a control with no visible effect. A travel day has no card and no anchor to scroll
    // to, so the cell states its slot and stops there.
    await renderStrip({ cards: [AWAY] });

    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.getByTestId('wf-heat-card')).toHaveAttribute('data-away', 'true');
  });

  it('keeps its slot, its sun time and the arm\'s own word for the state', async () => {
    await renderStrip({ cards: [stripCard(), AWAY] });

    const cells = screen.getAllByTestId('wf-heat-card');
    expect(cells).toHaveLength(2);
    expect(cells[1]).toHaveTextContent('05:20');
    expect(cells[1]).toHaveTextContent('Not forecast');
  });

  it('takes no verdict colour, since nothing about the sky was judged', async () => {
    await renderStrip({ cards: [AWAY] });
    expect(screen.getByTestId('wf-heat-verdict')).toHaveAttribute('data-verdict', 'AWAY');
  });
});

describe('WindowFirstHeatStrip — the movement channel', () => {
  const moved = (delta, regionName = 'Northumberland & Tyneside') => stripCard({
    movement: { regionName, delta },
  });

  it('⚠️ draws NO per-card movement chip — the change line is the whole channel now', async () => {
    // The deletion M1 makes (deletion ledger). P6 put a delta chip on every thumbnail beside this
    // line; the design's note is that it earned nothing, because a magnitude on a tile with no room
    // to name the region it belonged to left the reader with a number attributable to nothing.
    // The card face reclaims the row; the line below still names the movers and their regions.
    await renderStrip({ cards: [moved(0.6)] });

    expect(screen.queryByTestId('wf-heat-move')).toBeNull();
    expect(screen.getByTestId('wf-heat-change')).toHaveTextContent('Northumberland & Tyneside');
  });

  it('⚠️ leaves the movement OUT of the accessible name, because nothing renders it there', async () => {
    // Plan §3 rule 15 in the direction a deletion tests it: the sentence counts what is actually
    // rendered, so when the chip went the clause went with it. A name still carrying "up 0.6"
    // would describe an element the card no longer draws.
    await renderStrip({ cards: [moved(0.6)] });

    const name = screen.getByTestId('wf-heat-card').textContent;
    expect(name).not.toContain('up 0.6');
    expect(screen.getByRole('button', {
      name: 'Tonight Sunset, 21:11, Worth it, best, nothing in reach',
    })).toBeInTheDocument();
  });

  it('renders NOTHING at all when the payload carries no delta', async () => {
    // The ordinary state on the first serve after a deploy, and for any region the previous build
    // did not hold. A `—` here would claim a measurement nobody made.
    await renderStrip({ cards: [stripCard()] });

    expect(screen.queryByTestId('wf-heat-change')).toBeNull();
  });

  it('names the top two movers, their regions and the run age under the strip', async () => {
    await renderStrip({
      runAge: '52m ago',
      cards: [
        moved(0.6, 'Northumberland & Tyneside'),
        stripCard({ key: `${TODAY}:SUNRISE`, targetType: 'SUNRISE', sunrise: true, label: 'Today Sunrise', movement: { regionName: 'Cumbria', delta: -0.9 } }),
        stripCard({ key: '2026-08-05:SUNSET', date: '2026-08-05', label: 'Tomorrow Sunset', movement: { regionName: 'Yorkshire', delta: 0.2 } }),
      ],
    });

    const line = screen.getByTestId('wf-heat-change');
    expect(line).toHaveTextContent('Moved at the last forecast run, 52m ago');
    // Two, not three — and biggest first. Asserted per item rather than as one string, because the
    // visually-hidden spoken form sits between the glyph and the region: a whole-line match would
    // be pinning the concatenation of the visible and hidden halves, which is a string no reader
    // ever perceives in either channel.
    const items = within(line).getAllByTestId('wf-heat-change-item');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('Today Sunrise');
    expect(items[0]).toHaveTextContent('▼0.9');
    expect(items[0]).toHaveTextContent('in Cumbria');
    expect(items[1]).toHaveTextContent('Tonight Sunset');
    expect(items[1]).toHaveTextContent('▲0.6');
    expect(items[1]).toHaveTextContent('in Northumberland & Tyneside');
    expect(line).not.toHaveTextContent('Yorkshire');
  });

  it('tones each mover in the change line by its own direction', async () => {
    // The chips on the thumbnails pin `data-tone`; the change line's marks did not, so deleting
    // the attribute here (or hardcoding it) painted a falling window green with a green suite.
    await renderStrip({
      runAge: '52m ago',
      cards: [
        moved(0.6),
        stripCard({ key: `${TODAY}:SUNRISE`, targetType: 'SUNRISE', sunrise: true, label: 'Today Sunrise', movement: { regionName: 'Cumbria', delta: -0.9 } }),
      ],
    });

    const marks = screen.getAllByTestId('wf-heat-change-mark');
    expect(marks.map((m) => m.getAttribute('data-tone'))).toEqual(['down', 'up']);
  });

  it('keeps the separator and the region OUTSIDE the unbreakable atom', async () => {
    // ⚠️ A reflow regression guard, not a structure preference. With `wf-hstrip-chg` on the whole
    // item the clause was one unbreakable run — JSX strips the whitespace-only lines between array
    // elements, so the paragraph's last break opportunity was the space before the run age, and a
    // 25-character region name put ~328px of text into a 315px column at 375px: a horizontal page
    // scroller, which `.wf-hstrip`'s own comment says the strip exists not to be. jsdom does no
    // layout and `css: false` loads no stylesheet, so the WIDTH is unassertable here — what is
    // assertable is the containment that makes wrapping possible, which is the thing that changed.
    await renderStrip({ runAge: '52m ago', cards: [moved(0.6)] });

    const item = screen.getByTestId('wf-heat-change-item');
    const atom = item.querySelector('.wf-hstrip-chg');
    expect(atom).toContainElement(screen.getByTestId('wf-heat-change-mark'));
    expect(atom.textContent).not.toContain('·');
    expect(atom.textContent).not.toContain('Northumberland');
    expect(item.textContent).toContain('in Northumberland & Tyneside');
  });

  it('withholds the change line entirely when nothing moved', async () => {
    // `topMovers` drops a measured zero, so two windows that did not move leave nothing to name —
    // and with the per-card chip gone, that is the whole movement channel withdrawing rather than
    // degrading to six flat marks.
    await renderStrip({ runAge: '52m ago', cards: [moved(0), moved(0)] });

    expect(screen.queryByTestId('wf-heat-change')).toBeNull();
  });

  /**
   * The age's own line, which M3 gave this component when it deleted the rail footer.
   *
   * <p>⚠️ The two forms are MUTUALLY EXCLUSIVE by construction, and that is the whole point: the
   * page states one age (Rule 7), where before M3 the footer and the change line each printed the
   * same `generatedAt`. The no-movement form also drops the verb — "Moved at the last forecast run"
   * over an empty list would assert a movement the same element has just declined to name.
   */
  it('states the run age on its own when nothing moved, rather than losing it with the line', async () => {
    await renderStrip({ runAge: '52m ago', cards: [moved(0), moved(0)] });

    const line = screen.getByTestId('wf-heat-runage');
    expect(line).toHaveTextContent('Last forecast run 52m ago');
    expect(line.textContent).not.toMatch(/moved/i);
  });

  it('⚠️ never draws both age forms at once', async () => {
    await renderStrip({ runAge: '52m ago', cards: [moved(0.6), moved(0)] });

    expect(screen.getByTestId('wf-heat-change')).toHaveTextContent('52m ago');
    expect(screen.queryByTestId('wf-heat-runage')).toBeNull();
  });

  it('says nothing at all when there is no age and nothing moved', async () => {
    // Degrade is silence. An empty "Last forecast run" is a claim with nothing behind it, and the
    // rail footer that used to hold this fact applied the same rule.
    await renderStrip({ cards: [moved(0)] });

    expect(screen.queryByTestId('wf-heat-runage')).toBeNull();
    expect(screen.queryByTestId('wf-heat-change')).toBeNull();
  });

  it('still names its movers when the run age is unknown', async () => {
    // `formatRelativeAge` answers null for an absent or unparseable stamp. The movers are what the
    // line is for, so an absent age costs the age and nothing else.
    await renderStrip({ cards: [moved(0.6)] });

    const line = screen.getByTestId('wf-heat-change');
    expect(line).toHaveTextContent('Moved at the last forecast run ·');
    const item = within(line).getByTestId('wf-heat-change-item');
    expect(item).toHaveTextContent('▲0.6');
    expect(item).toHaveTextContent('in Northumberland & Tyneside');
  });
});

describe('WindowFirstHeatStrip — a window nobody rated', () => {
  /**
   * The defect this block exists for, in one sentence: a window with no ratings and a window whose
   * whole roster was rated 1 rendered IDENTICALLY, because the kernel returns null for an empty
   * point set and the tile falls back to bare geography — while the verdict word underneath comes
   * from the briefing's weather thresholds, which run the full horizon and print a confident
   * "Poor" either way. Reported against a real Saturday sunrise at T+3, where Gate 4 evaluates
   * SETTLED cells only.
   */
  const SCORED = stripCard({ verdict: 'STAND_DOWN', verdictLabel: 'Poor', bestRating: 2 });
  const UNSCORED = stripCard({
    key: '2026-08-07:SUNRISE',
    date: '2026-08-07',
    dow: 'Sat',
    sunrise: true,
    label: 'Saturday sunrise',
    time: '05:49',
    verdict: 'STAND_DOWN',
    verdictLabel: 'Poor',
    bestRating: null,
  });
  /** Both tiles, same verdict, same word — only the served rating differs. */
  const BOTH = [SCORED, UNSCORED];

  it('hatches the plate of the unrated window and leaves the rated one alone', async () => {
    // The pair is the assertion. A test that rendered only the unscored tile would pass against a
    // component that hatched every plate unconditionally — which is the same defect with the
    // opposite sign, since a hatch on all six says nothing about any of them.
    await withMeasuredThumbs(160, async () => {
      await renderStrip({ cards: BOTH, });
    });

    expect(drawGeo).toHaveBeenCalledTimes(2);
    expect(drawGeo.mock.calls.map((c) => c[5].hatch)).toEqual([false, true]);
  });

  it('marks the unrated tile and not its identically-worded neighbour', async () => {
    await renderStrip({ cards: BOTH });
    const [scored, unrated] = screen.getAllByTestId('wf-heat-card');

    expect(scored).not.toHaveAttribute('data-unscored');
    expect(unrated).toHaveAttribute('data-unscored', 'true');
    // Same word, same channel, on both — which is the point. The rating is what is missing, so
    // the RATING channel is what is withheld; the weather verdict is a true statement either way,
    // and `index.css` measured this 9px word to AA on the assumption it keeps its own colour.
    const verdicts = screen.getAllByTestId('wf-heat-verdict');
    expect(verdicts.map((v) => v.textContent)).toEqual(['Poor', 'Poor']);
    verdicts.forEach((v) => expect(v).toHaveAttribute('data-verdict', 'STAND_DOWN'));
  });

  it('says "not scored" in the accessible name, straight after the verdict it qualifies', async () => {
    // A hatch is not vocabulary. Without this clause the whole mark is invisible to a screen
    // reader, which would hear the same sentence for both tiles above.
    await renderStrip({ cards: BOTH });

    // ⚠️ The unscored card carries NO best-reach clause WHEN ITS POOL HOLDS SOMETHING. The window
    // has no rating anywhere, so its pool has no rated spot either, and "not scored" followed by
    // "best, not scored yet" would be one fact said twice in a sentence that has to stay scannable
    // across six cards. With an EMPTY pool the clause returns: "nothing is scored" and "nothing is
    // reachable" are different claims, and only the second is on the face.
    await cleanup();
    await renderStrip({
      cards: [BOTH[0], { ...BOTH[1], pool: [poolSpot({ rating: null })] }],
    });
    // The remainder rides the sentence too, not the pointer-only tooltip alone — A10 requires the
    // `N > Σbars` gap to be disclosed wherever the N is, and this is the extreme case of it.
    expect(screen.getByRole('button', { name: 'Saturday sunrise, 05:49, Poor, not scored, 1 location within reach, 1 not yet rated' }))
      .toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tonight Sunset, 21:11, Poor, best, nothing in reach' }))
      .toBeInTheDocument();
  });

  it('keeps the "nothing in reach" clause on an unscored window whose pool is empty too', async () => {
    // Both suppressions could fire at once, and then the card face said "Best — nothing in reach"
    // while its name said only "not scored". Two different claims; the reader who cannot see the
    // face was told the wrong one.
    await renderStrip({ cards: [BOTH[1]] });
    expect(screen.getByRole('button', { name: 'Saturday sunrise, 05:49, Poor, not scored, best, nothing in reach' }))
      .toBeInTheDocument();
  });

  it('names the convention in the footer only while a hatched plate is on screen', async () => {
    await renderStrip({ cards: BOTH });
    expect(screen.getByTestId('wf-heat-foot')).toHaveTextContent('unshaded — not scored');

    cleanup();
    await renderStrip({ cards: [SCORED] });
    expect(screen.queryByTestId('wf-heat-unscored-note')).toBeNull();
  });

  it('leaves a RATED window alone even when its field has no points to paint', async () => {
    // ⚠️ The production defect, in one test. An empty point set is a fact about the join behind
    // the picture, not about the forecast — on 2026-08-19 three windows the payload was rating had
    // one, and a Saturday drew a hatched plate reading "not scored" directly above its own card
    // reading `best spot 5★`. Marking a window the surface beneath it is rating is worse than the
    // blank this whole channel was built to fix.
    await withMeasuredThumbs(160, async () => {
      await renderStrip({ cards: [stripCard({ bestRating: 5 })], pointSets: new Map() });
    });

    expect(drawGeo.mock.calls.at(-1)[5].hatch).toBe(false);
    expect(screen.getByTestId('wf-heat-card')).not.toHaveAttribute('data-unscored');
    expect(screen.queryByTestId('wf-heat-unscored-note')).toBeNull();
  });

  it('marks a window with no served rating even when its field DOES have points', async () => {
    // The converse, and the reason the predicate is not "either signal will do": the two can
    // disagree in both directions, and the payload's own answer is the one that governs.
    await withMeasuredThumbs(160, async () => {
      await renderStrip({
        cards: [UNSCORED],
        pointSets: new Map([[UNSCORED.key, [
          { id: 1, name: 'Bamburgh Beach', lat: 55.61, lng: -1.71, rid: 'R', r: [4] },
        ]]]),
      });
    });

    expect(drawGeo.mock.calls.at(-1)[5].hatch).toBe(true);
    expect(screen.getByTestId('wf-heat-card')).toHaveAttribute('data-unscored', 'true');
  });

  it('leaves an away window to its own truer word, and does not call it unscored', async () => {
    // It carries no rating either — nothing is evaluated on a travel day — so the bare predicate
    // catches it. "Not forecast" is the stronger claim and the one the payload supports: the
    // weather never ran for it, where an unscored live window has a real verdict and no rating.
    const away = stripCard({
      key: '2026-08-06:SUNSET',
      date: '2026-08-06',
      label: 'Thursday sunset',
      time: '20:21',
      verdict: null,
      verdictLabel: 'Not forecast',
      bestRating: null,
      away: true,
    });
    await renderStrip({ cards: [SCORED, away] });

    const cells = screen.getAllByTestId('wf-heat-card');
    expect(cells[1]).toHaveAttribute('data-away', 'true');
    expect(cells[1]).not.toHaveAttribute('data-unscored');
    expect(screen.queryByTestId('wf-heat-unscored-note')).toBeNull();
  });
});

describe('WindowFirstHeatStrip — the header, footer and beyond line', () => {
  it('states the horizon with NO count of any kind', async () => {
    // §2.6 and §4.3 both say "kicker + rule, no count", and §6 clause 4 bans counts of our own data
    // outright — the mock's "204 rated locations · 51 named" is a fact about the database, and
    // `WindowAttributeRow`, `WindowFirstDoors` and the retired rail were each stripped of exactly
    // this copy. An earlier cut printed "The next 2 days", reasoning that a count of the days DRAWN
    // is not a database count; the plan says no count, twice, unqualified, so the number went. (The
    // design's static "Next four days" is not portable either: six windows span three dates or four
    // depending on whether the day's sunrise has passed.)
    await renderStrip({
      cards: [
        stripCard(),
        stripCard({ key: '2026-08-05:SUNRISE', date: '2026-08-05', label: 'Tomorrow sunrise' }),
        stripCard({ key: '2026-08-05:SUNSET', date: '2026-08-05', label: 'Tomorrow sunset' }),
      ],
    });

    expect(screen.getByTestId('wf-heat-head')).toHaveTextContent('The days ahead');
    expect(screen.getByTestId('wf-heat-head').textContent).not.toMatch(/\d/);
    expect(screen.getByTestId('wf-heat-strip').textContent).not.toMatch(/\d+\s+(rated )?locations/i);
  });

  it('says the same thing for a one-day strip, naming no number to have to agree with', async () => {
    await renderStrip();
    expect(screen.getByTestId('wf-heat-head')).toHaveTextContent('The days ahead');
  });

  it('decodes the ramp and admits the haze, in the design\'s own words', async () => {
    await renderStrip();
    const foot = screen.getByTestId('wf-heat-foot');

    expect(foot).toHaveTextContent('poor → worth it');
    expect(foot).toHaveTextContent('later days render hazier — lower confidence');
    // The one clause that says what the field is NOT — the lens filters the cards, never the field
    // (plan §3). Dropping it would leave a picture a reader could reasonably read as their own
    // reachable options.
    expect(foot).toHaveTextContent('The field shows the forecast, not your reach');
  });

  it('names the regions beyond the planning area, and only the measured ones', async () => {
    // `beyondRegions` withholds an unmeasured region on purpose: "beyond your planning area" is a
    // claim about a drive, and naming one nobody computed would be inventing the distance.
    await renderStrip({
      spots: [
        spot(),
        spot({ id: 2, regionName: 'The Highlands', rid: 'The Highlands' }),
        spot({ id: 3, regionName: 'Snowdonia', rid: 'Snowdonia' }),
        spot({ id: 4, regionName: 'Unmeasured', rid: 'Unmeasured' }),
      ],
      reachById: new Map([
        [1, { driveMinutes: 66 }],
        [2, { driveMinutes: GLANCE_MINUTES + 1 }],
        [3, { driveMinutes: GLANCE_MINUTES + 90 }],
      ]),
    });

    const beyond = screen.getByTestId('wf-heat-beyond');
    // "from home", never the design's "and not in the field". The field is NOT area-filtered — only
    // the framing is (plan §3: the lens does not filter the field), and `bbox` pads the frame by
    // 1.7× in longitude — so a beyond region can be, and often is, painted. The line names a drive,
    // which is the one thing `beyondRegions` actually measured.
    // Two regions, nearest first, joined by the arm's own middle dot — with one, the separator is
    // untested and `join(', ')` would pass.
    expect(beyond).toHaveTextContent('Beyond 3h from home: The Highlands · Snowdonia');
    expect(beyond).not.toHaveTextContent('not in the field');
    expect(beyond).not.toHaveTextContent('Unmeasured');
  });

  it('draws no beyond line when everything measured is inside the area', async () => {
    await renderStrip({ reachById: new Map([[1, { driveMinutes: 66 }]]) });
    expect(screen.queryByTestId('wf-heat-beyond')).toBeNull();
  });

  it('draws no beyond line at all for a user with no reach data', async () => {
    // The no-home degrade. With nothing measured every region is in the area (D6: never synthesise
    // a smaller one), so there is nothing to name and the line is absent rather than empty.
    await renderStrip({ spots: [spot(), spot({ id: 2, regionName: 'The Highlands', rid: 'The Highlands' })] });
    expect(screen.queryByTestId('wf-heat-beyond')).toBeNull();
  });

  it('offers no search link when the caller wires none', async () => {
    // The link is a P7 addition and is opt-in: a strip rendered without a handler keeps the P2
    // behaviour exactly, which is what makes it safe to hand to a caller that has no search.
    await renderStrip({
      spots: [spot(), spot({ id: 2, regionName: 'The Highlands', rid: 'The Highlands' })],
      reachById: new Map([[1, { driveMinutes: 66 }], [2, { driveMinutes: GLANCE_MINUTES + 1 }]]),
    });

    expect(within(screen.getByTestId('wf-heat-beyond')).queryByRole('button')).toBeNull();
  });

  it('offers a search link naming the NEAREST beyond region, and hands that name back', async () => {
    // Nearest first, because `beyondRegions` is sorted that way and it is the one a reader is most
    // likely to want. One link rather than one per region: six would make the tail longer than the
    // strip it is a footnote to, and the search box is editable.
    const onSearchRegion = vi.fn();
    await renderStrip({
      spots: [
        spot(),
        spot({ id: 2, regionName: 'The Highlands', rid: 'The Highlands' }),
        spot({ id: 3, regionName: 'Snowdonia', rid: 'Snowdonia' }),
      ],
      reachById: new Map([
        [1, { driveMinutes: 66 }],
        [2, { driveMinutes: GLANCE_MINUTES + 90 }],
        [3, { driveMinutes: GLANCE_MINUTES + 1 }],
      ]),
      onSearchRegion,
    });

    const link = screen.getByTestId('wf-heat-beyond-search');
    expect(link).toHaveTextContent('search to plan from Snowdonia');
    fireEvent.click(link);
    expect(onSearchRegion).toHaveBeenCalledWith('Snowdonia');
  });
});

/**
 * The origin's effect on the strip (plan §4.8) — the design's headline state.
 *
 * <p><b>What breaks if these fail.</b> "All six thumbnails re-frame" is the whole visible claim of
 * the origin move. The frame reaches the kernel as {@code opts.fit}, so that argument is the only
 * place the claim can be checked without a canvas — and it is checked on EVERY card, because a
 * re-frame that applied to one thumbnail would be worse than none: six pictures at two scales read
 * as a forecast difference.
 */
describe('WindowFirstHeatStrip — the origin re-frames it', () => {
  const LAKES = spot({ id: 9, name: 'Derwentwater', regionName: 'Lake District', rid: 'Lake District', lat: 54.58, lng: -3.14 });
  const ORIGIN = { name: 'Lake District', baseName: 'Keswick' };
  const TWO_CARDS = [
    stripCard(),
    stripCard({ key: '2026-08-05:SUNRISE', date: '2026-08-05', dow: 'Wed', sunrise: true, label: 'Tomorrow sunrise', time: '05:20' }),
  ];

  it('⚠️ fits EVERY thumbnail to the origin\'s own region, not to the planning area', async () => {
    // Measured thumbs, because jsdom reports 0 for every box and the strip does not paint at zero
    // width — so without this the calls the assertions read never happen.
    await withMeasuredThumbs(160, async () => {
      await renderStrip({ cards: TWO_CARDS, spots: [spot(), LAKES], origin: ORIGIN });
    });

    expect(drawGeo).toHaveBeenCalledTimes(2);
    const fits = drawGeo.mock.calls.map((call) => call[5].fit);
    expect(fits[0]).toEqual(bbox([LAKES]));
    expect(fits[1]).toEqual(fits[0]);
    // And it is a DIFFERENT frame from the home one, or the assertion above would pass on a
    // component that ignored the origin entirely.
    expect(fits[0]).not.toEqual(bbox([spot(), LAKES]));
  });

  it('⚠️ leaves the POINT SET whole — framing must never become the filter §3 forbids', async () => {
    // The footer promises "the field shows the forecast, not your reach". An origin narrows what is
    // in shot; it must not remove a point from the blend, or a spot just outside the frame would
    // stop lighting the edge of it.
    const points = [
      { id: 1, lat: 55.61, lng: -1.71, r: [4] },
      { id: 9, lat: 54.58, lng: -3.14, r: [2] },
    ];
    await withMeasuredThumbs(160, async () => {
      await renderStrip({
        spots: [spot(), LAKES],
        origin: ORIGIN,
        pointSets: new Map([[stripCard().key, points]]),
      });
    });

    expect(drawGeo.mock.calls[0][3]).toBe(points);
  });

  it('withholds the beyond line when away — "beyond 3h from home" is about the home area', async () => {
    await renderStrip({
      spots: [spot(), spot({ id: 2, regionName: 'The Highlands', rid: 'The Highlands' })],
      reachById: new Map([[1, { driveMinutes: 66 }], [2, { driveMinutes: GLANCE_MINUTES + 1 }]]),
      origin: ORIGIN,
      onSearchRegion: vi.fn(),
    });

    expect(screen.queryByTestId('wf-heat-beyond')).toBeNull();
  });

  it('frames on the planning area with no origin', async () => {
    await withMeasuredThumbs(160, async () => {
      await renderStrip({ spots: [spot(), LAKES] });
    });
    expect(drawGeo.mock.calls[0][5].fit).toEqual(bbox([spot(), LAKES]));
  });

  it('⚠️ re-frames on an origin CHANGE, not only on a first render with one set', async () => {
    // The mutation this catches is `origin` dropped from the framing memo's dependency list: the
    // first render is correct either way, and the strip then keeps the previous frame for the rest
    // of the session — a stale-frame defect no render-once test can see.
    await withMeasuredThumbs(160, async () => {
      const props = {
        cards: [stripCard()],
        spots: [spot(), LAKES],
        pointSets: scoredWindows([stripCard()]),
        todayStr: TODAY,
      };
      let rerender;
      await act(async () => {
        ({ rerender } = render(<WindowFirstHeatStrip {...props} />));
      });
      expect(drawGeo.mock.calls[0][5].fit).toEqual(bbox([spot(), LAKES]));

      await act(async () => {
        rerender(<WindowFirstHeatStrip {...props} origin={ORIGIN} />);
      });

      const last = drawGeo.mock.calls[drawGeo.mock.calls.length - 1];
      expect(last[5].fit).toEqual(bbox([LAKES]));
    });
  });
});

describe('WindowFirstHeatStrip — the confidence channel', () => {
  it('hazes a window whose confidence the payload never carried, from the horizon alone', async () => {
    // `resolveConfidence` is fail-soft: with no backend tier it infers from the horizon and CAPS the
    // inference at medium, which CLAUDE.md flags as load-bearing — an absent field means either a
    // legacy payload or a deliberate null, and the client cannot tell them apart. So a null must
    // never resolve to full confidence. `todayStr` is what makes the horizon term real.
    await withMeasuredThumbs(160, async () => {
      await renderStrip({ cards: [stripCard({ confidence: null })], todayStr: TODAY });
    });
    const sameDay = drawGeo.mock.calls[0][5].conf;
    drawGeo.mockClear();

    await withMeasuredThumbs(160, async () => {
      await renderStrip({ cards: [stripCard({ confidence: null })], todayStr: '2026-07-30' });
    });
    const farOut = drawGeo.mock.calls[0][5].conf;

    expect(sameDay).toBeLessThan(1);
    expect(farOut).toBeLessThan(sameDay);
  });

  it('renders no confidence MARK anywhere — the haze is the whole of it', async () => {
    // Plan §2.7's "one uniform channel": the window card's verdict badge is the only place
    // confidence becomes a visible mark, and the retired day rail carried a test saying it rendered
    // none. The strip consumes the same tier and must spend it entirely on the canvas — no tier
    // word, no percentage (D3 rejects the design's `◐ 88%` outright), no glyph.
    await renderStrip({ cards: [stripCard({ confidence: 'low' })] });

    // The footer's honest caption ("later days render hazier — lower confidence") is required by
    // D3 and is excluded: it explains the channel rather than marking one window's tier.
    const cardText = screen.getByTestId('wf-heat-card').textContent;
    expect(cardText).not.toMatch(/\b(low|medium|high|provisional)\b|%|◐/i);
  });
});

describe('WindowFirstHeatStrip — the legend', () => {
  it('paints the ramp bar from the ramp module, not from a copied gradient', async () => {
    // `scoreRamp.js` exists so the canvas and everything beside it read one set of literals. A
    // hand-written CSS gradient here would be a second copy that drifts with nothing failing, which
    // is the exact case the module's own header argues against — so the bar's stops must BE the
    // ramp's stops. Compared as `rgb()` triples because that is what a browser (and jsdom) stores an
    // inline hex as; comparing the source strings would pass on a stylesheet that never applied.
    await renderStrip();

    const painted = screen.getByTestId('wf-heat-legbar').style.background;
    RAMP_STOPS.forEach((stop) => {
      const [r, g, b] = [1, 3, 5].map((i) => parseInt(stop.hex.slice(i, i + 2), 16));
      expect(painted).toContain(`rgb(${r}, ${g}, ${b})`);
    });
  });
});

describe('WindowFirstHeatStrip — the canvases', () => {
  it('hides them from the accessibility tree, because the words are the answer', async () => {
    await renderStrip();
    screen.getAllByTestId('wf-heat-canvas')
      .forEach((cv) => expect(cv).toHaveAttribute('aria-hidden', 'true'));
  });

  it('gives every thumbnail one', async () => {
    await renderStrip({
      cards: [stripCard(), stripCard({ key: '2026-08-05:SUNRISE', date: '2026-08-05' })],
    });
    expect(screen.getAllByTestId('wf-heat-canvas')).toHaveLength(2);
  });

  it('carries the class and the column count its phone transpose rule selects', async () => {
    // jsdom evaluates no `@media` rule, so the CLASS and the custom property are the hooks this file
    // can pin (`frontend-test-standards.md`: assert the class, leave the pixels to the browser
    // check). The desktop rule is `repeat(var(--dc), 1fr)` with explicit placement; the ≤639px rule
    // drops to two columns, flows the same markup and spans the day headings — never a horizontal
    // scroller, which is the half of the retired rail worth not repeating.
    await renderStrip();
    const grid = screen.getByTestId('wf-heat-grid');
    expect(grid).toHaveClass('wf-hstrip');
    expect(grid).toHaveStyle({ '--dc': '1' });
  });
});

describe('WindowFirstHeatStrip — what it hands the kernel', () => {
  /** Two windows, each with its OWN point set — the fixture the assertions below need. */
  const TWO = [
    stripCard(),
    stripCard({
      key: '2026-08-05:SUNRISE', date: '2026-08-05', dow: 'Wed', sunrise: true,
      label: 'Tomorrow sunrise', time: '05:20',
    }),
  ];
  const pointsFor = (name) => [{ id: 1, name, lat: 55.61, lng: -1.71, rid: 'R', r: [4] }];

  it('reads each window\'s points by KEY and at POINT_SCORE_INDEX', async () => {
    // ⚠️ The P1 seam, and the reason the point sets are a Map rather than an array.
    //
    // TWO cards, deliberately: with one, this test cannot fail. `POINT_SCORE_INDEX` is 0 and the
    // only window index is also 0, so a caller that passed the LOOP index — the exact mutation the
    // seam exists to forbid — satisfies `expect(win).toBe(POINT_SCORE_INDEX)`; and with one entry in
    // the Map, `get(card.key)` and "the first value" are indistinguishable. The second card gives
    // both a value that differs: its window index is 1, and its point set is a different array.
    //
    // What the mutation would cost: the kernel reads `r[win]`, every point carries one score at
    // index 0, so `r[1]` is undefined → NaN, and P0's deliberate "a non-finite score resolves to
    // the BOTTOM of the ramp" rule paints a full-strength dark-red field with the coverage clamp
    // untouched. A window that looks confidently terrible rather than blank.
    const first = pointsFor('Bamburgh Beach');
    const second = pointsFor('Whitby Abbey');
    await withMeasuredThumbs(160, async () => {
      await renderStrip({
        cards: TWO,
        pointSets: new Map([[`${TODAY}:SUNSET`, first], ['2026-08-05:SUNRISE', second]]),
      });
    });

    expect(drawGeo).toHaveBeenCalledTimes(2);
    expect(drawGeo.mock.calls.map((c) => c[3])).toEqual([first, second]);
    expect(drawGeo.mock.calls.map((c) => c[4])).toEqual([POINT_SCORE_INDEX, POINT_SCORE_INDEX]);
  });

  it('measures EACH card\'s own well, and scales that card\'s radius to it', async () => {
    // ⚠️ The change M1 makes to the paint, and the assertion has to make the two wells DISAGREE.
    // P2's strip was one row of equal columns, so measuring the first well and spending it on all
    // six was exactly right; in the matrix a day holding one window spans the full row on a phone,
    // which is twice the width of a paired card in the same grid — painting it at a sibling's width
    // draws it at half scale inside its own box.
    //
    // A `withMeasuredThumbs`-style stub on `Element.prototype` gives every well the same number, so
    // reverting to `const cardWidth = width` would survive it. This stubs PER WELL: the first
    // (which the hook's gate measures) answers 160 and the second answers 80.
    await withWellWidths([160, 80], async () => {
      await renderStrip({ cards: TWO });
    });

    const [, w0, h0, , , opts0] = drawGeo.mock.calls[0];
    const [, w1, h1, , , opts1] = drawGeo.mock.calls[1];
    expect(w0).toBe(160);
    expect(w1).toBe(80);
    // ONE aspect for the whole matrix — the frame is the padded box around the framed spots and the
    // cards must stay comparable — applied to each card's own width.
    const aspectNow = thumbAspect(bbox([spot()]));
    expect(h0).toBe(Math.round(160 * aspectNow));
    expect(h1).toBe(Math.round(80 * aspectNow));
    // The blur radius follows the card too, or a narrow card is drawn with a wide card's smear.
    expect(opts0.radius).toBe(Math.max(10, 160 * 0.155));
    expect(opts1.radius).toBe(Math.max(10, 80 * 0.155));
    expect(opts0.fit).toEqual(bbox([spot()]));
  });

  it('falls back to the gate\'s own measurement for a well that measures zero', async () => {
    // The degrade the per-card measurement needs: a card whose well answers 0 must still be painted
    // at SOME size, because a zero-width paint is a silent no-op — a blank canvas with nothing in
    // the console — where a slightly wrong size is a visible picture. The gate has already proved
    // the document is laid out, so its width is the honest fallback.
    //
    await withWellWidths([160, 0], async () => {
      await renderStrip({ cards: TWO });
    });

    expect(drawGeo).toHaveBeenCalledTimes(2);
    expect(drawGeo.mock.calls[1][1]).toBe(160);
    expect(drawGeo.mock.calls[1][2]).toBeGreaterThan(0);
  });

  it('hands a window with nothing scored an empty set rather than another window\'s', async () => {
    // The honest coverage case: a window every location is unscored in contributes no points, and
    // the kernel\'s own clamp then leaves the canvas transparent. The SECOND card is what makes
    // this discriminating — with only an empty Map there is no neighbouring set to fall back to, so
    // the failure the name describes is unreachable and only the `|| []` default is pinned.
    const second = pointsFor('Whitby Abbey');
    await withMeasuredThumbs(160, async () => {
      await renderStrip({
        cards: TWO,
        pointSets: new Map([['2026-08-05:SUNRISE', second]]),
      });
    });

    expect(drawGeo.mock.calls[0][3]).toEqual([]);
    expect(drawGeo.mock.calls[1][3]).toBe(second);
  });

  it('declines a canvas whose 2d context is null, rather than throwing inside an effect', async () => {
    // P0 left this guard to whoever mounts a canvas — `field`/`fit` DEREFERENCE the context — and a
    // real browser returns null under memory pressure. Unguarded, the TypeError escapes an effect
    // and takes the whole Plan pane to the error boundary: six thumbnails costing the reader every
    // window row below them.
    HTMLCanvasElement.prototype.getContext = () => null;
    await withMeasuredThumbs(160, async () => {
      await renderStrip({ cards: TWO });
    });

    expect(drawGeo).not.toHaveBeenCalled();
    expect(screen.queryAllByTestId('wf-heat-canvas')).toHaveLength(0);
    expect(screen.getByRole('button', { name: 'Tonight Sunset, 21:11, Worth it, best, nothing in reach' }))
      .toBeInTheDocument();
  });

  it('declines a cell one pixel under the floor and paints one pixel over it', async () => {
    // `MIN_THUMB_PX` is 26 and guards WIDTH, while `drawGeo` gates on both dimensions at 20 — the
    // extra pixels are the height (`width × frameAspect`, aspect floored at 0.78). Both sides of the
    // boundary, so an off-by-one is not free.
    //
    // ⚠️ The pair MOVED with the v3 aspect clamp: at 0.85 the floor was 25, and 25 × 0.78 = 19.5
    // fails `drawGeo`'s height gate, so the constant had to become 26. If this test is ever seen to
    // fail after an aspect change, the constant is what is wrong, not the test.
    await withMeasuredThumbs(26, async () => { await renderStrip(); });
    expect(drawGeo).not.toHaveBeenCalled();

    // ⚠️ UNMOUNTED before the second render, and this is the whole reason the assertion below can
    // count. The 25px strip did not paint — it left a rAF retry pending, and the retry is right to
    // keep going, because a canvas that was too small and becomes big enough SHOULD paint. Leaving
    // it mounted while the stub answers 26 lets that retry succeed against the same module-level
    // `drawGeo` mock the second strip is being counted through, so the count is 2. It reproduced
    // only under full-suite parallel load, where a frame has time to fire between the two renders —
    // the exact shape this repo has recorded before ("a green isolated run does not exonerate it").
    cleanup();
    drawGeo.mockClear();
    await withMeasuredThumbs(27, async () => { await renderStrip(); });
    expect(drawGeo).toHaveBeenCalledTimes(1);
  });

  it('hazes a low-confidence window more than a high-confidence one', async () => {
    // D3: one scalar for the haze and the badge decay, so the picture cannot look more certain than
    // the word beside it. Asserted as an ORDERING rather than against two literals, so it keeps
    // holding if `CONFIDENCE_TREATMENT`\'s numbers are retuned.
    await withMeasuredThumbs(160, async () => {
      await renderStrip({ cards: [stripCard({ confidence: 'high' })] });
    });
    const high = drawGeo.mock.calls[0][5].conf;
    drawGeo.mockClear();

    await withMeasuredThumbs(160, async () => {
      await renderStrip({ cards: [stripCard({ confidence: 'low' })] });
    });
    const low = drawGeo.mock.calls[0][5].conf;

    expect(high).toBeGreaterThan(low);
    expect(low).toBeGreaterThan(0);
  });

  it('declines to paint a canvas too small to measure, rather than throwing', async () => {
    // §5.6: `drawGeo` declines below 20px because a zero measure throws on `cv.width`, and the
    // arm\'s panes mount `display: none`. jsdom reports 0 for everything, which is exactly that
    // state — so the strip must retry rather than paint, and the words must be on screen either way.
    await renderStrip();

    expect(drawGeo).not.toHaveBeenCalled();
    expect(screen.getByTestId('wf-heat-verdict')).toHaveTextContent('Worth it');
  });
});

describe('WindowFirstHeatStrip — the geometry lifecycle', () => {
  it('waits for the topology rather than spending the measurement budget on it', async () => {
    // P0's note, and the reason `land()` is exported at all: `drawGeo` returns null for THREE
    // different reasons, and "the geometry has not loaded yet" must not be confused with "the box is
    // too small". A retry budget sized for a layout tick can expire before a lazy chunk arrives,
    // leaving blank thumbnails with no further trigger — so the wait is `load()`'s promise, and the
    // paint re-runs when it lands. With `land()` null the first pass must draw nothing, and the
    // resolve must then draw.
    // Twice, because two effects read it before the promise settles: the mount effect (to decide
    // whether the nonce is worth moving) and then the paint effect. Both must see "not loaded".
    land.mockReturnValueOnce(null).mockReturnValueOnce(null);
    await withMeasuredThumbs(160, async () => {
      await renderStrip();
    });

    // Exactly once: nothing painted while the geometry was absent, and the resolve triggered the
    // one paint. Two would mean the first pass drew a coastline it did not have.
    expect(drawGeo).toHaveBeenCalledTimes(1);
  });

  it('does not repaint when the geometry was ALREADY loaded on mount', async () => {
    // The `alreadyLoaded` guard. `load()` resolves immediately in that case, so an unconditional
    // nonce bump would redraw all six canvases a second time on every mount after the first.
    await withMeasuredThumbs(160, async () => {
      await renderStrip();
    });

    expect(drawGeo).toHaveBeenCalledTimes(1);
  });
});

describe('WindowFirstHeatStrip — when the geometry cannot be fetched', () => {
  it('drops the canvases and keeps every word', async () => {
    // `load()` rejects rather than latching failure (P0), and its own docs warn that an unhandled
    // rejection shows as a permanent loading state with no visible cause. Six black boxes would be
    // worse than none: an empty frame claims a map with nothing on it, which is a different and
    // false statement from "the picture is unavailable".
    load.mockRejectedValueOnce(new Error('chunk fetch failed'));
    await renderStrip();

    expect(screen.queryAllByTestId('wf-heat-canvas')).toHaveLength(0);
    expect(screen.getByRole('button', { name: 'Tonight Sunset, 21:11, Worth it, best, nothing in reach' }))
      .toBeInTheDocument();
    expect(screen.getByTestId('wf-heat-foot')).toHaveTextContent('poor → worth it');
  });
});

describe('thumbAspect — the frame clamps, which are this component\'s and not the kernel\'s', () => {
  // `aspect()` has no clamps of its own (§7.1), so these boundaries belong here. The v3 band is
  // 0.78–1.0, tightened from P2's 0.85–1.22: the matrix stacks two rows of cards, so every pixel of
  // thumbnail height is paid for twice down a column, and a card's picture is never taller than it
  // is wide.
  it('passes a frame already inside the band through UNCHANGED', () => {
    // Equality with the kernel's own answer, not membership of the band: `expect(x).toBeGreaterThan
    // (0.78)` passes for `return 1`, for `aspect(f) * 1.05`, and for anything else that happens to
    // land between the clamps — which is the whole interval this function is supposed to be the
    // identity on.
    const inside = bbox([{ lat: 54.0, lng: -2.0 }, { lat: 55.0, lng: -0.2 }]);
    expect(thumbAspect(inside)).toBe(aspect(inside));
    expect(aspect(inside)).toBeGreaterThan(THUMB_ASPECT_MIN);
    expect(aspect(inside)).toBeLessThan(THUMB_ASPECT_MAX);
  });

  it('⚠️ clamps the app\'s CURRENT coverage, which P2\'s wider band passed through', () => {
    // Worth knowing rather than discovering in a browser: the default `BBOX` measures 1.0118, so at
    // production coverage every card's picture is square — the ceiling is not a theoretical edge, it
    // is the shipped state. If a coverage change ever brings this back inside the band the cards get
    // slightly taller, which is the intended behaviour and not a regression.
    expect(aspect(BBOX)).toBeGreaterThan(THUMB_ASPECT_MAX);
    expect(thumbAspect(BBOX)).toBe(THUMB_ASPECT_MAX);
  });

  it('clamps a very wide frame up to the floor', () => {
    const wide = bbox([{ lat: 54.0, lng: -6.0 }, { lat: 54.1, lng: 1.0 }]);
    expect(thumbAspect(wide)).toBe(THUMB_ASPECT_MIN);
  });

  it('clamps a very tall frame down to the ceiling', () => {
    const tall = bbox([{ lat: 50.0, lng: -1.6 }, { lat: 58.0, lng: -1.5 }]);
    expect(thumbAspect(tall)).toBe(THUMB_ASPECT_MAX);
  });
});
