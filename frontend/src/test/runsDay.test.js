import { describe, it, expect } from 'vitest';
import {
  dayButtonParts, daysAgo, formatClockSecondsUk, formatGbp, formatMs, formatUsd, isCycleType, laneOf, nextDay,
  previousDay, rowCostIsOpen, rowCostText, rowDurationText, rowMatchesFilter, rowStartText, stripBlock, stripFraction,
  stripScale, stripTicks, typeStyle, untilText,
} from '../utils/runsDay.js';
import { cycleDayRow, dayRow, scheduledRow } from './runsDayFixtures.js';

// The Day view's pure view-model (runs-deep-dive-plan.md D2): wording, filters, lanes and strip coordinates.

describe('types', () => {
  it('colours every served type, and an unknown one as Other', () => {
    ['OVERNIGHT', 'AFTERNOON', 'EVENING', 'ASK_READY', 'BRIEFING', 'SKY_EVAL', 'OTHER'].forEach((type) => {
      expect(typeStyle(type).swatch).toMatch(/^bg-run-/);
      expect(typeStyle(type).fill).toMatch(/^fill-run-/);
      expect(typeStyle(type).stroke).toMatch(/^stroke-run-/);
    });
    expect(typeStyle('SOMETHING_NEW')).toBe(typeStyle('OTHER'));
  });

  it('puts the three cycles in one lane, and every other type in its own', () => {
    expect(['OVERNIGHT', 'AFTERNOON', 'EVENING'].map(laneOf)).toEqual([0, 0, 0]);
    expect(['ASK_READY', 'BRIEFING', 'SKY_EVAL', 'OTHER', 'NEW'].map(laneOf)).toEqual([1, 2, 3, 4, 4]);
    expect(isCycleType('EVENING')).toBe(true);
    expect(isCycleType('BRIEFING')).toBe(false);
  });
});

describe('rowMatchesFilter', () => {
  const rows = {
    cycle: cycleDayRow(), brief: dayRow({ type: 'BRIEFING' }), partial: dayRow({ status: 'PARTIAL' }),
    failed: dayRow({ status: 'FAILED' }), running: dayRow({ status: 'RUNNING' }),
  };

  it('keeps by type, and all keeps everything', () => {
    expect(rowMatchesFilter(rows.cycle, 'cycles')).toBe(true);
    expect(rowMatchesFilter(rows.brief, 'cycles')).toBe(false);
    expect(rowMatchesFilter(rows.brief, 'briefing')).toBe(true);
    expect(rowMatchesFilter(dayRow({ type: 'ASK_READY' }), 'ask_ready')).toBe(true);
    expect(rowMatchesFilter(dayRow({ type: 'SKY_EVAL' }), 'sky_eval')).toBe(true);
    expect(rowMatchesFilter(dayRow(), 'other')).toBe(true);
    expect(rowMatchesFilter(rows.cycle, 'all')).toBe(true);
    expect(rowMatchesFilter(rows.cycle, 'whatever')).toBe(true);
  });

  it('attention is Partial and Failed only', () => {
    expect(rowMatchesFilter(rows.partial, 'attention')).toBe(true);
    expect(rowMatchesFilter(rows.failed, 'attention')).toBe(true);
    expect(rowMatchesFilter(rows.running, 'attention')).toBe(false);
    expect(rowMatchesFilter(rows.cycle, 'attention')).toBe(false);
  });
});

describe('days', () => {
  it('counts days back from today, by calendar date', () => {
    expect(daysAgo('2026-11-02', '2026-11-02')).toBe(0);
    expect(daysAgo('2026-11-01', '2026-11-02')).toBe(1);
    expect(daysAgo('2026-10-26', '2026-11-02')).toBe(7);
    // Across the 25 October clock change: still whole days.
    expect(daysAgo('2026-10-24', '2026-10-26')).toBe(2);
  });

  it('steps by date over the clock changes', () => {
    expect(previousDay('2026-10-26')).toBe('2026-10-25');
    expect(previousDay('2026-10-25')).toBe('2026-10-24');
    expect(nextDay('2026-10-24')).toBe('2026-10-25');
    expect(nextDay('2026-03-28')).toBe('2026-03-29');
    expect(previousDay('2027-01-01')).toBe('2026-12-31');
  });

  it('words the date button', () => {
    const now = new Date('2026-11-02T09:00:00Z');
    expect(dayButtonParts('2026-11-02', '2026-11-02', now)).toEqual({ word: 'Today', date: 'Mon 2 Nov' });
    expect(dayButtonParts('2026-11-01', '2026-11-02', now)).toEqual({ word: 'Yesterday', date: 'Sun 1 Nov' });
    expect(dayButtonParts('2026-10-26', '2026-11-02', now)).toEqual({ word: 'Monday', date: '26 Oct' });
    expect(dayButtonParts('2025-12-25', '2026-11-02', now).date).toBe('25 Dec 2025');
  });
});

