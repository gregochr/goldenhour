import { useCallback, useEffect, useRef, useState } from 'react';
import { spotPeekPlacement } from '../utils/windowSpotPeek.js';

/**
 * How long the pointer must rest on a spot card before the peek opens.
 *
 * <p><b>180ms, not the plan's 140.</b> Plan §5`:581` and §5a`:697` both write "140ms open", and this
 * is a deliberate, recorded deviation — 180 is the whole difference between a panel that answers a
 * question and one that fires at a mouse crossing the row on its way somewhere else. This strip is
 * a horizontal row of up to seven cards separated by 8px, so a single sweep towards the
 * {@code ‹ ›} arrows crosses all of them; shortening the delay on a surface that dense is the wrong
 * direction.
 *
 * <p>It is load-bearing for the keyboard path as well: tabbing to a partly-offscreen card triggers
 * the browser's own scroll-into-view, and the delay is what keeps that from producing a flash — see
 * {@link useSpotPeek}.
 */
const PEEK_OPEN_DELAY_MS = 180;

/**
 * Grace after the pointer leaves the CARD, in px-time: long enough to cross the 10px gap to the
 * panel without it vanishing. The plan's number, adopted.
 */
const PEEK_TRIGGER_GRACE_MS = 160;

/**
 * Grace after the pointer leaves the PANEL. The plan's number, adopted — and the reason the two are
 * split rather than sharing one value: they are not the same journey. Leaving the
 * card means the reader is travelling <em>towards</em> the panel across a gap and needs the longer
 * allowance, while leaving the panel means they are done with it and the shorter one is what keeps a
 * dismissed peek from trailing the pointer down the page.
 */
const PEEK_PANEL_GRACE_MS = 120;

/**
 * The one peek on the page, held as its own dismisser.
 *
 * <p>Module-scoped rather than per-hook, and that is the whole point. State lives per strip so that
 * collapsing a window unmounts the panel with its anchor (see below), but the page must still carry
 * exactly <b>one</b> panel — the guarantee a shared popover host (since deleted as dead code) got
 * free from owning a single popover. A {@code focusin} listener alone cannot buy it back: it fires on a focus <em>change</em>,
 * so it covers open-by-pointer-then-Tab-away and not the reverse, and a pointer moving between two
 * expanded windows' strips sends the first strip nothing at all. Two correctly-placed tooltips would
 * then sit on screen together.
 *
 * <p>A dismisser rather than a flag, because the instance that must close is not the one doing the
 * opening. It is paired with an owner token — a stable per-instance object — so a hook can tell
 * "this is mine" without a {@code useCallback} referencing itself, which the lint rules forbid.
 * Every path that ends a peek clears this, so a stale entry cannot outlive its component.
 */
let openPeek = null;

/**
 * State, timers and dismissal for one strip's spot peek.
 *
 * <h2>One peek per strip, and that is what handles collapse</h2>
 *
 * <p>A shared popover host (since deleted as dead code) owned exactly one popover for a whole
 * subtree, for a reason worth keeping: a panel rendered inside the thing that opened it dies when
 * that thing re-renders. This hook
 * is scoped narrower on purpose. P9 shipped collapse, and a window card's collapsible region
 * unmounts {@code WindowSpotStrip} entirely — so a peek whose state lives in the strip is torn down
 * with its own anchor, by construction, with no toggle-aware dismissal to write or to forget. Nothing
 * else on the page dismisses on collapse, and the alternative (hoisting to the shell) would have had
 * to.
 *
 * <p>The cost of that choice is that six strips hold six independent peeks, so the "exactly one
 * panel" guarantee has to be bought back. Two things buy it, and neither is sufficient alone: the
 * page-level {@code openPeek} above, which any {@code open()} closes first and which therefore covers
 * a pointer moving between two expanded windows; and the {@code focusin} listener below, which closes
 * a peek when focus lands anywhere that is not its own anchor. The listener fires only on a focus
 * <em>change</em>, so on its own it covers open-by-pointer-then-Tab-away and not the reverse — that
 * asymmetry was a real defect, found by review rather than by a test.
 *
 * <h2>Dismissal is document-level, and scroll is the one that matters</h2>
 *
 * <p>Placement is computed once from the anchor's viewport rect and nothing recomputes it, so the
 * instant anything scrolls the panel describes a position its anchor has left. The listener is
 * registered in the <b>capture</b> phase because scroll does not bubble and the scroller here is an
 * inner element — {@code .wf-spots} — rather than the document. One capture listener therefore
 * catches all three routes at once: the {@code ‹ ›} nudge buttons, a trackpad swipe, and the
 * browser's own scroll-into-view when a card is tabbed to — including the page scroll underneath,
 * which a plain {@code onScroll} on the strip would not.
 *
 * <p>That third route is why the open <em>delay</em> must not be dropped for the focus path. Tabbing
 * to a partly-offscreen card fires focus, then a scroll-into-view. With the delay, the scroll lands
 * during it and cancels a peek that was never drawn. Without it, the peek would paint and then be
 * dismissed a frame later — a flash on exactly the cards nearest the strip's edges. The deliberate
 * consequence is that a keyboard peek does <b>not</b> open on a card the browser had to scroll into
 * view; it opens on every card already in the scrollport. That is a shortcut degrading, not a route
 * disappearing — the card is a real button and the peek is {@code aria-hidden}, so nothing reachable
 * only through it is lost.
 *
 * <p>Escape and resize close it too, matching the shared popover host's pattern (since deleted as
 * dead code). The listeners are registered
 * only while a peek is open or pending, so a page of six strips adds nothing at rest.
 *
 * @returns {{peek: ?object, open: function, hold: function, closeFromTrigger: function,
 *          closeFromPanel: function, dismiss: function}}
 */
