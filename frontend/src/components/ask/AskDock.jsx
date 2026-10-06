import { useEffect, useLayoutEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import AskClearAnswer from './AskClearAnswer.jsx';
import AskConversation from './AskConversation.jsx';
import AskInputRow from './AskInputRow.jsx';
import useAskRequestContext from '../../hooks/useAskRequestContext.js';

/** The dock's id: the one an {@code AskField}'s {@code aria-controls} names while it is open. */
export const ASK_DOCK_ID = 'wf-ask-dock';

/**
 * Keeps a sticky dock's bottom at the bottom of the viewport.
 *
 * <p>The dock is {@code position: sticky; top: 0; height: 100dvh} (plan §2.6), which is right once the
 * page has scrolled past what sits above the shell — but BEFORE that its top is a banner and `<main>`'s
 * padding down the page, so a 100dvh box would hang that far below the fold and take its input row with
 * it, on the very first paint. A sticky element's own top is {@code max(natural top, 0)}, which is
 * exactly the amount to take off its height; it does not depend on the height, so there is no loop.
 * The figure is published as {@code --wf-dock-top} (the stylesheet subtracts it) and is written
 * imperatively, the way {@code useLensReserve} writes its own, rather than through state: it changes
 * on every scroll frame until the dock sticks and touches no React tree.
 *
 * @param {{current: ?HTMLElement}} ref the dock
 * @param {boolean} enabled false on the Map tab, where the dock is the frame's own height
 */
function useStickyViewportFill(ref, enabled) {
  useLayoutEffect(() => {
    const node = ref.current;
    if (!enabled || !node) return undefined;
    let frame = 0;
    const measure = () => {
      frame = 0;
      const top = Math.max(0, Math.round(node.getBoundingClientRect().top));
      node.style.setProperty('--wf-dock-top', `${top}px`);
    };
    const schedule = () => {
      if (frame === 0) frame = requestAnimationFrame(measure);
    };
    measure();
    window.addEventListener('scroll', schedule, { passive: true });
    window.addEventListener('resize', schedule);
    // A banner arriving above the shell moves the dock's natural top without a scroll or a resize.
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule);
    observer?.observe(document.body);
    return () => {
      if (frame !== 0) cancelAnimationFrame(frame);
      window.removeEventListener('scroll', schedule);
      window.removeEventListener('resize', schedule);
      observer?.disconnect();
      node.style.removeProperty('--wf-dock-top');
    };
  }, [ref, enabled]);
}

/**
 * Keeps focus from falling to {@code <body>} when the dock stops being something focus can be in.
 *
 * <p>Two routes, both of which put focus on {@code <body>} with no event anyone could answer: the dock
 * becoming {@code inert} while it holds focus (a health poll reads DOWN while the reader is typing; a
 * dialog is opened programmatically), and the dock being released while it holds focus (the window
 * crosses 1024px, as at 200% zoom or an iPad rotating; Ask is switched off). Either way the reader's
 * next Escape and Tab start from the top of the page and the answer they were reading has gone from the
 * accessibility tree.
 *
 * <p>A LAYOUT effect, so it runs in the commit that sets {@code inert} — before the browser's own focus
 * fix-up — and its cleanup runs while the dock's node is still in the document on unmount. The reader
 * is handed to {@code fallbackFocus()}: the tab in force, which is on screen on every route here and
 * never the field (the field is disabled in the first case and about to be replaced in the second). It
 * does nothing when focus is elsewhere, so a ✕ or Escape close, which moves focus FIRST, is untouched.
 *
 * @param {{current: ?HTMLElement}} ref the dock
 * @param {boolean} inert whether the dock is inert in this commit
 * @param {function(): ?HTMLElement} fallbackFocus where focus goes
 */
function useKeepFocusAlive(ref, inert, fallbackFocus) {
  const fallbackRef = useRef(fallbackFocus);
  useLayoutEffect(() => { fallbackRef.current = fallbackFocus; }, [fallbackFocus]);
  const rescue = () => {
    const node = ref.current;
    if (node && node.contains(document.activeElement)) {
      fallbackRef.current()?.focus({ preventScroll: true });
    }
  };
  useLayoutEffect(() => {
    if (inert) rescue();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `rescue` closes over refs only
  }, [inert]);
  useLayoutEffect(() => rescue, []); // eslint-disable-line react-hooks/exhaustive-deps
}

