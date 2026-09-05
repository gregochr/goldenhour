import { configure } from '@testing-library/react';
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
// Several files rely on that — `mapDates`, `computeAutoSelection`, `DateStripToday` and
// `MapViewAuroraNight` pin Europe/London; `mapDatesAbroad`, `instantsAbroad`,
// `jobRunSlotDatesAbroad`, `metricsTodayFilterAbroad`, `solarEventTimes` and `leaveByAbroad` pin
// America/New_York — and EVERY abroad file carries a "the zone fixture itself" guard, so if this
// line ever defeated a per-file pin those files fail rather than quietly becoming duplicates.
// (`grep -l "process.env.TZ" src/test/*` for the current set; the list above is the reason, not the
// index, and a count written here rots.) ⚠️ A date assertion is not always enough to be that guard.
// At `jobRunSlotDatesAbroad`'s headline instant New York and UTC are on the SAME date, so that file
// asserts `resolvedOptions().timeZone` outright; `leaveByAbroad` must do the same for a different
// reason — the code under test names `Europe/London` itself, so its answer is the same under the
// pin and under this default, and only the guard can tell the file is still doing its job. Check
// which form a new abroad file needs rather than copying a sibling's.
// `testEnvironmentTimezone.test.js` is the other half: it fails if this line stops taking effect at
// all.
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

/**
 * ⚠️ Testing Library's async timeout, raised from its 1000 ms default.
 *
 * <p>The Plan shell mounts its matrix, its window popup, its search panel and its location sheet
 * behind {@code React.lazy} boundaries, and tests across the shell files open with
 * {@code await screen.findByTestId('wf-heat-strip')} — a wait on a real dynamic {@code import()},
 * not on a fetch a test could gate. Isolated they resolve in single-digit milliseconds; under a full
 * parallel run on a loaded machine three of them were measured timing out at 1000 ms while the same
 * files passed alone in ten seconds. That is the "a green isolated run does NOT exonerate it" flake
 * this project has been bitten by before, and the honest fix here is the timeout rather than a gate,
 * because there is nothing to gate on: the module either has loaded or has not.
 *
 * <p>It is a CEILING, not a delay — a resolved boundary still returns immediately, so nothing gets
 * slower. What it costs is that a genuinely never-appearing element now fails after four seconds
 * instead of one.
 *
 * <p>⚠️ <b>It WAS also 80% of Vitest's then-5000 ms per-test budget, and that was not enough on
 * its own.</b> The measurement above stands, and so does the conclusion beside it — the fix really
 * was the timeout rather than a gate — but the ceiling was raised without the per-test budget
 * moving with it, and a test crossing TWO of these boundaries in sequence cannot fit two 4000 ms
 * waits into 5000 ms. So it died as {@code Test timed out in 5000ms} naming neither wait.
 * Reproduced by running the full suite three times concurrently under a 16-process CPU load: 3 of 3
 * runs failed identically, on the first test of three different shell files.
 * {@code vite.config.js} now sets {@code testTimeout: 20000}, which is what makes this 4000 ms
 * ceiling reachable at all; its note carries the measurements, and the record of a per-file
 * warm-up that was built to gate these boundaries and then deleted as unnecessary.
 */
configure({ asyncUtilTimeout: 4000 });
