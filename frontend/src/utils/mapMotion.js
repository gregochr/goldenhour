import { flushSync } from 'react-dom';

/**
 * The card opens into the map (`docs/engineering/screen-spec.md` D4, `screen-spec-plan.md` section 6): a card's
 * thumbnail morphs into the map view, and the view morphs back into its card, with the browser's View Transitions.
 *
 * <p>This is decoration over a state change that always happens: every path that cannot morph runs the update
 * plainly, so a browser without {@code document.startViewTransition} (Firefox before 144, older Safari), a stepping
 * map, a load-time open and a card that is gone behave exactly as they did before.
 *
 * <h2>The names are classes</h2>
 *
 * <p>The CSP is {@code style-src 'self'}: no inline style, so {@code view-transition-name} is only ever set through
 * {@link CARD_CLASS} and {@link FRAME_CLASS} (`index.css`). At most one element carries the name in each state: the
 * card in the old state and the view's root in the new one on an open, the reverse on a close.
 *
 * <h2>The live map is never in a snapshot</h2>
 *
 * <p>While a transition runs the view's root carries {@link ARRIVING_CLASS}, which makes the pane
 * {@code visibility: hidden} (never {@code display: none}, so Leaflet still measures a real box and the hidden-mount
 * guards are unchanged). The class is removed when the transition ends, with a {@link CLEANUP_TIMEOUT_MS} timer behind
 * it, so a skipped or failed transition can never leave the map invisible.
 */

/** The class that gives a card thumbnail the morph's name (`view-transition-name: wf-map-morph`). */
export const CARD_CLASS = 'wf-vt-card';
/** The class that gives the view's root the morph's name. */
export const FRAME_CLASS = 'wf-vt-frame';
/** The class that hides the pane while the morph runs (visibility, never display). */
export const ARRIVING_CLASS = 'wf-map-arriving';
/** On the document element while a close morph runs, so the close can be shorter than the open. */
export const CLOSING_CLASS = 'wf-vt-closing';
/** The longest a morph's classes stay on, once its update has run, before they are taken off regardless. */
export const CLEANUP_TIMEOUT_MS = 600;
/** The longest an open waits for the browser to call its update (a frame, in practice) before giving up on the morph. */
export const CALLBACK_TIMEOUT_MS = 2000;
/** The thumbnail on a card (`WindowFirstHeatStrip`), the only source the morph starts from. */
export const THUMBNAIL_SELECTOR = '.wf-hc-cv';
const CARD_SELECTOR = '[data-testid="wf-heat-card"]';

/** True while a morph is between its start and its cleanup, so a second press never starts a second one. */
let busy = false;
/** What the running morph is ('open' or 'close') and its transition, so a close pressed mid-open can end it. */
let activeKind = null;
let activeTransition = null;

/** Whether this browser can run a view transition at all. */
function supported() {
  return typeof document !== 'undefined' && typeof document.startViewTransition === 'function';
}

/**
 * The card thumbnail an open or close started from: {@code from} is a thumbnail, a card, or something inside a card
 * (the card's own button, which is what holds focus when a card is pressed).
 *
 * @param {?Element} from the element the door was pressed on
 * @returns {?Element} the connected thumbnail, or null (a lane, a chip, Find, a link: not a morph)
 */
export function thumbnailOf(from) {
  if (!(from instanceof Element) || !from.isConnected) return null;
  const thumbnail = from.matches(THUMBNAIL_SELECTOR)
    ? from
    : from.closest(CARD_SELECTOR)?.querySelector(THUMBNAIL_SELECTOR) ?? null;
  return thumbnail?.isConnected ? thumbnail : null;
}

/** Whether an element is drawn and (at least partly) inside the viewport: only then is it worth morphing to or from. */
function visibleOnScreen(element) {
  const rect = element.getBoundingClientRect();
  if (rect.width <= 0 || rect.height <= 0) return false;
  return rect.bottom > 0 && rect.right > 0 && rect.top < window.innerHeight && rect.left < window.innerWidth;
}

/**
 * Whether an open should morph.
 *
 * @param {object} conditions
 * @param {?Element} conditions.source the thumbnail
 * @param {boolean} [conditions.inPlace] the view is already open (a window moving inside it)
 * @param {boolean} [conditions.loadTime] the open came from the page's own address
 * @param {boolean} [conditions.stepping] the reader is stepping windows with the arrows
 * @returns {boolean}
 */
