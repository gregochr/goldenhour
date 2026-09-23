/**
 * `components/map/MapCallout.jsx` — the Map tab's selection callout (map-tab-v2-plan.md §3 P9).
 *
 * The anchoring ARITHMETIC (below/flip/clamp/band) is `mapCallout.test.js`'s job, against pure
 * functions with no DOM at all; this file proves the React/Leaflet host wires content correctly —
 * the served-summary-then-region-gloss reason prose, the `reachMeasured` facts row (drive/miles/
 * leave-by/dark-sky, each independently gated), the tide-topic filter, the every-window strip's
 * honest unscored cells, the two actions, the close/open wiring, and the accessibility contract
 * (a real accessible name, no focus trap, no `aria-modal`).
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen, within,
} from '@testing-library/react';
import { buildEvaluationGateIndex, buildScoreIndex, lookupForWindow } from '../utils/locationSheet.js';
import { buildRegionGlossIndex } from '../utils/mapCallout.js';

let currentMap = null;
vi.mock('react-leaflet', () => ({ useMap: () => currentMap }));

// Mutable per-test, `FiltersPopover.test.jsx`'s own pattern — defaults to desktop/tablet (286px),
// so every EXISTING test in this file (none of which mention width) is unaffected; only the phone
// describe block below (map-tab-v2-plan.md §3 P12) flips it to exercise the 266px card.
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

import MapCallout from '../components/map/MapCallout.jsx';

/** Measures every element the same fixed size — the component's own two-pass measure-then-place
 * needs a real `offsetWidth`/`offsetHeight` to place anything at all (jsdom lays nothing out). */
function withMeasuredCard(width, height) {
  const w = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
  const h = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get: () => width });
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get: () => height });
  return () => {
    if (w) Object.defineProperty(HTMLElement.prototype, 'offsetWidth', w);
    else delete HTMLElement.prototype.offsetWidth;
    if (h) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', h);
    else delete HTMLElement.prototype.offsetHeight;
  };
}

/** A Leaflet map stubbed to what this component touches — mirrors `MapLabels.test.jsx`'s own
 * `makeMap`, plus `panInside`/`flyTo`/`getZoom`, which that layer never calls. */
function makeMap({ size = { x: 800, y: 500 } } = {}) {
  const handlers = new Map();
  const wrap = document.createElement('div');
  const container = document.createElement('div');
  wrap.appendChild(container);
  document.body.appendChild(wrap);
  Object.defineProperty(container, 'offsetWidth', { value: size.x, configurable: true });
  return {
    zoom: 9,
    panInsideCalls: [],
    flyToCalls: [],
    container,
    getZoom() { return this.zoom; },
    getSize: () => size,
    getContainer: () => container,
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 100, y: (56 - lat) * 100 }),
    panInside(latlng, opts) { this.panInsideCalls.push([latlng, opts]); },
    flyTo(latlng, zoom) { this.flyToCalls.push([latlng, zoom]); },
    on(events, fn) { for (const e of events.split(' ')) handlers.set(e, [...(handlers.get(e) || []), fn]); },
    off(events, fn) {
      for (const e of events.split(' ')) {
        handlers.set(e, (handlers.get(e) || []).filter((h) => h !== fn));
      }
    },
  };
}

const TODAY = '2026-06-15';

const LOCATION = {
  id: 7,
  name: 'Bamburgh',
  lat: 55.6,
  lon: -1.7,
  regionName: 'North East',
  bortleClass: 3,
  tideType: ['HIGH'],
  locationType: ['SEASCAPE'],
};

const SUNSET_EVENT = {
  id: `solar:${TODAY}:SUNSET`,
  kind: 'solar',
  eventType: 'SUNSET',
  date: TODAY,
  label: 'Tonight sunset',
  time: '21:10',
  badges: [
    { type: 'KING_TIDE', label: 'King tide', rarityRank: 1 },
    { type: 'DUST', label: 'Saharan dust', rarityRank: 2 },
  ],
};

const ASTRO_EVENT = {
  id: `astro:${TODAY}:ASTRO`,
  kind: 'astro',
  eventType: 'ASTRO',
  date: TODAY,
  label: 'Tonight',
  time: '23:40',
  badges: [],
};

function scoreRow(overrides = {}) {
  return {
    locationId: LOCATION.id,
    locationName: LOCATION.name,
    date: TODAY,
    targetType: 'SUNSET',
    rating: 4,
    summary: 'A warm, layered sky with a clean sea horizon.',
    fierySkyPotential: 70,
    goldenHourPotential: 65,
    goldenHourStart: `${TODAY}T19:30:00Z`,
    goldenHourEnd: `${TODAY}T20:10:00Z`,
    blueHourStart: `${TODAY}T20:10:00Z`,
    blueHourEnd: `${TODAY}T20:50:00Z`,
    ...overrides,
  };
}

let frames = [];
// Saved and restored, matching `MapHeatLayer.test.jsx`. Symmetry, not a live fix: `isolate: true`
// keeps this out of every other file and `beforeEach` reinstalls the queue for every test in this one.
let originalRaf;
let originalCancel;
beforeEach(() => {
  frames = [];
  mockIsMobile = false;
  originalRaf = global.requestAnimationFrame;
  originalCancel = global.cancelAnimationFrame;
  global.requestAnimationFrame = (cb) => { frames.push(cb); return frames.length; };
  global.cancelAnimationFrame = (id) => { frames[id - 1] = null; };
});

afterEach(() => {
  global.requestAnimationFrame = originalRaf;
  global.cancelAnimationFrame = originalCancel;
  currentMap = null;
  document.body.innerHTML = '';
  vi.clearAllMocks();
});

async function mount(props = {}) {
  let result;
  await act(async () => {
    result = render(
      <MapCallout
        location={LOCATION}
        event={SUNSET_EVENT}
        rating={4}
        {...props}
      />,
    );
  });
  return result;
}

