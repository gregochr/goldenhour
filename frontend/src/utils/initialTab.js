/**
 * The app's phone boundary plus a landscape-phone arm — see default-tab-by-device-plan.md §3.2.
 * The first arm mirrors {@code useIsMobile}'s own query, so the default agrees with the layout the
 * reader will actually get; the second catches an iPhone held landscape (widths up to ~932px,
 * heights ≤ ~440px), which the width test alone would send to the map. Every iPad has a height
 * ≥ 744px in either orientation, and a desktop has a fine pointer, so neither arm trips on it.
 */
export const PHONE_OPENING_QUERY = '(max-width: 639px), (pointer: coarse) and (max-height: 499px)';

/**
 * The tab the app opens on for this device: Plan on a phone, Map on anything larger.
 *
 * <p>Read once at mount — never re-evaluated on resize or rotation (plan §3.3): rotating the
 * device or resizing the window after load must not move the reader to another tab.
 *
 * <p>Fail-safe: with no {@code matchMedia} (SSR, very old engines) answers {@code 'plan'}, the
 * long-standing default.
 *
 * @param {Window|undefined} [win] the window to read the media query from — a parameter rather
 *        than the bare global so the function is testable without touching the global stub.
 * @returns {'plan'|'map'}
 */
export function resolveInitialTab(win = typeof window === 'undefined' ? undefined : window) {
  if (!win?.matchMedia) return 'plan';
  return win.matchMedia(PHONE_OPENING_QUERY).matches ? 'plan' : 'map';
}
