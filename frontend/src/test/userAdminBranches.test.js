import { describe, it, expect } from 'vitest';
import {
  applyUserFilters, chipCounts, formatCivilDayShort, formatJoined, formatJoinedPhrase, formatLastActive,
  formatSentWhen, matchesSearch, NO_FILTERS, roleChangeNote, roleLabel, subRegionsCell, USER_SORT_ACCESSORS,
} from '../utils/userAdmin.js';

const NOW = new Date('2026-10-27T10:00:00Z');

describe('roleLabel fallbacks', () => {
  it('returns an unknown role as is and empty for none', () => {
    expect(roleLabel('GUEST')).toBe('GUEST');
    expect(roleLabel(undefined)).toBe('');
    expect(roleLabel(null)).toBe('');
  });
});

describe('matchesSearch with missing fields', () => {
  it('matches the email when there is no username', () => {
    expect(matchesSearch({ email: 'Ann@Example.com' }, ' ann@')).toBe(true);
  });
  it('matches the username when there is no email', () => {
    expect(matchesSearch({ username: 'Bob' }, 'bo')).toBe(true);
  });
  it('does not match when neither field holds the text', () => {
    expect(matchesSearch({}, 'zz')).toBe(false);
  });
});

describe('applyUserFilters', () => {
  const users = [
    { username: 'a', role: 'LITE_USER', enabled: true, subRegionStatus: 'FIRST_CHOICE', overLimit: true },
    { username: 'b', role: 'LITE_USER', enabled: false, subRegionStatus: 'LOCKED' },
    { username: 'c', role: 'PRO_USER', enabled: true, awaitingVerification: true, subRegionStatus: 'FIRST_CHOICE' },
    { username: 'd', role: 'ADMIN', enabled: true },
  ];
  const names = (f) => applyUserFilters(users, { ...NO_FILTERS, ...f }).map((u) => u.username);

  it('filters by role', () => expect(names({ role: 'LITE_USER' })).toEqual(['a', 'b']));
  it('filters awaiting', () => expect(names({ awaiting: true })).toEqual(['c']));
  it('filters disabled', () => expect(names({ disabled: true })).toEqual(['b']));
  it('filters Lite readers with no sub-regions only', () => expect(names({ noSubRegions: true })).toEqual(['a']));
  it('filters locked', () => expect(names({ locked: true })).toEqual(['b']));
  it('filters over limit', () => expect(names({ overLimit: true })).toEqual(['a']));
  it('ANDs chips', () => expect(names({ role: 'LITE_USER', locked: true, disabled: true })).toEqual(['b']));
  it('returns everyone with no filters', () => expect(names({})).toEqual(['a', 'b', 'c', 'd']));
});

describe('chipCounts', () => {
  it('counts every chip and ignores unknown roles in the role tallies', () => {
    const users = [
      { role: 'LITE_USER', enabled: true, subRegionStatus: 'FIRST_CHOICE', overLimit: true },
      { role: 'LITE_USER', enabled: false, subRegionStatus: 'LOCKED' },
      { role: 'PRO_USER', enabled: true, awaitingVerification: true },
      { role: 'ADMIN', enabled: true },
      { role: 'WEIRD', enabled: true },
    ];
    expect(chipCounts(users)).toEqual({
      all: 5, LITE_USER: 2, PRO_USER: 1, ADMIN: 1,
      awaiting: 1, disabled: 1, noSubRegions: 1, locked: 1, overLimit: 1,
    });
  });
});

describe('formatCivilDayShort', () => {
  it('formats a civil day', () => expect(formatCivilDayShort('2026-11-25')).toBe('25 Nov'));
  it('is null for a non-string', () => {
    expect(formatCivilDayShort(null)).toBeNull();
    expect(formatCivilDayShort(20261125)).toBeNull();
  });
  it('is null for a malformed string', () => expect(formatCivilDayShort('25/11/2026')).toBeNull());
});

describe('subRegionsCell', () => {
  it('words Pro and Admin as every sub-region', () => {
    expect(subRegionsCell({ role: 'PRO_USER' })).toEqual({ kind: 'role', text: 'Pro: every sub-region' });
    expect(subRegionsCell({ role: 'ADMIN' })).toEqual({ kind: 'role', text: 'Admin: every sub-region' });
  });
  it('says None chosen when the picks are missing or empty', () => {
    expect(subRegionsCell({ role: 'LITE_USER' })).toEqual({ kind: 'none', text: 'None chosen' });
    expect(subRegionsCell({ role: 'LITE_USER', subRegions: [] })).toEqual({ kind: 'none', text: 'None chosen' });
  });
  it('says can change by default', () => {
    expect(subRegionsCell({ role: 'LITE_USER', subRegions: ['A', 'B'], subRegionStatus: 'OPEN' }))
      .toEqual({ kind: 'picks', count: 2, detail: 'can change', names: 'A, B' });
  });
  it('words a lock with and without a date', () => {
    expect(subRegionsCell({ role: 'LITE_USER', subRegions: ['A'], subRegionStatus: 'LOCKED', nextChangeOn: '2026-11-25' }).detail)
      .toBe('locked to 25 Nov');
    expect(subRegionsCell({ role: 'LITE_USER', subRegions: ['A'], subRegionStatus: 'LOCKED' }).detail).toBe('locked');
  });
  it('words a free window with and without a date', () => {
    expect(subRegionsCell({ role: 'LITE_USER', subRegions: ['A'], subRegionStatus: 'FREE_UNTIL', freeUntil: '2026-12-01' }).detail)
      .toBe('free to 1 Dec');
    expect(subRegionsCell({ role: 'LITE_USER', subRegions: ['A'], subRegionStatus: 'FREE_UNTIL' }).detail)
      .toBe('free to change');
  });
  it('appends over limit', () => {
    expect(subRegionsCell({ role: 'LITE_USER', subRegions: ['A'], overLimit: true }).detail)
      .toBe('can change · over limit');
  });
});