describe('MapCallout — header and verdict', () => {
  let restore;
  beforeEach(() => { currentMap = makeMap(); restore = withMeasuredCard(286, 260); });
  afterEach(() => restore());

  it('renders the location name, region and a real accessible name', async () => {
    await mount();
    const card = screen.getByTestId('map-callout');
    expect(card).toHaveTextContent('Bamburgh');
    // A real accessible name (frontend-test-standards.md: role queries where a role contract
    // exists) — not merely present text, since this is what a screen reader announces.
    expect(screen.getByRole('group', { name: /Bamburgh, selected/ })).toBe(card);
  });

  it('shows the rounded rating and the SERVED-threshold verdict word, never re-deriving it', async () => {
    await mount({ rating: 4 });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('4★ Worth it');
  });

  it('reads "Maybe" and "Poor" at the documented thresholds', async () => {
    const { unmount } = await mount({ rating: 3 });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('3★ Maybe');
    unmount();
    await mount({ rating: 2 });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('2★ Poor');
  });

  it('shows an honest "Not scored yet" badge once the rating\'s own source has answered', async () => {
    // An astro night with `scoresKnown: false` on purpose — the state `MapView` produces when a
    // night's own request has answered before the SOLAR scores have: the headline's claim follows
    // `ratingKnown` alone, so the solar fetch cannot hold it back.
    await mount({
      event: ASTRO_EVENT, rating: null, ratingKnown: true, scoresKnown: false,
    });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Not scored yet');
  });

  it('shows "Loading…" — never "Not scored yet" — while the rating\'s own source has not answered, even with the solar scores landed', async () => {
    // ⚠️ The night-step defect's exact shape. The headline used to read `scoresKnown` — the SOLAR
    // fetch's flag — for every window, so with the solar scores in and an astro night's own request
    // still in flight it said "Not scored yet" for the whole round trip. A failed or in-flight
    // fetch is not evidence that nothing was rated (the P9 phase's review found `scoresKnown`
    // threaded to this component and never read at all).
    await mount({
      event: ASTRO_EVENT, rating: null, ratingKnown: false, scoresKnown: true,
    });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Loading…');
    expect(screen.getByTestId('map-callout-score')).not.toHaveTextContent('Not scored yet');
  });

  it('defaults to "Loading…" when no ratingKnown is supplied — a caller that forgets it never gets the definitive claim', async () => {
    await mount({ rating: null, scoresKnown: true });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Loading…');
  });

  it('claims no failure it was never told of — ratingRetrying defaults to false', async () => {
    // Review T6: the default was pinned only by a comment on the test above. A caller that forgets
    // the prop gets "Loading…", never a failure nobody reported.
    await mount({ event: ASTRO_EVENT, rating: null, ratingKnown: false });
    const score = screen.getByTestId('map-callout-score');
    expect(score).toHaveTextContent('Loading…');
    expect(score).not.toHaveTextContent('Couldn’t load');
  });

  it('says "Couldn’t load — trying again" in place of "Loading…" once the rating\'s own source has failed and is asked again', async () => {
    // "Loading…" claims a load under way, and between a long outage's retries nothing is in flight;
    // "trying again" claims only that the asking goes on (`MapView.jsx`'s `ratingRetrying`).
    await mount({
      event: ASTRO_EVENT, rating: null, ratingKnown: false, ratingRetrying: true, scoresKnown: true,
    });
    const score = screen.getByTestId('map-callout-score');
    expect(score).toHaveTextContent('Couldn’t load — trying again');
    expect(score).not.toHaveTextContent('Loading…');
    expect(score).not.toHaveTextContent('Not scored yet');
  });

  it('lets an answer in hand outrank a failure — "Not scored yet" while ratingRetrying is also true', async () => {
    // A night still holding its answer while a refresh of it fails: a failure takes nothing away, so
    // the answer is what the headline says — and all it says.
    await mount({
      event: ASTRO_EVENT, rating: null, ratingKnown: true, ratingRetrying: true,
    });
    const score = screen.getByTestId('map-callout-score');
    expect(score).toHaveTextContent('Not scored yet');
    expect(score).not.toHaveTextContent('Couldn’t load');
  });

  it('never lets the failure line stand in for a rating it has', async () => {
    await mount({ event: ASTRO_EVENT, rating: 4, ratingRetrying: true });
    const score = screen.getByTestId('map-callout-score');
    expect(score).toHaveTextContent('4★ Worth it');
    expect(score).not.toHaveTextContent('Couldn’t load');
  });

  it('mounts no status region of its own — the tab\'s is the one that announces the failure', async () => {
    // Codex, #848: a region in this card is mounted by the selection, so a night that failed before
    // a place was picked arrived in it already holding the sentence, and live regions announce
    // changes. `MapView` owns the one region (`MapViewNightScoresLoading.test.jsx` pins it); a second
    // here would announce the same failure twice. By role: this card is placed, so it is in the tree.
    await mount({ event: ASTRO_EVENT, rating: null, ratingKnown: false, ratingRetrying: true });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Couldn’t load — trying again');
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('gives the verdict badge readable ink at BOTH ends of the ramp, never a fixed dark ink', async () => {
    // The temperature ramp's hot (5★) end is nearly as dark as its cold (1★/2★) end is light — a
    // hardcoded `#0F172A` passed contrast at the gold middle and failed AA at the dark-red "Poor"
    // end (map-tab-v2-plan.md §3 P9 review). `readableInkOn` is the same ink pair
    // `windowFirstSpots.spotBadgeStyle` already measures against.
    const { unmount } = await mount({ rating: 2 });
    const poorInk = screen.getByTestId('map-callout-score').style.color;
    unmount();
    await mount({ rating: 4 });
    const worthItInk = screen.getByTestId('map-callout-score').style.color;
    // Both ends must be one of the two real ink colours, and — the whole point — they must not
    // collapse to the SAME fixed value regardless of fill.
    expect([poorInk, worthItInk]).not.toEqual([worthItInk, worthItInk]);
    expect(poorInk).not.toBe('');
    expect(worthItInk).not.toBe('');
  });

  it('the verdict line reads the day-only dayLabel, not the raw label the kind chip would repeat', async () => {
    await mount({ event: { ...SUNSET_EVENT, label: 'Tonight sunset', dayLabel: 'Tonight' } });
    const verdict = screen.getByTestId('map-callout-verdict');
    expect(verdict).toHaveTextContent('Tonight · 21:10');
    // The kind chip's own "Sunset" word is a SEPARATE element (`.wf-hc-sun`), so this asserts on
    // the label span specifically rather than the whole row's flattened text.
    expect(verdict.querySelector('.wf-callout-verdict-label')).toHaveTextContent('Tonight · 21:10');
  });

  it('falls back to label when an event predates dayLabel', async () => {
    // eslint-disable-next-line no-unused-vars -- destructured only to omit it from `noDayLabel`
    const { dayLabel, ...noDayLabel } = SUNSET_EVENT;
    await mount({ event: noDayLabel });
    expect(screen.getByTestId('map-callout-verdict')).toHaveTextContent('Tonight sunset · 21:10');
  });

  it('renders subject tags as WORDS in the header subtitle, never the compact-row icon glyphs', async () => {
    await mount({ location: { ...LOCATION, locationType: ['SEASCAPE', 'WILDLIFE'] } });
    const sub = screen.getByTestId('map-callout').querySelector('.wf-callout-sub');
    expect(sub).toHaveTextContent('Seascape');
    expect(sub).toHaveTextContent('Wildlife');
    // The icon glyphs (🌊/🐾) are the compact-row form this line is explicitly NOT — see
    // `utils/locationTypes.js`'s own doc on `locationTypeIcons`.
    expect(sub.textContent).not.toMatch(/[\u{1F300}-\u{1FAFF}]/u);
  });

  it('is NOT a modal — no aria-modal, no focus trap', async () => {
    await mount();
    const card = screen.getByTestId('map-callout');
    expect(card).not.toHaveAttribute('aria-modal');
    // Tab order is not intercepted: the close button and the action buttons are ordinary
    // focusable elements with no wrapping trap logic.
    expect(screen.getByTestId('map-callout-close').tagName).toBe('BUTTON');
  });

  it('calls onClose when the ✕ is pressed', async () => {
    const onClose = vi.fn();
    await mount({ onClose });
    fireEvent.click(screen.getByTestId('map-callout-close'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('MapCallout — reason prose', () => {
  let restore;
  beforeEach(() => { currentMap = makeMap(); restore = withMeasuredCard(286, 260); });
  afterEach(() => restore());

  it('shows the location\'s own served summary when the window is scored', async () => {
    const scoreIndex = buildScoreIndex([scoreRow()]);
    await mount({ scoreIndex });
    expect(screen.getByTestId('map-callout-reason'))
      .toHaveTextContent('A warm, layered sky with a clean sea horizon.');
  });

  it('falls back to the region gloss when this location\'s own window carries no summary', async () => {
    const scoreIndex = buildScoreIndex([scoreRow({ summary: null })]);
    const regionGlossIndex = buildRegionGlossIndex([{
      date: TODAY,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{ regionName: 'North East', glossHeadline: null, glossDetail: 'A settled coastal evening across the region.' }],
      }],
    }]);
    await mount({ scoreIndex, regionGlossIndex });
    expect(screen.getByTestId('map-callout-reason'))
      .toHaveTextContent('A settled coastal evening across the region.');
  });

  it('renders no reason line at all when neither a summary nor a gloss exists', async () => {
    await mount({ scoreIndex: null, regionGlossIndex: null });
    expect(screen.queryByTestId('map-callout-reason')).toBeNull();
  });

  it('never invents a reason for a night row (no served summary source, no gloss index entry)', async () => {
    const scoreIndex = buildScoreIndex([scoreRow()]);
    const regionGlossIndex = buildRegionGlossIndex([{
      date: TODAY,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{ regionName: 'North East', glossHeadline: 'Clear', glossDetail: 'Clear all evening.' }],
      }],
    }]);
    await mount({ event: ASTRO_EVENT, scoreIndex, regionGlossIndex });
    expect(screen.queryByTestId('map-callout-reason')).toBeNull();
  });
});

describe('MapCallout — facts row (reachMeasured discipline)', () => {
  let restore;
  beforeEach(() => { currentMap = makeMap(); restore = withMeasuredCard(286, 260); });
  afterEach(() => restore());

  it('omits Drive and Leave-by entirely when the drive is unmeasured', async () => {
    await mount({ driveMinutes: null, distanceMiles: null });
    const facts = screen.queryByTestId('map-callout-facts');
    expect(facts?.textContent ?? '').not.toMatch(/Drive/);
    expect(facts?.textContent ?? '').not.toMatch(/Leave by/);
  });

  it('shows Drive with miles at HOME (distanceMiles known)', async () => {
    await mount({ driveMinutes: 65, distanceMiles: 12 });
    expect(screen.getByTestId('map-callout-facts')).toHaveTextContent('1h 5min · 12 mi');
  });

  it('shows Drive with NO miles under an away origin (distanceMiles null)', async () => {
    await mount({ driveMinutes: 65, distanceMiles: null });
    const facts = screen.getByTestId('map-callout-facts');
    // No trailing "· N mi" clause at all — not merely "no bare 'mi'", since "min" itself contains
    // that substring and would falsely pass a naive `not.toHaveTextContent('mi')` check.
    expect(facts).toHaveTextContent('Drive1h 5minDark sky');
  });

  it('shows Leave-by only when the event carries a recoverable instant (a scored solar window)', async () => {
    const scoreIndex = buildScoreIndex([scoreRow()]);
    await mount({ driveMinutes: 30, scoreIndex });
    expect(screen.getByTestId('map-callout-facts')).toHaveTextContent('Leave by');
  });

  it('omits Leave-by for a night row, which carries no recoverable event instant', async () => {
    await mount({ event: ASTRO_EVENT, driveMinutes: 30 });
    expect(screen.queryByTestId('map-callout-facts')?.textContent ?? '').not.toMatch(/Leave by/);
  });

  it('shows dark-sky with the "· dark" suffix at or below the threshold', async () => {
    await mount({ driveMinutes: null });
    expect(screen.getByTestId('map-callout-facts')).toHaveTextContent('3 · dark');
  });
});

/**
 * The tide-fit block (T5, `docs/engineering/tide-window-plan.md`) — keyed on a served tide FACT
 * existing at all (`tideOnLight != null` with a `fitPhrase`), never on `onTheLight` (bundle rev
 * 2's different, on-the-light question). `TideFitBlock.test.jsx` covers the block's own rendering
 * exhaustively; this file proves the HOST wires it correctly — the right fact, the right `want`,
 * the resolved jump target, and the interaction with the evaluation gate row beside it.
 */
describe('MapCallout — the tide-fit block (T5)', () => {
  let restore;
  beforeEach(() => { currentMap = makeMap(); restore = withMeasuredCard(286, 260); });
  afterEach(() => restore());

  const MATCH = {
    aligned: true, state: 'HIGH', onTheLight: true, phrase: 'HW 19:52 · 36m before sunset',
    fitPhrase: 'high water, falling · HW 19:52 · 36m before sunset · 3.9 m',
  };
  const MISS = {
    aligned: false, state: 'LOW', onTheLight: false, phrase: null, shortfall: 'HIGHER',
    fitPhrase: 'wants high water · low tide, rising at 05:42 · 1.2 m of 4.3 m',
  };

  it('renders the match tier — glyph, the match heading, and the fit phrase', async () => {
    await mount({ tideOnLight: MATCH });
    const block = screen.getByTestId('tide-fit-block');
    expect(block).toHaveAttribute('data-tier', 'match');
    expect(block).toHaveTextContent('Tide lands on the light');
    expect(block).toHaveTextContent('high water, falling · HW 19:52 · 36m before sunset · 3.9 m');
    expect(block.querySelector('svg')).toBeInTheDocument();
  });

  it('renders the miss tier — the miss heading and the fit phrase, no jump with no index supplied', async () => {
    await mount({ tideOnLight: MISS });
    const block = screen.getByTestId('tide-fit-block');
    expect(block).toHaveAttribute('data-tier', 'miss');
    expect(block).toHaveTextContent('Wrong water, not wrong light');
    expect(block).toHaveTextContent('wants high water · low tide, rising at 05:42 · 1.2 m of 4.3 m');
    // `location.tideType` is `['HIGH']` — the wanted set — so the denial names it even with no
    // `tideAlignmentIndex` at all (the scan finds nothing to jump to, which reads as a denial).
    expect(screen.getByTestId('tide-fit-denial'))
      .toHaveTextContent('Nothing in these four days puts high water on the light here.');
  });

  it('omits the block entirely when no tideOnLight fact is supplied at all (an inland location)', async () => {
    await mount({ tideOnLight: null });
    expect(screen.queryByTestId('tide-fit-block')).toBeNull();
  });

  // The sky component (tide gate lift, 2026-09-18, docs/engineering/tide-window-plan.md §6 Q1):
  // TideFitBlock.test.jsx covers the rendering rule exhaustively; this proves the HOST wires the
  // right combined figure — MapCallout's own `ratingRounded` (from the `rating` prop) — through as
  // `combinedRating`, not a re-derivation of its own.
  it('a miss states the served skyRating beside the header\'s own combined star', async () => {
    await mount({ tideOnLight: { ...MISS, skyRating: 4 }, rating: 3 });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('3★');
    expect(screen.getByTestId('tide-fit-sky')).toHaveTextContent('sky 4★');
  });

  it('a match whose sky score agrees with the header\'s combined star states nothing new', async () => {
    await mount({ tideOnLight: { ...MATCH, skyRating: 4 }, rating: 4 });
    expect(screen.queryByTestId('tide-fit-sky')).toBeNull();
  });

  it('a match whose sky score differs from the header\'s combined star states it', async () => {
    await mount({ tideOnLight: { ...MATCH, skyRating: 4 }, rating: 5 });
    expect(screen.getByTestId('tide-fit-sky')).toHaveTextContent('sky 4★');
  });

  it('omits the block when a fact exists but carries no fitPhrase — never a heading with nothing under it', async () => {
    await mount({ tideOnLight: { aligned: true, onTheLight: true, phrase: 'x', fitPhrase: null } });
    expect(screen.queryByTestId('tide-fit-block')).toBeNull();
  });

  it('the jump calls onSelectEv with the resolved ROW OBJECT, found by scanning the served evRows', async () => {
    const onSelectEv = vi.fn();
    const LATER_EVENT = {
      ...SUNSET_EVENT, id: 'solar:2026-06-17:SUNSET', date: '2026-06-17', dayLabel: 'Tomorrow', time: '20:25',
    };
    const tideAlignmentIndex = {
      byId: new Map([[`${LOCATION.id}|2026-06-17|SUNSET`, { aligned: true, state: 'HIGH' }]]),
      byName: new Map(),
    };
    await mount({
      tideOnLight: MISS,
      tideAlignmentIndex,
      evRows: [SUNSET_EVENT, LATER_EVENT],
      onSelectEv,
    });
    const jump = screen.getByRole('button', { name: /Next high water on the light · Tomorrow sunset 20:25/ });
    fireEvent.click(jump);
    expect(onSelectEv).toHaveBeenCalledTimes(1);
    expect(onSelectEv).toHaveBeenCalledWith(LATER_EVENT);
  });

  it('says "beyond" (the denial) when the index carries no later fit for this location', async () => {
    const tideAlignmentIndex = { byId: new Map(), byName: new Map() };
    await mount({
      tideOnLight: MISS, tideAlignmentIndex, evRows: [SUNSET_EVENT],
    });
    expect(screen.getByTestId('tide-fit-denial')).toHaveTextContent('Nothing in these four days');
    expect(screen.queryByRole('button', { name: /Next/ })).toBeNull();
  });
});

/**
 * The gate row (#866) and the tide-fit block (T5) beside it — plan §5 #6's rule: no fact prints
 * twice on one card. The gate sentence owns the offset clause; the block's miss phrase owns the
 * level, height and "wants" clause, and T1 built it to omit the offset clause for exactly this
 * reason. This is the composition test, not a re-test of either component's own content.
 *
 * ⚠️ Production no longer produces an `evaluationGate` for tide — the tide gate lift (2026-09-18,
 * docs/engineering/tide-window-plan.md §6 Q1) emptied `BriefingGatingPolicy.HARD_CONSTRAINT_REASONS`,
 * so `BriefingSlotBuilder` never words this sentence for a `TIDE_MISMATCH` standdown any more (a
 * mismatched tide reaches Claude and scores through `TideVisitor` instead). The fixture below is
 * kept as a valid GENERIC test of the composition rule — a served `evaluationGate` string still
 * renders this way for any future hard constraint the mechanism might gate again — not as a claim
 * that today's pipeline still builds one for tide.
 */
describe('MapCallout — the gate row and the tide-fit block together (T5, §5 #6)', () => {
  let restore;
  beforeEach(() => { currentMap = makeMap(); restore = withMeasuredCard(286, 260); });
  afterEach(() => restore());

  const OFFSET_CLAUSE = 'LW 20:40 · 30m before sunset';
  const GATE = `Tide not right at sunset · needs high water, mid tide instead · ${OFFSET_CLAUSE}`;
  const GATED_DAY = {
    date: TODAY,
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [{
        regionName: 'North East',
        slots: [{
          locationId: LOCATION.id, locationName: LOCATION.name, solarEventTime: `${TODAY}T20:10:00`,
          verdict: 'STANDDOWN', standdownReason: 'Tide mismatch', evaluationGate: GATE,
        }],
      }],
    }],
  };
  const evaluationGateIndex = buildEvaluationGateIndex([GATED_DAY]);
  // The block's own miss phrase, built by T1 to NEVER repeat the nearest-extreme offset clause
  // the gate sentence above already states.
  const GATED_MISS = {
    aligned: false, state: 'HIGH', onTheLight: false, phrase: OFFSET_CLAUSE, shortfall: 'LOWER',
    fitPhrase: 'wants high water, mid tide · low tide, rising at 20:40 · 1.2 m of 4.3 m',
  };

  it('renders BOTH the gate row and the tide-fit block, with the offset clause appearing exactly once in the card\'s text', async () => {
    await mount({
      rating: null, scoreIndex: null, evaluationGateIndex, tideOnLight: GATED_MISS,
    });
    const gate = screen.getByTestId('map-callout-gate');
    const block = screen.getByTestId('tide-fit-block');
    expect(gate).toHaveTextContent(GATE);
    expect(block).toHaveAttribute('data-tier', 'miss');
    expect(block).toHaveTextContent('Wrong water, not wrong light');

    const cardText = screen.getByTestId('map-callout').textContent;
    const occurrences = cardText.split(OFFSET_CLAUSE).length - 1;
    expect(occurrences).toBe(1);
    // And the "wants" clause is the block's alone — the gate sentence's own "needs …" clause
    // names the water differently ("needs high water, mid tide instead"), so this checks the
    // block's own clause appears, not a coincidental substring match against the gate's.
    expect(cardText).toContain('wants high water, mid tide');
  });
});

