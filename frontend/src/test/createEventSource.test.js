import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import createEventSource from '../utils/createEventSource.js';

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

describe('createEventSource', () => {
  let origEventSource;

  beforeEach(() => {
    origEventSource = globalThis.EventSource;
    globalThis.EventSource = MockEventSource;
    MockEventSource.instances = [];
    vi.stubGlobal('localStorage', { getItem: vi.fn(() => 'test-token') });
  });

  afterEach(() => {
    globalThis.EventSource = origEventSource;
    vi.unstubAllGlobals();
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
    vi.useFakeTimers();
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

    vi.useRealTimers();
  });

  it('does not reconnect after cleanup is called', () => {
    vi.useFakeTimers();
    const cleanup = createEventSource('/api/test');
    const source = MockEventSource.instances[0];

    cleanup();
    source.readyState = MockEventSource.CLOSED;
    source.onerror();

    vi.advanceTimersByTime(10000);
    expect(MockEventSource.instances).toHaveLength(1);

    vi.useRealTimers();
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
      vi.useFakeTimers();
      createEventSource('/api/test', {}, {}, { reconnectOnVisible: true });
      const source = MockEventSource.instances[0];
      source.readyState = MockEventSource.CLOSED;
      source.onerror(); // schedules the 5s retry

      document.dispatchEvent(new Event('visibilitychange'));
      expect(MockEventSource.instances).toHaveLength(2);

      // The previously scheduled retry must not fire a second reconnect.
      vi.advanceTimersByTime(5000);
      expect(MockEventSource.instances).toHaveLength(2);

      vi.useRealTimers();
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
