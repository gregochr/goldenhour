import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { deferred, NOW, settings } from './askFixtures.js';

vi.mock('../api/askApi.js', () => ({ getAskSettings: vi.fn() }));
import { getAskSettings } from '../api/askApi.js';
import useAskAllowance from '../hooks/useAskAllowance.js';

beforeEach(() => {
  vi.resetAllMocks();
  localStorage.clear();
});

afterEach(() => vi.useRealTimers());

describe('useAskAllowance', () => {
  it('claims nothing about the allowance until the server has answered', () => {
    getAskSettings.mockReturnValue(deferred().promise);

    const { result } = renderHook(() => useAskAllowance());

    expect(result.current).toMatchObject({
      status: 'loading', loaded: false, enabled: null, used: null, limit: null, left: null,
      typedAvailable: null,
    });
  });

  it('takes the server’s figures as they are', async () => {
    getAskSettings.mockResolvedValue(settings({ used: 1, left: 2 }));

    const { result } = renderHook(() => useAskAllowance());

    await waitFor(() => expect(result.current.loaded).toBe(true));
    expect(result.current).toMatchObject({
      status: 'ready', enabled: true, used: 1, limit: 3, left: 2, typedAvailable: true,
    });
  });

  it('reads a switched-off Ask as enabled:false with its zeros', async () => {
    getAskSettings.mockResolvedValue({
      enabled: false, used: 0, limit: 0, left: 0, typedAvailable: false,
    });

    const { result } = renderHook(() => useAskAllowance());

    await waitFor(() => expect(result.current.loaded).toBe(true));
    expect(result.current).toMatchObject({ enabled: false, left: 0, typedAvailable: false });
  });

  it('refetches on demand and shows what the server says THEN, not a local count-down', async () => {
    getAskSettings.mockResolvedValueOnce(settings({ used: 0, left: 3 }))
      .mockResolvedValueOnce(settings({ used: 1, left: 2 }));
    const { result } = renderHook(() => useAskAllowance());
    await waitFor(() => expect(result.current.left).toBe(3));

    act(() => result.current.refetch());

    await waitFor(() => expect(result.current.left).toBe(2));
    expect(getAskSettings).toHaveBeenCalledTimes(2);
  });

  describe('a read started before a served answer, and the answer itself', () => {
    /** The hook loaded with {@code 3 of 3}, and a hand-held read in flight. */
    const loadedWithReadInFlight = async () => {
      getAskSettings.mockResolvedValueOnce(settings({ used: 0, left: 3 }));
      const { result } = renderHook(() => useAskAllowance());
      await waitFor(() => expect(result.current.left).toBe(3));
      const read = deferred();
      getAskSettings.mockReturnValueOnce(read.promise);
      act(() => result.current.refetch());
      await waitFor(() => expect(getAskSettings).toHaveBeenCalledTimes(2));
      return { result, read };
    };

    it('keeps the served figure when an OLDER read resolves after it', async () => {
      const { result, read } = await loadedWithReadInFlight();

      act(() => result.current.applyServed({ left: 1, limit: 3 }));
      await act(async () => { read.resolve(settings({ used: 0, left: 3 })); });

      expect(result.current).toMatchObject({ left: 1, used: 2, limit: 3 });
    });

    it('ignores an older read that FAILS after a served figure, which stays ready', async () => {
      const { result, read } = await loadedWithReadInFlight();

      act(() => result.current.applyServed({ left: 1, limit: 3 }));
      await act(async () => { read.reject(new Error('offline')); });

      expect(result.current).toMatchObject({ status: 'ready', left: 1 });
    });

    it('applies a read started AFTER the served figure', async () => {
      const { result, read } = await loadedWithReadInFlight();
      act(() => result.current.applyServed({ left: 1, limit: 3 }));
      await act(async () => { read.resolve(settings({ used: 0, left: 3 })); });
      getAskSettings.mockResolvedValueOnce(settings({ used: 3, left: 0 }));

      act(() => result.current.refetch());

      await waitFor(() => expect(result.current.left).toBe(0));
      expect(getAskSettings).toHaveBeenCalledTimes(3);
    });

    it('is not invalidated by a served figure that is not a count', async () => {
      const { result, read } = await loadedWithReadInFlight();

      act(() => result.current.applyServed({ left: -1, limit: 3 }));
      await act(async () => { read.resolve(settings({ used: 1, left: 2 })); });

      expect(result.current.left).toBe(2);
    });

    it('ignores the first of two reads when it resolves last', async () => {
      getAskSettings.mockResolvedValueOnce(settings({ used: 0, left: 3 }));
      const { result } = renderHook(() => useAskAllowance());
      await waitFor(() => expect(result.current.left).toBe(3));
      const first = deferred();
      const second = deferred();
      getAskSettings.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
      act(() => result.current.refetch());
      await waitFor(() => expect(getAskSettings).toHaveBeenCalledTimes(2));
      act(() => result.current.refetch());
      await waitFor(() => expect(getAskSettings).toHaveBeenCalledTimes(3));

      await act(async () => { second.resolve(settings({ used: 2, left: 1 })); });
      await act(async () => { first.resolve(settings({ used: 0, left: 3 })); });

      expect(result.current.left).toBe(1);
    });
  });

  it('applies the figures a POST response carries, deriving used from them', async () => {
    getAskSettings.mockResolvedValue(settings());
    const { result } = renderHook(() => useAskAllowance());
    await waitFor(() => expect(result.current.loaded).toBe(true));

    act(() => result.current.applyServed({ left: 1, limit: 3 }));

    expect(result.current).toMatchObject({ left: 1, limit: 3, used: 2, typedAvailable: true });
  });

  it('keeps what it knew about typedAvailable when a POST response moves the count', async () => {
    getAskSettings.mockResolvedValue(settings({ typedAvailable: false }));
    const { result } = renderHook(() => useAskAllowance());
    await waitFor(() => expect(result.current.loaded).toBe(true));

    act(() => result.current.applyServed({ left: 2, limit: 3 }));

    expect(result.current.typedAvailable).toBe(false);
  });

  it.each([
    ['a negative left', { left: -1, limit: 3 }],
    ['a fractional left', { left: 1.5, limit: 3 }],
    ['a missing limit', { left: 1 }],
    ['a string', { left: '1', limit: '3' }],
  ])('ignores %s from a POST response', async (_name, served) => {
    getAskSettings.mockResolvedValue(settings());
    const { result } = renderHook(() => useAskAllowance());
    await waitFor(() => expect(result.current.loaded).toBe(true));

    act(() => result.current.applyServed(served));

    expect(result.current).toMatchObject({ left: 3, used: 0 });
  });

  it('survivesAFailedRead: keeps the last known figures and says the read failed', async () => {
    getAskSettings.mockResolvedValueOnce(settings({ used: 1, left: 2 }))
      .mockRejectedValueOnce(new Error('down'));
    const { result } = renderHook(() => useAskAllowance());
    await waitFor(() => expect(result.current.left).toBe(2));

    act(() => result.current.refetch());

    await waitFor(() => expect(result.current.status).toBe('failed'));
    expect(result.current.left).toBe(2);
  });

  it('a first read that fails leaves the figures unknown, never zero', async () => {
    getAskSettings.mockRejectedValue(new Error('down'));

    const { result } = renderHook(() => useAskAllowance());

    await waitFor(() => expect(result.current.status).toBe('failed'));
    expect(result.current).toMatchObject({ loaded: false, left: null, enabled: null });
  });

  it.each([
    ['a string body', 'nope'],
    ['a body with no counts', { enabled: true }],
    ['a body whose counts are not whole numbers', { enabled: true, used: 'a', limit: 3, left: 3 }],
  ])('treats %s as a failed read, not as an allowance', async (_name, body) => {
    getAskSettings.mockResolvedValue(body);

    const { result } = renderHook(() => useAskAllowance());

    await waitFor(() => expect(result.current.status).toBe('failed'));
    expect(result.current.loaded).toBe(false);
  });

  it('drops a superseded read when it lands (settled in an awaited act)', async () => {
    const first = deferred();
    const second = deferred();
    getAskSettings.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const { result } = renderHook(() => useAskAllowance());
    act(() => result.current.refetch());

    // The NEWER read answers first; the older one lands after it with a different figure.
    await act(async () => { second.resolve(settings({ used: 2, left: 1 })); });
    expect(result.current.left).toBe(1);
    await act(async () => { first.resolve(settings({ used: 0, left: 3 })); });

    expect(result.current.left).toBe(1);
  });

  it('a superseded read’s failure cannot mark the newest answer as failed', async () => {
    const first = deferred();
    const second = deferred();
    getAskSettings.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const { result } = renderHook(() => useAskAllowance());
    act(() => result.current.refetch());
    await act(async () => { second.resolve(settings({ used: 2, left: 1 })); });

    await act(async () => { first.reject(new Error('late failure')); });

    expect(result.current.status).toBe('ready');
    expect(result.current.left).toBe(1);
  });

  describe('the UK day turning over', () => {
    // Frozen at 23:30 UTC on 5 Oct = 00:30 BST on the 6th: the UK date has moved, the UTC date has not.
    const justBeforeUkMidnight = new Date('2026-10-05T22:30:00Z');
    const justAfterUkMidnight = new Date('2026-10-05T23:30:00Z');

    const returnToTheTab = () => act(() => { window.dispatchEvent(new Event('focus')); });

    it('re-reads the allowance when the tab comes back on a later UK day', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(justBeforeUkMidnight);
      getAskSettings.mockResolvedValueOnce(settings({ used: 3, left: 0 }))
        .mockResolvedValueOnce(settings({ used: 0, left: 3 }));
      const { result } = renderHook(() => useAskAllowance());
      await waitFor(() => expect(result.current.left).toBe(0));

      vi.setSystemTime(justAfterUkMidnight);
      returnToTheTab();

      await waitFor(() => expect(result.current.left).toBe(3));
      expect(getAskSettings).toHaveBeenCalledTimes(2);
    });

    it.each([
      ['rejected', (mock) => mock.mockRejectedValueOnce(new Error('down'))],
      ['answered with a body that is not an allowance', (mock) => mock.mockResolvedValueOnce('nope')],
    ])('tries again on the next return when the re-read after midnight %s', async (_name, failTheSecondRead) => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(justBeforeUkMidnight);
      getAskSettings.mockResolvedValueOnce(settings({ used: 3, left: 0 }));
      failTheSecondRead(getAskSettings);
      getAskSettings.mockResolvedValueOnce(settings({ used: 0, left: 3 }));
      const { result } = renderHook(() => useAskAllowance());
      await waitFor(() => expect(result.current.left).toBe(0));
      vi.setSystemTime(justAfterUkMidnight);

      returnToTheTab();
      await waitFor(() => expect(result.current.status).toBe('failed'));
      // Yesterday's "none left" still stands — but the date it was read on has NOT been moved on.
      expect(result.current.left).toBe(0);
      returnToTheTab();

      await waitFor(() => expect(result.current.left).toBe(3));
      expect(getAskSettings).toHaveBeenCalledTimes(3);
    });

    it('does not re-read it when the tab comes back on the same UK day', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(NOW);
      getAskSettings.mockResolvedValue(settings());
      const { result } = renderHook(() => useAskAllowance());
      await waitFor(() => expect(result.current.loaded).toBe(true));

      returnToTheTab();
      returnToTheTab();

      expect(getAskSettings).toHaveBeenCalledTimes(1);
    });

    it('does nothing for a tab that is not visible', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(justBeforeUkMidnight);
      getAskSettings.mockResolvedValue(settings());
      const { result } = renderHook(() => useAskAllowance());
      await waitFor(() => expect(result.current.loaded).toBe(true));
      vi.setSystemTime(justAfterUkMidnight);
      const hidden = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');

      returnToTheTab();

      expect(getAskSettings).toHaveBeenCalledTimes(1);
      hidden.mockRestore();
    });
  });

  it('writes nothing to storage: it is never cached, so a logout cannot carry it over', async () => {
    const write = vi.spyOn(Storage.prototype, 'setItem');
    getAskSettings.mockResolvedValue(settings());
    const { result } = renderHook(() => useAskAllowance());
    await waitFor(() => expect(result.current.loaded).toBe(true));
    act(() => result.current.applyServed({ left: 1, limit: 3 }));

    expect(write).not.toHaveBeenCalled();
    expect(localStorage.length).toBe(0);
    write.mockRestore();
  });
});
