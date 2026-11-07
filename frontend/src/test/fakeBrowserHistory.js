import { vi } from 'vitest';

/**
 * A small fake of a Chromium session history, for `utils/dialogHistory` tests (see
 * `docs/engineering/reference/dev-and-testing.md`, "Dialog history tests").
 *
 * <p>It models what jsdom does not: every queued traversal runs (jsdom merges two `back()` calls into
 * one), a push truncates the forward entries, an out-of-range `go` does nothing, `popstate` fires
 * asynchronously (only when a test calls {@link flush}/{@link step}), and a `go` is relative to the
 * entry the browser is on when the traversal RUNS, not when it was queued. It also models
 * `userActivation` and `scrollRestoration` (with an ordered `log`).
 *
 * @param {object} [options]
 * @param {boolean} [options.activation] initial `navigator.userActivation.isActive`
 * @param {boolean} [options.scroll] whether `history.scrollRestoration` exists (jsdom: it does not)
 * @param {string} [options.scrollInitial] the value `scrollRestoration` starts with
 * @param {boolean} [options.dropGo] swallow every `go`: it is queued by nobody and never lands
 * @returns {object} `{history, win, ...controls}`
 */
export function createFakeBrowser({
  activation = true, scroll = true, scrollInitial = 'auto', dropGo = false,
} = {}) {
  const entries = [{ state: null }];
  let index = 0;
  const queue = [];
  const listeners = new Set();
  const log = [];
  let scrollValue = scrollInitial;

  const history = {
    get state() { return entries[index].state; },
    pushState: vi.fn((state) => {
      entries.splice(index + 1);
      entries.push({ state });
      index += 1;
      log.push('push');
    }),
    replaceState: vi.fn((state) => {
      entries[index].state = state;
      log.push('replace');
    }),
    go: vi.fn((delta) => { if (!dropGo) queue.push(delta); }),
    back: vi.fn(() => queue.push(-1)),
    forward: vi.fn(() => queue.push(1)),
  };
  if (scroll) {
    Object.defineProperty(history, 'scrollRestoration', {
      get: () => scrollValue,
      set: (v) => { scrollValue = v; log.push(`scroll:${v}`); },
      enumerable: true,
      configurable: true,
    });
  }

  const win = {
    history,
    document,
    navigator: { userActivation: { isActive: activation } },
    addEventListener: (type, fn) => { if (type === 'popstate') listeners.add(fn); },
    removeEventListener: (type, fn) => { if (type === 'popstate') listeners.delete(fn); },
  };

  const dispatch = () => listeners.forEach((fn) => fn(new Event('popstate')));

  const browser = {
    history,
    win,
    log,
    /** The index of the entry the browser is on. */
    get index() { return index; },
    get entries() { return entries; },
    get listenerCount() { return listeners.size; },
    setActivation(on) { win.navigator.userActivation.isActive = on; },
    /** The user presses Back / Forward / a long-press jump: queued like any traversal. */
    userBack() { queue.push(-1); },
    userForward() { queue.push(1); },
    userGo(delta) { queue.push(delta); },
    /** Runs ONE queued traversal. Returns false when none was queued. */
    step() {
      if (!queue.length) return false;
      const target = index + queue.shift();
      if (target >= 0 && target < entries.length) {
        index = target;
        dispatch();
      }
      return true;
    },
    /** Runs every queued traversal, including any queued by a popstate handler. */
    flush() {
      let guard = 0;
      while (browser.step()) {
        guard += 1;
        if (guard > 50) throw new Error('runaway traversal loop');
      }
    },
    get queued() { return queue.length; },
    /** Navigation to a new hash: a new entry with null state, and (as in browsers) a popstate. */
    hashPush(hash) {
      entries.splice(index + 1);
      entries.push({ state: null, hash });
      index += 1;
      dispatch();
    },
    /** The app's own `history.pushState(null, ...)`: a new entry and NO popstate. */
    appPush(state = null) {
      history.pushState(state, '');
    },
    dispatch,
  };
  return browser;
}

/** Lets the store's reconcile microtask run. */
export async function tick() {
  await Promise.resolve();
  await Promise.resolve();
}
