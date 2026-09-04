import { describe, it, expect } from 'vitest';
import { familyOf, occurrenceCountsLine, anyConditionInterim, bitsWord } from '../utils/comingUpConditions.js';

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
      'every date · 2 not listed, 1 below, 1 on Plan',
    );
  });

  it('omits the inside-Plan clause entirely when nothing has that status', () => {
    const occurrences = [{ status: 'heldBack' }, { status: 'promoted' }];

    expect(occurrenceCountsLine(occurrences)).toBe(
      'every date · 1 not listed, 1 below',
    );
  });

  it('degrades to zero counts for a missing or non-array occurrence list', () => {
    expect(occurrenceCountsLine(undefined)).toBe('every date · 0 not listed, 0 below');
    expect(occurrenceCountsLine(null)).toBe('every date · 0 not listed, 0 below');
  });
});

describe('bitsWord', () => {
  it('buckets a surprisal score into a plain-English word, matching the backend boundaries', () => {
    expect(bitsWord(1.9)).toBe('common');
    expect(bitsWord(2.0)).toBe('occasional');
    expect(bitsWord(3.9)).toBe('occasional');
    expect(bitsWord(4.0)).toBe('uncommon');
    expect(bitsWord(5.4)).toBe('uncommon');
    expect(bitsWord(6.0)).toBe('rare');
    expect(bitsWord(7.0)).toBe('rare');
    expect(bitsWord(8.0)).toBe('very rare');
    expect(bitsWord(9.0)).toBe('very rare');
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
