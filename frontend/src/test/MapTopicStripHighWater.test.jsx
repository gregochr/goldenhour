/**
 * The four-day strip's High-water tick (hot-topics-by-day-plan §3 M3): a sibling button over the Big seas
 * row's two columns for the day, at the served high water's place between the served sunrise and sunset
 * (clamped 4-96%), named "High water 12:40, Friday", at least 24px square, drawn by border in forced
 * colours; the strip shades the whole day when the High-water row is the window on screen. Real
 * `fourDayGrid` model, real `buildMapEvents` rows.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach,
} from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import MapTopicStrip from '../components/map/MapTopicStrip.jsx';
import { fourDayGrid } from '../utils/mapTopics.js';
import { buildMapEvents } from '../utils/mapEvents.js';
import { formatEventTimeUk } from '../utils/conversions.js';
import { FRIDAY_HW } from './bigSeasFixtures.js';

const THU = '2026-10-22';
const FRI = '2026-10-23';
const NOW = new Date('2026-10-22T09:40:00Z');

const win = (date, targetType) => ({
  key: `${date}:${targetType}`, date, targetType, label: `${date} ${targetType}`, time: '07:20',
  bestRating: 4, confidenceTier: 'high', badges: [],
});

function build(day = FRIDAY_HW, activeId = `solar:${FRI}:SUNRISE`) {
  const events = buildMapEvents({
    solarWindows: [win(THU, 'SUNSET'), win(FRI, 'SUNRISE'), win(FRI, 'SUNSET')],
    forecastDates: [THU, FRI],
    todayStr: THU,
    tomorrowStr: FRI,
    formatTimeUk: formatEventTimeUk,
    bigSeas: [day],
    now: NOW,
  });
  const grid = fourDayGrid({
    events,
    topicIndex: new Map(),
    hotTopics: [],
    scopeNames: [],
    bigSeas: [day],
    todayStr: THU,
    activeRow: events.find((e) => e.id === activeId),
    now: NOW,
  });
  return { events, grid };
}

function renderStrip(day, activeId, props = {}) {
  const { grid } = build(day, activeId);
  const onSelectHw = vi.fn();
  const utils = render(
    <MapTopicStrip
      grid={grid}
      currentTypes={['BIG_SEAS']}
      onToggleType={vi.fn()}
      onPick={vi.fn()}
      onSelectHw={onSelectHw}
      {...props}
    />,
  );
  return { onSelectHw, ...utils };
}

const tick = () => screen.getByTestId(`wf-topic-strip-hw-tick-${FRI}`);

beforeEach(() => { localStorage.setItem('mapTopicStripOpen', '1'); });

describe('MapTopicStrip: the High-water tick', () => {
  it('sits over the Big seas row\'s two columns for its day, at the served proportion along them', () => {
    renderStrip();
    const cell = screen.getByTestId(`wf-topic-strip-hw-${FRI}`);
    // Big seas is the first topic row = grid row 3; Friday is the second day = grid columns 4..6 (2 cols).
    expect(cell.style.gridArea).toBe('3 / 4 / 4 / 6');
    expect(cell.style.getPropertyValue('--x')).toBe(`${(320 / 620) * 100}%`);
    expect(cell).toHaveTextContent('HW 12:40');
  });

  it('is a SIBLING of the bars, never nested in one, and a real button with a keyboard stop', () => {
    renderStrip();
    const btn = tick();
    expect(btn.tagName).toBe('BUTTON');
    expect(btn.closest('.wf-tl-b')).toBeNull();
    expect(btn.parentElement).toBe(screen.getByTestId(`wf-topic-strip-hw-${FRI}`));
    expect(btn.parentElement.parentElement).toBe(screen.getByTestId('wf-topic-strip-grid'));
    expect(btn).not.toHaveAttribute('tabindex', '-1');
  });

  it('is named "HW 12:40, high water Friday", so the visible label is in the name', () => {
    renderStrip();
    expect(screen.getByRole('button', { name: 'HW 12:40, high water Friday' })).toBe(tick());
    // The printed label is INSIDE the button, and the name starts with it (WCAG 2.5.3).
    expect(tick().querySelector('.wf-tl-hwl')).toHaveTextContent('HW 12:40');
    expect(tick().getAttribute('aria-label').startsWith(tick().textContent)).toBe(true);
  });

  it('gives the Big seas track room (36px) so the label clears the arrow row; other rows stay 24px', () => {
    renderStrip();
    const grid = screen.getByTestId('wf-topic-strip-grid');
    expect(grid.style.gridTemplateRows).toBe('auto auto 36px');
    expect(screen.getByTestId('wf-topic-strip-label-BIG_SEAS')).toHaveClass('wf-tl-tall');
    expect(screen.getByTestId('wf-topic-bar-BIG_SEAS-2')).toHaveClass('wf-tl-tall');
  });

  it('draws the tick after the Big seas row\'s label and bars (tab order), never at the end of the grid', () => {
    renderStrip();
    const label = screen.getByTestId('wf-topic-strip-label-BIG_SEAS');
    const bars = screen.getAllByTestId(/wf-topic-bar-BIG_SEAS-/);
    const wrapper = screen.getByTestId(`wf-topic-strip-hw-${FRI}`);
    const order = (a, b) => Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
    expect(order(label, bars[0])).toBe(true);
    expect(order(bars.at(-1), wrapper)).toBe(true);
  });

  it('keeps the track plain (no template override) when no tick is drawn', () => {
    renderStrip({ ...FRIDAY_HW, daylightEnd: undefined });
    expect(screen.getByTestId('wf-topic-strip-grid').style.gridTemplateRows).toBe('');
    expect(screen.getByTestId('wf-topic-strip-label-BIG_SEAS')).not.toHaveClass('wf-tl-tall');
  });

  it('a press moves to the High-water row (focus parked on the strip first) and says where', () => {
    const { onSelectHw } = renderStrip();
    fireEvent.click(tick());
    expect(onSelectHw).toHaveBeenCalledTimes(1);
    const [row, message] = onSelectHw.mock.calls[0];
    expect(row.id).toBe(`hw:${FRI}:HIGH_WATER`);
    expect(row.kind).toBe('hw');
    expect(row.date).toBe(FRI);
    expect(message).toBe('Window moved to Friday high water 12:40');
    expect(document.activeElement).toBe(screen.getByTestId('wf-topic-strip'));
  });

  it('on the High-water row the tick is current and a press is in place (nothing moves)', () => {
    const { onSelectHw } = renderStrip(FRIDAY_HW, `hw:${FRI}:HIGH_WATER`);
    expect(tick()).toHaveAttribute('data-current', 'true');
    expect(tick()).toHaveAttribute('aria-current', 'true');
    fireEvent.click(tick());
    expect(onSelectHw).not.toHaveBeenCalled();
  });

  it('shades the WHOLE day when the High-water row is the window on screen, one column otherwise', () => {
    const { unmount } = renderStrip(FRIDAY_HW, `hw:${FRI}:HIGH_WATER`);
    const now = screen.getByTestId('wf-topic-strip-now');
    expect(now).toHaveAttribute('data-whole-day', 'true');
    // Friday's columns are 2 and 3 = grid columns 4..5, to the last row.
    expect(now.style.gridArea).toMatch(/^1 \/ 4 \/ \d+ \/ 6$/);
    unmount();
    renderStrip(FRIDAY_HW, `solar:${FRI}:SUNRISE`);
    const one = screen.getByTestId('wf-topic-strip-now');
    expect(one).not.toHaveAttribute('data-whole-day');
    expect(one.style.gridArea).toMatch(/^1 \/ 4 \/ \d+ \/ 5$/);
  });

  it('the Big seas bar over that day is "current" (selected in place) on the High-water row', () => {
    renderStrip(FRIDAY_HW, `hw:${FRI}:HIGH_WATER`);
    const seasBars = screen.getAllByTestId(/wf-topic-bar-BIG_SEAS-/);
    expect(seasBars.some((b) => b.getAttribute('data-current') === 'true')).toBe(true);
  });

  it('draws no tick without both served bounds', () => {
    renderStrip({ ...FRIDAY_HW, daylightStart: undefined });
    expect(screen.queryByTestId(`wf-topic-strip-hw-${FRI}`)).toBeNull();
  });

  it('clamps a tick near either edge of the day to 4 or 96%', () => {
    const { unmount } = renderStrip({ ...FRIDAY_HW, hwTime: '2026-10-23T06:21:00' });
    expect(screen.getByTestId(`wf-topic-strip-hw-${FRI}`).style.getPropertyValue('--x')).toBe('4%');
    unmount();
    renderStrip({ ...FRIDAY_HW, hwTime: '2026-10-23T16:39:00' });
    expect(screen.getByTestId(`wf-topic-strip-hw-${FRI}`).style.getPropertyValue('--x')).toBe('96%');
  });

  it('draws no tick while the strip is collapsed (it has no grid)', () => {
    localStorage.setItem('mapTopicStripOpen', '0');
    renderStrip();
    expect(screen.queryByTestId(`wf-topic-strip-hw-${FRI}`)).toBeNull();
  });
});

describe('the High-water tick\'s stylesheet', () => {
  const css = readFileSync(resolve(__dirname, '../index.css'), 'utf8');
  const rule = (selector) => {
    const start = css.indexOf(selector);
    return start < 0 ? '' : css.slice(start, css.indexOf('}', start) + 1);
  };

  it('the button is at least 24px wide and fills the 24px row (the hit area)', () => {
    const r = rule('.wf-tl-hw button {');
    expect(r).toMatch(/width:\s*24px/);
    expect(r).toMatch(/top:\s*0/);
    expect(r).toMatch(/bottom:\s*0/);
    // The grid rows it sits in are 24px tall.
    expect(rule('.wf-tl-grid {')).toMatch(/grid-auto-rows:\s*24px/);
  });

  it('the wrapper lets pointer events through and the button takes them', () => {
    expect(rule('.wf-tl-hw {')).toMatch(/pointer-events:\s*none/);
    expect(rule('.wf-tl-hw button {')).toMatch(/pointer-events:\s*auto/);
  });

  it('in forced colours the tick is drawn by BORDER, not by background', () => {
    const forced = css.slice(css.indexOf('@media (forced-colors: active) {\n  /* The tick is drawn by BORDER'));
    const block = forced.slice(0, forced.indexOf('.wf-tl-b { border'));
    expect(block).toMatch(/\.wf-tl-hw button::before \{[^}]*border-left:\s*2px solid CanvasText/);
    expect(block).toMatch(/\.wf-tl-hw button\[data-current='true'\]::before \{[^}]*border-left:\s*4px double Highlight/);
  });
});
