import '@testing-library/jest-dom';

// Recharts uses ResizeObserver — stub it for JSDOM
global.ResizeObserver = class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
};

// jsdom has no matchMedia, so any component reaching for a media query (useIsMobile) throws on
// first render. Defaults to "no match" — i.e. the desktop branch — which is what tests that render
// a component without saying otherwise mean. A test needing the mobile branch mocks the hook
// itself, as the MapView suites already do.
if (typeof globalThis.matchMedia !== 'function') {
  globalThis.matchMedia = (query) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent() { return false; },
  });
}

// jsdom localStorage polyfill — ensures getItem/setItem/removeItem/clear are available
if (typeof globalThis.localStorage === 'undefined' || typeof globalThis.localStorage.getItem !== 'function') {
  const store = {};
  globalThis.localStorage = {
    getItem(key) { return Object.prototype.hasOwnProperty.call(store, key) ? store[key] : null; },
    setItem(key, value) { store[key] = String(value); },
    removeItem(key) { delete store[key]; },
    clear() { Object.keys(store).forEach(k => delete store[k]); },
    get length() { return Object.keys(store).length; },
    key(index) { return Object.keys(store)[index] ?? null; },
  };
}
