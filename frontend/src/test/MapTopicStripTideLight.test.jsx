import React from 'react';
import {
  describe, it, expect, beforeEach, afterEach, vi,
} from 'vitest';
import { render, screen, within } from '@testing-library/react';
import MapTopicStrip from '../components/map/MapTopicStrip.jsx';
import MapTopicChips from '../components/map/MapTopicChips.jsx';
import {
  fourDayGrid, markPhrase, tideLightFact, topicsOnRow,
} from '../utils/mapTopics.js';
import { buildTopicIndex } from '../utils/windowFirstTopics.js';
import { windowKey } from '../utils/windowKeys.js';

/**
 * The tide-at-the-light treatment on the four-day strip and the topic chip (Pro and Admin): marks,
 * the peak sub-line, the open edge, the detail line and the chip clause, driven by the SERVED
 * `tide.lightAlignment`. Today is Tuesday 27 Oct 2026; the fixtures are the design handoff's.
 *
 * Window, light, nearest water, kind (null: mid tide), height, as the design lists them:
 * Tue up HW 05:50, Tue down mid, Wed up HW, Wed down mid, Thu up HW, Thu down LW, Fri up HW, Fri down LW.
 */
const TUE = '2026-10-27';
const WED = '2026-10-28';
const THU = '2026-10-29';
const FRI = '2026-10-30';
const DATES = [TUE, WED, THU, FRI];

const W = [
  { date: TUE, type: 'SUNRISE', light: '06:50', a: 'HW', near: 'HW', at: '05:50', off: '1h00 before sunrise', h: '4.9 m' },
  { date: TUE, type: 'SUNSET', light: '16:42', a: 'NONE', near: 'HW', at: '18:14', off: '1h32 after sunset', h: '4.8 m' },
  { date: WED, type: 'SUNRISE', light: '06:52', a: 'HW', near: 'HW', at: '06:35', off: '17m before sunrise', h: '4.8 m' },
  { date: WED, type: 'SUNSET', light: '16:40', a: 'NONE', near: 'HW', at: '18:57', off: '2h17 after sunset', h: '4.7 m' },
  { date: THU, type: 'SUNRISE', light: '06:54', a: 'HW', near: 'HW', at: '07:20', off: '26m after sunrise', h: '4.7 m' },
  { date: THU, type: 'SUNSET', light: '16:38', a: 'LW', near: 'LW', at: '15:32', off: '1h06 before sunset', h: '0.6 m' },
  { date: FRI, type: 'SUNRISE', light: '06:56', a: 'HW', near: 'HW', at: '08:05', off: '1h09 after sunrise', h: '4.6 m' },
  { date: FRI, type: 'SUNSET', light: '16:36', a: 'LW', near: 'LW', at: '16:14', off: '22m before sunset', h: '0.7 m' },
];

const tideOf = (w) => ({
  locationName: 'North Shields',
  nearestType: w.near,
  nearestTime: w.at,
  nearestOffset: w.off,
  lightAlignment: w.a,
  nearestHeight: w.h,
  eventTime: w.light,
});

/** The EV list: every window a solar row with its served tide. `lite` strips the Pro fields as the server does. */
function events({ lite = false, noTide = false } = {}) {
  return W.map((w) => {
    let tide = tideOf(w);
    if (lite) {
      tide = {
        locationName: tide.locationName, nearestType: tide.nearestType, nearestTime: tide.nearestTime,
      };
    }
    return {
      id: `solar:${w.date}:${w.type}`,
      kind: 'solar',
      date: w.date,
      eventType: w.type,
      badges: [],
      tide: noTide ? null : tide,
    };
  });
}

const tideTopic = (date, extra = {}) => ({
  type: 'SPRING_TIDE',
  label: 'Spring tide',
  date,
  eventType: null,
  regions: [],
  locationNames: ['A', 'B'],
  tideRun: {
    dayNumber: DATES.indexOf(date) + 1, dayCount: 4, range: '4.4 m', peak: false,
  },
  ...extra,
});

/** A run Tue to Fri whose biggest VISIBLE day is Tuesday at 4.9 m (the served `peak`, window-bounded). */
function springRun(lastDate = FRI) {
  const dates = DATES.slice(0, DATES.indexOf(lastDate) + 1);
  return dates.map((d, i) => tideTopic(d, {
    tideRun: {
      dayNumber: i + 1, dayCount: dates.length, range: i === 0 ? '4.9 m' : '4.4 m', peak: i === 0,
    },
  }));
}

