import { useLayoutEffect } from 'react';
import { MAP_PANE_SELECTOR } from '../utils/mapForeignModal.js';

/** The gap kept between a panel's far edge and the frame's — the chrome's own 8px inset. */
export const FRAME_GAP_PX = 8;

/** The custom property the stylesheet's `max-height: min(420px, var(--wf-fit-room, 420px))` reads. */
export const FIT_ROOM_PROPERTY = '--wf-fit-room';

/** The 36px window control, the top-left group's height while its own height is unpublished. */
const TL_FALLBACK_PX = 36;
/** Air kept between a `clearTopChrome` panel and the top-left chrome group. */
const TL_CLEARANCE_PX = 16;

/**
 * Caps a Map tab panel to the room its map frame has for it (owner report, 2026-10-08: the Filters
 * popover ran past the bottom of a short frame, the frame clipped it, and its last rows could not
 * be reached). Measures, in a layout effect while the panel is open, the distance from the panel's
 * anchored edge to the frame edge it grows toward, less {@link FRAME_GAP_PX}, and writes it as
 * {@link FIT_ROOM_PROPERTY} on the panel. The stylesheet reads it beside `overflow-y: auto`, so
 * every row stays reachable by scrolling, by wheel and by Tab.
 *
 * <p>A MEASUREMENT write (the same escape hatch `MapTideStrip`'s `--tsh` uses): the room depends on
 * laid-out geometry no class can know. Re-measured on a window resize and whenever the frame or
 * the anchor's own box changes size.
 *
 * <p>Precondition: a 'down' panel is anchored by its top edge and an 'up' panel by its bottom edge
 * (`top: calc(100% + 6px)` / `bottom: calc(100% + 6px)`), so its height never moves the edge measured.
 *
 * <p>No floor: a panel given more than the room would be clipped again, which is the bug itself.
 * Nothing is written outside a map pane or with no panel mounted, leaving the stylesheet's 420px.
 *
 * @param {{current: HTMLElement|null}} panelRef the panel element (mounted only while open)
 * @param {{current: HTMLElement|null}} anchorRef the control's root; its parent is the map chrome
 *   corner, whose offset parent is the positioned frame
 * @param {object} options
 * @param {'down'|'up'} [options.direction] 'down' for a panel hanging below its chip (room = panel
 *   top to frame bottom, bounded by the viewport); 'up' for one standing above it (room = frame top,
 *   bounded by the viewport, to panel bottom)
 * @param {boolean} options.open whether the panel is showing
 * @param {boolean} [options.disabled] true where the panel is something else (a phone sheet)
 * @param {boolean} [options.clearTopChrome] for an 'up' panel: stop the room at the top-left chrome
 *   group's bottom edge plus 16px ({@code --wf-chrome-tl-h}, 36 while unpublished) rather than at the
 *   frame's top, so a tall bottom-left stack (the four-day strip) cannot let the panel grow over the
 *   window pill and the topic chips
 */
export function useFitToFrame(
  panelRef,
  anchorRef,
  {
    direction = 'down', open, disabled = false, clearTopChrome = false,
  },
) {
  useLayoutEffect(() => {
    if (!open || disabled) return undefined;
    const panel = panelRef.current;
    const anchor = anchorRef.current;
    const chrome = anchor?.parentElement;
    const pane = anchor?.closest(MAP_PANE_SELECTOR);
    if (!panel || !chrome || !pane) return undefined;
    // `offsetParent` is the positioned frame the chrome hangs in; the pane is the fallback where
    // there is none to read (jsdom, which has no layout).
    const frame = chrome.offsetParent ?? pane;
    const fit = () => {
      const frameRect = frame.getBoundingClientRect();
      const panelRect = panel.getBoundingClientRect();
      const tl = parseFloat(frame.style?.getPropertyValue?.('--wf-chrome-tl-h')) || TL_FALLBACK_PX;
      const ceiling = Math.max(frameRect.top, 0) + (clearTopChrome ? tl + TL_CLEARANCE_PX : 0);
      const room = direction === 'up'
        ? panelRect.bottom - ceiling - FRAME_GAP_PX
        : Math.min(frameRect.bottom, window.innerHeight) - panelRect.top - FRAME_GAP_PX;
      panel.style.setProperty(FIT_ROOM_PROPERTY, `${Math.max(Math.floor(room), 0)}px`);
    };
    fit();
    window.addEventListener('resize', fit);
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(fit);
    observer?.observe(frame);
    observer?.observe(chrome);
    const topChrome = clearTopChrome ? frame.querySelector?.('[data-testid="wf-map-chrome-tl"]') : null;
    if (topChrome) observer?.observe(topChrome);
    return () => {
      window.removeEventListener('resize', fit);
      observer?.disconnect();
    };
  }, [panelRef, anchorRef, direction, open, disabled, clearTopChrome]);
}
