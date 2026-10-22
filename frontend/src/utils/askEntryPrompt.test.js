import { describe, it, expect } from 'vitest';
import { askEntryPrompt } from './askEntryPrompt.js';

const ev = (date) => ({ date });

describe('askEntryPrompt', () => {
  it('says the next few days for a weekday-only list', () => {
    expect(askEntryPrompt('plan', [ev('2026-10-22'), ev('2026-10-23')]))
      .toBe('Ask about the next few days…');
  });

  it('says this weekend when a Saturday is held (2026-10-24, no timezone drift)', () => {
    expect(askEntryPrompt('plan', [ev('2026-10-23'), ev('2026-10-24')]))
      .toBe('Ask about this weekend…');
  });

  it('says this weekend for a Sunday', () => {
    expect(askEntryPrompt('plan', [ev('2026-10-25')])).toBe('Ask about this weekend…');
  });

  it('says the next few days for an empty or missing list', () => {
    expect(askEntryPrompt('plan', [])).toBe('Ask about the next few days…');
    expect(askEntryPrompt('plan', undefined)).toBe('Ask about the next few days…');
  });

  it('ignores a weekend that is a travel day', () => {
    expect(askEntryPrompt('plan', [ev('2026-10-24'), ev('2026-10-25')],
      new Set(['2026-10-24', '2026-10-25']))).toBe('Ask about the next few days…');
  });

  it('keeps one weekend date that is not a travel day', () => {
    expect(askEntryPrompt('plan', [ev('2026-10-24'), ev('2026-10-25')], new Set(['2026-10-24'])))
      .toBe('Ask about this weekend…');
  });

  it('ignores malformed dates', () => {
    expect(askEntryPrompt('plan', [ev(undefined), ev('nope')])).toBe('Ask about the next few days…');
  });

  it('keeps the Coming up prompt whatever the events', () => {
    expect(askEntryPrompt('coming-up', [ev('2026-10-24')])).toBe('Ask about rare events…');
  });

  it('gives the Map tab the Plan rule', () => {
    expect(askEntryPrompt('map', [ev('2026-10-22')])).toBe('Ask about the next few days…');
    expect(askEntryPrompt('map', [ev('2026-10-25')])).toBe('Ask about this weekend…');
  });

  it('gives the Map tab the Plan rule', () => {
    expect(askEntryPrompt('map', [ev('2026-10-22')])).toBe('Ask about the next few days…');
    expect(askEntryPrompt('map', [ev('2026-10-25')])).toBe('Ask about this weekend…');
  });

  it('falls back to the Plan logic for an unknown tab', () => {
    expect(askEntryPrompt('map', [ev('2026-10-24')])).toBe('Ask about this weekend…');
    expect(askEntryPrompt(undefined, [])).toBe('Ask about the next few days…');
  });
});