/**
 * Ask PhotoCast's docked column — the answer surface from 1024px up (plan §2.6; design README, "Where
 * Ask lives": a 380px column docked right, main content narrows). The shell mounts it as a SIBLING of
 * the whole shell column (masthead, tab row and panel), so the masthead and the panel narrow together
 * and O-17's "the two columns never drift apart" holds.
 *
 * <p><b>It is not a dialog.</b> An {@code <aside>} — a {@code complementary} landmark by its own
 * implicit role, named "Ask PhotoCast" — with no {@code aria-modal}, no focus
 * handling beyond what is below: the page beside it stays live, which is the point of a dock, and
 * the Map's Escape ladders and outside-press rules read "is a modal over me" off {@code aria-modal}
 * dialogs — a dock that claimed to be one would make every map panel stand down for it
 * ({@code utils/mapForeignModal.js}). What it does instead is the shell's: it is {@code inert} while a
 * shell dialog, search or settings is open (never the page; its scrim covers it, so it is not dimmed
 * as well), and it closes when the reader leaves Ask's tabs. It is deliberately NOT inert under a dead
 * backend: it must stay closable.
 *
 * <p><b>Escape closes it only while focus is inside it.</b> The handler is on the dock, so a press
 * with focus anywhere else never reaches it and every other Escape rule behaves exactly as before; a
 * press inside it closes the dock, returns focus to the field (the shell's {@code onClose}) and stops
 * propagating, so the same key does not also close a layer the reader never meant. An IME's
 * composing Escape is left alone.
 *
 * <p><b>Closing keeps the conversation</b> (✕ and Escape only close); "Clear answer" ends it. The
 * dock unmounts when closed, so its input row's text resets with each opening — the sheet's rule.
 *
 * <p>{@code data-ask-surface} is what {@code useOutsideDismiss} reads: a press in here dismisses no
 * map panel, so choosing a pick card does not close the drilldown it is being compared with.
 *
 * @param {object} props
 * @param {'desktop'|'wide'} props.band 360px from 1024px, 380px from 1180px
 * @param {boolean} props.sticky Plan and Coming up (the document scrolls: stick to the viewport); false
 *        on the Map, where the dock is the frame's own height
 * @param {boolean} props.inert a shell dialog, search or settings is open
 * @param {'map'|'plan'|'coming-up'} props.view the tab the suggestions are offered for and the view the
 *        question is sent with
 * @param {string} props.viewLabel the view chip's text, e.g. "Plan · all regions"
 * @param {string} props.contextLabel what the header says the dock is beside, e.g. "on Plan"
 * @param {{current: ?HTMLElement}} props.inputRef the question field, so the shell can focus it
 * @param {function(): void} props.onClose the ✕ and Escape call this (closes and returns focus)
 * @param {function(): ?HTMLElement} props.fallbackFocus where focus goes if the dock stops being focusable
 *        while it holds it (the tab in force)
 * @param {function(object): React.ReactNode} [props.pickActions] controls for each pick card's row
 * @param {{openInPlan: ?function(object): void, setPostcode: ?function(): void}} [props.planActions]
 *        the doors out of "Plan this" (F5). The dock is not modal, so {@code openInPlan} leaves it open
 *        beside the location sheet it opens (which makes it {@code inert} while it is up)
 */
export default function AskDock({
  band, sticky, inert, view, viewLabel, contextLabel, inputRef, onClose, fallbackFocus,
  pickActions = undefined, planActions = undefined,
}) {
  const dockRef = useRef(null);
  // On the Map: the region in scope and the window on the pill, as the pane published them.
  const requestContext = useAskRequestContext(view, viewLabel);
  useStickyViewportFill(dockRef, sticky);
  useKeepFocusAlive(dockRef, inert, fallbackFocus);
  // Opening focuses the question field — the mock's own behaviour, and the whole of what `/` means
  // when the dock is closed. Run once per mounting; the field is a read-only sentence when typed
  // questions are off, and still the right place for focus (it names the reason).
  useEffect(() => {
    inputRef.current?.focus({ preventScroll: true });
  }, [inputRef]);

  // A native listener on the dock's own node, not a React prop: a keydown reaches it only when the
  // focused element is inside the dock, which is exactly the rule, and stopping it here stops it for
  // every `document`-level Escape rule too (React's own listener is further up, at the root).
  // Held in a ref so a fresh `onClose` each render does not re-subscribe.
  const onCloseRef = useRef(onClose);
  useEffect(() => { onCloseRef.current = onClose; }, [onClose]);
  useEffect(() => {
    const node = dockRef.current;
    if (!node) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== 'Escape' || event.isComposing) return;
      event.stopPropagation();
      onCloseRef.current();
    };
    node.addEventListener('keydown', onKeyDown);
    return () => node.removeEventListener('keydown', onKeyDown);
  }, []);

  return (
    <aside
      ref={dockRef}
      id={ASK_DOCK_ID}
      aria-label="Ask PhotoCast"
      data-ask-surface=""
      data-testid="ask-dock"
      data-band={band}
      data-sticky={sticky ? '' : undefined}
      inert={inert || undefined}
      className="wf-ask-dock"
    >
      <div className="wf-ask-dock-inner">
        <header className="wf-ask-dock-head">
          <span className="wf-ask-dock-t" aria-hidden="true">Ask</span>
          <span className="wf-ask-dock-ctx" data-testid="ask-dock-context">
            <span aria-hidden="true">· </span>
            {contextLabel}
          </span>
          <button
            type="button"
            className="wf-ask-dock-x"
            data-testid="ask-dock-close"
            aria-label="Close Ask"
            onClick={onClose}
          >
            <span aria-hidden="true">✕</span>
          </button>
        </header>
        <div className="wf-ask-dock-scroll" data-testid="ask-dock-scroller">
          <AskConversation
            view={view}
            scope={requestContext.scope}
            viewLabel={requestContext.viewLabel}
            windowLabel={requestContext.windowLabel}
            windowId={requestContext.windowId}
            regionIds={requestContext.regionIds}
            pickActions={pickActions}
            planActions={planActions}
          />
          <AskClearAnswer inputRef={inputRef} />
        </div>
        <AskInputRow inputRef={inputRef} view={view} viewLabel={viewLabel} />
      </div>
    </aside>
  );
}

AskDock.propTypes = {
  band: PropTypes.oneOf(['desktop', 'wide']).isRequired,
  sticky: PropTypes.bool.isRequired,
  inert: PropTypes.bool.isRequired,
  view: PropTypes.oneOf(['map', 'plan', 'coming-up']).isRequired,
  viewLabel: PropTypes.string.isRequired,
  contextLabel: PropTypes.string.isRequired,
  inputRef: PropTypes.oneOfType([PropTypes.func, PropTypes.shape({ current: PropTypes.any })]).isRequired,
  onClose: PropTypes.func.isRequired,
  fallbackFocus: PropTypes.func.isRequired,
  pickActions: PropTypes.func,
  planActions: PropTypes.shape({ openInPlan: PropTypes.func, setPostcode: PropTypes.func }),
};
