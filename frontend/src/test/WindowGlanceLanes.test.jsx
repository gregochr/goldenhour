import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, within, cleanup } from '@testing-library/react';
import WindowGlanceLanes from '../components/WindowGlanceLanes.jsx';
import { buildFourDayTiles, buildGlanceLanes } from '../utils/fourDayTiles.js';

/**
 * "The days ahead" lanes (days-ahead-lanes-plan.md). The model has its own suite ({@code fourDayTiles.test.js}), the
 * strip's wiring is in {@code WindowFirstHeatStrip.test.jsx}, and the stylesheet's own text is pinned in
 * {@code glanceLanesCss.test.js}; what only the markup can be wrong about is the lanes' structure, their accessible
 * names, the tap hand-off and the phone's per-day copies. jsdom loads no stylesheet, so nothing here asserts a colour
 * or a width.
 *
 * <p>TODAY is Sat 24 Oct 2026 (days Sat 24 … Tue 27).
 */
const TODAY = '2026-10-24';
const SUN = '2026-10-25';
const MON = '2026-10-26';
const TUE = '2026-10-27';
const WED = '2026-10-28';
const DATES = [TODAY, SUN, MON, TUE];

const run = (date, n, count, glance = {}) => ({
  type: 'SPRING_TIDE', label: 'Spring tide', date, eventType: null,
  tideRun: { runLabel: 'SPRING RUN', dayNumber: n, dayCount: count },
  glance: { weather: false, value: `day ${n}`, ...glance },
});
const dust = { type: 'DUST', label: 'Saharan dust', date: MON, eventType: 'SUNSET' };
const inversion = {
  type: 'INVERSION', label: 'Inversion', date: SUN, eventType: 'SUNRISE',
  glance: { value: 'dawn · 9/10 · strong', weather: true },
};
const aurora = {
  type: 'AURORA', label: 'Aurora', date: SUN, eventType: 'NIGHT',
  glance: { value: 'Kp 6', weather: true },
};

/** A strip's lanes the way it builds them. */
function lanesFor(topics, { away = [], columns = DATES, builderArgs = {} } = {}) {
  const { days } = buildFourDayTiles({ todayStr: TODAY, hotTopics: topics, ...builderArgs });
  return buildGlanceLanes(days, columns.map((date) => ({ date, live: !away.includes(date) })), topics);
}

const renderLanes = (topics, { date, onTap = () => {}, ...rest } = {}) => render(
  <WindowGlanceLanes lanes={lanesFor(topics, rest)} date={date} onTap={onTap} />,
);
const lane = (type) => screen.getAllByTestId('wf-glance-lane').find((el) => el.getAttribute('data-topic') === type);

afterEach(() => {
  cleanup();
});

describe('WindowGlanceLanes — the grid (desktop and tablet)', () => {
  it('renders nothing at all with no lanes', () => {
    const { container } = renderLanes([]);
    expect(container).toBeEmptyDOMElement();
  });

  it('is a list of buttons, each lane an item placed inline on its own columns and row', () => {
    renderLanes([run(SUN, 1, 3), run(MON, 2, 3), run(TUE, 3, 3), inversion]);
    const list = screen.getByTestId('wf-glance-lanes');
    expect(list.tagName).toBe('UL');
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveStyle({ '--ls': '2', '--le': '5', '--lr': '1' });
    expect(items[1]).toHaveStyle({ '--ls': '2', '--le': '3', '--lr': '2' });
    items.forEach((item) => expect(within(item).getAllByRole('button')).toHaveLength(1));
  });

  it('leaves --c to the colour: the lane carries it, the item does not', () => {
    renderLanes([run(SUN, 1, 2), run(MON, 2, 2)]);
    expect(lane('SPRING_TIDE').style.getPropertyValue('--c')).toBe('#6FA8B0');
    expect(lane('SPRING_TIDE').closest('li').style.getPropertyValue('--c')).toBe('');
  });

  it('prints the name, the PEAK tag once and the whole detail, never composed or truncated', () => {
    renderLanes([run(SUN, 1, 3), run(MON, 2, 3, { value: '4.6 m · HW 16:02 · a very long served detail', badge: 'PEAK' }), run(TUE, 3, 3)]);
    const el = lane('SPRING_TIDE');
    expect(el.querySelector('.wf-lane-name')).toHaveTextContent(/^Spring tide$/);
    expect(el.querySelector('.wf-lane-val')).toHaveTextContent(/^4\.6 m · HW 16:02 · a very long served detail$/);
    expect(el.querySelectorAll('.wf-lane-peak')).toHaveLength(1);
    expect(el.querySelector('.wf-lane-peak')).toHaveTextContent('PEAK');
  });

  it('draws no PEAK tag and no detail when the topic serves neither', () => {
    renderLanes([dust]);
    expect(lane('DUST').querySelector('.wf-lane-peak')).toBeNull();
    expect(lane('DUST').querySelector('.wf-lane-val')).toBeNull();
  });

  it('marks a multi-day lane, and its edges from the served dates', () => {
    renderLanes(
      [run(TODAY, 1, 5), run(SUN, 2, 5), run(MON, 3, 5), run(TUE, 4, 5), run(WED, 5, 5)],
      { away: [TODAY] },
    );
    const el = lane('SPRING_TIDE');
    expect(el).toHaveAttribute('data-span', 'multi');
    expect(el).toHaveAttribute('data-starts-before', 'true');
    expect(el).toHaveAttribute('data-ends-after', 'true');
  });

  it('marks neither edge, nor a span, on a one-day lane', () => {
    renderLanes([dust]);
    expect(lane('DUST')).not.toHaveAttribute('data-span');
    expect(lane('DUST')).not.toHaveAttribute('data-starts-before');
    expect(lane('DUST')).not.toHaveAttribute('data-ends-after');
  });

  it('hides the swatch and the PEAK tag from assistive technology', () => {
    renderLanes([run(SUN, 1, 2, { badge: 'PEAK' }), run(MON, 2, 2)]);
    const el = lane('SPRING_TIDE');
    expect(el.querySelector('.wf-lane-sw')).toHaveAttribute('aria-hidden', 'true');
    expect(el.querySelector('.wf-lane-peak')).toHaveAttribute('aria-hidden', 'true');
  });
});

