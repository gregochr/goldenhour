import React from 'react';
import PropTypes from 'prop-types';

/**
 * Catches a failed lazy chunk (a rejected {@code import()}: a stale tab after a deploy, a flaky or
 * offline connection) so it takes down only the surface that wanted the chunk, not the whole app.
 *
 * <p>React unmounts the entire root for an uncaught render error, and a rejected {@code lazy()} import
 * is rethrown at render. Put this OUTSIDE the {@code Suspense} that wraps the lazy component. A
 * {@code lazy()} caches its rejection, so retrying in place cannot work: the way out is a reload (and
 * {@code onClose}, for a dialog that can simply be dismissed).
 *
 * <p>A class because React has no hook form of an error boundary.
 *
 * @param {object} props
 * @param {React.ReactNode} props.children the lazily loaded surface (inside its own Suspense)
 * @param {string} [props.label] what failed to load, for the heading
 * @param {'page'|'overlay'|'inline'} [props.variant] `page` fills the screen (auth pages), `overlay`
 *        is a modal dialog over a scrim (settings, Ask sheet), `inline` is a compact block (Ask dock)
 * @param {function} [props.onClose] offers a Close control that calls this
 * @param {function} [props.reload] defaults to reloading the page (overridable for tests: jsdom
 *        cannot stub {@code window.location.reload})
 * @param {string} [props.id] DOM id for the inline variant (the Ask dock's, for {@code aria-controls})
 */
export default class ChunkErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { failed: false };
    this.handleReload = this.handleReload.bind(this);
  }

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error) {
    console.error('Lazy chunk failed to load', error);
  }

  handleReload() {
    const { reload } = this.props;
    if (reload) reload();
    else window.location.reload();
  }

  render() {
    const {
      children, label = 'This part of the app', variant = 'inline', onClose = undefined, id = undefined,
    } = this.props;
    const { failed } = this.state;
    if (!failed) return children;

    const body = (
      <div className="flex flex-col items-center gap-3 text-center p-6" data-testid="chunk-error">
        <p className="text-plex-text">{`Couldn't load — ${label} didn't arrive.`}</p>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={this.handleReload}
            data-testid="chunk-error-reload"
            className="border border-plex-border text-plex-text px-3 py-1 rounded"
          >
            Reload
          </button>
          {onClose && (
            <button
              type="button"
              onClick={onClose}
              data-testid="chunk-error-close"
              className="border border-plex-border text-plex-text-muted px-3 py-1 rounded"
            >
              Close
            </button>
          )}
        </div>
      </div>
    );

    if (variant === 'page') {
      return <div className="min-h-screen flex items-center justify-center">{body}</div>;
    }
    if (variant === 'overlay') {
      return (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={label}
          className="fixed inset-0 z-50 flex items-center justify-center"
        >
          <div className="app-scrim absolute inset-0" />
          <div className="relative bg-plex-surface border border-plex-border rounded-lg">{body}</div>
        </div>
      );
    }
    return <aside id={id} className="wf-ask-dock" aria-label={label}>{body}</aside>;
  }
}

ChunkErrorBoundary.propTypes = {
  children: PropTypes.node.isRequired,
  label: PropTypes.string,
  variant: PropTypes.oneOf(['page', 'overlay', 'inline']),
  onClose: PropTypes.func,
  reload: PropTypes.func,
  id: PropTypes.string,
};
