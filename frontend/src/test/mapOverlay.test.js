import { describe, it, expect } from 'vitest';
import { buildMapOverlay, normalizeMapTrigger } from '../utils/mapOverlay.js';

const DATE = '2026-07-04';

function loc(name, regionName, rating, { lat = 54, lon = -1, types = ['LANDSCAPE'], bortleClass = null } = {}) {
  return {
    name,
    regionName,
    lat,
    lon,
    enabled: true,
    locationType: types,
    bortleClass,
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
  describe('the coming-up trigger (D8, plan §6b)', () => {
    it('coastal-spots → filters and fits to the matching pins with a caption', () => {
      const locations = [
        loc('Bamburgh', 'Northumberland', 3, { types: ['SEASCAPE'] }),
        loc('Whitby', 'Yorkshire', 3, { types: ['SEASCAPE'] }),
        loc('Elsewhere', 'Yorkshire', 3, { types: ['LANDSCAPE'] }),
      ];
      const ov = buildMapOverlay(
        { kind: 'coming-up', filterAction: 'SEASCAPE', label: 'Spring tide run', date: DATE },
        ctx(locations),
      );
      expect(ov.title).toBe('Spring tide run');
      expect(ov.handoff.filterAction).toBe('SEASCAPE');
      expect(ov.handoff.darkSky).toBe(false);
      expect(ov.handoff.date).toBe(DATE);
      expect(ov.focus.points).toHaveLength(2);
      expect(ov.caption).toContain('2 locations');
      // Never dresses a T+90 chronology date as a rating claim — claims nothing about ratings,
      // matching the `topic` branch it is modelled on (D8).
      expect(ov.narrativeTone).toBe('standdown');
      expect(ov.narrativeHead).toBeNull();
    });

    it('dark-sky-spots → filters by Bortle class, never by locationType', () => {
      const locations = [
        loc('Kielder', 'Northumberland', 3, { bortleClass: 3 }),
        loc('Thick Sky', 'Northumberland', 3, { bortleClass: 7 }),
        loc('Unmeasured', 'Northumberland', 3, { bortleClass: null }),
      ];
      const ov = buildMapOverlay(
        { kind: 'coming-up', darkSky: true, label: 'Perseids', date: DATE },
        ctx(locations),
      );
      expect(ov.handoff.darkSky).toBe(true);
      expect(ov.handoff.filterAction).toBeNull();
      expect(ov.focus.names).toEqual(['Kielder']);
    });

    it('carries the trigger date, unlike the aurora branch — no rating is ever claimed for it', () => {
      const ov = buildMapOverlay(
        { kind: 'coming-up', filterAction: 'SEASCAPE', date: '2026-11-26' },
        ctx([loc('Bamburgh', 'Northumberland', 3, { types: ['SEASCAPE'] })]),
      );
      expect(ov.handoff.date).toBe('2026-11-26');
    });

    it('falls back to a null-safe title and caption when nothing matches', () => {
      const ov = buildMapOverlay(
        { kind: 'coming-up', darkSky: true, date: DATE },
        ctx([loc('Nowhere Dark', 'Northumberland', 3, { bortleClass: 8 })]),
      );
      expect(ov.title).toBe('Dark-sky spots');
      expect(ov.caption).toBeNull();
      expect(ov.focus).toBeNull();
    });
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

describe('normalizeMapTrigger (extracted from App.jsx\'s handleShowOnMap, D8)', () => {
  it('a coming-up handoff normalises to kind:\'coming-up\', never kind:\'topic\'', () => {
    // The regression this pins: both this handoff shape AND the plain-filterAction handoff (the
    // topic-pill one, below) carry a `filterAction` — only the explicit `kind` field on the input
    // tells them apart, and the coming-up check has to run FIRST or a coastal-spots tap becomes a
    // kind:'topic' trigger instead (invisible today, since both branches render similarly, but
    // fatal once P6 deletes kind:'topic' and its handler).
    const trigger = normalizeMapTrigger(
      { kind: 'coming-up', filterAction: 'SEASCAPE', label: 'Spring tide run', date: DATE },
      null,
    );
    expect(trigger).toEqual({
      kind: 'coming-up', filterAction: 'SEASCAPE', darkSky: false, label: 'Spring tide run', date: DATE,
    });
  });

  it('a dark-sky coming-up handoff carries an explicit boolean darkSky and a null filterAction', () => {
    const trigger = normalizeMapTrigger({ kind: 'coming-up', darkSky: true, date: DATE }, null);
    expect(trigger).toEqual({
      kind: 'coming-up', filterAction: null, darkSky: true, label: null, date: DATE,
    });
  });

  it('a plain filterAction handoff (no kind) still normalises to kind:\'topic\' — the pre-P3b path', () => {
    const trigger = normalizeMapTrigger({ filterAction: 'BLUEBELL', label: 'Bluebell', date: DATE }, null);
    expect(trigger.kind).toBe('topic');
  });

  it('an aurora handoff is still checked first, ahead of coming-up and topic alike', () => {
    const trigger = normalizeMapTrigger({ kind: 'aurora', date: DATE }, null);
    expect(trigger).toEqual({ kind: 'aurora', date: DATE });
  });

  it('a region handoff normalises to kind:\'region\'', () => {
    const trigger = normalizeMapTrigger({ region: 'Tyne and Wear', date: DATE, eventType: 'SUNSET' }, null);
    expect(trigger.kind).toBe('region');
  });

  it('a plain date plus a location name normalises to kind:\'location\'', () => {
    const trigger = normalizeMapTrigger(DATE, 'SUNSET', 'Bamburgh Beach');
    expect(trigger).toEqual({ kind: 'location', locationName: 'Bamburgh Beach', date: DATE, eventType: 'SUNSET' });
  });

  it('a bare date with no location name normalises to kind:\'event\'', () => {
    const trigger = normalizeMapTrigger(DATE, 'SUNSET');
    expect(trigger).toEqual({ kind: 'event', date: DATE, eventType: 'SUNSET' });
  });
});
