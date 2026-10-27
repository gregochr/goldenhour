import { describe, it, expect } from 'vitest';
import {
  applyUserFilters, chipCounts, formatCivilDayShort, formatJoinedPhrase, formatLastActive, formatSentWhen, matchesSearch, NO_FILTERS,
  roleChangeNote, roleLabel, statusOf, subRegionsCell,
} from '../utils/userAdmin.js';

// 27 October 2026, 10:00 UK (GMT: British Summer Time ended on the 25th).
const NOW = new Date('2026-10-27T10:00:00Z');

describe('roleLabel and statusOf', () => {
  it('words the roles plainly', () => {
    expect(roleLabel('LITE_USER')).toBe('Lite');
    expect(roleLabel('PRO_USER')).toBe('Pro');
    expect(roleLabel('ADMIN')).toBe('Admin');
  });

  it('keeps awaiting verification apart from disabled', () => {
    expect(statusOf({ enabled: false, awaitingVerification: true })).toBe('awaiting');
    expect(statusOf({ enabled: false, awaitingVerification: false })).toBe('disabled');
    expect(statusOf({ enabled: false })).toBe('disabled');
    expect(statusOf({ enabled: true })).toBe('active');
  });
});

describe('formatLastActive', () => {
  it('reads Never for no instant', () => {
    expect(formatLastActive('', NOW)).toBe('Never');
    expect(formatLastActive(null, NOW)).toBe('Never');
  });

  it('reads Today with the UK clock, Yesterday, then the date', () => {
    expect(formatLastActive('2026-10-27T09:14:00', NOW)).toBe('Today, 09:14');
    expect(formatLastActive('2026-10-26T23:50:00', NOW)).toBe('Yesterday');
    expect(formatLastActive('2026-10-21T08:00:00', NOW)).toBe('21 Oct');
  });

  it('adds the year to a date from another year', () => {
    expect(formatLastActive('2025-10-21T08:00:00', NOW)).toBe('21 Oct 2025');
  });

  it('calls the UK day by the UK calendar: 23:30 UTC in summer is already tomorrow', () => {
    const summerNow = new Date('2026-06-02T09:00:00Z');
    expect(formatLastActive('2026-06-01T23:30:00', summerNow)).toBe('Today, 00:30');
  });

  it('stays correct across the day the clocks go back', () => {
    // 25 October 2026 is 25 hours long in the UK; the 24th is yesterday of the 25th.
    expect(formatLastActive('2026-10-24T12:00:00', new Date('2026-10-25T12:00:00Z'))).toBe('Yesterday');
  });
});

describe('formatSentWhen', () => {
  it('says today at the clock, or the day and the clock', () => {
    expect(formatSentWhen('2026-10-27T08:02:00Z', NOW)).toBe('today at 08:02');
    expect(formatSentWhen('2026-10-20T08:02:00Z', NOW)).toBe('20 Oct at 09:02');
    expect(formatSentWhen(null, NOW)).toBeNull();
  });
});

describe('formatCivilDayShort', () => {
  it('names the civil day without a zone moving it', () => {
    expect(formatCivilDayShort('2026-11-25')).toBe('25 Nov');
    expect(formatCivilDayShort('2026-11-02')).toBe('2 Nov');
    expect(formatCivilDayShort(null)).toBeNull();
    expect(formatCivilDayShort('nonsense')).toBeNull();
  });
});

describe('subRegionsCell', () => {
  it('words a Lite reader by status, and flags over limit', () => {
    const picks = ['A', 'B'];
    expect(subRegionsCell({ role: 'LITE_USER', subRegions: picks, subRegionStatus: 'LOCKED', nextChangeOn: '2026-11-25' }))
      .toEqual({ kind: 'picks', count: 2, detail: 'locked to 25 Nov', names: 'A, B' });
    expect(subRegionsCell({ role: 'LITE_USER', subRegions: picks, subRegionStatus: 'FREE_UNTIL', freeUntil: '2026-11-02' }).detail)
      .toBe('free to 2 Nov');
    expect(subRegionsCell({ role: 'LITE_USER', subRegions: picks, subRegionStatus: 'OPEN' }).detail).toBe('can change');
    expect(subRegionsCell({ role: 'LITE_USER', subRegions: picks, subRegionStatus: 'OPEN', overLimit: true }).detail)
      .toBe('can change · over limit');
  });

  it('words none, Pro and Admin', () => {
    expect(subRegionsCell({ role: 'LITE_USER', subRegions: [] }).text).toBe('None chosen');
    expect(subRegionsCell({ role: 'PRO_USER', subRegions: ['kept'] }).text).toBe('Pro: every sub-region');
    expect(subRegionsCell({ role: 'ADMIN' }).text).toBe('Admin: every sub-region');
  });
});

