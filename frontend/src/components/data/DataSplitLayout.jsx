import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { useIsSplitLayout } from '../../hooks/useIsSplitLayout.js';

/** Marks the panel's title, the element that takes focus when a panel opens. */
export const PANEL_TITLE_ATTR = 'data-panel-title';

/**
 * The Data tabs' list-and-panel shell (data-tabs-redesign-plan.md §4). The mode follows the viewport:
 * <ul>
 *   <li>1000px and up: split, the list beside a 440px panel that stays in view as the list scrolls;
 *   <li>640 to 999px: overlay, the panel over the right of the list card under a scrim that closes it; the list is
 *       {@code inert} while it is open, so Tab and clicks cannot reach what the scrim covers;
 *   <li>639px and under ({@link useIsMobile}, the project's one phone rule): a full page, a 52px back bar over the
 *       panel and the list out of sight (kept mounted, so its search and filters survive, and its scroll position is
 *       put back when the panel closes).
 * </ul>
 * Split versus overlay is CSS (so crossing 1000px never remounts anything); {@link useIsSplitLayout} is the same
 * breakpoint in JS, used only for {@code inert}. Phone-only classes sit behind {@code isMobile}.
 *
 * <p>Focus: opening a panel, switching entries, or changing the panel's mode ({@code focusKey}: view to edit and back)
 * moves focus to the element marked {@link PANEL_TITLE_ATTR};
 * closing it puts focus back on the row that opened it ({@code {rowTestIdPrefix}-row-{selectionKey}}), or, when
 * that row is gone, on the list's search box or heading. It never steals focus the reader has moved elsewhere.
 *
 * <p>⚠️ The panel is never {@code role="dialog"} or {@code aria-modal}, in any mode: {@code mapForeignModal.js}
 * treats such an element outside the Map pane as a foreign modal without asking whether it is visible, and the
 * Operations pane is hidden, never unmounted, so a panel left open would break the Map's Escape and outside press
 * for the session. It is an {@code <aside aria-label>}, and the scrim is a plain {@code aria-hidden} div.
 *
 * @param {object} props
 * @param {import('react').ReactNode} props.list the list card's contents (always mounted)
 * @param {import('react').ReactNode} [props.panel] the panel's contents; a falsy value means no panel is open
 * @param {() => void} props.onClose closes the panel (the scrim, and the phone back button unless {@code onBack})
 * @param {() => void} [props.onBack] the phone back button, when it should do something other than close (step back
 *   from a sub-mode to the entry); defaults to {@code onClose}
 * @param {string} props.panelLabel the panel's accessible name (an {@code aria-label})
 * @param {string} props.backLabel the tab's name for the phone back button ("‹ Waitlist")
 * @param {import('react').ReactNode} [props.backAction] the panel's primary action, on the phone back bar's right
 * @param {{current: HTMLElement|null}} [props.panelRef] set to the panel's root, for {@code usePanelEscape}
 * @param {string|number|null} [props.selectionKey] the open entry's id, for returning focus to its row
 * @param {string} [props.focusKey] changes whenever the panel's body changes mode (defaults to the selection): focus
 *   moves to the panel title each time, so a keyboard or screen-reader user is not left on a control that is gone
 * @param {string} [props.rowTestIdPrefix] the grid's test id prefix; its rows are {@code {prefix}-row-{id}}
 * @param {string} [props.testId] the layout's test id; the panel and scrim derive theirs from it
 */
