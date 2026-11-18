import React from 'react';
import { createPortal } from 'react-dom';
import PropTypes from 'prop-types';
import { useIsMobile } from '../../hooks/useIsMobile.js';

/**
 * A one-line toast with an Undo action (find-and-favourites-plan.md F4): "Added Steel Rigg to your places." and a
 * button that takes it back. The app's only toast with an action, so it carries the accessibility rules a timed
 * action needs:
 * <ul>
 *   <li>the text is in an always-mounted {@code role="status"} region, and the buttons are OUTSIDE it, so a screen
 *       reader announces the sentence and never "Undo, button" as part of a changing live region;</li>
 *   <li>it never goes in under ten seconds, and the timer holds while the pointer or focus is on it
 *       ({@code useUndoToast}, WCAG 2.2.1);</li>
 *   <li>it is portalled to {@code <body>} above every dialog, so a sheet cannot hide the one control that undoes
 *       what the reader just did; and it takes no Escape (Escape belongs to the layer under it);</li>
 *   <li>pressing Undo or the close button hands focus back to what the reader had pressed, when it is still there.</li>
 * </ul>
 *
 * @param {object} props
 * @param {?{id: number, message: string, onUndo: ?function, returnTo: ?Element}} props.toast what to show, or null
 * @param {function(): void} props.onDismiss closes it
 * @param {function(): void} props.onPause the pointer or focus is on it
 * @param {function(): void} props.onResume the pointer and focus have left it
 */
export default function UndoToast({ toast, onDismiss, onPause, onResume }) {
  const isMobile = useIsMobile();

  /** Leaves only when focus and the pointer are both out of the toast, so tabbing Undo to ✕ never resumes. */
  const onBlur = (event) => {
    if (!event.currentTarget.contains(event.relatedTarget)) onResume();
  };
  const closeAndReturn = (action) => {
    const target = toast?.returnTo;
    action?.();
    onDismiss();
    // The buttons unmount with the toast, which would drop focus to <body>.
    if (target?.isConnected) target.focus({ preventScroll: true });
  };

  return createPortal(
    // The wrapper only listens, so the pause is the same for a mouse and a keyboard reader.
    <div
      className={`fixed left-1/2 z-[10001] flex max-w-[calc(100vw-2rem)] -translate-x-1/2 items-center gap-3 rounded-lg border border-plex-border-light bg-plex-surface-light px-4 py-2 text-sm text-plex-text shadow-lg ${
        isMobile ? 'bottom-[max(1.5rem,var(--safe-b))]' : 'bottom-6'
      } ${toast ? '' : 'pointer-events-none opacity-0'}`}
      data-testid={toast ? 'undo-toast' : 'undo-toast-idle'}
      onMouseEnter={onPause}
      onMouseLeave={onResume}
      onFocus={onPause}
      onBlur={onBlur}
    >
      <p role="status" className="m-0" data-testid="undo-toast-status">{toast?.message ?? ''}</p>
      {toast?.onUndo && (
        <button
          type="button"
          className="rounded-md border border-plex-border-light bg-transparent px-3 py-1 font-mono text-xs uppercase tracking-wider text-plex-text hover:bg-plex-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-plex-gold"
          data-testid="undo-toast-undo"
          onClick={() => closeAndReturn(toast.onUndo)}
        >
          Undo
        </button>
      )}
      {toast && (
        <button
          type="button"
          aria-label="Dismiss"
          className="rounded-md border-0 bg-transparent px-1 text-plex-text-secondary hover:text-plex-text focus-visible:outline focus-visible:outline-2 focus-visible:outline-plex-gold"
          data-testid="undo-toast-dismiss"
          onClick={() => closeAndReturn(null)}
        >
          <span aria-hidden="true">✕</span>
        </button>
      )}
    </div>,
    document.body,
  );
}

UndoToast.propTypes = {
  toast: PropTypes.shape({
    id: PropTypes.number.isRequired,
    message: PropTypes.string.isRequired,
    onUndo: PropTypes.func,
    returnTo: PropTypes.object,
  }),
  onDismiss: PropTypes.func.isRequired,
  onPause: PropTypes.func.isRequired,
  onResume: PropTypes.func.isRequired,
};
