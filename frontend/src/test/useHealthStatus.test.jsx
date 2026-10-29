import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('../utils/createEventSource.js', () => ({ default: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({ getSettings: vi.fn() }));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: vi.fn() }));

import createEventSource from '../utils/createEventSource.js';
import { getSettings } from '../api/settingsApi.js';
import { useAuth } from '../context/AuthContext.jsx';
import { useHealthStatus, sameHealth } from '../hooks/useHealthStatus.js';

const DOWN_GRACE_MS = 12000;
let streams;

function lastStream() {
  return streams[streams.length - 1];
}

function statusEvent(stream) {
  act(() => {
    stream.handlers.status({ status: 'UP', degraded: [] });
  });
}

async function advance(ms) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe('useHealthStatus session probe', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    streams = [];
    createEventSource.mockImplementation((path, params, handlers, options) => {
      const stream = { path, params, handlers, options, cleanup: vi.fn() };
      streams.push(stream);
      return stream.cleanup;
    });
    getSettings.mockReset();
    getSettings.mockResolvedValue({});
    useAuth.mockReturnValue({ token: 'tok-1' });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('(a) probes once on the first error and never again in the same error burst', async () => {
    renderHook(() => useHealthStatus(true));
    const { options } = lastStream();

    act(() => options.onError());
    expect(getSettings).toHaveBeenCalledTimes(1);

    act(() => options.onError());
    act(() => options.onError());
    expect(getSettings).toHaveBeenCalledTimes(1);

    await advance(DOWN_GRACE_MS + 1);
    act(() => options.onError());
    act(() => options.onError());
    expect(getSettings).toHaveBeenCalledTimes(1);
  });

  it('(b) a status event re-arms the probe for a later failure', () => {
    renderHook(() => useHealthStatus(true));
    const stream = lastStream();

    act(() => stream.options.onError());
    expect(getSettings).toHaveBeenCalledTimes(1);

    statusEvent(stream);
    act(() => stream.options.onError());
    expect(getSettings).toHaveBeenCalledTimes(2);
  });

  it('(c) a status event before the grace period clears the DOWN timer', async () => {
    const { result } = renderHook(() => useHealthStatus(true));
    const stream = lastStream();

    act(() => stream.options.onError());
    await advance(DOWN_GRACE_MS - 1000);
    statusEvent(stream);
    await advance(DOWN_GRACE_MS * 2);

    expect(result.current.status).toBe('UP');
  });

  it('(d) DOWN is set after the grace period when the probe resolves', async () => {
    getSettings.mockResolvedValue({});
    const { result } = renderHook(() => useHealthStatus(true));

    act(() => lastStream().options.onError());
    await advance(DOWN_GRACE_MS - 1);
    expect(result.current.status).toBeNull();
    await advance(2);
    expect(result.current.status).toBe('DOWN');
  });

  it('(d) DOWN is set after the grace period when the probe rejects', async () => {
    getSettings.mockRejectedValue(new Error('network'));
    const { result } = renderHook(() => useHealthStatus(true));

    act(() => lastStream().options.onError());
    await advance(DOWN_GRACE_MS - 1);
    expect(result.current.status).toBeNull();
    await advance(2);
    expect(result.current.status).toBe('DOWN');
  });

  it('(e) a token change tears down the old stream, drops its timer and may probe again', async () => {
    const { result, rerender } = renderHook(() => useHealthStatus(true));
    const first = lastStream();

    act(() => first.options.onError());
    expect(getSettings).toHaveBeenCalledTimes(1);

    useAuth.mockReturnValue({ token: 'tok-2' });
    rerender();

    expect(first.cleanup).toHaveBeenCalledTimes(1);
    expect(streams).toHaveLength(2);

    await advance(DOWN_GRACE_MS * 2);
    expect(result.current.status).toBeNull();

    act(() => lastStream().options.onError());
    expect(getSettings).toHaveBeenCalledTimes(2);
  });

  it('(f) opens no stream and probes nothing when disabled', () => {
    renderHook(() => useHealthStatus(false));
    expect(createEventSource).not.toHaveBeenCalled();
    expect(getSettings).not.toHaveBeenCalled();
  });

  it('(f) opens no stream and probes nothing without a token', () => {
    useAuth.mockReturnValue({ token: null });
    renderHook(() => useHealthStatus(true));
    expect(createEventSource).not.toHaveBeenCalled();
    expect(getSettings).not.toHaveBeenCalled();
  });
});

describe('useHealthStatus tick identity', () => {
  beforeEach(() => {
    streams = [];
    createEventSource.mockImplementation((path, params, handlers, options) => {
      const stream = { path, params, handlers, options, cleanup: vi.fn() };
      streams.push(stream);
      return stream.cleanup;
    });
    useAuth.mockReturnValue({ token: 'tok-1' });
  });

  it('a tick that changes only the probe time moves checkedAt and keeps every other field', () => {
    const { result } = renderHook(() => useHealthStatus(true));
    statusEvent(lastStream());
    const first = result.current;
    expect(first.checkedAt).toBeInstanceOf(Date);

    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      vi.setSystemTime(Date.now() + 30000);
      statusEvent(lastStream());
    } finally {
      vi.useRealTimers();
    }

    expect(result.current.checkedAt.getTime()).toBeGreaterThan(first.checkedAt.getTime());
    expect(result.current.degraded).toBe(first.degraded);
    expect(result.current.status).toBe('UP');
  });
});

describe('sameHealth', () => {
  const base = {
    status: 'UP', degraded: [], checkedAt: new Date(1000),
    build: { commitId: 'abc', branch: 'main' }, session: { user: 'a' },
    database: { latencyMs: 3 }, services: { tides: { latencyMs: 40 } },
    appVersion: '1.0', startedAt: '2026-01-01T00:00:00Z',
  };

  it.each([
    ['status', { status: 'DEGRADED' }],
    ['degraded', { degraded: ['tides'] }],
    ['services (nested latency)', { services: { tides: { latencyMs: 41 } } }],
    ['appVersion', { appVersion: '1.1' }],
    ['startedAt', { startedAt: '2026-01-02T00:00:00Z' }],
    ['build', { build: { commitId: 'def', branch: 'main' } }],
    ['session', { session: { user: 'b' } }],
    ['database', { database: { latencyMs: 4 } }],
  ])('a change to %s is not the same', (_name, change) => {
    expect(sameHealth(base, { ...base, ...change })).toBe(false);
  });

  it('a change to checkedAt alone is the same', () => {
    expect(sameHealth(base, { ...base, checkedAt: new Date(99999) })).toBe(true);
  });
});

describe('useHealthStatus repeated DOWN', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    streams = [];
    createEventSource.mockImplementation((path, params, handlers, options) => {
      const stream = { path, params, handlers, options, cleanup: vi.fn() };
      streams.push(stream);
      return stream.cleanup;
    });
    getSettings.mockResolvedValue({});
    useAuth.mockReturnValue({ token: 'tok-1' });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('a second DOWN grace firing keeps the health fields and still moves checkedAt', async () => {
    const { result } = renderHook(() => useHealthStatus(true));
    const { options } = lastStream();
    act(() => options.onError());
    await advance(DOWN_GRACE_MS + 1);
    const first = result.current;
    expect(first.status).toBe('DOWN');

    act(() => options.onError());
    await advance(DOWN_GRACE_MS + 1);

    expect(result.current.status).toBe('DOWN');
    expect(Object.is(result.current.degraded, first.degraded)).toBe(true);
    expect(result.current.checkedAt.getTime()).toBeGreaterThan(first.checkedAt.getTime());
  });
});
