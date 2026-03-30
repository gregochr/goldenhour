import PropTypes from 'prop-types';

const MAX_WIDTH = { sm: 'max-w-sm', md: 'max-w-md', lg: 'max-w-lg' };

/**
 * Shared modal overlay with a centred card panel.
 *
 * When `bare` is true, only the overlay and backdrop are rendered — children
 * must provide their own panel element. Use this for modals with custom panel
 * layouts (e.g. header/body/footer sections with their own padding).
 */
export default function Modal({
  label,
  onClose,
  maxWidth = 'md',
  bare = false,
  className = '',
  'data-testid': testId,
  children,
}) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-label={label}
      data-testid={testId}
    >
      <div
        className="absolute inset-0 bg-black/60"
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
  className: PropTypes.string,
  'data-testid': PropTypes.string,
  children: PropTypes.node.isRequired,
};