describe('WindowGlanceLanes — the accessible name is built from content', () => {
  it('reads one multi-day lane with a peak, an earlier start and a jump as exactly this name', () => {
    const entry = { id: 'spring-tide:run', type: 'spring-tide', startDate: TODAY, endDate: TUE, coincidence: [], dayRanges: [] };
    renderLanes(
      [run(TODAY, 1, 4), run(SUN, 2, 4), run(MON, 3, 4, { value: '4.6 m', badge: 'PEAK' }), run(TUE, 4, 4)],
      { away: [TODAY], builderArgs: { entries: [entry], renderedEntryIds: new Set([entry.id]) } },
    );
    expect(lane('SPRING_TIDE')).toHaveAccessibleName(
      'Spring tide 4.6 m, peak Monday, on Monday, Sunday to Tuesday, began earlier, shown in Coming up',
    );
  });

  it('says where a lane that runs on past the last day goes on', () => {
    renderLanes([run(MON, 2, 4), run(TUE, 3, 4), run(WED, 4, 4)]);
    expect(lane('SPRING_TIDE')).toHaveAccessibleName(
      'Spring tide day 2, on Monday, Monday to Tuesday, continues after Tuesday, opens Coming up',
    );
  });

  it('names the lane\'s last day after "continues after", never the served runEnd', () => {
    const ext = (date, n) => ({
      ...run(date, n, 9),
      tideRun: { runLabel: 'SPRING RUN', dayNumber: n, dayCount: 9, runStart: MON, runEnd: '2026-11-02' },
    });
    renderLanes([ext(MON, 1), ext(TUE, 2)]);
    expect(lane('SPRING_TIDE')).toHaveAccessibleName(
      'Spring tide day 1, on Monday, Monday to Tuesday, continues after Tuesday, opens Coming up',
    );
    expect(lane('SPRING_TIDE')).toHaveAttribute('data-ends-after', 'true');
    expect(lane('SPRING_TIDE')).not.toHaveAttribute('data-starts-before');
  });

  it('reads a single-day lane without a day for its detail', () => {
    renderLanes([dust, { ...dust, type: 'MIST', glance: { value: 'dawn · 8/10', weather: true } }]);
    expect(lane('DUST')).toHaveAccessibleName('Dust, Monday, opens Coming up');
  });

  it('names the At a Glance window for a lane that opens one', () => {
    renderLanes([dust], { builderArgs: { openableKeys: new Set([`${MON}:SUNSET`]) } });
    expect(lane('DUST')).toHaveAccessibleName('Dust, Monday, opens the At a Glance window');
  });

  it('names the Map door for a weather lane, morning or evening', () => {
    renderLanes([inversion, { ...inversion, type: 'MIST', date: MON, eventType: 'SUNSET' }]);
    expect(lane('INVERSION')).toHaveAccessibleName(/Sunday, opens the Map that morning$/);
    expect(lane('MIST')).toHaveAccessibleName(/Monday, opens the Map that evening$/);
  });

  it('names a one-night aurora under the night it falls and puts no "WX" or "weather-driven" in the name', () => {
    renderLanes([aurora]);
    expect(lane('AURORA')).toHaveAccessibleName('Aurora Kp 6, Sunday, opens the Map that evening');
    expect(lane('AURORA').textContent).not.toContain('WX');
  });

  it('keeps a literal space between a lane\'s name block and its detail (the separator rule)', () => {
    renderLanes([run(SUN, 1, 1)]);
    const value = lane('SPRING_TIDE').querySelector('.wf-lane-val');
    expect(value.previousSibling.nodeType).toBe(Node.TEXT_NODE);
    expect(value.previousSibling.textContent).toBe(' ');
  });
});