const surgeTopic = (date, eventType) => ({
  type: 'STORM_SURGE', label: 'Storm surge', date, eventType, regions: [], locationNames: ['A'],
});

const NOW = new Date('2026-10-27T05:00:00Z');

function gridOf({
  hotTopics, activeIndex = 6, marks = true, evs = events(),
}) {
  const active = evs[activeIndex];
  return fourDayGrid({
    events: evs,
    topicIndex: buildTopicIndex(hotTopics),
    hotTopics,
    scopeNames: [],
    todayStr: TUE,
    activeRow: active,
    now: NOW,
    tideMarks: marks,
  });
}

function renderStrip(grid, props = {}) {
  return render(
    <MapTopicStrip
      grid={grid}
      selectedType={null}
      currentTypes={[]}
      onToggleType={vi.fn()}
      onPick={vi.fn()}
      {...props}
    />,
  );
}

const marksOf = (grid, type) => grid.rows.find((r) => r.type === type).marks.map((m) => [m.column, m.align]);

beforeEach(() => { localStorage.clear(); });
afterEach(() => { localStorage.clear(); });

describe('tideLightFact and the grid model', () => {
  it('reads the served alignment: HW and LW on the light, NONE as mid tide, anything else as no fact', () => {
    expect(tideLightFact({ lightAlignment: 'HW', nearestType: 'HW' }).align).toBe('HW');
    expect(tideLightFact({ lightAlignment: 'LW', nearestType: 'LW' }).align).toBe('LW');
    expect(tideLightFact({ lightAlignment: 'NONE', nearestType: 'HW' }).align).toBeNull();
    expect(tideLightFact({ nearestType: 'HW' })).toBeNull();
    expect(tideLightFact(null)).toBeNull();
  });

  it('marks exactly the columns whose window is HW or LW on the light, never a mid-tide one', () => {
    const grid = gridOf({ hotTopics: springRun() });

    expect(marksOf(grid, 'SPRING_TIDE')).toEqual([
      [0, 'HW'], [2, 'HW'], [4, 'HW'], [5, 'LW'], [6, 'HW'], [7, 'LW'],
    ]);
  });

  it('puts the run\'s served peak day and range on the row', () => {
    const grid = gridOf({ hotTopics: springRun() });

    expect(grid.rows[0].sub).toEqual({ dow: 'Tuesday', range: '4.9 m' });
  });

  it('keeps a King tide identical but for its name', () => {
    const king = springRun().map((t) => ({ ...t, type: 'KING_TIDE', label: 'King tide' }));
    const grid = gridOf({ hotTopics: king });

    expect(marksOf(grid, 'KING_TIDE')).toEqual(marksOf(gridOf({ hotTopics: springRun() }), 'SPRING_TIDE'));
    expect(grid.rows[0].sub.range).toBe('4.9 m');
  });

  it('never draws an open edge: nothing served says a run continues past the strip', () => {
    [springRun(), springRun(THU)].forEach((run) => {
      gridOf({ hotTopics: run }).rows[0].bars.forEach((bar) => expect(bar.open).toBeUndefined());
    });
  });

  it('with no served alignment (no tide data, or a Lite reader) is today\'s row EVEN WITH tideRun present', () => {
    // The topics still carry tideRun (peak true on Tuesday): only the window's served fact licenses the
    // sub-line, so a row whose windows have no tide reads exactly as before.
    expect(springRun()[0].tideRun.peak).toBe(true);
    const noTide = gridOf({ hotTopics: springRun(), evs: events({ noTide: true }) });
    const lite = gridOf({ hotTopics: springRun(), evs: events({ lite: true }) });

    [noTide, lite].forEach((grid) => {
      expect(grid.rows[0].marks).toEqual([]);
      expect(grid.rows[0].sub).toBeUndefined();
      expect(grid.tideDetail).toBeNull();
    });
  });

  it('with tideMarks off (a LITE reader) adds nothing to the grid at all', () => {
    const grid = gridOf({ hotTopics: springRun(), marks: false });

    expect(grid.rows[0].marks).toBeUndefined();
    expect(grid.rows[0].sub).toBeUndefined();
    expect(grid.tideDetail).toBeUndefined();
  });

  it('words a bar\'s marks for its accessible name', () => {
    const grid = gridOf({ hotTopics: springRun() });
    const row = grid.rows[0];

    expect(markPhrase(row, { start: 4, end: 7 })).toBe(
      'high water on the light Thursday sunrise, 07:20, 26m after; '
      + 'low water on the light Thursday sunset, 15:32, 1h06 before; '
      + 'high water on the light Friday sunrise, 08:05, 1h09 after; '
      + 'low water on the light Friday sunset, 16:14, 22m before',
    );
    expect(markPhrase(row, { start: 1, end: 1 })).toBe('');
  });
});