export function canMorphOpen({ source, inPlace = false, loadTime = false, stepping = false }) {
  return supported() && !busy && !inPlace && !loadTime && !stepping && Boolean(source?.isConnected);
}

/**
 * Runs {@code update} (the whole open) inside a view transition from the card's thumbnail, or plainly.
 *
 * <p>{@code update} is the WHOLE open body (it closes the shell's layers, takes the door, sets the route and note and
 * shows the view), run inside the transition's callback with {@code flushSync}, so the old snapshot is the untouched
 * screen. A second press while this one is pending runs its {@code update} plainly at once (this module is busy);
 * the first press's deferred body then finds the view open and re-targets it in place, so there is still one view and
 * one history entry.
 *
 * @param {object} options
 * @param {?Element} options.source the card thumbnail the open was pressed on
 * @param {function(): void} options.update the open
 * @param {boolean} [options.inPlace] see {@link canMorphOpen}
 * @param {boolean} [options.loadTime] see {@link canMorphOpen}
 * @param {boolean} [options.stepping] see {@link canMorphOpen}
 * @param {function(): ?Element} [options.frame] the view's root, read after the update has committed
 * @param {function(): void} [options.onDone] runs once the classes are off (a focus that could not land while the pane
 *        was hidden is re-applied here)
 * @returns {boolean} whether a morph was started
 */
export function openWithMotion({
  source, update, inPlace = false, loadTime = false, stepping = false,
  frame = () => document.querySelector('[data-map-screen]'), onDone = undefined,
}) {
  if (!canMorphOpen({ source, inPlace, loadTime, stepping })) {
    update();
    return false;
  }
  let ran = false;
  let cleaned = false;
  let cleanupWanted = false;
  let frameNode = null;
  let timer = null;
  /** Takes the names and the hidden pane off. Never before the update has run (it would be undone by it). */
  const cleanup = (force = false) => {
    if (cleaned) return;
    if (!ran && force !== true) {
      cleanupWanted = true;
      return;
    }
    cleaned = true;
    // The callback never came (forced cleanup): the press must not be lost, so the update runs here, once.
    if (!ran) {
      ran = true;
      update();
    }
    busy = false;
    activeKind = null;
    activeTransition = null;
    clearTimeout(timer);
    source.classList.remove(CARD_CLASS);
    frameNode?.classList.remove(FRAME_CLASS, ARRIVING_CLASS);
    onDone?.();
  };
  const run = () => {
    if (cleaned) {
      // Late: a forced cleanup got here first. Run the update plainly if nothing has, and leave no class behind.
      if (!ran) {
        ran = true;
        flushSync(update);
      }
      return;
    }
    ran = true;
    flushSync(update);
    // The name moves from the card to the view in this one commit: two elements with one name would abort the morph.
    source.classList.remove(CARD_CLASS);
    frameNode = frame();
    frameNode?.classList.add(FRAME_CLASS, ARRIVING_CLASS);
    // The clock for the animation starts here, not at the press: a slow first mount must not cut it short.
    clearTimeout(timer);
    timer = setTimeout(cleanup, CLEANUP_TIMEOUT_MS);
    if (cleanupWanted) cleanup();
  };
  busy = true;
  source.classList.add(CARD_CLASS);
  let transition;
  try {
    transition = document.startViewTransition(run);
  } catch {
    cleanup(true);
    return false;
  }
  activeKind = 'open';
  activeTransition = transition;
  timer = setTimeout(() => cleanup(true), CALLBACK_TIMEOUT_MS);
  // Skipped, aborted or rejected: all end in the same cleanup, and none leaves an unhandled rejection behind.
  transition.updateCallbackDone?.catch?.(() => cleanup());
  Promise.resolve(transition.finished).catch(() => undefined).finally(cleanup);
  return true;
}

/**
 * Whether a close should morph: the same browser test, the view and its card both drawn, and not already running one.
 *
 * @param {object} conditions
 * @param {?Element} conditions.source the card thumbnail the view was opened from
 * @param {?Element} conditions.frame the view's root
 * @returns {boolean}
 */