export default function DataSplitLayout({
  list, panel = null, onClose, panelLabel, backLabel, backAction = null, panelRef = undefined,
  selectionKey = null, rowTestIdPrefix = undefined, testId = 'data-split', onBack = undefined, focusKey = undefined,
}) {
  const isMobile = useIsMobile();
  const isSplit = useIsSplitLayout();
  const open = Boolean(panel);
  const ownRef = useRef(null);
  const asideRef = panelRef ?? ownRef;
  const rootRef = useRef(null);
  const wasOpen = useRef(false);
  const openKey = useRef(null);
  const openFocusKey = useRef(null);
  const effectiveFocusKey = focusKey ?? selectionKey;
  const listScroll = useRef(0);

  // The phone page replaces the list, so remember where the list was scrolled to put it back.
  useEffect(() => {
    if (!isMobile || open) return undefined;
    const onScroll = () => { listScroll.current = window.scrollY; };
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, [isMobile, open]);

  useEffect(() => {
    const opening = open && (!wasOpen.current || openKey.current !== selectionKey
      || openFocusKey.current !== effectiveFocusKey);
    const closing = !open && wasOpen.current;
    if (opening) {
      asideRef.current?.querySelector(`[${PANEL_TITLE_ATTR}]`)?.focus({ preventScroll: true });
      if (isMobile && !wasOpen.current) window.scrollTo(0, 0);
    } else if (closing) {
      if (isMobile) window.scrollTo(0, listScroll.current);
      const active = document.activeElement;
      // Only when focus fell to the page (the panel it was in has gone); never from where the reader moved it.
      if (!active || active === document.body) {
        const root = rootRef.current;
        const row = rowTestIdPrefix == null ? null
          : root?.querySelector(`[data-testid="${rowTestIdPrefix}-row-${openKey.current}"]`);
        const fallback = row ?? root?.querySelector('input[type="search"]') ?? root?.querySelector('h2');
        if (fallback && !fallback.matches('input, button, [tabindex]')) fallback.tabIndex = -1;
        fallback?.focus({ preventScroll: true });
      }
    }
    wasOpen.current = open;
    openKey.current = open ? selectionKey : null;
    openFocusKey.current = open ? effectiveFocusKey : null;
  }, [open, selectionKey, effectiveFocusKey, isMobile, asideRef, rowTestIdPrefix]);

  const rootClass = open && !isMobile
    ? 'relative max-[999px]:min-h-[34rem] min-[1000px]:grid min-[1000px]:grid-cols-[minmax(0,1fr)_440px]'
    : 'relative';
  const panelClass = isMobile
    ? 'min-w-0 bg-plex-panel pb-[var(--safe-b)] pl-[var(--safe-l)] pr-[var(--safe-r)]'
    : 'absolute inset-y-0 right-0 z-20 w-[440px] max-w-full border-l border-plex-border bg-plex-panel shadow-[-12px_0_32px_rgba(0,0,0,.45)] min-[1000px]:static min-[1000px]:w-auto min-[1000px]:shadow-none';
  // Overlay and split: the content rides in a box that sticks to the top and is no taller than the viewport (or the
  // card, when that is shorter), so the panel stays in view as the list scrolls and its body scrolls inside it.
  const stickyClass = isMobile ? '' : 'sticky top-0 flex max-h-[min(100dvh,100%)] min-h-0 flex-col';

  return (
    <div ref={rootRef} className={rootClass} data-testid={testId}>
      <div
        className="min-w-0"
        hidden={open && isMobile}
        inert={open && !isSplit}
        data-testid={`${testId}-list`}
      >
        {list}
      </div>
      {open && !isMobile && (
        <div
          className="absolute inset-0 z-10 bg-[rgba(10,7,6,0.66)] min-[1000px]:hidden"
          aria-hidden="true"
          onClick={onClose}
          data-testid={`${testId}-scrim`}
        />
      )}
      {open && (
        <aside ref={asideRef} aria-label={panelLabel} className={panelClass} data-testid={`${testId}-panel`}>
          {isMobile && (
            <div
              className="sticky top-0 z-10 flex h-[52px] items-center justify-between gap-2 border-b border-plex-border bg-plex-panel px-2"
              data-testid={`${testId}-backbar`}
            >
              <button
                type="button"
                className="flex min-h-[44px] items-center gap-1 rounded px-2 text-sm font-medium text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
                aria-label={`Back to ${backLabel}`}
                onClick={onBack ?? onClose}
                data-testid={`${testId}-back`}
              >
                <span aria-hidden="true">&lsaquo;</span>
                {backLabel}
              </button>
              {backAction}
            </div>
          )}
          <div className={stickyClass} data-testid={`${testId}-panel-box`}>{panel}</div>
        </aside>
      )}
    </div>
  );
}

DataSplitLayout.propTypes = {
  list: PropTypes.node.isRequired,
  panel: PropTypes.node,
  onClose: PropTypes.func.isRequired,
  panelLabel: PropTypes.string.isRequired,
  backLabel: PropTypes.string.isRequired,
  backAction: PropTypes.node,
  panelRef: PropTypes.shape({ current: PropTypes.any }),
  selectionKey: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
  rowTestIdPrefix: PropTypes.string,
  testId: PropTypes.string,
  onBack: PropTypes.func,
  focusKey: PropTypes.string,
};
