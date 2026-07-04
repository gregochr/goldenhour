import { describe, it, expect } from 'vitest';
import { buildMapOverlay } from '../utils/mapOverlay.js';

const DATE = '2026-07-04';

function loc(name, regionName, rating, { lat = 54, lon = -1, types = ['LANDSCAPE'] } = {}) {
  return {
    name,
    regionName,
    lat,
    lon,
    enabled: true,
    locationType: types,
    forecastsByDate: new Map([[DATE, { sunset: { rating, solarEventTime: `${DATE}T20:49:00` } }]]),
  };
}

const ctx = (locations, briefingScores = new Map()) => ({
  locations,
  briefingScores,
  todayStr: DATE,
  tomorrowStr: '2026-07-05',
  nonce: 7,
});

describe('buildMapOverlay', () => {
  it('region trigger → single region, flies to the top-rated location, no caption', () => {
    const locations = [
      loc('Low', 'Tyne and Wear', 2),
      loc('Top', 'Tyne and Wear', 4),
    ];
    const ov = buildMapOverlay(
      { kind: 'region', region: 'Tyne and Wear', date: DATE, eventType: 'SUNSET' },
      ctx(locations),
    );
    expect(ov.title).toBe('Tyne and Wear');
    expect(ov.subLine).toContain('Today sunset');
    expect(ov.subLine).toContain('21:49'); // 20:49Z → 21:49 BST
    expect(ov.caption).toBeNull();
    expect(ov.focus).toBeNull();
    expect(ov.handoff.locationName).toBe('Top');
  });

  it('uses the briefing-score summary as the narrative when present', () => {
    const locations = [loc('Top', 'Tyne and Wear', 4)];
    const scores = new Map([
      ['Tyne and Wear|2026-07-04|SUNSET|Top', { rating: 4, summary: 'A high-cloud canvas.' }],
    ]);
    const ov = buildMapOverlay(
      { kind: 'region', region: 'Tyne and Wear', date: DATE, eventType: 'SUNSET' },
      ctx(locations, scores),
    );
    expect(ov.narrative).toBe('A high-cloud canvas.');
    expect(ov.narrativeTone).toBe('go');
    expect(ov.narrativeHead).toContain('Tyne and Wear');
  });

  it('event trigger spanning >1 region → fits to pins with a caption and no auto-open', () => {
    const locations = [
      loc('A', 'Region One', 4, { lat: 54.1, lon: -1.1 }),
      loc('B', 'Region Two', 3, { lat: 55.2, lon: -2.2 }),
    ];
    const ov = buildMapOverlay({ kind: 'event', date: DATE, eventType: 'SUNSET' }, ctx(locations));
    expect(ov.caption).toContain('2 regions');
    expect(ov.focus.points).toHaveLength(2);
    expect(ov.focus.nonce).toBe(7);
    expect(ov.handoff.locationName).toBeUndefined();
    expect(ov.narrative).toMatch(/Tap a pin/);
  });

  it('event trigger with a single rated region behaves like a region trigger', () => {
    const locations = [
      loc('A', 'Only Region', 4),
      loc('B', 'Only Region', 2),
    ];
    const ov = buildMapOverlay({ kind: 'event', date: DATE, eventType: 'SUNSET' }, ctx(locations));
    expect(ov.focus).toBeNull();
    expect(ov.handoff.locationName).toBe('A');
    expect(ov.title).toBe('Only Region');
  });

  it('location trigger → flies to that location and opens its popup', () => {
    const locations = [loc('Simonside', 'Tyne and Wear', 4)];
    const ov = buildMapOverlay(
      { kind: 'location', locationName: 'Simonside', date: DATE, eventType: 'SUNSET' },
      ctx(locations),
    );
    expect(ov.title).toBe('Simonside');
    expect(ov.handoff.locationName).toBe('Simonside');
    expect(ov.focus).toBeNull();
  });

  it('topic trigger → filters and fits to the matching pins with a caption', () => {
    const locations = [
      loc('Wood A', 'Region One', 3, { types: ['BLUEBELL'] }),
      loc('Wood B', 'Region Two', 3, { types: ['BLUEBELL'] }),
      loc('Coast', 'Region Three', 3, { types: ['SEASCAPE'] }),
    ];
    const ov = buildMapOverlay(
      { kind: 'topic', filterAction: 'BLUEBELL', label: 'Bluebell conditions', date: DATE },
      ctx(locations),
    );
    expect(ov.title).toBe('Bluebell conditions');
    expect(ov.handoff.filterAction).toBe('BLUEBELL');
    expect(ov.focus.points).toHaveLength(2);
    expect(ov.caption).toContain('2 locations');
  });
});
