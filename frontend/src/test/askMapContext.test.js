import { describe, it, expect } from 'vitest';
import { askWindowId, askWindowOf, buildMapAskContext } from '../utils/askMapContext.js';

/**
 * What the Map publishes to Ask (F3, plan §2.6) — the scope, the window and the words for the chips.
 * Pure: `MapView` collects the facts, the pane supplies the regions list, this joins them.
 */

const REGIONS = [
  { id: 3, name: 'Northumberland & Tyneside' },
  { id: 5, name: 'North York Moors & Coast' },
  { id: 8, name: 'The Lake District' },
];
const WINDOW = { date: '2026-10-10', eventType: 'SUNRISE', label: 'Saturday sunrise' };

const facts = (over = {}) => ({
  focusRegion: null, scoped: false, areaNames: [], areaLabel: null, window: null, ...over,
});

describe('askWindowId — the server\'s own encoding', () => {
  it('is yyyy-MM-dd_sunrise / _sunset, lower case', () => {
    expect(askWindowId('2026-10-10', 'SUNRISE')).toBe('2026-10-10_sunrise');
    expect(askWindowId('2026-10-05', 'SUNSET')).toBe('2026-10-05_sunset');
  });
});

describe('askWindowOf', () => {
  const row = (over = {}) => ({
    kind: 'solar', date: '2026-10-10', eventType: 'SUNRISE', label: 'Saturday sunrise', served: true, ...over,
  });

  it('is the window of a served SOLAR row', () => {
    expect(askWindowOf(row())).toEqual(WINDOW);
  });

  it('⚠️ is null for a night row — astro and aurora nights are no window of the forecast\'s', () => {
    expect(askWindowOf(row({ kind: 'astro', eventType: 'ASTRO' }))).toBeNull();
    expect(askWindowOf(row({ kind: 'aur', eventType: 'AURORA' }))).toBeNull();
  });

  it('is null for a filler row the briefing did not serve — the server has no such window', () => {
    expect(askWindowOf(row({ served: false }))).toBeNull();
  });

  it('is null for no row, a row without a usable date, or an event that is not a sunrise or sunset', () => {
    expect(askWindowOf(null)).toBeNull();
    expect(askWindowOf(row({ date: undefined }))).toBeNull();
    expect(askWindowOf(row({ eventType: 'MOONRISE' }))).toBeNull();
  });

  it('carries an empty label as an empty string rather than the word undefined', () => {
    expect(askWindowOf(row({ label: undefined })).label).toBe('');
  });
});

describe('buildMapAskContext — the scope', () => {
  it('Everywhere: no regions, labelled so', () => {
    expect(buildMapAskContext(facts(), REGIONS)).toEqual({
      regionIds: [], regionNames: [], windowId: null, windowLabel: null, viewLabel: 'Map · Everywhere',
    });
  });

  it('a focused region is that region alone, by id, with its name on the chip', () => {
    const out = buildMapAskContext(facts({ focusRegion: 'North York Moors & Coast' }), REGIONS);

    expect(out.regionIds).toEqual([5]);
    expect(out.regionNames).toEqual(['North York Moors & Coast']);
    expect(out.viewLabel).toBe('Map · North York Moors & Coast');
  });

  it('a focused region beats the scope segment, whatever it says', () => {
    const out = buildMapAskContext(facts({
      focusRegion: 'The Lake District', scoped: true, areaNames: ['Northumberland & Tyneside'],
    }), REGIONS);

    expect(out.regionIds).toEqual([8]);
  });

  it('"My area" is the regions of the scope pool, labelled "My area"', () => {
    const out = buildMapAskContext(facts({
      scoped: true, areaNames: ['Northumberland & Tyneside', 'North York Moors & Coast'],
    }), REGIONS);

    expect(out.regionIds).toEqual([3, 5]);
    expect(out.viewLabel).toBe('Map · My area');
  });

  it('an away origin names its own area', () => {
    const out = buildMapAskContext(facts({
      scoped: true, areaNames: ['The Lake District'], areaLabel: 'Around Keswick',
    }), REGIONS);

    expect(out.viewLabel).toBe('Map · Around Keswick');
  });

  it('a segment that narrows nothing (no home) is Everywhere, not "My area"', () => {
    const out = buildMapAskContext(facts({ scoped: false, areaNames: ['The Lake District'] }), REGIONS);

    expect(out.regionIds).toEqual([]);
    expect(out.viewLabel).toBe('Map · Everywhere');
  });

  it('"My area" with no regions in it sends — and says — everywhere', () => {
    const out = buildMapAskContext(facts({ scoped: true, areaNames: [] }), REGIONS);

    expect(out.regionIds).toEqual([]);
    expect(out.viewLabel).toBe('Map · Everywhere');
  });

  it('⚠️ names what is SENT: one region the list cannot place widens the question to everywhere, and the chip says so', () => {
    const area = buildMapAskContext(facts({
      scoped: true, areaNames: ['Northumberland & Tyneside', 'Somewhere Renamed'],
    }), REGIONS);
    expect(area.regionIds).toEqual([]);
    expect(area.regionNames).toEqual([]);
    expect(area.viewLabel).toBe('Map · Everywhere');

    const focus = buildMapAskContext(facts({ focusRegion: 'Somewhere Renamed' }), REGIONS);
    expect(focus.regionIds).toEqual([]);
    expect(focus.viewLabel).toBe('Map · Everywhere');
  });

  it('the regions list has not arrived yet: everywhere, until it does', () => {
    expect(buildMapAskContext(facts({ focusRegion: 'The Lake District' }), []).regionIds).toEqual([]);
    expect(buildMapAskContext(facts({ focusRegion: 'The Lake District' }), undefined).regionIds).toEqual([]);
  });

  it('joins by name byte-identically — a trimmed name is another name', () => {
    expect(buildMapAskContext(facts({ focusRegion: ' The Lake District' }), REGIONS).regionIds).toEqual([]);
  });

  it('a region with a non-integer id cannot be sent', () => {
    expect(buildMapAskContext(facts({ focusRegion: 'X' }), [{ id: 'abc', name: 'X' }]).regionIds).toEqual([]);
  });
});

describe('buildMapAskContext — the window', () => {
  it('a solar window becomes its id and its chip label', () => {
    const out = buildMapAskContext(facts({ window: WINDOW }), REGIONS);

    expect(out.windowId).toBe('2026-10-10_sunrise');
    expect(out.windowLabel).toBe('Saturday sunrise');
  });

  it('no window (a night row, or an unserved one) sends none and draws no chip', () => {
    const out = buildMapAskContext(facts({ window: null }), REGIONS);

    expect(out.windowId).toBeNull();
    expect(out.windowLabel).toBeNull();
  });
});
