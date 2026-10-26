import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render, screen } from '@testing-library/react';
import { setRewind } from '../utils/rewind.js';
import BigSeasLine from '../components/map/BigSeasLine.jsx';
import { bigSeasFor, bigSeasCopy } from '../utils/bigSeas.js';
import { buildMapEvents } from '../utils/mapEvents.js';
import {
  bigSeasChipItems, bigSeasChipAriaLabel, bigSeasChipTipLines,
} from '../utils/mapLabels.js';
import {
  SATURDAY, FRIDAY_HW, THURSDAY_MORNING, solarRow, weekEvents,
} from './bigSeasFixtures.js';

/**
 * tide-light-pro-plan.md F1: a Lite reader's big-seas day, shaped as the strip (and, after B1, the
 * server) leaves it: {@code alignedEvent}, {@code gapMinutes} and {@code gapSide} null and every
 * {@code structures[].aligned} false. The waves, the window, the free high-water clock, the daylight
 * High-water row and the jumps stay; the light clause, "On the sunset ›", the aligned ring and the
 * bolt on the aligned light row go.
 */

const LITE = {
  ...SATURDAY,
  alignedEvent: null,
  gapMinutes: null,
  gapSide: null,
  structures: SATURDAY.structures.map((s) => ({ ...s, aligned: false })),
};
const LITE_FRIDAY = {
  ...FRIDAY_HW,
  structures: FRIDAY_HW.structures.map((s) => ({ ...s, aligned: false })),
};
const EVENTS = weekEvents();
const row = (id) => EVENTS.find((r) => r.id === id);
const ROSTER = new Map([
  [243, { name: 'Roker Pier & Lighthouse', lat: 54.92, lon: -1.36, regionName: 'North East', driveMinutes: 45 }],
  [28, { name: "St. Mary's Lighthouse", lat: 55.07, lon: -1.44, regionName: 'North East', driveMinutes: null }],
  [21, { name: 'Amble', lat: 55.33, lon: -1.58, regionName: 'North East', driveMinutes: 80 }],
]);

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(THURSDAY_MORNING);
});

afterEach(() => {
  setRewind(null);
  vi.useRealTimers();
});

describe('big seas over a Lite-shaped day', () => {
  it('prints waves, window and the free high-water clock, with no light clause and no action', () => {
    for (const id of ['solar:2026-10-24:SUNRISE', 'solar:2026-10-24:SUNSET']) {
      const sel = bigSeasFor([LITE], row(id), EVENTS, THURSDAY_MORNING);
      expect(sel.form).toBe('full');
      expect(sel.aligned).toBe(false);
      const copy = bigSeasCopy(sel);
      expect(copy.line1).toBe('15:30–18:15 · high water 16:58');
      expect(copy.line2).toContain('Waves 4.1 m from NNE onto 3 piers and lighthouses');
      expect(copy.action).toBeNull();
      expect(copy.line1).not.toMatch(/before|after|sunset|sunrise/);
    }
  });

  it('the compact form says nothing of the light and offers no jump', () => {
    const sel = bigSeasFor([LITE], row('solar:2026-10-22:SUNSET'), EVENTS, THURSDAY_MORNING);
    expect(sel.form).toBe('compact');
    const copy = bigSeasCopy(sel);
    expect(copy.line1).toBe('Saturday 15:30–18:15 · high water 16:58');
    expect(copy.line1).not.toMatch(/near sunset/);
    // With no aligned event the day is the same as any unaligned big-seas day: no "See …" jump.
    expect(copy.action).toBeNull();
  });

  it('keeps the daylight High-water day\'s compact jump to its High-water row', () => {
    const rows = buildMapEvents({
      solarWindows: [
        { date: '2026-10-22', targetType: 'SUNSET', label: 'Thu 22 Oct', time: '17:45', bestRating: 3 },
        { date: '2026-10-23', targetType: 'SUNRISE', label: 'Fri 23 Oct', time: '07:20', bestRating: 3 },
      ],
      forecastDates: ['2026-10-22', '2026-10-23'],
      todayStr: '2026-10-22',
      tomorrowStr: '2026-10-23',
      bigSeas: [LITE_FRIDAY],
      now: THURSDAY_MORNING,
    });
    const thursday = rows.find((r) => r.id === 'solar:2026-10-22:SUNSET');
    const sel = bigSeasFor([LITE_FRIDAY], thursday, rows, THURSDAY_MORNING);
    expect(sel.form).toBe('compact');
    expect(sel.jumpRow?.kind).toBe('hw');
    expect(bigSeasCopy(sel).action).toMatch(/^See /);
  });

  it('renders no "On the sunset ›" button on the line', () => {
    render(<BigSeasLine bigSeas={[LITE]} evRow={row('solar:2026-10-24:SUNRISE')} events={EVENTS} onSelectEv={vi.fn()} />);
    expect(screen.getByTestId('wf-big-seas-l1').textContent).toBe('15:30–18:15 · high water 16:58');
    expect(screen.queryByTestId('wf-big-seas-go')).toBeNull();
  });

  it('rings no chip on either light and prints no light clause in the chip words', () => {
    for (const type of ['SUNRISE', 'SUNSET']) {
      const items = bigSeasChipItems(LITE, ROSTER, 9, solarRow(LITE.date, type));
      expect(items).toHaveLength(3);
      expect(items.every((i) => i.ringed === false)).toBe(true);
      for (const item of items) {
        expect(bigSeasChipAriaLabel(item)).not.toMatch(/high water on the/);
        expect(bigSeasChipTipLines(item).some((l) => l.startsWith('High water'))).toBe(false);
      }
    }
  });

  it('stamps no bolt on a light row', () => {
    const rows = buildMapEvents({
      solarWindows: [
        { date: '2026-10-24', targetType: 'SUNRISE', label: 'Sat 24 Oct', time: '07:00', bestRating: 3 },
        { date: '2026-10-24', targetType: 'SUNSET', label: 'Sat 24 Oct', time: '17:45', bestRating: 3 },
      ],
      forecastDates: ['2026-10-24'],
      todayStr: '2026-10-24',
      tomorrowStr: '2026-10-25',
      bigSeas: [LITE],
      now: THURSDAY_MORNING,
    });
    expect(rows.some((r) => r.bigSeasAligned)).toBe(false);
  });

  it('keeps the daylight High-water row and its bolt-free chip ring (the free "when" fact)', () => {
    const rows = buildMapEvents({
      solarWindows: [
        { date: '2026-10-23', targetType: 'SUNRISE', label: 'Fri 23 Oct', time: '07:20', bestRating: 3 },
        { date: '2026-10-23', targetType: 'SUNSET', label: 'Fri 23 Oct', time: '17:40', bestRating: 3 },
      ],
      forecastDates: ['2026-10-23'],
      todayStr: '2026-10-23',
      tomorrowStr: '2026-10-24',
      bigSeas: [LITE_FRIDAY],
      now: THURSDAY_MORNING,
    });
    const hw = rows.filter((r) => r.kind === 'hw');
    expect(hw).toHaveLength(1);
    expect(rows.some((r) => r.bigSeasAligned)).toBe(false);

    const sel = bigSeasFor([LITE_FRIDAY], hw[0], rows, THURSDAY_MORNING);
    expect(sel.form).toBe('full');
    const items = bigSeasChipItems(LITE_FRIDAY, ROSTER, 9, hw[0]);
    // On the High-water row high water IS the event, so it rings: that is the free daylight fact.
    expect(items.every((i) => i.ringed === true)).toBe(true);
  });
});
