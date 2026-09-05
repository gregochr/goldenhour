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
  act, fireEvent, render, screen,
} from '@testing-library/react';
import { buildScoreIndex } from '../utils/locationSheet.js';
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

  it('shows an honest "Not scored yet" badge once the scores response has actually landed', async () => {
    await mount({ rating: null, scoresKnown: true });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Not scored yet');
  });

  it('shows "Loading…" instead — never the definitive "Not scored yet" — while scoresKnown is false', async () => {
    // A failed or in-flight fetch is not evidence that nothing was rated (map-tab-v2-plan.md §3 P9
    // review — `scoresKnown` was threaded to this component and never read at all).
    await mount({ rating: null, scoresKnown: false });
    expect(screen.getByTestId('map-callout-score')).toHaveTextContent('Loading…');
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

describe('MapCallout — the tide-alignment row (bundle rev 2\'s tide-chip tweak)', () => {
  let restore;
  beforeEach(() => { currentMap = makeMap(); restore = withMeasuredCard(286, 260); });
  afterEach(() => restore());

  const ALIGNED = { onTheLight: true, phrase: 'HW 19:52 · 36m before sunset' };

  it('renders the row — glyph, bold heading, and the phrase — only when onTheLight is true', async () => {
    await mount({ tideOnLight: ALIGNED });
    const row = screen.getByTestId('map-callout-tide');
    expect(row).toHaveTextContent('Tide lands on the light');
    expect(row).toHaveTextContent('HW 19:52 · 36m before sunset');
    expect(row.querySelector('svg')).toBeTruthy();
  });

  it('omits the row entirely when the tide is not on the light — never a "no alignment" line', async () => {
    await mount({ tideOnLight: { onTheLight: false, phrase: 'LW 22:10 · 3h18 after sunset' } });
    expect(screen.queryByTestId('map-callout-tide')).toBeNull();
  });

  it('omits the row when no tideOnLight fact is supplied at all (an inland location)', async () => {
    await mount({ tideOnLight: null });
    expect(screen.queryByTestId('map-callout-tide')).toBeNull();
  });

  it('omits the row when onTheLight is true but no phrase exists — never a heading with nothing under it', async () => {
    await mount({ tideOnLight: { onTheLight: true, phrase: null } });
    expect(screen.queryByTestId('map-callout-tide')).toBeNull();
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

  it('reads a night row\'s SERVED star off astroConditionsByDate — never claims "unscored" for a figure already in memory', async () => {
    // `scoreIndex` never covers night rows (it is built from solar `LocationEvaluationView` rows
    // only), but the served figure is sitting in `astroConditionsByDate` one level up — the strip
    // must read it from there rather than falling back to a blanket "unscored" (map-tab-v2-plan.md
    // §3 P9 review — this was a confirmed defect in the first cut, not a design choice).
    const astroConditionsByDate = new Map([
      [TODAY, [{ locationName: LOCATION.name, stars: 3 }, { locationName: 'Someone Else', stars: 5 }]],
    ]);
    await mount({ event: ASTRO_EVENT, rating: 3, evRows, astroConditionsByDate, scoresKnown: true });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    expect(cells[1]).toHaveTextContent('3★');
  });

  it('reads a night row\'s SERVED star off auroraResultsByDate the same way', async () => {
    const aurEvRows = [{ ...ASTRO_EVENT, id: 'aur:2026-06-15:AURORA', kind: 'aur', eventType: 'AURORA' }];
    const auroraResultsByDate = new Map([
      [TODAY, [{ locationName: LOCATION.name, stars: 4 }]],
    ]);
    await mount({
      event: aurEvRows[0], rating: 4, evRows: aurEvRows, auroraResultsByDate, scoresKnown: true,
    });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    expect(screen.getByTestId('map-callout-strip-cell')).toHaveTextContent('4★');
  });

  it('shows an honestly unscored cell for a night row this location genuinely has no served row for', async () => {
    const astroConditionsByDate = new Map([[TODAY, [{ locationName: 'Someone Else', stars: 5 }]]]);
    await mount({
      event: ASTRO_EVENT, rating: null, evRows, astroConditionsByDate, scoresKnown: true,
    });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    expect(cells[1]).toHaveTextContent('—');
    expect(cells[1]).not.toHaveTextContent('★');
  });

  it('reads "Loading…" rather than "—" for a null cell while scoresKnown is false', async () => {
    await mount({ event: ASTRO_EVENT, rating: null, evRows, scoresKnown: false });
    fireEvent.click(screen.getByTestId('map-callout-strip-toggle'));
    const cells = screen.getAllByTestId('map-callout-strip-cell');
    expect(cells[1]).toHaveTextContent('…');
    expect(cells[1]).not.toHaveTextContent('—');
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
    // The bug this pins: switching between an unaligned and an aligned window toggles the tide
    // row's presence, changing the card's own rendered height — but `location` and `map` are
    // unchanged, so neither `paint`'s identity nor `stripOpen` moved, and the anchor box stayed
    // sized for the PREVIOUS window until an unrelated pan/zoom forced a re-measure. The fix keys
    // the repaint effect on `event?.id` too, the same way it already keys on `stripOpen` — proven
    // here by counting calls to the one `map.*` read `paint()` always makes,
    // `latLngToContainerPoint`, since jsdom's faked `offsetHeight` (from `withMeasuredCard`) is a
    // constant and cannot itself show the resulting box move.
    const OTHER_EVENT = { ...SUNSET_EVENT, id: 'solar:2026-06-16:SUNSET', date: '2026-06-16' };
    const { rerender } = await mount({
      event: SUNSET_EVENT, tideOnLight: { onTheLight: false, phrase: null },
    });
    const paintSpy = vi.spyOn(currentMap, 'latLngToContainerPoint');
    const callsBeforeSwitch = paintSpy.mock.calls.length;

    await act(async () => {
      rerender(
        <MapCallout
          location={LOCATION}
          event={OTHER_EVENT}
          rating={4}
          tideOnLight={{ onTheLight: true, phrase: 'HW 19:52 · 36m before sunset' }}
        />,
      );
    });

    expect(paintSpy.mock.calls.length).toBeGreaterThan(callsBeforeSwitch);
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


