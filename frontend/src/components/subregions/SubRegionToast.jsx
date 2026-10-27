import { useEffect } from 'react';
import { createPortal } from 'react-dom';
import PropTypes from 'prop-types';
import { useIsMobile } from '../../hooks/useIsMobile.js';

/** How long the toast stays up. */
const TOAST_MS = 5000;

/**
 * A one-line confirmation toast (handoff A6): "Sub-regions saved." or the same with the next-change date.
 * A polite status, portalled to {@code <body>} so it clears whichever dialog is open, and gone after five
 * seconds. The {@code role="status"} region stays mounted (empty and invisible) and the text is written into
 * it, because a live region that appears already holding its text is announced unreliably. It sits at the top
 * of the screen so it never covers a control at the foot of Settings (the Danger zone).
 *
 * @param {object} props
 * @param {string|null} props.message the line to show
 * @param {() => void} props.onDone called when it times out
 */
export default function SubRegionToast({ message, onDone }) {
  // On a phone the top of the screen is the dialog's title; the toast goes to the foot, above the safe area.
  const isMobile = useIsMobile();
  useEffect(() => {
    if (!message) return undefined;
    const timer = setTimeout(onDone, TOAST_MS);
    return () => clearTimeout(timer);
  }, [message, onDone]);

  return createPortal(
    <div
      role="status"
      className={`fixed ${isMobile ? 'bottom-[max(1.5rem,var(--safe-b))]' : 'top-4'} left-1/2 -translate-x-1/2 z-[10001] max-w-[calc(100vw-2rem)] rounded-lg border border-plex-border-light bg-plex-surface-light px-4 py-2 text-sm text-plex-text shadow-lg ${
        message ? '' : 'opacity-0 pointer-events-none'
      }`}
      data-testid={message ? 'sub-region-toast' : 'sub-region-toast-region'}
    >
      {message}
    </div>,
    document.body,
  );
}

SubRegionToast.propTypes = {
  message: PropTypes.string,
  onDone: PropTypes.func.isRequired,
};
