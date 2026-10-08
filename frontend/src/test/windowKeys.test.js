import { describe, it, expect } from 'vitest';
import { windowKey, windowTail } from '../utils/windowKeys.js';
import { buildScoreIndex, lookupForWindow } from '../utils/locationSheet.js';
import { hasEventPassed, latestSolarEventTimes } from '../utils/solarEventTimes.js';

/**
 * The two window-key formats are kept apart on purpose: {@code date:targetType} addresses a window
 * as a value, {@code date|targetType} is the tail of a per-location index key. Hand-built keys in
 * other suites ({@code MapViewStaleWindow.test.jsx}, {@code mapTideFit.test.js}) bake the pipe form in.
 */
describe('windowKeys', () => {
  it('windowKey is date:targetType', () => {
    expect(windowKey('2026-10-11', 'SUNRISE')).toBe('2026-10-11:SUNRISE');
  });

  it('windowTail is date|targetType — a different format, not merged', () => {
    expect(windowTail('2026-10-11', 'SUNRISE')).toBe('2026-10-11|SUNRISE');
    expect(windowTail('2026-10-11', 'SUNRISE')).not.toBe(windowKey('2026-10-11', 'SUNRISE'));
  });

  it('the location index is keyed `${id}|${tail}` and `${name}|${tail}` with the shared tail', () => {
    const idx = buildScoreIndex([
      { locationId: 7, locationName: 'Bamburgh', date: '2026-10-11', targetType: 'SUNRISE', rating: 3 },
    ]);
    const tail = windowTail('2026-10-11', 'SUNRISE');
    expect(idx.byId.has(`7|${tail}`)).toBe(true);
    expect(idx.byName.has(`Bamburgh|${tail}`)).toBe(true);
    expect(lookupForWindow(idx, 7, 'Bamburgh', '2026-10-11', 'SUNRISE').rating).toBe(3);
  });

  it('latestSolarEventTimes is keyed by the same tail', () => {
    const briefing = {
      days: [{
        date: '2026-10-11',
        eventSummaries: [{
          targetType: 'SUNRISE',
          regions: [{ slots: [{ solarEventTime: '2026-10-11T05:50:00' }] }],
        }],
      }],
    };
    const times = latestSolarEventTimes(briefing);
    expect([...times.keys()]).toEqual([windowTail('2026-10-11', 'SUNRISE')]);
    expect(hasEventPassed(times, '2026-10-11', 'SUNRISE', new Date('2026-10-12T00:00:00Z'))).toBe(true);
  });
});
