/**
 * `hooks/useOutcomeRatings.js` — the reader's own outcome ratings (outcome-rating-plan.md §2 #5,
 * "The data"): the range read, the optimistic save with its rollback, the overtaken response, a read
 * that must not clobber a write, the LITE read-once rule and the rewind's silence.
 *
 * The API module is mocked at its boundary with promises each test settles by hand, so the ORDER of
 * arrival — the thing the hook exists to be correct about — is the test's, never the scheduler's.
 * Every late-response negative settles inside an awaited `act` (frontend-test-standards.md).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';

vi.mock('../api/outcomeRatingApi.js', () => ({
  fetchMyRatings: vi.fn(),
  putRating: vi.fn(),
}));

import { fetchMyRatings, putRating } from '../api/outcomeRatingApi.js';
import { rowKey, useOutcomeRatings } from '../hooks/useOutcomeRatings.js';

/** A promise the test settles. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

const KEY = rowKey(7, '2026-06-14', 'SUNSET');
const OTHER = rowKey(8, '2026-06-14', 'SUNSET');

const ROW = (locationId, rating, extra = {}) => ({
  locationId,
  locationName: `Place ${locationId}`,
  eventDate: '2026-06-14',
  eventType: 'SUNSET',
  rating,
  updatedAt: '2026-06-14T21:30:00Z',
  ...extra,
});

function mount(overrides = {}) {
  const props = {
    role: 'PRO_USER', rewound: false, from: '2026-06-13', to: '2026-06-15', refreshKey: 'a', ...overrides,
  };
  return renderHook((p) => useOutcomeRatings(p), { initialProps: props });
}

async function settle() {
  await act(async () => { await Promise.resolve(); });
}

beforeEach(() => {
  vi.clearAllMocks();
  fetchMyRatings.mockResolvedValue([]);
});

describe('useOutcomeRatings — the range read', () => {
  it('reads the caller\'s ratings for [from, to] on mount and keys them by location, date and type', async () => {
    fetchMyRatings.mockResolvedValue([ROW(7, 4), ROW(8, null)]);
    const { result } = mount();
    expect(result.current.loaded).toBe(false);
    await settle();
    expect(fetchMyRatings).toHaveBeenCalledWith('2026-06-13', '2026-06-15');
    expect(result.current.loaded).toBe(true);
    expect(result.current.mine.get(KEY)).toEqual({ rating: 4, updatedAt: '2026-06-14T21:30:00Z' });
    // "Not seen" is a value: the key exists, the rating is null.
    expect(result.current.mine.has(OTHER)).toBe(true);
    expect(result.current.mine.get(OTHER).rating).toBeNull();
    expect(result.current.mine.size).toBe(2);
  });

  it('reads again when the oldest known date moves (an older day landed) and when the forecasts refresh', async () => {
    const { rerender } = mount();
    await settle();
    expect(fetchMyRatings).toHaveBeenCalledTimes(1);

    rerender({
      role: 'PRO_USER', rewound: false, from: '2026-06-12', to: '2026-06-15', refreshKey: 'a',
    });
    await settle();
    expect(fetchMyRatings).toHaveBeenLastCalledWith('2026-06-12', '2026-06-15');

    rerender({
      role: 'PRO_USER', rewound: false, from: '2026-06-12', to: '2026-06-15', refreshKey: 'b',
    });
    await settle();
    expect(fetchMyRatings).toHaveBeenCalledTimes(3);
  });

  it('treats a failed read as "loaded, nothing known" — the block stays usable, the PUT is an upsert', async () => {
    fetchMyRatings.mockRejectedValue(new Error('network'));
    const { result } = mount();
    await settle();
    expect(result.current.loaded).toBe(true);
    expect(result.current.mine.size).toBe(0);
  });

  it('a LATE FAILURE of an overtaken read does not mark the range loaded while the newer read is pending', async () => {
    const first = deferred();
    const second = deferred();
    fetchMyRatings.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const { result, rerender } = mount();
    rerender({
      role: 'PRO_USER', rewound: false, from: '2026-06-12', to: '2026-06-15', refreshKey: 'a',
    });
    await act(async () => { first.reject(new Error('late')); });
    expect(result.current.loaded).toBe(false);
    expect(result.current.readFailed).toBe(false);
    await act(async () => { second.resolve([]); });
    expect(result.current.loaded).toBe(true);
    expect(result.current.readFailed).toBe(false);
  });

  it('drops a read that a newer read has overtaken', async () => {
    const first = deferred();
    const second = deferred();
    fetchMyRatings.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const { result, rerender } = mount();
    rerender({
      role: 'PRO_USER', rewound: false, from: '2026-06-12', to: '2026-06-15', refreshKey: 'a',
    });

    await act(async () => { second.resolve([ROW(7, 5)]); });
    await act(async () => { first.resolve([ROW(7, 1)]); });

    expect(result.current.mine.get(KEY).rating).toBe(5);
  });

  it('does not ask for anything without a range', async () => {
    mount({ from: null });
    await settle();
    expect(fetchMyRatings).not.toHaveBeenCalled();
  });
});

describe('useOutcomeRatings — covers(date): which dates have been READ', () => {
  it('is false before any read lands and true for the range once it has', async () => {
    const { result } = mount();
    expect(result.current.covers('2026-06-14')).toBe(false);
    await settle();
    expect(result.current.covers('2026-06-14')).toBe(true);
    expect(result.current.covers('2026-06-13')).toBe(true);
    expect(result.current.covers('2026-06-15')).toBe(true);
    expect(result.current.covers('2026-06-12')).toBe(false);
  });

  it('an older day widening the range is NOT covered until that range\'s own read lands (an absent key there means "not asked", not "unrated")', async () => {
    const wider = deferred();
    fetchMyRatings.mockResolvedValueOnce([]).mockReturnValueOnce(wider.promise);
    const { result, rerender } = mount();
    await settle();
    rerender({
      role: 'PRO_USER', rewound: false, from: '2026-06-12', to: '2026-06-15', refreshKey: 'a',
    });
    await settle();
    // `loaded` stays true (a refresh must not blank the block), the new date is the one that waits.
    expect(result.current.loaded).toBe(true);
    expect(result.current.covers('2026-06-14')).toBe(true);
    expect(result.current.covers('2026-06-12')).toBe(false);

    await act(async () => { wider.resolve([ROW(7, 2)]); });
    expect(result.current.covers('2026-06-12')).toBe(true);
  });

  it('a failed read still covers its range (the PUT is an upsert; nothing better is known)', async () => {
    fetchMyRatings.mockRejectedValue(new Error('network'));
    const { result } = mount();
    await settle();
    expect(result.current.covers('2026-06-14')).toBe(true);
  });

  it('is false for everything while rewound', async () => {
    const { result } = mount({ rewound: true });
    await settle();
    expect(result.current.covers('2026-06-14')).toBe(false);
  });
});

describe('useOutcomeRatings — readFailed (the counts and the badge state a fact, so they need it)', () => {
  it('is false before any read, and false after a read that succeeded', async () => {
    const { result } = mount();
    expect(result.current.readFailed).toBe(false);
    await settle();
    expect(result.current.readFailed).toBe(false);
  });

  it('is true after a FAILED read although loaded and covers() still say "read" (the block stays usable)', async () => {
    fetchMyRatings.mockRejectedValue(new Error('network'));
    const { result } = mount();
    await settle();
    expect(result.current.readFailed).toBe(true);
    expect(result.current.loaded).toBe(true);
    expect(result.current.covers('2026-06-14')).toBe(true);
    expect(result.current.mine.size).toBe(0);
  });

  it('clears when a later read succeeds, and returns when a later read fails', async () => {
    fetchMyRatings.mockRejectedValueOnce(new Error('network'));
    const { result, rerender } = mount();
    await settle();
    expect(result.current.readFailed).toBe(true);
    fetchMyRatings.mockResolvedValueOnce([ROW(7, 4)]);
    await act(async () => { rerender({
      role: 'PRO_USER', rewound: false, from: '2026-06-13', to: '2026-06-15', refreshKey: 'b',
    }); });
    expect(result.current.readFailed).toBe(false);
    expect(result.current.mine.get(KEY).rating).toBe(4);
    fetchMyRatings.mockRejectedValueOnce(new Error('network'));
    await act(async () => { rerender({
      role: 'PRO_USER', rewound: false, from: '2026-06-13', to: '2026-06-15', refreshKey: 'c',
    }); });
    expect(result.current.readFailed).toBe(true);
  });

  it('is never true while rewound — even when a live read had already failed', async () => {
    fetchMyRatings.mockRejectedValue(new Error('network'));
    const { result, rerender } = mount();
    await settle();
    expect(result.current.readFailed).toBe(true);
    await act(async () => { rerender({
      role: 'PRO_USER', rewound: true, from: '2026-06-13', to: '2026-06-15', refreshKey: 'a',
    }); });
    expect(result.current.readFailed).toBe(false);
  });

  it('a stale read\'s late FAILURE cannot silence the counts after a newer read succeeded', async () => {
    const first = deferred();
    const second = deferred();
    fetchMyRatings.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const { result, rerender } = mount();
    rerender({
      role: 'PRO_USER', rewound: false, from: '2026-06-12', to: '2026-06-15', refreshKey: 'a',
    });
    await act(async () => { second.resolve([ROW(7, 4)]); });
    await act(async () => { first.reject(new Error('late')); });
    expect(result.current.readFailed).toBe(false);
    expect(result.current.mine.get(KEY).rating).toBe(4);
  });
});

describe('useOutcomeRatings — wasRead(date): the server was ASKED about the date', () => {
  it('is range-based, false before any read lands and false outside the range read', async () => {
    const { result } = mount();
    expect(result.current.wasRead('2026-06-14')).toBe(false);
    await settle();
    expect(result.current.wasRead('2026-06-14')).toBe(true);
    expect(result.current.wasRead('2026-06-12')).toBe(false);
  });

  it('is NOT true for a LITE reader\'s older day, though covers() is (LITE re-reads nothing)', async () => {
    const { result } = mount({ role: 'LITE_USER' });
    await settle();
    expect(result.current.covers('2026-06-10')).toBe(true);
    expect(result.current.wasRead('2026-06-10')).toBe(false);
    expect(result.current.wasRead('2026-06-14')).toBe(true);
  });

  it('is false while rewound', async () => {
    const { result } = mount({ rewound: true });
    await settle();
    expect(result.current.wasRead('2026-06-14')).toBe(false);
  });
});

describe('useOutcomeRatings — a LITE reader', () => {
  it('reads once and never again, and never writes', async () => {
    const { result, rerender } = mount({ role: 'LITE_USER' });
    await settle();
    rerender({
      role: 'LITE_USER', rewound: false, from: '2026-06-10', to: '2026-06-15', refreshKey: 'z',
    });
    await settle();
    expect(fetchMyRatings).toHaveBeenCalledTimes(1);
    expect(fetchMyRatings).toHaveBeenCalledWith('2026-06-13', '2026-06-15');
    // A LITE reader cannot write, so an older day needs no read of its own to be offered.
    expect(result.current.covers('2026-06-10')).toBe(true);

    let saved;
    await act(async () => { saved = await result.current.rate(7, '2026-06-14', 'SUNSET', 3); });
    expect(saved).toBe(false);
    expect(putRating).not.toHaveBeenCalled();
    expect(result.current.mine.size).toBe(0);
    expect(result.current.canRate).toBe(false);
  });
});

describe('useOutcomeRatings — while rewound', () => {
  it('asks for nothing, offers nothing and refuses a write', async () => {
    const { result } = mount({ rewound: true });
    await settle();
    expect(fetchMyRatings).not.toHaveBeenCalled();
    expect(result.current.loaded).toBe(false);
    expect(result.current.mine.size).toBe(0);
    let saved;
    await act(async () => { saved = await result.current.rate(7, '2026-06-14', 'SUNSET', 3); });
    expect(saved).toBe(false);
    expect(putRating).not.toHaveBeenCalled();
  });
});

describe('useOutcomeRatings — an optimistic save', () => {
  it('writes the value first, PUTs exactly the slot, then takes the server\'s row', async () => {
    const put = deferred();
    putRating.mockReturnValue(put.promise);
    const { result } = mount();
    await settle();

    let saved;
    let pending;
    act(() => { pending = result.current.rate(7, '2026-06-14', 'SUNSET', 4); });
    expect(putRating).toHaveBeenCalledWith({
      locationId: 7, eventDate: '2026-06-14', eventType: 'SUNSET', rating: 4,
    });
    // Optimistic: the value is already there, and the slot is saving.
    expect(result.current.mine.get(KEY).rating).toBe(4);
    expect(result.current.saving.has(KEY)).toBe(true);

    await act(async () => {
      put.resolve(ROW(7, 4, { updatedAt: '2026-06-14T22:00:00Z' }));
      saved = await pending;
    });
    expect(saved).toBe(true);
    expect(result.current.saving.has(KEY)).toBe(false);
    expect(result.current.saveError.has(KEY)).toBe(false);
    expect(result.current.mine.get(KEY)).toEqual({ rating: 4, updatedAt: '2026-06-14T22:00:00Z' });
  });

  it('sends "didn\'t see it" as an explicit null and keeps the key', async () => {
    putRating.mockResolvedValue(ROW(7, null));
    const { result } = mount();
    await settle();
    await act(async () => { await result.current.rate(7, '2026-06-14', 'SUNSET', null); });
    expect(putRating).toHaveBeenCalledWith(expect.objectContaining({ rating: null }));
    expect(result.current.mine.has(KEY)).toBe(true);
    expect(result.current.mine.get(KEY).rating).toBeNull();
  });

  it('restores the PREVIOUS value on failure and reports the error for that key only', async () => {
    fetchMyRatings.mockResolvedValue([ROW(7, 2), ROW(8, 5)]);
    putRating.mockRejectedValue(new Error('500'));
    const { result } = mount();
    await settle();

    let saved;
    await act(async () => { saved = await result.current.rate(7, '2026-06-14', 'SUNSET', 5); });
    expect(saved).toBe(false);
    expect(result.current.mine.get(KEY)).toEqual({ rating: 2, updatedAt: '2026-06-14T21:30:00Z' });
    expect(result.current.mine.get(OTHER).rating).toBe(5);
    expect(result.current.saveError.has(KEY)).toBe(true);
    expect(result.current.saveError.has(OTHER)).toBe(false);
    expect(result.current.saving.has(KEY)).toBe(false);
  });

  it('deletes the key on failure when there was no previous value', async () => {
    putRating.mockRejectedValue(new Error('403'));
    const { result } = mount();
    await settle();
    await act(async () => { await result.current.rate(7, '2026-06-14', 'SUNSET', 3); });
    expect(result.current.mine.has(KEY)).toBe(false);
    expect(result.current.saveError.has(KEY)).toBe(true);
  });

  it('keeps the error through the retry\'s own saving, and clears it on the next success', async () => {
    putRating.mockRejectedValueOnce(new Error('500'));
    const retry = deferred();
    putRating.mockReturnValueOnce(retry.promise);
    const { result } = mount();
    await settle();
    await act(async () => { await result.current.rate(7, '2026-06-14', 'SUNSET', 3); });
    expect(result.current.saveError.has(KEY)).toBe(true);

    let pending;
    act(() => { pending = result.current.rate(7, '2026-06-14', 'SUNSET', 3); });
    // "the line stays while the retry is saving"
    expect(result.current.saving.has(KEY)).toBe(true);
    expect(result.current.saveError.has(KEY)).toBe(true);

    await act(async () => { retry.resolve(ROW(7, 3)); await pending; });
    expect(result.current.saveError.has(KEY)).toBe(false);
    expect(result.current.mine.get(KEY).rating).toBe(3);
  });

  it('clearSaveError clears one key', async () => {
    putRating.mockRejectedValue(new Error('500'));
    const { result } = mount();
    await settle();
    await act(async () => { await result.current.rate(7, '2026-06-14', 'SUNSET', 3); });
    act(() => { result.current.clearSaveError(KEY); });
    expect(result.current.saveError.has(KEY)).toBe(false);
  });

  it('drops the response of an OVERTAKEN request — a slow failure cannot undo a newer tap', async () => {
    const slow = deferred();
    const fast = deferred();
    putRating.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise);
    const { result } = mount();
    await settle();

    let first;
    let second;
    act(() => { first = result.current.rate(7, '2026-06-14', 'SUNSET', 2); });
    act(() => { second = result.current.rate(7, '2026-06-14', 'SUNSET', 5); });

    await act(async () => { fast.resolve(ROW(7, 5)); await second; });
    expect(result.current.mine.get(KEY).rating).toBe(5);
    expect(result.current.saving.has(KEY)).toBe(false);

    // The older request now FAILS. It must change nothing.
    let firstSaved;
    await act(async () => { slow.reject(new Error('timeout')); firstSaved = await first; });
    expect(firstSaved).toBe(false);
    expect(result.current.mine.get(KEY).rating).toBe(5);
    expect(result.current.saveError.has(KEY)).toBe(false);
  });

  it('drops an overtaken SUCCESS too — the older answer cannot repaint what the newer tap decided', async () => {
    const slow = deferred();
    const fast = deferred();
    putRating.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise);
    const { result } = mount();
    await settle();

    let first;
    act(() => { first = result.current.rate(7, '2026-06-14', 'SUNSET', 2); });
    act(() => { result.current.rate(7, '2026-06-14', 'SUNSET', 5); });

    await act(async () => { slow.resolve(ROW(7, 2)); await first; });
    // The newer request is still in flight: the slot is still saving and still shows the newer tap.
    expect(result.current.mine.get(KEY).rating).toBe(5);
    expect(result.current.saving.has(KEY)).toBe(true);
    await act(async () => { fast.resolve(ROW(7, 5)); });
  });

  it('rolls back to the value held BEFORE the first of two overlapping saves', async () => {
    fetchMyRatings.mockResolvedValue([ROW(7, 1)]);
    const a = deferred();
    const b = deferred();
    putRating.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);
    const { result } = mount();
    await settle();

    act(() => { result.current.rate(7, '2026-06-14', 'SUNSET', 2); });
    let second;
    act(() => { second = result.current.rate(7, '2026-06-14', 'SUNSET', 3); });
    await act(async () => { b.reject(new Error('500')); await second; });
    expect(result.current.mine.get(KEY).rating).toBe(1);
    await act(async () => { a.reject(new Error('500')); });
  });

  it('a save that resolves after unmount reports false (it landed on nothing)', async () => {
    const put = deferred();
    putRating.mockReturnValue(put.promise);
    const { result, unmount } = mount();
    await settle();
    let pending;
    act(() => { pending = result.current.rate(7, '2026-06-14', 'SUNSET', 4); });
    unmount();
    let saved;
    await act(async () => { put.resolve(ROW(7, 4)); saved = await pending; });
    expect(saved).toBe(false);
  });
});

describe('useOutcomeRatings — a read cannot clobber a write', () => {
  it('keeps a value written while the read was in flight, whatever the read says', async () => {
    const read = deferred();
    fetchMyRatings.mockReturnValueOnce(read.promise);
    const put = deferred();
    putRating.mockReturnValue(put.promise);
    const { result } = mount();

    let pending;
    act(() => { pending = result.current.rate(7, '2026-06-14', 'SUNSET', 4); });
    // The read was served before the PUT committed: it still says 2, and carries a row of its own.
    await act(async () => { read.resolve([ROW(7, 2), ROW(8, 3)]); });
    expect(result.current.mine.get(KEY).rating).toBe(4);
    expect(result.current.mine.get(OTHER).rating).toBe(3);

    await act(async () => { put.resolve(ROW(7, 4)); await pending; });
    expect(result.current.mine.get(KEY).rating).toBe(4);
  });

  it('keeps a value whose write SETTLED while the read was in flight', async () => {
    const read = deferred();
    fetchMyRatings.mockResolvedValueOnce([]).mockReturnValueOnce(read.promise);
    putRating.mockResolvedValue(ROW(7, 4));
    const { result, rerender } = mount();
    await settle();

    rerender({
      role: 'PRO_USER', rewound: false, from: '2026-06-12', to: '2026-06-15', refreshKey: 'a',
    });
    await act(async () => { await result.current.rate(7, '2026-06-14', 'SUNSET', 4); });
    await act(async () => { read.resolve([]); });
    expect(result.current.mine.get(KEY).rating).toBe(4);
  });

  it('takes the server\'s word for a key whose write SETTLED before the read began (another device may have changed it)', async () => {
    putRating.mockResolvedValue(ROW(7, 4));
    fetchMyRatings.mockResolvedValueOnce([]).mockResolvedValueOnce([ROW(7, 1)]);
    const { result, rerender } = mount();
    await settle();
    await act(async () => { await result.current.rate(7, '2026-06-14', 'SUNSET', 4); });
    rerender({
      role: 'PRO_USER', rewound: false, from: '2026-06-13', to: '2026-06-15', refreshKey: 'b',
    });
    await settle();
    expect(result.current.mine.get(KEY).rating).toBe(1);
  });

  it('takes the server\'s word for a key nothing local has touched', async () => {
    fetchMyRatings.mockResolvedValueOnce([ROW(7, 2)]).mockResolvedValueOnce([ROW(7, 5)]);
    const { result, rerender } = mount();
    await settle();
    rerender({
      role: 'PRO_USER', rewound: false, from: '2026-06-13', to: '2026-06-15', refreshKey: 'b',
    });
    await settle();
    expect(result.current.mine.get(KEY).rating).toBe(5);
  });
});

describe('rowKey', () => {
  it('is the location, date and event type, in that order', () => {
    expect(rowKey(7, '2026-06-14', 'SUNSET')).toBe('7|2026-06-14|SUNSET');
  });
});
