import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import createEventSourceUntracked from '../utils/createEventSource.js';

class MockEventSource {
  static CLOSED = 2;
  static OPEN = 1;

  constructor(url) {
    this.url = url;
    this.readyState = 0;
    this._listeners = {};
    this.onerror = null;
    MockEventSource.instances.push(this);
  }

  addEventListener(name, handler) {
    if (!this._listeners[name]) this._listeners[name] = [];
    this._listeners[name].push(handler);
  }

  close() {
    this.readyState = MockEventSource.CLOSED;
    this._closed = true;
  }

  _emit(name, data) {
    (this._listeners[name] || []).forEach((h) => h({ data }));
  }
}

MockEventSource.instances = [];

// Every test opens its connections through this wrapper so `afterEach` can close them. The cleanup
// `createEventSource` returns is the only handle on a connection's retry timer and its visibility
// listeners; closing the mock source reaches neither. Calling the import directly opens a
// connection nothing will close.
const openConnections = [];

function createEventSource(...args) {
  const close = createEventSourceUntracked(...args);
  openConnections.push(close);
  return close;
}

describe('createEventSource', () => {
  let origEventSource;

  beforeEach(() => {
    // The whole file runs on the fake clock, so no retry a test arms can fire once the test is
    // over. One did: "calls onError even when readyState is CLOSED" ran on the real clock and left
    // the 5 s retry armed. When the worker outlived it (only ever seen under load; the rest of this
    // file takes milliseconds), it fired after `afterEach` had put EventSource back to jsdom's,
    // which has none, and threw `EventSource is not a constructor` from the timer: an unhandled
    // error that failed the whole run with every test passing.
    vi.useFakeTimers();
    origEventSource = globalThis.EventSource;
    globalThis.EventSource = MockEventSource;
    MockEventSource.instances = [];
    vi.stubGlobal('localStorage', { getItem: vi.fn(() => 'test-token') });
  });

  afterEach(() => {
    // Close every connection the test opened, whether it passed or threw. The fake clock stops a
    // retry left behind from firing; only the cleanup releases the connection's source, listeners
    // and timer.
    for (const close of openConnections.splice(0)) close();
    // Then check that nothing was missed, on every run; each check sees a leak the other cannot. A
    // cleanup closes its connection's current source and a reconnect closes the one it replaces, so
    // a source still open belongs to a connection nothing released, with or without a retry. A
    // timer still pending is a retry nothing cancelled, even behind a source a test closed itself.
    const leftOpen = MockEventSource.instances.filter((source) => !source._closed);
    const leftArmed = vi.getTimerCount();
    // Restored here rather than on each test's last line, which a failing assertion never reaches.
    vi.useRealTimers();
    globalThis.EventSource = origEventSource;
    vi.unstubAllGlobals();
    // Asserted last, so a failure cannot skip the restores above.
    expect(leftOpen.map((source) => source.url), 'connections left open').toEqual([]);
    expect(leftArmed, 'retries left armed').toBe(0);
  });

  it('constructs URL with base, path, params, and token', () => {
    createEventSource('/api/test', { foo: 'bar' });
    const url = MockEventSource.instances[0].url;
    expect(url).toContain('/api/test?');
    expect(url).toContain('foo=bar');
    expect(url).toContain('token=test-token');
  });

  it('registers event listeners', () => {
    const handler = vi.fn();
    createEventSource('/api/test', {}, { 'my-event': handler });
    const source = MockEventSource.instances[0];
    source._emit('my-event', JSON.stringify({ value: 42 }));
    expect(handler).toHaveBeenCalledWith({ value: 42 });
  });

  it('JSON-parses event data', () => {
    const handler = vi.fn();
    createEventSource('/api/test', {}, { update: handler });
    MockEventSource.instances[0]._emit('update', JSON.stringify({ a: 1 }));
    expect(handler).toHaveBeenCalledWith({ a: 1 });
  });

  it('ignores JSON parse errors', () => {
    const handler = vi.fn();
    createEventSource('/api/test', {}, { update: handler });
    MockEventSource.instances[0]._emit('update', 'not-json');
    expect(handler).not.toHaveBeenCalled();
  });

  it('calls onError when readyState is not CLOSED', () => {
    const onError = vi.fn();
    createEventSource('/api/test', {}, {}, { onError });
    const source = MockEventSource.instances[0];
    source.readyState = 1; // OPEN
    source.onerror();
    expect(onError).toHaveBeenCalledOnce();
  });

  it('calls onError even when readyState is CLOSED', () => {
    const onError = vi.fn();
    createEventSource('/api/test', {}, {}, { onError });
    const source = MockEventSource.instances[0];
    source.readyState = MockEventSource.CLOSED;
    source.onerror();
    expect(onError).toHaveBeenCalledOnce();
  });

  it('closes source on closeOn event', () => {
    const handler = vi.fn();
    createEventSource('/api/test', {}, { done: handler }, { closeOn: 'done' });
    const source = MockEventSource.instances[0];
    source._emit('done', JSON.stringify({ ok: true }));
    expect(handler).toHaveBeenCalledWith({ ok: true });
    expect(source._closed).toBe(true);
  });

  it('returns cleanup function that closes source', () => {
    const cleanup = createEventSource('/api/test');
    const source = MockEventSource.instances[0];
    expect(source._closed).toBeUndefined();
    cleanup();
    expect(source._closed).toBe(true);
  });

  it('uses custom getToken', () => {
    createEventSource('/api/test', {}, {}, { getToken: () => 'custom-tok' });
    const url = MockEventSource.instances[0].url;
    expect(url).toContain('token=custom-tok');
  });

  it('reconnects when EventSource enters CLOSED state', () => {
    const onError = vi.fn();
    createEventSource('/api/test', {}, {}, { onError });
    expect(MockEventSource.instances).toHaveLength(1);

    const source = MockEventSource.instances[0];
    source.readyState = MockEventSource.CLOSED;
    source.onerror();

    expect(onError).toHaveBeenCalledOnce();
    expect(MockEventSource.instances).toHaveLength(1);

    vi.advanceTimersByTime(5000);
    expect(MockEventSource.instances).toHaveLength(2);
  });

  it('reads a freshly-provided token on reconnect, not a stale captured one', () => {
    let current = 'tok-1';
    createEventSource('/api/test', {}, {}, { getToken: () => current });
    expect(MockEventSource.instances[0].url).toContain('token=tok-1');

    const source = MockEventSource.instances[0];
    source.readyState = MockEventSource.CLOSED;
    current = 'tok-2'; // token refreshed (e.g. by the axios interceptor) between attempts
    source.onerror();

    vi.advanceTimersByTime(5000);

    expect(MockEventSource.instances).toHaveLength(2);
    expect(MockEventSource.instances[1].url).toContain('token=tok-2');
  });

  it('closes the previous source when reconnecting', () => {
    createEventSource('/api/test');
    const first = MockEventSource.instances[0];
    first.readyState = MockEventSource.CLOSED;
    first.onerror();

    vi.advanceTimersByTime(5000);

    expect(first._closed).toBe(true);
    expect(MockEventSource.instances).toHaveLength(2);
  });

  it('does not reconnect after cleanup is called', () => {
    const cleanup = createEventSource('/api/test');
    const source = MockEventSource.instances[0];

    cleanup();
    source.readyState = MockEventSource.CLOSED;
    source.onerror();

    vi.advanceTimersByTime(10000);
    expect(MockEventSource.instances).toHaveLength(1);
  });

  it('cancels a pending retry when cleanup is called', () => {
    const cleanup = createEventSource('/api/test');
    const source = MockEventSource.instances[0];
    source.readyState = MockEventSource.CLOSED;
    source.onerror(); // schedules the 5s retry
    expect(vi.getTimerCount()).toBe(1);

    cleanup();

    // Counted, not advanced: `closed` alone stops a retry that fires from reconnecting, so the
    // pending-timer count, not the instance count, is what tells a cancelled retry from one left
    // armed to fire as a no-op.
    expect(vi.getTimerCount()).toBe(0);
  });

  describe('reconnectOnVisible', () => {
    it('reconnects immediately when the tab becomes visible and the source is CLOSED', () => {
      createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });
      const source = MockEventSource.instances[0];
      source.readyState = MockEventSource.CLOSED;

      document.dispatchEvent(new Event('visibilitychange'));

      expect(MockEventSource.instances).toHaveLength(2);
    });

    it('reconnects on window focus when the source is CLOSED', () => {
      createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });
      MockEventSource.instances[0].readyState = MockEventSource.CLOSED;

      window.dispatchEvent(new Event('focus'));

      expect(MockEventSource.instances).toHaveLength(2);
    });

    it('does not reconnect on visibility change while the source is still OPEN', () => {
      createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });
      MockEventSource.instances[0].readyState = MockEventSource.OPEN;

      document.dispatchEvent(new Event('visibilitychange'));

      expect(MockEventSource.instances).toHaveLength(1);
    });

    it('cancels the pending throttled retry and reconnects at once on visibility', () => {
      createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });
      const source = MockEventSource.instances[0];
      source.readyState = MockEventSource.CLOSED;
      source.onerror(); // schedules the 5s retry

      document.dispatchEvent(new Event('visibilitychange'));
      expect(MockEventSource.instances).toHaveLength(2);

      // The previously scheduled retry must not fire a second reconnect.
      vi.advanceTimersByTime(5000);
      expect(MockEventSource.instances).toHaveLength(2);
    });

    it('removes visibility/focus listeners on cleanup', () => {
      const cleanup = createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });
      cleanup();
      MockEventSource.instances[0].readyState = MockEventSource.CLOSED;

      document.dispatchEvent(new Event('visibilitychange'));
      window.dispatchEvent(new Event('focus'));

      expect(MockEventSource.instances).toHaveLength(1);
    });

    it('does not register visibility listeners when the option is off', () => {
      createEventSource('/api/test');
      MockEventSource.instances[0].readyState = MockEventSource.CLOSED;

      document.dispatchEvent(new Event('visibilitychange'));

      expect(MockEventSource.instances).toHaveLength(1);
    });
  });
});
