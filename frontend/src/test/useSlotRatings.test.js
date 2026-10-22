/**
 * `hooks/useSlotRatings.js` — the Admin's per-slot "All ratings" read (outcome-rating-plan.md §2 #7,
 * §3 O5a): fetched on the FIRST expand, re-fetched after the admin's own save lands and only for the
 * slot that save was for, never for a reader who is not enabled, and never with a stale answer from
 * another slot or an overtaken request.
 *
 * The API module is mocked at its boundary with promises each test settles by hand, so the order of
 * arrival is the test's. Every late-response negative settles inside an awaited `act`
 * (frontend-test-standards.md).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';

vi.mock('../api/outcomeRatingApi.js', () => ({
  fetchAllRatingsForSlot: vi.fn(),
}));

import { fetchAllRatingsForSlot } from '../api/outcomeRatingApi.js';
import { rowKey } from '../hooks/useOutcomeRatings.js';
import { useSlotRatings } from '../hooks/useSlotRatings.js';

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

const SLOT = { locationId: 7, date: '2026-06-14', eventType: 'SUNSET' };
const KEY = rowKey(7, '2026-06-14', 'SUNSET');
const OTHER = { locationId: 8, date: '2026-06-14', eventType: 'SUNSET' };

const ANSWER = (names = ['jgregory', 'mhart']) => ({
  eventTime: '2026-06-14T20:10:00Z',
  forecastRunAt: '2026-06-14T17:05:00Z',
  pipelineRunId: 4,
  ratings: names.map((username, i) => ({
    username, rating: 3 + (i % 2), updatedAt: '2026-06-14T21:30:00Z', mine: i === 0,
  })),
});

function mount(overrides = {}) {
  const props = {
    enabled: true, ...SLOT, open: false, ...overrides,
  };
  return renderHook((p) => useSlotRatings(p), { initialProps: props });
}

async function settle() {
  await act(async () => { await Promise.resolve(); });
}

beforeEach(() => {
  vi.clearAllMocks();
  fetchAllRatingsForSlot.mockResolvedValue(ANSWER());
});

describe('useSlotRatings — when it reads', () => {
  it('asks for nothing while the box is collapsed, however long the callout stays open', async () => {
    const { result } = mount();
    await settle();
    expect(fetchAllRatingsForSlot).not.toHaveBeenCalled();
    expect(result.current.rows).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it('fetches on the first expand, for exactly that slot, and normalises the rows', async () => {
    const pending = deferred();
    fetchAllRatingsForSlot.mockReturnValue(pending.promise);
    const { result, rerender } = mount();
    rerender({ enabled: true, ...SLOT, open: true });
    await settle();

    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(1);
    expect(fetchAllRatingsForSlot).toHaveBeenCalledWith(7, '2026-06-14', 'SUNSET');
    expect(result.current.loading).toBe(true);
    expect(result.current.rows).toBeNull();

    await act(async () => {
      pending.resolve({
        ...ANSWER([]),
        ratings: [{ username: 'jgregory', rating: undefined, updatedAt: undefined, mine: 1 }],
      });
    });
    expect(result.current.loading).toBe(false);
    expect(result.current.failed).toBe(false);
    expect(result.current.rows).toEqual([
      { username: 'jgregory', rating: null, updatedAt: null, mine: true, seen: null },
    ]);
    expect(result.current.eventTime).toBe('2026-06-14T20:10:00Z');
    expect(result.current.forecastRunAt).toBe('2026-06-14T17:05:00Z');
  });

  it('carries each row\'s seen tag through, and null for a row without one (valley-mist-plan M3)', async () => {
    fetchAllRatingsForSlot.mockResolvedValue({
      ...ANSWER([]),
      ratings: [
        { username: 'zoe', rating: 5, updatedAt: '2026-06-14T21:30:00Z', mine: false, seen: 'MIST' },
        { username: 'pat', rating: null, updatedAt: '2026-06-14T21:31:00Z', mine: false, seen: 'NOT_SEEN' },
        { username: 'mhart', rating: 4, updatedAt: '2026-06-14T21:32:00Z', mine: false },
      ],
    });
    const { result, rerender } = mount();
    rerender({ enabled: true, ...SLOT, open: true });
    await settle();

    expect(result.current.rows.map((r) => [r.username, r.seen]))
      .toEqual([['zoe', 'MIST'], ['pat', 'NOT_SEEN'], ['mhart', null]]);
  });

  it('does not ask again when the box is collapsed and re-expanded (first expand only)', async () => {
    const { result, rerender } = mount({ open: true });
    await settle();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(1);
    expect(result.current.rows).toHaveLength(2);

    rerender({ enabled: true, ...SLOT, open: false });
    rerender({ enabled: true, ...SLOT, open: true });
    await settle();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(1);
    expect(result.current.rows).toHaveLength(2);
  });

  it.each([
    ['not enabled (a PRO or LITE reader, or no block mounted)', { enabled: false, open: true }],
    ['no slot yet (a night or a window with no id)', { locationId: null, open: true }],
  ])('never fetches when %s', async (_name, overrides) => {
    const { result } = mount(overrides);
    await settle();
    expect(fetchAllRatingsForSlot).not.toHaveBeenCalled();
    expect(result.current.rows).toBeNull();
    expect(result.current.loading).toBe(false);
    // A refetch from the callout's save wrapper is a no-op for the same reason.
    act(() => result.current.refetch(KEY));
    expect(fetchAllRatingsForSlot).not.toHaveBeenCalled();
  });
});

describe('useSlotRatings — after the admin\'s own save', () => {
  it('re-fetches the slot it holds, keeping the old rows on screen until the new ones land', async () => {
    const { result } = mount({ open: true });
    await settle();
    expect(result.current.rows).toHaveLength(2);

    const second = deferred();
    fetchAllRatingsForSlot.mockReturnValue(second.promise);
    act(() => result.current.refetch(KEY));
    await settle();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(2);
    // No flicker: the stale list stays, and nothing says "loading" over it.
    expect(result.current.rows).toHaveLength(2);
    expect(result.current.loading).toBe(false);

    await act(async () => { second.resolve(ANSWER(['jgregory', 'mhart', 'new'])); });
    expect(result.current.rows.map((r) => r.username)).toEqual(['jgregory', 'mhart', 'new']);
  });

  it('re-fetches when the list was opened once and is collapsed now (what it holds is stale either way)', async () => {
    const { result, rerender } = mount({ open: true });
    await settle();
    rerender({ enabled: true, ...SLOT, open: false });
    act(() => result.current.refetch(KEY));
    await settle();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(2);
  });

  it('asks for nothing when the list was never fetched — the first expand will', async () => {
    const { result } = mount({ open: false });
    act(() => result.current.refetch(KEY));
    await settle();
    expect(fetchAllRatingsForSlot).not.toHaveBeenCalled();
  });

  it('asks for nothing for a slot other than the one it holds (a save that finished after the admin moved on)', async () => {
    const { result } = mount({ open: true });
    await settle();
    act(() => result.current.refetch(rowKey(8, '2026-06-14', 'SUNSET')));
    act(() => result.current.refetch(null));
    await settle();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(1);
  });

  it('drops the answer to an overtaken re-fetch: the newest request decides the rows', async () => {
    const { result } = mount({ open: true });
    await settle();
    const slow = deferred();
    const fast = deferred();
    fetchAllRatingsForSlot.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise);
    act(() => result.current.refetch(KEY));
    act(() => result.current.refetch(KEY));
    await settle();

    await act(async () => { fast.resolve(ANSWER(['newest'])); });
    await act(async () => { slow.resolve(ANSWER(['older'])); });
    expect(result.current.rows.map((r) => r.username)).toEqual(['newest']);
  });
});

describe('useSlotRatings — a failed read', () => {
  it('reports failed with no rows, stays quiet about loading, and a re-open tries again', async () => {
    fetchAllRatingsForSlot.mockRejectedValueOnce(new Error('502'));
    const { result, rerender } = mount({ open: true });
    await settle();
    expect(result.current.failed).toBe(true);
    expect(result.current.rows).toBeNull();
    expect(result.current.loading).toBe(false);
    // It does not retry by itself.
    await settle();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(1);

    rerender({ enabled: true, ...SLOT, open: false });
    rerender({ enabled: true, ...SLOT, open: true });
    await settle();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(2);
    expect(result.current.failed).toBe(false);
    expect(result.current.rows).toHaveLength(2);
  });

  it('a failed re-fetch after a good read reports failed but keeps the rows already held (stale, not unreadable)', async () => {
    const { result } = mount({ open: true });
    await settle();
    fetchAllRatingsForSlot.mockRejectedValueOnce(new Error('502'));
    act(() => result.current.refetch(KEY));
    await settle();
    expect(result.current.failed).toBe(true);
    expect(result.current.rows).toHaveLength(2);
    expect(result.current.loading).toBe(false);
  });

  it('closing the box forgets a failure, so the next open reads "loading" at once, never the old failure', async () => {
    fetchAllRatingsForSlot.mockRejectedValueOnce(new Error('502'));
    const { result, rerender } = mount({ open: true });
    await settle();
    expect(result.current.failed).toBe(true);
    rerender({ enabled: true, ...SLOT, open: false });
    await settle();
    expect(result.current.failed).toBe(false);

    const retry = deferred();
    fetchAllRatingsForSlot.mockReturnValue(retry.promise);
    rerender({ enabled: true, ...SLOT, open: true });
    // The very render that opens it: loading, not failed.
    expect(result.current.failed).toBe(false);
    expect(result.current.loading).toBe(true);
    await act(async () => { retry.resolve(ANSWER()); });
    expect(result.current.rows).toHaveLength(2);
  });

  it('reads a payload with no ratings array as an empty list, never a crash', async () => {
    fetchAllRatingsForSlot.mockResolvedValue({ eventTime: null, forecastRunAt: null, ratings: null });
    const { result } = mount({ open: true });
    await settle();
    expect(result.current.rows).toEqual([]);
  });
});

describe('useSlotRatings — a different slot, and leaving', () => {
  it('drops the rows of the slot it leaves and fetches the new slot afresh on its own first expand', async () => {
    const { result, rerender } = mount({ open: true });
    await settle();
    expect(result.current.rows).toHaveLength(2);

    // The callout resets `open` with the selection (it owns `adminOpen`).
    rerender({ enabled: true, ...OTHER, open: false });
    expect(result.current.rows).toBeNull();
    await settle();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(1);

    rerender({ enabled: true, ...OTHER, open: true });
    await settle();
    expect(fetchAllRatingsForSlot).toHaveBeenLastCalledWith(8, '2026-06-14', 'SUNSET');
  });

  it('a slot revisited reads fresh: the first slot\'s held rows did not survive the visit elsewhere', async () => {
    const { rerender } = mount({ open: true });
    await settle();
    rerender({ enabled: true, ...OTHER, open: false });
    rerender({ enabled: true, ...SLOT, open: true });
    await settle();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(2);
  });

  it('drops a response that lands after the admin moved to another slot', async () => {
    const late = deferred();
    fetchAllRatingsForSlot.mockReturnValueOnce(late.promise);
    const { result, rerender } = mount({ open: true });
    await settle();
    rerender({ enabled: true, ...OTHER, open: false });
    await act(async () => { late.resolve(ANSWER(['stranger'])); });
    expect(result.current.rows).toBeNull();
    rerender({ enabled: true, ...OTHER, open: true });
    await settle();
    // The other slot got its OWN read, not the first slot's answer.
    expect(fetchAllRatingsForSlot).toHaveBeenLastCalledWith(8, '2026-06-14', 'SUNSET');

    // And the abandoned answer was never kept: coming back to the first slot reads it afresh.
    rerender({ enabled: true, ...SLOT, open: false });
    fetchAllRatingsForSlot.mockResolvedValue(ANSWER(['fresh']));
    rerender({ enabled: true, ...SLOT, open: true });
    await settle();
    expect(fetchAllRatingsForSlot).toHaveBeenLastCalledWith(7, '2026-06-14', 'SUNSET');
    expect(result.current.rows.map((r) => r.username)).toEqual(['fresh']);
  });

  it('a save that lands after the callout has gone asks for nothing', async () => {
    const { result, unmount } = mount({ open: true });
    await settle();
    const { refetch } = result.current;
    unmount();
    refetch(KEY);
    await settle();
    expect(fetchAllRatingsForSlot).toHaveBeenCalledTimes(1);
  });
});