describe('MapTopicStrip: the tide row', () => {
  it('draws the sub-line under the name and a HW mark with a ring on the current window', () => {
    renderStrip(gridOf({ hotTopics: springRun() }));

    expect(screen.getByTestId('wf-topic-strip-sub-SPRING_TIDE').textContent).toBe('peak Tue · 4.9 m');
    const current = screen.getByTestId('wf-topic-strip-mark-SPRING_TIDE-6');
    expect(current.textContent).toBe('HW');
    expect(current.getAttribute('data-current')).toBe('true');
    expect(current.hasAttribute('title')).toBe(false);
    expect(current.getAttribute('aria-hidden')).toBe('true');
    expect(screen.getByTestId('wf-topic-strip-mark-SPRING_TIDE-4').getAttribute('data-current')).toBeNull();
  });

  it('draws an LW mark as the outlined kind and nothing in a mid-tide column', () => {
    renderStrip(gridOf({ hotTopics: springRun() }));

    const lw = screen.getByTestId('wf-topic-strip-mark-SPRING_TIDE-7');
    expect(lw.textContent).toBe('LW');
    expect(lw.getAttribute('data-align')).toBe('LW');
    expect(screen.queryByTestId('wf-topic-strip-mark-SPRING_TIDE-1')).toBeNull();
    expect(screen.queryByTestId('wf-topic-strip-mark-SPRING_TIDE-3')).toBeNull();
  });

  it('carries the alignment in the bar\'s accessible name, because the marks are decoration', () => {
    renderStrip(gridOf({ hotTopics: springRun() }));

    const name = screen.getByTestId('wf-topic-bar-SPRING_TIDE-0').getAttribute('aria-label');
    expect(name).toContain('high water on the light Tuesday sunrise, 05:50, 1h00 before');
    expect(name).toContain('low water on the light Friday sunset, 16:14, 22m before');
  });

  it('names the peak in the label\'s accessible name', () => {
    renderStrip(gridOf({ hotTopics: springRun() }));

    expect(screen.getByTestId('wf-topic-strip-label-SPRING_TIDE').getAttribute('aria-label'))
      .toContain('Tide, peak Tue · 4.9 m');
  });

  it('writes the detail line for an aligned window, in the design\'s words', () => {
    renderStrip(gridOf({ hotTopics: springRun() }));

    expect(screen.getByTestId('wf-topic-strip-tide-detail').textContent).toBe(
      'Fri sunrise 06:56 · high water 08:05, 1h09 after · 4.6 m · North Shields',
    );
  });

  it('writes the mid-tide detail line, naming the nearest water without a height', () => {
    renderStrip(gridOf({ hotTopics: springRun(), activeIndex: 3 }));

    expect(screen.getByTestId('wf-topic-strip-tide-detail').textContent).toBe(
      'Wed sunset 16:40 · mid tide · high water 18:57, 2h17 after · North Shields',
    );
  });

  it('stepping from the aligned window to a mid-tide one removes the ring and rewrites the line', () => {
    const { rerender } = renderStrip(gridOf({ hotTopics: springRun(), activeIndex: 6 }));
    expect(screen.getByTestId('wf-topic-strip-mark-SPRING_TIDE-6').getAttribute('data-current')).toBe('true');

    rerender(
      <MapTopicStrip
        grid={gridOf({ hotTopics: springRun(), activeIndex: 3 })}
        selectedType={null}
        currentTypes={[]}
        onToggleType={vi.fn()}
        onPick={vi.fn()}
      />,
    );

    expect(screen.getByTestId('wf-topic-strip-mark-SPRING_TIDE-6').getAttribute('data-current')).toBeNull();
    expect(screen.getByTestId('wf-topic-strip-tide-detail').textContent).toContain('mid tide');
  });

  it('rings no mark and writes no line while a night row is on the map', () => {
    const night = {
      id: 'astro:2026-10-30', kind: 'astro', date: FRI, eventType: 'NIGHT', badges: [], tide: null,
    };
    const grid = fourDayGrid({
      events: events(),
      topicIndex: buildTopicIndex(springRun()),
      hotTopics: springRun(),
      scopeNames: [],
      todayStr: TUE,
      activeRow: night,
      now: NOW,
      tideMarks: true,
    });
    // The night sits under Friday's sunset column: a solar-only ring must not light it.
    expect(grid.currentColumn).toBe(7);
    renderStrip(grid);

    expect(screen.getByTestId('wf-topic-strip-mark-SPRING_TIDE-7').getAttribute('data-current')).toBeNull();
    expect(screen.queryByTestId('wf-topic-strip-tide-detail')).toBeNull();
  });

  it('keeps the Big seas track at the normal height when only a sub-line asked for custom rows', () => {
    const seas = [{
      date: TUE, structures: [], daylightHighWater: false, hwTime: null,
    }];
    const grid = fourDayGrid({
      events: events(),
      topicIndex: buildTopicIndex(springRun()),
      hotTopics: springRun(),
      scopeNames: [],
      bigSeas: seas,
      todayStr: TUE,
      activeRow: events()[6],
      now: NOW,
      tideMarks: true,
    });
    renderStrip(grid);

    const tracks = screen.getByTestId('wf-topic-strip-grid').style.gridTemplateRows.split(' ');
    // header, header, then one px per row, then the detail line: no 36px Big seas track without ticks.
    expect(tracks.slice(0, 2)).toEqual(['auto', 'auto']);
    expect(tracks).not.toContain('36px');
    expect(tracks).toContain('32px');
    expect(tracks[tracks.length - 1]).toBe('auto');
  });

  it('writes an LW detail line in the low-water words', () => {
    renderStrip(gridOf({ hotTopics: springRun(), activeIndex: 5 }));

    expect(screen.getByTestId('wf-topic-strip-tide-detail').textContent).toBe(
      'Thu sunset 16:38 · low water 15:32, 1h06 before · 0.6 m · North Shields',
    );
  });

  it('draws no detail line when no tide row covers the window on the map', () => {
    const hot = [tideTopic(THU), tideTopic(FRI)];
    renderStrip(gridOf({ hotTopics: hot, activeIndex: 0 }));

    expect(screen.queryByTestId('wf-topic-strip-tide-detail')).toBeNull();
  });

  it('draws nothing new for a reader with no tide fact: the row is as it was', () => {
    const bare = springRun().map((t) => ({ ...t, tideRun: undefined }));
    renderStrip(gridOf({ hotTopics: bare, evs: events({ lite: true }) }));

    expect(screen.queryByTestId('wf-topic-strip-tide-detail')).toBeNull();
    expect(screen.queryByTestId('wf-topic-strip-mark-SPRING_TIDE-6')).toBeNull();
    expect(screen.getByTestId('wf-topic-strip-label-SPRING_TIDE').getAttribute('aria-label'))
      .toBe('Tide: jump to Tuesday sunrise');
  });

  it('draws the tide row exactly as before for a LITE reader: a locked door, no marks, no sub-line', () => {
    renderStrip(gridOf({ hotTopics: springRun(), marks: false }), { lockedTopicTypes: ['SPRING_TIDE'] });

    expect(screen.queryByTestId('wf-topic-strip-sub-SPRING_TIDE')).toBeNull();
    expect(screen.queryAllByTestId(/^wf-topic-strip-mark-/)).toHaveLength(0);
    expect(screen.queryByTestId('wf-topic-strip-tide-detail')).toBeNull();
  });

  it('on the phone sheet states the peak day only', () => {
    renderStrip(gridOf({ hotTopics: springRun() }), { variant: 'sheet', openByDefault: true });

    expect(screen.getByTestId('wf-topic-strip-sub-SPRING_TIDE').textContent).toBe('peak Tue');
    expect(screen.getByTestId('wf-topic-strip-tide-detail').textContent).toBe(
      'Fri sunrise 06:56 · high water 08:05, 1h09 after · 4.6 m · North Shields',
    );
  });
});

