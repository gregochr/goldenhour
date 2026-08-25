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

  it('region trigger with locationNames restricts to the single qualifying spot', () => {
    const locations = [
      loc('Buttermere', 'The Lake District', 4),
      loc('Other', 'The Lake District', 3),
    ];
    const ov = buildMapOverlay(
      { kind: 'region', region: 'The Lake District', date: DATE, eventType: 'SUNSET', locationNames: ['Buttermere'], label: 'Cloud inversion' },
      ctx(locations),
    );
    expect(ov.handoff.locationName).toBe('Buttermere');
    // The single qualifying spot still restricts the map's markers to just it (names, no points).
    expect(ov.focus.names).toEqual(['Buttermere']);
    expect(ov.focus.points).toBeUndefined();
    expect(ov.subLine).toContain('Cloud inversion');
  });

  it('region trigger with several qualifying spots fits to them with a caption', () => {
    const locations = [
      loc('A', 'The Lake District', 4, { lat: 54.0, lon: -3.0 }),
      loc('B', 'The Lake District', 3, { lat: 54.5, lon: -3.2 }),
      loc('C', 'The Lake District', 2, { lat: 54.7, lon: -3.1 }),
    ];
    const ov = buildMapOverlay(
      { kind: 'region', region: 'The Lake District', date: DATE, eventType: 'SUNSET', locationNames: ['A', 'B'], label: 'Cloud inversion' },
      ctx(locations),
    );
    expect(ov.focus.points).toHaveLength(2);
    expect(ov.focus.names).toEqual(['A', 'B']); // markers restricted to just the qualifying spots
    expect(ov.caption).toContain('2 spots');
    expect(ov.title).toBe('The Lake District');
  });

  it('a typed topic (bluebell) passes its filterAction so the map filters to that type', () => {
    const locations = [
      loc('Wood A', 'The Lake District', 3, { lat: 54.0, lon: -3.0, types: ['BLUEBELL'] }),
      loc('Wood B', 'The Lake District', 3, { lat: 54.5, lon: -3.2, types: ['BLUEBELL'] }),
    ];
    const ov = buildMapOverlay(
      { kind: 'region', region: 'The Lake District', date: DATE, eventType: 'SUNSET', locationNames: ['Wood A', 'Wood B'], label: 'Bluebell conditions', filterAction: 'BLUEBELL' },
      ctx(locations),
    );
    expect(ov.handoff.filterAction).toBe('BLUEBELL');
    expect(ov.focus.points).toHaveLength(2);
  });

  it('a topic without a location type carries a null filterAction', () => {
    const locations = [loc('Buttermere', 'The Lake District', 4)];
    const ov = buildMapOverlay(
      { kind: 'region', region: 'The Lake District', date: DATE, eventType: 'SUNSET', locationNames: ['Buttermere'], label: 'Cloud inversion', filterAction: null },
      ctx(locations),
    );
    expect(ov.handoff.filterAction).toBeNull();
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
  describe('the aurora trigger', () => {
    it('hands the map the aurora event for the night in question', () => {
      const ov = buildMapOverlay({ kind: 'aurora', date: DATE }, ctx([loc('Kielder', 'Northumberland', 4)]));
      expect(ov.handoff.eventType).toBe('AURORA');
      expect(ov.handoff.date).toBe(DATE);
      expect(ov.title).toBe('Aurora tonight');
    });

    // The trap this branch exists to avoid, and the reason it claims so little: `ratingFor` and
    // `solarTimeFor` both resolve any non-SUNRISE event to the SUNSET forecast. An aurora overlay
    // built through the ordinary path would therefore have shown that evening's sunset rating and
    // its 20:49 sunset time, presented as aurora facts. The fixture carries both so this test
    // fails loudly if a later change starts reaching for them. (21:49, not the fixture's
    // 20:49: the overlay prints Europe/London local time, which the control below pins.)
    it('claims no rating, no clock time and no count of our own locations', () => {
      const ov = buildMapOverlay({ kind: 'aurora', date: DATE }, ctx([
        loc('Kielder', 'Northumberland', 4),
        loc('Derwent', 'Durham', 5),
      ]));
      expect(ov.caption).toBeNull();
      expect(ov.subLine).toBeNull();
      expect(ov.focus).toBeNull();
      expect(ov.narrativeTone).not.toBe('go');
      const printed = `${ov.title} ${ov.subLine ?? ''} ${ov.caption ?? ''} ${ov.narrative ?? ''} ${ov.narrativeHead ?? ''}`;
      expect(printed).not.toMatch(/21:49/);
      expect(printed).not.toMatch(/\d\u2605|\bbest\b/i);
      expect(printed).not.toMatch(/\b2 locations\b/);
    });

    // The positive control for the assertion above: the ordinary event path DOES print the time,
    // so "no 20:49" is a fact about the aurora branch rather than about the fixture.
    it('is a departure from the event trigger, which does print the solar time', () => {
      const ov = buildMapOverlay({ kind: 'event', date: DATE, eventType: 'SUNSET' }, ctx([
        loc('Kielder', 'Northumberland', 4),
      ]));
      const printed = `${ov.title} ${ov.subLine ?? ''} ${ov.caption ?? ''}`;
      expect(printed).toMatch(/21:49/);
    });
  });
});
