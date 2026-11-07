import { describe, it, expect } from 'vitest';
import { buildWindowRows } from '../utils/windowFirstRows.js';

/** The tide row's facts for a tide rollup with only the given fields. */
const factsFor = (tide) => buildWindowRows({ tide })[0].facts;
const textOf = (fact) => fact.segments.map((s) => s.text).join(' ');

describe('buildWindowRows — a tide fact with nothing to state is absent', () => {
  it('has no facts at all for an empty rollup (no blank state, nearest or range fact)', () => {
    expect(factsFor({})).toEqual([]);
  });

  it('drops the state/direction fact when neither is known', () => {
    expect(factsFor({ range: '4.9 m' }).map(textOf)).toEqual(['4.9 m']);
  });

  it('states just the state, or just the direction, when only one is served', () => {
    expect(factsFor({ state: 'HIGH' })).toEqual([{ segments: [{ text: 'high water', tone: 'strong' }], optional: false }]);
    expect(factsFor({ direction: 'FALLING' })).toEqual([{ segments: [{ text: 'falling', tone: 'strong' }], optional: false }]);
  });

  it('ignores a state or direction word it does not know', () => {
    expect(factsFor({ state: 'SLACK', direction: 'SIDEWAYS' })).toEqual([]);
  });
});

describe('buildWindowRows — the nearest-extreme fact needs its type and its time', () => {
  it('states type, time and offset together with the trailing dot', () => {
    const [fact] = factsFor({ nearestType: 'High', nearestTime: '05:44', nearestOffset: '12 min before' });
    expect(fact.segments).toEqual([
      { text: 'High 05:44 ·', tone: 'base' },
      { text: '12 min before', tone: 'strong' },
    ]);
  });

  it('keeps the clock but no dot when a Lite reader\'s offset is withheld', () => {
    const [fact] = factsFor({ nearestType: 'High', nearestTime: '05:44', nearestOffset: null });
    expect(fact.segments).toEqual([{ text: 'High 05:44', tone: 'base' }]);
  });

  it('states nothing from an offset when the time is missing', () => {
    expect(factsFor({ nearestType: 'High', nearestOffset: '12 min before' })).toEqual([]);
  });

  it('states nothing from an offset when the type is missing', () => {
    expect(factsFor({ nearestTime: '05:44', nearestOffset: '12 min before' })).toEqual([]);
  });

  it('states nothing from a type or a time alone', () => {
    expect(factsFor({ nearestType: 'High' })).toEqual([]);
    expect(factsFor({ nearestTime: '05:44' })).toEqual([]);
  });
});

describe('buildWindowRows — the range / anomaly / place fact', () => {
  it('joins whichever of range, anomaly and place are served', () => {
    expect(factsFor({ range: '4.9 m', locationName: 'Whitby' }).map(textOf)).toEqual(['4.9 m · at Whitby']);
    expect(factsFor({ locationName: 'Whitby' }).map(textOf)).toEqual(['at Whitby']);
    expect(factsFor({ rangeAnomaly: '1.2 m above an average tide' }).map(textOf))
      .toEqual(['1.2 m above an average tide']);
  });
});