describe('filters', () => {
  const users = [
    { id: 1, username: 'anna', email: 'a@x.com', role: 'LITE_USER', enabled: true, subRegionStatus: 'LOCKED' },
    { id: 2, username: 'bob', email: 'anna.friend@x.com', role: 'LITE_USER', enabled: true, subRegionStatus: 'FIRST_CHOICE' },
    { id: 3, username: 'cat', email: 'c@x.com', role: 'PRO_USER', enabled: false, subRegionStatus: 'NOT_APPLICABLE' },
    { id: 4, username: 'dan', email: 'd@x.com', role: 'LITE_USER', enabled: false, awaitingVerification: true, subRegionStatus: 'FIRST_CHOICE' },
  ];

  it('matches the search on either field, ignoring case and spaces at the ends', () => {
    expect(matchesSearch(users[1], ' ANNA ')).toBe(true);
    expect(matchesSearch(users[2], 'anna')).toBe(false);
    expect(matchesSearch(users[2], '')).toBe(true);
  });

  it('ANDs the role with the toggles', () => {
    expect(applyUserFilters(users, { ...NO_FILTERS, role: 'LITE_USER', noSubRegions: true }).map((u) => u.id)).toEqual([2, 4]);
    expect(applyUserFilters(users, { ...NO_FILTERS, noSubRegions: true, awaiting: true }).map((u) => u.id)).toEqual([4]);
    expect(applyUserFilters(users, { ...NO_FILTERS, disabled: true }).map((u) => u.id)).toEqual([3]);
  });

  it('counts each chip over everyone', () => {
    expect(chipCounts(users)).toEqual({
      all: 4, LITE_USER: 3, PRO_USER: 1, ADMIN: 0, awaiting: 1, disabled: 1, noSubRegions: 2, locked: 1, overLimit: 0,
    });
  });
});

describe('roleChangeNote', () => {
  const base = { username: 'tom_h', limit: 3 };

  it('has nothing to say for an unchanged role or Pro to Admin', () => {
    expect(roleChangeNote({ ...base, from: 'LITE_USER', to: 'LITE_USER', pickCount: 2 })).toBeNull();
    expect(roleChangeNote({ ...base, from: 'PRO_USER', to: 'ADMIN', pickCount: 2 })).toBeNull();
  });

  it('says the picks are kept when Lite goes up, singular for one', () => {
    expect(roleChangeNote({ ...base, from: 'LITE_USER', to: 'PRO_USER', pickCount: 3 }))
      .toBe("Pro sees every sub-region. tom_h's 3 picks are kept, and come back into use if the account returns to Lite.");
    expect(roleChangeNote({ ...base, from: 'LITE_USER', to: 'PRO_USER', pickCount: 1 }))
      .toBe("Pro sees every sub-region. tom_h's 1 pick is kept, and comes back into use if the account returns to Lite.");
  });

  it('says the kept picks come back when Pro returns to Lite, or that they will be asked', () => {
    expect(roleChangeNote({ ...base, from: 'PRO_USER', to: 'LITE_USER', pickCount: 2 }))
      .toBe("Lite readers choose up to 3 sub-regions. tom_h's 2 kept picks come back into use; the lock carries on from their last change.");
    expect(roleChangeNote({ ...base, from: 'ADMIN', to: 'LITE_USER', pickCount: 0 }))
      .toBe("They'll be asked to choose at their next sign-in.");
  });
});

describe('formatJoinedPhrase', () => {
  it('says today for a UK-today join and the date otherwise', () => {
    expect(formatJoinedPhrase('2026-10-27T00:05:00', NOW)).toBe('today');
    expect(formatJoinedPhrase('2026-10-19T09:00:00', NOW)).toBe('19 Oct 2026');
    expect(formatJoinedPhrase(null, NOW)).toBe('—');
  });
});