describe('MapTopicStrip: the storm surge row', () => {
  const surge = [
    surgeTopic(FRI, 'SUNRISE'), surgeTopic(FRI, 'SUNSET'),
  ];

  it('gets the marks and the detail line, but no sub-line', () => {
    renderStrip(gridOf({ hotTopics: surge }));

    expect(screen.getByTestId('wf-topic-strip-mark-STORM_SURGE-6').textContent).toBe('HW');
    expect(screen.getByTestId('wf-topic-strip-mark-STORM_SURGE-7').textContent).toBe('LW');
    expect(screen.queryByTestId('wf-topic-strip-sub-STORM_SURGE')).toBeNull();
    expect(screen.getByTestId('wf-topic-strip-tide-detail').textContent).toContain('high water 08:05');
  });

  it('draws the detail line once when a tide row and a surge row both cover the window', () => {
    renderStrip(gridOf({ hotTopics: [...springRun(), ...surge] }));

    expect(screen.getAllByTestId('wf-topic-strip-tide-detail')).toHaveLength(1);
    // Listed surge-first, the tide row still wins the line: the detail reads from the tide run's row.
    expect(screen.getByTestId('wf-topic-strip-tide-detail').getAttribute('data-type')).toBe('SPRING_TIDE');
  });

  it('is a plain bar for a reader with no tide fact', () => {
    renderStrip(gridOf({ hotTopics: surge, marks: false }));

    expect(screen.queryAllByTestId(/^wf-topic-strip-mark-/)).toHaveLength(0);
    expect(screen.queryByTestId('wf-topic-strip-tide-detail')).toBeNull();
  });
});

