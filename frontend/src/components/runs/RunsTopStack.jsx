import PropTypes from 'prop-types';
import ErrorBanner from '../shared/ErrorBanner.jsx';

/**
 * What sits above the Runs views, in one fixed order (runs-deep-dive-plan.md F3): the run-progress panel, then the
 * load error, then the running-cycle banner. The banner is the owner's: it stays above the view tabs on every view.
 *
 * @param {object} props
 * @param {import('react').ReactNode} [props.progress] the live run-progress panel
 * @param {string|null} [props.error] the live poll's load error
 * @param {import('react').ReactNode} [props.liveStrip] the running cycle's strip
 */
export default function RunsTopStack({ progress = null, error = null, liveStrip = null }) {
  return (
    <>
      {progress}
      <ErrorBanner message={error} data-testid="runs-error" />
      {liveStrip}
    </>
  );
}

RunsTopStack.propTypes = {
  progress: PropTypes.node,
  error: PropTypes.string,
  liveStrip: PropTypes.node,
};
