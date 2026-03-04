import '@testing-library/jest-dom';

// Recharts uses ResizeObserver — stub it for JSDOM
global.ResizeObserver = class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
};

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
