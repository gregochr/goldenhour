/**
 * `hooks/useOutcomeRatings.js` — the "what did you see" tag (valley-mist-plan M3): the read carries it,
 * `rateTag` PUTs the SAME rating with it, the optimistic value follows the server's keep-or-drop rule,
 * a failed tag save rolls back the tag alone and reports on its OWN set (so the block never leaves its
 * "You rated" layout), and "didn't see it" carries NOT_SEEN.
 *
 * Same style as `useOutcomeRatings.test.js`: the API module is mocked at its boundary with promises
 * the test settles by hand.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';

vi.mock('../api/outcomeRatingApi.js', () => ({
  fetchMyRatings: vi.fn(),
  putRating: vi.fn(),
}));

import { fetchMyRatings, putRating } from '../api/outcomeRatingApi.js';
import { rowKey, settledSeen, useOutcomeRatings } from '../hooks/useOutcomeRatings.js';

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

const KEY = rowKey(7, '2026-06-14', 'SUNSET');
const SLOT = [7, '2026-06-14', 'SUNSET'];

const ROW = (rating, extra = {}) => ({
  locationId: 7,
  locationName: 'Place 7',
  eventDate: '2026-06-14',
  eventType: 'SUNSET',
  rating,
  updatedAt: '2026-06-14T21:30:00Z',
  ...extra,
});

async function mounted(rows) {
  fetchMyRatings.mockResolvedValue(rows);
  const view = renderHook((p) => useOutcomeRatings(p), {
    initialProps: {
      role: 'PRO_USER', rewound: false, from: '2026-06-13', to: '2026-06-15', refreshKey: 'a',
    },
  });
  await act(async () => { await Promise.resolve(); });
  return view;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('the tag in the read', () => {
  it('carries a served seen onto the record, and leaves an untagged record exactly as it was', async () => {
    const { result } = await mounted([ROW(4, { seen: 'MIST' }), ROW(3, { locationId: 8 })]);
    expect(result.current.mine.get(KEY)).toEqual({
      rating: 4, updatedAt: '2026-06-14T21:30:00Z', seen: 'MIST',
    });
    expect(result.current.mine.get(rowKey(8, '2026-06-14', 'SUNSET')))
      .toEqual({ rating: 3, updatedAt: '2026-06-14T21:30:00Z' });
  });
});

describe('rateTag', () => {
  it('PUTs the SAME rating again with the tag, and the stored tag lands from the server\'s word', async () => {
    const { result } = await mounted([ROW(4)]);
    putRating.mockResolvedValue({ rating: 4, updatedAt: 'T2', seen: 'INVERSION' });

    let ok;
    await act(async () => { ok = await result.current.rateTag(...SLOT, 'INVERSION'); });

    expect(ok).toBe(true);
    expect(putRating).toHaveBeenCalledWith({
      locationId: 7, eventDate: '2026-06-14', eventType: 'SUNSET', rating: 4, seen: 'INVERSION',
    });
    expect(result.current.mine.get(KEY)).toEqual({ rating: 4, updatedAt: 'T2', seen: 'INVERSION' });
    expect(result.current.tagSaving.has(KEY)).toBe(false);
    expect(result.current.tagSaveError.has(KEY)).toBe(false);
  });

  it('sends an explicit null to clear, and the record loses the tag', async () => {
    const { result } = await mounted([ROW(4, { seen: 'MIST' })]);
    putRating.mockResolvedValue({ rating: 4, updatedAt: 'T2' });

    await act(async () => { await result.current.rateTag(...SLOT, null); });

    expect(putRating.mock.calls[0][0].seen).toBeNull();
    expect(result.current.mine.get(KEY)).toEqual({ rating: 4, updatedAt: 'T2' });
  });

  it('is optimistic on its OWN set: tagSaving is on, saving is not, and the tag shows before the answer', async () => {
    const { result } = await mounted([ROW(4)]);
    const pending = deferred();
    putRating.mockReturnValue(pending.promise);

    let save;
    act(() => { save = result.current.rateTag(...SLOT, 'FLAT'); });

    expect(result.current.tagSaving.has(KEY)).toBe(true);
    expect(result.current.saving.has(KEY)).toBe(false);
    expect(result.current.mine.get(KEY).seen).toBe('FLAT');
    await act(async () => { pending.resolve({ rating: 4, updatedAt: 'T2', seen: 'FLAT' }); await save; });
    expect(result.current.tagSaving.has(KEY)).toBe(false);
  });

  it('rolls back the tag ALONE on failure — the star was never in question — and flags the tag set', async () => {
    const { result } = await mounted([ROW(4, { seen: 'COLOUR' })]);
    putRating.mockRejectedValue(new Error('500'));

    let ok;
    await act(async () => { ok = await result.current.rateTag(...SLOT, 'MIST'); });

    expect(ok).toBe(false);
    expect(result.current.mine.get(KEY)).toEqual({
      rating: 4, updatedAt: '2026-06-14T21:30:00Z', seen: 'COLOUR',
    });
    expect(result.current.tagSaveError.has(KEY)).toBe(true);
    expect(result.current.saveError.has(KEY)).toBe(false);
    expect(result.current.tagSaving.has(KEY)).toBe(false);
  });

  it('clears the tag failure on the next success, and with clearSaveError', async () => {
    const { result } = await mounted([ROW(4)]);
    putRating.mockRejectedValueOnce(new Error('500'));
    await act(async () => { await result.current.rateTag(...SLOT, 'MIST'); });
    expect(result.current.tagSaveError.has(KEY)).toBe(true);

    act(() => { result.current.clearSaveError(KEY); });
    expect(result.current.tagSaveError.has(KEY)).toBe(false);

    putRating.mockRejectedValueOnce(new Error('500'));
    await act(async () => { await result.current.rateTag(...SLOT, 'MIST'); });
    putRating.mockResolvedValueOnce({ rating: 4, updatedAt: 'T3', seen: 'MIST' });
    await act(async () => { await result.current.rateTag(...SLOT, 'MIST'); });
    expect(result.current.tagSaveError.has(KEY)).toBe(false);
  });

  it('refuses, asking nothing, for a slot with no star — unrated, or "didn\'t see it"', async () => {
    const { result } = await mounted([ROW(null, { seen: 'NOT_SEEN' })]);

    let unrated;
    let notSeen;
    await act(async () => {
      unrated = await result.current.rateTag(9, '2026-06-14', 'SUNSET', 'MIST');
      notSeen = await result.current.rateTag(...SLOT, 'MIST');
    });

    expect(unrated).toBe(false);
    expect(notSeen).toBe(false);
    expect(putRating).not.toHaveBeenCalled();
  });

  it('is refused for a LITE reader, who HOLDS the star but may not write', async () => {
    fetchMyRatings.mockResolvedValue([ROW(4)]);
    const lite = renderHook((p) => useOutcomeRatings(p), {
      initialProps: { role: 'LITE_USER', from: '2026-06-13', to: '2026-06-15' },
    });
    await act(async () => { await Promise.resolve(); });
    // Proves the refusal reaches the role gate rather than the "no star held" guard.
    expect(lite.result.current.mine.get(KEY).rating).toBe(4);
    let ok;
    await act(async () => { ok = await lite.result.current.rateTag(...SLOT, 'MIST'); });
    expect(ok).toBe(false);
    expect(putRating).not.toHaveBeenCalled();
  });
});

describe('writes for one slot are serialised', () => {
  it('a star tapped while a tag save is in flight is queued: its PUT is issued only after the tag settles', async () => {
    const { result } = await mounted([ROW(3)]);
    const tag = deferred();
    const star = deferred();
    putRating.mockReturnValueOnce(tag.promise).mockReturnValueOnce(star.promise);

    let tagSave;
    let starSave;
    act(() => { tagSave = result.current.rateTag(...SLOT, 'MIST'); });
    act(() => { starSave = result.current.rate(...SLOT, 5); });
    await act(async () => { await Promise.resolve(); });

    expect(putRating).toHaveBeenCalledTimes(1);
    expect(putRating.mock.calls[0][0]).toMatchObject({ rating: 3, seen: 'MIST' });

    await act(async () => { tag.resolve({ rating: 3, updatedAt: 'T2', seen: 'MIST' }); await tagSave; });
    expect(putRating).toHaveBeenCalledTimes(2);
    expect(putRating.mock.calls[1][0].rating).toBe(5);
    expect(putRating.mock.calls[1][0].seen).toBeUndefined();

    await act(async () => { star.resolve({ rating: 5, updatedAt: 'T3', seen: 'MIST' }); await starSave; });
    expect(result.current.mine.get(KEY)).toEqual({ rating: 5, updatedAt: 'T3', seen: 'MIST' });
    expect(result.current.saving.has(KEY)).toBe(false);
    expect(result.current.tagSaving.has(KEY)).toBe(false);
  });

  it('a tag tapped while a star save is in flight is queued, and carries the star the slot holds when its turn comes', async () => {
    const { result } = await mounted([ROW(3)]);
    const star = deferred();
    const tag = deferred();
    putRating.mockReturnValueOnce(star.promise).mockReturnValueOnce(tag.promise);

    let starSave;
    let tagSave;
    act(() => { starSave = result.current.rate(...SLOT, 5); });
    act(() => { tagSave = result.current.rateTag(...SLOT, 'FLAT'); });
    await act(async () => { await Promise.resolve(); });
    expect(putRating).toHaveBeenCalledTimes(1);

    await act(async () => { star.resolve({ rating: 5, updatedAt: 'T2' }); await starSave; });

    expect(putRating).toHaveBeenCalledTimes(2);
    expect(putRating.mock.calls[1][0]).toMatchObject({ rating: 5, seen: 'FLAT' });
    await act(async () => { tag.resolve({ rating: 5, updatedAt: 'T3', seen: 'FLAT' }); await tagSave; });
    expect(result.current.mine.get(KEY)).toEqual({ rating: 5, updatedAt: 'T3', seen: 'FLAT' });
  });

  it('a failed write does not break the queue: the write behind it still runs', async () => {
    const { result } = await mounted([ROW(3)]);
    const tag = deferred();
    const star = deferred();
    putRating.mockReturnValueOnce(tag.promise).mockReturnValueOnce(star.promise);

    let tagSave;
    let starSave;
    act(() => { tagSave = result.current.rateTag(...SLOT, 'MIST'); });
    act(() => { starSave = result.current.rate(...SLOT, 5); });
    await act(async () => { tag.reject(new Error('500')); await tagSave; });
    expect(result.current.tagSaveError.has(KEY)).toBe(true);
    expect(putRating).toHaveBeenCalledTimes(2);

    await act(async () => { star.resolve({ rating: 5, updatedAt: 'T3' }); await starSave; });
    expect(result.current.mine.get(KEY)).toEqual({ rating: 5, updatedAt: 'T3' });
    expect(result.current.saving.has(KEY)).toBe(false);
    expect(result.current.tagSaving.has(KEY)).toBe(false);
  });

  it('a queued tag whose slot lost its star by its turn asks for nothing', async () => {
    const { result } = await mounted([ROW(3)]);
    const star = deferred();
    putRating.mockReturnValueOnce(star.promise);

    let starSave;
    let tagSave;
    act(() => { starSave = result.current.rate(...SLOT, null); });
    act(() => { tagSave = result.current.rateTag(...SLOT, 'MIST'); });
    let tagResult;
    await act(async () => {
      star.resolve({ rating: null, updatedAt: 'T2', seen: 'NOT_SEEN' });
      await starSave;
      tagResult = await tagSave;
    });

    expect(tagResult).toBe(false);
    expect(putRating).toHaveBeenCalledTimes(1);
  });

  it('writes for DIFFERENT slots are not queued behind each other', async () => {
    const { result } = await mounted([ROW(3), ROW(3, { locationId: 8 })]);
    const first = deferred();
    const second = deferred();
    putRating.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);

    act(() => { result.current.rate(...SLOT, 5); });
    act(() => { result.current.rate(8, '2026-06-14', 'SUNSET', 4); });

    expect(putRating).toHaveBeenCalledTimes(2);
    await act(async () => {
      first.resolve({ rating: 5, updatedAt: 'T2' });
      second.resolve({ rating: 4, updatedAt: 'T2' });
      await Promise.resolve();
    });
  });
});

describe('rate and the tag it carries', () => {
  it('"didn\'t see it" (null) is sent WITH NOT_SEEN, and the record holds it', async () => {
    const { result } = await mounted([]);
    putRating.mockResolvedValue({ rating: null, updatedAt: 'T2', seen: 'NOT_SEEN' });

    await act(async () => { await result.current.rate(...SLOT, null); });

    expect(putRating).toHaveBeenCalledWith({
      locationId: 7, eventDate: '2026-06-14', eventType: 'SUNSET', rating: null, seen: 'NOT_SEEN',
    });
    expect(result.current.mine.get(KEY)).toEqual({ rating: null, updatedAt: 'T2', seen: 'NOT_SEEN' });
  });

  it('a star press leaves seen OUT of the PUT, so the server keeps the earlier tag', async () => {
    const { result } = await mounted([ROW(3, { seen: 'MIST' })]);
    putRating.mockResolvedValue({ rating: 5, updatedAt: 'T2', seen: 'MIST' });

    await act(async () => { await result.current.rate(...SLOT, 5); });

    expect(putRating.mock.calls[0][0].seen).toBeUndefined();
    expect(result.current.mine.get(KEY)).toEqual({ rating: 5, updatedAt: 'T2', seen: 'MIST' });
  });

  it('shows the kept tag optimistically while the star saves', async () => {
    const { result } = await mounted([ROW(3, { seen: 'MIST' })]);
    const pending = deferred();
    putRating.mockReturnValue(pending.promise);

    let save;
    act(() => { save = result.current.rate(...SLOT, 5); });
    expect(result.current.mine.get(KEY)).toEqual({
      rating: 5, updatedAt: '2026-06-14T21:30:00Z', seen: 'MIST',
    });
    await act(async () => { pending.resolve({ rating: 5, updatedAt: 'T2', seen: 'MIST' }); await save; });
  });

  it('a star replacing "didn\'t see it" drops the NOT_SEEN tag, as the server does', async () => {
    const { result } = await mounted([ROW(null, { seen: 'NOT_SEEN' })]);
    const pending = deferred();
    putRating.mockReturnValue(pending.promise);

    let save;
    act(() => { save = result.current.rate(...SLOT, 4); });
    expect(result.current.mine.get(KEY).seen).toBeUndefined();
    await act(async () => { pending.resolve({ rating: 4, updatedAt: 'T2' }); await save; });
    expect(result.current.mine.get(KEY)).toEqual({ rating: 4, updatedAt: 'T2' });
  });
});

describe('settledSeen — the keep-or-drop rule, mirroring OutcomeRatingService', () => {
  it('takes an explicit tag or null as it stands', () => {
    expect(settledSeen('MIST', 4, 'FLAT')).toBe('FLAT');
    expect(settledSeen('MIST', 4, null)).toBeNull();
    expect(settledSeen(null, 4, 'COLOUR')).toBe('COLOUR');
  });

  it('keeps the earlier tag when none is sent and it can stand beside the rating', () => {
    expect(settledSeen('MIST', 5, undefined)).toBe('MIST');
    expect(settledSeen('NOT_SEEN', null, undefined)).toBe('NOT_SEEN');
  });

  it('drops an earlier tag the new rating contradicts', () => {
    expect(settledSeen('NOT_SEEN', 4, undefined)).toBeNull();
    expect(settledSeen('MIST', null, undefined)).toBeNull();
  });

  it('is null with nothing to keep', () => {
    expect(settledSeen(null, 4, undefined)).toBeNull();
    expect(settledSeen(undefined, null, undefined)).toBeNull();
  });
});
