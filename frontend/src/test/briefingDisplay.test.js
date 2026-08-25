import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  VERDICT_ORDER,
  DISPLAY_ORDER,
  isPoorSlot,
  slotSortKey,
  sortedSlotsByTidePriority,
  weatherCodeToIcon,
  msToMph,
  formatDriveDuration,
  getEventTime,
  isEventPast,
  AFTERGLOW_MS,
} from '../utils/briefingDisplay.js';

describe('briefingDisplay', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  describe('isPoorSlot', () => {
    it('uses displayVerdict when present — STAND_DOWN and AWAITING are poor', () => {
      expect(isPoorSlot({ displayVerdict: 'STAND_DOWN' })).toBe(true);
      expect(isPoorSlot({ displayVerdict: 'AWAITING' })).toBe(true);
      expect(isPoorSlot({ displayVerdict: 'WORTH_IT' })).toBe(false);
      expect(isPoorSlot({ displayVerdict: 'MAYBE' })).toBe(false);
    });

    it('displayVerdict wins over the legacy verdict — a Claude-elevated STANDDOWN slot is not poor', () => {
      expect(isPoorSlot({ displayVerdict: 'WORTH_IT', verdict: 'STANDDOWN' })).toBe(false);
    });

    it('falls back to the legacy verdict for slots that pre-date displayVerdict', () => {
      expect(isPoorSlot({ verdict: 'STANDDOWN' })).toBe(true);
      expect(isPoorSlot({ verdict: 'GO' })).toBe(false);
      expect(isPoorSlot({ verdict: 'MARGINAL' })).toBe(false);
    });
  });

  describe('sortedSlotsByTidePriority', () => {
    const slots = [
      { locationName: 'Alnmouth', verdict: 'MARGINAL' },
      { locationName: 'Bamburgh', verdict: 'GO' },
      { locationName: 'Craster', verdict: 'GO', tideAligned: true },
      { locationName: 'Dunstanburgh', verdict: 'GO', flags: ['King tide 5.2m'] },
      { locationName: 'Embleton', verdict: 'MARGINAL', tideAligned: true },
    ];

    it('puts king tide first, then tide-aligned, within each verdict', () => {
      expect(sortedSlotsByTidePriority(slots).map((s) => s.locationName)).toEqual([
        'Dunstanburgh', // GO + king
        'Craster', // GO + tide-aligned
        'Bamburgh', // GO plain
        'Embleton', // MARGINAL + tide-aligned
        'Alnmouth', // MARGINAL plain
      ]);
    });

    it('does not mutate its input', () => {
      const copy = [...slots];
      sortedSlotsByTidePriority(slots);
      expect(slots).toEqual(copy);
    });

    it('slotSortKey ranks king detection case-insensitively from flags', () => {
      expect(slotSortKey({ verdict: 'GO', flags: ['KING TIDE'] })).toBe(0);
      expect(slotSortKey({ verdict: 'GO', tideAligned: true })).toBe(1);
      expect(slotSortKey({ verdict: 'GO' })).toBe(2);
      expect(slotSortKey({ verdict: 'MARGINAL', tideAligned: true })).toBe(3);
      expect(slotSortKey({ verdict: 'MARGINAL' })).toBe(4);
      expect(slotSortKey({ verdict: 'STANDDOWN' })).toBe(5);
    });
  });

  describe('weatherCodeToIcon boundaries', () => {
    it.each([
      [null, ''],
      [0, '☀️'],
      [2, '🌤️'],
      [3, '☁️'],
      [48, '🌫️'],
      [67, '🌦️'],
      [80, '🌦️'],
      [82, '🌦️'],
      [77, '❄️'],
      [85, '❄️'],
      [86, '❄️'],
      [95, '⛈️'],
    ])('code %s → %s', (code, icon) => {
      expect(weatherCodeToIcon(code)).toBe(icon);
    });
  });

  describe('msToMph', () => {
    it('rounds to whole mph (briefing rows show integers)', () => {
      expect(msToMph(5.5)).toBe(12); // 12.3035 → 12
      expect(msToMph(null)).toBeNull();
    });
  });

  describe('formatDriveDuration', () => {
    it.each([
      [null, null],
      [45, '45 min'],
      [60, '1h'],
      [65, '1h 5min'],
      [120, '2h'],
    ])('%s minutes → %s', (minutes, expected) => {
      expect(formatDriveDuration(minutes)).toBe(expected);
    });
  });

  describe('event time helpers', () => {
    const es = {
      regions: [
        {
          slots: [
            { locationName: 'A' },
            { locationName: 'B', solarEventTime: '2026-07-16T20:30:00' },
          ],
        },
      ],
      unregioned: [{ solarEventTime: '2026-07-16T21:00:00' }],
    };

    it('getEventTime returns the first slot time found in regions before unregioned', () => {
      expect(getEventTime(es)).toBe('2026-07-16T20:30:00');
      expect(getEventTime({ regions: [], unregioned: [] })).toBeNull();
    });

    it('isEventPast is false within the afterglow window and true beyond it', () => {
      const eventMs = new Date('2026-07-16T20:30:00Z').getTime();
      vi.useFakeTimers();
      vi.setSystemTime(eventMs + AFTERGLOW_MS - 1000);
      expect(isEventPast(es)).toBe(false);
      vi.setSystemTime(eventMs + AFTERGLOW_MS + 1000);
      expect(isEventPast(es)).toBe(true);
    });

    it('getEventTime falls back to the summary time when every slot was withdrawn', () => {
      // The shape BriefingHonestyFilter serves for a zero-coverage region: the region survives,
      // its slots do not. Before the summary carried its own time this returned null.
      const blanked = {
        targetType: 'SUNRISE',
        solarEventTime: '2026-07-16T05:20:00',
        regions: [{ regionName: 'R', slots: [] }],
        unregioned: [],
      };
      expect(getEventTime(blanked)).toBe('2026-07-16T05:20:00');
    });

    it('a slot time still wins over the summary time', () => {
      expect(getEventTime({ ...es, solarEventTime: '2026-07-16T04:00:00' }))
        .toBe('2026-07-16T20:30:00');
    });

    it('a withdrawn-slot event is judged past from its own summary time', () => {
      const blanked = {
        targetType: 'SUNRISE',
        solarEventTime: '2026-07-16T05:20:00',
        regions: [{ regionName: 'R', slots: [] }],
        unregioned: [],
      };
      vi.useFakeTimers();
      vi.setSystemTime(new Date('2026-07-16T19:00:00Z'));
      // The regression: this read false, so a sunrise 14 hours gone consumed a visible-event slot.
      expect(isEventPast(blanked, '2026-07-16')).toBe(true);
    });

    describe('an event with no resolvable time at all', () => {
      // Only reachable for a briefing cached before the backend carried solarEventTime.
      const timeless = (targetType) => ({ targetType, regions: [], unregioned: [] });

      it('counts as current when the caller cannot say which day it is', () => {
        expect(isEventPast(timeless('SUNRISE'))).toBe(false);
      });

      it('is past when its date is before today', () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-07-16T19:00:00Z'));
        expect(isEventPast(timeless('SUNRISE'), '2026-07-15')).toBe(true);
        expect(isEventPast(timeless('SUNSET'), '2026-07-15')).toBe(true);
      });

      it('is not past when its date is after today', () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-07-16T19:00:00Z'));
        expect(isEventPast(timeless('SUNRISE'), '2026-07-17')).toBe(false);
      });

      it('is past for today\'s sunrise once local time is past noon, but never for sunset', () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-07-16T19:00:00Z')); // 20:00 BST
        expect(isEventPast(timeless('SUNRISE'), '2026-07-16')).toBe(true);
        expect(isEventPast(timeless('SUNSET'), '2026-07-16')).toBe(false);
      });

      it('is not past for today\'s sunrise before noon', () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-07-16T09:00:00Z')); // 10:00 BST
        expect(isEventPast(timeless('SUNRISE'), '2026-07-16')).toBe(false);
      });
    });
  });

  describe('ordering constants', () => {
    it('rank GO/WORTH_IT best and STANDDOWN/AWAITING worst', () => {
      expect(VERDICT_ORDER.GO).toBeLessThan(VERDICT_ORDER.MARGINAL);
      expect(VERDICT_ORDER.MARGINAL).toBeLessThan(VERDICT_ORDER.STANDDOWN);
      expect(DISPLAY_ORDER.WORTH_IT).toBeLessThan(DISPLAY_ORDER.MAYBE);
      expect(DISPLAY_ORDER.MAYBE).toBeLessThan(DISPLAY_ORDER.STAND_DOWN);
      expect(DISPLAY_ORDER.STAND_DOWN).toBeLessThan(DISPLAY_ORDER.AWAITING);
    });
  });
});
