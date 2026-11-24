import { createElement, useCallback } from 'react';
import { act, waitFor } from '@testing-library/react';

/**
 * A stand-in for the one thing that used to open the map view in a shell test: the "Map" button beside the tab list.
 * There is no such button any more (`docs/engineering/one-screen-plan.md` O3): the view opens from a card, a lane, Find,
 * a sheet or the aurora banner, all through the shell's `openMap`, which the shell hands out through its
 * `registerOpenMap` prop. A test passes {@link registerMapDoor} as that prop (the Ask harness does it for every shell it
 * renders) and calls {@link openMapView} where it used to click the button.
 *
 * <p>The registration is module state, which is safe because the shell registers on mount and with `null` on unmount, so
 * the last shell rendered is the one in hand.
 */
let doors = null;

/**
 * The shell's `registerOpenMap` prop.
 *
 * @param {?{open: Function, close: Function}} next the shell's doors, or null when it unmounts
 */
export function registerMapDoor(next) {
  doors = next;
}

/**
 * Opens the map view the way any door does (`openMap`), inside {@code act}. The default request is the map's own window,
 * as the old button gave.
 *
 * @param {object} [request] `openMap`'s own request (`{window, focus, from, trigger, ...}`)
 */
export function openMapView(request = {}) {
  act(() => { doors.open(request); });
}

/**
 * Closes the view through the same registration the aurora banner uses, inside {@code act}.
 *
 * @param {object} [options] `closeMap`'s options (`{restoreFocus}`)
 */
export function closeMapView(options = {}) {
  act(() => { doors.close(options); });
}

/** Whether a shell has registered its doors (it does so on mount). */
export function mapDoorReady() {
  return doors != null;
}

/**
 * Presses a row of the ☰ menu, opening the menu first when it is shut: Settings, Change home postcode, Guide, Operations
 * and Sign out are rows of one menu at every width (`docs/engineering/one-screen-plan.md` D14), where the desktop's
 * inline cog, `?` and Sign out buttons used to be.
 *
 * @param {string} testId the row's test id (`window-first-settings`, `masthead-menu-guide`, `window-first-signout`, ...)
 */
export function pressMenuRow(testId) {
  if (!document.querySelector('[data-testid="masthead-menu"]')) {
    act(() => { document.querySelector('[data-testid="masthead-menu-button"]').click(); });
  }
  act(() => { document.querySelector(`[data-testid="${testId}"]`).click(); });
}

/**
 * For the App-level tests, which mount the real shell inside `App`: wraps the shell module so its `registerOpenMap`
 * registration also reaches {@link openMapView}, while `App`'s own registration (the aurora banner's door) still happens.
 * Use it from a `vi.mock` factory:
 *
 * <pre>vi.mock('../components/WindowFirstShell.jsx', async (importOriginal) => (
 *   (await import('./shellMapDoor.js')).withMapDoors(await importOriginal())));</pre>
 *
 * @param {{default: Function}} actual the real module
 * @returns {{default: Function}} the module with its default export wrapped
 */
export function withMapDoors(actual) {
  const Shell = actual.default;
  function ShellWithDoors(props) {
    const { registerOpenMap } = props;
    const register = useCallback((next) => {
      registerMapDoor(next);
      registerOpenMap?.(next);
    }, [registerOpenMap]);
    return createElement(Shell, { ...props, registerOpenMap: register });
  }
  return { ...actual, default: ShellWithDoors };
}

/**
 * App-level: waits until the map is offered (the forecast has landed, so `App` handed the shell a pane) by trying the
 * door until the view opens. A door with no window changes nothing but the view, so retrying is harmless.
 */
export async function openMapWhenOffered() {
  await waitFor(() => {
    openMapView();
    if (!document.querySelector('[data-testid="map-screen"]:not([hidden])')) throw new Error('the map is not offered yet');
  });
}

/** App-level: waits until the map is offered, leaving the view closed (what a test used to prove by the Map button). */
export async function mapOffered() {
  await openMapWhenOffered();
  closeMapView();
}

/**
 * Opens the ☰ menu when it is shut and returns the row with this test id (null when the menu has no such row), for a test
 * that presses or inspects it itself.
 *
 * @param {string} testId the row's test id
 * @returns {?HTMLElement}
 */
export function menuRow(testId) {
  if (!document.querySelector('[data-testid="masthead-menu"]')) {
    act(() => { document.querySelector('[data-testid="masthead-menu-button"]').click(); });
  }
  return document.querySelector(`[data-testid="${testId}"]`);
}
