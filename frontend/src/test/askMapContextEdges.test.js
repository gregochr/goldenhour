import { describe, it, expect } from 'vitest';
import { askWindowOf, buildMapAskContext } from '../utils/askMapContext.js';

describe('askMapContext edges', () => {
  it('has no window for a night row of a kind Ask has no word for', () => {
    expect(askWindowOf({ date: '2026-11-07', kind: 'NIGHT', eventType: 'MOONRISE', label: 'x' })).toBeNull();
    expect(askWindowOf({ date: '2026-11-07', eventType: 'SUNRISE', label: 'x' })).toBeNull();
  });

  it('finds a focus region among a list that holds null entries', () => {
    const regions = [null, { id: 4, name: 'Lakes' }];
    const ctx = buildMapAskContext({ focusRegion: 'Lakes', scoped: false, areaNames: [] }, regions);
    expect(ctx.regionIds).toEqual([4]);
    expect(ctx.regionNames).toEqual(['Lakes']);
    expect(ctx.viewLabel).toBe('Map · Lakes');
  });

  it('falls back to everywhere when the focus region is not in the list', () => {
    const ctx = buildMapAskContext({ focusRegion: 'Lakes', scoped: false, areaNames: [] }, [{ id: 4, name: 'Dales' }]);
    expect(ctx.regionIds).toEqual([]);
    expect(ctx.regionNames).toEqual([]);
    expect(ctx.viewLabel).toBe('Map · Everywhere');
  });

  it('falls back to everywhere when the regions list is not an array', () => {
    const ctx = buildMapAskContext({ focusRegion: 'Lakes', scoped: false, areaNames: [] }, null);
    expect(ctx.regionIds).toEqual([]);
    expect(ctx.viewLabel).toBe('Map · Everywhere');
  });
});