describe('the topic chip', () => {
  function chipsFor(index, { tideLight = true, topics = springRun() } = {}) {
    const evs = events();
    return topicsOnRow({
      row: evs[index],
      topicIndex: buildTopicIndex(topics),
      hotTopics: topics,
      scopeNames: [],
      roster: [],
      tideLight,
    });
  }

  it('reads "HW on the light" between the name and the count on an aligned window', () => {
    const chips = chipsFor(6);
    render(<MapTopicChips chips={chips} selectedType={null} onToggle={vi.fn()} />);

    const chip = screen.getByTestId('wf-topic-chip-SPRING_TIDE');
    expect(within(chip).getByTestId('wf-topic-light').textContent).toBe('HW on the light');
    // WCAG 2.5.3: the visible words are in the accessible name.
    expect(chip.getAttribute('aria-label')).toContain('HW on the light');
  });

  it('reads "LW on the light" and then "mid tide" as the window steps', () => {
    expect(chipsFor(7)[0].light.text).toBe('LW on the light');
    expect(chipsFor(3)[0].light.text).toBe('mid tide');
    expect(chipsFor(3)[0].label).toContain('mid tide');
  });

  it('carries the clause on the surge chip too', () => {
    const chips = chipsFor(6, { topics: [surgeTopic(FRI, 'SUNRISE')] });

    expect(chips[0].light.text).toBe('HW on the light');
  });

  it('has no clause for a LITE reader, or when the window carries no alignment', () => {
    const lockedChips = chipsFor(6, { tideLight: false });
    expect(lockedChips).toHaveLength(1);
    expect(lockedChips[0].light).toBeNull();
    const evs = events({ lite: true });
    const liteChips = topicsOnRow({
      row: evs[6],
      topicIndex: buildTopicIndex(springRun()),
      hotTopics: springRun(),
      scopeNames: [],
      roster: [],
      tideLight: true,
    });
    expect(liteChips).toHaveLength(1);
    const [chip] = liteChips;
    expect(chip.light).toBeNull();
    expect(chip.label).toBe('Spring tide');
  });

  it('never adds it to a night row', () => {
    const night = { id: 'astro:2026-10-30', kind: 'astro', date: FRI, tide: tideOf(W[6]) };
    const [chip] = topicsOnRow({
      row: night,
      topicIndex: new Map([[windowKey(FRI, 'SUNSET'), springRun()]]),
      hotTopics: [{ ...surgeTopic(FRI, 'NIGHT'), type: 'SPRING_TIDE', eventType: 'NIGHT' }],
      scopeNames: [],
      roster: [],
      tideLight: true,
    });
    expect(chip?.light ?? null).toBeNull();
  });
});
