import { describe, it, expect } from 'vitest';
import { familyOf, occurrenceCountsLine, anyConditionInterim } from '../utils/comingUpConditions.js';

describe('familyOf', () => {
  it('maps every first-ship condition type to its family token', () => {
    expect(familyOf('COASTAL_TIDES')).toBe('coastal');
    expect(familyOf('DUST')).toBe('dust');
    expect(familyOf('VALLEY_INVERSIONS')).toBe('air');
  });

  it('degrades to night-sky for an unrecognised type rather than a blank swatch', () => {
    expect(familyOf('SOMETHING_NEW')).toBe('night-sky');
  });
});

describe('occurrenceCountsLine', () => {
  it('counts each status from the served occurrence list', () => {
    const occurrences = [
      { status: 'heldBack' }, { status: 'heldBack' },
      { status: 'promoted' },
      { status: 'insidePlan' },
    ];

    expect(occurrenceCountsLine(occurrences)).toBe(
      'every occurrence in the window · 2 held back, 1 in the list, 1 inside Plan',
    );
  });

  it('omits the inside-Plan clause entirely when nothing has that status', () => {
    const occurrences = [{ status: 'heldBack' }, { status: 'promoted' }];

    expect(occurrenceCountsLine(occurrences)).toBe(
      'every occurrence in the window · 1 held back, 1 in the list',
    );
  });

  it('degrades to zero counts for a missing or non-array occurrence list', () => {
    expect(occurrenceCountsLine(undefined)).toBe('every occurrence in the window · 0 held back, 0 in the list');
    expect(occurrenceCountsLine(null)).toBe('every occurrence in the window · 0 held back, 0 in the list');
  });
});

describe('anyConditionInterim', () => {
  it('is true when any served condition is interim', () => {
    expect(anyConditionInterim([{ interim: false }, { interim: true }])).toBe(true);
  });

  it('is false when every condition is mature, or the list is empty/absent', () => {
    expect(anyConditionInterim([{ interim: false }])).toBe(false);
    expect(anyConditionInterim([])).toBe(false);
    expect(anyConditionInterim(undefined)).toBe(false);
  });
});
