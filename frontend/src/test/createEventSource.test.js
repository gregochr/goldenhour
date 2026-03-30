import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import createEventSource from '../utils/createEventSource.js';

class MockEventSource {
  static CLOSED = 2;

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

  it('skips onError when readyState is CLOSED', () => {
    const onError = vi.fn();
    createEventSource('/api/test', {}, {}, { onError });
    const source = MockEventSource.instances[0];
    source.readyState = MockEventSource.CLOSED;
    source.onerror();
    expect(onError).not.toHaveBeenCalled();
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
});