describe('date phrases', () => {
  it('says Today with the UK clock time', () => {
    expect(formatLastActive('2026-10-27T08:14:00Z', NOW)).toBe('Today, 08:14');
  });
  it('says Yesterday', () => {
    expect(formatLastActive('2026-10-26T12:00:00Z', NOW)).toBe('Yesterday');
  });
  it('omits the year in the same year', () => {
    expect(formatLastActive('2026-10-21T12:00:00Z', NOW)).toBe('21 Oct');
  });
  it('adds the year in another year', () => {
    expect(formatLastActive('2025-10-21T12:00:00Z', NOW)).toBe('21 Oct 2025');
  });
  it('formatJoined gives an em dash for nothing and the date otherwise', () => {
    expect(formatJoined(null)).toBe('—');
    expect(formatJoined('2026-10-19T09:00:00Z')).toBe('19 Oct 2026');
  });
  it('formatJoinedPhrase says today or the date', () => {
    expect(formatJoinedPhrase('2026-10-27T01:00:00Z', NOW)).toBe('today');
    expect(formatJoinedPhrase('2026-10-19T09:00:00Z', NOW)).toBe('19 Oct 2026');
    expect(formatJoinedPhrase(null, NOW)).toBe('—');
  });
  it('formatSentWhen handles none, today and another day', () => {
    expect(formatSentWhen(null, NOW)).toBeNull();
    expect(formatSentWhen('2026-10-27T08:02:00Z', NOW)).toBe('today at 08:02');
    expect(formatSentWhen('2026-10-20T08:02:00Z', NOW)).toBe('20 Oct at 09:02');
  });
});

describe('USER_SORT_ACCESSORS', () => {
  it('reads each sort key', () => {
    expect(USER_SORT_ACCESSORS.username({})).toBe('');
    expect(USER_SORT_ACCESSORS.username({ username: 'zed' })).toBe('zed');
    expect(USER_SORT_ACCESSORS.role({ role: 'PRO_USER' })).toBe('Pro');
    expect(USER_SORT_ACCESSORS.subRegions({ role: 'LITE_USER', subRegions: ['a', 'b'] })).toBe(2);
    expect(USER_SORT_ACCESSORS.subRegions({ role: 'LITE_USER' })).toBe(0);
    expect(USER_SORT_ACCESSORS.subRegions({ role: 'PRO_USER', subRegions: ['a'] })).toBeNull();
    expect(USER_SORT_ACCESSORS.status({ enabled: false, awaitingVerification: true })).toBe('Awaiting verification');
    expect(USER_SORT_ACCESSORS.lastActive({ lastActiveAt: '2026-10-01T00:00:00Z' })).toBe('2026-10-01T00:00:00Z');
    expect(USER_SORT_ACCESSORS.lastActive({})).toBeNull();
    expect(USER_SORT_ACCESSORS.joined({ createdAt: '2026-10-01T00:00:00Z' })).toBe('2026-10-01T00:00:00Z');
    expect(USER_SORT_ACCESSORS.joined({ createdAt: '' })).toBeNull();
  });
});

describe('roleChangeNote', () => {
  const base = { username: 'ann', limit: 3 };
  it('is null for no change', () => {
    expect(roleChangeNote({ ...base, from: 'PRO_USER', to: 'PRO_USER', pickCount: 1 })).toBeNull();
  });
  it('asks a Lite newcomer with no picks to choose', () => {
    expect(roleChangeNote({ ...base, from: 'PRO_USER', to: 'LITE_USER', pickCount: 0 }))
      .toBe("They'll be asked to choose at their next sign-in.");
  });
  it('words returning to Lite with one pick and with several', () => {
    expect(roleChangeNote({ ...base, from: 'PRO_USER', to: 'LITE_USER', pickCount: 1 }))
      .toBe("Lite readers choose up to 3 sub-regions. ann's 1 kept pick comes back into use; the lock carries on from their last change.");
    expect(roleChangeNote({ ...base, from: 'ADMIN', to: 'LITE_USER', pickCount: 2 }))
      .toBe("Lite readers choose up to 3 sub-regions. ann's 2 kept picks come back into use; the lock carries on from their last change.");
  });
  it('is null between Pro and Admin', () => {
    expect(roleChangeNote({ ...base, from: 'PRO_USER', to: 'ADMIN', pickCount: 2 })).toBeNull();
  });
  it('words Lite to Pro with none, one and several picks', () => {
    expect(roleChangeNote({ ...base, from: 'LITE_USER', to: 'PRO_USER', pickCount: 0 }))
      .toBe('Pro sees every sub-region.');
    expect(roleChangeNote({ ...base, from: 'LITE_USER', to: 'ADMIN', pickCount: 1 }))
      .toBe("Admin sees every sub-region. ann's 1 pick is kept, and comes back into use if the account returns to Lite.");
    expect(roleChangeNote({ ...base, from: 'LITE_USER', to: 'PRO_USER', pickCount: 2 }))
      .toBe("Pro sees every sub-region. ann's 2 picks are kept, and come back into use if the account returns to Lite.");
  });
});