export default function useSpotPeek() {
  const [peek, setPeek] = useState(null);
  // "A peek is open OR one is pending" — the listeners key on this rather than on `peek`, because a
  // scroll during the open delay has to cancel a panel that has not been drawn yet.
  const [armed, setArmed] = useState(false);
  const openTimer = useRef(null);
  const hideTimer = useRef(null);
  const anchorRef = useRef(null);
  // Stable per-instance identity, so this hook can recognise its own entry in `openPeek`. Lazy
  // state rather than `useRef({}).current` — reading a ref during render is a lint error here, and
  // the initialiser form gives the same "created once, never changes" guarantee without one.
  const [token] = useState(() => ({}));

  /** Cancels a pending open and a pending dismissal, and takes the panel down now. */
  const dismiss = useCallback(() => {
    clearTimeout(openTimer.current);
    clearTimeout(hideTimer.current);
    anchorRef.current = null;
    if (openPeek && openPeek.token === token) openPeek = null;
    setPeek(null);
    setArmed(false);
  }, [token]);

  /**
   * Schedules a peek for {@code anchorEl}, measuring at fire time rather than now.
   *
   * <p>React clears {@code currentTarget} once the handler returns, so the rect cannot simply be
   * read there and stashed. The element itself is a stable DOM node, though, so
   * capturing <em>it</em> and measuring inside the timeout is both safe and better: the panel is
   * then placed against where the card is when it opens, not where it was 180ms earlier — and the
   * strip is a surface where those differ, because a card can be nudged, snapped or scrolled into
   * view during the delay.
   *
   * @param {Element} anchorEl the spot card
   * @param {?Element} boundsEl the strip wrapper the panel is clamped inside
   * @param {object}  content  the resolved peek content
   */
  const open = useCallback((anchorEl, boundsEl, content) => {
    if (!anchorEl) return;
    // Any peek anywhere on the page goes first — including one belonging to a different window's
    // strip, which nothing else here can see. See `openPeekDismiss`.
    if (openPeek && openPeek.token !== token) openPeek.dismiss();
    openPeek = { token, dismiss };
    // Moving to a different card takes the old panel down at once rather than leaving it up for the
    // new one's delay. The trigger's own leave handler covers the pointer case; this covers the
    // keyboard one, where tabbing off a card the pointer is still resting on fires no mouseleave.
    if (anchorRef.current !== anchorEl) setPeek(null);
    anchorRef.current = anchorEl;
    clearTimeout(hideTimer.current);
    clearTimeout(openTimer.current);
    setArmed(true);
    openTimer.current = setTimeout(() => {
      const placed = spotPeekPlacement(
        anchorEl.getBoundingClientRect(),
        boundsEl?.getBoundingClientRect() ?? null,
      );
      setPeek({ ...placed, ...content });
    }, PEEK_OPEN_DELAY_MS);
  }, [dismiss, token]);

  /** The panel's own pointer-enter: cancels the grace the trigger's leave started. */
  const hold = useCallback(() => clearTimeout(hideTimer.current), []);

  // Routed through `dismiss` rather than repeating its body, so the page-level token above is
  // released on every path that ends a peek and not only on the immediate ones. Clearing the timer
  // that is currently firing is a no-op.
  const startClose = useCallback((graceMs) => {
    clearTimeout(openTimer.current);
    hideTimer.current = setTimeout(dismiss, graceMs);
  }, [dismiss]);

  /** Leaving the card. Starts the longer grace, because the pointer may be crossing to the panel. */
  const closeFromTrigger = useCallback(
    () => startClose(PEEK_TRIGGER_GRACE_MS), [startClose],
  );

  /** Leaving the panel. Starts the shorter grace — the reader is done with it. */
  const closeFromPanel = useCallback(
    () => startClose(PEEK_PANEL_GRACE_MS), [startClose],
  );

  useEffect(() => {
    if (!armed) return undefined;
    const onKeyDown = (e) => { if (e.key === 'Escape') dismiss(); };
    // Focus landing anywhere that is not this peek's own anchor takes it down. Without it a peek
    // opened by the pointer in one window card survives a Tab into another window's strip, and the
    // page carries two panels — the guarantee a shared popover host got free from owning only one.
    const onFocusIn = (e) => { if (e.target !== anchorRef.current) dismiss(); };
    // Capture: scroll does not bubble, and the scroller is `.wf-spots`, not the document.
    window.addEventListener('scroll', dismiss, true);
    window.addEventListener('resize', dismiss);
    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('focusin', onFocusIn);
    return () => {
      window.removeEventListener('scroll', dismiss, true);
      window.removeEventListener('resize', dismiss);
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('focusin', onFocusIn);
    };
  }, [armed, dismiss]);

  // Unmounting is the collapse path: a pending open timer that fires into a torn-down tree is a
  // setState on an unmounted component, and a pending hide timer would outlive the panel. The
  // page-level token is released too, or a collapsed window would leave a dismisser behind that
  // every later `open()` on any other strip would call into a torn-down tree.
  useEffect(() => () => {
    clearTimeout(openTimer.current);
    clearTimeout(hideTimer.current);
    if (openPeek && openPeek.token === token) openPeek = null;
  }, [token]);

  return { peek, open, hold, closeFromTrigger, closeFromPanel, dismiss };
}
