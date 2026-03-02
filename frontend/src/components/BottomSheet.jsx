import React, { useEffect } from 'react';
import PropTypes from 'prop-types';

/**
 * Mobile bottom sheet overlay. Slides up from the bottom of the viewport
 * with a semi-transparent backdrop. Tap the backdrop or close button to dismiss.
 *
 * @param {object} props
 * @param {boolean} props.open - Whether the sheet is visible.
 * @param {function} props.onClose - Called when the user dismisses the sheet.
 * @param {React.ReactNode} props.children - Content rendered inside the sheet.
 */
export default function BottomSheet({ open, onClose, children }) {
  // Prevent body scroll while open
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, [open]);

  if (!open) return null;

  return (
    <div data-testid="bottom-sheet-root">
      {/* Backdrop */}
      <div
        data-testid="bottom-sheet-overlay"
        className="fixed inset-0 z-50 bg-black/50"
        onClick={onClose}
      />

      {/* Sheet */}
      <div
        role="dialog"
        aria-modal="true"
        data-testid="bottom-sheet"
        className="fixed bottom-0 left-0 right-0 z-50 rounded-t-2xl bg-plex-surface border-t border-plex-border animate-slide-up"
        style={{ maxHeight: '60vh' }}
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
    </div>
  );
}

BottomSheet.propTypes = {
  open: PropTypes.bool.isRequired,
  onClose: PropTypes.func.isRequired,
  children: PropTypes.node,
};