describe('MapCallout — topics filtered to the location', () => {
  let restore;
  beforeEach(() => { currentMap = makeMap(); restore = withMeasuredCard(286, 260); });
  afterEach(() => restore());

  it('shows a tide topic on a coastal-tidal location', async () => {
    await mount({ location: { ...LOCATION, tideType: ['HIGH'] } });
    expect(screen.getByTestId('map-callout-topics')).toHaveTextContent('King tide');
  });

  it('drops the tide topic for a location with no tide preference at all', async () => {
    await mount({ location: { ...LOCATION, tideType: [] } });
    const topics = screen.getByTestId('map-callout-topics');
    expect(topics).not.toHaveTextContent('King tide');
    expect(topics).toHaveTextContent('Saharan dust');
  });
});

describe('MapCallout — the every-window strip', () => {
  let restore;
  const evRows = [
    { ...SUNSET_EVENT },
    { ...ASTRO_EVENT },
    {
      id: 'solar:2026-06-16:SUNRISE',
      kind: 'solar',
      eventType: 'SUNRISE',
      date: '2026-06-16',
      label: 'Tomorrow sunrise',
      time: '04:40',
      badges: [],
    },
  ];
  beforeEach(() => { currentMap = makeMap(); restore = withMeasuredCard(286, 260); });
  afterEach(() => restore());

  it('is collapsed by default', async () => {
    await mount({ evRows });
    expect(screen.queryByTestId('map-callout-strip')).toBeNull();
    expect(screen.getByTestId('map-callout-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
  });

  it('expands to show one cell per EV row', async () => {
    await mount({ evRows });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    expect(screen.getAllByTestId('map-callout-strip-cell')).toHaveLength(3);
    expect(screen.getByTestId('map-callout-strip-toggle')).toHaveAttribute('aria-expanded', 'true');
  });

  it('shows a real rating for a scored SOLAR row', async () => {
    const scoreRows = [
      scoreRow(),
      scoreRow({ date: '2026-06-16', targetType: 'SUNRISE', rating: 5, summary: 'Clear dawn.' }),
    ];
    const scoreIndex = buildScoreIndex(scoreRows);
    await mount({ evRows, scoreIndex });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    expect(cells[0]).toHaveTextContent('4★');
    expect(cells[2]).toHaveTextContent('5★');
  });

  // ⚠️ From here to the on-screen block, every cell under test is one that is NOT the window on
  // screen — the default mount's SUNSET_EVENT is `cells[0]`, so `cells[1]` (astro) and `cells[2]`
  // (sunrise) read their own sources. The on-screen cell restates the headline instead, and has its
  // own block below; asserting the source rules on it would test the wrong rule.

  it('reads a night row\'s SERVED star off astroConditionsByDate — never claims "unscored" for a figure already in memory', async () => {
    // `scoreIndex` never covers night rows (it is built from solar `LocationEvaluationView` rows
    // only), but the served figure is sitting in `astroConditionsByDate` one level up — the strip
    // must read it from there rather than falling back to a blanket "unscored" (a confirmed defect
    // in the P9 phase's first cut, not a design choice).
    const astroConditionsByDate = new Map([
      [TODAY, [{ locationName: LOCATION.name, stars: 3 }, { locationName: 'Someone Else', stars: 5 }]],
    ]);
    await mount({ evRows, astroConditionsByDate, scoresKnown: true });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    expect(cells[1]).toHaveTextContent('3★');
  });

  it('reads a night row\'s SERVED star off auroraResultsByDate the same way', async () => {
    const aurRow = { ...ASTRO_EVENT, id: 'aur:2026-06-15:AURORA', kind: 'aur', eventType: 'AURORA' };
    const auroraResultsByDate = new Map([
      [TODAY, [{ locationName: LOCATION.name, stars: 4 }]],
    ]);
    await mount({ evRows: [SUNSET_EVENT, aurRow], auroraResultsByDate, scoresKnown: true });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    expect(screen.getAllByTestId('map-callout-strip-cell')[1]).toHaveTextContent('4★');
  });

  it('shows an honestly unscored cell for a night row this location genuinely has no served row for', async () => {
    const astroConditionsByDate = new Map([[TODAY, [{ locationName: 'Someone Else', stars: 5 }]]]);
    await mount({ evRows, astroConditionsByDate, scoresKnown: true });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    expect(cells[1]).toHaveTextContent('—');
    expect(cells[1]).not.toHaveTextContent('★');
  });

  it('reads "…" rather than "—" for a null SOLAR cell while the solar scores have not landed', async () => {
    await mount({ evRows, scoresKnown: false });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    // cells[2] is the SUNRISE row, which no scoreIndex rates here.
    expect(cells[2]).toHaveTextContent('…');
    expect(cells[2]).not.toHaveTextContent('—');
  });

  it('reads "—" for a null SOLAR cell once the solar scores have landed', async () => {
    await mount({ evRows, scoresKnown: true });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    expect(cells[2]).toHaveTextContent('—');
    expect(cells[2]).not.toHaveTextContent('…');
  });

  it('reads "…" for a night cell whose own night is still pending — even with the solar scores landed', async () => {
    // ⚠️ The case the strip's old note swore could not happen: "never … claiming 'unscored' while
    // a fetch is still in flight". A night cell read `scoresKnown` — the SOLAR fetch's flag — so
    // with the solar scores in and the night's preview still loading, it printed "—".
    await mount({ evRows, scoresKnown: true, pendingNightRowIds: new Set([ASTRO_EVENT.id]) });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    expect(cells[1]).toHaveTextContent('…');
    expect(cells[1]).not.toHaveTextContent('—');
  });

  it('reads "—" for a night cell once its own night has answered — whatever the solar scores are doing', async () => {
    // The other half of that decoupling: an unanswered SOLAR fetch no longer holds a night cell at
    // "…" once the night's own list has answered without this location.
    const astroConditionsByDate = new Map([[TODAY, [{ locationName: 'Someone Else', stars: 5 }]]]);
    await mount({ evRows, astroConditionsByDate, scoresKnown: false });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    expect(cells[1]).toHaveTextContent('—');
    expect(cells[1]).not.toHaveTextContent('…');
  });

  it('names its toggle and its cells as buttons, and the toggle\'s state follows the strip', async () => {
    // The role contract, asserted through roles where the card IS placed — the integration file
    // queries these by test id because its harness leaves the card unplaced and `visibility:
    // hidden`, and it points here for this.
    await mount({ evRows });
    const toggle = screen.getByRole('button', { name: 'Every event here' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(within(screen.getByTestId('map-callout-strip')).getAllByRole('button')).toHaveLength(3);
  });

  it('gives Sunrise and Sunset non-colliding 3/4-letter kind badges — never both "SUN"', async () => {
    await mount({ evRows });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    // cells[0] is the SUNSET_EVENT row, cells[2] is the SUNRISE row built above.
    expect(cells[0]).toHaveTextContent('SET');
    expect(cells[2]).toHaveTextContent('RISE');
  });

  it('a strip cell\'s visible text is dayLabel, but its title keeps the FULL label (no kind chip on the title)', async () => {
    const rowsWithDayLabel = [
      { ...SUNSET_EVENT, dayLabel: 'Tonight' },
      { ...ASTRO_EVENT },
      {
        id: 'solar:2026-06-16:SUNRISE',
        kind: 'solar',
        eventType: 'SUNRISE',
        date: '2026-06-16',
        label: 'Tomorrow sunrise',
        dayLabel: 'Tomorrow',
        time: '04:40',
        badges: [],
      },
    ];
    await mount({ evRows: rowsWithDayLabel });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    expect(cells[0].querySelector('.wf-callout-strip-date')).toHaveTextContent('Tonight');
    expect(cells[0]).not.toHaveTextContent('Tonight sunset');
    expect(cells[0]).toHaveAttribute('title', 'Tonight sunset · 21:10');
    expect(cells[2].querySelector('.wf-callout-strip-date')).toHaveTextContent('Tomorrow');
    expect(cells[2]).toHaveAttribute('title', 'Tomorrow sunrise · 04:40');
  });

  it('a strip cell falls back to label when its row predates dayLabel', async () => {
    await mount({ evRows });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    expect(cells[0].querySelector('.wf-callout-strip-date')).toHaveTextContent('Tonight sunset');
  });

  it('selecting a cell calls onSelectEv with that row, switching the window', async () => {
    const onSelectEv = vi.fn();
    await mount({ evRows, onSelectEv });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    fireEvent.click(cells[2]);
    expect(onSelectEv).toHaveBeenCalledTimes(1);
    expect(onSelectEv).toHaveBeenCalledWith(evRows[2]);
  });

  /**
   * The window menu's rescue (`hooks/useRowFocusRescue.js`), on the strip: a cell can leave the OPEN
   * strip with nobody pressing anything — the EV list is rebuilt against the clock, so last night's
   * cells go at dawn (D-14) — and the toggle, mounted for as long as the strip is open, takes the
   * focus the cell leaves behind rather than letting it fall to `<body>`.
   */
  it('hands focus to the toggle when the focused cell leaves the open strip', async () => {
    const { rerender } = await mount({ evRows });
    fireEvent.click(screen.getByRole('button', { name: /Every event here/ }));
    act(() => { screen.getAllByTestId('map-callout-strip-cell')[1].focus(); });

    await act(async () => {
      rerender(
        <MapCallout location={LOCATION} event={SUNSET_EVENT} rating={4} evRows={[evRows[0], evRows[2]]} />,
      );
    });

    const toggle = screen.getByRole('button', { name: /Every event here/ });
    expect(document.activeElement).toBe(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
  });

  it('never takes focus from a cell that still has it', async () => {
    const { rerender } = await mount({ evRows });
    fireEvent.click(screen.getByRole('button', { name: /Every event here/ }));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    act(() => { cells[1].focus(); });
    act(() => { cells[2].focus(); });

    await act(async () => {
      rerender(
        <MapCallout location={LOCATION} event={SUNSET_EVENT} rating={4} evRows={[evRows[0], evRows[2]]} />,
      );
    });

    expect(document.activeElement).toHaveAttribute('data-ev-id', evRows[2].id);
  });

  it('collapses back to default the moment the selection changes to a different location', async () => {
    const { rerender } = await mount({ evRows });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    expect(screen.getByTestId('map-callout-strip')).toBeInTheDocument();
    await act(async () => {
      rerender(
        <MapCallout
          location={{ ...LOCATION, id: 99, name: 'Whitby' }}
          event={SUNSET_EVENT}
          rating={3}
          evRows={evRows}
        />,
      );
    });
    expect(screen.queryByTestId('map-callout-strip')).toBeNull();
  });
});

describe('MapCallout — the strip\'s cell for the window on screen restates the headline', () => {
  // ⚠️ One card must not print two answers for one place and one window. The headline reads the
  // window's own request (`rating`/`ratingKnown`); every other cell reads the preview — so reading
  // the preview for THIS cell too put "Loading…" above "—", or a star above "—", or "Not scored
  // yet" above a stale star. Three review lenses found it independently.
  let restore;
  const evRows = [{ ...SUNSET_EVENT }, { ...ASTRO_EVENT }];
  beforeEach(() => { currentMap = makeMap(); restore = withMeasuredCard(286, 260); });
  afterEach(() => restore());

  const openStrip = () => {
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    return screen.getAllByTestId('map-callout-strip-cell');
  };

  it('reads "…" beside the headline\'s "Loading…" — never "—", though the preview has answered without this place', async () => {
    const astroConditionsByDate = new Map([[TODAY, [{ locationName: 'Someone Else', stars: 5 }]]]);
    await mount({
      event: ASTRO_EVENT, rating: null, ratingKnown: false, evRows, astroConditionsByDate, scoresKnown: true,
    });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Loading…');
    const cells = openStrip();
    expect(cells[1]).toHaveTextContent('…');
    expect(cells[1]).not.toHaveTextContent('—');
  });

  it('reads "…" beside "Couldn’t load — trying again" too — no answer yet, and one still being asked for', async () => {
    // A cell has room for a mark, not the words. "—" would say this place is not rated that night,
    // which a failed request is no evidence for.
    const astroConditionsByDate = new Map([[TODAY, [{ locationName: 'Someone Else', stars: 5 }]]]);
    await mount({
      event: ASTRO_EVENT,
      rating: null,
      ratingKnown: false,
      ratingRetrying: true,
      evRows,
      astroConditionsByDate,
      scoresKnown: true,
    });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Couldn’t load — trying again');
    const cells = openStrip();
    expect(cells[1]).toHaveTextContent('…');
    expect(cells[1]).not.toHaveTextContent('—');
  });

  it('shows the headline\'s own star — never the preview\'s different one', async () => {
    const astroConditionsByDate = new Map([[TODAY, [{ locationName: LOCATION.name, stars: 3 }]]]);
    await mount({ event: ASTRO_EVENT, rating: 5, evRows, astroConditionsByDate });
    const cells = openStrip();
    expect(cells[1]).toHaveTextContent('5★');
    expect(cells[1]).not.toHaveTextContent('3★');
  });

  it('reads "—" beside "Not scored yet" — even while the preview for that night is still pending', async () => {
    await mount({
      event: ASTRO_EVENT,
      rating: null,
      ratingKnown: true,
      evRows,
      pendingNightRowIds: new Set([ASTRO_EVENT.id]),
    });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Not scored yet');
    const cells = openStrip();
    expect(cells[1]).toHaveTextContent('—');
    expect(cells[1]).not.toHaveTextContent('…');
  });

  it('restates a solar headline too — its star, where the score index has none for that window', async () => {
    // No `scoreIndex`: the solar source rule alone would print "—" or "…" here, beside "4★ Worth it".
    await mount({ evRows, rating: 4, scoresKnown: true });
    const cells = openStrip();
    expect(cells[0]).toHaveTextContent('4★');
  });
});

describe('MapCallout — actions', () => {
  let restore;
  beforeEach(() => { currentMap = makeMap(); restore = withMeasuredCard(286, 260); });
  afterEach(() => restore());

  it('"Zoom to it" flies to the location, flooring the zoom at 12.6', async () => {
    currentMap.zoom = 10;
    await mount();
    fireEvent.click(screen.getByTestId('map-callout-zoom'));
    expect(currentMap.flyToCalls).toHaveLength(1);
    expect(currentMap.flyToCalls[0]).toEqual([[LOCATION.lat, LOCATION.lon], 12.6]);
  });

  it('"Zoom to it" never floors DOWN a deeper zoom the reader already has', async () => {
    currentMap.zoom = 14;
    await mount();
    fireEvent.click(screen.getByTestId('map-callout-zoom'));
    expect(currentMap.flyToCalls[0][1]).toBe(14);
  });

  it('"Open in Plan" calls the tab-moving handoff exactly once, never the peek', async () => {
    const onOpenInPlan = vi.fn();
    const onOpenSheet = vi.fn();
    await mount({ onOpenInPlan, onOpenSheet });
    fireEvent.click(screen.getByTestId('map-callout-open-in-plan'));
    expect(onOpenInPlan).toHaveBeenCalledTimes(1);
    expect(onOpenSheet).not.toHaveBeenCalled();
  });
});

describe('MapCallout — anchoring lifecycle', () => {
  let restore;
  beforeEach(() => { currentMap = makeMap(); restore = withMeasuredCard(286, 260); });
  afterEach(() => restore());

  it('pans the point into view exactly ONCE per new selection, not on every re-render', async () => {
    const { rerender } = await mount();
    expect(currentMap.panInsideCalls).toHaveLength(1);
    expect(currentMap.panInsideCalls[0][0]).toEqual([LOCATION.lat, LOCATION.lon]);
    expect(currentMap.panInsideCalls[0][1]).toEqual({ padding: [70, 150] });
    // A re-render with a DIFFERENT prop (rating) but the SAME location must not re-pan.
    await act(async () => {
      rerender(<MapCallout location={LOCATION} event={SUNSET_EVENT} rating={5} />);
    });
    expect(currentMap.panInsideCalls).toHaveLength(1);
  });

  it('pans again when the selection moves to a DIFFERENT location', async () => {
    const { rerender } = await mount();
    expect(currentMap.panInsideCalls).toHaveLength(1);
    await act(async () => {
      rerender(
        <MapCallout location={{ ...LOCATION, id: 99, name: 'Whitby' }} event={SUNSET_EVENT} rating={3} />,
      );
    });
    expect(currentMap.panInsideCalls).toHaveLength(2);
  });

  it('renders nothing with no location selected', async () => {
    const { container } = await mount({ location: null });
    expect(container.textContent).toBe('');
  });

  it('draws NO pointer at its point — the card is a plain plate now', async () => {
    // The 11px rotated-square tail was removed on 2026-09-05 at the owner's request
    // (map-tab-v2-plan.md §4.30). Pinned HERE as well as in `mapCallout.test.js` (no `tailLeft`)
    // and `mapCalloutClampCascade` (no stylesheet rule) because this is the only one of the three
    // that sees the RENDERED card: a pointer re-added under any other class, or with inline styles
    // and no class at all, would pass both of the others. The card is placed (`box` is non-null,
    // via `withMeasuredCard`), which is the state the tail used to render in.
    await mount();
    const card = screen.getByTestId('map-callout');
    expect(card.querySelector('[class*="tail"]')).toBeNull();
    // The card's own children are its content — no decorative element ahead of the body.
    expect(card.firstElementChild).toHaveClass('wf-callout-body');
    // Nothing absolutely positioned outside the plate: the tail's whole mechanism was a child
    // pulled to a negative offset, so no child may carry one.
    for (const child of card.children) {
      expect(child.style.top.startsWith('-')).toBe(false);
      expect(child.style.bottom.startsWith('-')).toBe(false);
    }
  });

  it('re-measures the anchor when the ACTIVE EVENT changes, even though location/map do not (regression: adversarial review on the tide-chip PR)', async () => {
    // The bug this pins: switching between a window with no tide fact and one with a served fact
    // toggles the tide-fit block's presence, changing the card's own rendered height — but
    // `location` and `map` are unchanged, so neither `paint`'s identity nor `stripOpen` moved, and
    // the anchor box stayed sized for the PREVIOUS window until an unrelated pan/zoom forced a
    // re-measure. The fix keys the repaint effect on `event?.id` too, the same way it already keys
    // on `stripOpen` — proven here by counting calls to the one `map.*` read `paint()` always
    // makes, `latLngToContainerPoint`, since jsdom's faked `offsetHeight` (from
    // `withMeasuredCard`) is a constant and cannot itself show the resulting box move.
    const OTHER_EVENT = { ...SUNSET_EVENT, id: 'solar:2026-06-16:SUNSET', date: '2026-06-16' };
    const { rerender } = await mount({ event: SUNSET_EVENT, tideOnLight: null });
    const paintSpy = vi.spyOn(currentMap, 'latLngToContainerPoint');
    const callsBeforeSwitch = paintSpy.mock.calls.length;

    await act(async () => {
      rerender(
        <MapCallout
          location={LOCATION}
          event={OTHER_EVENT}
          rating={4}
          tideOnLight={{
            aligned: true, onTheLight: true, phrase: 'HW 19:52 · 36m before sunset',
            fitPhrase: 'high water, falling · HW 19:52 · 36m before sunset · 3.9 m',
          }}
        />,
      );
    });

    expect(paintSpy.mock.calls.length).toBeGreaterThan(callsBeforeSwitch);
  });

  it.each([
    ['a failure', { ratingRetrying: true }],
    ['the night\'s answer, without this place', { ratingKnown: true }],
    ['a preview\'s star arriving', { rating: 4 }],
  ])('re-measures the anchor when the headline changes in place — %s (review B1/C5)', async (_, next) => {
    // The same bug one level down: no new window, no new selection, but the verdict row rewritten,
    // and the row wraps — "Couldn’t load — trying again" (≈207px) always adds a line to it. Each
    // case moves exactly one of the three inputs, so each one's dependency is pinned on its own.
    // Counted the same way as the test above: jsdom's faked height cannot show the box move.
    const base = {
      location: LOCATION, event: ASTRO_EVENT, rating: null, ratingKnown: false, ratingRetrying: false,
    };
    const { rerender } = await mount(base);
    const paintSpy = vi.spyOn(currentMap, 'latLngToContainerPoint');
    const callsBeforeChange = paintSpy.mock.calls.length;

    await act(async () => { rerender(<MapCallout {...base} {...next} />); });

    expect(paintSpy.mock.calls.length).toBeGreaterThan(callsBeforeChange);
  });

  it('re-measures the anchor when tideStripHeight changes — the strip toggling open/collapsed underneath the card (T7 follow-up, Codex P1)', async () => {
    // The bug this pins: `MapCallout`'s own placement band treats the tide strip as one of its
    // floor/ceiling bars (`BAND_BAR_SELECTOR`, T7), but nothing in this component's own repaint
    // triggers ever fired when the STRIP's own rect changed — a reader who selected a location
    // while the strip was collapsed, then opened it, kept the collapsed band boundary while the
    // strip's real rect grew underneath the card, until an unrelated pan/zoom forced a re-measure.
    // Counted the same way as the two tests above: jsdom's faked height cannot show the box move,
    // but `paint()` always reads `latLngToContainerPoint` once per run, so a rising call count is
    // proof the repaint fired.
    const { rerender } = await mount({ tideStripHeight: 34 });
    const paintSpy = vi.spyOn(currentMap, 'latLngToContainerPoint');
    const callsBeforeResize = paintSpy.mock.calls.length;

    await act(async () => {
      rerender(<MapCallout location={LOCATION} event={SUNSET_EVENT} rating={4} tideStripHeight={166} />);
    });

    expect(paintSpy.mock.calls.length).toBeGreaterThan(callsBeforeResize);
  });

  it('re-measures the anchor when the strip appears/disappears underneath the card, not only when it resizes', async () => {
    // The `null` half of the same contract — `MapTideStrip` reports `null` on unmount (the strip
    // going from visible to invisible, or vice versa), which must retrigger a repaint exactly like
    // a real resize does, since the strip stops or starts being one of `paint()`'s bars either way.
    const { rerender } = await mount({ tideStripHeight: null });
    const paintSpy = vi.spyOn(currentMap, 'latLngToContainerPoint');
    const callsBeforeAppear = paintSpy.mock.calls.length;

    await act(async () => {
      rerender(<MapCallout location={LOCATION} event={SUNSET_EVENT} rating={4} tideStripHeight={166} />);
    });

    expect(paintSpy.mock.calls.length).toBeGreaterThan(callsBeforeAppear);
  });
});

describe('MapCallout — the tide strip is a band floor too (tide-window-plan.md T7)', () => {
  beforeEach(() => { currentMap = makeMap(); });

  // The strip is no longer covered transitively via `.wf-map-chrome-bl` once it moves to its own
  // phone-only mount (T7) — `BAND_BAR_SELECTOR` in `MapCallout.jsx` carries its own
  // `[data-testid="wf-tide-strip"]` entry for exactly that reason. `card.style.maxHeight` is the
  // one rendered value that exposes `frame.band` (`{band.bot - band.top}px`, set unconditionally
  // from `frame`, never from the measured `box`/`placement` — see the component's own comment on
  // that style), so a real chrome sibling with that testid moving the band is observable without
  // needing `withMeasuredCard` at all.
  it('clamps the placement band above a real strip element, not merely above the counts footer', async () => {
    vi.spyOn(currentMap.container, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, width: 800, height: 500,
    });
    const strip = document.createElement('div');
    strip.setAttribute('data-testid', 'wf-tide-strip');
    currentMap.container.parentElement.appendChild(strip);
    // Lower half of the 500px-tall frame, comfortably over `calloutBand`'s own ≥50%-frame-width
    // floor/ceiling test (780/800 ≈ 97.5%) — the phone strip's real `left:8px; right:8px` shape.
    vi.spyOn(strip, 'getBoundingClientRect').mockReturnValue({
      left: 10, right: 790, top: 400, bottom: 450, width: 780, height: 50,
    });

    await mount();

    // No other chrome bar is present, so `band.top` stays the default 8px floor; `band.bot` is
    // `min(frameHeight - 8, strip.top - 8)` = `min(492, 392)` = 392 → maxHeight 384px. Without the
    // strip counted at all it would be `492 - 8` = 484px (the sibling "no strip" test below).
    const card = screen.getByTestId('map-callout');
    expect(card.style.maxHeight).toBe('384px');
  });

  it('is a no-op with no strip in the DOM — the band falls back to the frame\'s own floor', async () => {
    vi.spyOn(currentMap.container, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, width: 800, height: 500,
    });
    await mount();
    const card = screen.getByTestId('map-callout');
    expect(card.style.maxHeight).toBe('484px');
  });
});

describe('MapCallout — phone width (map-tab-v2-plan.md §3 P12, README §7: "286px (266px mobile)")', () => {
  let restore;
  beforeEach(() => { currentMap = makeMap(); restore = withMeasuredCard(286, 260); });
  afterEach(() => restore());

  it('renders at 286px on desktop/tablet', async () => {
    mockIsMobile = false;
    await mount();
    expect(screen.getByTestId('map-callout').style.width).toBe('286px');
  });

  it('renders at 266px on the phone — the same card, narrower, not a second component', async () => {
    mockIsMobile = true;
    await mount();
    expect(screen.getByTestId('map-callout').style.width).toBe('266px');
  });

  it('the every-window strip still defaults collapsed on the phone, exactly as it does everywhere else', async () => {
    // README §7: collapsed by default because the expanded strip's ~427px height is why it
    // collapses on a phone at all — but the default itself is universal (`stripOpen`'s own
    // `useState(false)`), not a phone-specific gate this component adds.
    mockIsMobile = true;
    await mount();
    expect(screen.queryByTestId('map-callout-strip')).not.toBeInTheDocument();
    expect(screen.getByTestId('map-callout-strip-toggle')).toHaveAttribute('aria-expanded', 'false');
  });

  it('the strip still expands on tap on the phone — collapsed by default, not fixed shut', async () => {
    mockIsMobile = true;
    await mount();
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    expect(screen.getByTestId('map-callout-strip')).toBeInTheDocument();
  });
});

/**
 * Increment §1 — the clamped prose is the ROUTE, not a dead end.
 *
 * <p><b>What breaks if these fail:</b> a real ~90-word Claude narrative clamps to three lines and
 * ends in three dots with nothing to click. The clamp itself is right (the card must not cover the
 * ground it is describing); clamping into nothing is not.
 */
describe('MapCallout — the reason routes into the location sheet (increment §1)', () => {
  let restore;
  beforeEach(() => { currentMap = makeMap(); restore = withMeasuredCard(286, 260); });
  afterEach(() => restore());

  const LONG = 'A deep bank of altocumulus is drifting east off the Cheviots through the '
    + 'afternoon, thinning as it goes, and the tail of it should be sitting right over the '
    + 'western horizon as the sun drops. The sea horizon itself is clean, which is the half that '
    + 'matters most here, and there is enough mid-level canvas overhead to take colour once the '
    + 'light starts coming in underneath it.';

  it('is a BUTTON, and opens the sheet OVER the map — never the tab-moving route', async () => {
    // ⚠️ The two routes into one sheet are deliberately different props. The prose is a peek: the
    // map stays behind it, so the reader can back out to the selection they pressed it from.
    // `onOpenInPlan` (the actions row's button, which names the Plan tab) must not fire from here.
    const onOpenSheet = vi.fn();
    const onOpenInPlan = vi.fn();
    const scoreIndex = buildScoreIndex([scoreRow({ summary: LONG })]);
    await mount({ scoreIndex, onOpenSheet, onOpenInPlan });
    const reason = screen.getByTestId('map-callout-reason');
    expect(reason.tagName).toBe('BUTTON');
    fireEvent.click(reason);
    expect(onOpenSheet).toHaveBeenCalledTimes(1);
    expect(onOpenInPlan).not.toHaveBeenCalled();
  });

  it('takes focus on the press, so the sheet has a return address to restore to', async () => {
    // ⚠️ macOS/iOS Safari do not focus a `<button>` on click. `useDialogFocus` captures
    // `document.activeElement` when the sheet mounts and restores it on close, so without the
    // explicit focus the peek's whole point — backing out to where you were — degrades to <body>.
    const scoreIndex = buildScoreIndex([scoreRow({ summary: LONG })]);
    await mount({ scoreIndex, onOpenSheet: vi.fn() });
    const reason = screen.getByTestId('map-callout-reason');
    fireEvent.click(reason);
    expect(document.activeElement).toBe(reason);
  });

  it('captions the route, and the caption is NOT inside the clamped box', async () => {
    // ⚠️ The increment's load-bearing implementation note. `-webkit-line-clamp` needs
    // `display: -webkit-box`; putting it on the BUTTON would clamp the caption away with the prose
    // (and be silently killed by any later `display: block` rule). The structural assertion is what
    // survives a stylesheet edit — jsdom does not implement `-webkit-line-clamp` at all.
    const scoreIndex = buildScoreIndex([scoreRow({ summary: LONG })]);
    await mount({ scoreIndex });
    const reason = screen.getByTestId('map-callout-reason');
    const clamped = reason.querySelector('.wf-callout-reason-text');
    expect(clamped).not.toBeNull();
    expect(clamped.textContent).toBe(LONG);
    // The caption is a SIBLING of the clamped box, never a descendant of it.
    const caption = reason.querySelector('.wf-callout-reason-more');
    expect(caption.textContent).toContain('Four days here');
    expect(clamped.contains(caption)).toBe(false);
  });

  it('names its destination accessibly — the caption alone names nothing', async () => {
    const scoreIndex = buildScoreIndex([scoreRow({ summary: LONG })]);
    await mount({ scoreIndex });
    // The visible caption is `aria-hidden`; the accessible name opens with the place, which is what
    // a speech-input user says (2.5.3).
    expect(screen.getByRole('button', { name: /Bamburgh — four days here/ }))
      .toBe(screen.getByTestId('map-callout-reason'));
  });
});

/**
 * The evaluation gate on the callout — the same served sentence the location sheet prints, above
 * the reason prose, so the card and the sheet it opens never disagree about one window.
 */
describe('MapCallout — evaluation gate', () => {
  let restore;
  beforeEach(() => { currentMap = makeMap(); restore = withMeasuredCard(286, 260); });
  afterEach(() => restore());

  const GATE = "Tide not right at sunset · needs high water, mid tide instead · LW 20:40 · 30m before sunset";
  const GATED_DAY = {
    date: TODAY,
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [{
        regionName: 'North East',
        glossHeadline: null,
        glossDetail: 'A settled coastal evening across the region.',
        slots: [{
          locationId: LOCATION.id, locationName: LOCATION.name, solarEventTime: `${TODAY}T20:10:00`,
          verdict: 'STANDDOWN', standdownReason: 'Tide mismatch', evaluationGate: GATE,
        }],
      }],
    }],
  };
  const evaluationGateIndex = buildEvaluationGateIndex([GATED_DAY]);
  const regionGlossIndex = buildRegionGlossIndex([GATED_DAY]);

  it('prints the gate above the region gloss on an unrated window, and labels the gloss as the region\'s', async () => {
    await mount({ rating: null, scoreIndex: null, evaluationGateIndex, regionGlossIndex });
    const gate = screen.getByTestId('map-callout-gate');
    const reason = screen.getByTestId('map-callout-reason');
    expect(gate).toHaveTextContent(GATE);
    expect(reason).toHaveTextContent('A settled coastal evening across the region.');
    expect(screen.getByTestId('map-callout-reason-region')).toHaveTextContent('North East sky');
    expect(gate.compareDocumentPosition(reason) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('says "Not scored" in the header — no "yet" — when the gate says nothing is coming', async () => {
    // The sheet this opens says the same two words for the same reason; "yet" above "the tide
    // is wrong" is the card telling the reader to wait for something the pipeline has ruled out.
    await mount({ rating: null, scoreIndex: null, ratingKnown: true, evaluationGateIndex });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Not scored');
    expect(screen.getByTestId('map-callout-score')).not.toHaveTextContent('yet');
  });

  it('announces the prose\'s owner first — the reason button\'s name opens with the region kicker', async () => {
    await mount({ rating: null, scoreIndex: null, evaluationGateIndex, regionGlossIndex });
    // `\s*` because the jsdom name polyfill trims each element's text at the span boundary — in a
    // browser the space survives. The claim is the ORDER: owner, then prose, then the place.
    expect(screen.getByRole('button', { name: /^North East sky ·\s*A settled coastal evening/ }))
      .toBe(screen.getByTestId('map-callout-reason'));
  });

  it('⚠️ EITHER rating source alone hides the gate — the header prop and the batch score row are different engines', async () => {
    // The header's `rating` can come from `/api/forecast` (the synchronous engine) where the score
    // index reads only the batch scores. A star in the header above "Not scored" below is the
    // contradiction the gate exists to remove, so each source alone must be enough to hide it.
    await mount({ rating: 3, scoreIndex: null, evaluationGateIndex });
    expect(screen.queryByTestId('map-callout-gate')).toBeNull();
    document.body.innerHTML = '';
    currentMap = makeMap();
    await mount({ rating: null, scoreIndex: buildScoreIndex([scoreRow({ summary: null })]), evaluationGateIndex });
    expect(screen.queryByTestId('map-callout-gate')).toBeNull();
  });

  it('prints the gate alone when there is no gloss to borrow', async () => {
    await mount({ rating: null, scoreIndex: null, evaluationGateIndex, regionGlossIndex: null });
    expect(screen.getByTestId('map-callout-gate')).toHaveTextContent(GATE);
    expect(screen.queryByTestId('map-callout-reason')).toBeNull();
  });

  it('⚠️ a rated window shows no gate — the rating is the evidence, the gate a later build\'s decision', async () => {
    await mount({ rating: 4, scoreIndex: buildScoreIndex([scoreRow()]), evaluationGateIndex, regionGlossIndex });
    expect(screen.queryByTestId('map-callout-gate')).toBeNull();
    expect(screen.getByTestId('map-callout-reason')).toHaveTextContent('A warm, layered sky');
    expect(screen.queryByTestId('map-callout-reason-region')).toBeNull();
  });

  it('never prints a gate for a night row — the gate is a solar-window fact', async () => {
    // ⚠️ Keyed on the NIGHT row's own event type, so the lookup WOULD hit and only the
    // `event.kind === 'solar'` guard stands between the index and the card. A sunset-keyed index
    // here proves nothing about the guard — the key shape alone would miss.
    const nightKeyed = buildEvaluationGateIndex([{
      date: TODAY,
      eventSummaries: [{
        targetType: 'ASTRO',
        regions: [{ regionName: 'North East', slots: [
          { locationId: LOCATION.id, locationName: LOCATION.name, solarEventTime: `${TODAY}T23:40:00`, evaluationGate: GATE },
        ] }],
      }],
    }]);
    expect(lookupForWindow(nightKeyed, LOCATION.id, LOCATION.name, TODAY, 'ASTRO'))
      .toEqual({ gate: GATE });
    await mount({ event: ASTRO_EVENT, rating: null, scoreIndex: null, evaluationGateIndex: nightKeyed, regionGlossIndex });
    expect(screen.queryByTestId('map-callout-gate')).toBeNull();
  });

  it('prints nothing when no index was supplied', async () => {
    await mount({ rating: null, scoreIndex: null });
    expect(screen.queryByTestId('map-callout-gate')).toBeNull();
  });
});
