import PropTypes from 'prop-types';
import { useIsMobile } from '../../hooks/useIsMobile.js';

/**
 * The Data tabs' list-and-panel shell (data-tabs-redesign-plan.md §4). The mode follows the viewport:
 * <ul>
 *   <li>1000px and up: split, the list beside a 440px panel;
 *   <li>640 to 999px: overlay, the panel over the right of the list card under a scrim that closes it;
 *   <li>639px and under ({@link useIsMobile}, the project's one phone rule): a full page, a 52px back bar over the
 *       panel and the list out of sight (kept mounted, so its search and filters survive the round trip).
 * </ul>
 * The split and overlay modes are CSS only (so crossing 1000px never remounts anything); only the phone page needs
 * the hook. Phone-only classes sit behind {@code isMobile}, so 1000px and up is the same markup as before.
 *
 * <p>⚠️ The panel is never {@code role="dialog"} or {@code aria-modal}, in any mode: {@code mapForeignModal.js}
 * treats such an element outside the Map pane as a foreign modal without asking whether it is visible, and the
 * Operations pane is hidden, never unmounted, so a panel left open would break the Map's Escape and outside press
 * for the session. It is an {@code <aside aria-label>}, and the scrim is a plain {@code aria-hidden} div.
 *
 * @param {object} props
 * @param {import('react').ReactNode} props.list the list card's contents (always mounted)
 * @param {import('react').ReactNode} [props.panel] the panel's contents; a falsy value means no panel is open
 * @param {() => void} props.onClose closes the panel (the scrim, and the phone back button)
 * @param {string} props.panelLabel the panel's accessible name (an {@code aria-label})
 * @param {string} props.backLabel the tab's name for the phone back button ("‹ Waitlist")
 * @param {import('react').ReactNode} [props.backAction] the panel's primary action, on the phone back bar's right
 * @param {{current: HTMLElement|null}} [props.panelRef] set to the panel's root, for {@code usePanelEscape}
 * @param {string} [props.testId] the layout's test id; the panel and scrim derive theirs from it
 */
export default function DataSplitLayout({
  list, panel = null, onClose, panelLabel, backLabel, backAction = null, panelRef = undefined,
  testId = 'data-split',
}) {
  const isMobile = useIsMobile();
  const open = Boolean(panel);

  const rootClass = open && !isMobile
    ? 'relative max-[999px]:min-h-[34rem] min-[1000px]:grid min-[1000px]:grid-cols-[minmax(0,1fr)_440px]'
    : 'relative';
  const panelClass = isMobile
    ? 'min-w-0 bg-plex-panel pb-[var(--safe-b)] pl-[var(--safe-l)] pr-[var(--safe-r)]'
    : 'absolute inset-y-0 right-0 z-20 w-[440px] max-w-full overflow-y-auto border-l border-plex-border bg-plex-panel shadow-[-12px_0_32px_rgba(0,0,0,.45)] min-[1000px]:static min-[1000px]:w-auto min-[1000px]:overflow-visible min-[1000px]:shadow-none';

  return (
    <div className={rootClass} data-testid={testId}>
      <div className="min-w-0" hidden={open && isMobile} data-testid={`${testId}-list`}>{list}</div>
      {open && !isMobile && (
        <div
          className="absolute inset-0 z-10 bg-[rgba(10,7,6,0.66)] min-[1000px]:hidden"
          aria-hidden="true"
          onClick={onClose}
          data-testid={`${testId}-scrim`}
        />
      )}
      {open && (
        <aside ref={panelRef} aria-label={panelLabel} className={panelClass} data-testid={`${testId}-panel`}>
          {isMobile && (
            <div
              className="sticky top-0 z-10 flex h-[52px] items-center justify-between gap-2 border-b border-plex-border bg-plex-panel px-2"
              data-testid={`${testId}-backbar`}
            >
              <button
                type="button"
                className="flex min-h-[44px] items-center gap-1 rounded px-2 text-sm font-medium text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
                aria-label={`Back to ${backLabel}`}
                onClick={onClose}
                data-testid={`${testId}-back`}
              >
                <span aria-hidden="true">&lsaquo;</span>
                {backLabel}
              </button>
              {backAction}
            </div>
          )}
          {panel}
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
  testId: PropTypes.string,
};