export function canMorphClose({ source, frame }) {
  return supported() && !busy && Boolean(source?.isConnected) && Boolean(frame?.isConnected)
    && !frame.hidden && visibleOnScreen(source);
}

/**
 * Runs {@code update} (the close) as a morph back into the card the view was opened from, or plainly.
 *
 * <p>Only the close button and Escape call this; Back, the scrim and every other close stay instant. The pane is
 * hidden first and one frame is allowed to paint, so the old snapshot holds the view's frame without the live tiles;
 * the name then goes on the frame, and the card takes it in the new state.
 *
 * @param {object} options
 * @param {?Element} options.source the card thumbnail the view was opened from
 * @param {?Element} options.frame the view's root
 * @param {function(): void} options.update the close
 * @returns {boolean} whether a morph was started
 */
export function closeWithMotion({ source, frame, update }) {
  if (busy) {
    // A close pressed while the OPEN morph is still running (a quick reader, a held key) must close, not be swallowed:
    // end the morph where it is and close plainly. A second press on a running close morph is the duplicate.
    if (activeKind === 'open') {
      activeTransition?.skipTransition?.();
      update();
      return false;
    }
    return true;
  }
  if (!canMorphClose({ source, frame })) {
    update();
    return false;
  }
  let cleaned = false;
  let updated = false;
  let timer = null;
  /** The close, exactly once, whichever path reaches it first. */
  const closeOnce = () => {
    if (updated) return;
    updated = true;
    update();
  };
  const cleanup = () => {
    if (cleaned) return;
    cleaned = true;
    busy = false;
    activeKind = null;
    activeTransition = null;
    watcher?.disconnect();
    clearTimeout(timer);
    document.documentElement.classList.remove(CLOSING_CLASS);
    source.classList.remove(CARD_CLASS);
    frame.classList.remove(FRAME_CLASS, ARRIVING_CLASS);
  };
  busy = true;
  activeKind = 'close';
  frame.classList.add(ARRIVING_CLASS);
  // The view being hidden or shown again during the one-frame wait (Back, a reopen) means this close is stale.
  let viewChanged = false;
  const watcher = typeof MutationObserver === 'function'
    ? new MutationObserver(() => { viewChanged = true; })
    : null;
  watcher?.observe(frame, { attributes: true, attributeFilter: ['hidden'] });
  // A frame that never comes (a hidden tab) must not leave the view open: the timer closes it plainly.
  timer = setTimeout(() => { cleanup(); closeOnce(); }, CLEANUP_TIMEOUT_MS);
  requestAnimationFrame(() => {
    if (cleaned) return;
    watcher?.takeRecords();
    watcher?.disconnect();
    // Closed (or closed and reopened) while waiting: nothing to close, and a stale close must never hit a new view.
    if (viewChanged || frame.hidden) {
      cleanup();
      return;
    }
    // The card may have scrolled out or unmounted in the frame that was waited for: then it is a plain close.
    if (!canMorphCloseNow(source, frame)) {
      cleanup();
      closeOnce();
      return;
    }
    frame.classList.add(FRAME_CLASS);
    document.documentElement.classList.add(CLOSING_CLASS);
    const run = () => {
      if (cleaned) {
        // Late: the timer got here first. Close only if nothing has, and name nothing.
        closeOnce();
        return;
      }
      updated = true;
      clearTimeout(timer);
      timer = setTimeout(cleanup, CLEANUP_TIMEOUT_MS);
      flushSync(update);
      frame.classList.remove(FRAME_CLASS);
      if (source.isConnected) source.classList.add(CARD_CLASS);
    };
    let transition;
    try {
      transition = document.startViewTransition(run);
    } catch {
      cleanup();
      closeOnce();
      return;
    }
    activeTransition = transition;
    transition.updateCallbackDone?.catch?.(() => cleanup());
    Promise.resolve(transition.finished).catch(() => undefined).finally(cleanup);
  });
  return true;
}

/** The checks of {@link canMorphClose} that can change in the frame a close waits for (it is `busy` by then). */
function canMorphCloseNow(source, frame) {
  return source.isConnected && frame.isConnected && visibleOnScreen(source);
}
