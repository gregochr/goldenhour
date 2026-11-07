import { describe, it, expect, vi, afterEach } from 'vitest';
import { purgeServiceWorkerState } from '../utils/serviceWorkerReset.js';

const defined = [];

function define(target, name, value) {
  Object.defineProperty(target, name, { value, configurable: true, writable: true });
  defined.push([target, name]);
}

afterEach(() => {
  defined.splice(0).forEach(([target, name]) => {
    delete target[name];
  });
});

describe('purgeServiceWorkerState', () => {
  it('unregisters every registration and deletes every cache', async () => {
    const unregister = [vi.fn().mockResolvedValue(true), vi.fn().mockResolvedValue(true)];
    define(navigator, 'serviceWorker', {
      getRegistrations: vi.fn().mockResolvedValue(unregister.map((fn) => ({ unregister: fn }))),
    });
    const deleted = vi.fn().mockResolvedValue(true);
    define(globalThis, 'caches', { keys: vi.fn().mockResolvedValue(['a', 'b']), delete: deleted });
    await purgeServiceWorkerState();
    unregister.forEach((fn) => expect(fn).toHaveBeenCalledTimes(1));
    expect(deleted.mock.calls).toEqual([['a'], ['b']]);
  });

  it('still clears the caches when the service worker half fails, and never rejects', async () => {
    define(navigator, 'serviceWorker', { getRegistrations: vi.fn().mockRejectedValue(new Error('denied')) });
    const deleted = vi.fn().mockResolvedValue(true);
    define(globalThis, 'caches', { keys: vi.fn().mockResolvedValue(['a']), delete: deleted });
    await expect(purgeServiceWorkerState()).resolves.toBeUndefined();
    expect(deleted).toHaveBeenCalledWith('a');
  });

  it('still unregisters when the cache half fails', async () => {
    const unregister = vi.fn().mockResolvedValue(true);
    define(navigator, 'serviceWorker', { getRegistrations: vi.fn().mockResolvedValue([{ unregister }]) });
    define(globalThis, 'caches', { keys: vi.fn().mockRejectedValue(new Error('denied')), delete: vi.fn() });
    await expect(purgeServiceWorkerState()).resolves.toBeUndefined();
    expect(unregister).toHaveBeenCalledTimes(1);
  });

  it('resolves quietly where neither API exists', async () => {
    await expect(purgeServiceWorkerState()).resolves.toBeUndefined();
  });
});
