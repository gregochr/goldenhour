import { act } from '@testing-library/react';
import { __resetForTests } from '../utils/dialogHistory.js';
import { createFakeBrowser, tick } from './fakeBrowserHistory.js';

/**
 * Shared harness for the wired-consumer Back tests (`back*.test.jsx`): a fresh Chromium-like fake
 * history installed behind the engine's test seam, so a test presses Back with `userBack()` and
 * asserts what the dialog did, never real history counts. See `dev-and-testing.md`, "Dialog history
 * tests".
 *
 * @param {object} [options] forwarded to {@link createFakeBrowser}
 * @returns {object} the fake browser, with a `settle()` that lets the store's microtask, deferred
 *          shrink and queued traversals all run inside `act`, and a `back()` that presses Back and settles
 */
export function installBackBrowser(options) {
  document.documentElement.removeAttribute('data-back-depth');
  const browser = createFakeBrowser(options);
  __resetForTests({ history: browser.history, win: browser.win });
  browser.settle = async () => {
    await act(async () => {
      await tick();
      browser.flush();
      await tick();
      browser.flush();
    });
  };
  browser.back = async () => {
    browser.userBack();
    await browser.settle();
  };
  return browser;
}

/** Puts the real engine and `document` back after a test. */
export function uninstallBackBrowser() {
  __resetForTests();
  document.body.style.overflow = '';
}
