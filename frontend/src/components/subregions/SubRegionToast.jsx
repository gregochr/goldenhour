import { useEffect } from 'react';
import { createPortal } from 'react-dom';
import PropTypes from 'prop-types';

/** How long the toast stays up. */
const TOAST_MS = 5000;

/**
 * A one-line confirmation toast (handoff A6): "Sub-regions saved." or the same with the next-change date.
 * A polite status, portalled to {@code <body>} so it clears whichever dialog is open, and gone after five
 * seconds. Renders nothing without a message.
 *
 * @param {object} props
 * @param {string|null} props.message the line to show
 * @param {() => void} props.onDone called when it times out
 */
export default function SubRegionToast({ message, onDone }) {
  useEffect(() => {
    if (!message) return undefined;
    const timer = setTimeout(onDone, TOAST_MS);
    return () => clearTimeout(timer);
  }, [message, onDone]);

  if (!message) return null;
  return createPortal(
    <div
      role="status"
      className="fixed bottom-6 left-1/2 -translate-x-1/2 z-[10001] max-w-[calc(100vw-2rem)] rounded-lg border border-plex-border-light bg-plex-surface-light px-4 py-2 text-sm text-plex-text shadow-lg"
      data-testid="sub-region-toast"
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
