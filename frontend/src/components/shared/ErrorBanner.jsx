import PropTypes from 'prop-types';

/**
 * Shared error banner — renders nothing when message is falsy.
 */
export default function ErrorBanner({ message, className = '', 'data-testid': testId }) {
  if (!message) return null;

  return (
    <div className={`bg-red-900/20 border border-red-700 rounded-lg p-4 ${className}`.trim()} data-testid={testId}>
      <p className="text-red-400 text-sm">{message}</p>
    </div>
  );
}

ErrorBanner.propTypes = {
  message: PropTypes.string,
  className: PropTypes.string,
  'data-testid': PropTypes.string,
};