describe('figures', () => {
  it('formats pounds and dollars to the penny and cent, or an em dash', () => {
    expect(formatGbp(1.38)).toBe('£1.38');
    expect(formatGbp(0)).toBe('£0.00');
    expect(formatGbp(null)).toBe('—');
    expect(formatUsd(1.85)).toBe('$1.85');
    expect(formatUsd(undefined)).toBe('—');
  });

  it('words a start, a duration and a cost, with ~ for estimates', () => {
    expect(rowStartText(dayRow({ startedAt: '2026-11-02T12:56:00Z' }))).toBe('12:56');
    expect(rowStartText(dayRow({ startedAt: '2026-11-02T12:56:00Z', estimated: true }))).toBe('~12:56');
    expect(rowDurationText(dayRow({ durationSeconds: 2601 }))).toBe('43m 21s');
    expect(rowDurationText(scheduledRow({ durationSeconds: 930 }))).toBe('~15m 30s');
    expect(rowDurationText(scheduledRow({ durationSeconds: null }))).toBe('—');
    expect(rowCostText(dayRow({ costGbp: 1.38 }))).toBe('£1.38');
    expect(rowCostText(scheduledRow({ costGbp: 0.7 }))).toBe('~£0.70');
    expect(rowCostText(dayRow({ costGbp: null }))).toBe('—');
  });

  it('says "so far" for an unknown or accruing cost, never for a row still to come', () => {
    expect(rowCostIsOpen(dayRow({ costComplete: false }))).toBe(true);
    expect(rowCostIsOpen(dayRow({ costComplete: true }))).toBe(false);
    expect(rowCostIsOpen(scheduledRow({ costComplete: false }))).toBe(false);
  });

  it('formats the facts clock to the second, on the UK clock', () => {
    expect(formatClockSecondsUk('2026-11-02T02:56:42Z')).toBe('02:56:42');
    expect(formatClockSecondsUk('2026-07-02T02:56:42Z')).toBe('03:56:42');
    expect(formatClockSecondsUk(null)).toBe('');
  });

  it('formats a call time', () => {
    expect(formatMs(304)).toBe('304ms');
    expect(formatMs(2_211_000)).toBe('36m 51s');
    expect(formatMs(null)).toBe('—');
  });

  it('words the wait until an instant to the minute, never under one', () => {
    const now = new Date('2026-11-02T09:26:00Z');
    expect(untilText('2026-11-02T12:39:00Z', now)).toBe('in 3h 13m');
    expect(untilText('2026-11-02T09:40:00Z', now)).toBe('in 14m');
    expect(untilText('2026-11-02T09:26:10Z', now)).toBe('in 1m');
    expect(untilText(null, now)).toBe('');
  });
});

describe('the strip\'s coordinates', () => {
  const day = { dayStartUtc: '2026-11-02T00:00:00Z', dayEndUtc: '2026-11-03T00:00:00Z' };

  it('scales the served day, midnight to midnight', () => {
    const scale = stripScale(day);
    expect(scale.span).toBe(24 * 3_600_000);
    expect(stripFraction(Date.parse('2026-11-02T12:00:00Z'), scale)).toBe(0.5);
    expect(stripFraction(Date.parse('2026-11-01T12:00:00Z'), scale)).toBe(0);
    expect(stripFraction(Date.parse('2026-11-04T12:00:00Z'), scale)).toBe(1);
  });

  it('spans 25 hours on the day the clocks go back, and 23 when they go forward', () => {
    expect(stripScale({ dayStartUtc: '2026-10-24T23:00:00Z', dayEndUtc: '2026-10-26T00:00:00Z' }).span).toBe(25 * 3_600_000);
    expect(stripScale({ dayStartUtc: '2026-03-29T00:00:00Z', dayEndUtc: '2026-03-29T23:00:00Z' }).span).toBe(23 * 3_600_000);
  });

  it('ticks every three hours on the UK clock and closes at 00:00', () => {
    const ticks = stripTicks(stripScale(day));
    expect(ticks.map((t) => t.label)).toEqual(['00:00', '03:00', '06:00', '09:00', '12:00', '15:00', '18:00', '21:00', '00:00']);
    expect(ticks.at(0).fraction).toBe(0);
    expect(ticks.at(-1).fraction).toBe(1);
  });

  it('places a finished run from its start to its end, in its lane', () => {
    const scale = stripScale(day);
    const block = stripBlock(
      cycleDayRow({ startedAt: '2026-11-02T06:00:00Z', endedAt: '2026-11-02T12:00:00Z' }), scale, scale.end,
    );
    expect(block.lane).toBe(0);
    expect(block.x0).toBe(0.25);
    expect(block.x1).toBe(0.5);
  });

  it('runs a RUNNING row to now, and a scheduled one for its median duration', () => {
    const scale = stripScale(day);
    const now = Date.parse('2026-11-02T09:00:00Z');
    const running = stripBlock(dayRow({ status: 'RUNNING', endedAt: null, startedAt: '2026-11-02T06:00:00Z' }), scale, now);
    expect(running.x1).toBe(0.375);
    const scheduled = stripBlock(
      scheduledRow({ startedAt: '2026-11-02T12:00:00Z', durationSeconds: 3600 }), scale, now,
    );
    expect(scheduled.x0).toBe(0.5);
    expect(scheduled.x1).toBeCloseTo(0.5 + 1 / 24, 6);
  });

  it('gives a run with no end a zero-width block (drawn as a sliver), and skips one with no start', () => {
    const scale = stripScale(day);
    const noEnd = stripBlock(dayRow({ endedAt: null, durationSeconds: null, status: 'FAILED' }), scale, scale.end);
    expect(noEnd.x1).toBe(noEnd.x0);
    expect(stripBlock(dayRow({ startedAt: null }), scale, scale.end)).toBeNull();
  });
});
