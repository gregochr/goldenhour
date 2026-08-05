import { useEffect } from 'react';
import PropTypes from 'prop-types';
import useDialogFocus from '../../hooks/useDialogFocus.js';

const MAX_WIDTH = { sm: 'max-w-sm', md: 'max-w-md', lg: 'max-w-lg' };

/**
 * Shared modal overlay with a centred card panel.
 *
 * <p>When `bare` is true, only the overlay and backdrop are rendered — children must provide their
 * own panel element. Use this for modals with header/body/footer sections that own their padding.
 *
 * <h2>Focus</h2>
 *
 * <p>The dialog takes focus when it opens and hands it back to whatever had it when it closes —
 * see {@link useDialogFocus}, which also records why this is not a focus trap. Before that, `role`
 * and `aria-modal` were the whole of the implementation, and they are semantics: a keyboard user
 * had to Tab through the entire page behind the backdrop to reach a dialog that had just opened
 * over it.
 *
 * <h2>Escape is opt-in, and that is not timidity</h2>
 *
 * <p>The handler this replaces sat on the backdrop — an empty `div` with no `tabIndex` and no
 * children, so it could never be the keydown target nor an ancestor of one. It existed to satisfy
 * a lint rule about click handlers, and it never fired. Escape now works, but only where a caller
 * asks for it, because eleven of the fifteen render sites hold state a reader would lose: four
 * carry unsaved forms, one holds a generated password that has to be copied before it is gone, and
 * one is a deliberately unclosable spinner. Turning dismissal on everywhere would have converted a
 * dead handler into a data-loss handler.
 *
 * @param {object}   props
 * @param {string}   props.label            the dialog's accessible name
 * @param {Function} [props.onClose]        omit to make the dialog unclosable (the refresh spinner
 *                                          does exactly this)
 * @param {boolean}  [props.closeOnEscape]  opt in where dismissal loses nothing
 */
export default function Modal({
  label,
  onClose,
  maxWidth = 'md',
  bare = false,
  closeOnEscape = false,
  className = '',
  'data-testid': testId,
  children,
}) {
  const dialogRef = useDialogFocus(true);

  useEffect(() => {
    if (!closeOnEscape || !onClose) return undefined;
    // Document-level: the dialog root is focusable but its descendants are where a reader actually
    // is, and keydown from a portalled child would not bubble through this subtree at all.
    const onKeyDown = (e) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [closeOnEscape, onClose]);

  return (
    <div
      ref={dialogRef}
      tabIndex={-1}
      className="fixed inset-0 z-50 flex items-center justify-center p-4 focus:outline-none"
      role="dialog"
      aria-modal="true"
      aria-label={label}
      data-testid={testId}
    >
      <div
        className="absolute inset-0 bg-black/60"
        role="presentation"
        onClick={onClose}
        data-testid={testId ? `${testId}-backdrop` : undefined}
      />
      {bare ? (
        children
      ) : (
        <div
          className={`relative bg-plex-surface border border-plex-border rounded-xl shadow-2xl p-6 w-full ${MAX_WIDTH[maxWidth]} flex flex-col gap-4 ${className}`.trim()}
        >
          {children}
        </div>
      )}
    </div>
  );
}

Modal.propTypes = {
  label: PropTypes.string.isRequired,
  onClose: PropTypes.func,
  maxWidth: PropTypes.oneOf(['sm', 'md', 'lg']),
  bare: PropTypes.bool,
  closeOnEscape: PropTypes.bool,
  className: PropTypes.string,
  'data-testid': PropTypes.string,
  children: PropTypes.node.isRequired,
};