describe('WindowGlanceLanes — a press hands the lane to onTap', () => {
  it('passes the whole lane, so the strip can pick the route', () => {
    const onTap = vi.fn();
    renderLanes([dust], { onTap });
    fireEvent.click(lane('DUST'));
    expect(onTap).toHaveBeenCalledTimes(1);
    expect(onTap).toHaveBeenCalledWith(expect.objectContaining({
      type: 'DUST', key: `DUST:${MON}@${MON}`, startCol: 3, endCol: 3,
    }));
  });
});

describe('WindowGlanceLanes — the phone\'s per-day cells', () => {
  const RUN = [
    run(TODAY, 1, 5), run(SUN, 2, 5), run(MON, 3, 5, { value: '4.6 m', badge: 'PEAK' }), run(TUE, 4, 5), run(WED, 5, 5),
  ];
  const cell = (date) => {
    cleanup();
    renderLanes(RUN, { date, away: [TODAY] });
  };

  it('renders nothing for a day no lane covers', () => {
    const { container } = renderLanes([dust], { date: SUN });
    expect(container).toBeEmptyDOMElement();
  });

  it('is a cell holding single-column lanes, not placed on a grid', () => {
    renderLanes([dust], { date: MON });
    const day = screen.getByTestId('wf-glance-lanes-day');
    expect(day).toHaveClass('wf-lanecell');
    expect(within(day).getByRole('listitem')).not.toHaveAttribute('style');
    expect(screen.queryByTestId('wf-glance-lanes')).toBeNull();
  });

  it('draws the dotted edge on the first day only when the run began earlier, and the fade only on the last day when it goes on', () => {
    cell(SUN);
    expect(lane('SPRING_TIDE')).toHaveAttribute('data-starts-before', 'true');
    expect(lane('SPRING_TIDE')).not.toHaveAttribute('data-ends-after');
    cell(MON);
    expect(lane('SPRING_TIDE')).toHaveAttribute('data-starts-before', 'true');
    expect(lane('SPRING_TIDE')).not.toHaveAttribute('data-ends-after');
    cell(TUE);
    expect(lane('SPRING_TIDE')).toHaveAttribute('data-starts-before', 'true');
    expect(lane('SPRING_TIDE')).toHaveAttribute('data-ends-after', 'true');
  });

  it('draws a plain solid lane on the one day of a one-day lane', () => {
    renderLanes([dust], { date: MON });
    expect(lane('DUST')).not.toHaveAttribute('data-starts-before');
    expect(lane('DUST')).not.toHaveAttribute('data-ends-after');
  });

  it('draws the PEAK tag once: only on the peak day\'s copy', () => {
    cell(SUN);
    expect(lane('SPRING_TIDE').querySelector('.wf-lane-peak')).toBeNull();
    cell(MON);
    expect(lane('SPRING_TIDE').querySelector('.wf-lane-peak')).toHaveTextContent('PEAK');
  });

  it('speaks each copy relative to its own day', () => {
    cell(SUN);
    expect(lane('SPRING_TIDE')).toHaveAccessibleName(
      'Spring tide 4.6 m, peak Monday, on Monday, Sunday, began earlier, continues to Tuesday, opens Coming up',
    );
    cell(MON);
    expect(lane('SPRING_TIDE')).toHaveAccessibleName(
      'Spring tide 4.6 m, peak Monday, on Monday, Monday, continues from Sunday, continues to Tuesday, opens Coming up',
    );
    cell(TUE);
    expect(lane('SPRING_TIDE')).toHaveAccessibleName(
      'Spring tide 4.6 m, peak Monday, on Monday, Tuesday, continues from Sunday, continues after Tuesday, opens Coming up',
    );
  });

  it('hands the same lane to onTap from any day\'s copy', () => {
    const onTap = vi.fn();
    renderLanes(RUN, { date: TUE, away: [TODAY], onTap });
    fireEvent.click(lane('SPRING_TIDE'));
    expect(onTap).toHaveBeenCalledWith(expect.objectContaining({ type: 'SPRING_TIDE', startCol: 2, endCol: 4 }));
  });
});
