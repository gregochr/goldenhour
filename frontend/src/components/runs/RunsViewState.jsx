import PropTypes from 'prop-types';

/**
 * The Cost and Timing views' loading skeleton: quiet pulsing blocks where the cards will be (F13).
 *
 * @param {object} props
 * @param {string} props.testId the skeleton's test id
 */
export function ViewSkeleton({ testId }) {
  return (
    <div className="flex animate-pulse flex-col gap-4 motion-reduce:animate-none" data-testid={testId} aria-busy="true">
      <p className="sr-only">Loading{'…'}</p>
      <div className="h-[110px] rounded-xl bg-plex-surface-light/60" />
      <div className="h-[300px] rounded-xl bg-plex-surface-light/60" />
      <div className="h-[260px] rounded-xl bg-plex-surface-light/60" />
    </div>
  );
}

ViewSkeleton.propTypes = { testId: PropTypes.string.isRequired };

/**
 * A view that could not load: the server's words and a Retry (F13). A refresh that fails over an answer already on
 * screen is {@link ViewStaleNote}'s, not this.
 *
 * @param {object} props
 * @param {string} props.message what went wrong
 * @param {() => void} props.onRetry asks again
 * @param {string} props.testId the box's test id; the button's is {@code {testId}-retry}
 */
export function ViewError({ message, onRetry, testId }) {
  return (
    <div className="rounded-xl border border-red-700 bg-red-900/20 p-4" data-testid={testId} role="alert">
      <p className="text-sm text-red-400">{message}</p>
      <button type="button" className="btn-secondary mt-2 text-xs max-[639px]:min-h-11" onClick={onRetry} data-testid={`${testId}-retry`}>
        Retry
      </button>
    </div>
  );
}

ViewError.propTypes = {
  message: PropTypes.string.isRequired,
  onRetry: PropTypes.func.isRequired,
  testId: PropTypes.string.isRequired,
};

/**
 * A failed refresh over an answer that is still on screen: the answer stays, this line says it is not current.
 *
 * @param {object} props
 * @param {string} props.message what went wrong
 * @param {() => void} props.onRetry asks again
 * @param {string} props.testId the line's test id
 */
export function ViewStaleNote({ message, onRetry, testId }) {
  return (
    <p className="font-mono text-[11px] text-verdict-marginal" data-testid={testId} role="status">
      Could not refresh ({message}); showing the last answer.{' '}
      <button type="button" className="cursor-pointer underline focus-visible:outline-2 focus-visible:outline-plex-gold" onClick={onRetry}>
        Retry
      </button>
    </p>
  );
}

ViewStaleNote.propTypes = {
  message: PropTypes.string.isRequired,
  onRetry: PropTypes.func.isRequired,
  testId: PropTypes.string.isRequired,
};
