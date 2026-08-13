// The suite's timezone, pinned so it is a property of the repository rather than of whoever ran it.
//
// Nothing pinned it before, so the machine's zone decided: dev machines here are Europe/London and
// GitHub runners are UTC, which means the two were running measurably different tests. `TZ=UTC` and
// `TZ=Europe/London` both passed, so the divergence was invisible — but `TZ=America/New_York` failed
// `UserManagementView`, on a component formatting a UK date in the device's own zone. That is a
// product defect (the same family as #500's map dates), not a test one, and it is exactly what a
// zone nobody happened to run hid.
//
// UTC rather than Europe/London for two reasons. It is what CI already runs, so a failure there
// reproduces here byte for byte and the fixtures need no reinterpretation. And it is the zone that
// DISAGREES with `Europe/London` for the seven months of BST, so a date the app reads on the
// device's zone where it means the UK calendar can at least diverge — under a London pin the two
// strings are identical all year and no assertion could ever tell them apart.
//
// ⚠️ This is a default, not a ceiling. A test file that pins its own zone still wins: setup files
// run before the test module is evaluated, so a file-scope `process.env.TZ = …` is applied second.
// Five files rely on that (`mapDates`, `computeAutoSelection`, `DateStripToday`, `MapViewAuroraNight`
// pin Europe/London; `mapDatesAbroad` pins America/New_York), and `mapDatesAbroad`'s own "the zone
// fixture itself" test asserts the disagreement outright — so if this line ever defeated a per-file
// pin, that file fails rather than quietly becoming a duplicate. `testEnvironmentTimezone.test.js`
// is the other half: it fails if this line stops taking effect at all.
process.env.TZ = 'UTC';

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
