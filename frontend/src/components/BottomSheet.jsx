import React, { useEffect } from 'react';
import { createPortal } from 'react-dom';
import PropTypes from 'prop-types';
import useDialogFocus from '../hooks/useDialogFocus.js';

/**
 * Mobile bottom sheet overlay. Slides up from the bottom of the viewport
 * with a semi-transparent backdrop. Tap the backdrop or close button to dismiss.
 *
 * @param {object} props
 * @param {boolean} props.open - Whether the sheet is visible.
 * @param {function} props.onClose - Called when the user dismisses the sheet.
 * @param {string} [props.label] - The sheet's accessible name.
 * @param {React.ReactNode} props.children - Content rendered inside the sheet.
 */
export default function BottomSheet({ open, onClose, label = 'Details', children }) {
  // Prevent body scroll while open
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, [open]);

  // Gated on `open`, unlike Modal's — this component returns null rather than unmounting, so the
  // hook has to be told when the sheet is actually on screen.
  const dialogRef = useDialogFocus(open);

  if (!open) return null;

  return createPortal(
    <div data-testid="bottom-sheet-root">
      {/* Backdrop */}
      <div
        data-testid="bottom-sheet-overlay"
        className="fixed inset-0 bg-black/50"
        style={{ zIndex: 9999 }}
        role="button"
        tabIndex={-1}
        aria-label="Close"
        onClick={onClose}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') onClose(); }}
      />

      {/* Sheet */}
      <div
        ref={dialogRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        // Named at last. A role="dialog" with no accessible name announces as "dialog" and nothing
        // else, so a screen-reader user was told something had opened but not what.
        aria-label={label}
        data-testid="bottom-sheet"
        className="fixed bottom-0 left-0 right-0 rounded-t-2xl bg-plex-surface border-t border-plex-border animate-slide-up focus:outline-none"
        style={{ zIndex: 10000, maxHeight: '60vh' }}
      >
        {/* Drag handle */}
        <div className="flex justify-center pt-2 pb-1">
          <div className="w-10 h-1 rounded-full bg-plex-border" />
        </div>

        {/* Close button */}
        <button
          data-testid="bottom-sheet-close"
          onClick={onClose}
          className="absolute top-2 right-3 w-8 h-8 flex items-center justify-center rounded-full text-plex-text-muted hover:text-plex-text transition-colors"
          aria-label="Close"
        >
          &#x2715;
        </button>

        {/* Scrollable content */}
        <div className="overflow-y-auto px-4 pb-6" style={{ maxHeight: 'calc(60vh - 40px)' }}>
          {children}
        </div>
      </div>
    </div>,
    document.body,
  );
}

BottomSheet.propTypes = {
  open: PropTypes.bool.isRequired,
  onClose: PropTypes.func.isRequired,
  label: PropTypes.string,
  children: PropTypes.node,
};
